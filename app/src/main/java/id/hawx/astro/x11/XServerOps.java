package id.hawx.astro.x11;

import id.hawx.astro.utils.Core;

/**
 * Mekanik server X per-display, TANPA policy/map/watcher.
 *
 * Dipakai dua manager (pisah sebagian, ronde terminal 2026-10-25):
 * XSessionManager (desktop :0/:3-:4) dan XTerminalManager (terminal
 * :1-:2). Satu sumber kebenaran buat probe/start/kill/setup/fit —
 * policy (range display, map, watcher, revive openbox, DPI prefs)
 * tetap di manager masing-masing, bukan di sini.
 *
 * Dipindah verbatim dari XSessionManager (c505290) kecuali:
 * - setup sesi jadi parameter (dulu ditempel XRUN_DEPLOY + panggil
 *   watcher shim xrun lama — keduanya mati bersama model toggle);
 * - pesan sukses setup jadi parameter (dulu dibedakan d==0 vs d>0
 *   di dalam method);
 * - update state :0 (dulu di probeSync/ensure/kill) pindah ke wrapper
 *   XSessionManager — helper ini stateless soal state manager.
 */
class XServerOps {

    public enum State { STOPPED, STARTING, RUNNING }

    static class Probe {
        State state = State.STOPPED;
        boolean sock = false;
        boolean zombie = false;
        String detail = "";
    }

    private final Core core;
    private String lastProbeSummary = "";

    XServerOps(Core core) {
        this.core = core;
    }

    // ------------------------------------------------------------ paths

    static String root() { return Core.CHROOT_ROOT; }
    static String tmp() { return root() + "/tmp"; }
    static String sockPath(int d) { return tmp() + "/.X11-unix/X" + d; }
    static String pidPath(int d) {
        return d == 0 ? tmp() + "/astro-x11-server.pid"
                : tmp() + "/astro-x11-server-d" + d + ".pid";
    }
    static String serverLog(int d) {
        return d == 0 ? tmp() + "/x11-server.log"
                : tmp() + "/x11-server-d" + d + ".log";
    }
    static String tagFor(int d) { return d == 0 ? "" : "d" + d; }
    static String entryPidPath(String entryId) {
        return tmp() + "/astro-launch-" + entryId + ".pid";
    }
    static String entryLogPath(String entryId) {
        return "/tmp/astro-launch-" + entryId + ".log"; // sisi chroot
    }

    // ------------------------------------------------------------ shell helpers

    static String shq(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** "KUNCI=nilai" per baris -> rangkaian export berkuot aman. */
    static String envExports(String envRaw) {
        StringBuilder sb = new StringBuilder();
        if (envRaw == null) return "";
        for (String line : envRaw.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (!k.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            sb.append("export ").append(k).append("=").append(shq(v)).append("; ");
        }
        return sb.toString();
    }

    private String run(String script) {
        try {
            String out = core.executeCommand(script);
            return out == null ? "" : out;
        } catch (Exception e) {
            return "ERROR: " + e;
        }
    }

    // ------------------------------------------------------------ probe

    /**
     * Skrip probe — SUMBER KEBENARAN = /proc/net/unix, BUKAN nama proses.
     * (Dipindah verbatim dari XSessionManager: cmdline app_process
     * ditulis ulang, comm server X cuma "main", jadi tidak ada token
     * nama yang bisa dipegang. Yang tidak bisa bohong: entri listener
     * display di /proc/net/unix + inode-nya.)
     */
    String probeScript(int d) {
        String sock = sockPath(d);
        return "SOCK=" + sock + "; PIDF=" + pidPath(d) + "; "
                + "INO=$(awk -v s=\"$SOCK\" -v a=\"@$SOCK\" '$8==s || $8==a {print $7; exit}' /proc/net/unix 2>/dev/null); "
                + "if [ -n \"$INO\" ]; then OWNER=''; "
                + "set -- $(cat $PIDF 2>/dev/null); FP=$1; FS=$2; "
                + "if [ -n \"$FP\" ] && [ -d /proc/$FP ] && [ -n \"$FS\" ] && "
                + "[ \"$(awk '{print $22}' /proc/$FP/stat 2>/dev/null)\" = \"$FS\" ] && "
                + "ls -l /proc/$FP/fd 2>/dev/null | grep -q \"socket:\\[$INO\\]\"; then OWNER=$FP; "
                + "else for d in /proc/[0-9]*; do "
                + "if ls -l $d/fd 2>/dev/null | grep -q \"socket:\\[$INO\\]\"; then OWNER=${d#/proc/}; break; fi; done; fi; "
                + "if [ -n \"$OWNER\" ]; then "
                + "ST=$(awk '{print $22}' /proc/$OWNER/stat 2>/dev/null); "
                + "if [ \"$(cat $PIDF 2>/dev/null)\" != \"$OWNER $ST\" ]; then echo \"$OWNER $ST\" > $PIDF; echo ADOPT=1; fi; "
                + "echo STATE=RUNNING; echo PID=$OWNER; "
                + "grep VmRSS /proc/$OWNER/status 2>/dev/null | awk '{print \"RSS=\"$2\" \"$3}'; "
                + "if [ -S $SOCK ]; then echo SOCK=1; else echo SOCK=0; echo ZOMBIE=1; fi; "
                + "else echo STATE=STOPPED; echo SOCK=0; fi; "
                + "else "
                + "set -- $(cat $PIDF 2>/dev/null); P=$1; S=$2; "
                + "if [ -n \"$P\" ] && [ -d /proc/$P ] && [ -n \"$S\" ] && "
                + "[ \"$(awk '{print $22}' /proc/$P/stat 2>/dev/null)\" = \"$S\" ]; then "
                + "echo STATE=RUNNING; echo PID=$P; echo SOCK=0; echo ZOMBIE=1; "
                + "grep VmRSS /proc/$P/status 2>/dev/null | awk '{print \"RSS=\"$2\" \"$3}'; "
                + "else echo STATE=STOPPED; [ -S $SOCK ] && echo SOCK=1 || echo SOCK=0; fi; fi";
    }

    Probe probeSync(int disp) {
        Probe p = new Probe();
        String out = run(probeScript(disp));
        StringBuilder d = new StringBuilder();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("STATE=")) {
                p.state = "RUNNING".equals(line.substring(6)) ? State.RUNNING : State.STOPPED;
            } else if (line.startsWith("SOCK=")) {
                p.sock = "1".equals(line.substring(5));
            } else if (line.equals("ZOMBIE=1")) {
                p.zombie = true;
            } else if (line.startsWith("PID=") || line.startsWith("RSS=")) {
                d.append(line).append(' ');
            } else if (line.equals("ADOPT=1")) {
                d.append("(server yatim diadopsi) ");
            }
        }
        p.detail = d.toString().trim();
        // Log debug: catat hanya saat ringkasan probe BERUBAH (probe
        // dipanggil tiap 200ms saat poll socket — mencatat semuanya
        // membanjiri log).
        String summary = "d" + disp + " " + p.state + "/sock=" + p.sock + "/zombie=" + p.zombie + " " + p.detail;
        if (!summary.equals(lastProbeSummary)) {
            lastProbeSummary = summary;
            X11DebugLog.log("PROBE", summary);
        }
        return p;
    }

    /** Setup satu-kali display DON (xrun polos): seperti setup bare +
     *  openbox di dalam display (mini-desktop: tool berdekorasi, bisa
     *  geser/resize/tutup sendiri). Display xrun per-tool TETAP bare —
     *  varian ini HANYA dipakai jalur don manager terminal. Deteksi
     *  openbox PER DISPLAY (pindai /proc/PID/environ cari DISPLAY=:d):
     *  pgrep -x global saja salah — openbox :1 akan menutupi :2. */
    String sessionSetupOpenboxCommand(int d, int dpi) {
        String fontDirHost = root() + "/usr/share/fonts/X11/misc";
        // Pindai openbox yang DISPLAY-nya tepat :d (bukan display lain).
        String scan = "OB=0; for _op in $(pgrep -x openbox 2>/dev/null); do "
                + "if tr '\\0' '\\n' </proc/$_op/environ 2>/dev/null | grep -q \"^DISPLAY=:" + d + "$\"; then OB=1; break; fi; done; ";
        return "export DISPLAY=:" + d + "; "
                + "{ echo \"== astro x11 don setup :" + d + " ==\"; "
                + "[ -f /usr/share/fonts/X11/misc/fonts.dir ] || { command -v mkfontdir >/dev/null 2>&1 && mkfontdir /usr/share/fonts/X11/misc && echo 'fonts.dir dibuat via mkfontdir'; }; "
                + "if command -v xset >/dev/null 2>&1; then "
                + "xset fp+ " + fontDirHost + "; xset fp rehash; echo 'font path didaftarkan'; "
                + "else echo 'xset tidak ada (apt install -y x11-xserver-utils)'; fi; "
                + "if command -v xrdb >/dev/null 2>&1; then "
                + "printf 'Xft.dpi: " + dpi + "\\n' | xrdb -merge; echo 'Xft.dpi=" + dpi + " diterapkan'; fi; "
                + "if command -v openbox >/dev/null 2>&1; then "
                + scan
                + "if [ \"$OB\" = 0 ]; then setsid openbox >/tmp/openbox-d" + d + ".log 2>&1 </dev/null & fi; sleep 2; "
                + scan
                + "if [ \"$OB\" = 1 ]; then echo \"openbox don :" + d + " jalan\"; "
                + "else echo 'openbox don GAGAL start (lihat /tmp/openbox-d" + d + ".log)'; fi; "
                + "else echo 'openbox tidak ada (apt install -y openbox)'; fi; "
                + "} >/tmp/astro-x11-session-d" + d + ".log 2>&1";
    }

    /** Perintah revive openbox display don :d (PER DISPLAY — bukan
     *  pgrep -x global: openbox :1 tak boleh menutupi :2). Dipakai
     *  jalur manager terminal saja (re-show + already-running don);
     *  :0/desktop punya helper sendiri di XSessionManager. */
    static String openboxReviveCommand(int d) {
        return "OB=0; for _op in $(pgrep -x openbox 2>/dev/null); do "
                + "if tr '\\0' '\\n' </proc/$_op/environ 2>/dev/null | grep -q \"^DISPLAY=:" + d + "$\"; then OB=1; break; fi; done; "
                + "if [ \"$OB\" = 0 ] && command -v openbox >/dev/null 2>&1; then "
                + "setsid openbox >/tmp/openbox-d" + d + ".log 2>&1 </dev/null & sleep 1; fi; ";
    }

    // ------------------------------------------------------------ start

    String startScript(int d) {
        // Identitas spawn = $! + starttime-nya, ditulis ke file PID.
        // Keberhasilan sebenarnya diputuskan poll socket di
        // ensureRunningSync. Socket TIDAK dihapus di sini (bisa milik
        // server yatim yang hidup); jalur zombie di ensure yang
        // membersihkan sebelum spawn.
        return "mkdir -p " + tmp() + "; "
                + "rm -f " + pidPath(d) + "; "
                + "TMPDIR=" + tmp() + " "
                + "XKB_CONFIG_ROOT=" + root() + "/usr/share/X11/xkb "
                + "CLASSPATH=$(pm path id.hawx.astro | cut -d: -f2) "
                + "setsid /system/bin/app_process / --nice-name=astro-x11" + (d > 0 ? "-d" + d : "") + " "
                + "com.termux.x11.CmdEntryPoint :" + d + (d > 0 ? " -tag " + tagFor(d) : "") + " >" + serverLog(d) + " 2>&1 </dev/null & "
                + "SVPID=$!; "
                + "ST=$(awk '{print $22}' /proc/$SVPID/stat 2>/dev/null); "
                + "if [ -n \"$ST\" ]; then echo \"$SVPID $ST\" > " + pidPath(d) + "; echo PIDFILE_OK; "
                + "else echo PIDFILE_GAGAL; fi";
    }

    /** Setup bare untuk display >0: font + DPI saja, TANPA window
     *  manager — satu display = satu app yang memenuhi display-nya. */
    String sessionSetupBareCommand(int d, int dpi) {
        String fontDirHost = root() + "/usr/share/fonts/X11/misc";
        return "export DISPLAY=:" + d + "; "
                + "{ echo \"== astro x11 bare setup d" + d + " ==\"; "
                + "[ -f /usr/share/fonts/X11/misc/fonts.dir ] || { command -v mkfontdir >/dev/null 2>&1 && mkfontdir /usr/share/fonts/X11/misc && echo 'fonts.dir dibuat via mkfontdir'; }; "
                + "if command -v xset >/dev/null 2>&1; then "
                + "xset fp+ " + fontDirHost + "; xset fp rehash; echo 'font path didaftarkan'; fi; "
                + "if command -v xrdb >/dev/null 2>&1; then "
                + "printf 'Xft.dpi: " + dpi + "\\n' | xrdb -merge; echo 'Xft.dpi diterapkan'; fi; "
                + "} >/tmp/astro-x11-session-d" + d + ".log 2>&1";
    }

    /**
     * Pastikan server hidup. Mengembalikan true bila RUNNING + socket siap.
     * Menangani server zombie (proses hidup, socket hilang) dengan restart.
     *
     * @param setupCommand perintah setup chroot sekali jalan setelah
     *        socket siap (dipilih pemanggil: :0 = setup lengkap,
     *        floating = setup bare). TANPA XRUN_DEPLOY (shim xrun
     *        sudah mati — xrun gabung tinggal di astro_profile.sh).
     * @param onAlreadyRunning dipanggil bila server SUDAH jalan saat
     *        dicek (dipakai desktop :0 buat revive openbox; null =
     *        tidak ada aksi). Setup TIDAK dijalankan di cabang ini.
     * @param successMessage teks report saat server jalan + setup
     *        diterapkan (dipilih pemanggil, dulu dibedakan d==0/d>0).
     */
    boolean ensureRunningSync(int d, StringBuilder report,
            String setupCommand, String successMessage,
            Runnable onAlreadyRunning) {
        Probe p = probeSync(d);
        if (p.state == State.RUNNING && p.sock && !p.zombie) {
            report.append("server sudah jalan");
            if (onAlreadyRunning != null) onAlreadyRunning.run();
            return true;
        }
        if (p.zombie) {
            report.append("server zombie/banci (listener/socket tak sehat) — dibunuh, start ulang. ");
            X11DebugLog.log("ZOMBIE", "probe=" + p.detail + " sock=" + p.sock + " — bunuh + start ulang");
            killServerSync(d);
            // tunggu listener lama benar-benar lepas dari /proc/net/unix
            long dl = System.currentTimeMillis() + 3_000L;
            while (System.currentTimeMillis() < dl) {
                Probe q0 = probeSync(d);
                if (q0.state == State.STOPPED && !q0.zombie) break;
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        }
        String out = run(startScript(d));
        X11DebugLog.log("SPAWN", "hasil startScript: " + out.trim().replace("\n", " | "));
        if (!out.contains("PIDFILE_OK")) {
            report.append("spawn gagal menulis file PID. Log: ").append(tailServerLog(d));
            return false;
        }
        // poll socket maks 10 detik, interval 200ms
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            Probe q = probeSync(d);
            if (q.state == State.RUNNING && q.sock && !q.zombie) {
                // setup sesi di chroot bila ter-mount (d0: font/dpi/
                // openbox; d>0: bare font/dpi saja — tanpa WM)
                try {
                    if (core.isMounted()) {
                        core.threadChrootCommand(setupCommand);
                        Thread.sleep(700);
                        report.append(successMessage);
                    } else {
                        report.append("server jalan (chroot belum ter-mount — setup dilewati)");
                    }
                } catch (Exception e) {
                    report.append("server jalan (setup sesi: ").append(e).append(')');
                }
                X11DebugLog.log("SESI", "server JALAN + socket siap — " + report);
                return true;
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        report.append("timeout 10 detik menunggu socket. Log: ").append(tailServerLog(d));
        X11DebugLog.log("SPAWN", "TIMEOUT 10s menunggu socket d" + d + ". Log server: " + tailServerLog(d));
        return false;
    }

    String tailServerLog(int d) {
        String out = run("tail -6 " + serverLog(d) + " 2>/dev/null");
        out = out.trim();
        return out.isEmpty() ? "(log kosong)" : out.replace("\n", " | ");
    }

    void killServerSync(int d) {
        // Penjaga STARTTIME sebelum membunuh: bunuh hanya bila proses di
        // file PID masih proses yang sama dengan yang dicatat (starttime
        // /proc/stat field 22 identik) — anti PID daur ulang, tanpa nama.
        run("set -- $(cat " + pidPath(d) + " 2>/dev/null); P=$1; S=$2; "
                + "if [ -n \"$P\" ] && [ -n \"$S\" ] && [ -d /proc/$P ] && "
                + "[ \"$(awk '{print $22}' /proc/$P/stat 2>/dev/null)\" = \"$S\" ]; then "
                + "kill -TERM $P 2>/dev/null; sleep 1; "
                + "if [ -d /proc/$P ] && [ \"$(awk '{print $22}' /proc/$P/stat 2>/dev/null)\" = \"$S\" ]; then "
                + "kill -KILL $P 2>/dev/null; fi; fi; "
                + "rm -f " + sockPath(d) + " " + pidPath(d) + " " + tmp() + "/.X" + d + "-lock");
        X11DebugLog.log("KILL", "server d" + d + " dimatikan (penjaga starttime), socket + file PID dibersihkan");
    }

    /** Cek proses entri hidup dari file PID-nya + verifikasi token cmdline (anti PID-reuse). */
    boolean entryRunningSync(String entryId, String expectToken) {
        String script = "P=''; [ -f " + entryPidPath(entryId) + " ] && P=$(cat " + entryPidPath(entryId) + " 2>/dev/null); "
                + "if [ -n \"$P\" ] && [ -d /proc/$P ]; then "
                + (expectToken != null && !expectToken.isEmpty()
                    ? "tr '\\0' ' ' < /proc/$P/cmdline 2>/dev/null | grep -q " + shq(expectToken) + " && echo RUNNING || echo MATI"
                    : "echo RUNNING")
                + "; else echo MATI; fi";
        return run(script).contains("RUNNING");
    }

    /** Skala global aplikasi klasik (keputusan Hawx: global, bukan
     *  per jendela): xterm tak nurut Xft.dpi (font bitmap) — suntik
     *  font TrueType + ukuran turunan nilai DPI pas peluncuran.
     *  Entri tersimpan tetap bersih; transformasi cuma di sini. */
    static String scaledCommand(String cmd, String firstWord, int dpi) {
        if (cmd == null) return "";
        if ("xterm".equals(firstWord) && !cmd.contains("-fa")) {
            int fs = Math.max(8, Math.min(24, dpi / 16));
            return "xterm -fa 'DejaVu Sans Mono' -fs " + fs
                    + cmd.substring("xterm".length());
        }
        return cmd;
    }

    /** Satu kali fit: tunggu jendela app lahir (maks ~4 dtk), paksa 0,0 + W×H.
     *  Dipakai host floating saat surface berubah ukuran (resize jendela
     *  Android -> RANDR): fit ulang konten display ini SEKALI ke ukuran
     *  surface terakhir. Pengganti watcher polling — nol beban diam. */
    void runFitOnce(String entryId, int d, int w, int h) {
        String dims = (w > 0 && h > 0)
                ? "W=" + w + "; H=" + h + "; "
                : "dims=$(xdpyinfo 2>/dev/null | awk '/dimensions:/ {print $2}'); "
                + "[ -n \"$dims\" ] || exit 0; W=${dims%x*}; H=${dims#*x}; ";
        String script =
                "export DISPLAY=:" + d + "; "
                + "command -v xdotool >/dev/null 2>&1 || { echo 'xdotool tidak ada'; exit 0; }; "
                + "pid=$(cat /tmp/astro-launch-" + entryId + ".pid 2>/dev/null); "
                + "[ -n \"$pid\" ] || exit 0; "
                + dims
                + "win=''; "
                + "for i in $(seq 1 12); do "
                + "win=$(xdotool search --pid $pid 2>/dev/null | head -1); "
                + "[ -n \"$win\" ] && break; "
                + "kill -0 $pid 2>/dev/null || exit 0; "
                + "sleep 0.35; done; "
                + "[ -n \"$win\" ] || exit 0; "
                + "xdotool windowmove $win 0 0 windowsize $win $W $H 2>/dev/null; "
                + "echo \"fit win=$win ${W}x${H}\"";
        String cmd = "(setsid sh -c " + shq(script) + " >>/tmp/astro-fit-" + entryId
                + ".log 2>&1 </dev/null &)";
        try {
            core.threadChrootCommand(cmd);
            X11DebugLog.log("FIT", "fit sekali d=" + d + " " + entryId + " " + w + "x" + h);
        } catch (Exception e) {
            X11DebugLog.log("FIT", "gagal fit: " + e);
        }
    }
}
