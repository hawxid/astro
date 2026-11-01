# ASTRO_FRIDA_BRIDGE_VERSION = 4
#!/usr/bin/env python3
"""Astro Frida bridge LENGKAP (Batch19-fix + tema OSIRIS, 2026-11-01).

Web UI Frida server-rendered: bridge chroot Python ini melayani
halaman HTML + endpoint Frida untuk webview melayang di atas game.
SEMUA endpoint (kecuali /health) wajib token sesi — token TIDAK
PERNAH di URL maupun log; token disisipkan server-side sebagai
hidden form field, dan diterima juga via header X-Astro-Token atau
cookie sesi (yang dipasang panel Frida saat membuka WebUI).

Keamanan (aturan keras, dari diskusi 2026-11-01):
- Bind 127.0.0.1 SAJA — tidak pernah ke semua interface. Chroot tidak
  mengisolasi network, jadi 127.0.0.1 chroot = 127.0.0.1 Android;
  localhost-only berarti tidak kena LAN/Wi-Fi.
- Token sesi acak per start (env ASTRO_FRIDA_TOKEN, dibuat
  FridaManager). Tanpa token yang cocok, semua endpoint kecuali
  /health menjawab 401. Token TIDAK PERNAH dicetak ke log.
- Fail closed: bila env token kosong/tidak ada, proses menolak
  start (exit 2).
- Halaman LOKAL saja: tidak ada aset remote, tidak ada JS
  framework — CSS inline, JavaScript NOL (auto-refresh status
  memakai <meta refresh>, bukan skrip).
- Hanya stdlib + binding frida (frida-tools sudah ada di chroot
  user dari PoC). Bila binding tidak ada, endpoint Frida gagal
  dengan pesan jelas, bukan crash diam-diam.

Alamat tersimpan WAJIB module+offset (bukan absolut): ASLR
mengubah base tiap run. Alamat heap/anonim (di luar modul mana
pun) ditandai tidak-stabil dan TIDAK pernah ditulis otomatis
saat load tabel — cari ulang.

Endpoint:
- GET  /health    (tanpa token, liveness saja)
- GET  /status    (bertoken, JSON; bentuk lama dipertahankan)
- GET  /          menu (status + link semua fitur)
- GET  /processes daftar proses (tombol attach) + form spawn
- POST /attach    (pid atau nama proses)
- POST /spawn     (nama paket: spawn + attach + resume)
- POST /detach    (lepas sesi, matikan freeze + auto-attach)
- GET  /modules   enumerate_modules (butuh sesi)
- GET  /ranges    enumerate_ranges (butuh sesi)
- POST /scan      (tipe int8/16/32/64, float, double, string,
                   unknown-initial = snapshot nilai awal)
- POST /refine    (changed/unchanged/increased/decreased/equal)
- POST /write     (module+offset atau alamat absolut, tipe, nilai)
- POST /freeze    (tandai entri watchlist + jalan/henti rewrite)
- GET  /watchlist (nilai live + tombol freeze/tulis/hapus)
- POST /table/save (watchlist -> JSON di /tmp/astro_frida_tables/)
- POST /table/load (JSON -> watchlist + opsional terapkan)
- POST /script    (runner JS custom, output console.log)
- POST /auto-attach (toggle: pantau proses, auto-attach +
                   opsional auto-apply tabel)
- GET  /il2cpp    (dump class/field via agent JS; bila target
                   bukan IL2CPP: pesan jelas, bukan crash)

State scan/watchlist/freeze hidup di memori bridge (single-user
localhost, jadi state global boleh). Tabel tersimpan di disk.

Jalan: ASTRO_FRIDA_TOKEN=<token> python3 astro_frida_bridge.py
Log: /tmp/astro_frida_bridge.log (dialihkan perintah start).
Berhenti: pkill -f astro_frida_bridge.py (dari FridaManager.stop,
SEBELUM frida-server dimatikan — urutan kill: klien dulu, server
kemudian).
"""

import hashlib
import hmac
import html
import json
import os
import re
import struct
import sys
import threading
import time
import urllib.parse
from urllib.parse import parse_qs, urlparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 18081
SESSION_TOKEN = ""
# Angka ini HARUS sama dengan marker komentar di baris 1 — manager
# Java membaca marker itu untuk memutuskan overwrite filesDir.
BRIDGE_VERSION = 4
TOKEN_HEADER = "X-Astro-Token"
TABLES_DIR = "/tmp/astro_frida_tables"
SCAN_MATCH_CAP = 5000
SNAP_ENTRY_CAP = 1000000
SNAP_RANGE_MAX = 64 * 1024 * 1024
IL2CPP_CLASS_CAP = 1000
IL2CPP_FIELD_CAP = 100
SCRIPT_WINDOW_SEC = 5
AGENT_TIMEOUT_SEC = 60
FREEZE_INTERVAL_SEC = 0.7
AUTO_POLL_SEC = 2.0
MAX_BODY = 1024 * 1024

try:
    import frida as _frida
    FRIDA_AVAILABLE = True
except Exception as _frida_err:  # noqa: F841 (pesan dipakai di halaman)
    _frida = None
    FRIDA_AVAILABLE = False

# Tipe nilai yang didukung scan/tulis. (ukuran byte, format struct,
# reader JS, writer JS). String ditangani khusus (panjang variabel).
TYPES = {
    "int8": (1, "b", "readS8", "writeS8"),
    "int16": (2, "h", "readS16", "writeS16"),
    "int32": (4, "i", "readS32", "writeS32"),
    "int64": (8, "q", "readS64", "writeS64"),
    "float": (4, "f", "readFloat", "writeFloat"),
    "double": (8, "d", "readDouble", "writeDouble"),
    "string": (0, None, "readUtf8String", "writeUtf8String"),
}


# ---------------------------------------------------------------- state
class BridgeState:
    """Satu-satunya state (single-user localhost). Diakses lewat
    lock untuk mutasi; pemanggilan frida sendiri tidak dikunci
    (sesi frida mendukung banyak skrip bersamaan)."""

    def __init__(self):
        self.lock = threading.RLock()
        self.device = None
        self.device_error = None
        self.session = None
        self.attached_pid = None
        self.attached_name = None
        # Hasil scan: [{module, offset, type, value, absolute}]
        # absolute = alamat saat scan (tampilan saja; yang disimpan
        # ke tabel hanya module+offset).
        self.scan_results = []
        self.scan_note = ""
        # Watchlist: [{module, offset, type, value, frozen}]
        self.watchlist = []
        self.freeze_on = False
        self.freeze_stop = None
        self.freeze_gen = 0
        self.auto_on = False
        self.auto_target = ""
        self.auto_table = ""
        self.auto_apply = False
        self.auto_freeze = False
        self.auto_stop = None
        self.auto_gen = 0


STATE = BridgeState()


# ---------------------------------------------------------------- util
def esc(value):
    return html.escape("" if value is None else str(value), quote=True)


def parse_cookies(header):
    out = {}
    if not header:
        return out
    for part in header.split(";"):
        part = part.strip()
        if "=" in part:
            key, val = part.split("=", 1)
            out[key.strip()] = val.strip()
    return out


def token_ok(supplied, expected):
    """Perbandingan token string tunggal, compare_digest
    (anti-timing). Pemanggil memilih carrier-nya (header, cookie
    sesi, atau field form POST). TIDAK PERNAH log token."""
    if not supplied or not expected:
        return False
    try:
        return hmac.compare_digest(supplied, expected)
    except TypeError:
        return False


CSS = (
    "body{background:#050508;color:#E8E8EA;font-family:sans-serif;"
    "margin:0;padding:12px} a{color:#22D3EE} h1{font-size:20px}"
    "h2{font-size:16px} .card{background:#0B0B10;border:1px solid"
    " #2A2A35;border-radius:12px;padding:12px;margin:10px 0}"
    "button{background:#D4AF37;color:#0B0B10;border:0;border-radius:8px;"
    "padding:8px 14px;margin:2px;cursor:pointer}"
    "button.sec{background:#1A1A24;color:#E8E8EA} input,select{background:#12121A;color:"
    "#E8E8EA;border:1px solid #2A2A35;border-radius:6px;padding:6px;"
    "margin:2px} table{border-collapse:collapse;width:100%}"
    "td,th{border:1px solid #2A2A35;padding:5px;text-align:left;"
    "font-size:13px} code{font-size:12px;word-break:break-all}"
    ".ok{color:#00FF88} .bad{color:#EF5350} .dim{color:#8A8A95}"
    ".mono{font-family:monospace} pre{white-space:pre-wrap;"
    "word-break:break-all;background:#0B0B10;padding:8px;"
    "border-radius:8px}"
)


def page(title, body, token, refresh=None):
    """Satu halaman HTML penuh. Token disisipkan server-side HANYA
    sebagai hidden field di form (helper form() di bawah) — halaman
    ini sendiri tidak pernah mencetak token sebagai teks."""
    meta = ""
    if refresh:
        meta = '<meta http-equiv="refresh" content="%d">' % int(refresh)
    return ("<!DOCTYPE html><html><head><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,"
            "initial-scale=1'>" + meta + "<title>" + esc(title) + "</title>"
            "<style>" + CSS + "</style></head><body>"
            "<h1>" + esc(title) + "</h1>"
            + body +
            "<p class='dim'><a href='/'>&larr; Menu</a> &middot; "
            "Astro Frida bridge (localhost saja)</p>"
            "</body></html>").encode("utf-8")


def form(action, fields, token, submit, css_class=""):
    """Form POST biasa + hidden token field. fields = list
    (nama, html-input) yang sudah di-escape pemanggil."""
    out = ["<form method='post' action='" + esc(action) + "'>"]
    out.append("<input type='hidden' name='token' value='"
               + esc(token) + "'>")
    for _name, widget in fields:
        out.append(widget)
    out.append("<button type='submit' class='" + css_class + "'>"
               + esc(submit) + "</button></form>")
    return "".join(out)


def txt(name, value="", size=18, placeholder=""):
    return ("<input name='" + esc(name) + "' value='" + esc(value)
            + "' size='" + str(int(size)) + "' placeholder='"
            + esc(placeholder) + "'>")


def sel(name, options, current=""):
    out = ["<select name='" + esc(name) + "'>"]
    for opt in options:
        picked = " selected" if opt == current else ""
        out.append("<option value='" + esc(opt) + "'" + picked + ">"
                   + esc(opt) + "</option>")
    out.append("</select>")
    return "".join(out)


def note(text, ok=True):
    if not text:
        return ""
    cls = "ok" if ok else "bad"
    return "<p class='" + cls + "'>" + esc(text) + "</p>"


def read_form(handler):
    """Baca body POST urlencoded (cap 1 MB). Kembalikan dict
    nama -> nilai tunggal. Isi form TIDAK PERNAH di-log."""
    length = 0
    try:
        length = int(handler.headers.get("Content-Length") or 0)
    except ValueError:
        length = 0
    if length <= 0:
        return {}
    if length > MAX_BODY:
        return {"_too_big": "1"}
    raw = handler.rfile.read(length)
    parsed = urllib.parse.parse_qs(raw.decode("utf-8", "replace"))
    return {k: v[0] for k, v in parsed.items() if v}


def addr_hex(value):
    try:
        return "0x%x" % int(value)
    except (TypeError, ValueError):
        return "?"


def fmt_value(vtype, value):
    if value is None:
        return "(tidak bisa dibaca)"
    if vtype == "string":
        return str(value)
    return str(value)


def parse_value(vtype, text):
    """Nilai form -> nilai Python (atau raise ValueError)."""
    text = (text or "").strip()
    if vtype == "string":
        return text
    if vtype in ("int8", "int16", "int32", "int64"):
        return int(text, 0)
    if vtype in ("float", "double"):
        return float(text)
    raise ValueError("tipe tidak dikenal: " + vtype)


def encode_pattern(vtype, value):
    """Nilai -> pola hex little-endian untuk Memory.scanSync."""
    if vtype == "string":
        data = str(value).encode("utf-8")
    else:
        _size, fmt, _r, _w = TYPES[vtype]
        data = struct.pack("<" + fmt, value)
    return " ".join("%02x" % b for b in data)


# ---------------------------------------------------------------- frida
class FridaError(Exception):
    pass


def need_frida():
    if not FRIDA_AVAILABLE:
        raise FridaError(
            "binding frida tidak tersedia di chroot — install dulu: "
            "pip install frida-tools (lalu Start ulang dari menu)")
    return _frida


def get_device():
    """Remote device ke frida-server localhost. API add_remote_device
    dicek pakai hasattr — bila tidak ada di binding terpasang, gagal
    dengan pesan jelas (jangan menebak diam-diam)."""
    frida = need_frida()
    with STATE.lock:
        if STATE.device is not None:
            return STATE.device
    try:
        mgr = frida.get_device_manager()
        if not hasattr(mgr, "add_remote_device"):
            raise FridaError(
                "binding frida di chroot tidak punya add_remote_device"
                " — update frida-tools via pip, lalu Start ulang")
        dev = mgr.add_remote_device("127.0.0.1")
        with STATE.lock:
            STATE.device = dev
            STATE.device_error = None
        return dev
    except FridaError:
        raise
    except Exception as exc:
        raise FridaError("tidak bisa terhubung ke frida-server "
                         "127.0.0.1:27042: %s" % exc)


def drop_device():
    with STATE.lock:
        STATE.device = None


def ensure_session():
    need_frida()
    with STATE.lock:
        sess = STATE.session
    if sess is None:
        raise FridaError("belum attach — pilih proses dulu di /processes")
    return sess


def stop_freeze_thread():
    with STATE.lock:
        STATE.freeze_on = False
        ev = STATE.freeze_stop
        STATE.freeze_gen += 1
    if ev is not None:
        ev.set()


def stop_auto_thread():
    with STATE.lock:
        STATE.auto_on = False
        ev = STATE.auto_stop
        STATE.auto_gen += 1
    if ev is not None:
        ev.set()


def detach_session():
    stop_freeze_thread()
    stop_auto_thread()
    with STATE.lock:
        sess = STATE.session
        STATE.session = None
        STATE.attached_pid = None
        STATE.attached_name = None
        STATE.scan_results = []
        STATE.scan_note = ""
    if sess is not None:
        try:
            sess.detach()
        except Exception:
            pass


def attach_to_pid(pid, name):
    """Attach (sesi lama dilepas dulu). Scan lama dibersihkan;
    watchlist dipertahankan (module+offset bisa di-resolve ulang)."""
    dev = get_device()
    detach_session()
    try:
        sess = dev.attach(pid)
    except Exception as exc:
        raise FridaError("gagal attach ke %s (%s): %s" % (name, pid, exc))
    with STATE.lock:
        STATE.session = sess
        STATE.attached_pid = pid
        STATE.attached_name = name
    return sess


def list_processes():
    dev = get_device()
    try:
        procs = dev.enumerate_processes()
    except Exception as exc:
        drop_device()
        raise FridaError("gagal enumerate proses: %s" % exc)
    out = []
    for proc in procs:
        try:
            out.append({"pid": int(proc.pid), "name": str(proc.name)})
        except Exception:
            continue
    out.sort(key=lambda item: item["name"].lower())
    return out


def find_pid_by_name(name):
    for proc in list_processes():
        if proc["name"] == name:
            return proc["pid"]
    return None


def get_modules():
    sess = ensure_session()
    try:
        mods = sess.enumerate_modules()
    except Exception as exc:
        raise FridaError("gagal enumerate modul: %s" % exc)
    out = []
    for mod in mods:
        try:
            out.append({"name": str(mod.name), "base": int(mod.base_address),
                        "size": int(mod.size), "path": str(mod.path)})
        except Exception:
            continue
    return out


RANGE_PROTECTIONS = ("r--", "rw-", "rwx", "r-x", "--x", "---")


def get_ranges():
    """Daftar semua range memori proses yang di-attach.

    API Python Frida yang benar: range di-enumerate PER MODUL
    (Module.enumerate_ranges), BUKAN Session.enumerate_ranges —
    metode itu tidak ada di Session modern (batch19 memakainya,
    sehingga halaman Range selalu error). Bila binding tidak punya
    Module.enumerate_ranges, fallback ke agent JS
    Process.enumerateRangesSync per proteksi."""
    sess = ensure_session()
    try:
        mods = sess.enumerate_modules()
    except Exception as exc:
        raise FridaError("gagal enumerate modul: %s" % exc)
    seen = set()
    out = []
    have_method = False
    for mod in mods:
        enum = getattr(mod, "enumerate_ranges", None)
        if enum is None:
            continue
        have_method = True
        for prot in RANGE_PROTECTIONS:
            try:
                ranges = enum(prot)
            except Exception:
                continue
            for rng in ranges or []:
                try:
                    base = int(rng.base_address)
                    size = int(rng.size)
                except Exception:
                    continue
                if size <= 0 or (base, size) in seen:
                    continue
                seen.add((base, size))
                out.append({"base": base, "size": size,
                            "protection": str(rng.protection)})
    if have_method:
        return out
    # Fallback: agent JS (Process.enumerateRangesSync ada di Frida 17).
    agent = r"""
(function () {
  var prots = ['r--', 'rw-', 'rwx', 'r-x', '--x', '---'];
  var out = [];
  for (var i = 0; i < prots.length; i++) {
    try {
      var rs = Process.enumerateRangesSync(prots[i]);
      for (var j = 0; j < rs.length; j++) {
        out.push({base: rs[j].base.toString(), size: rs[j].size,
                  protection: rs[j].protection});
      }
    } catch (e) { /* proteksi ini tidak didukung, lewati */ }
  }
  send({ranges: out});
})();
"""
    try:
        payload = run_agent_once(agent, action="range-fallback")
    except Exception as exc:
        raise FridaError("fallback range via agent gagal: %s" % exc)
    for r in payload.get("ranges", []):
        try:
            base = int(r["base"], 16)
            size = int(r["size"])
        except Exception:
            continue
        if size <= 0 or (base, size) in seen:
            continue
        seen.add((base, size))
        out.append({"base": base, "size": size,
                    "protection": str(r["protection"])})
    if not out:
        raise FridaError(
            "binding frida tidak menyediakan enumerate_ranges "
            "(bukan di Module maupun di agent) — update frida-tools")
    return out


def readable_ranges():
    out = []
    for r in get_ranges():
        prot = str(r["protection"]).lower()
        if "r" in prot or "read" in prot:
            out.append(r)
    return out


def resolve_module(addr):
    """Alamat absolut -> (nama_modul, offset) atau ('', alamat).
    Alamat di luar modul (heap/anonim) ditandai tidak-stabil."""
    for mod in get_modules():
        if mod["base"] <= addr < mod["base"] + mod["size"]:
            return mod["name"], addr - mod["base"]
    return "", addr


def resolve_absolute(module, offset):
    """(nama_modul, offset) -> alamat absolut, atau None bila modul
    tidak ditemukan di sesi saat ini."""
    if not module:
        return None
    for mod in get_modules():
        if mod["name"] == module or mod["path"].endswith("/" + module):
            return mod["base"] + offset
    return None


def run_agent_once(js_code, timeout=AGENT_TIMEOUT_SEC, action="agent"):
    """Jalankan agent, tunggu SATU pesan send/error. Kembalikan
    payload (dict) atau raise FridaError. `action` hanya label
    (scan/snapshot/baca/tulis/il2cpp) supaya halaman error menyebut
    fungsi mana yang gagal — batch19 tidak melabelinya sehingga
    error agent tidak bisa dilacak ke tombolnya."""
    sess = ensure_session()
    script = None
    box = {"event": threading.Event(), "messages": []}

    def on_message(message, _data):
        box["messages"].append(message)
        box["event"].set()

    try:
        script = sess.create_script(js_code)
        script.on("message", on_message)
        script.load()
    except Exception as exc:
        raise FridaError("gagal load %s: %s" % (action, exc))
    try:
        if not box["event"].wait(timeout):
            raise FridaError("timeout %s (%d dtk)" % (action, timeout))
        msg = box["messages"][0]
        if msg.get("type") == "error":
            raise FridaError("agent error [%s]: %s"
                             % (action, msg.get("description", "?")))
        return msg.get("payload")
    finally:
        try:
            script.unload()
        except Exception:
            pass


def run_agent_collect(js_code, window=SCRIPT_WINDOW_SEC):
    """Runner JS custom: kumpulkan semua pesan selama jendela waktu,
    lalu unload. Kembalikan list baris log."""
    sess = ensure_session()
    script = None
    logs = []

    def on_message(message, _data):
        if message.get("type") == "send":
            payload = message.get("payload")
            if isinstance(payload, str):
                logs.append(payload)
            else:
                logs.append(json.dumps(payload, ensure_ascii=False))
        elif message.get("type") == "error":
            logs.append("[error] " + str(message.get("description", "?")))

    try:
        script = sess.create_script(js_code)
        script.on("message", on_message)
        script.load()
    except Exception as exc:
        return ["[error] gagal load script: %s" % exc]
    time.sleep(window)
    try:
        script.unload()
    except Exception:
        pass
    if not logs:
        logs.append("(tidak ada output dalam %d detik)" % window)
    return logs


SCAN_AGENT = r"""
(function () {
  var data = __DATA__;
  var out = [];
  for (var i = 0; i < data.ranges.length; i++) {
    var r = data.ranges[i];
    try {
      var m = Memory.scanSync(ptr(r.base), r.size, data.pattern);
      for (var j = 0; j < m.length; j++) {
        out.push(m[j].address.toString());
        if (out.length >= data.cap) {
          send({matches: out, truncated: true});
          return;
        }
      }
    } catch (e) { /* range tidak bisa di-scan, lewati */ }
  }
  send({matches: out, truncated: false});
})();
"""

SNAP_AGENT = r"""
(function () {
  var data = __DATA__;
  var out = [];
  for (var i = 0; i < data.ranges.length; i++) {
    var r = data.ranges[i];
    if (r.size > data.range_max) { continue; }
    var base = ptr(r.base);
    try {
      for (var off = 0; off + data.step <= r.size; off += data.step) {
        var p = base.add(off);
        var v;
        if (data.type === 'int8') v = p.readS8();
        else if (data.type === 'int16') v = p.readS16();
        else if (data.type === 'int32') v = p.readS32();
        else if (data.type === 'int64') v = p.readS64().toString();
        else if (data.type === 'float') v = p.readFloat();
        else if (data.type === 'double') v = p.readDouble();
        out.push([p.toString(), v]);
        if (out.length >= data.cap) {
          send({entries: out, truncated: true});
          return;
        }
      }
    } catch (e) { /* range berubah di tengah jalan, lewati */ }
  }
  send({entries: out, truncated: false});
})();
"""

READ_AGENT = r"""
(function () {
  var data = __DATA__;
  var out = [];
  for (var i = 0; i < data.addrs.length; i++) {
    var a = data.addrs[i];
    var p = ptr(a.addr);
    try {
      var v;
      if (a.type === 'int8') v = p.readS8();
      else if (a.type === 'int16') v = p.readS16();
      else if (a.type === 'int32') v = p.readS32();
      else if (a.type === 'int64') v = p.readS64().toString();
      else if (a.type === 'float') v = p.readFloat();
      else if (a.type === 'double') v = p.readDouble();
      else if (a.type === 'string') v = p.readUtf8String(256);
      out.push(v);
    } catch (e) { out.push(null); }
  }
  send({values: out});
})();
"""

WRITE_AGENT = r"""
(function () {
  var data = __DATA__;
  var errors = [];
  for (var i = 0; i < data.writes.length; i++) {
    var w = data.writes[i];
    var p = ptr(w.addr);
    try {
      if (w.type === 'int8') p.writeS8(w.value);
      else if (w.type === 'int16') p.writeS16(w.value);
      else if (w.type === 'int32') p.writeS32(w.value);
      else if (w.type === 'int64') p.writeS64(w.value);
      else if (w.type === 'float') p.writeFloat(w.value);
      else if (w.type === 'double') p.writeDouble(w.value);
      else if (w.type === 'string') {
        var cur = "";
        try { cur = p.readUtf8String(1024) || ""; } catch (e) { cur = ""; }
        if (w.value.length > cur.length) {
          errors.push(w.addr + ": string baru lebih panjang dari "
            + "alokasi lama (" + cur.length + " karakter) — ditolak");
        } else {
          p.writeUtf8String(w.value);
        }
      }
    } catch (e) { errors.push(w.addr + ": " + e.message); }
  }
  send({errors: errors});
})();
"""

IL2CPP_AGENT = r"""
(function () {
  function exp(name) {
    try { return Module.getExportByName('libil2cpp.so', name); }
    catch (e) { return null; }
  }
  if (Module.findBaseAddress('libil2cpp.so') === null) {
    send({il2cpp: false, reason: 'libil2cpp.so tidak ditemukan'});
    return;
  }
  var need = ['il2cpp_domain_get', 'il2cpp_domain_get_assemblies',
    'il2cpp_assembly_get_image', 'il2cpp_image_get_class_count',
    'il2cpp_image_get_class', 'il2cpp_class_get_name',
    'il2cpp_class_get_namespace', 'il2cpp_class_get_field_count',
    'il2cpp_class_get_fields', 'il2cpp_field_get_name',
    'il2cpp_field_get_offset'];
  var fn = {};
  for (var i = 0; i < need.length; i++) {
    fn[need[i]] = exp(need[i]);
    if (fn[need[i]] === null) {
      send({il2cpp: false,
        reason: 'export hilang: ' + need[i] + ' (API tidak lengkap)'});
      return;
    }
  }
  try {
    var domainGet = new NativeFunction(fn['il2cpp_domain_get'], 'pointer', []);
    var domain = domainGet();
    var sizePtr = Memory.alloc(Process.pointerSize);
    var getAssemblies = new NativeFunction(
      fn['il2cpp_domain_get_assemblies'], 'pointer', ['pointer', 'pointer']);
    var arr = getAssemblies(domain, sizePtr);
    var count = sizePtr.readU32();
    var imageGet = new NativeFunction(
      fn['il2cpp_assembly_get_image'], 'pointer', ['pointer']);
    var classCount = new NativeFunction(
      fn['il2cpp_image_get_class_count'], 'int', ['pointer']);
    var classGet = new NativeFunction(
      fn['il2cpp_image_get_class'], 'pointer', ['pointer', 'int']);
    var nameGet = new NativeFunction(
      fn['il2cpp_class_get_name'], 'pointer', ['pointer']);
    var nsGet = new NativeFunction(
      fn['il2cpp_class_get_namespace'], 'pointer', ['pointer']);
    var fieldCount = new NativeFunction(
      fn['il2cpp_class_get_field_count'], 'int', ['pointer']);
    var fieldsGet = new NativeFunction(
      fn['il2cpp_class_get_fields'], 'pointer', ['pointer', 'pointer']);
    var fieldName = new NativeFunction(
      fn['il2cpp_field_get_name'], 'pointer', ['pointer']);
    var fieldOff = new NativeFunction(
      fn['il2cpp_field_get_offset'], 'int', ['pointer']);
    var classes = [];
    var truncated = false;
    for (var a = 0; a < count; a++) {
      var assembly = arr.add(a * Process.pointerSize).readPointer();
      var image = imageGet(assembly);
      var n = classCount(image);
      for (var c = 0; c < n; c++) {
        if (classes.length >= __CLASS_CAP__) { truncated = true; break; }
        var klass = classGet(image, c);
        var fields = [];
        var iter = Memory.alloc(Process.pointerSize);
        var fcount = fieldCount(klass);
        for (var f = 0; f < fcount; f++) {
          if (fields.length >= __FIELD_CAP__) { break; }
          var field = fieldsGet(klass, iter);
          if (field.isNull()) { break; }
          fields.push({name: fieldName(field).readUtf8String(),
            offset: fieldOff(field)});
        }
        classes.push({ns: nsGet(klass).readUtf8String(),
          name: nameGet(klass).readUtf8String(), fields: fields});
      }
      if (truncated) { break; }
    }
    send({il2cpp: true, classes: classes, truncated: truncated});
  } catch (e) {
    send({il2cpp: false, reason: 'exception saat dump: ' + e.message});
  }
})();
""".replace("__CLASS_CAP__", str(IL2CPP_CLASS_CAP)).replace(
    "__FIELD_CAP__", str(IL2CPP_FIELD_CAP))


def run_scan(vtype, value_text, unknown):
    """Scan -> (list entri module+offset, note). unknown=True berarti
    snapshot nilai awal (hanya tipe numerik)."""
    ensure_session()
    ranges = readable_ranges()
    if unknown:
        if vtype == "string":
            raise FridaError("unknown-initial tidak berlaku untuk string"
                             " — isi nilai string yang dicari")
        step = TYPES[vtype][0]
        data = {"ranges": ranges, "type": vtype, "step": step,
                "cap": SNAP_ENTRY_CAP, "range_max": SNAP_RANGE_MAX}
        payload = run_agent_once(SNAP_AGENT.replace(
            "__DATA__", json.dumps(data)), action="snapshot")
        entries = []
        for addr_s, val in payload.get("entries", []):
            addr = int(addr_s, 16)
            if vtype == "int64":
                val = int(val)
            module, offset = resolve_module(addr)
            entries.append({"module": module, "offset": offset,
                            "type": vtype, "value": val,
                            "absolute": addr})
        note = ""
        if payload.get("truncated"):
            note = ("snapshot dipotong (cap %d entri / range >%d MB "
                    "dilewati) — hasil tidak lengkap" % (
                        SNAP_ENTRY_CAP, SNAP_RANGE_MAX // (1024 * 1024)))
        return entries, note
    value = parse_value(vtype, value_text)
    pattern = encode_pattern(vtype, value)
    data = {"ranges": ranges, "pattern": pattern, "cap": SCAN_MATCH_CAP}
    payload = run_agent_once(SCAN_AGENT.replace(
        "__DATA__", json.dumps(data)), action="scan")
    entries = []
    for addr_s in payload.get("matches", []):
        addr = int(addr_s, 16)
        module, offset = resolve_module(addr)
        entries.append({"module": module, "offset": offset,
                        "type": vtype, "value": value, "absolute": addr})
    note = ""
    if payload.get("truncated"):
        note = ("hasil dipotong (cap %d alamat) — persempit range "
                "atau refine dulu" % SCAN_MATCH_CAP)
    return entries, note


def read_values(entries):
    """Baca nilai live untuk entri (module+offset di-resolve dulu).
    Kembalikan list nilai (None bila tidak bisa dibaca/di-resolve).
    Entri heap (module kosong) dibaca di alamat absolut scan-time
    hanya untuk tampilan — tidak pernah ditulis otomatis."""
    addrs = []
    for entry in entries:
        absolute = None
        if entry["module"]:
            absolute = resolve_absolute(entry["module"], entry["offset"])
        else:
            absolute = entry.get("absolute")
        addrs.append({"addr": absolute, "type": entry["type"]}
                     if absolute is not None else None)
    todo = [a for a in addrs if a is not None]
    values = {}
    if todo:
        payload = run_agent_once(READ_AGENT.replace(
            "__DATA__", json.dumps({"addrs": todo})), action="baca")
        vals = payload.get("values", [])
        j = 0
        for i, item in enumerate(addrs):
            if item is None:
                values[i] = None
            else:
                values[i] = vals[j] if j < len(vals) else None
                j += 1
    else:
        values = {i: None for i in range(len(addrs))}
    return [values[i] for i in range(len(addrs))]


def write_entries(writes):
    """writes = [{module, offset, type, value}] atau absolute.
    Kembalikan list error (kosong = semua sukses). Entri heap
    (module kosong) TIDAK PERNAH ditulis lewat jalur ini kecuali
    pemanggil menyertakan absolute secara eksplisit (tulis manual
    dari halaman scan — itu aksi sadar user, bukan load tabel)."""
    jobs = []
    for item in writes:
        if item.get("module"):
            absolute = resolve_absolute(item["module"], item["offset"])
            if absolute is None:
                return ["modul %s tidak ditemukan — attach ulang?"
                        % item["module"]]
        else:
            absolute = item.get("absolute")
            if absolute is None:
                return ["alamat tidak bisa di-resolve (heap/anonim)"]
        jobs.append({"addr": absolute, "type": item["type"],
                     "value": item["value"]})
    if not jobs:
        return ["tidak ada entri untuk ditulis"]
    payload = run_agent_once(WRITE_AGENT.replace(
        "__DATA__", json.dumps({"writes": jobs})), action="tulis")
    return payload.get("errors", [])


def refine_entries(mode, value_text):
    """Refine hasil scan terakhir. Kembalikan (entri_baru, note)."""
    with STATE.lock:
        current = list(STATE.scan_results)
    if not current:
        raise FridaError("belum ada hasil scan — scan dulu")
    if mode not in ("changed", "unchanged", "increased", "decreased",
                    "equal"):
        raise FridaError("mode refine tidak dikenal: " + mode)
    if mode in ("increased", "decreased"):
        if current[0]["type"] == "string":
            raise FridaError("mode %s tidak berlaku untuk string" % mode)
    target = None
    if mode == "equal":
        target = parse_value(current[0]["type"], value_text)
    values = read_values(current)
    kept = []
    for entry, now in zip(current, values):
        if now is None:
            continue
        old = entry["value"]
        if entry["type"] == "int64":
            try:
                now = int(now)
            except (TypeError, ValueError):
                continue
        keep = False
        if mode == "changed":
            keep = now != old
        elif mode == "unchanged":
            keep = now == old
        elif mode == "increased":
            keep = now > old
        elif mode == "decreased":
            keep = now < old
        elif mode == "equal":
            keep = now == target
        if keep:
            entry = dict(entry)
            entry["value"] = now
            kept.append(entry)
    return kept, "%d dari %d alamat lolos refine (%s)" % (
        len(kept), len(current), mode)


def freeze_loop(gen):
    while True:
        with STATE.lock:
            if gen != STATE.freeze_gen or not STATE.freeze_on:
                return
            ev = STATE.freeze_stop
            entries = [dict(w) for w in STATE.watchlist if w.get("frozen")]
        if ev is not None and ev.wait(FREEZE_INTERVAL_SEC):
            return
        if not entries:
            continue
        try:
            write_entries(entries)
        except Exception:
            pass


def start_freeze_thread():
    with STATE.lock:
        STATE.freeze_gen += 1
        gen = STATE.freeze_gen
        STATE.freeze_stop = threading.Event()
        STATE.freeze_on = True
    thread = threading.Thread(target=freeze_loop, args=(gen,),
                              daemon=True)
    thread.start()


def auto_loop(gen):
    while True:
        with STATE.lock:
            if gen != STATE.auto_gen or not STATE.auto_on:
                return
            ev = STATE.auto_stop
            target = STATE.auto_target
            table = STATE.auto_table
            apply = STATE.auto_apply
            freeze = STATE.auto_freeze
        if ev is not None and ev.wait(AUTO_POLL_SEC):
            return
        try:
            with STATE.lock:
                attached = STATE.session is not None
            if attached:
                continue
            pid = find_pid_by_name(target)
            if pid is None:
                continue
            attach_to_pid(pid, target)
            if table:
                entries, _unresolved, _err = load_table(table)
                if entries:
                    with STATE.lock:
                        STATE.watchlist = entries
                    if apply:
                        errs = write_entries(entries)
                        if not errs and freeze and any(
                                e.get("frozen") for e in entries):
                            start_freeze_thread()
        except Exception:
            pass


def start_auto_thread(target, table, apply, freeze):
    with STATE.lock:
        STATE.auto_gen += 1
        gen = STATE.auto_gen
        STATE.auto_stop = threading.Event()
        STATE.auto_on = True
        STATE.auto_target = target
        STATE.auto_table = table
        STATE.auto_apply = apply
        STATE.auto_freeze = freeze
    thread = threading.Thread(target=auto_loop, args=(gen,),
                              daemon=True)
    thread.start()


def sanitize_table_name(name):
    cleaned = re.sub(r"[^A-Za-z0-9_.\-]", "_", (name or "").strip())
    cleaned = cleaned.strip(".")[:64]
    return cleaned or "tabel"


def table_path(name):
    return os.path.join(TABLES_DIR, sanitize_table_name(name) + ".json")


def list_tables():
    try:
        os.makedirs(TABLES_DIR, exist_ok=True)
        return sorted(f[:-5] for f in os.listdir(TABLES_DIR)
                      if f.endswith(".json"))
    except Exception:
        return []


def save_table(name, entries):
    """Simpan watchlist — HANYA module+offset (alamat absolut
    TIDAK PERNAH ditulis; entri heap tidak disimpan sebagai entri
    permanen karena tidak stabil antar run)."""
    os.makedirs(TABLES_DIR, exist_ok=True)
    saved = []
    for entry in entries:
        if not entry["module"]:
            continue
        saved.append({"module": entry["module"], "offset": entry["offset"],
                      "type": entry["type"], "value": entry["value"],
                      "frozen": bool(entry.get("frozen"))})
    path = table_path(name)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump({"game": sanitize_table_name(name),
                   "entries": saved}, handle)
    return path, len(saved)


def load_table(name):
    """Kembalikan (entries, unresolved, error). Entries memakai
    module+offset; unresolved = entri yang modulnya tidak ketemu
    di sesi saat ini (tidak pernah ditulis)."""
    path = table_path(name)
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except Exception as exc:
        return [], [], "gagal baca tabel: %s" % exc
    entries = []
    unresolved = []
    for raw in data.get("entries", []):
        try:
            if raw["type"] not in TYPES:
                continue
            entry = {"module": str(raw["module"]),
                     "offset": int(raw["offset"]),
                     "type": raw["type"], "value": raw["value"],
                     "frozen": bool(raw.get("frozen"))}
        except Exception:
            continue
        if not entry["module"]:
            unresolved.append(entry)
            continue
        absolute = resolve_absolute(entry["module"], entry["offset"])
        if absolute is None:
            unresolved.append(entry)
            continue
        entry["absolute"] = absolute
        entries.append(entry)
    return entries, unresolved, ""


# ---------------------------------------------------------------- halaman
def attached_line():
    with STATE.lock:
        pid = STATE.attached_pid
        name = STATE.attached_name
        scan_n = len(STATE.scan_results)
        watch_n = len(STATE.watchlist)
        frozen_n = sum(1 for w in STATE.watchlist if w.get("frozen"))
        auto = STATE.auto_on
        target = STATE.auto_target
    if pid is None:
        line = "<p>Target: <span class='bad'>belum attach</span></p>"
    else:
        line = ("<p>Target: <b>" + esc(name) + "</b> (pid " + str(pid)
                + ")</p>")
    line += ("<p>Hasil scan: %d &middot; Watchlist: %d (%d freeze)"
             " &middot; Auto-attach: %s</p>"
             % (scan_n, watch_n, frozen_n,
                "ON (" + esc(target) + ")" if auto else "OFF"))
    return line


def render_menu(token):
    with STATE.lock:
        attached = STATE.session is not None
        auto = STATE.auto_on
        target = STATE.auto_target
    tables = list_tables()
    body = ["<div class='card'><h2>Status</h2>", attached_line(), "</div>"]
    body.append("<div class='card'><h2>Fitur</h2><ul>")
    for path, label in (("/processes", "Proses (attach / spawn)"),
                        ("/scan", "Cari &amp; refine nilai"),
                        ("/watchlist", "Watchlist &amp; tabel"),
                        ("/modules", "Modul target"),
                        ("/ranges", "Range memori target"),
                        ("/script", "Runner script JS"),
                        ("/il2cpp", "Dump IL2CPP (game Unity)")):
        body.append("<li><a href='" + path + "'>" + label + "</a></li>")
    body.append("</ul></div>")
    body.append("<div class='card'><h2>Auto-attach</h2>")
    if auto:
        body.append("<p class='ok'>ON — target: " + esc(target) + "</p>")
        body.append(form("/auto-attach", [], token, "Matikan auto-attach",
                         "sec"))
        body[-1] = body[-1].replace(
            "</form>",
            "<input type='hidden' name='action' value='off'></form>")
    else:
        body.append(form("/auto-attach", [
            ("target", txt("target", "", 24, "nama proses persis")),
            ("table", sel("table", [""] + tables)),
        ], token, "Nyalakan auto-attach"))
        body[-1] = body[-1].replace(
            "</form>",
            "<input type='hidden' name='action' value='on'>"
            "<label><input type='checkbox' name='apply' value='1'> "
            "auto-apply tabel</label> "
            "<label><input type='checkbox' name='freeze' value='1'> "
            "freeze yang ditandai</label></form>")
    body.append("<p class='dim'>Auto-attach memantau tiap 2 detik; "
                "bila target muncul, bridge attach + (opsional) "
                "terapkan tabel. Detach manual mematikan auto-attach."
                "</p></div>")
    if attached:
        body.append("<div class='card'><h2>Sesi</h2>")
        body.append(form("/detach", [], token, "Detach", "sec"))
        body.append("</div>")
    return page("Frida WebUI", "".join(body), token, refresh=10)


def render_processes(token, procs, note_text):
    with STATE.lock:
        attached_pid = STATE.attached_pid
    body = ["<div class='card'><h2>Proses</h2>", note(note_text),
            "<p class='dim'>Attach = suntik agent ke proses yang "
            "sedang jalan. Spawn = jalankan paket dalam keadaan "
            "tertahan, attach, lalu resume.</p>"]
    if attached_pid is not None:
        body.append("<p>Ter-attach: pid " + str(attached_pid) + " "
                    + form("/detach", [], token, "Detach", "sec")
                    + "</p>")
    body.append("<table><tr><th>PID</th><th>Nama</th><th></th></tr>")
    for proc in procs:
        mark = " <span class='ok'>&larr; ter-attach</span>" \
            if proc["pid"] == attached_pid else ""
        body.append("<tr><td>" + str(proc["pid"]) + "</td><td>"
                    + esc(proc["name"]) + mark + "</td><td>"
                    + form("/attach", [
                        ("pid", "<input type='hidden' name='pid' value='"
                         + str(proc["pid"]) + "'>"),
                    ], token, "Attach") + "</td></tr>")
    body.append("</table></div>")
    body.append("<div class='card'><h2>Spawn paket</h2>")
    body.append(form("/spawn", [
        ("package", txt("package", "", 30, "com.contoh.game")),
    ], token, "Spawn + attach"))
    body.append("<p class='dim'>Paket tidak harus sedang jalan; "
                "bridge spawn lalu resume setelah attach.</p></div>")
    body.append("<div class='card'><h2>Attach by nama</h2>")
    body.append(form("/attach", [
        ("name", txt("name", "", 30, "nama proses persis")),
    ], token, "Attach by nama"))
    body.append("</div>")
    return page("Proses", "".join(body), token)


def render_modules(token, mods, note_text):
    body = ["<div class='card'><h2>Modul</h2>", note(note_text),
            "<table><tr><th>Modul</th><th>Base</th><th>Ukuran</th>"
            "<th>Path</th></tr>"]
    for mod in mods:
        body.append("<tr><td>" + esc(mod["name"]) + "</td><td class='mono'>"
                    + addr_hex(mod["base"]) + "</td><td>"
                    + str(mod["size"]) + "</td><td class='mono'>"
                    + esc(mod["path"]) + "</td></tr>")
    body.append("</table></div>")
    return page("Modul", "".join(body), token)


def render_ranges(token, ranges, note_text):
    shown = ranges[:500]
    body = ["<div class='card'><h2>Range memori</h2>", note(note_text),
            "<p class='dim'>Menampilkan %d dari %d range.</p>"
            % (len(shown), len(ranges)),
            "<table><tr><th>Base</th><th>Ukuran</th>"
            "<th>Proteksi</th></tr>"]
    for rng in shown:
        body.append("<tr><td class='mono'>" + addr_hex(rng["base"])
                    + "</td><td>" + str(rng["size"]) + "</td><td>"
                    + esc(rng["protection"]) + "</td></tr>")
    body.append("</table></div>")
    return page("Range memori", "".join(body), token)


def entry_label(entry):
    if entry["module"]:
        return esc(entry["module"]) + "+0x%x" % entry["offset"]
    return "<span class='bad'>heap (tidak stabil)</span>"


def render_scan(token, results, note_text):
    with STATE.lock:
        scan_note = STATE.scan_note
    body = ["<div class='card'><h2>Cari nilai</h2>"]
    if scan_note:
        body.append(note(scan_note, ok=False))
    body.append(note(note_text))
    body.append(form("/scan", [
        ("type", sel("type", list(TYPES.keys()), "int32")),
        ("value", txt("value", "", 20, "nilai (0x.. boleh)")),
    ], token, "Cari"))
    body[-1] = body[-1].replace(
        "</form>",
        "<label><input type='checkbox' name='unknown' value='1'> "
        "unknown-initial (snapshot nilai awal, tipe numerik)"
        "</label></form>")
    body.append("<p class='dim'>Alur GG: cari nilai awal &rarr; ubah "
                "nilai di game &rarr; refine &rarr; tulis. Scan dibatasi "
                "5000 alamat; snapshot unknown dibatasi 1 jt entri dan "
                "range &le;64 MB.</p></div>")
    body.append("<div class='card'><h2>Refine</h2>")
    body.append(form("/refine", [
        ("mode", sel("mode", ["changed", "unchanged", "increased",
                              "decreased", "equal"])),
        ("value", txt("value", "", 20, "hanya untuk mode equal")),
    ], token, "Refine"))
    body.append("</div>")
    shown = results[:500]
    body.append("<div class='card'><h2>Hasil (%d, tampil %d)</h2>"
                % (len(results), len(shown)))
    if not results:
        body.append("<p class='dim'>Belum ada hasil — attach lalu cari."
                    "</p>")
    else:
        body.append("<table><tr><th>Alamat</th><th>Tipe</th><th>Nilai</th>"
                    "<th></th><th></th></tr>")
        for idx, entry in enumerate(shown):
            body.append("<tr><td class='mono'>" + entry_label(entry)
                        + "</td><td>" + esc(entry["type"]) + "</td><td>"
                        + esc(fmt_value(entry["type"], entry["value"]))
                        + "</td><td>"
                        + form("/watchlist/add", [
                            ("index", "<input type='hidden' name='index' "
                             "value='" + str(idx) + "'>"),
                        ], token, "&rarr; Watchlist") + "</td><td>"
                        + form("/write", [
                            ("back", "<input type='hidden' name='back' "
                             "value='scan'>"),
                            ("index", "<input type='hidden' name='index' "
                             "value='" + str(idx) + "'>"),
                            ("value", txt("value", "", 12, "nilai baru")),
                        ], token, "Tulis") + "</td></tr>")
        body.append("</table>")
    body.append("</div>")
    return page("Cari nilai", "".join(body), token)


def render_watchlist(token, live_values, note_text):
    with STATE.lock:
        entries = list(STATE.watchlist)
        frozen_on = STATE.freeze_on
    tables = list_tables()
    body = ["<div class='card'><h2>Watchlist</h2>", note(note_text),
            "<p>Freeze: <b>" + ("ON" if frozen_on else "OFF")
            + "</b> (rewrite tiap ~0,7 dtk, berhenti saat detach)"
            + form("/freeze", [
                ("run", "<input type='hidden' name='run' value='"
                 + ("off" if frozen_on else "on") + "'>"),
            ], token, "Matikan freeze" if frozen_on else "Jalankan freeze")
            + "</p>"]
    if not entries:
        body.append("<p class='dim'>Watchlist kosong — tambah dari "
                    "hasil scan.</p>")
    else:
        body.append("<table><tr><th>Alamat</th><th>Tipe</th>"
                    "<th>Nilai live</th><th>Freeze</th><th></th>"
                    "<th></th><th></th></tr>")
        for idx, entry in enumerate(entries):
            live = live_values[idx] if idx < len(live_values) else None
            body.append("<tr><td class='mono'>" + entry_label(entry)
                        + "</td><td>" + esc(entry["type"]) + "</td><td>"
                        + esc(fmt_value(entry["type"], live))
                        + "</td><td>"
                        + ("YA" if entry.get("frozen") else "-") + "</td><td>"
                        + form("/freeze", [
                            ("index", "<input type='hidden' name='index' "
                             "value='" + str(idx) + "'>"),
                            ("frozen", "<input type='hidden' name='frozen' "
                             "value='" + ("0" if entry.get("frozen") else "1")
                             + "'>"),
                        ], token, "Unfreeze" if entry.get("frozen")
                        else "Freeze") + "</td><td>"
                        + form("/write", [
                            ("back", "<input type='hidden' name='back' "
                             "value='watchlist'>"),
                            ("windex", "<input type='hidden' name='windex' "
                             "value='" + str(idx) + "'>"),
                            ("value", txt("value", "", 12, "nilai baru")),
                        ], token, "Tulis") + "</td><td>"
                        + form("/watchlist/del", [
                            ("index", "<input type='hidden' name='index' "
                             "value='" + str(idx) + "'>"),
                        ], token, "Hapus", "sec") + "</td></tr>")
        body.append("</table>")
    body.append("</div>")
    body.append("<div class='card'><h2>Tabel (per game)</h2>")
    body.append("<p class='dim'>Tabel disimpan sebagai JSON module+"
                "offset di /tmp/astro_frida_tables/ (persisten di disk)."
                " Alamat heap TIDAK disimpan (tidak stabil antar run)."
                "</p>")
    body.append(form("/table/save", [
        ("game", txt("game", "", 24, "nama game")),
    ], token, "Simpan watchlist jadi tabel"))
    if tables:
        body.append(form("/table/load", [
            ("table", sel("table", tables)),
        ], token, "Muat tabel"))
        body[-1] = body[-1].replace(
            "</form>",
            "<label><input type='checkbox' name='apply' value='1'> "
            "terapkan nilai</label> "
            "<label><input type='checkbox' name='freeze' value='1'> "
            "freeze yang ditandai</label></form>")
        body.append("<p class='dim'>Tabel tersedia: "
                    + esc(", ".join(tables)) + "</p>")
    else:
        body.append("<p class='dim'>Belum ada tabel tersimpan.</p>")
    body.append("</div>")
    return page("Watchlist", "".join(body), token)


def render_script(token, logs):
    body = ["<div class='card'><h2>Runner script JS</h2>",
            "<p class='dim'>Kirim agent script ke sesi (console.log "
            "via send() juga ditangkap). Script dijalankan max ~5 "
            "detik lalu di-unload.</p>"]
    body.append(form("/script", [
        ("code", "<textarea name='code' rows='10' cols='60' "
         "placeholder='console.log(&quot;halo&quot;);'></textarea>"),
    ], token, "Jalankan"))
    if logs is not None:
        body.append("<pre>" + esc("\n".join(logs)) + "</pre>")
    body.append("</div>")
    return page("Runner script", "".join(body), token)


def render_il2cpp(token, result, note_text):
    body = ["<div class='card'><h2>Dump IL2CPP</h2>", note(note_text)]
    if not result or not result.get("il2cpp"):
        reason = ""
        if result:
            reason = result.get("reason", "")
        body.append("<p class='bad'>Bukan IL2CPP"
                    + (": " + esc(reason) if reason else "")
                    + ".</p><p class='dim'>Target harus game Unity "
                    "(libil2cpp.so) dan sudah attach.</p>")
    else:
        classes = result.get("classes", [])
        body.append("<p class='ok'>%d class ditemukan%s.</p>"
                    % (len(classes),
                       " (dipotong, cap %d)" % IL2CPP_CLASS_CAP
                       if result.get("truncated") else ""))
        for klass in classes[:200]:
            title = (klass.get("ns") or "") + "." + klass.get("name", "")
            body.append("<h3>" + esc(title) + "</h3><ul>")
            for field in klass.get("fields", []):
                body.append("<li class='mono'>" + esc(field["name"])
                            + " @ 0x%x" % field["offset"] + "</li>")
            body.append("</ul>")
        if len(classes) > 200:
            body.append("<p class='dim'>Menampilkan 200 class pertama "
                        "dari %d.</p>" % len(classes))
    body.append("</div>")
    return page("IL2CPP", "".join(body), token)


def render_error(token, message):
    body = ("<div class='card'><h2>Terjadi kesalahan</h2>"
            "<p class='bad'>" + esc(message) + "</p></div>")
    return page("Kesalahan", body, token)


def render_unauthorized():
    body = ("<div class='card'><h2>Tidak diizinkan</h2>"
            "<p class='bad'>Token sesi tidak cocok atau tidak ada.</p>"
            "<p>Buka WebUI lewat tombol <b>Buka WebUI</b> di menu "
            "Frida aplikasi — token dibuat baru tiap Start dan "
            "tidak pernah tampil di URL.</p></div>")
    return page("Tidak diizinkan", body, "")


# ---------------------------------------------------------------- HTTP
class BridgeHandler(BaseHTTPRequestHandler):
    server_version = "AstroFrida/2"

    def log_message(self, _fmt, *_args):
        pass

    def _json(self, code, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _html(self, code, body):
        # page() sudah mengembalikan bytes; terima keduanya.
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _path(self):
        return urlparse(self.path).path

    def _token(self):
        return SESSION_TOKEN

    def _auth_header_cookie(self):
        header = self.headers.get(TOKEN_HEADER, "")
        if token_ok(header, self._token()):
            return True
        cookie = self.headers.get("Cookie", "")
        cookie_token = parse_cookies(cookie).get("session", "")
        return token_ok(cookie_token, self._token())

    def _read_fields(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length > MAX_BODY:
            return {}
        raw = self.rfile.read(length) if length > 0 else b""
        try:
            parsed = parse_qs(raw.decode("utf-8", "replace"))
        except Exception:
            return {}
        return {key: values[0] for key, values in parsed.items()}

    def _auth(self, fields):
        if self._auth_header_cookie():
            return True
        return token_ok(fields.get("token", ""), self._token())

    def do_GET(self):
        path = self._path()
        if path == "/health":
            self._json(200, {"ok": True, "service": "astro-frida-bridge"})
            return
        if path == "/status":
            if not self._auth_header_cookie():
                self._json(401, {"ok": False, "error": "unauthorized"})
                return
            with STATE.lock:
                attached_pid = STATE.attached_pid
                attached_name = STATE.attached_name
                scan_n = len(STATE.scan_results)
                watch_n = len(STATE.watchlist)
                frozen_n = sum(1 for w in STATE.watchlist
                               if w.get("frozen"))
                auto = STATE.auto_on
            self._json(200, {"ok": True, "token_ok": True,
                             "attached_pid": attached_pid,
                             "attached_name": attached_name,
                             "scan_count": scan_n,
                             "watchlist_count": watch_n,
                             "frozen_count": frozen_n,
                             "auto_attach": auto,
                             "frida_available": FRIDA_AVAILABLE})
            return
        if not self._auth_header_cookie():
            self._html(401, render_unauthorized())
            return
        token = self._token()
        try:
            if path == "/":
                self._html(200, render_menu(token))
            elif path == "/processes":
                self._html(200, render_processes(
                    token, list_processes(), ""))
            elif path == "/modules":
                self._html(200, render_modules(token, get_modules(), ""))
            elif path == "/ranges":
                self._html(200, render_ranges(token, get_ranges(), ""))
            elif path == "/scan":
                with STATE.lock:
                    results = list(STATE.scan_results)
                self._html(200, render_scan(token, results, ""))
            elif path == "/watchlist":
                with STATE.lock:
                    entries = list(STATE.watchlist)
                live = []
                if entries:
                    try:
                        live = read_values(entries)
                    except FridaError:
                        live = [None] * len(entries)
                self._html(200, render_watchlist(token, live, ""))
            elif path == "/script":
                self._html(200, render_script(token, None))
            elif path == "/il2cpp":
                ensure_session()
                result = run_agent_once(IL2CPP_AGENT, action="il2cpp")
                self._html(200, render_il2cpp(token, result, ""))
            else:
                self._html(404, render_error(token, "halaman tidak ada"))
        except FridaError as exc:
            self._html(200, render_error(token, str(exc)))
        except Exception as exc:
            sys.stderr.write("bridge error: %s: %s\n"
                             % (type(exc).__name__, exc))
            self._html(200, render_error(
                token, "error internal: %s" % type(exc).__name__))

    def do_POST(self):
        path = self._path()
        fields = self._read_fields()
        if not self._auth(fields):
            if path == "/status":
                self._json(401, {"ok": False, "error": "unauthorized"})
            else:
                self._html(401, render_unauthorized())
            return
        token = self._token()
        try:
            if path == "/attach":
                note_text = ""
                pid_text = fields.get("pid", "")
                name = fields.get("name", "")
                if pid_text:
                    pid = int(pid_text)
                    procs = {p["pid"]: p["name"] for p in list_processes()}
                    pname = procs.get(pid, str(pid))
                    attach_to_pid(pid, pname)
                    note_text = "ter-attach ke %s (pid %d)" % (pname, pid)
                elif name:
                    pid = find_pid_by_name(name)
                    if pid is None:
                        raise FridaError("proses '%s' tidak ditemukan — "
                                         "pastikan aplikasi sedang jalan"
                                         % name)
                    attach_to_pid(pid, name)
                    note_text = "ter-attach ke %s (pid %d)" % (name, pid)
                else:
                    raise FridaError("isi pid atau nama proses")
                self._html(200, render_processes(
                    token, list_processes(), note_text))
            elif path == "/spawn":
                package = fields.get("package", "")
                if not package:
                    raise FridaError("isi nama paket")
                dev = get_device()
                detach_session()
                try:
                    pid = dev.spawn(package)
                    sess = dev.attach(pid)
                    dev.resume(pid)
                except Exception as exc:
                    raise FridaError("gagal spawn %s: %s"
                                     % (package, exc))
                with STATE.lock:
                    STATE.session = sess
                    STATE.attached_pid = pid
                    STATE.attached_name = package
                self._html(200, render_processes(
                    token, list_processes(),
                    "spawn + attach OK: %s (pid %d)" % (package, pid)))
            elif path == "/detach":
                detach_session()
                # Detach manual mematikan auto-attach (aturan clean).
                stop_auto_thread()
                self._html(200, render_menu(token))
            elif path == "/scan":
                vtype = fields.get("type", "int32")
                if vtype not in TYPES:
                    raise FridaError("tipe tidak dikenal: " + vtype)
                unknown = fields.get("unknown", "") == "1"
                value = fields.get("value", "")
                entries, scan_note = run_scan(vtype, value, unknown)
                with STATE.lock:
                    STATE.scan_results = entries
                    STATE.scan_note = scan_note
                self._html(200, render_scan(
                    token, entries,
                    "%d alamat ditemukan" % len(entries)))
            elif path == "/refine":
                mode = fields.get("mode", "changed")
                value = fields.get("value", "")
                kept, refine_note = refine_entries(mode, value)
                with STATE.lock:
                    STATE.scan_results = kept
                self._html(200, render_scan(token, kept, refine_note))
            elif path == "/write":
                back = fields.get("back", "scan")
                value = fields.get("value", "")
                if "index" in fields:
                    with STATE.lock:
                        results = list(STATE.scan_results)
                    idx = int(fields["index"])
                    if idx < 0 or idx >= len(results):
                        raise FridaError("indeks hasil scan tidak valid")
                    entry = dict(results[idx])
                    entry["value"] = parse_value(entry["type"], value)
                    # Tulis manual dari halaman scan = aksi sadar user:
                    # entri heap boleh ditulis lewat alamat absolutnya.
                    if not entry["module"] and entry.get("absolute") is None:
                        raise FridaError("alamat tidak bisa di-resolve")
                    errs = write_entries([entry])
                    if errs:
                        raise FridaError(" ; ".join(errs))
                    with STATE.lock:
                        STATE.scan_results[idx]["value"] = entry["value"]
                    self._html(200, render_scan(
                        token, list(STATE.scan_results),
                        "tertulis: %s" % entry_label(entry)))
                elif "windex" in fields:
                    with STATE.lock:
                        entries = list(STATE.watchlist)
                    idx = int(fields["windex"])
                    if idx < 0 or idx >= len(entries):
                        raise FridaError("indeks watchlist tidak valid")
                    entry = dict(entries[idx])
                    entry["value"] = parse_value(entry["type"], value)
                    errs = write_entries([entry])
                    if errs:
                        raise FridaError(" ; ".join(errs))
                    with STATE.lock:
                        STATE.watchlist[idx]["value"] = entry["value"]
                        entries = list(STATE.watchlist)
                    live = []
                    try:
                        live = read_values(entries)
                    except FridaError:
                        live = [None] * len(entries)
                    self._html(200, render_watchlist(
                        token, live, "tertulis: %s" % entry_label(entry)))
                else:
                    raise FridaError("target tulis tidak dikenali")
            elif path == "/freeze":
                run = fields.get("run", "")
                if run == "on":
                    with STATE.lock:
                        has_frozen = any(w.get("frozen")
                                         for w in STATE.watchlist)
                    if not has_frozen:
                        raise FridaError("tidak ada entri yang ditandai "
                                         "freeze — tandai dulu")
                    start_freeze_thread()
                elif run == "off":
                    stop_freeze_thread()
                elif "index" in fields:
                    idx = int(fields["index"])
                    with STATE.lock:
                        if idx < 0 or idx >= len(STATE.watchlist):
                            raise FridaError("indeks watchlist tidak "
                                             "valid")
                        STATE.watchlist[idx]["frozen"] = (
                            fields.get("frozen", "0") == "1")
                        entries = list(STATE.watchlist)
                else:
                    raise FridaError("aksi freeze tidak dikenali")
                with STATE.lock:
                    entries = list(STATE.watchlist)
                live = []
                try:
                    live = read_values(entries)
                except FridaError:
                    live = [None] * len(entries)
                self._html(200, render_watchlist(token, live, ""))
            elif path == "/watchlist/add":
                with STATE.lock:
                    results = list(STATE.scan_results)
                idx = int(fields.get("index", "-1"))
                if idx < 0 or idx >= len(results):
                    raise FridaError("indeks hasil scan tidak valid")
                entry = dict(results[idx])
                if not entry["module"]:
                    raise FridaError("alamat heap tidak bisa masuk "
                                     "watchlist permanen (tidak stabil "
                                     "antar run) — tulis manual saja")
                with STATE.lock:
                    STATE.watchlist.append(entry)
                    entries = list(STATE.watchlist)
                live = []
                try:
                    live = read_values(entries)
                except FridaError:
                    live = [None] * len(entries)
                self._html(200, render_watchlist(
                    token, live, "ditambahkan ke watchlist"))
            elif path == "/watchlist/del":
                idx = int(fields.get("index", "-1"))
                with STATE.lock:
                    if idx < 0 or idx >= len(STATE.watchlist):
                        raise FridaError("indeks watchlist tidak valid")
                    del STATE.watchlist[idx]
                    entries = list(STATE.watchlist)
                live = []
                try:
                    live = read_values(entries)
                except FridaError:
                    live = [None] * len(entries)
                self._html(200, render_watchlist(token, live, "dihapus"))
            elif path == "/table/save":
                game = fields.get("game", "")
                with STATE.lock:
                    entries = list(STATE.watchlist)
                path_saved, saved_n = save_table(game, entries)
                with STATE.lock:
                    entries = list(STATE.watchlist)
                live = []
                try:
                    live = read_values(entries)
                except FridaError:
                    live = [None] * len(entries)
                self._html(200, render_watchlist(
                    token, live,
                    "tabel '%s' tersimpan (%d entri; entri heap dilewati)"
                    % (sanitize_table_name(game), saved_n)))
            elif path == "/table/load":
                table = fields.get("table", "")
                if not table:
                    raise FridaError("pilih tabel dulu")
                entries, unresolved, err = load_table(table)
                if err:
                    raise FridaError(err)
                with STATE.lock:
                    STATE.watchlist = entries
                note_text = ("tabel '%s' dimuat: %d entri"
                             % (sanitize_table_name(table), len(entries)))
                if unresolved:
                    note_text += ("; %d tidak bisa di-resolve (modul "
                                  "tidak ditemukan / heap)" % len(unresolved))
                if fields.get("apply", "") == "1" and entries:
                    errs = write_entries(entries)
                    if errs:
                        note_text += "; tulis gagal: " + " ; ".join(errs)
                    else:
                        note_text += "; nilai diterapkan"
                    if fields.get("freeze", "") == "1" and any(
                            e.get("frozen") for e in entries):
                        start_freeze_thread()
                        note_text += "; freeze jalan"
                live = []
                try:
                    live = read_values(entries)
                except FridaError:
                    live = [None] * len(entries)
                self._html(200, render_watchlist(token, live, note_text))
            elif path == "/script":
                code = fields.get("code", "")
                if not code.strip():
                    raise FridaError("isi script dulu")
                ensure_session()
                logs = run_agent_collect(code)
                self._html(200, render_script(token, logs))
            elif path == "/auto-attach":
                action = fields.get("action", "")
                if action == "off":
                    stop_auto_thread()
                elif action == "on":
                    target = fields.get("target", "")
                    if not target:
                        raise FridaError("isi nama proses target")
                    table = fields.get("table", "")
                    start_auto_thread(target, table,
                                      fields.get("apply", "") == "1",
                                      fields.get("freeze", "") == "1")
                else:
                    raise FridaError("aksi auto-attach tidak dikenali")
                self._html(200, render_menu(token))
            else:
                self._html(404, render_error(token, "endpoint tidak ada"))
        except FridaError as exc:
            self._html(200, render_error(token, str(exc)))
        except (ValueError, KeyError, IndexError) as exc:
            self._html(200, render_error(
                token, "input tidak valid: %s" % exc))
        except Exception as exc:
            sys.stderr.write("bridge error: %s: %s\n"
                             % (type(exc).__name__, exc))
            self._html(200, render_error(
                token, "error internal: %s" % type(exc).__name__))

    def do_PUT(self):
        self._json(404, {"ok": False, "error": "not found"})

    def do_DELETE(self):
        self._json(404, {"ok": False, "error": "not found"})


def main():
    token = os.environ.get("ASTRO_FRIDA_TOKEN", "")
    if not token:
        sys.stderr.write("ASTRO_FRIDA_TOKEN kosong — keluar\n")
        sys.exit(2)
    global SESSION_TOKEN
    SESSION_TOKEN = token
    fingerprint = hashlib.sha256(token.encode("utf-8")).hexdigest()[:12]
    sys.stderr.write("astro-frida-bridge v%d listen 127.0.0.1:%d "
                     "token fp %s\n" % (BRIDGE_VERSION, PORT, fingerprint))
    server = ThreadingHTTPServer((HOST, PORT), BridgeHandler)
    server.daemon_threads = True
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
