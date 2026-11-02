package id.hawx.astro.kismet;

import android.content.Context;

import androidx.annotation.NonNull;

import id.hawx.astro.utils.Core;
import id.hawx.astro.x11.WebUiLauncher;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Management Kismet (menu khusus, pola FridaManager Batch16/17).
 *
 * Mengelola lifecycle SATU komponen, ON-DEMAND saja:
 * kismet chroot (Debian Trixie, repo resmi kismetwireless.net),
 * sumber capture wlan0 langsung dalam mode monitor, web UI di
 * 127.0.0.1:2501 SAJA (tidak pernah 0.0.0.0).
 *
 * Fakta PoC perangkat (2026-11-02, JANGAN diragukan lagi):
 * - Capture HANYA jalan via wlan0 langsung dalam mode monitor
 *   (diset manual dulu). Kismet default membuat VIF (wlan0mon)
 *   dan itu GAGAL di driver qcacld user ("wlan0mon did not enter
 *   monitor mode"). Bukti: `iw dev wlan0 set type monitor` lalu
 *   log Kismet "wlan0 interface 'wlan0' is already in monitor
 *   mode" + "Data source 'wlan0' launched successfully".
 * - Kismet default bind 0.0.0.0:2501 (terbukti via ss). Bind
 *   localhost dipaksa lewat site override (lihat bawah).
 * - Kismet menulis .kismet relatif ke cwd (terbukti file
 *   `.//Kismet-...kismet` di /). Launch selalu `cd` dulu ke
 *   direktori log eksplisit /tmp/astro_kismet.
 * - EINVAL adalah race transisi driver, BUKAN perintah rusak:
 *   rangkaian cepat down->type->up bisa EINVAL; dengan jeda /
 *   retry operasi yang sama berhasil. Karena itu tarian monitor
 *   di sini memakai JEDA + RETRY + VERIFIKASI via `iw dev`
 *   (bukan percaya exit code).
 * - TANPA `ip link set ... up` SAMA SEKALI (keputusan user):
 *   UP dilakukan via toggle WiFi di Quick Settings (framework
 *   Android yang menaikkan + mengasosiasikan). Kismet menaikkan
 *   interface sendiri saat start (terbukti capture jalan).
 * - ifindex wlan0 berubah-ubah antar run (netdev dibuat ulang
 *   saat ganti tipe) — semua perintah merujuk NAMA (wlan0),
 *   tidak pernah cache ifindex.
 * - Peringatan ROOTUSER, plugin directory, NetworkManager =
 *   jinak, diabaikan.
 *
 * Bind localhost — KOREKSI terhadap dugaan awal (dokumentasi
 * upstream kismetwireless/kismet, conf/kismet_httpd.conf):
 * - Key yang benar adalah `http_bind_address` (BUKAN
 *   `httpd_bind_address`).
 * - File `~/.kismet/kismet_httpd.conf` adalah FILE AUTH
 *   (httpd_auth_file — tempat login digenerasi saat koneksi
 *   pertama), BUKAN override config — menulis baris config ke
 *   sana BERISIKO merusak flow login, jadi TIDAK dilakukan.
 * - Mekanisme override yang benar: `/etc/kismet/kismet_site.conf`
 *   (site override, dimuat terakhir, tidak ditimpa upgrade).
 *   Manager menulis `http_bind_address=127.0.0.1` ke sana
 *   (idempotent: baris lama dihapus dulu, lalu ditambah).
 * - Jaring pengaman: status() SELALU mengecek bind aktual via
 *   `ss` dan melaporkan bindUnsafe bila ada yang mendengarkan
 *   di alamat selain 127.0.0.1:2501. Bila key/file ini ternyata
 *   tidak berpengaruh di perangkat, panel akan menampilkan
 *   "TIDAK AMAN" secara jujur — bukan disembunyikan.
 * - Catatan login: Kismet menggenerasi login saat koneksi web
 *   pertama — pengguna membuat login itu di WebUI sendiri
 *   (bukan urusan manager, bukan token sesi seperti Frida).
 *
 * Aturan keras (cerminan Frida):
 * - Localhost-only; tidak ada flag/bind 0.0.0.0.
 * - On-demand + kill-after: tidak ada autostart, tidak ada
 *   daemon permanen. start() idempotent; stop() best-effort
 *   dan tidak pernah throw.
 * - stop() membunuh via PID file dulu; PENGECUALIAN DISETUJUI
 *   USER (2026-11-02, koreksi atas bug "Stop tidak mematikan
 *   server"): Stop eksplisit dari menu BOLEH membunuh kismet
 *   apa pun yang listen di 2501 (termasuk dari terminal) bila
 *   PID-file kill gagal / tidak ada PID — karena di HP user
 *   semua kismet = miliknya. Aturan Frida "jangan bunuh server
 *   asing" TIDAK berlaku untuk Kismet atas keputusan ini.
 *   Fallback ini dipicu HANYA oleh verifikasi port (masih
 *   listen sesudah PID kill), bukan membabi buta.
 * - VIF tidak pernah DIBUAT; VIF sisa (wlan0mon) hanya DIHAPUS.
 *
 * Semantik status yang jujur (cerminan Frida):
 * - kismetRunning = "ada yang listening di 2501", BUKAN
 *   "di-start panel ini". Server manual/asing tampil ON;
 *   stop() eksplisit akan membunuhnya juga lewat fallback
 *   verifikasi port (keputusan user 2026-11-02, lihat bawah).
 * - owned = PID file cocok dengan proses yang masih hidup.
 * - wifiType/wifiChannel dari `iw dev` (channel ada =
 *   terasosiasi; tanpa channel = belum naik / belum asosiasi).
 *
 * Threading: SEMUA method publik yang menyentuh shell/socket
 * HARUS dipanggil dari thread latar (bukan UI thread) — sama
 * seperti Core.customChrootCommand dan WebUiLauncher.isListening.
 * Satu pengecualian: closeHook() aman dipanggil dari UI thread
 * karena ia hanya melahirkan thread latar.
 */
public final class KismetManager {

    /** Kismet web UI: host TERKUNCI, port default Kismet. */
    public static final String KISMET_HOST = "127.0.0.1";
    public static final int KISMET_PORT = 2501;

    /** Interface capture (nama saja — ifindex berubah-ubah). */
    private static final String IFACE = "wlan0";
    /** VIF sisa Kismet — hanya DIHAPUS, tidak pernah dibuat. */
    private static final String STALE_VIF = "wlan0mon";
    /** Direktori log eksplisit di chroot (cwd saat launch). */
    private static final String LOG_DIR = "/tmp/astro_kismet";
    /** Site override Kismet (sistem, bukan auth file). */
    private static final String SITE_CONF = "/etc/kismet/kismet_site.conf";
    /** HOME eksplisit saat launch (auth/login Kismet di sini). */
    private static final String KISMET_HOME = "/root";

    private static final int POLL_TRIES = 30;
    private static final int POLL_MS = 1000;
    /** Jeda antar langkah tarian monitor (race transisi driver). */
    private static final int DANCE_SLEEP_MS = 1500;
    private static final int MAX_DANCE_TRIES = 3;

    /** Potret status — field final, dibaca call site apa adanya. */
    public static final class Status {
        /** Ada yang listening di 2501 (milik siapa pun). */
        public final boolean kismetRunning;
        /** PID file cocok dengan proses yang masih hidup. */
        public final boolean owned;
        /** Ada listener 2501 di alamat selain 127.0.0.1. */
        public final boolean bindUnsafe;
        /** Tipe wlan0 dari `iw dev` ("monitor"/"managed"/"?"). */
        public final String wifiType;
        /** Nomor channel dari `iw dev`, atau null bila tak ada. */
        public final String wifiChannel;

        Status(boolean kismetRunning, boolean owned, boolean bindUnsafe,
               String wifiType, String wifiChannel) {
            this.kismetRunning = kismetRunning;
            this.owned = owned;
            this.bindUnsafe = bindUnsafe;
            this.wifiType = wifiType;
            this.wifiChannel = wifiChannel;
        }
    }

    private final Core core;

    public KismetManager(@NonNull Context context) {
        this.core = new Core(context.getApplicationContext());
    }

    // ---------------- API utama ----------------

    /**
     * Start on-demand (idempotent). Bila sudah ada yang listening
     * di 2501, tidak di-start ulang (bisa jadi server asing).
     * Melempar IOException dengan pesan jelas bila monitor gagal
     * atau server tidak menjawab. WAJIB thread latar.
     *
     * Capture PID: `setsid` bisa fork bila sudah jadi group
     * leader, sehingga `$!` yang tercatat bisa jadi parent yang
     * sudah mati (bug perangkat 2026-11-02: Stop tidak mematikan
     * server karena isPidAlive false lalu kill dilewati). Karena
     * itu sesudah server menjawab, PID beneran diambil ulang via
     * `pgrep -f "kismet -c wlan[0]"` dan ditulis menimpa PID
     * file. Trik bracket `[0]` WAJIB: tanpa itu, cmdline shell
     * `sh -c "pgrep -f 'kismet -c wlan0'"` mengandung substring
     * pola dan ikut ter-match. PID pertama hasil pgrep = proses
     * utama kismet (helper capture bernama kismet_cap_linux_wifi,
     * cmdline-nya beda, tidak ikut match). Bila pgrep kosong,
     * `$!` dipertahankan sebagai fallback — start tidak digagalkan
     * hanya karena pgrep kosong.
     */
    public synchronized Status start() throws IOException {
        Status cur = status();
        if (cur.kismetRunning) {
            return cur;
        }
        // Bunuh sisa milik manager saja (PID file), jangan sentuh
        // server asing.
        Integer pid = readPid();
        if (pid != null && isPidAlive(pid)) {
            chroot("kill " + pid + "; sleep 1");
        }
        chroot("rm -f " + LOG_DIR + "/kismet.pid");
        // Bersihkan VIF sisa (hapus saja, tidak pernah buat baru).
        chroot("iw dev " + STALE_VIF + " del 2>/dev/null");
        // Tarian monitor: down -> jeda -> set monitor -> jeda ->
        // verifikasi via iw dev (bukan percaya exit code). Retry
        // sampai 3x karena race transisi driver (EINVAL jinak).
        boolean monitor = false;
        for (int attempt = 0; attempt < MAX_DANCE_TRIES && !monitor; attempt++) {
            chroot("ip link set " + IFACE + " down");
            sleepQuietly(DANCE_SLEEP_MS);
            chroot("iw dev " + IFACE + " set type monitor");
            sleepQuietly(DANCE_SLEEP_MS);
            monitor = "monitor".equals(readWifiState().type);
        }
        if (!monitor) {
            throw new IOException("wlan0 gagal masuk mode monitor sesudah "
                    + MAX_DANCE_TRIES + "x percobaan — nyalakan WiFi dari "
                    + "Quick Settings untuk kembali normal");
        }
        // Paksa bind localhost via site override (key benar per
        // dokumentasi upstream: http_bind_address).
        chroot("mkdir -p /etc/kismet && touch " + SITE_CONF
                + " && sed -i '/^http_bind_address=/d' " + SITE_CONF
                + " && printf 'http_bind_address=127.0.0.1\\n' >> " + SITE_CONF);
        // Launch detached (pola Frida: setsid + log + PID file),
        // cwd = direktori log eksplisit supaya .kismet tidak
        // mengotori /. Kismet menaikkan interface sendiri.
        chroot("mkdir -p " + LOG_DIR + " && cd " + LOG_DIR
                + " && HOME=" + KISMET_HOME + " setsid kismet -c " + IFACE
                + " > " + LOG_DIR + "/kismet.log 2>&1 < /dev/null & echo $! > "
                + LOG_DIR + "/kismet.pid");
        boolean up = waitListening(KISMET_PORT);
        if (!up) {
            throw new IOException("Kismet tidak menjawab di 127.0.0.1:"
                    + KISMET_PORT + " — cek log chroot: " + LOG_DIR + "/kismet.log");
        }
        // Perbaiki PID file: $! dari setsid bisa jadi parent mati
        // (fork). Ambil PID beneran via pgrep; bila kosong, $!
        // dipertahankan (jangan gagalkan start).
        try {
            Integer realPid = readKismetPid();
            if (realPid != null) {
                chroot("echo " + realPid + " > " + LOG_DIR + "/kismet.pid");
            }
        } catch (Throwable ignored) {
        }
        return status();
    }

    /**
     * Stop bersih, best-effort, TIDAK PERNAH throw. Urutan baru
     * (perbaikan bug perangkat 2026-11-02 — Stop/✕ hanya
     * merestore managed, server tetap jalan):
     * (a) kill via PID file (kill -> sleep 1 -> bila masih hidup
     * pkill -x kismet -> sleep 1 -> bila masih hidup kill -9);
     * (b) verifikasi port: bila 2501 masih listen (tidak ada PID
     * file, PID meleset, atau server asing), fallback
     * `pkill -x kismet` -> sleep 1 -> bila masih listen,
     * `pkill -9 -x kismet` -> sleep 1 -> verifikasi akhir
     * (hasil verifikasi tidak mengubah perilaku lain);
     * (c) BARU sesudah itu: rm PID file -> hapus VIF sisa ->
     * restore wlan0 ke managed (jeda + retry + verifikasi tipe).
     * Fallback (b) adalah keputusan user: Stop eksplisit dari
     * menu BOLEH membunuh kismet apa pun di 2501, karena di HP
     * user semua kismet = miliknya (aturan Frida "jangan bunuh
     * server asing" tidak berlaku untuk Kismet).
     * TIDAK PERNAH `ip link set ... up`: UP dilakukan pengguna
     * via toggle WiFi di Quick Settings (framework yang menaikkan
     * + mengasosiasikan). Restore tetap dieksekusi walau semua
     * kill gagal total. WAJIB thread latar.
     */
    public synchronized void stop() {
        try {
            Integer pid = readPid();
            if (pid != null && isPidAlive(pid)) {
                chroot("kill " + pid + "; sleep 1");
                if (isPidAlive(pid)) {
                    // Proses milik sendiri masih hidup sesudah
                    // SIGTERM: force-kill via nama (PID cocok).
                    chroot("pkill -x kismet; sleep 1");
                }
                if (isPidAlive(pid)) {
                    chroot("kill -9 " + pid + "; sleep 1");
                }
            }
        } catch (Throwable ignored) {
        }
        // Verifikasi port + fallback (perbaikan bug 2026-11-02):
        // bila 2501 masih listen — tidak ada PID file, PID meleset
        // (setsid fork), atau server asing — Stop eksplisit tetap
        // mematikan servernya (keputusan user). Best-effort.
        try {
            if (WebUiLauncher.isListening(KISMET_PORT)) {
                chroot("pkill -x kismet; sleep 1");
            }
            if (WebUiLauncher.isListening(KISMET_PORT)) {
                chroot("pkill -9 -x kismet; sleep 1");
            }
            // Verifikasi akhir (hasil sengaja tidak dipakai —
            // stop tetap best-effort, restore jalan apa pun).
            boolean stillRunning = WebUiLauncher.isListening(KISMET_PORT);
            if (stillRunning) {
                // Tidak ada aksi lanjutan: kill sudah maksimal
                // (SIGKILL via nama); restore tetap jalan bawah.
            }
        } catch (Throwable ignored) {
        }
        try {
            chroot("rm -f " + LOG_DIR + "/kismet.pid");
        } catch (Throwable ignored) {
        }
        try {
            chroot("iw dev " + STALE_VIF + " del 2>/dev/null");
        } catch (Throwable ignored) {
        }
        try {
            boolean managed = false;
            for (int attempt = 0; attempt < MAX_DANCE_TRIES && !managed; attempt++) {
                chroot("ip link set " + IFACE + " down");
                sleepQuietly(DANCE_SLEEP_MS);
                chroot("iw dev " + IFACE + " set type managed");
                sleepQuietly(DANCE_SLEEP_MS);
                managed = "managed".equals(readWifiState().type);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Status cepat (socket connect + ss + iw dev + PID). WAJIB thread latar. */
    public Status status() {
        boolean running = WebUiLauncher.isListening(KISMET_PORT);
        boolean unsafe = false;
        boolean owned = false;
        WifiState wifi = readWifiState();
        try {
            for (String line : chroot("ss -ltn")) {
                if (line.contains(":2501") && !line.contains("127.0.0.1:2501")) {
                    unsafe = true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Integer pid = readPid();
            owned = pid != null && isPidAlive(pid);
        } catch (Throwable ignored) {
        }
        return new Status(running, owned, unsafe, wifi.type, wifi.channel);
    }

    /**
     * Hook tutup untuk jendela Kismet: pass sebagai onClose ke
     * WebOverlayWindows.show(...) — ✕ jendela menghentikan
     * Kismet + merestore WiFi lewat thread latar (aman dipanggil
     * dari UI thread; pola WebUiLauncher.killServer). Keputusan
     * ini dicatat: pengguna akan mengoreksi setelah melihat
     * hasil bila maunya ✕ tidak menghentikan service.
     * Fire-and-forget.
     */
    public Runnable closeHook() {
        return () -> {
            Thread t = new Thread(() -> KismetManager.this.stop(), "kismet-stop");
            t.start();
        };
    }

    // ---------------- chroot ----------------

    /** Satu perintah chroot, nolog (pola Frida). */
    private ArrayList<String> chroot(String cmd) {
        return core.customChrootCommand(cmd, true);
    }

    /** PID dari file PID chroot, atau null bila tak ada / rusak. */
    private Integer readPid() {
        try {
            for (String line : chroot("cat " + LOG_DIR + "/kismet.pid")) {
                String pid = line.trim();
                if (pid.isEmpty()) continue;
                try {
                    return Integer.parseInt(pid);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * PID utama kismet via pgrep (pola bracket anti-self-match),
     * atau null bila tidak ditemukan. PID pertama = proses utama
     * (helper kismet_cap_linux_wifi cmdline-nya beda, tidak match).
     * Dipakai start() untuk memperbaiki PID file dari $! setsid
     * yang bisa jadi parent mati (fork).
     */
    private Integer readKismetPid() {
        try {
            for (String line : chroot("pgrep -f \"kismet -c wlan[0]\"")) {
                String pid = line.trim();
                if (pid.isEmpty()) continue;
                try {
                    return Integer.parseInt(pid);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** PID hidup? (cek /proc, bukan exit code kill). */
    private boolean isPidAlive(int pid) {
        try {
            for (String line : chroot("test -d /proc/" + pid + " && echo ALIVE")) {
                if (line.trim().equals("ALIVE")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Tipe + channel wlan0 dari `iw dev` (blok interface persis). */
    private static final class WifiState {
        String type = "?";
        String channel = null;
    }

    private WifiState readWifiState() {
        WifiState st = new WifiState();
        try {
            boolean inBlock = false;
            for (String raw : chroot("iw dev")) {
                String line = raw.trim();
                if (line.startsWith("Interface ")) {
                    // Cocok persis "Interface wlan0" (bukan wlan0mon).
                    inBlock = line.equals("Interface " + IFACE);
                    continue;
                }
                if (!inBlock) continue;
                if (line.startsWith("type ")) {
                    st.type = line.substring(5).trim();
                } else if (line.startsWith("channel ")) {
                    String rest = line.substring(8).trim();
                    int sp = rest.indexOf(' ');
                    st.channel = sp > 0 ? rest.substring(0, sp) : rest;
                }
            }
        } catch (Throwable ignored) {
        }
        return st;
    }

    // ---------------- cek hidup ----------------

    private boolean waitListening(int port) {
        for (int i = 0; i < POLL_TRIES; i++) {
            if (WebUiLauncher.isListening(port)) return true;
            sleepQuietly(POLL_MS);
        }
        return WebUiLauncher.isListening(port);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
