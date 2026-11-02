package id.hawx.astro.kismet;

import android.content.Context;

import androidx.annotation.NonNull;

import id.hawx.astro.utils.Core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Manager bridge alat mentah Kismet (Fase 1 integrasi WebUI Kismet x
 * alat mentah, 2026-11-02 — gas maksimal Hawx, fondasi dibuktikan
 * PoC SIGSTOP 22:29).
 *
 * Mengelola lifecycle SATU komponen, ON-DEMAND saja: bridge chroot
 * Python (assets/astro_kismet_tools_bridge.py), bind 127.0.0.1:18082
 * SAJA, token sesi acak per start. Bridge inilah yang mengeksekusi
 * mdk4 / aireplay-ng / airodump-ng / l2ping / sdptool atas perintah
 * WebUI Kismet (Fase 2), termasuk orkestrasi SIGSTOP Kismet +
 * fokus channel + SIGCONT.
 *
 * BUKAN UI dan BUKAN bagian KismetManager: kelas ini library yang
 * dipanggil panel Kismet (Fase 2). KismetManager TIDAK disentuh
 * batch ini — panel Kismet yang sudah terverifikasi perangkat
 * tetap apa adanya; wiring (start tools best-effort sesudah
 * Kismet start, stop tools SEBELUM Kismet stop) dikerjakan Fase 2
 * bersama mod WebUI, karena token bridge baru bermakna saat WebUI
 * dibuka (cookie sesi untuk host 127.0.0.1, port-independent).
 *
 * Aturan keras (pola bridge Frida, disesuaikan):
 * - Localhost-only; tidak ada flag/bind 0.0.0.0.
 * - On-demand + kill-after: tidak ada autostart, tidak ada daemon
 *   boot. start() idempotent (bridge yang sudah jalan tidak
 *   di-start ulang); stop() best-effort dan tidak pernah throw.
 * - Token sesi TIDAK PERNAH ditulis ke log: perintah start bridge
 *   jalan lewat customChrootCommand(..., true) = nolog, dan token
 *   hanya lewat env proses bridge.
 * - Urutan stop di call site: tools bridge DULU, baru Kismet.
 *   Bridge mati via SIGTERM -> watchdog-nya (atexit/signal)
 *   men-CONT Kismet, jadi Kismet stop sesudahnya melihat proses
 *   yang jalan normal, bukan beku.
 * - sessionToken hanya dikenal untuk bridge yang di-start manajer
 *   ini; bila bridge sudah jalan dari pihak lain, token = null
 *   (restart bridge lewat stop()+start() untuk regenerasi).
 *
 * Threading: SEMUA method publik yang menyentuh shell/socket HARUS
 * dipanggil dari thread latar (bukan UI thread) — sama seperti
 * Core.customChrootCommand.
 */
public final class KismetToolsManager {

    /** Bridge alat mentah: host TERKUNCI, port bebas (cek: tak ada
     *  pemakai 18082 di codebase; Frida memakai 18081). */
    public static final String TOOLS_HOST = "127.0.0.1";
    public static final int TOOLS_PORT = 18082;

    private static final String BRIDGE_ASSET = "astro_kismet_tools_bridge.py";
    private static final String BRIDGE_CHROOT_PATH = "/tmp/astro_kismet_tools_bridge.py";
    /** Mod WebUI (Fase 2): JS injeksi + skrip patch index.html. */
    private static final String WEBUI_JS_ASSET = "astro_kismet_webui_tools.js";
    private static final String WEBUI_PATCH_ASSET = "astro_kismet_webui_patch.sh";
    private static final String WEBUI_JS_CHROOT = "/tmp/astro_kismet_webui_tools.js";
    private static final String WEBUI_PATCH_CHROOT = "/tmp/astro_kismet_webui_patch.sh";
    private static final String CHROOT_ROOT = "/data/local/astro/release";
    private static final int POLL_TRIES = 10;
    private static final int POLL_MS = 1000;
    private static final int HEALTH_TIMEOUT_MS = 1000;

    /** Potret status — field final, dibaca call site apa adanya. */
    public static final class Status {
        public final boolean bridgeRunning;
        /** Token sesi bridge saat ini, atau null bila bridge tidak
         *  di-start manajer ini / tidak jalan. */
        public final String sessionToken;

        Status(boolean bridgeRunning, String sessionToken) {
            this.bridgeRunning = bridgeRunning;
            this.sessionToken = sessionToken;
        }
    }

    private final Context appContext;
    private final Core core;
    private volatile String sessionToken;

    public KismetToolsManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.core = new Core(appContext);
    }

    // ---------------- API utama ----------------

    /**
     * Start on-demand (idempotent). Bridge yang sudah listening
     * tidak di-start ulang. Melempar IOException dengan pesan jelas
     * bila chroot belum terpasang atau bridge tidak menjawab.
     * WAJIB thread latar.
     */
    public synchronized Status start() throws IOException {
        Status cur = status();
        if (cur.bridgeRunning) {
            return cur;
        }
        Boolean mounted = core.isMounted();
        if (mounted == null || !mounted) {
            throw new IOException("Chroot belum terpasang — bridge alat "
                    + "Kismet tidak bisa jalan");
        }
        ensureBridgeScript();
        ensureWebUiPatch();
        String token = UUID.randomUUID().toString();
        startBridge(token);
        if (!waitBridgeHealthy()) {
            throw new IOException("Bridge alat Kismet tidak menjawab di 127.0.0.1:"
                    + TOOLS_PORT + " — cek log chroot: /tmp/astro_kismet_tools_bridge.log");
        }
        sessionToken = token;
        return status();
    }

    /**
     * Stop bersih, best-effort, TIDAK PERNAH throw. pkill SIGTERM
     * memicu watchdog bridge (CONT Kismet bila beku), lalu jeda.
     * Call site WAJIB memanggil ini SEBELUM KismetManager.stop().
     * WAJIB thread latar.
     */
    public synchronized void stop() {
        try {
            core.customChrootCommand("pkill -f astro_kismet_tools_bridge.py; sleep 1", true);
        } catch (Throwable ignored) {
        }
        sessionToken = null;
    }

    /** Status cepat (satu GET /health). WAJIB thread latar. */
    public Status status() {
        boolean bridgeUp = bridgeHealthy();
        String token = bridgeUp ? sessionToken : null;
        return new Status(bridgeUp, token);
    }

    /** Token sesi bridge saat ini (null bila tidak ada). */
    public String sessionToken() {
        return sessionToken;
    }

    // ---------------- bridge ----------------

    /**
     * Aturan baca versi (pola FridaManager persis): baris pertama
     * skrip membawa marker "# ASTRO_KISMET_TOOLS_BRIDGE_VERSION = N".
     * Manager membaca versi asset (APK) dan versi filesDir, lalu
     * menimpa filesDir HANYA bila asset lebih baru. Bila versi sama,
     * filesDir dipertahankan apa adanya — tweak lokal selamat.
     * File tidak ada = 0; marker hilang / tidak bisa dibaca = 1;
     * asset yang tidak bisa dibaca / tanpa marker = IOException
     * (gagal start dengan pesan jelas, bukan diam-diam menjalankan
     * bridge lama). JANGAN hapus baris versi saat tweak lokal.
     */
    private static final String BRIDGE_VERSION_MARKER = "ASTRO_KISMET_TOOLS_BRIDGE_VERSION";

    /** Baca versi dari 10 baris pertama stream; -1 bila marker tak ada. */
    private int readBridgeVersion(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (int i = 0; i < 10; i++) {
            String line = reader.readLine();
            if (line == null) break;
            int marker = line.indexOf(BRIDGE_VERSION_MARKER);
            if (marker < 0) continue;
            int eq = line.indexOf('=', marker);
            if (eq < 0) continue;
            try {
                return Integer.parseInt(line.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /** Versi filesDir: tidak ada = 0, marker hilang / tak terbaca = 1. */
    private int readFilesBridgeVersion(File script) {
        if (script == null || !script.exists()) return 0;
        try (InputStream in = new FileInputStream(script)) {
            int version = readBridgeVersion(in);
            return version < 0 ? 1 : version;
        } catch (IOException ignored) {
            return 1;
        }
    }

    /** Versi asset APK; IOException bila asset tak bisa dibaca. */
    private int readAssetBridgeVersion() throws IOException {
        try (InputStream in = appContext.getAssets().open(BRIDGE_ASSET)) {
            int version = readBridgeVersion(in);
            if (version < 0) {
                throw new IOException("Asset bridge tanpa marker versi");
            }
            return version;
        }
    }

    /** Salin skrip bridge asset -> filesDir -> chroot /tmp, dengan
     *  update berversi: filesDir hanya ditimpa bila asset lebih baru. */
    private void ensureBridgeScript() throws IOException {
        File filesScript = new File(appContext.getFilesDir(), BRIDGE_ASSET);
        int filesVersion = readFilesBridgeVersion(filesScript);
        int assetVersion = readAssetBridgeVersion();
        if (!filesScript.exists() || assetVersion > filesVersion) {
            copyAsset(BRIDGE_ASSET, filesScript);
        }
        core.copyFile(filesScript.getAbsolutePath(), CHROOT_ROOT + BRIDGE_CHROOT_PATH);
        core.customChrootCommand("chmod +x " + BRIDGE_CHROOT_PATH, true);
    }

    /**
     * Pasang mod WebUI (best-effort, TIDAK PERNAH throw): salin JS
     * injeksi + skrip patch ke chroot /tmp, lalu jalankan skrip
     * patch. Skrip patch yang menemukan httpd, membackup
     * index.html, dan menyisipkan tag script — idempotent +
     * tahan update apt. Gagal di sini hanya berarti menu
     * long-press tidak muncul; bridge tetap jalan (mod WebUI
     * adalah add-on, bukan prasyarat bridge).
     */
    private void ensureWebUiPatch() {
        try {
            File filesJs = new File(appContext.getFilesDir(), WEBUI_JS_ASSET);
            copyAsset(WEBUI_JS_ASSET, filesJs);
            core.copyFile(filesJs.getAbsolutePath(), CHROOT_ROOT + WEBUI_JS_CHROOT);
            File filesPatch = new File(appContext.getFilesDir(), WEBUI_PATCH_ASSET);
            copyAsset(WEBUI_PATCH_ASSET, filesPatch);
            core.copyFile(filesPatch.getAbsolutePath(), CHROOT_ROOT + WEBUI_PATCH_CHROOT);
            core.customChrootCommand("chmod +x " + WEBUI_PATCH_CHROOT
                    + "; sh " + WEBUI_PATCH_CHROOT, true);
        } catch (Throwable ignored) {
            // Best-effort: mod WebUI gagal != bridge gagal.
        }
    }

    /**
     * Start bridge (pola FridaManager: bunuh sisa lama, nohup + log).
     * Token TIDAK PERNAH masuk string perintah yang dicatat:
     * perintah ini jalan lewat customChrootCommand(..., true) =
     * nolog, dan env assignment tidak pernah ditulis ke log Astro.
     */
    private void startBridge(@NonNull String token) {
        core.customChrootCommand("pkill -f astro_kismet_tools_bridge.py; sleep 1", true);
        core.customChrootCommand("ASTRO_KISMET_TOOLS_TOKEN=" + token
                + " nohup python3 " + BRIDGE_CHROOT_PATH
                + " > /tmp/astro_kismet_tools_bridge.log 2>&1 & echo started", true);
    }

    // ---------------- cek hidup ----------------

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
            URL url = new URL("http://" + TOOLS_HOST + ":" + TOOLS_PORT + "/health");
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
