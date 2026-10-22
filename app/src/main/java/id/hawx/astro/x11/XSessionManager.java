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
                + "pgrep -x openbox >/dev/null 2>&1 || (openbox >/tmp/openbox.log 2>&1 &); echo 'openbox siap'; "
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
                        core.threadChrootCommand(d == 0 ? sessionSetupCommand() : sessionSetupBareCommand(d));
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

    public void launch(LauncherEntry entry, Callback cb) {
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
                    + "cd " + shq(entry.workdirOrDefault()) + " 2>/dev/null || cd /root; "
                    + envExports(entry.env)
                    + "(setsid " + entry.command + " >" + entryLogPath(entry.id) + " 2>&1 & "
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
        appCtx.startActivity(viewerIntentForDisplay(d));
    }

    /** Launch app GUI ke display :d (varian launch() dengan DISPLAY bebas). */
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
            String launchCmd = "export DISPLAY=:" + d + "; export TMPDIR=/tmp; "
                    + "cd " + shq(entry.workdirOrDefault()) + " 2>/dev/null || cd /root; "
                    + envExports(entry.env)
                    + "(setsid " + entry.command + " >" + entryLogPath(entry.id) + " 2>&1 & "
                    + "echo $! > /tmp/astro-launch-" + entry.id + ".pid)";
            try {
                core.threadChrootCommand(launchCmd);
                Thread.sleep(500);
            } catch (Exception e) {
                post(cb, false, "Gagal meluncurkan: " + e);
                return;
            }
            touchActivity();
            if (openViewer) main.post(() -> openViewerForDisplay(d));
            post(cb, true, entry.name + " diluncurkan di :" + d);
        });
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
}
