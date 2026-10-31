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


    public interface Callback {
        void onResult(boolean ok, String message);
    }

    public interface ProbeCallback {
        void onProbe(XServerOps.State state, String detail);
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
        // Watcher gabungan terminal harus hidup begitu chroot mounted
        // (alur ini tidak pakai toggle). get() manager terminal sudah
        // men-sync watcher-nya sendiri, jadi cukup panggil get().
        XTerminalManager.get(ctx);
        return instance;
    }

    private final Context appCtx;
    private final Core core;
    private final XServerOps ops;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile XServerOps.State state = XServerOps.State.STOPPED;

    private XSessionManager(Context ctx) {
        this.appCtx = ctx;
        this.core = new Core(ctx);
        this.ops = new XServerOps(core);
    }

    // ------------------------------------------------------------ paths

    private String root() { return XServerOps.root(); }
    private String tmp() { return XServerOps.tmp(); }
    private String sockPath() { return XServerOps.sockPath(0); }
    private String pidPath() { return XServerOps.pidPath(0); }
    private String serverLog() { return XServerOps.serverLog(0); }
    private String entryPidPath(String entryId) { return XServerOps.entryPidPath(entryId); }
    private String entryLogPath(String entryId) { return XServerOps.entryLogPath(entryId); } // sisi chroot

    // ---- varian per-display (PoC multi-display 2026-10-22) ----
    // Display 0 mempertahankan path persis seperti sebelumnya; display
    // N>0 memakai sufiks -dN. Nomor display dialokasikan pemanggil.
    private String sockPath(int d) { return XServerOps.sockPath(d); }
    private String pidPath(int d) { return XServerOps.pidPath(d); }
    private String serverLog(int d) { return XServerOps.serverLog(d); }
    private static String tagFor(int d) { return XServerOps.tagFor(d); }

    public XServerOps.State cachedState() { return state; }

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
        if (last == 0L || state != XServerOps.State.RUNNING) return 0L;
        return System.currentTimeMillis() - last;
    }

    // ------------------------------------------------------------ shell helpers

    static String shq(String s) {
        return XServerOps.shq(s);
    }

    /** "KUNCI=nilai" per baris -> rangkaian export berkuot aman. */
    /** "KUNCI=nilai" per baris -> rangkaian export berkuot aman. */
    static String envExports(String envRaw) {
        return XServerOps.envExports(envRaw);
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
    // (mekanik pindah ke XServerOps; wrapper ini cuma meneruskan +
    // merawat state :0 seperti dulu.)

    private XServerOps.Probe probeSync() { return probeSync(0); }

    private XServerOps.Probe probeSync(int disp) {
        XServerOps.Probe p = ops.probeSync(disp);
        if (disp == 0) state = p.state;
        return p;
    }

    public void probe(ProbeCallback cb) {
        exec.execute(() -> {
            XServerOps.Probe p = probeSync();
            String detail = p.detail + (p.sock ? " socket:ada" : " socket:tidak-ada");
            if (cb != null) main.post(() -> cb.onProbe(p.state, detail.trim()));
        });
    }

    // ------------------------------------------------------------ start

    /** Revive openbox :0 fire-and-forget (tak pernah menggagalkan
     *  pemanggil): hanya bila chroot ter-mount. PER DISPLAY via
     *  XServerOps.openboxReviveCommand(0) — pgrep global SALAH di
     *  sini: openbox display don (:1/:2) akan menutupi openbox :0
     *  yang mati. Dipakai semua jalur masuk :0 (ensure-cabang
     *  sudah-jalan, viewer-open, wmGuard launch). */
    private void reviveOpenboxIfNeeded() {
        try {
            if (!core.isMounted()) return;
            core.threadChrootCommand("export DISPLAY=:0; " + XServerOps.openboxReviveCommand(0));
        } catch (Throwable ignored) {
        }
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
                + "pgrep -x openbox >/dev/null 2>&1 || (setsid openbox >/tmp/openbox.log 2>&1 </dev/null &); sleep 2; "
                + "OPID=$(pgrep -x openbox | head -1); "
                + "if [ -n \"$OPID\" ]; then echo \"openbox jalan (pid $OPID)\"; "
                + "else echo 'openbox GAGAL start (lihat /tmp/openbox.log)'; fi; "
                + "else echo 'openbox tidak ada (apt install -y openbox)'; fi; "
                + "} >/tmp/astro-x11-session.log 2>&1";
    }

    /**
     * Pastikan server hidup. Mengembalikan true bila RUNNING + socket siap.
     * Menangani server zombie (proses hidup, socket hilang) dengan restart.
     */
    private boolean ensureRunningSync(StringBuilder report) { return ensureRunningSync(0, report); }

    private boolean ensureRunningSync(int d, StringBuilder report) {
        // Cabang server-sudah-jalan :0 juga me-revive openbox (aturan
        // desktop; display bare terminal tidak disentuh revive).
        final int dd = d;
        boolean ok = ops.ensureRunningSync(d, report,
                d == 0 ? sessionSetupCommand()
                        : ops.sessionSetupBareCommand(d, getDpi()),
                d == 0 ? "server jalan, sesi disiapkan (font/DPI/openbox)"
                        : "server :" + d + " jalan, setup bare (font/DPI, tanpa WM)",
                () -> { if (dd == 0) reviveOpenboxIfNeeded(); });
        if (ok) {
            touchActivity();
            if (d == 0) state = XServerOps.State.RUNNING;
        } else if (d == 0) {
            state = XServerOps.State.STOPPED;
        }
        return ok;
    }

    private String tailServerLog() { return tailServerLog(0); }

    private String tailServerLog(int d) { return ops.tailServerLog(d); }

    private void killServerSync() { killServerSync(0); }

    private void killServerSync(int d) {
        ops.killServerSync(d);
        if (d == 0) state = XServerOps.State.STOPPED;
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
    /** Cek proses entri hidup dari file PID-nya + verifikasi token cmdline (anti PID-reuse). */
    public boolean entryRunningSync(String entryId, String expectToken) {
        return ops.entryRunningSync(entryId, expectToken);
    }

    /** Skala global aplikasi klasik (keputusan Hawx: global, bukan
     *  per jendela): xterm tak nurut Xft.dpi (font bitmap) — suntik
     *  font TrueType + ukuran turunan nilai DPI pas peluncuran.
     *  Entri tersimpan tetap bersih; transformasi cuma di sini. */
    /** Skala global aplikasi klasik (keputusan Hawx: global, bukan
     *  per jendela): xterm tak nurut Xft.dpi (font bitmap) — suntik
     *  font TrueType + ukuran turunan nilai DPI pas peluncuran.
     *  Entri tersimpan tetap bersih; transformasi cuma di sini. */
    private String scaledCommand(LauncherEntry entry) {
        String cmd = entry.command;
        if (cmd == null) return "";
        return XServerOps.scaledCommand(cmd, entry.firstWord(), getDpi());
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
            if (!entry.allowMultiple && entryRunningSync(entry.id, entry.firstWord())) {
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
        // Pengaman pisah sebagian: display terminal TAK BOLEH dibunuh
        // telanjang dari sini (viewer+host nyangkut = log 35). Siapa
        // pun yang sampai ke sini dengan d terminal dialihkan ke
        // release penuh manager terminal (viewer tutup + server mati
        // + map bebas).
        if (XTerminalManager.isTerminalDisplay(d)) {
            XTerminalManager.get(appCtx).releaseDisplay(d, cb);
            return;
        }
        exec.execute(() -> {
            killServerSync(d);
            post(cb, true, "Display :" + d + " dimatikan");
        });
    }

    /** Probe satu display (state + RSS server) — untuk perbandingan RAM antar server. */
    public void probeDisplay(int d, ProbeCallback cb) {
        exec.execute(() -> {
            XServerOps.Probe p = probeSync(d);
            String detail = p.detail + (p.sock ? " socket:ada" : " socket:tidak-ada");
            if (cb != null) main.post(() -> cb.onProbe(p.state, detail.trim()));
        });
    }

    /** Task Manager: viewer fullscreen (Lorie MainActivity) untuk display
     *  ini masih ada atau tidak — dipakai kolom "viewer" (bersama
     *  layer/decor). Tag instance = tagFor(d); :0 -> "". */
    public boolean hasFullscreenViewer(int d) {
        try {
            return com.termux.x11.MainActivity.getInstance(tagFor(d)) != null;
        } catch (Throwable ignored) {
            return false;
        }
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
        // REVIVE :0 DI SEMUA JALUR VIEWER-OPEN: viewer dibuka pada
        // server yang sudah jalan TANPA ensure (ikon viewer, tap
        // dock :0, relaunch sudah-jalan) — tanpa ini openbox :0
        // yang mati tidak pernah hidup lagi (layar :0 telanjang).
        // Fire-and-forget, tak pernah menunda viewer.
        if (d == 0) reviveOpenboxIfNeeded();
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
                if (probeSync(d).state != XServerOps.State.RUNNING) {
                    // Pisah sebagian: listing desktop HANYA membersihkan
                    // peta desktop (d>=3). Display terminal (d<=2) yang
                    // mati TIDAK disentuh di sini — state terminal
                    // milik manager terminal (refresh dock saja sudah
                    // pernah mutasi state terminal — akar log 35).
                    if (dd >= 3) {
                        lastEntryByDisplay.remove(d);
                        lastEntryObjByDisplay.remove(d);
                        floatingDisplays.values().removeIf(v -> v == dd);
                    }
                    continue;
                }
                LauncherEntry e = lastEntryObjByDisplay.get(d);
                boolean app = e != null && entryRunningSync(e.id, e.firstWord());
                out.add(new DisplayInfo(d, e != null ? e.id : null,
                        e != null ? e.name : null, app));
            }
            main.post(() -> cb.onDisplays(out));
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
        } else if (lifeWatched.isEmpty() && lifePoller != null) {
            lifePoller.shutdownNow();
            lifePoller = null;
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
        // Pisah sebagian: LIFE desktop TIDAK BOLEH menyentuh display
        // terminal (guard; LIFE desktop cuma me-watch jendela layer
        // desktop, tapi jangan pernah bunuh terminal dari sini).
        if (XTerminalManager.isTerminalDisplay(d)) {
            return;
        }
        id.hawx.astro.x11.window.XWindowLayer.closeForDisplay(d);
        stopDisplay(d, (ok, msg) -> { });
    }

    public void launchFloating(LauncherEntry entry, FloatingCallback cb) {
        exec.execute(() -> {
            boolean running = entryRunningSync(entry.id, entry.firstWord());
            if (!entry.allowMultiple && running) {
                Integer d = floatingDisplays.get(entry.id);
                if (d != null) postF(cb, true, d, entry.name + " sudah jalan di :" + d);
                else postF(cb, false, -1, entry.name + " sedang jalan (bukan floating)");
                return;
            }
            // Pool desktop :3-:4 (pisah sebagian; :1-:2 milik terminal).
            int chosen = -1;
            for (int d = 3; d <= 4; d++) {
                if (probeSync(d).state != XServerOps.State.RUNNING) { chosen = d; break; }
            }
            if (chosen < 0) {
                postF(cb, false, -1, "Semua display floating penuh (:3-:4)");
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
            }
            final LauncherEntry fu = use;
            floatingDisplays.put(fu.id, disp);
            launchOnDisplay(fu, disp, (ok, msg) -> {
                if (!ok) floatingDisplays.remove(fu.id);
                postF(cb, ok, disp, msg);
            }, false);
        });
    }

    /**
     * Buka jendela floating untuk entri yang SUDAH jalan — display
     * dicari dari state live, bukan cuma map in-memory (ronde
     * 2 jendela, 2026-10-31): map floating hilang saat restart app
     * atau dibersihkan refresh dock, padahal proses + server masih
     * hidup — dulu jalur ini diam-diam tidak membuka jendela sama
     * sekali (skip launch / cabang "bukan floating" tanpa toast).
     * Urutan: map floating (bila servernya masih RUNNING) ->
     * lastEntry display :3-:4 -> DISPLAY environ proses. Hasil
     * di luar :3-:4 ditolak (aturan: xterm 2 jendela tidak pernah
     * :0/fullscreen) dan dilaporkan sebagai gagal, bukan diam.
     * Callback (ok, display, msg) selalu di main thread.
     */
    public void openFloatingWindowFor(LauncherEntry entry, FloatingCallback cb) {
        exec.execute(() -> {
            Integer mapped = floatingDisplays.get(entry.id);
            if (mapped != null && mapped >= 3 && mapped <= 4
                    && probeSync(mapped).state == XServerOps.State.RUNNING) {
                postF(cb, true, mapped, entry.name + " sudah jalan di :" + mapped);
                return;
            }
            for (int d = 3; d <= 4; d++) {
                if (probeSync(d).state == XServerOps.State.RUNNING
                        && entry.id.equals(lastEntryByDisplay.get(d))) {
                    floatingDisplays.put(entry.id, d);
                    postF(cb, true, d, entry.name + " sudah jalan di :" + d);
                    return;
                }
            }
            int live = ops.displayForEntry(entry.id);
            if (live >= 3 && live <= 4) {
                floatingDisplays.put(entry.id, live);
                lastEntryByDisplay.put(live, entry.id);
                lastEntryObjByDisplay.put(live, entry);
                postF(cb, true, live, entry.name + " sudah jalan di :" + live);
                return;
            }
            if (live >= 0) {
                postF(cb, false, -1, entry.name + " jalan di :" + live
                        + " (bukan jendela floating :3-:4)");
            } else {
                postF(cb, false, -1, entry.name + " jalan tapi display-nya tidak ketemu");
            }
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
                wmGuard = XServerOps.openboxReviveCommand(0);
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
        ops.runFitOnce(entry.id, d, 0, 0);
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
            return;
        }
        exec.execute(() -> {
            try {
                killServerSync(d);
            } catch (Exception e) {
            }
            launchOnDisplay(entry, d, (ok, msg) -> {
            }, false);
        });
    }

    public void notifyFloatingResized(int d, int w, int h) {
        String entryId = lastEntryByDisplay.get(d);
        if (entryId == null || w <= 0 || h <= 0) return;
        ops.runFitOnce(entryId, d, w, h);
    }

    /** Terapkan DPI ke sesi yang sedang jalan (xrdb langsung), tanpa restart. */
    public void applyDpiLive(Callback cb) {
        exec.execute(() -> {
            XServerOps.Probe p = probeSync();
            if (p.state != XServerOps.State.RUNNING) {
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
