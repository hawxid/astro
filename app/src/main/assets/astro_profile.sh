# Astro chroot shell environment.
#
# Deployed into the chroot at /etc/astro_profile.sh and sourced from
# /etc/profile, so every interactive login shell the terminal opens (it enters
# via `bash -l`) gets a cool, root-aware, blue two-line prompt instead of the
# bland default. Also adds a few quality-of-life aliases.
#
# Note: \h is intentionally NOT used — the chroot shares the device UTS
# namespace, so \h is the Android hostname ("localhost"), which is exactly the
# dull prompt this replaces. We brand the prompt "⚡Astro" instead.

# Only dress up interactive shells.
case "$-" in
    *i*) ;;
    *) return 0 2>/dev/null || exit 0 ;;
esac

# Blue palette, wrapped in \[ \] so the line editor keeps the cursor maths right.
__s_rail='\[\033[1;38;5;33m\]'   # bold blue — rails
__s_path='\[\033[38;5;75m\]'     # light blue — path
__s_dim='\[\033[2m\]'
__s_rst='\[\033[0m\]'

# Root → brighter blue brand; normal user → blue. \$ renders '#' for root, '$' otherwise.
if [ "$(id -u 2>/dev/null)" = "0" ]; then
    __s_brand='\[\033[1;38;5;39m\]'
else
    __s_brand='\[\033[1;38;5;33m\]'
fi
__s_tip="${__s_brand}"'\$'"${__s_rst}"

#   ┌──(⚡Astro)─[ ~/current/path ]
#   └─#
PS1="${__s_rail}┌──${__s_rst}${__s_dim}(${__s_rst}${__s_brand}⚡Astro${__s_rst}${__s_dim})${__s_rst}${__s_dim}─[${__s_rst}${__s_path}\\w${__s_rst}${__s_dim}]${__s_rst}\n${__s_rail}└─${__s_rst}${__s_tip} "
PS2="${__s_rail}└─${__s_rst}${__s_dim}>${__s_rst} "
export PS1 PS2
[ -z "$TERM" ] && export TERM=xterm-256color

# Quality-of-life aliases.
alias ls='ls --color=auto'
alias ll='ls -lh'
alias la='ls -lha'
alias l='ls -CF'
alias ..='cd ..'
alias ...='cd ../..'
alias grep='grep --color=auto'
alias egrep='egrep --color=auto'
alias ports='netstat -tulpn 2>/dev/null || ss -tulpn'

# One tasteful banner per interactive login shell.
if [ -z "$__STRYKER_GREETED" ]; then
    __STRYKER_GREETED=1
    printf '\033[1;38;5;39m⚡ ASTRO\033[0m \033[2m- pentest shell\033[0m\n'
fi

unset __s_rail __s_path __s_dim __s_rst __s_brand __s_tip

# Model xrun gabung (pisah sebagian, pengganti don + xrun-shim lama).
# Dulu: don (display polos) dan xrun-shim (display per-tool) itu dua
# perintah + dua watcher. Sekarang SATU perintah `xrun`, satu watcher:
#   xrun           -> jendela kosong + DISPLAY (perilaku don lama);
#                     display ini MINI-DESKTOP ber-openbox di dalam
#                     (revisi 2026-10-25): tool dari tab berdekorasi,
#                     bisa geser/resize/tutup sendiri.
#   xrun <cmd>     -> jendela langsung isi tool (perilaku xrun lama),
#                     TANPA nyentuh DISPLAY tab; display per-tool ini
#                     TETAP bare (tanpa openbox).
#   doff           -> matikan display polos tab ini.
# TIDAK ADA hook PROMPT_COMMAND, TIDAK ADA biaya per-prompt.
# HARUS fungsi (bukan alias: alias tak bisa loop/tunggu/timeout bersih;
# bukan binary: child tak bisa export ke shell induk).
# Identitas: PPID bash = pid sesi kalau su nge-fork (kasus perangkat);
# $$ ditulis sebagai isi request sebagai kunci cadangan (kalau su exec
# langsung, $$ = pid sesi). App memetakan keduanya.
# Aturan "sudah jalan": shell TAK PERNAH menebak dari DISPLAY — status
# itu HANYA datang dari ready manager ("R:N" = tampil lagi, ":N" =
# baru). Cek socket di bawah cuma kebersihan: DISPLAY set tapi
# socket mati (mis. display dibunuh dari dock) -> unset supaya tak
# ada DISPLAY basi kalau request timeout.
xrun() {
    _xrun_req="/tmp/astro-xrun-requests"
    _xrun_ready="/tmp/astro-xrun-ready"
    if [ "$#" -gt 0 ]; then
        # Ber-argumen: fire-and-forget seperti xrun lama.
        mkdir -p "$_xrun_req" 2>/dev/null
        _xrun_epoch=$(date +%s 2>/dev/null || echo 0)
        printf '%s' "$*" > "$_xrun_req/xrun-cmd-${PPID:-$$}-$_xrun_epoch.tmp" 2>/dev/null \
            && mv "$_xrun_req/xrun-cmd-${PPID:-$$}-$_xrun_epoch.tmp" "$_xrun_req/xrun-cmd-${PPID:-$$}-$_xrun_epoch" 2>/dev/null
        echo "xrun: terkirim: $*"
        unset _xrun_req _xrun_ready _xrun_epoch
        return 0
    fi
    # Polos: cek socket dulu, lalu tulis request + tunggu ready.
    if [ -n "$DISPLAY" ]; then
        _xrun_sock="/tmp/.X11-unix/X${DISPLAY#:}"
        if [ ! -S "$_xrun_sock" ]; then
            unset DISPLAY
        fi
        unset _xrun_sock
    fi
    _xrun_ppid="${PPID:-$$}"
    mkdir -p "$_xrun_req" 2>/dev/null
    _xrun_epoch=$(date +%s 2>/dev/null || echo 0)
    printf '%s' "$$" > "$_xrun_req/xrun-$_xrun_ppid-$_xrun_epoch.tmp" 2>/dev/null \
        && mv "$_xrun_req/xrun-$_xrun_ppid-$_xrun_epoch.tmp" "$_xrun_req/xrun-$_xrun_ppid-$_xrun_epoch" 2>/dev/null
    if [ ! -f "$_xrun_req/xrun-$_xrun_ppid-$_xrun_epoch" ]; then
        echo "display gagal: request tidak bisa ditulis"
        unset _xrun_req _xrun_ready _xrun_ppid _xrun_epoch
        return 1
    fi
    _xrun_i=0
    while [ "$_xrun_i" -lt 40 ]; do
        if [ -f "$_xrun_ready/$_xrun_ppid" ]; then
            _xrun_d=$(cat "$_xrun_ready/$_xrun_ppid" 2>/dev/null)
            rm -f "$_xrun_ready/$_xrun_ppid" 2>/dev/null
            case "$_xrun_d" in
                :[0-9]*)
                    export DISPLAY="$_xrun_d"
                    echo "display siap: $_xrun_d"
                    unset _xrun_req _xrun_ready _xrun_ppid _xrun_epoch _xrun_i _xrun_d
                    return 0
                    ;;
                R:[0-9]*)
                    export DISPLAY=":${_xrun_d#R:}"
                    echo "sudah jalan: :${_xrun_d#R:}"
                    unset _xrun_req _xrun_ready _xrun_ppid _xrun_epoch _xrun_i _xrun_d
                    return 0
                    ;;
                POOL_FULL)
                    echo "display terminal penuh (:1-:2 jalan) — matikan satu display dulu (dock tahan -> Matikan, atau doff di tabnya)"
                    unset _xrun_req _xrun_ready _xrun_ppid _xrun_epoch _xrun_i _xrun_d
                    return 1
                    ;;
                *)
                    echo "display gagal start — cek log /sdcard/Astro/x11-debug.log"
                    unset _xrun_req _xrun_ready _xrun_ppid _xrun_epoch _xrun_i _xrun_d
                    return 1
                    ;;
            esac
        fi
        sleep 0.5 2>/dev/null || sleep 1
        _xrun_i=$((_xrun_i + 1))
    done
    echo "display timeout (server tidak merespons ~20 detik) — cek log /sdcard/Astro/x11-debug.log"
    unset _xrun_req _xrun_ready _xrun_ppid _xrun_epoch _xrun_i _xrun_d
    return 1
}

doff() {
    if [ -z "$DISPLAY" ]; then
        echo "display sudah mati"
        return 0
    fi
    _doff_ppid="${PPID:-$$}"
    _doff_req="/tmp/astro-xrun-requests"
    mkdir -p "$_doff_req" 2>/dev/null
    _doff_epoch=$(date +%s 2>/dev/null || echo 0)
    printf '%s' "$$" > "$_doff_req/off-$_doff_ppid-$_doff_epoch.tmp" 2>/dev/null \
        && mv "$_doff_req/off-$_doff_ppid-$_doff_epoch.tmp" "$_doff_req/off-$_doff_ppid-$_doff_epoch" 2>/dev/null
    unset DISPLAY
    echo "display dimatikan"
    unset _doff_ppid _doff_req _doff_epoch
    return 0
}
