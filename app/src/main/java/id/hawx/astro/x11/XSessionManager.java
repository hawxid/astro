package id.hawx.astro.x11;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import id.hawx.astro.utils.Core;

/**
 * Milestone 2 — pengelola sesi X (Lorie) untuk Astro.
 *
 * Keputusan desain yang dikunci Hawx (2026-10-21, bundle §5 + revisi kritik
 * Gemini + bukti perangkat):
 *  - Satu display bersama :0 untuk semua app GUI; window manager = openbox
 *    di dalam chroot, diluncurkan bersama sesi (jendela bisa digeser).
 *  - Server hidup MALAS (lazy): hanya saat ada permintaan GUI. Nol proses
 *    saat berhenti.
 *  - Pelacakan proses server via FILE PID berisi "PID starttime", ditulis
 *    skrip start dari $! spawn. Kebenaran sesi dibaca dari /proc/net/unix
 *    (listener :0 -> inode -> pemilik via fd), TANPA token nama proses —
 *    nama di jalur app_process terbukti tidak bisa dipegang (comm = "main").
 *  - Timeout tunggu socket 10 detik (SD665 di bawah beban bisa telat).
 *  - Sesi mulai -> daftarkan font chroot ke server (xset fp+, path sisi host)
 *    + terapkan Xft.dpi global + start openbox. Obat masalah font xterm
 *    UTF-8 yang terbukti di perangkat (font berkas tinggal di chroot,
 *    server jalan di host).
 *  - Stop sesi: tutup activity penampil dulu (ACTION_STOP), pkill openbox,
 *    lalu SIGTERM -> SIGKILL ke PID server, socket sisa + file PID dihapus.
 *  - Stop per-app: SIGTERM ke grup proses perintah (wrapper setsid saat
 *    launch), tunggu, baru SIGKILL. Server/display TIDAK ikut mati.
 *  - Pemulihan crash: diam — sesi dibangun ulang pada tap berikutnya.
 *  - Watchdog: TANPA polling. UI memeriksa keanggotaan idle > 15 menit
 *    saat onResume (cap aktivitas disimpan di preferensi).
 */
public class XSessionManager {

    public enum State { STOPPED, STARTING, RUNNING }

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    public interface ProbeCallback {
        void onProbe(State state, String detail);
    }

    private static final String PREFS = "astro_x11";
    private static final String KEY_LAST_ACTIVITY = "last_activity_ms";
    private static final String KEY_DPI = "xft_dpi";
    public static final int DPI_DEFAULT = 192;
    public static final long IDLE_LIMIT_MS = 15 * 60 * 1000L;
    private static final String ACTION_STOP_VIEWER = "com.termux.x11.ACTION_STOP";

    private static XSessionManager instance;

    public static synchronized XSessionManager get(Context ctx) {
        if (instance == null) instance = new XSessionManager(ctx.getApplicationContext());
        return instance;
    }

    private final Context appCtx;
    private final Core core;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile State state = State.STOPPED;

    private XSessionManager(Context ctx) {
        this.appCtx = ctx;
        this.core = new Core(ctx);
    }

    // ------------------------------------------------------------ paths

    private String root() { return Core.CHROOT_ROOT; }
    private String tmp() { return root() + "/tmp"; }
    private String sockPath() { return tmp() + "/.X11-unix/X0"; }
    private String pidPath() { return tmp() + "/astro-x11-server.pid"; }
    private String serverLog() { return tmp() + "/x11-server.log"; }
    private String entryPidPath(String entryId) { return tmp() + "/astro-launch-" + entryId + ".pid"; }
    private String entryLogPath(String entryId) { return "/tmp/astro-launch-" + entryId + ".log"; } // sisi chroot

    // ---- varian per-display (PoC multi-display 2026-10-22) ----
    // Display 0 mempertahankan path persis seperti sebelumnya; display
    // N>0 memakai sufiks -dN. Nomor display dialokasikan pemanggil
    // (nomor bebas terkecil — nomor dipakai ulang setelah server mati).
    private String sockPath(int d) { return tmp() + "/.X11-unix/X" + d; }
    private String pidPath(int d) { return d == 0 ? pidPath() : tmp() + "/astro-x11-server-d" + d + ".pid"; }
    private String serverLog(int d) { return d == 0 ? serverLog() : tmp() + "/x11-server-d" + d + ".log"; }
    private static String tagFor(int d) { return d == 0 ? "" : "d" + d; }

    public State cachedState() { return state; }

    public SharedPreferences prefs() {
        return appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int getDpi() { return prefs().getInt(KEY_DPI, DPI_DEFAULT); }

    public void setDpi(int dpi) { prefs().edit().putInt(KEY_DPI, dpi).apply(); }

    public void touchActivity() {
        prefs().edit().putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis()).apply();
    }

    public long idleMillis() {
        long last = prefs().getLong(KEY_LAST_ACTIVITY, 0L);
        if (last == 0L || state != State.RUNNING) return 0L;
        return System.currentTimeMillis() - last;
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

    private void post(Callback cb, boolean ok, String msg) {
        if (cb != null) main.post(() -> cb.onResult(ok, msg));
    }

    // ------------------------------------------------------------ probe

    /**
     * Skrip probe — SUMBER KEBENARAN = /proc/net/unix, BUKAN nama proses.
     *
     * Pelajaran berdarah 2026-10-21 (tiga asumsi nama gugur di perangkat):
     * cmdline app_process ditulis ulang, comm server X ternyata hanya
     * "main" (nama generik runtime), jadi TIDAK ADA token nama yang bisa
     * dipegang. Yang tidak bisa bohong: entri listener display :0 di
     * /proc/net/unix (nama berkas ATAU abstrak) + inode-nya.
     *
     * Alur: cari inode listener X0 -> petakan ke PID pemilik lewat
     * /proc/PID/fd -> bila file PID belum tahu, ADOPSI (tulis "pid
     * starttime"). Pemilik ada tapi berkas socket hilang = ZOMBIE banci
     * (kasus nyata: spawn kedua yang mati fatal meng-unlink socket server
     * yang hidup). Tanpa listener: zombie klasik = PID file PID masih
     * hidup dengan starttime yang cocok tapi tidak mendengarkan.
     * File PID berisi "PID STARTTIME" (starttime = field 22 /proc/stat,
     * anti daur ulang PID).
     */
    private String probeScript() { return probeScript(0); }

    private String probeScript(int d) {
        String sock = sockPath(d);
        return "SOCK=" + sock + "; PIDF=" + pidPath(d) + "; "
                + "INO=$(awk -v s=\"$SOCK\" -v a=\"@$SOCK\" '$8==s || $8==a {print $7; exit}' /proc/net/unix 2>/dev/null); "
                + "if [ -n \"$INO\" ]; then OWNER=''; "
                // JALUR CEPAT (fix lemot 2026-10-22): scan /proc/*/fd untuk
                // SEMUA proses itu mahal (ratusan ls+grep per probe, dan
                // probe dibayar berkali-kali per launch). Bila file PID
                // cocok (PID hidup + starttime sama + fd socket terbukti
                // miliknya — cek SATU proses saja), scan total dilewati.
                // Scan penuh hanya untuk adopsi server yatim (file PID
                // tidak cocok), kasus langka setelah app mati paksa.
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

    private static class Probe {
        State state = State.STOPPED;
        boolean sock = false;
        boolean zombie = false;
        String detail = "";
    }

    private Probe probeSync() { return probeSync(0); }

    private Probe probeSync(int disp) {
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
        if (disp == 0) state = p.state;
        // Log debug: catat hanya saat ringkasan probe BERUBAH (probe dipanggil
        // tiap 200ms saat poll socket — mencatat semuanya membanjiri log).
        String summary = "d" + disp + " " + p.state + "/sock=" + p.sock + "/zombie=" + p.zombie + " " + p.detail;
        if (!summary.equals(lastProbeSummary)) {
            lastProbeSummary = summary;
            X11DebugLog.log("PROBE", summary);
        }
        return p;
    }

    private String lastProbeSummary = "";

    public void probe(ProbeCallback cb) {
        exec.execute(() -> {
            Probe p = probeSync();
            String detail = p.detail + (p.sock ? " socket:ada" : " socket:tidak-ada");
            if (cb != null) main.post(() -> cb.onProbe(p.state, detail.trim()));
        });
    }

    // ------------------------------------------------------------ start

    private String startScript() { return startScript(0); }

    private String startScript(int d) {
        // Identitas spawn = $! (setsid mengeksekusi app_process di tempat
        // pada shell non-interaktif) + starttime-nya, ditulis ke file PID
        // SEGERA — tanpa cek nama apa pun (nama proses di jalur ini terbukti
        // tidak bisa dipegang: cmdline ditulis ulang, comm cuma "main").
        // Keberhasilan sebenarnya diputuskan poll socket di ensureRunningSync.
        // Socket TIDAK dihapus di sini (bisa milik server yatim yang hidup);
        // jalur zombie di ensure yang membersihkan sebelum spawn.
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

    /** Setup bare untuk display >0 (PoC): font + DPI saja, TANPA window
     *  manager — satu display = satu app yang memenuhi display-nya. */
    private String sessionSetupBareCommand(int d) {
        String fontDirHost = root() + "/usr/share/fonts/X11/misc";
        return "export DISPLAY=:" + d + "; "
                + "{ echo \"== astro x11 bare setup d" + d + " ==\"; "
                + "[ -f /usr/share/fonts/X11/misc/fonts.dir ] || { command -v mkfontdir >/dev/null 2>&1 && mkfontdir /usr/share/fonts/X11/misc && echo 'fonts.dir dibuat via mkfontdir'; }; "
                + "if command -v xset >/dev/null 2>&1; then "
                + "xset fp+ " + fontDirHost + "; xset fp rehash; echo 'font path didaftarkan'; fi; "
                + "if command -v xrdb >/dev/null 2>&1; then "
                + "printf 'Xft.dpi: " + getDpi() + "\\n' | xrdb -merge; echo 'Xft.dpi diterapkan'; fi; "
                + "} >/tmp/astro-x11-session-d" + d + ".log 2>&1";
    }

    /** Setup satu-kali per sesi, dijalankan DI DALAM chroot setelah socket siap. */
    private String sessionSetupCommand() {
        String fontDirHost = root() + "/usr/share/fonts/X11/misc";
        return "export DISPLAY=:0; "
                + "{ echo \"== astro x11 session setup ==\"; "
                + "[ -f /usr/share/fonts/X11/misc/fonts.dir ] || { command -v mkfontdir >/dev/null 2>&1 && mkfontdir /usr/share/fonts/X11/misc && echo 'fonts.dir dibuat via mkfontdir'; }; "
                + "if command -v xset >/dev/null 2>&1; then "
                + "xset fp+ " + fontDirHost + "; xset fp rehash; echo 'font path didaftarkan'; "
                + "else echo 'xset tidak ada (apt install -y x11-xserver-utils)'; fi; "
                + "if command -v xrdb >/dev/null 2>&1; then "
                + "printf 'Xft.dpi: " + getDpi() + "\\n' | xrdb -merge; echo 'Xft.dpi=" + getDpi() + " diterapkan'; fi; "
                + "if command -v openbox >/dev/null 2>&1; then "
                + "pgrep -x openbox >/dev/null 2>&1 || (setsid openbox >/tmp/openbox.log 2>&1 </dev/null &); echo 'openbox siap'; "
                + "else echo 'openbox tidak ada (apt install -y openbox)'; fi; "
                + "} >/tmp/astro-x11-session.log 2>&1";
    }

    /**
     * Pastikan server hidup. Mengembalikan true bila RUNNING + socket siap.
     * Menangani server zombie (proses hidup, socket hilang) dengan restart.
     */
    private boolean ensureRunningSync(StringBuilder report) { return ensureRunningSync(0, report); }

    private boolean ensureRunningSync(int d, StringBuilder report) {
        Probe p = probeSync(d);
        if (p.state == State.RUNNING && p.sock && !p.zombie) {
            report.append("server sudah jalan");
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
        if (d == 0) state = State.STARTING;
        String out = run(startScript(d));
        X11DebugLog.log("SPAWN", "hasil startScript: " + out.trim().replace("\n", " | "));
        if (!out.contains("PIDFILE_OK")) {
            report.append("spawn gagal menulis file PID. Log: ").append(tailServerLog(d));
            if (d == 0) state = State.STOPPED;
            return false;
        }
        // poll socket maks 10 detik, interval 200ms
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            Probe q = probeSync(d);
            if (q.state == State.RUNNING && q.sock && !q.zombie) {
                // setup sesi di chroot bila ter-mount (d0: font/dpi/openbox;
                // d>0: bare font/dpi saja — PoC tanpa WM)
                try {
                    if (core.isMounted()) {
                        core.threadChrootCommand((d == 0 ? sessionSetupCommand() : sessionSetupBareCommand(d)) + XRUN_DEPLOY);
                        syncXrunWatcher();
                        Thread.sleep(700);
                        report.append(d == 0 ? "server jalan, sesi disiapkan (font/DPI/openbox)"
                                : "server :" + d + " jalan, setup bare (font/DPI, tanpa WM)");
                    } else {
                        report.append("server jalan (chroot belum ter-mount — setup dilewati)");
                    }
                } catch (Exception e) {
                    report.append("server jalan (setup sesi: ").append(e).append(')');
                }
                touchActivity();
                if (d == 0) state = State.RUNNING;
                X11DebugLog.log("SESI", "server JALAN + socket siap — " + report);
                return true;
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        report.append("timeout 10 detik menunggu socket. Log: ").append(tailServerLog(d));
        X11DebugLog.log("SPAWN", "TIMEOUT 10s menunggu socket d" + d + ". Log server: " + tailServerLog(d));
        if (d == 0) state = State.STOPPED;
        return false;
    }

    private String tailServerLog() { return tailServerLog(0); }

    private String tailServerLog(int d) {
        String out = run("tail -6 " + serverLog(d) + " 2>/dev/null");
        out = out.trim();
        return out.isEmpty() ? "(log kosong)" : out.replace("\n", " | ");
    }

    private void killServerSync() { killServerSync(0); }

    private void killServerSync(int d) {
        // Penjaga STARTTIME sebelum membunuh: bunuh hanya bila proses di
        // PID file PID masih proses yang sama dengan yang dicatat (starttime
        // /proc/stat field 22 identik) — anti PID daur ulang, tanpa nama.
        run("set -- $(cat " + pidPath(d) + " 2>/dev/null); P=$1; S=$2; "
                + "if [ -n \"$P\" ] && [ -n \"$S\" ] && [ -d /proc/$P ] && "
                + "[ \"$(awk '{print $22}' /proc/$P/stat 2>/dev/null)\" = \"$S\" ]; then "
                + "kill -TERM $P 2>/dev/null; sleep 1; "
                + "if [ -d /proc/$P ] && [ \"$(awk '{print $22}' /proc/$P/stat 2>/dev/null)\" = \"$S\" ]; then "
                + "kill -KILL $P 2>/dev/null; fi; fi; "
                + "rm -f " + sockPath(d) + " " + pidPath(d) + " " + tmp() + "/.X" + d + "-lock");
        X11DebugLog.log("KILL", "server d" + d + " dimatikan (penjaga starttime), socket + file PID dibersihkan");
        if (d == 0) state = State.STOPPED;
    }

    public void start(Callback cb) {
        exec.execute(() -> {
            StringBuilder rep = new StringBuilder();
            boolean ok = ensureRunningSync(rep);
            post(cb, ok, rep.toString());
        });
    }

    // ------------------------------------------------------------ stop sesi

    public void stopSession(Callback cb) {
        X11DebugLog.log("SESI", "stopSession diminta dari UI");
        exec.execute(() -> {
            // 1) tutup activity penampil dulu (biar tidak memamerkan overlay error)
            try {
                Intent i = new Intent(ACTION_STOP_VIEWER);
                i.setPackage(appCtx.getPackageName());
                appCtx.sendBroadcast(i);
            } catch (Exception ignored) {}
            // 2) matikan openbox di chroot (app GUI kehilangan WM, server masih hidup sebentar)
            try { core.threadChrootCommand("pkill openbox 2>/dev/null; true"); } catch (Exception ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            // 3) matikan server + bersihkan socket sisa & file PID
            killServerSync();
            post(cb, true, "Sesi X dihentikan bersih");
        });
    }

    // ------------------------------------------------------------ viewer

    public Intent viewerIntent() {
        Intent i = new Intent();
        i.setClassName(appCtx.getPackageName(), "com.termux.x11.MainActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    public void openViewer() {
        touchActivity();
        appCtx.startActivity(viewerIntent());
    }

    // ------------------------------------------------------------ launch / stop per entri

    /** Cek proses entri hidup dari file PID-nya + verifikasi token cmdline (anti PID-reuse). */
    public boolean entryRunningSync(String entryId, String expectToken) {
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
    private String scaledCommand(LauncherEntry entry) {
        String cmd = entry.command;
        if (cmd == null) return "";
        if ("xterm".equals(entry.firstWord()) && !cmd.contains("-fa")) {
            int fs = Math.max(8, Math.min(24, getDpi() / 16));
            return "xterm -fa 'DejaVu Sans Mono' -fs " + fs
                    + cmd.substring("xterm".length());
        }
        return cmd;
    }

    public void launch(LauncherEntry entry, Callback cb) {
        if ("webview".equals(entry.type)) {
            post(cb, false, "Entri WebView dibuka dari ikon desktopnya");
            return;
        }
        if ("cli".equals(entry.type)) {
            // CLI: kirim ke kanal terminal chroot Astro (tanpa DISPLAY dari template)
            String cmd = "cd " + shq(entry.workdirOrDefault()) + " 2>/dev/null || cd /root; "
                    + envExports(entry.env) + entry.command;
            try {
                core.threadChrootCommand(cmd);
                touchActivity();
                post(cb, true, "Dikirim ke terminal chroot — buka Terminal Astro untuk melihatnya");
            } catch (Exception e) {
                post(cb, false, "Gagal mengirim perintah: " + e);
            }
            return;
        }
        exec.execute(() -> {
            if (!core.isMounted()) {
                post(cb, false, "Chroot belum jalan — start dulu dari Dashboard Astro");
                return;
            }
            X11DebugLog.log("LAUNCH", "GUI " + entry.id + " :: " + entry.command);
            if (!entry.allowMultiple && entryRunningSync(entry.id, entry.firstWord())) {
                X11DebugLog.log("LAUNCH", entry.id + " sudah jalan — fokus ke viewer");
                main.post(this::openViewer);
                post(cb, true, entry.name + " sudah jalan — display dibuka");
                return;
            }
            StringBuilder rep = new StringBuilder();
            if (!ensureRunningSync(rep)) {
                post(cb, false, rep.toString());
                return;
            }
            // file PID + log entri ditulis dari DALAM chroot -> path sisi chroot (/tmp/...)
            String launchCmd = "export DISPLAY=:0; export TMPDIR=/tmp; "
                    + "xset fp+ " + root() + "/usr/share/fonts/X11/misc 2>/dev/null; xset fp rehash 2>/dev/null; "
                    + "cd " + shq(entry.workdirOrDefault()) + " 2>/dev/null || cd /root; "
                    + envExports(entry.env)
                    + "(setsid " + scaledCommand(entry) + " >" + entryLogPath(entry.id) + " 2>&1 & "
                    + "echo $! > /tmp/astro-launch-" + entry.id + ".pid)";
            try {
                core.threadChrootCommand(launchCmd);
                Thread.sleep(500);
            } catch (Exception e) {
                post(cb, false, "Gagal meluncurkan: " + e);
                return;
            }
            touchActivity();
            main.post(this::openViewer);
            post(cb, true, entry.name + " diluncurkan");
        });
    }

    /** Stop satu app GUI: TERM ke grup proses -> tunggu -> KILL. Server tetap hidup. */
    public void stopEntry(LauncherEntry entry, Callback cb) {
        exec.execute(() -> {
            String script = "P=''; [ -f " + entryPidPath(entry.id) + " ] && P=$(cat " + entryPidPath(entry.id) + " 2>/dev/null); "
                    + "if [ -n \"$P\" ] && [ -d /proc/$P ]; then "
                    + "kill -TERM -$P 2>/dev/null; kill -TERM $P 2>/dev/null; sleep 2; "
                    + "[ -d /proc/$P ] && { kill -KILL -$P 2>/dev/null; kill -KILL $P 2>/dev/null; }; "
                    + "echo DISTOP; else echo SUDAH_MATI; fi; "
                    + "rm -f " + entryPidPath(entry.id);
            String out = run(script);
            X11DebugLog.log("STOP-ENTRY", entry.id + " -> " + out.trim());
            post(cb, true, out.contains("DISTOP") ? entry.name + " dihentikan" : entry.name + " sudah tidak jalan");
        });
    }

    // ------------------------------------------------------------ PoC multi-display

    /** Start server display :d (d>0: bare, tanpa WM). Bukti PoC 2026-10-22:
     *  banyak server independen dalam satu perangkat. */
    public void startDisplay(int d, Callback cb) {
        exec.execute(() -> {
            StringBuilder rep = new StringBuilder();
            boolean ok = ensureRunningSync(d, rep);
            post(cb, ok, rep.toString());
        });
    }

    /** Matikan server display :d saja (penjaga starttime); display lain utuh. */
    public void stopDisplay(int d, Callback cb) {
        exec.execute(() -> {
            // R2: display tab yang dimatikan (mis. Matikan dari dock)
            // ikut melepas reservasi + markernya, supaya hook prompt
            // berikutnya meng-unset DISPLAY (bukan menunjuk display mati).
            clearTabForDisplay(d);
            killServerSync(d);
            post(cb, true, "Display :" + d + " dimatikan");
        });
    }

    /** Probe satu display (state + RSS server) — untuk perbandingan RAM antar server. */
    public void probeDisplay(int d, ProbeCallback cb) {
        exec.execute(() -> {
            Probe p = probeSync(d);
            String detail = p.detail + (p.sock ? " socket:ada" : " socket:tidak-ada");
            if (cb != null) main.post(() -> cb.onProbe(p.state, detail.trim()));
        });
    }

    /** Intent penampil untuk display :d — d>0 lewat jendela bertag
     *  (MainActivityTagged, data URI termux-x11-window://d<d>). */
    public Intent viewerIntentForDisplay(int d) {
        if (d == 0) return viewerIntent();
        Intent i = new Intent();
        i.setClassName(appCtx.getPackageName(), "com.termux.x11.MainActivity");
        i.setData(Uri.parse("termux-x11-window://" + tagFor(d)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    public void openViewerForDisplay(int d) {
        touchActivity();
        // SERAH-TERIMA: fullscreen mengambil alih display ini -> jendela
        // floating untuk display yang sama ditutup dulu (satu koneksi).
        if (d > 0) id.hawx.astro.x11.window.XWindowActivity.closeForDisplay(d);
        if (d > 0) id.hawx.astro.x11.window.XWindowLayer.closeForDisplay(d);
        appCtx.startActivity(viewerIntentForDisplay(d));
    }

    /** Launch app GUI ke display :d (varian launch() dengan DISPLAY bebas). */
    public interface FloatingCallback {
        void onResult(boolean ok, int display, String message);
    }

    /** Display -> entri terakhir yang diluncurkan di situ (untuk auto-fit). */
    private final java.util.Map<Integer, String> lastEntryByDisplay =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Display -> OBJEK entri terakhir (untuk pemulihan server macet). */
    private final java.util.Map<Integer, LauncherEntry> lastEntryObjByDisplay =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Peta entri -> display floating terakhir (memori saja; untuk fokus ulang). */
    private final java.util.Map<String, Integer> floatingDisplays =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Luncurkan entri sebagai jendela floating: pilih display bebas terendah
     * (>= :1), jalankan TANPA viewer fullscreen; pemanggil membuka
     * XWindowActivity untuk display hasil. Entri yang sudah jalan ->
     * kembalikan display terakhirnya (fokus ulang, bukan luncur ulang).
     */
    /** Info satu display floating buat panel aktivitas. */
    public static class DisplayInfo {
        public final int display;
        public final String entryId;
        public final String name;
        public final boolean appRunning;

        DisplayInfo(int display, String entryId, String name, boolean appRunning) {
            this.display = display;
            this.entryId = entryId;
            this.name = name;
            this.appRunning = appRunning;
        }
    }

    public interface DisplaysCallback {
        void onDisplays(java.util.List<DisplayInfo> list);
    }

    /** Daftar display :1-:4 yang servernya HIDUP (buat panel aktivitas di
     *  desktop: buka lagi / matikan display yatim yang jendelanya sudah
     *  tertutup — kebutuhan Hawx). Peta basi untuk display mati sekalian
     *  dibersihkan di sini. */
    public void listDisplays(DisplaysCallback cb) {
        exec.execute(() -> {
            java.util.List<DisplayInfo> out = new java.util.ArrayList<>();
            for (int d = 1; d <= 4; d++) {
                final int dd = d;
                if (probeSync(d).state != State.RUNNING) {
                    lastEntryByDisplay.remove(d);
                    lastEntryObjByDisplay.remove(d);
                    floatingDisplays.values().removeIf(v -> v == dd);
                    // R2: server tab yang mati sendiri (crash/dibunuh dari
                    // luar) jangan mengunci slot pool selamanya.
                    clearTabForDisplay(dd);
                    continue;
                }
                LauncherEntry e = lastEntryObjByDisplay.get(d);
                boolean app = e != null && entryRunningSync(e.id, e.firstWord());
                out.add(new DisplayInfo(d, e != null ? e.id : null,
                        e != null ? e.name : null, app));
            }
            X11DebugLog.log("PANEL", "listDisplays -> " + out.size() + " display hidup");
            main.post(() -> cb.onDisplays(out));
        });
    }

    // ---- fondasi toggle terminal: xrun ----
    private static final String KEY_FLOAT_TERM = "terminal_float";

    public boolean getTerminalFloat() {
        return prefs().getBoolean(KEY_FLOAT_TERM, false);
    }

    public void setTerminalFloat(boolean on) {
        setTerminalFloat(on, null);
    }

    /** Overload ronde perbaikan: daftar PID boleh dibawa pengirim
     *  broadcast (NeoTermActivity tahu tab-nya sendiri) supaya klaim
     *  tidak bergantung pada snapshot foregroundActivity(). List
     *  null/kosong = fallback ke snapshot foreground seperti dulu
     *  (dipakai switch X11PocActivity yang tak membawa PID). */
    public void setTerminalFloat(boolean on,
            java.util.List<Integer> providedPids) {
        prefs().edit().putBoolean(KEY_FLOAT_TERM, on).apply();
        syncXrunWatcher();
        // R2: isi toggle berubah dari "watcher xrun on/off" menjadi
        // "server per-tab on/off + marker". Klaim/lepas jalan di thread
        // exec (satu jalur dengan launchFloating — tanpa balapan soal
        // nomor display). Tab baru tetap ter-klaim lewat broadcast
        // onTabAdded.
        if (on) {
            final java.util.List<Integer> pids;
            if (providedPids != null && !providedPids.isEmpty()) {
                pids = new java.util.ArrayList<>(providedPids);
                X11DebugLog.log("TAB", "toggle ON: " + pids.size()
                        + " pid dari broadcast");
            } else {
                pids = collectForegroundTermPids();
            }
            exec.execute(() -> {
                refreshLiveProfile();
                for (Integer pid : pids) claimTabDisplay(pid);
            });
        } else {
            exec.execute(this::releaseAllTabDisplays);
        }
    }

    /** PID shell chroot tab yang sedang hidup di activity depan (bila
     *  activity depan adalah NeoTermActivity; kalau bukan, list kosong).
     *  Hanya dipanggil dari thread utama. */
    private java.util.List<Integer> collectForegroundTermPids() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        String fgName = null;
        try {
            android.app.Activity fg =
                    id.hawx.astro.AstroApp.foregroundActivity();
            fgName = (fg == null) ? null : fg.getClass().getName();
            if (fg instanceof com.stryker.terminal.ui.term.NeoTermActivity) {
                out.addAll(((com.stryker.terminal.ui.term.NeoTermActivity) fg)
                        .collectTermPids());
            }
        } catch (Throwable t) {
            X11DebugLog.log("TAB", "snapshot tab gagal: " + t);
        }
        // Ronde perbaikan: snapshot yang kosong harus bersuara
        // (kelas foreground + jumlah pid) supaya log berikutnya
        // bisa membedakan "bukan terminal" vs "terminal tanpa tab".
        X11DebugLog.log("TAB", "snapshot tab: fg=" + fgName
                + " pids=" + out.size());
        return out;
    }

    /** Perintah deploy shim xrun ke chroot (idempotent, base64 biar aman
     *  dari quoting): /usr/local/bin/xrun menulis berkas request yang
     *  dibaca watcher polling di sisi host. */
    private static final String XRUN_DEPLOY =
            "echo IyEvYmluL3NoCmQ9L3RtcC9hc3Ryby14cnVuLXJlcXVlc3RzCm1rZGlyIC1wICIkZCIKZj0iJGQvcmVxLSQkLSQoZGF0ZSArJXMpIgpwcmludGYgIiVzIiAiJCoiID4gIiRmLnRtcCIgJiYgbXYgIiRmLnRtcCIgIiRmIgplY2hvICJ4cnVuOiB0ZXJraXJpbTogJCoiCg== | base64 -d > /usr/local/bin/xrun 2>/dev/null; "
            + "chmod +x /usr/local/bin/xrun 2>/dev/null; "
            + "mkdir -p /tmp/astro-xrun-requests; ";

    private java.util.concurrent.ScheduledExecutorService xrunPoller;

    private synchronized void syncXrunWatcher() {
        boolean want = getTerminalFloat() && core.isMounted();
        if (want && xrunPoller == null) {
            // Polling via shell ROOT, BUKAN FileObserver: direktori
            // request ada di pohon rootfs milik root — proses aplikasi
            // (uid aplikasi) tak bisa inotify ke sana, watcher lama
            // diam-diam tak pernah menembak (bukti log 28: watcher
            // AKTIF 09:32, request 09:43 tak pernah sampai).
            xrunPoller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            // Bersihkan antrean LAMA: berkas yang sudah ada pas watcher
            // nyala itu basi by definition (kasus 2026-10-24: request
            // 'xrun wireshark' dari tes lama tereksekusi sendiri pas
            // watcher pertama kali jalan beneran).
            core.threadChrootCommand("rm -f /tmp/astro-xrun-requests/req-* "
                    + "/tmp/astro-xrun-requests/*.tmp 2>/dev/null");
            xrunPoller.scheduleWithFixedDelay(this::pollXrunRequests,
                    400, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            // Shim harus ada SEKARANG, bukan nunggu start server berikutnya.
            core.threadChrootCommand(XRUN_DEPLOY);
            X11DebugLog.log("XRUN", "watcher AKTIF (polling root) + shim dideploy");
        } else if (!want && xrunPoller != null) {
            xrunPoller.shutdownNow();
            xrunPoller = null;
            X11DebugLog.log("XRUN", "watcher mati");
        }
    }

    /** Satu putaran polling: ambil SEMUA berkas request tertua-dulu,
     *  baca isinya + hapus, lalu luncurkan sebagai entri floating. */
    private void pollXrunRequests() {
        try {
            java.util.List<String> names = core.customChrootCommand(
                    "ls /tmp/astro-xrun-requests 2>/dev/null");
            if (names == null) return;
            for (String name : names) {
                name = name.trim();
                if (!name.matches("req-[A-Za-z0-9-]+")) continue;
                // Nama shim: req-<pid>-<epoch detik>. Yang umurnya
                // >120 dtk = sisa tes lama — buang, jangan eksekusi.
                long epoch = -1;
                int dash = name.lastIndexOf('-');
                if (dash > 0) {
                    try {
                        epoch = Long.parseLong(name.substring(dash + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (epoch > 0
                        && System.currentTimeMillis() / 1000 - epoch > 120) {
                    core.customChrootCommand(
                            "rm -f /tmp/astro-xrun-requests/" + name);
                    X11DebugLog.log("XRUN", "request basi dibuang: " + name);
                    continue;
                }
                java.util.List<String> out = core.customChrootCommand(
                        "cat /tmp/astro-xrun-requests/" + name
                                + "; rm -f /tmp/astro-xrun-requests/" + name);
                if (out == null) continue;
                String cmd = String.join(" ", out).trim();
                if (!cmd.isEmpty()) handleXrunCommand(cmd);
            }
        } catch (Throwable t) {
            // chroot sibuk/mati di tengah jalan — putaran berikut mencoba lagi
        }
    }

    private void handleXrunCommand(String cmd) {
        X11DebugLog.log("XRUN", "request: " + cmd);
        LauncherEntry e = new LauncherEntry();
        e.id = "xrun-" + (System.currentTimeMillis() / 1000);
        String fw = cmd.split("\\s+")[0];
        e.name = "Terminal \u00B7 " + fw;
        e.command = cmd;
        e.type = "gui";
        e.floating = true;
        e.allowMultiple = true;
        launchFloating(e, (ok, d, msg) -> {
            if (ok && d > 0) {
                // Kelahiran terminal = overlay DI TEMPAT (keputusan
                // Hawx: inti floating); gagal tampil -> jalur desktop.
                main.post(() -> {
                    boolean shown = id.hawx.astro.x11.window.XOverlayWindows
                            .show(appCtx, d, e.name + " \u00B7 :" + d);
                    if (!shown)
                        id.hawx.astro.MainActivity.openXWindowFrom(appCtx, d,
                                e.name + " \u00B7 :" + d);
                });
            }
        });
    }

    // ---- R1: lifecycle jendela floating berbasis liveness entri ----
    // Aturan: display floating (bare, :1-:4) yang entrinya SUDAH pernah
    // hidup lalu MATI 2 poll berturut-turut -> jendela ditutup +
    // server dimatikan (bukan ditinggal gelap). Jendela yang entrinya
    // belum pernah hidup TIDAK PERNAH ditutup watcher ini (grace
    // launch lambat). Berlaku 1-entri-per-display (model sekarang);
    // model per-tab nanti (multi tool per display) butuh hitung
    // window, bukan liveness entri. Dipakai host lewat watch/unwatch;
    // hide/minimize TIDAK unwatch (jendela masih ada).
    private final java.util.Map<Integer, Boolean> lifeSeen =
            new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> lifeDeadStreak =
            new java.util.HashMap<>();
    private final java.util.Set<Integer> lifeWatched =
            new java.util.HashSet<>();
    private java.util.concurrent.ScheduledExecutorService lifePoller;

    public synchronized void watchEntryDisplay(int d) {
        if (d <= 0) return;
        lifeWatched.add(d);
        syncLifePoller();
    }

    public synchronized void unwatchEntryDisplay(int d) {
        lifeWatched.remove(d);
        lifeSeen.remove(d);
        lifeDeadStreak.remove(d);
        syncLifePoller();
    }

    private synchronized void syncLifePoller() {
        if (!lifeWatched.isEmpty() && lifePoller == null) {
            lifePoller = java.util.concurrent.Executors
                    .newSingleThreadScheduledExecutor();
            lifePoller.scheduleWithFixedDelay(this::pollEntryLife,
                    1000, 4000, java.util.concurrent.TimeUnit.MILLISECONDS);
            X11DebugLog.log("LIFE", "watcher jalan (" + lifeWatched.size() + " display)");
        } else if (lifeWatched.isEmpty() && lifePoller != null) {
            lifePoller.shutdownNow();
            lifePoller = null;
            X11DebugLog.log("LIFE", "watcher mati");
        }
    }

    private void pollEntryLife() {
        java.util.Set<Integer> snapshot;
        synchronized (this) {
            snapshot = new java.util.HashSet<>(lifeWatched);
        }
        for (Integer d : snapshot) {
            try {
                LauncherEntry entry = lastEntryObjByDisplay.get(d);
                if (entry == null) continue;
                boolean running = entryRunningSync(entry.id, entry.firstWord());
                synchronized (this) {
                    if (!lifeWatched.contains(d)) continue;
                    if (running) {
                        lifeSeen.put(d, true);
                        lifeDeadStreak.put(d, 0);
                    } else if (Boolean.TRUE.equals(lifeSeen.get(d))) {
                        int streak = lifeDeadStreak.get(d) == null
                                ? 1 : lifeDeadStreak.get(d) + 1;
                        lifeDeadStreak.put(d, streak);
                        X11DebugLog.log("LIFE", "d=" + d + " entri MATI streak=" + streak);
                        if (streak >= 2) {
                            lifeWatched.remove(d);
                            lifeSeen.remove(d);
                            lifeDeadStreak.remove(d);
                            final int disp = d;
                            main.post(() -> closeDeadEntryDisplay(disp));
                        }
                    }
                }
            } catch (Throwable t) {
                // chroot sibuk — poll berikut mencoba lagi, jangan tutup apa-apa
            }
        }
    }

    private void closeDeadEntryDisplay(int d) {
        X11DebugLog.log("LIFE", "tutup jendela :" + d + " (entri mati)");
        id.hawx.astro.x11.window.XWindowLayer.closeForDisplay(d);
        id.hawx.astro.x11.window.XOverlayWindows.closeForDisplay(d);
        stopDisplay(d, (ok, msg) -> { });
    }

    public void launchFloating(LauncherEntry entry, FloatingCallback cb) {
        exec.execute(() -> {
            boolean running = entryRunningSync(entry.id, entry.firstWord());
            X11DebugLog.log("LAUNCH", "launchFloating " + entry.id
                    + " allowMultiple=" + entry.allowMultiple + " running=" + running);
            if (!entry.allowMultiple && running) {
                Integer d = floatingDisplays.get(entry.id);
                if (d != null) postF(cb, true, d, entry.name + " sudah jalan di :" + d);
                else postF(cb, false, -1, entry.name + " sedang jalan (bukan floating)");
                return;
            }
            int chosen = -1;
            for (int d = 1; d <= 4; d++) {
                // R2: display tab (reserved) dilewati MESKI probe bilang
                // STOPPED — nomor tanpa server itu tetap milik tab.
                if (reservedDisplays.contains(d)) continue;
                if (probeSync(d).state != State.RUNNING) { chosen = d; break; }
            }
            if (chosen < 0) {
                postF(cb, false, -1, "Semua display floating penuh (:1-:4)");
                return;
            }
            final int disp = chosen;
            // Entri multi yang SUDAH jalan: instance berikutnya beridentitas
            // sendiri (klon ber-id per display) — file PID/log/fit tak lagi
            // tabrakan antar instance (akar "multiple ON tapi tetap gabisa"
            // + fit nyasar ke jendela tetangga).
            LauncherEntry use = entry;
            if (entry.allowMultiple && running) {
                use = entry.copyWithId(entry.id + "-d" + disp);
                X11DebugLog.log("LAUNCH", "instance baru: " + use.id + " di :" + disp);
            }
            final LauncherEntry fu = use;
            floatingDisplays.put(fu.id, disp);
            launchOnDisplay(fu, disp, (ok, msg) -> {
                if (!ok) floatingDisplays.remove(fu.id);
                postF(cb, ok, disp, msg);
            }, false);
        });
    }

    private void postF(FloatingCallback cb, boolean ok, int d, String msg) {
        if (cb != null) main.post(() -> cb.onResult(ok, d, msg));
    }

    public void launchOnDisplay(LauncherEntry entry, int d, Callback cb) {
        launchOnDisplay(entry, d, cb, true);
    }

    /** openViewer=false: jangan buka viewer fullscreen (dipakai jendela
     *  floating — viewer fullscreen yang ikut kebuka merebut frame dan
     *  mengotori pengujian host tertanam). */
    public void launchOnDisplay(LauncherEntry entry, int d, Callback cb, boolean openViewer) {
        exec.execute(() -> {
            if (!core.isMounted()) {
                post(cb, false, "Chroot belum jalan — start dulu dari Dashboard Astro");
                return;
            }
            X11DebugLog.log("LAUNCH", "GUI d" + d + " " + entry.id + " :: " + entry.command);
            if (d > 0) {
                lastEntryByDisplay.put(d, entry.id);
                lastEntryObjByDisplay.put(d, entry);
            }
            if (!entry.allowMultiple && entryRunningSync(entry.id, entry.firstWord())) {
                if (openViewer) main.post(() -> openViewerForDisplay(d));
                post(cb, true, entry.name + " sudah jalan — display :" + d + " dibuka");
                return;
            }
            StringBuilder rep = new StringBuilder();
            if (!ensureRunningSync(d, rep)) {
                post(cb, false, rep.toString());
                return;
            }
            String wmGuard = "";
            if (d == 0) {
                // Aturan Hawx: fullscreen WAJIB ber-WM. openbox diawasi di
                // jalur launch (bukan cuma setup sesi sekali jalan): kalo
                // mati di tengah, launch berikutnya yang menghidupkan lagi.
                wmGuard = "if command -v openbox >/dev/null 2>&1 && ! pgrep -x openbox >/dev/null 2>&1; then "
                        + "setsid openbox >/tmp/openbox.log 2>&1 </dev/null & sleep 1; fi; ";
                X11DebugLog.log("LAUNCH", "wmGuard openbox untuk :0");
            }
            String launchCmd = "export DISPLAY=:" + d + "; export TMPDIR=/tmp; "
                    + wmGuard
                    + "xset fp+ " + root() + "/usr/share/fonts/X11/misc 2>/dev/null; xset fp rehash 2>/dev/null; "
                    + "cd " + shq(entry.workdirOrDefault()) + " 2>/dev/null || cd /root; "
                    + envExports(entry.env)
                    + "(setsid " + scaledCommand(entry) + " >" + entryLogPath(entry.id) + " 2>&1 & "
                    + "echo $! > /tmp/astro-launch-" + entry.id + ".pid)";
            try {
                core.threadChrootCommand(launchCmd);
                Thread.sleep(500);
            } catch (Exception e) {
                post(cb, false, "Gagal meluncurkan: " + e);
                return;
            }
            if (d > 0) queueAutoFit(entry, d);
            touchActivity();
            if (openViewer) main.post(() -> openViewerForDisplay(d));
            post(cb, true, entry.name + " diluncurkan di :" + d);
        });
    }

    /**
     * Auto-fit konten untuk display bare (floating, tanpa WM): one-shot
     * terpicu surface — tunggu jendela app (dari PID launch) lahir, lalu
     * paksa posisi 0,0 + ukuran = dimensi layar X; diulang tiap dimensi
     * layar berubah (resize jendela floating -> RANDR -> watcher ngefit
     * ulang). Satu watcher per entri (lock pid). Butuh xdotool + xdpyinfo
     * (x11-utils); tanpa itu skrip diam (log di /tmp/astro-fit-*).
     */
    private void queueAutoFit(LauncherEntry entry, int d) {
        runFitOnce(entry.id, d, 0, 0);
    }

    /**
     * Dipanggil host floating saat surface berubah ukuran (resize jendela
     * Android -> RANDR): fit ulang konten display ini SEKALI ke ukuran
     * surface terakhir. Pengganti watcher polling — nol beban diam.
     */
    /**
     * Pemulihan jendela gelap (temuan log 21): server display jalan tapi
     * binder tak pernah ditawarkan lagi (server ngira viewer lama masih
     * nyambung — loop knock-ulangnya berhenti). Host yang nunggu ±6 dtk
     * tanpa binder memanggil ini: server di-restart + entri diluncurkan
     * ulang, knock segar jatuh ke pending, host yang masih nunggu connect.
     * Sesi X display itu reset — harga yang wajar vs jendela hitam permanen.
     */
    public void recoverStuckDisplay(int d) {
        LauncherEntry entry = lastEntryObjByDisplay.get(d);
        if (entry == null) {
            X11DebugLog.log("HANDOFF", "pemulihan d=" + d + " batal: entri tak dikenal");
            return;
        }
        X11DebugLog.log("HANDOFF", "pemulihan d=" + d + ": restart server + relaunch "
                + entry.id);
        exec.execute(() -> {
            try {
                killServerSync(d);
            } catch (Exception e) {
                X11DebugLog.log("HANDOFF", "pemulihan d=" + d + " kill gagal: " + e);
            }
            launchOnDisplay(entry, d, (ok, msg) ->
                    X11DebugLog.log("HANDOFF", "pemulihan d=" + d + " relaunch: " + msg),
                    false);
        });
    }

    public void notifyFloatingResized(int d, int w, int h) {
        String entryId = lastEntryByDisplay.get(d);
        if (entryId == null || w <= 0 || h <= 0) return;
        runFitOnce(entryId, d, w, h);
    }

    /** Satu kali fit: tunggu jendela app lahir (maks ~4 dtk), paksa 0,0 + W×H. */
    private void runFitOnce(String entryId, int d, int w, int h) {
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

    /** Terapkan DPI ke sesi yang sedang jalan (xrdb langsung), tanpa restart. */
    public void applyDpiLive(Callback cb) {
        exec.execute(() -> {
            Probe p = probeSync();
            if (p.state != State.RUNNING) {
                post(cb, false, "Server tidak jalan — DPI tersimpan dan dipakai saat sesi berikutnya mulai");
                return;
            }
            try {
                core.threadChrootCommand("export DISPLAY=:0; printf 'Xft.dpi: " + getDpi()
                        + "\\n' | xrdb -merge 2>/dev/null; true");
                post(cb, true, "Xft.dpi=" + getDpi() + " diterapkan (app GTK yang sudah jalan mungkin perlu dibuka ulang)");
            } catch (Exception e) {
                post(cb, false, "Gagal menerapkan DPI: " + e);
            }
        });
    }

    // ---- R2: display per-tab (model service-display reaktif) ----
    // Satu display bare per tab terminal chroot (bukan per tool):
    // toggle ON -> tiap tab dapat nomor lowest-free dari SATU pool
    // bersama desktop (allocator yang sama; tabrakan mustahil karena
    // semua klaim jalan di thread exec ini) + server bare + marker
    // /tmp/astro-display/<pid-shell> berisi ":N" di dalam chroot.
    // Hook PROMPT_COMMAND di astro_profile.sh (dorman saat OFF) yang
    // mengekspor DISPLAY dari marker tiap prompt — jadi flip ON
    // belakangan tetap menyalakan GUI buat tab lama (asal shell-nya
    // sudah me-source profil ber-hook; tab yang lahir sebelum profil
    // ber-hook butuh dibuka ulang — lihat refreshLiveProfile).
    // Peta ini HANYA di memori: restart proses app membuat server tab
    // jadi yatim (kelihatan di dock sebagai server-only, bisa dimatikan
    // dari dock) sampai toggle di-cycle atau tab baru lahir.
    // BUKAN jatah R2 (tetap R4): visibility ngikut tab aktif, no-auto-
    // reclaim dock, dialog konfirmasi tutup tab, viewer reaktif.
    /** Display yang dipesan tab (meski servernya belum jalan). */
    private final java.util.Set<Integer> reservedDisplays =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** PID shell chroot -> display tabnya. */
    private final java.util.Map<Integer, Integer> tabDisplayByPid =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final String TAB_MARKER_DIR = "/tmp/astro-display";

    /** Tab lahir (broadcast NeoTermActivity) — klaim bila toggle ON. */
    public void onTerminalTabAdded(int pid) {
        exec.execute(() -> claimTabDisplay(pid));
    }

    /** Tab ditutup (broadcast NeoTermActivity) — hanya cleanup server
     *  + marker; cascade jendela/dialog = jatah R4. */
    public void onTerminalTabRemoved(int pid) {
        exec.execute(() -> releaseTabDisplay(pid));
    }

    /** Tulis marker display buat satu shell (root shell chroot, karena
     *  proses app-uid tak bisa menulis ke pohon rootfs milik root). */
    private void writeTabMarker(int pid, int d) {
        try {
            core.customChrootCommand("mkdir -p " + TAB_MARKER_DIR
                    + " && printf '%s' ':" + d + "' > "
                    + TAB_MARKER_DIR + "/" + pid);
        } catch (Throwable t) {
            X11DebugLog.log("TAB", "tulis marker pid=" + pid + " gagal: " + t);
        }
    }

    private void deleteTabMarker(int pid) {
        try {
            core.customChrootCommand("rm -f " + TAB_MARKER_DIR + "/" + pid);
        } catch (Throwable ignored) {}
    }

    /** Klaim display buat satu tab (thread exec saja). Urutan: nomor
     *  lowest-free (lewati RUNNING + reserved) -> reservasi -> server
     *  bare -> marker. Gagal start = reservasi dilepas, tanpa marker
     *  (hook tak pernah menunjuk display mati). */
    private void claimTabDisplay(int pid) {
        // Ronde perbaikan: early return yang dulu sunyi kini
        // dicatat satu baris, supaya log bisa membedakan "klaim
        // tak pernah jalan" vs "klaim jalan tapi dilewat".
        if (!getTerminalFloat()) {
            X11DebugLog.log("TAB", "klaim pid=" + pid
                    + " dilewat: toggle OFF");
            return;
        }
        if (pid <= 0) {
            X11DebugLog.log("TAB", "klaim pid=" + pid
                    + " dilewat: pid tidak valid");
            return;
        }
        if (tabDisplayByPid.containsKey(pid)) {
            X11DebugLog.log("TAB", "klaim pid=" + pid
                    + " dilewat: sudah diklaim :"
                    + tabDisplayByPid.get(pid));
            return;
        }
        int chosen = -1;
        for (int d = 1; d <= 4; d++) {
            if (reservedDisplays.contains(d)) continue;
            if (probeSync(d).state != State.RUNNING) { chosen = d; break; }
        }
        if (chosen < 0) {
            X11DebugLog.log("TAB", "pool penuh (:1-:4) — tab pid=" + pid
                    + " tanpa display");
            return;
        }
        reservedDisplays.add(chosen);
        tabDisplayByPid.put(pid, chosen);
        StringBuilder rep = new StringBuilder();
        boolean ok = ensureRunningSync(chosen, rep);
        if (!ok) {
            reservedDisplays.remove(chosen);
            tabDisplayByPid.remove(pid);
            X11DebugLog.log("TAB", "server tab :" + chosen + " (pid=" + pid
                    + ") gagal: " + rep);
            return;
        }
        writeTabMarker(pid, chosen);
        X11DebugLog.log("TAB", "tab pid=" + pid + " -> :" + chosen
                + " (server jalan, marker ditulis)");
    }

    /** Lepas satu tab: server mati + marker hapus + nomor balik pool. */
    private void releaseTabDisplay(int pid) {
        Integer d = tabDisplayByPid.remove(pid);
        if (d == null) return;
        reservedDisplays.remove(d);
        try {
            killServerSync(d);
        } catch (Throwable ignored) {}
        deleteTabMarker(pid);
        X11DebugLog.log("TAB", "tab pid=" + pid + " dilepas dari :" + d);
    }

    /** Lepas SEMUA tab (toggle OFF): server mati + seluruh marker hapus. */
    private void releaseAllTabDisplays() {
        java.util.List<Integer> pids =
                new java.util.ArrayList<>(tabDisplayByPid.keySet());
        for (Integer pid : pids) releaseTabDisplay(pid);
        try {
            core.customChrootCommand("rm -rf " + TAB_MARKER_DIR);
        } catch (Throwable ignored) {}
        X11DebugLog.log("TAB", "semua display tab dilepas (toggle OFF)");
    }

    /** Bersihkan pemetaan tab buat satu display TANPA membunuh server
     *  (dipakai stopDisplay/listDisplays yang sudah membunuh / menemukan
     *  server mati sendiri). Tanpa pemetaan = tanpa marker. */
    private void clearTabForDisplay(int d) {
        Integer pidToClear = null;
        for (java.util.Map.Entry<Integer, Integer> e : tabDisplayByPid.entrySet()) {
            if (e.getValue() != null && e.getValue().intValue() == d) {
                pidToClear = e.getKey();
                break;
            }
        }
        if (pidToClear != null) {
            tabDisplayByPid.remove(pidToClear);
            deleteTabMarker(pidToClear);
        }
        reservedDisplays.remove(d);
    }

    /** Segarkan salinan profil hidup di chroot (best-effort, root sisi
     *  Android). Latar: bootroot hanya menyalin asset profil saat MOUNT
     *  chroot, dan chroot Hawx ter-mount persisten — jadi tanpa ini,
     *  hook R2 tak pernah sampai ke /etc chroot yang sudah jalan sampai
     *  remount, dan toggle ON kelihatan "tidak bekerja" buat tab baru.
     *  Isi yang disalin = byte asset yang sama dengan yang dipakai
     *  bootroot (idempotent). Shell yang SUDAH jalan tetap memakai
     *  profil lama (profil cuma di-source saat shell lahir) — tab lama
     *  pra-hook tetap butuh dibuka ulang; tab baru langsung ber-hook. */
    private void refreshLiveProfile() {
        try {
            if (!core.isMounted()) return;
            core.customCommand("cp /data/data/id.hawx.astro/files/astro_profile.sh "
                    + Core.CHROOT_ROOT + "/etc/astro_profile.sh 2>/dev/null; true");
            X11DebugLog.log("TAB", "profil hidup disegarkan dari asset");
        } catch (Throwable t) {
            X11DebugLog.log("TAB", "segarkan profil gagal: " + t);
        }
    }
}
