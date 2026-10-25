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

# Model eksplisit don/doff (pengganti hook display per-tab).
# Dulu: hook PROMPT_COMMAND tiap prompt stat marker (biaya per-prompt +
# identitas pid sesi vs bash yang rapuh — su nge-fork bikin marker tak
# pernah ketemu). Sekarang: TIDAK ADA hook, TIDAK ADA biaya per-prompt.
# User ketik don di tab -> fungsi tulis request -> watcher app nyalakan
# server -> fungsi baca ready file -> export DISPLAY di shell INI SENDIRI.
# HARUS fungsi (bukan alias: alias tak bisa loop/tunggu/timeout bersih;
# bukan binary: child tak bisa export ke shell induk).
# Identitas: PPID bash = pid sesi kalau su nge-fork (kasus perangkat);
# $$ ditulis sebagai isi request sebagai kunci cadangan (kalau su exec
# langsung, $$ = pid sesi). App memetakan keduanya, jadi doff dan
# TAB_REMOVED cocok di kedua perilaku su.
don() {
    if [ -n "$DISPLAY" ]; then
        echo "sudah jalan: $DISPLAY"
        return 0
    fi
    _don_ppid="${PPID:-$$}"
    _don_req="/tmp/astro-display-requests"
    mkdir -p "$_don_req" 2>/dev/null
    _don_epoch=$(date +%s 2>/dev/null || echo 0)
    printf '%s' "$$" > "$_don_req/don-$_don_ppid-$_don_epoch.tmp" 2>/dev/null \
        && mv "$_don_req/don-$_don_ppid-$_don_epoch.tmp" "$_don_req/don-$_don_ppid-$_don_epoch" 2>/dev/null
    if [ ! -f "$_don_req/don-$_don_ppid-$_don_epoch" ]; then
        echo "display gagal: request tidak bisa ditulis"
        unset _don_ppid _don_req _don_epoch
        return 1
    fi
    _don_i=0
    while [ "$_don_i" -lt 40 ]; do
        if [ -f "/tmp/astro-display-ready/$_don_ppid" ]; then
            _don_d=$(cat "/tmp/astro-display-ready/$_don_ppid" 2>/dev/null)
            rm -f "/tmp/astro-display-ready/$_don_ppid" 2>/dev/null
            case "$_don_d" in
                :[0-9]*)
                    export DISPLAY="$_don_d"
                    echo "display siap: $_don_d"
                    unset _don_ppid _don_req _don_epoch _don_i _don_d
                    return 0
                    ;;
                POOL_FULL)
                    echo "display penuh (:1-:4 jalan) — matikan satu display dulu (dock tahan -> Matikan, atau doff di tabnya)"
                    unset _don_ppid _don_req _don_epoch _don_i _don_d
                    return 1
                    ;;
                *)
                    echo "display gagal start — cek log /sdcard/Astro/x11-debug.log"
                    unset _don_ppid _don_req _don_epoch _don_i _don_d
                    return 1
                    ;;
            esac
        fi
        sleep 0.5 2>/dev/null || sleep 1
        _don_i=$((_don_i + 1))
    done
    echo "display timeout (server tidak merespons ~20 detik) — cek log /sdcard/Astro/x11-debug.log"
    unset _don_ppid _don_req _don_epoch _don_i _don_d
    return 1
}

doff() {
    if [ -z "$DISPLAY" ]; then
        echo "display sudah mati"
        return 0
    fi
    _doff_ppid="${PPID:-$$}"
    _doff_req="/tmp/astro-display-requests"
    mkdir -p "$_doff_req" 2>/dev/null
    _doff_epoch=$(date +%s 2>/dev/null || echo 0)
    printf '%s' "$$" > "$_doff_req/off-$_doff_ppid-$_doff_epoch.tmp" 2>/dev/null \
        && mv "$_doff_req/off-$_doff_ppid-$_doff_epoch.tmp" "$_doff_req/off-$_doff_ppid-$_doff_epoch" 2>/dev/null
    unset DISPLAY
    echo "display dimatikan"
    unset _doff_ppid _doff_req _doff_epoch
    return 0
}
