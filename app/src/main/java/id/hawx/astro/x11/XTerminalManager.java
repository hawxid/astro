package id.hawx.astro.x11;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import id.hawx.astro.MainActivity;
import id.hawx.astro.utils.Core;
import id.hawx.astro.x11.window.XOverlayWindows;
import id.hawx.astro.x11.window.XWindowLayer;

/**
 * Manager display terminal (pisah sebagian, ronde terminal 2026-10-25).
 *
 * Prinsip: sisi terminal punya policy sendiri, pool sendiri (:1-:2),
 * dan TIDAK PERNAH dibunuh setengah-setengah oleh sisi lain. Kill dari
 * dock (3b) tetap boleh, tapi HARUS lewat release penuh di sini
 * (viewer tutup + server mati + map bebas) — bukan stopDisplay
 * telanjang yang dulu bikin kondisi setengah-terbuka log 35.
 *
 * Model perintah (gabung, revisi Hawx 2026-10-25): SATU perintah shell
 * `xrun` (fungsi di astro_profile.sh, bukan shim binary):
 * - `xrun` polos = jendela kosong + DISPLAY (perilaku don lama);
 *   xrun polos kedua = tampilkan lagi viewer kalau display udah jalan.
 * - `xrun <perintah>` = jendela langsung isi tool (perilaku xrun lama,
 *   tanpa nyentuh DISPLAY tab).
 * - `doff` = bunuh display polos tab itu. Fungsi `don` DIHAPUS.
 *
 * Satu watcher + satu dir request (/tmp/astro-xrun-requests), gate
 * core.isMounted() SAJA (tanpa toggle). Request basi >120 detik
 * dibuang; dieksekusi urut epoch nama berkas (bukan urutan ls yang
 * arbitrer — doff yang ditulis duluan harus jalan duluan).
 *
 * LIFE (5a): HANYA buat jendela xrun ber-argumen (entry-bound,
 * auto-tutup Ctrl+D dipertahankan). Display polos TIDAK di-LIFE-watch
 * (user-driven: hidup sampai doff/tutup tab/kill dock).
 */
public class XTerminalManager {

    /** Pool terminal. Desktop = :3-:4 (+ :0). */
    public static final int TERM_MIN = 1;
    public static final int TERM_MAX = 2;

    public static boolean isTerminalDisplay(int d) {
        return d >= TERM_MIN && d <= TERM_MAX;
    }

    private static final String PREFS = "astro_x11";
    private static final String KEY_DPI = "xft_dpi";
    private static final int DPI_DEFAULT = 192;

    private static final String REQ_DIR = "/tmp/astro-xrun-requests";
    private static final String READY_DIR = "/tmp/astro-xrun-ready";
    private static final long POLL_MS = 2000L;
    private static final long STALE_SEC = 120L;

    private static XTerminalManager instance;

    public static synchronized XTerminalManager get(Context ctx) {
        if (instance == null)
            instance = new XTerminalManager(ctx.getApplicationContext());
        instance.syncWatcher();
        return instance;
    }

    private final Context appCtx;
    private final Core core;
    private final XServerOps ops;
    private final java.util.concurrent.ExecutorService exec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** PID (sesi dan/atau bash) -> display polos tab itu. */
    private final java.util.Map<Integer, Integer> bareDisplayByPid =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Display -> entri xrun ber-argumen yang jalan di situ. */
    private final java.util.Map<Integer, CmdEntry> cmdEntryByDisplay =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static class CmdEntry {
        String id;
        String firstWord;
        String command;
    }

    private java.util.concurrent.ScheduledExecutorService watcherPoller;

    // ---- LIFE terminal (hanya jendela xrun ber-argumen) ----
    private final java.util.Map<Integer, Boolean> lifeSeen =
            new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> lifeDeadStreak =
            new java.util.HashMap<>();
    private final java.util.Set<Integer> lifeWatched =
            new java.util.HashSet<>();
    private java.util.concurrent.ScheduledExecutorService lifePoller;

    private XTerminalManager(Context ctx) {
        this.appCtx = ctx;
        this.core = new Core(ctx);
        this.ops = new XServerOps(core);
    }

    public int getDpi() {
        return appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_DPI, DPI_DEFAULT);
    }

    // ------------------------------------------------------------ watcher gabungan

    /** Sinkronisasi watcher gabungan: jalan bila chroot mounted, mati
     *  bila unmounted. Idempotent; dipanggil tiap get() supaya transisi
     *  mount ketahuan tanpa hook khusus. */
    public synchronized void syncWatcher() {
        boolean want = core.isMounted();
        if (want && watcherPoller == null) {
            watcherPoller = java.util.concurrent.Executors
                    .newSingleThreadScheduledExecutor();
            // Bersihkan antrean LAMA: berkas yang sudah ada pas watcher
            // nyala itu basi by definition (kasus lama: request xrun
            // dari tes lama tereksekusi sendiri pas watcher jalan).
            core.threadChrootCommand("rm -f " + REQ_DIR + "/req-* "
                    + REQ_DIR + "/xrun-* " + REQ_DIR + "/off-* "
                    + REQ_DIR + "/*.tmp 2>/dev/null");
            watcherPoller.scheduleWithFixedDelay(this::pollRequests,
                    0, POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            // Profil hidup HARUS segar SEBELUM tab baru lahir (live
            // profile cuma update saat mount — tanpa ini tab baru tak
            // kenal xrun()).
            refreshLiveProfile();
            X11DebugLog.log("XRUN", "watcher gabung AKTIF (poll 2 detik, gate mounted) + profil hidup disegarkan");
        } else if (!want && watcherPoller != null) {
            watcherPoller.shutdownNow();
            watcherPoller = null;
            X11DebugLog.log("XRUN", "watcher gabung mati (chroot unmounted)");
        }
    }

    /** Segarkan salinan profil hidup di chroot (best-effort). Isi yang
     *  disalin = byte asset yang sama dengan yang dipakai bootroot
     *  (idempotent). Shell yang SUDAH jalan tetap memakai profil lama
     *  (profil cuma di-source saat shell lahir) — tab baru langsung
     *  kenal xrun(). */
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

    private static class Req {
        final String kind;
        final int ppid;
        final long epoch;
        final String name;

        Req(String kind, int ppid, long epoch, String name) {
            this.kind = kind;
            this.ppid = ppid;
            this.epoch = epoch;
            this.name = name;
        }
    }

    /** Satu putaran polling request xrun/off. Kerja berat dilempar ke
     *  thread exec (satu jalur dengan launch) supaya probe + start
     *  server tak balapan. */
    private void pollRequests() {
        try {
            if (!core.isMounted()) {
                // Berhenti lewat main thread (bukan shutdownNow dari
                // dalam tugas poller sendiri).
                main.post(this::syncWatcher);
                return;
            }
            java.util.List<String> names = core.customChrootCommand(
                    "ls " + REQ_DIR + " 2>/dev/null");
            if (names == null) return;
            java.util.List<Req> reqs = new java.util.ArrayList<>();
            for (String raw : names) {
                String name = raw.trim();
                String kind;
                String rest;
                if (name.startsWith("xrun-cmd-")) {
                    kind = "cmd";
                    rest = name.substring("xrun-cmd-".length());
                } else if (name.startsWith("xrun-")) {
                    kind = "bare";
                    rest = name.substring("xrun-".length());
                } else if (name.startsWith("off-")) {
                    kind = "off";
                    rest = name.substring("off-".length());
                } else {
                    continue;
                }
                int dash = rest.lastIndexOf('-');
                if (dash <= 0) continue;
                int ppid;
                long epoch;
                try {
                    ppid = Integer.parseInt(rest.substring(0, dash));
                    epoch = Long.parseLong(rest.substring(dash + 1));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                // Request basi (>120 detik): tab yang menulis sudah
                // mati / watcher lama tertidur — buang, jangan nyalakan
                // server yatim buat tab yang tak ada lagi.
                if (epoch > 0
                        && System.currentTimeMillis() / 1000 - epoch > STALE_SEC) {
                    core.customChrootCommand("rm -f " + REQ_DIR + "/" + name);
                    X11DebugLog.log("XRUN", "request basi dibuang: " + name);
                    continue;
                }
                reqs.add(new Req(kind, ppid, epoch, name));
            }
            // Urutan kronologis (epoch nama berkas), bukan urutan ls
            // yang arbitrer: doff yang ditulis duluan harus dieksekusi
            // duluan (jebakan laten model eksplisit: satu batch
            // don+off yang kebalik).
            reqs.sort((a, b) -> Long.compare(a.epoch, b.epoch));
            for (Req r : reqs) {
                final Req fr = r;
                java.util.List<String> out = core.customChrootCommand(
                        "cat " + REQ_DIR + "/" + fr.name
                                + "; rm -f " + REQ_DIR + "/" + fr.name);
                if (out == null) continue;
                String content = String.join(" ", out).trim();
                if (fr.kind.equals("cmd")) {
                    if (content.isEmpty()) continue;
                    final String fCmd = content;
                    exec.execute(() -> handleCmdXrun(fr.ppid, fCmd));
                } else {
                    int shellPid = -1;
                    try {
                        shellPid = Integer.parseInt(content);
                    } catch (NumberFormatException ignored) {
                    }
                    final int fPpid = fr.ppid;
                    final int fShell = shellPid;
                    if (fr.kind.equals("bare")) {
                        exec.execute(() -> handleBareXrun(fPpid, fShell));
                    } else {
                        exec.execute(() -> handleOff(fPpid, fShell));
                    }
                }
            }
        } catch (Throwable t) {
            // chroot sibuk/mati di tengah jalan — putaran berikut mencoba lagi
        }
    }

    // ------------------------------------------------------------ handler request

    private boolean ensureBare(int d, StringBuilder rep) {
        return ops.ensureRunningSync(d, rep,
                ops.sessionSetupBareCommand(d, getDpi()),
                "server :" + d + " jalan, setup bare (font/DPI, tanpa WM)",
                null);
    }

    // REVISI 2026-10-25 (gas Hawx): display don = mini-desktop
    // ber-openbox di dalam. Cabang already-running ikut me-revive
    // openbox don (WM bisa mati sementara server hidup).
    private boolean ensureDon(int d, StringBuilder rep) {
        final int dd = d;
        return ops.ensureRunningSync(d, rep,
                ops.sessionSetupOpenboxCommand(d, getDpi()),
                "server :" + d + " jalan, setup don (font/DPI/openbox)",
                () -> reviveDonOpenbox(dd));
    }

    /** Revive openbox display don fire-and-forget (tak pernah
     *  menggagalkan pemanggil): hanya bila chroot ter-mount. */
    private void reviveDonOpenbox(int d) {
        try {
            if (!core.isMounted()) return;
            core.threadChrootCommand("export DISPLAY=:" + d + "; " + XServerOps.openboxReviveCommand(d));
            X11DebugLog.log("DON", "revive openbox don :" + d);
        } catch (Throwable ignored) {
        }
    }

    private void showTermViewer(int d, String title) {
        boolean shown = XOverlayWindows.show(appCtx, d, title);
        if (!shown) MainActivity.openXWindowFrom(appCtx, d, title);
    }

    private void writeReady(int ppid, String content) {
        try {
            core.customChrootCommand("mkdir -p " + READY_DIR
                    + " && printf '%s' '" + content + "' > "
                    + READY_DIR + "/" + ppid);
        } catch (Throwable t) {
            X11DebugLog.log("XRUN", "tulis ready pid=" + ppid + " gagal: " + t);
        }
    }

    private void removeBareKeys(int d) {
        java.util.List<Integer> pids = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : bareDisplayByPid.entrySet()) {
            if (e.getValue() != null && e.getValue().intValue() == d) pids.add(e.getKey());
        }
        for (Integer p : pids) bareDisplayByPid.remove(p);
    }

    /** xrun polos (thread exec saja). Identitas ganda: ppid (nama
     *  berkas request) + shellPid (isi request = $$ bash). Fork su ->
     *  ppid = pid sesi; exec langsung -> shellPid = pid sesi. Keduanya
     *  dipetakan, jadi doff dan TAB_REMOVED cocok di kedua perilaku su.
     *  Nomor lowest-free :1-:2, lewati RUNNING + nomor milik terminal
     *  lain (nomor milik tab hidup tak boleh dipakai ulang). */
    private void handleBareXrun(int ppid, int shellPid) {
        Integer have = bareDisplayByPid.get(ppid);
        if (have == null && shellPid > 0) have = bareDisplayByPid.get(shellPid);
        if (have != null) {
            // Idempotent TAPI terverifikasi (pelajaran log 35: don lama
            // percaya peta buta dan bilang "sudah jalan" padahal server
            // mati). Server hidup -> tampilkan lagi viewer (6a), bukan
            // raise diam-diam tanpa cek; server mati -> buang peta,
            // jatuh ke alokasi baru di bawah.
            XServerOps.Probe p = ops.probeSync(have);
            if (p.state == XServerOps.State.RUNNING && p.sock && !p.zombie) {
                writeReady(ppid, "R:" + have);
                final int disp = have;
                main.post(() -> showTermViewer(disp, "Display :" + disp));
                // Re-show ikut me-revive openbox don: server hidup
                // tapi WM-nya bisa sudah mati.
                reviveDonOpenbox(have);
                X11DebugLog.log("XRUN", "xrun polos pid=" + ppid + " -> :" + have + " (tampil lagi + revive openbox)");
                return;
            }
            removeBareKeys(have);
            X11DebugLog.log("XRUN", "xrun polos pid=" + ppid + " peta :" + have
                    + " server mati — alokasi baru");
        }
        int chosen = -1;
        for (int d = TERM_MIN; d <= TERM_MAX; d++) {
            if (bareDisplayByPid.containsValue(d)) continue;
            if (cmdEntryByDisplay.containsKey(d)) continue;
            if (ops.probeSync(d).state != XServerOps.State.RUNNING) { chosen = d; break; }
        }
        if (chosen < 0) {
            writeReady(ppid, "POOL_FULL");
            X11DebugLog.log("XRUN", "xrun polos pid=" + ppid + " ditolak: pool terminal penuh (:1-:2)");
            return;
        }
        bareDisplayByPid.put(ppid, chosen);
        if (shellPid > 0 && shellPid != ppid) bareDisplayByPid.put(shellPid, chosen);
        StringBuilder rep = new StringBuilder();
        boolean ok = ensureDon(chosen, rep);
        if (!ok) {
            removeBareKeys(chosen);
            writeReady(ppid, "FAILED");
            X11DebugLog.log("XRUN", "xrun polos pid=" + ppid + " gagal start :" + chosen + ": " + rep);
            return;
        }
        writeReady(ppid, ":" + chosen);
        X11DebugLog.log("XRUN", "xrun polos pid=" + ppid + " -> :" + chosen + " (server jalan, viewer dibuka)");
        final int disp = chosen;
        main.post(() -> showTermViewer(disp, "Display :" + disp));
    }

    /** xrun ber-argumen (thread exec saja): display sendiri buat tool
     *  itu, TANPA nyentuh DISPLAY tab pemanggil. Entry-bound -> masuk
     *  LIFE terminal (5a). Fire-and-forget: pool penuh / gagal start
     *  hanya tercatat di log (shell sudah kembali). */
    private void handleCmdXrun(int ppid, String cmd) {
        X11DebugLog.log("XRUN", "request: " + cmd);
        int chosen = -1;
        for (int d = TERM_MIN; d <= TERM_MAX; d++) {
            if (bareDisplayByPid.containsValue(d)) continue;
            if (cmdEntryByDisplay.containsKey(d)) continue;
            if (ops.probeSync(d).state != XServerOps.State.RUNNING) { chosen = d; break; }
        }
        if (chosen < 0) {
            X11DebugLog.log("XRUN", "xrun cmd ditolak: pool terminal penuh (:1-:2): " + cmd);
            return;
        }
        String fw = cmd.split("\\s+")[0];
        CmdEntry e = new CmdEntry();
        e.id = "xrun-" + (System.currentTimeMillis() / 1000);
        e.firstWord = fw;
        e.command = cmd;
        cmdEntryByDisplay.put(chosen, e);
        StringBuilder rep = new StringBuilder();
        boolean ok = ensureBare(chosen, rep);
        if (!ok) {
            cmdEntryByDisplay.remove(chosen);
            X11DebugLog.log("XRUN", "xrun cmd gagal start :" + chosen + ": " + rep);
            return;
        }
        String launchCmd = "export DISPLAY=:" + chosen + "; export TMPDIR=/tmp; "
                + "xset fp+ " + XServerOps.root() + "/usr/share/fonts/X11/misc 2>/dev/null; xset fp rehash 2>/dev/null; "
                + "cd /root; "
                + "(setsid " + XServerOps.scaledCommand(cmd, fw, getDpi())
                + " >" + XServerOps.entryLogPath(e.id) + " 2>&1 & "
                + "echo $! > /tmp/astro-launch-" + e.id + ".pid)";
        try {
            core.threadChrootCommand(launchCmd);
            Thread.sleep(500);
        } catch (Exception ex) {
            X11DebugLog.log("XRUN", "xrun cmd gagal luncur :" + chosen + ": " + ex);
            return;
        }
        final int disp = chosen;
        final String title = "Terminal \u00B7 " + fw + " \u00B7 :" + disp;
        main.post(() -> showTermViewer(disp, title));
        watchXrunDisplay(disp);
        ops.runFitOnce(e.id, disp, 0, 0);
    }

    /** doff (thread exec saja): lepas display POLOS tab itu. Display
     *  xrun per-tool TIDAK ikut mati (bukan milik tab). */
    private void handleOff(int ppid, int shellPid) {
        Integer d = bareDisplayByPid.get(ppid);
        if (d == null && shellPid > 0) d = bareDisplayByPid.get(shellPid);
        if (d == null) {
            X11DebugLog.log("XRUN", "doff pid=" + ppid + " dilewat: tidak ada display");
            return;
        }
        releaseDisplayInternal(d);
    }

    /** Tab ditutup (broadcast TAB_REMOVED): lepas display POLOS tab
     *  itu SAJA. Display xrun per-tool TIDAK ikut mati saat tab tutup
     *  (hidupnya ikut tool, bukan tab). */
    public void onTabRemoved(int pid) {
        exec.execute(() -> {
            Integer d = bareDisplayByPid.get(pid);
            if (d != null) releaseDisplayInternal(d);
        });
    }

    /** Release penuh (dipakai doff, tutup tab, kill dock, ✕ decor):
     *  viewer tutup (decor + layer bila sempat dicuri dock) + server
     *  mati + nomor bebas + ready bersih. Idempotent: server yang
     *  sudah mati = no-op, viewer yang sudah tutup = no-op. */
    public void releaseDisplay(int d) {
        releaseDisplay(d, null);
    }

    public void releaseDisplay(int d, XSessionManager.Callback cb) {
        exec.execute(() -> {
            releaseDisplayInternal(d);
            if (cb != null) {
                main.post(() -> cb.onResult(true, "Display terminal :" + d + " dimatikan"));
            }
        });
    }

    private void releaseDisplayInternal(int d) {
        // Kumpulkan pid dulu (sebelum peta dihapus) supaya ready file
        // mereka ikut terhapus — ready basi + PID reuse = fungsi shell
        // tab baru bisa membaca jawaban display yang salah.
        java.util.List<Integer> pids = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : bareDisplayByPid.entrySet()) {
            if (e.getValue() != null && e.getValue().intValue() == d) pids.add(e.getKey());
        }
        removeBareKeys(d);
        cmdEntryByDisplay.remove(d);
        unwatchXrunDisplay(d);
        final int disp = d;
        main.post(() -> {
            XWindowLayer.closeForDisplay(disp);
            XOverlayWindows.closeForDisplay(disp);
        });
        ops.killServerSync(d);
        try {
            StringBuilder rm = new StringBuilder();
            for (Integer p : pids) {
                if (rm.length() > 0) rm.append(' ');
                rm.append(READY_DIR).append('/').append(p);
            }
            if (rm.length() > 0) core.customChrootCommand("rm -f " + rm);
        } catch (Throwable ignored) {
        }
        X11DebugLog.log("XRUN", "display terminal :" + d + " dilepas (release penuh)");
    }

    // ------------------------------------------------------------ LIFE terminal

    /** Watch jendela xrun ber-argumen (entry-bound). Display polos
     *  JANGAN pernah di-watch (5a). */
    public synchronized void watchXrunDisplay(int d) {
        lifeWatched.add(d);
        syncLifePoller();
    }

    public synchronized void unwatchXrunDisplay(int d) {
        lifeWatched.remove(d);
        lifeSeen.remove(d);
        lifeDeadStreak.remove(d);
        syncLifePoller();
    }

    private synchronized void syncLifePoller() {
        if (!lifeWatched.isEmpty() && lifePoller == null) {
            lifePoller = java.util.concurrent.Executors
                    .newSingleThreadScheduledExecutor();
            lifePoller.scheduleWithFixedDelay(this::pollXrunLife,
                    1000, 4000, java.util.concurrent.TimeUnit.MILLISECONDS);
            X11DebugLog.log("LIFE", "watcher term jalan (" + lifeWatched.size() + " display)");
        } else if (lifeWatched.isEmpty() && lifePoller != null) {
            lifePoller.shutdownNow();
            lifePoller = null;
            X11DebugLog.log("LIFE", "watcher term mati");
        }
    }

    private void pollXrunLife() {
        java.util.Set<Integer> snapshot;
        synchronized (this) {
            snapshot = new java.util.HashSet<>(lifeWatched);
        }
        for (Integer d : snapshot) {
            try {
                CmdEntry entry = cmdEntryByDisplay.get(d);
                if (entry == null) continue;
                boolean running = ops.entryRunningSync(entry.id, entry.firstWord);
                synchronized (this) {
                    if (!lifeWatched.contains(d)) continue;
                    if (running) {
                        lifeSeen.put(d, true);
                        lifeDeadStreak.put(d, 0);
                    } else if (Boolean.TRUE.equals(lifeSeen.get(d))) {
                        int streak = lifeDeadStreak.get(d) == null
                                ? 1 : lifeDeadStreak.get(d) + 1;
                        lifeDeadStreak.put(d, streak);
                        X11DebugLog.log("LIFE", "term d=" + d + " entri MATI streak=" + streak);
                        if (streak >= 2) {
                            lifeWatched.remove(d);
                            lifeSeen.remove(d);
                            lifeDeadStreak.remove(d);
                            final int disp = d;
                            main.post(() -> {
                                XOverlayWindows.closeForDisplay(disp);
                                releaseDisplay(disp);
                            });
                        }
                    }
                }
            } catch (Throwable t) {
                // chroot sibuk — poll berikut mencoba lagi, jangan tutup apa-apa
            }
        }
    }

    // ------------------------------------------------------------ pemulihan + fit

    /** Pemulihan server macet buat display terminal (dipanggil host
     *  yang nunggu binder ±6 detik): restart server (don: setup
     *  openbox, cmd: setup bare), TANPA
     *  lewat entri desktop (lastEntryObjByDisplay milik desktop).
     *  Tool xrun yang sempat jalan bisa mati ikut server restart —
     *  LIFE terminal lalu menutup jendelanya bersih (lebih baik dari
     *  jendela hitam permanen). Display polos: host sambung ulang
     *  lewat knock segar. */
    public void recoverTerminalDisplay(int d) {
        exec.execute(() -> {
            X11DebugLog.log("HANDOFF", "pemulihan term d=" + d + ": restart server");
            try {
                ops.killServerSync(d);
            } catch (Exception e) {
                X11DebugLog.log("HANDOFF", "pemulihan term d=" + d + " kill gagal: " + e);
            }
            StringBuilder rep = new StringBuilder();
            // Restart pakai setup display itu sendiri: don -> openbox,
            // xrun per-tool -> bare (revisi 2026-10-25).
            boolean ok = cmdEntryByDisplay.containsKey(d) ? ensureBare(d, rep) : ensureDon(d, rep);
            X11DebugLog.log("HANDOFF", "pemulihan term d=" + d + " restart: " + (ok ? "ok" : rep));
        });
    }

    /** Fit ulang konten display terminal saat surface berubah ukuran.
     *  Hanya display xrun ber-argumen (punya entri); display polos
     *  tak punya entri tunggal — dilewat (klien dari tab mengatur
     *  ukurannya sendiri). */
    public void notifyTerminalResized(int d, int w, int h) {
        CmdEntry entry = cmdEntryByDisplay.get(d);
        if (entry == null || w <= 0 || h <= 0) return;
        ops.runFitOnce(entry.id, d, w, h);
    }
}
