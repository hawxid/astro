package id.hawx.astro.frida;

import android.content.Context;

import androidx.annotation.NonNull;

import id.hawx.astro.engine.Apt;
import id.hawx.astro.utils.Core;
import id.hawx.astro.x11.WebUiLauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Management server Frida (fondasi Batch16, 2026-11-01 — disetujui
 * Hawx: "gas" atas rencana konfirmasi).
 *
 * Mengelola lifecycle DUA komponen, ON-DEMAND saja:
 * (a) frida-server v17.25.0, binary sisi-Android, jalan sebagai root,
 *     bind 127.0.0.1:27042 SAJA (tidak pernah 0.0.0.0);
 * (b) bridge chroot Python (assets/astro_frida_bridge.py), bind
 *     127.0.0.1:18081 SAJA, token sesi acak per start.
 *
 * BUKAN UI dan BUKAN launcher entry: kelas ini library yang akan
 * dipanggil batch web UI nanti. Satu-satunya kaitan UI di batch ini
 * adalah closeHook() — Runnable untuk onClose WebOverlayWindows
 * (✕ jendela Frida menghentikan server, fail-safe keamanan).
 *
 * Aturan keras (dari diskusi keamanan 2026-11-01):
 * - Localhost-only di semua tempat; tidak ada flag/bind 0.0.0.0.
 * - Binary MANUAL, bukan bundel (Batch18, arahan Hawx):
 *   user menaruh sendiri binary resmi Frida di
 *   filesDir/frida-server (versi PoC: 17.25.0). Manager TIDAK
 *   PERNAH mengunduh, menyalin asset, atau mengekstrak. Aset
 *   .xz DIHAPUS dari repo/APK (bloated + beban download tiap
 *   test) — APK kembali ~18 MB.
 * - On-demand + kill-after: tidak ada autostart, tidak ada daemon
 *   boot, tidak ada proses permanen. start() idempotent (komponen
 *   yang sudah jalan tidak di-start ulang); stop() best-effort
 *   dan tidak pernah throw.
 * - Urutan stop: bridge (klien) dulu, lalu frida-server.
 * - Token sesi TIDAK PERNAH ditulis ke log (perintah shell yang
 *   dieksekusi Core.executeCommand/customChrootCommand dicatat
 *   Core logger — karena itu token TIDAK PERNAH muncul di string
 *   perintah; token hanya lewat field + env proses bridge yang
 *   TIDAK dicatat: perintah start bridge memakai env di dalam
 *   shell chroot yang sama, dan Core customChrootCommand(..., true)
 *   = nolog).
 *
 * Semantik status yang jujur:
 * - serverRunning = "ada frida-server yang listening di 27042",
 *   BUKAN "server milik manajer ini". Bila Hawx menjalankan
 *   frida-server manual (PoC) di port yang sama, status tetap
 *   true dan start() tidak menabraknya (tidak start ulang).
 * - stop() hanya membunuh instance MILIK MANAJER (PID file);
 *   server asing (mis. PoC manual di /data/local/tmp) TIDAK
 *   PERNAH dibunuh — fallback pkill memakai pola path biner
 *   filesDir, bukan nama generik.
 * - sessionToken hanya dikenal untuk bridge yang di-start manajer
 *   ini; bila bridge sudah jalan dari pihak lain, token = null
 *   (restart bridge lewat stop()+start() untuk regenerasi).
 *
 * Threading: SEMUA method publik yang menyentuh shell/socket HARUS
 * dipanggil dari thread latar (bukan UI thread) — sama seperti
 * Core.customChrootCommand dan WebUiLauncher.isListening. Satu
 * pengecualian: closeHook() aman dipanggil dari UI thread karena
 * ia hanya melahirkan thread latar (pola WebUiLauncher.killServer).
 */
public final class FridaManager {

    /** frida-server: host TERKUNCI, port default Frida. */
    public static final String FRIDA_HOST = "127.0.0.1";
    public static final int FRIDA_PORT = 27042;
    /** Bridge chroot: host TERKUNCI, port bebas (cek: tak ada
     *  pemakai 18081 di codebase; bluez memakai 18923). */
    public static final int BRIDGE_PORT = 18081;

    /** Binary frida-server: DITARUH MANUAL user di filesDir
     *  (bukan asset) — lihat ensureBinaryPresent(). */
    private static final String BRIDGE_ASSET = "astro_frida_bridge.py";
    private static final String BRIDGE_CHROOT_PATH = "/tmp/astro_frida_bridge.py";
    private static final String CHROOT_ROOT = "/data/local/astro/release";
    private static final int POLL_TRIES = 10;
    private static final int POLL_MS = 1000;
    private static final int HEALTH_TIMEOUT_MS = 1000;

    /** Potret status — field final, dibaca call site apa adanya. */
    public static final class Status {
        public final boolean serverRunning;
        public final boolean bridgeRunning;
        /** Token sesi bridge saat ini, atau null bila bridge tidak
         *  di-start manajer ini / tidak jalan. */
        public final String sessionToken;

        Status(boolean serverRunning, boolean bridgeRunning, String sessionToken) {
            this.serverRunning = serverRunning;
            this.bridgeRunning = bridgeRunning;
            this.sessionToken = sessionToken;
        }
    }

    private final Context appContext;
    private final Core core;
    private volatile String sessionToken;

    public FridaManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.core = new Core(appContext);
    }

    // ---------------- path file ----------------

    private File fridaBinFile() {
        return new File(appContext.getFilesDir(), "frida-server");
    }

    private File fridaPidFile() {
        return new File(appContext.getFilesDir(), "frida-server.pid");
    }

    private File fridaLogFile() {
        return new File(appContext.getFilesDir(), "frida-server.log");
    }

    // ---------------- API utama ----------------

    /**
     * Start on-demand (idempotent). Komponen yang sudah listening
     * tidak di-start ulang. Melempar IOException dengan pesan jelas
     * bila ada komponen gagal hidup (binary tidak ditemukan di
     * filesDir, chroot belum terpasang, port tidak menjawab).
     * WAJIB thread latar.
     */
    public synchronized Status start() throws IOException {
        Status cur = status();
        boolean serverUp = cur.serverRunning;
        boolean bridgeUp = cur.bridgeRunning;
        if (!serverUp) {
            ensureBinaryPresent();
            startServer();
            serverUp = waitListening(FRIDA_PORT);
            if (!serverUp) {
                throw new IOException("frida-server tidak menjawab di 127.0.0.1:"
                        + FRIDA_PORT + " — cek log: " + fridaLogFile().getAbsolutePath());
            }
        }
        if (!bridgeUp) {
            Boolean mounted = core.isMounted();
            if (mounted == null || !mounted) {
                throw new IOException("Chroot belum terpasang — bridge Frida "
                        + "tidak bisa jalan (server frida tetap hidup)");
            }
            ensureBridgeScript();
            String token = UUID.randomUUID().toString();
            startBridge(token);
            bridgeUp = waitBridgeHealthy();
            if (!bridgeUp) {
                throw new IOException("Bridge Frida tidak menjawab di 127.0.0.1:"
                        + BRIDGE_PORT + " — cek log chroot: /tmp/astro_frida_bridge.log");
            }
            sessionToken = token;
        }
        return status();
    }

    /**
     * Stop bersih, best-effort, TIDAK PERNAH throw. Urutan: bridge
     * dulu (pkill pola skrip + jeda), lalu frida-server (PID file,
     * fallback pkill pola path biner filesDir — TIDAK PERNAH
     * membunuh server asing di path lain). WAJIB thread latar.
     */
    public synchronized void stop() {
        try {
            core.customChrootCommand("pkill -f astro_frida_bridge.py; sleep 1", true);
        } catch (Throwable ignored) {
        }
        sessionToken = null;
        try {
            String pid = readPidFile();
            if (pid != null) {
                core.executeCommand("if [ -d /proc/" + pid + " ]; then kill "
                        + pid + " 2>/dev/null; fi; rm -f "
                        + Apt.shellQuote(fridaPidFile().getAbsolutePath()));
            }
            if (WebUiLauncher.isListening(FRIDA_PORT)) {
                core.executeCommand("pkill -f "
                        + Apt.shellQuote(fridaBinFile().getAbsolutePath())
                        + " 2>/dev/null; sleep 1");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Status cepat (dua socket connect + satu GET /health). WAJIB thread latar. */
    public Status status() {
        boolean serverUp = WebUiLauncher.isListening(FRIDA_PORT);
        boolean bridgeUp = bridgeHealthy();
        String token = bridgeUp ? sessionToken : null;
        return new Status(serverUp, bridgeUp, token);
    }

    /** Token sesi bridge saat ini (null bila tidak ada). */
    public String sessionToken() {
        return sessionToken;
    }

    /**
     * Hook tutup untuk jendela Frida: pass sebagai onClose ke
     * WebOverlayWindows.show(...) — ✕ menghentikan server lewat
     * thread latar (aman dipanggil dari UI thread; pola
     * WebUiLauncher.killServer). Fire-and-forget.
     */
    public Runnable closeHook() {
        return () -> {
            Thread t = new Thread(() -> FridaManager.this.stop(), "frida-stop");
            t.start();
        };
    }

    // ---------------- frida-server ----------------

    /**
     * Manager-only (Batch18): binary TIDAK PERNAH diunduh,
     * disalin dari asset, atau diekstrak. User menaruh sendiri
     * binary resmi Frida di filesDir/frida-server; manager hanya
     * memastikan file itu ada + bisa dieksekusi. Bila tidak ada,
     * gagal dengan pesan jelas (bukan ekstrak diam-diam).
     */
    private void ensureBinaryPresent() throws IOException {
        File bin = fridaBinFile();
        if (!bin.exists()) {
            throw new IOException("frida-server tidak ditemukan di "
                    + bin.getAbsolutePath() + " — taruh binary manual "
                    + "di situ lalu Start lagi");
        }
        if (!bin.canExecute()) {
            bin.setExecutable(true);
        }
        if (!bin.canExecute()) {
            throw new IOException("frida-server di "
                    + bin.getAbsolutePath() + " tidak bisa dieksekusi "
                    + "— chmod +x lalu Start lagi");
        }
    }

    /** Start frida-server detached (pola WebUiLauncher: setsid + log + PID file). */
    private void startServer() {
        String cmd = "setsid " + Apt.shellQuote(fridaBinFile().getAbsolutePath())
                + " -l " + FRIDA_HOST
                + " > " + Apt.shellQuote(fridaLogFile().getAbsolutePath())
                + " 2>&1 < /dev/null & echo $! > "
                + Apt.shellQuote(fridaPidFile().getAbsolutePath());
        core.executeCommand(cmd);
    }

    private String readPidFile() {
        File pidFile = fridaPidFile();
        if (!pidFile.exists()) return null;
        try (FileInputStream in = new FileInputStream(pidFile)) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n <= 0) return null;
            String pid = new String(buf, 0, n, "UTF-8").trim();
            if (pid.isEmpty()) return null;
            Integer.parseInt(pid);
            return pid;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------- bridge ----------------

    /** Salin skrip bridge asset -> filesDir -> chroot /tmp (pola
     *  BluezKeysMouseTransport.ensureScript persis). */
    private void ensureBridgeScript() throws IOException {
        File filesScript = new File(appContext.getFilesDir(), BRIDGE_ASSET);
        if (!filesScript.exists()) {
            copyAsset(BRIDGE_ASSET, filesScript);
        }
        core.copyFile(filesScript.getAbsolutePath(), CHROOT_ROOT + BRIDGE_CHROOT_PATH);
        core.customChrootCommand("chmod +x " + BRIDGE_CHROOT_PATH, true);
    }

    /**
     * Start bridge (pola Bluez: bunuh sisa lama, nohup + log).
     * Token TIDAK PERNAH masuk string perintah yang dicatat:
     * perintah ini jalan lewat customChrootCommand(..., true) =
     * nolog, dan env assignment tidak pernah ditulis ke log Astro.
     */
    private void startBridge(@NonNull String token) {
        core.customChrootCommand("pkill -f astro_frida_bridge.py; sleep 1", true);
        core.customChrootCommand("ASTRO_FRIDA_TOKEN=" + token
                + " nohup python3 " + BRIDGE_CHROOT_PATH
                + " > /tmp/astro_frida_bridge.log 2>&1 & echo started", true);
    }

    // ---------------- cek hidup ----------------

    private boolean waitListening(int port) {
        for (int i = 0; i < POLL_TRIES; i++) {
            if (WebUiLauncher.isListening(port)) return true;
            sleepQuietly(POLL_MS);
        }
        return WebUiLauncher.isListening(port);
    }

    private boolean waitBridgeHealthy() {
        for (int i = 0; i < POLL_TRIES; i++) {
            if (bridgeHealthy()) return true;
            sleepQuietly(POLL_MS);
        }
        return bridgeHealthy();
    }

    /** GET /health tanpa token (liveness saja). False = tidak jalan / timeout. */
    private boolean bridgeHealthy() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + FRIDA_HOST + ":" + BRIDGE_PORT + "/health");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HEALTH_TIMEOUT_MS);
            conn.setReadTimeout(HEALTH_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------- util ----------------

    /** Salin asset -> file filesDir (pola .part + rename, seperti
     *  Core.extractBusybox). */
    private void copyAsset(@NonNull String asset, @NonNull File dest) throws IOException {
        File part = new File(dest.getParentFile(), dest.getName() + ".part");
        try (InputStream in = appContext.getAssets().open(asset);
             OutputStream os = new FileOutputStream(part)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
            os.flush();
        } catch (IOException e) {
            part.delete();
            throw new IOException("Gagal menyalin asset " + asset, e);
        }
        if (part.length() <= 0) {
            part.delete();
            throw new IOException("Asset " + asset + " kosong");
        }
        dest.delete();
        if (!part.renameTo(dest)) {
            part.delete();
            throw new IOException("Gagal menempatkan " + dest.getName());
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
