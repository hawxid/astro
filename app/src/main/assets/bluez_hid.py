#!/usr/bin/env python3
"""Astro BlueZ HID device: the phone acts as a Bluetooth keyboard+mouse.

Runs inside the Debian chroot, on top of the bluebinder/VHCI controller
(hci0). Uses bluetoothd over the D-Bus system bus: registers a HID profile
(SDP record) + an auto-accept agent, then forwards HID reports from the
Astro app to the paired host over the HID interrupt channel.

Control channel: TCP 127.0.0.1:18923, newline-delimited JSON.
  -> {"type":"key","mod":0,"code":41}   keyboard press+release
  -> {"type":"move","dx":3,"dy":-2}     relative mouse move
  -> {"type":"btn","button":1,"pressed":true}  button bitmask 1/2/4
  -> {"type":"wheel","delta":1}
  -> {"type":"status"}  <- {"status":"waiting|connected","host":...}
  -> {"type":"quit"}    cleanup + exit
  <- {"status":...} sent on every state change.

Modes: --check prints an environment report (JSON) and exits.
Requires: bluetoothd, D-Bus system bus, python3-dbus, python3-gi, hci0 up.
HIDP references: HID profile spec 7.4 (SET_PROTOCOL -> Handshake 0x00,
GET_PROTOCOL -> DATA/Other + protocol byte 0x01 Report, DATA input = 0xA1).
"""

import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time

CONTROL_PORT = 18923
ADAPTER_PATH = "/org/bluez/hci0"
PROFILE_PATH = "/astro/hid"
AGENT_PATH = "/astro/agent"
DEVICE_NAME = "Astro Keys"
HID_UUID = "00001124-0000-1000-8000-00805f9b34fb"
CLASS_KEYBOARD = 0x002540

# Combined report descriptor: keyboard (report ID 1) + mouse (report ID 2).
# Built from the same descriptors the USB gadget uses, with 85 01 / 85 02
# inserted after the first collection start.
KB_DESC_HEX = (
    "05010906a1018501050719e029e715002501750195088102950175088101"
    "950575010508190129059102950175039101950675081500256505071900"
    "29658100c0"
)
MOUSE_DESC_HEX = (
    "05010902a10185020901a100050919012903150025019503750181029501"
    "75058103050109300931093815018127950375088106c0c0"
)
REPORT_DESC_HEX = KB_DESC_HEX + MOUSE_DESC_HEX


def log(msg):
    sys.stderr.write("[bluez_hid] %s\n" % msg)
    sys.stderr.flush()


def build_sdp_record():
    return """<record>
  <attribute id="0x0001">
    <sequence><uuid value="0x1124"/></sequence>
  </attribute>
  <attribute id="0x0004">
    <sequence>
      <sequence><uuid value="0x0100"/><uint16 value="0x0011"/></sequence>
      <sequence><uuid value="0x0011"/></sequence>
    </sequence>
  </attribute>
  <attribute id="0x0005">
    <sequence>
      <sequence><uuid value="0x0100"/><uint16 value="0x0013"/></sequence>
      <sequence><uuid value="0x0011"/></sequence>
    </sequence>
  </attribute>
  <attribute id="0x0009">
    <sequence>
      <sequence><uuid value="0x1124"/><uint16 value="0x0100"/></sequence>
    </sequence>
  </attribute>
  <attribute id="0x0100">
    <text value="Astro Keys"/>
  </attribute>
  <attribute id="0x0200"><uint16 value="0x0111"/></attribute>
  <attribute id="0x0201"><uint16 value="0x0040"/></attribute>
  <attribute id="0x0202"><uint16 value="0x0000"/></attribute>
  <attribute id="0x0203"><boolean value="true"/></attribute>
  <attribute id="0x0204"><boolean value="true"/></attribute>
  <attribute id="0x0205">
    <sequence>
      <sequence><uint8 value="0x22"/><text encoding="hex" value="%s"/></sequence>
    </sequence>
  </attribute>
  <attribute id="0x0206">
    <sequence>
      <sequence><uint16 value="0x0409"/><uint16 value="0x0100"/></sequence>
    </sequence>
  </attribute>
  <attribute id="0x0207"><boolean value="false"/></attribute>
  <attribute id="0x0208"><boolean value="true"/></attribute>
  <attribute id="0x0209"><boolean value="true"/></attribute>
  <attribute id="0x020a"><uint16 value="0x0100"/></attribute>
  <attribute id="0x020b"><uint16 value="0x0c80"/></attribute>
  <attribute id="0x020c"><boolean value="true"/></attribute>
  <attribute id="0x020d"><boolean value="true"/></attribute>
</record>""" % REPORT_DESC_HEX


def run_quiet(cmd):
    try:
        return subprocess.run(cmd, shell=True, capture_output=True,
                              text=True, timeout=15)
    except Exception as e:
        log("run failed %r: %s" % (cmd, e))
        return None


def run_daemon(cmd):
    """Start a daemon without waiting on its pipes.

    run_quiet() with capture_output would block until the daemon exits
    (the child inherits the pipes), so daemons must be spawned detached
    with stdio to DEVNULL instead.
    """
    try:
        subprocess.Popen(cmd, shell=True, stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
        return True
    except Exception as e:
        log("daemon start failed %r: %s" % (cmd, e))
        return False


def do_check():
    report = {}
    report["python"] = sys.version.split()[0]
    try:
        import dbus  # noqa: F401
        report["python_dbus"] = True
    except Exception as e:
        report["python_dbus"] = "missing: %s" % e
    try:
        import gi  # noqa: F401
        from gi.repository import GLib  # noqa: F401
        report["python_gi"] = True
    except Exception as e:
        report["python_gi"] = "missing: %s" % e
    report["bluetoothd"] = shutil.which("bluetoothd") or "missing"
    report["bluetoothctl"] = shutil.which("bluetoothctl") or "missing"
    report["hciconfig"] = shutil.which("hciconfig") or "missing"
    for sock in ("/run/dbus/system_bus_socket",
                 "/var/run/dbus/system_bus_socket"):
        report["dbus_socket:" + sock] = os.path.exists(sock)
    r = run_quiet("pgrep -f 'bluetooth[d]' || true")
    report["bluetoothd_running"] = bool(r and r.stdout.strip())
    r = run_quiet("pgrep -f 'bluebinde[r]' || true")
    report["bluebinder_running"] = bool(r and r.stdout.strip())
    r = run_quiet("hciconfig")
    out = r.stdout if r else ""
    report["hci0_present"] = "hci0:" in out
    report["hci0_up"] = "UP RUNNING" in out
    report["report_desc_bytes"] = len(bytes.fromhex(REPORT_DESC_HEX))
    print(json.dumps(report, indent=2))
    return 0


# ---------------------------------------------------------------- server ---

class HidServer:
    """Owns the D-Bus objects, the HID channels and the control socket."""

    def __init__(self):
        import dbus
        import dbus.service
        import dbus.mainloop.glib
        dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
        self.dbus = dbus
        # NOTE: do NOT connect the bus here. The system daemon may not be
        # running yet; serve() starts dbus-daemon first, then connects.
        self.bus = None
        self.adapter_path = ADAPTER_PATH
        self.loop = None
        self.state = "waiting"  # waiting | connected
        self.host = None
        self.protocol = 1  # 1 = Report (default), 0 = Boot
        self.held_buttons = 0
        self.last_kb = bytes(8)
        self.last_mouse = bytes(4)
        self.ctrl_sock = None
        self.int_sock = None
        self.ctrl_reader = None
        self.control_conn = None
        self.control_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.profile = None
        self.agent = None

    # ---------------------------------------------------------- status ---
    def push_status(self):
        with self.state_lock:
            payload = {"status": self.state, "host": self.host}
        self.send_to_app(payload)

    def send_to_app(self, payload):
        conn = self.control_conn
        if conn is None:
            return
        try:
            with self.control_lock:
                conn.sendall((json.dumps(payload) + "\n").encode())
        except Exception:
            pass

    def set_state(self, state, host=None):
        with self.state_lock:
            self.state = state
            self.host = host
        log("state -> %s host=%s" % (state, host))
        self.push_status()

    # ------------------------------------------------------------ HIDP ---
    def send_interrupt(self, data):
        sock = self.int_sock
        if sock is None:
            return False
        try:
            sock.sendall(data)
            return True
        except Exception as e:
            log("interrupt send failed: %s" % e)
            self.on_host_gone()
            return False

    def send_key(self, mod, code):
        report = bytes([mod & 0xFF, 0] + [code & 0xFF] + [0] * 5)
        self.last_kb = report
        if not self.send_interrupt(bytes([0xA1, 0x01]) + report):
            return
        self.send_interrupt(bytes([0xA1, 0x01]) + bytes(8))

    def send_mouse_move(self, dx, dy):
        report = bytes([self.held_buttons & 0xFF,
                        dx & 0xFF, dy & 0xFF, 0])
        self.last_mouse = report
        self.send_interrupt(bytes([0xA1, 0x02]) + report)

    def send_mouse_button(self, button, pressed):
        if pressed:
            self.held_buttons |= button
        else:
            self.held_buttons &= ~button
        report = bytes([self.held_buttons & 0xFF, 0, 0, 0])
        self.last_mouse = report
        self.send_interrupt(bytes([0xA1, 0x02]) + report)

    def send_mouse_click(self, button):
        self.send_mouse_button(button, True)
        self.send_mouse_button(button, False)

    def send_wheel(self, delta):
        report = bytes([self.held_buttons & 0xFF, 0, 0, delta & 0xFF])
        self.last_mouse = report
        self.send_interrupt(bytes([0xA1, 0x02]) + report)

    def handle_control_packet(self, data):
        """One HIDP transaction from the host on the control channel."""
        if not data:
            return
        header = data[0]
        txn = header & 0xF0
        if txn == 0x70:  # SET_PROTOCOL
            if len(data) >= 2:
                self.protocol = data[1] & 0x01
            self.reply_ctrl(bytes([0x00]))
        elif txn == 0x60:  # GET_PROTOCOL -> DATA/Other + protocol byte
            self.reply_ctrl(bytes([0xA0, self.protocol & 0x01]))
        elif txn == 0x90:  # SET_IDLE
            self.reply_ctrl(bytes([0x00]))
        elif txn == 0x80:  # GET_IDLE -> DATA/Other + idle byte
            self.reply_ctrl(bytes([0xA0, 0x00]))
        elif txn == 0x10:  # HID_CONTROL (incl. VIRTUAL_CABLE_UNPLUG)
            self.reply_ctrl(bytes([0x00]))
            if len(data) >= 2 and (data[1] & 0x0F) == 0x05:
                self.on_host_gone()
        elif txn == 0x40:  # GET_REPORT
            rtype = header & 0x03
            rid = data[1] if len(data) >= 2 else 0
            if rtype == 1 and rid == 1:
                self.reply_ctrl(bytes([0xA1, 0x01]) + self.last_kb)
            elif rtype == 1 and rid == 2:
                self.reply_ctrl(bytes([0xA1, 0x02]) + self.last_mouse)
            else:
                self.reply_ctrl(bytes([0x03]))  # unsupported request
        elif txn == 0x50:  # SET_REPORT (LEDs etc.) -> accept, ignore
            self.reply_ctrl(bytes([0x00]))
        else:
            self.reply_ctrl(bytes([0x03]))

    def reply_ctrl(self, data):
        sock = self.ctrl_sock
        if sock is None:
            return
        try:
            sock.sendall(data)
        except Exception as e:
            log("control reply failed: %s" % e)
            self.on_host_gone()

    def control_reader_loop(self):
        sock = self.ctrl_sock
        while sock is not None:
            try:
                data = sock.recv(64)
            except Exception:
                break
            if not data:
                break
            self.handle_control_packet(data)
        self.on_host_gone()

    def on_new_connection(self, device_path, fd, properties):
        try:
            raw = fd.take()
        except Exception:
            raw = int(fd)
        psm = None
        try:
            psm = int(properties.get("PSM", 0))
        except Exception:
            pass
        sock = socket.socket(fileno=raw)
        log("NewConnection device=%s psm=%s" % (device_path, psm))
        if psm == 0x11:
            self.ctrl_sock = sock
            if self.ctrl_reader is not None and self.ctrl_reader.is_alive():
                pass
            self.ctrl_reader = threading.Thread(
                target=self.control_reader_loop, daemon=True)
            self.ctrl_reader.start()
        elif psm == 0x13:
            self.int_sock = sock
        else:
            log("unknown PSM %s, closing" % psm)
            try:
                sock.close()
            except Exception:
                pass
            return
        if self.ctrl_sock is not None and self.int_sock is not None:
            host = device_path.split("/")[-1].replace("_", ":")
            self.set_state("connected", host)
            self.set_discoverable(False)

    def on_host_gone(self):
        if self.state == "connected":
            self.set_state("waiting", None)
            self.set_discoverable(True)
        for attr in ("ctrl_sock", "int_sock"):
            sock = getattr(self, attr)
            if sock is not None:
                try:
                    sock.close()
                except Exception:
                    pass
            setattr(self, attr, None)

    # --------------------------------------------------------- adapter ---
    def adapter_iface(self):
        return self.dbus.Interface(
            self.bus.get_object("org.bluez", self.adapter_path),
            "org.bluez.Adapter1")

    def props_iface(self):
        return self.dbus.Interface(
            self.bus.get_object("org.bluez", self.adapter_path),
            "org.freedesktop.DBus.Properties")

    def set_adapter_prop(self, name, value):
        try:
            self.props_iface().Set("org.bluez.Adapter1", name, value)
        except Exception as e:
            log("set %s failed: %s" % (name, e))

    def set_discoverable(self, on):
        self.set_adapter_prop("Discoverable", self.dbus.Boolean(on))
        self.set_adapter_prop("Pairable", self.dbus.Boolean(True))

    def ensure_bus(self):
        """Start dbus-daemon if needed, then connect the system bus."""
        sock_paths = ("/run/dbus/system_bus_socket",
                      "/var/run/dbus/system_bus_socket")
        if not any(os.path.exists(p) for p in sock_paths):
            log("no dbus system socket; trying to start dbus-daemon")
            if shutil.which("dbus-daemon") is None:
                log("FATAL: dbus-daemon binary missing (apt install dbus)")
                return False
            run_quiet("mkdir -p /run/dbus")
            run_daemon("dbus-daemon --system")
            time.sleep(1)
        try:
            self.bus = self.dbus.SystemBus()
            return True
        except Exception as e:
            log("FATAL: SystemBus connect failed: %s" % e)
            return False

    def ensure_stack(self):
        """Make sure bluetoothd is up, then pick the live adapter.

        bluetoothd's own input plugin (HID host) already owns UUID 0x1124,
        so our device profile registration is rejected with "UUID already
        registered" unless bluetoothd runs with --noplugin=input. Restart
        it with that flag when needed; nothing else in the chroot uses
        bluetoothd as a HID host, so this is safe here.
        """
        r = run_quiet("pgrep bluetoothd || true")
        pids = [l.strip() for l in (r.stdout.splitlines() if r else [])
                if l.strip()]
        has_noinput = False
        for pid in pids:
            try:
                with open("/proc/%s/cmdline" % pid, "rb") as f:
                    if b"noplugin=input" in f.read():
                        has_noinput = True
            except Exception:
                pass
        if pids and not has_noinput:
            log("bluetoothd running without --noplugin=input; restarting it")
            run_quiet("pkill bluetoothd")
            for _ in range(5):
                time.sleep(1)
                r2 = run_quiet("pgrep bluetoothd || true")
                if not (r2 and r2.stdout.strip()):
                    break
        if not pids or not has_noinput:
            log("starting bluetoothd --noplugin=input")
            run_quiet("mkdir -p /var/lib/bluetooth")
            run_daemon("bluetoothd --noplugin=input")
            time.sleep(2)
        # Wait for an adapter object to appear; prefer hci0, else first.
        for _ in range(15):
            try:
                om = self.dbus.Interface(
                    self.bus.get_object("org.bluez", "/"),
                    "org.freedesktop.DBus.ObjectManager")
                objects = om.GetManagedObjects()
                adapters = sorted(p for p in objects
                                  if p.startswith("/org/bluez/hci"))
                if adapters:
                    if len(adapters) > 1:
                        log("WARNING: multiple adapters %s" % adapters)
                    self.adapter_path = (ADAPTER_PATH if ADAPTER_PATH in adapters
                                         else adapters[0])
                    log("using adapter %s" % self.adapter_path)
                    return True
            except Exception as e:
                log("object manager wait: %s" % e)
            time.sleep(1)
        return False

    def setup_adapter(self):
        self.set_adapter_prop("Alias", DEVICE_NAME)
        self.set_adapter_prop("Powered", self.dbus.Boolean(True))
        # No timeouts: the default DiscoverableTimeout is 180s, after
        # which the phone vanishes from host scans mid-session. Zero
        # disables the timeout (BlueZ Adapter API); cleanup turns both
        # off again on disconnect, so visibility stays session-bounded.
        self.set_adapter_prop("DiscoverableTimeout", self.dbus.UInt32(0))
        self.set_adapter_prop("PairableTimeout", self.dbus.UInt32(0))
        # Adapter1.Class is read-only over D-Bus (device-proven) — set it
        # via hciconfig only.
        hci = self.adapter_path.split("/")[-1]
        run_quiet("hciconfig %s class 0x002540" % hci)
        run_quiet("hciconfig %s up" % hci)
        self.set_discoverable(True)

    # ------------------------------------------------------------ main ---
    def register_objects(self):
        dbus = self.dbus
        import dbus.service

        server = self

        class HidProfile(dbus.service.Object):
            def __init__(self, bus, path):
                super().__init__(bus, path)

            @dbus.service.method("org.bluez.Profile1",
                                 in_signature="oha{sv}", out_signature="")
            def NewConnection(self, device, fd, properties):
                server.on_new_connection(device, fd, properties)

            @dbus.service.method("org.bluez.Profile1",
                                 in_signature="", out_signature="")
            def Release(self):
                log("profile released")

            @dbus.service.method("org.bluez.Profile1",
                                 in_signature="o", out_signature="")
            def RequestDisconnection(self, device):
                server.on_host_gone()

        class HidAgent(dbus.service.Object):
            def __init__(self, bus, path):
                super().__init__(bus, path)

            @dbus.service.method("org.bluez.Agent1",
                                 in_signature="", out_signature="")
            def Release(self):
                log("agent released")

            @dbus.service.method("org.bluez.Agent1",
                                 in_signature="o", out_signature="s")
            def RequestPinCode(self, device):
                # Agent is asked to PROVIDE a PIN; we refuse PIN pairing.
                raise dbus.exceptions.DBusException(
                    "org.bluez.Error.Rejected")

            @dbus.service.method("org.bluez.Agent1",
                                 in_signature="ou", out_signature="")
            def DisplayPinCode(self, device, pincode):
                pass

            @dbus.service.method("org.bluez.Agent1",
                                 in_signature="o", out_signature="u")
            def RequestPasskey(self, device):
                # Agent is asked to PROVIDE a passkey; we refuse it.
                raise dbus.exceptions.DBusException(
                    "org.bluez.Error.Rejected")

            @dbus.service.method("org.bluez.Agent1",
                                 in_signature="ouu", out_signature="")
            def DisplayPasskey(self, device, passkey, entered):
                pass

            @dbus.service.method("org.bluez.Agent1",
                                 in_signature="ou", out_signature="")
            def RequestConfirmation(self, device, passkey):
                pass

            @dbus.service.method("org.bluez.Agent1",
                                 in_signature="o", out_signature="")
            def RequestAuthorization(self, device):
                pass

            @dbus.service.method("org.bluez.Agent1",
                                 in_signature="os", out_signature="")
            def AuthorizeService(self, device, uuid):
                pass

            @dbus.service.method("org.bluez.Agent1",
                                 in_signature="", out_signature="")
            def Cancel(self):
                pass

        self.profile = HidProfile(self.bus, PROFILE_PATH)
        self.agent = HidAgent(self.bus, AGENT_PATH)
        pm = dbus.Interface(
            self.bus.get_object("org.bluez", "/org/bluez"),
            "org.bluez.ProfileManager1")
        pm.RegisterProfile(PROFILE_PATH, HID_UUID, {
            "ServiceRecord": build_sdp_record(),
            "Role": "server",
            "RequireAuthentication": dbus.Boolean(True),
            "RequireAuthorization": dbus.Boolean(False),
            "AutoConnect": dbus.Boolean(False),
        })
        am = dbus.Interface(
            self.bus.get_object("org.bluez", "/org/bluez"),
            "org.bluez.AgentManager1")
        am.RegisterAgent(AGENT_PATH, "NoInputNoOutput")
        am.RequestDefaultAgent(AGENT_PATH)
        log("profile + agent registered")

    def cleanup(self):
        try:
            self.set_discoverable(False)
        except Exception:
            pass
        try:
            pm = self.dbus.Interface(
                self.bus.get_object("org.bluez", "/org/bluez"),
                "org.bluez.ProfileManager1")
            pm.UnregisterProfile(PROFILE_PATH)
        except Exception:
            pass
        try:
            am = self.dbus.Interface(
                self.bus.get_object("org.bluez", "/org/bluez"),
                "org.bluez.AgentManager1")
            am.UnregisterAgent(AGENT_PATH)
        except Exception:
            pass
        self.on_host_gone()

    def watchdog_loop(self):
        while True:
            time.sleep(45)
            if self.state == "waiting":
                try:
                    self.set_adapter_prop(
                        "Discoverable", self.dbus.Boolean(True))
                except Exception:
                    pass

    def handle_control_line(self, line):
        try:
            msg = json.loads(line)
        except Exception:
            return
        mtype = msg.get("type")
        if mtype == "key":
            self.send_key(int(msg.get("mod", 0)), int(msg.get("code", 0)))
        elif mtype == "move":
            self.send_mouse_move(int(msg.get("dx", 0)),
                                 int(msg.get("dy", 0)))
        elif mtype == "btn":
            self.send_mouse_button(int(msg.get("button", 1)),
                                   bool(msg.get("pressed", False)))
        elif mtype == "wheel":
            self.send_wheel(int(msg.get("delta", 0)))
        elif mtype == "status":
            self.push_status()
        elif mtype == "quit":
            raise SystemExit(0)

    def control_accept_loop(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", CONTROL_PORT))
        srv.listen(2)
        log("control listening on 127.0.0.1:%d" % CONTROL_PORT)
        while True:
            conn, _ = srv.accept()
            with self.control_lock:
                old = self.control_conn
                self.control_conn = conn
            if old is not None:
                try:
                    old.close()
                except Exception:
                    pass
            log("app connected")
            self.push_status()
            try:
                buf = b""
                while True:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
                    buf += chunk
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        self.handle_control_line(line.decode(errors="replace"))
            except SystemExit:
                raise
            except Exception as e:
                log("control connection error: %s" % e)
            finally:
                with self.control_lock:
                    if self.control_conn is conn:
                        self.control_conn = None
                try:
                    conn.close()
                except Exception:
                    pass
                log("app disconnected; shutting down")
                self.cleanup()
                if self.loop is not None:
                    self.loop.quit()
                return

    def serve(self):
        if not self.ensure_bus():
            return 2
        if not self.ensure_stack():
            log("FATAL: no bluetooth adapter found "
                "(aktifkan Bluebinder dulu, lalu coba lagi)")
            return 2
        self.setup_adapter()
        self.register_objects()
        from gi.repository import GLib
        self.loop = GLib.MainLoop()
        threading.Thread(target=self.control_accept_loop,
                         daemon=True).start()
        threading.Thread(target=self.watchdog_loop, daemon=True).start()
        log("serving; pair host ke '%s'" % DEVICE_NAME)
        self.push_status()
        try:
            self.loop.run()
        except KeyboardInterrupt:
            pass
        finally:
            self.cleanup()
        return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--check":
        return do_check()
    try:
        server = HidServer()
    except Exception as e:
        log("FATAL: cannot init D-Bus: %s" % e)
        return 2
    return server.serve()


if __name__ == "__main__":
    sys.exit(main())
