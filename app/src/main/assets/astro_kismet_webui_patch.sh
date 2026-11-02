#!/bin/sh
# Patch WebUI Kismet untuk Astro Tools (Fase 2, 2026-11-02).
# Dijalankan best-effort oleh KismetToolsManager tiap start bridge;
# KEGAGALAN DI SINI TIDAK PERNAH menggagalkan start bridge (mod
# WebUI hanya add-on; bridge tetap jalan).
#
# Cara kerja (tahan update apt):
# 1. Cari direktori httpd Kismet (dpkg -L, lalu fallback umum).
# 2. Salin astro_tools.js (dari /tmp, sudah dideploy manager) ke
#    httpd/js/astro_tools.js.
# 3. index.html: bila sudah mengandung tag skrip -> selesai.
#    Bila belum: backup dulu (hanya bila backup belum ada ATAU
#    index.html berubah dari backup = upstream baru dari apt),
#    lalu sisipkan <script> sebelum </body> (atau akhir file).
# Backup TIDAK PERNAH menimpa backup yang sudah ada dari upstream
# yang sama — jadi file asli selalu bisa dikembalikan.

JS_SRC="/tmp/astro_kismet_webui_tools.js"
TAG='<script src="js/astro_tools.js"></script>'

HTTPD_DIR=""
# 1a. Dari daftar file paket kismet (paling akurat).
if command -v dpkg >/dev/null 2>&1; then
    IDX=$(dpkg -L kismet 2>/dev/null | grep -m1 '/httpd/index\.html$' || true)
    if [ -n "$IDX" ]; then
        HTTPD_DIR=$(dirname "$IDX")
    fi
fi
# 1b. Fallback lokasi umum.
if [ -z "$HTTPD_DIR" ] || [ ! -f "$HTTPD_DIR/index.html" ]; then
    for d in /usr/share/kismet/httpd /usr/local/share/kismet/httpd; do
        if [ -f "$d/index.html" ]; then
            HTTPD_DIR="$d"
            break
        fi
    done
fi
if [ -z "$HTTPD_DIR" ] || [ ! -f "$HTTPD_DIR/index.html" ]; then
    echo "ASTRO-WEBUI-PATCH: httpd tidak ditemukan"
    exit 1
fi

if [ ! -f "$JS_SRC" ]; then
    echo "ASTRO-WEBUI-PATCH: $JS_SRC tidak ada"
    exit 1
fi

cp "$JS_SRC" "$HTTPD_DIR/js/astro_tools.js" 2>/dev/null || {
    echo "ASTRO-WEBUI-PATCH: gagal menyalin JS ke $HTTPD_DIR/js/"
    exit 1
}

IDX="$HTTPD_DIR/index.html"
BAK="$HTTPD_DIR/index.html.astro-bak"

if grep -q 'astro_tools\.js' "$IDX" 2>/dev/null; then
    echo "ASTRO-WEBUI-PATCH: sudah terpasang ($HTTPD_DIR)"
    exit 0
fi

# index.html belum bertag: backup bila belum ada, atau bila isinya
# berubah dari backup (= upstream baru sesudah update apt).
if [ ! -f "$BAK" ]; then
    cp "$IDX" "$BAK"
    echo "ASTRO-WEBUI-PATCH: backup dibuat ($BAK)"
elif ! cmp -s "$IDX" "$BAK"; then
    cp "$IDX" "$BAK"
    echo "ASTRO-WEBUI-PATCH: backup diperbarui (upstream baru)"
fi

if grep -q '</body>' "$IDX"; then
    # Sisip sebelum </body> (pertama saja).
    sed -i "0,/<\/body>/s||$TAG\n</body>|" "$IDX"
else
    printf '\n%s\n' "$TAG" >> "$IDX"
fi

if grep -q 'astro_tools\.js' "$IDX" 2>/dev/null; then
    echo "ASTRO-WEBUI-PATCH: terpasang ($HTTPD_DIR)"
    exit 0
else
    echo "ASTRO-WEBUI-PATCH: gagal menyisipkan tag"
    exit 1
fi
