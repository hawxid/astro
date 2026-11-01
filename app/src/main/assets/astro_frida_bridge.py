#!/usr/bin/env python3
"""Astro Frida bridge (fondasi Batch16, 2026-11-01).

Proses chroot Python yang NANTI melayani web UI Frida. Batch ini
sengaja minimal, sesuai scope yang disetujui Hawx (management server
+ minimize-ke-ikon; halaman web UI Frida masih "tunggu dulu"):
bridge hanya perlu ada, start/stop bersih, dan mengekspos health
check + token sesi.

Keamanan (aturan keras, dari diskusi keamanan 2026-11-01):
- Bind 127.0.0.1 SAJA — tidak pernah 0.0.0.0. Chroot tidak
  mengisolasi network, jadi 127.0.0.1 chroot = 127.0.0.1 Android;
  localhost-only berarti tidak kena LAN/Wi-Fi.
- Token sesi acak per start (env ASTRO_FRIDA_TOKEN, dibuat
  FridaManager). Tanpa token, SEMUA endpoint kecuali /health
  menjawab 401 — aplikasi lokal iseng tidak bisa asal memanggil
  API bridge. Token TIDAK PERNAH dicetak ke log.
- Fail closed: bila env token kosong/tidak ada, proses menolak
  start (exit 2) — bridge tanpa token lebih berbahaya daripada
  tidak ada bridge sama sekali.
- Hanya stdlib (http.server) — tanpa dependensi pip.

Endpoint batch ini:
- GET /health  (tanpa token, liveness saja, tidak membocorkan apa pun)
- GET /status  (butuh header X-Astro-Token)
- Lainnya -> 404. Endpoint Frida (daftar proses, scan, tulis, ...)
  mendarat di batch web UI, bukan di sini.

Jalan: ASTRO_FRIDA_TOKEN=<token> python3 astro_frida_bridge.py
Log: /tmp/astro_frida_bridge.log (dialihkan perintah start).
Berhenti: pkill -f astro_frida_bridge.py (dari FridaManager.stop,
SEBELUM frida-server dimatikan — urutan kill: klien dulu, server
kemudian).
"""

import hashlib
import hmac
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 18081
TOKEN_HEADER = "X-Astro-Token"


def _token_ok(handler):
    """True bila header token cocok (compare_digest, anti-timing)."""
    given = handler.headers.get(TOKEN_HEADER)
    if given is None:
        return False
    expected = handler.server.bridge_token
    return hmac.compare_digest(given, expected)


class BridgeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send_json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"ok": True, "service": "astro-frida-bridge"})
            return
        if self.path == "/status":
            if not _token_ok(self):
                self._send_json(401, {"ok": False, "error": "unauthorized"})
                return
            self._send_json(200, {"ok": True, "token_ok": True})
            return
        self._send_json(404, {"ok": False, "error": "not found"})

    # Metode lain tidak dilayani batch ini — tetap 404, bukan 405,
    # supaya tidak membocorkan daftar method.
    def do_POST(self):
        self._send_json(404, {"ok": False, "error": "not found"})

    def do_PUT(self):
        self._send_json(404, {"ok": False, "error": "not found"})

    def do_DELETE(self):
        self._send_json(404, {"ok": False, "error": "not found"})

    def log_message(self, fmt, *args):
        # Diam untuk request biasa (log chroot tidak boleh jadi
        # tempat token beredar); error Python tetap ke stderr.
        pass


def main():
    token = os.environ.get("ASTRO_FRIDA_TOKEN", "")
    if not token:
        sys.stderr.write(
            "astro_frida_bridge: ASTRO_FRIDA_TOKEN kosong — "
            "menolak start tanpa token (fail closed)\n"
        )
        return 2
    # Jejak token di memori saja; jangan pernah print token.
    fingerprint = hashlib.sha256(token.encode("utf-8")).hexdigest()[:12]
    server = ThreadingHTTPServer((HOST, PORT), BridgeHandler)
    server.bridge_token = token
    server.daemon_threads = True
    sys.stderr.write(
        "astro_frida_bridge: listening 127.0.0.1:%d (token sha256:%s)\n"
        % (PORT, fingerprint)
    )
    sys.stderr.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
