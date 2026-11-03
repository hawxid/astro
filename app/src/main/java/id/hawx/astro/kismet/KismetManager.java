package id.hawx.astro.kismet;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import id.hawx.astro.R;
import id.hawx.astro.bluetooth.BluebinderController;
import id.hawx.astro.gps.GpsdBridge;
import id.hawx.astro.utils.Core;
import id.hawx.astro.x11.WebUiLauncher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * Bind localhost — KOREKSI KEDUA terhadap dugaan awal
 * (dokumentasi upstream kismetwireless/kismet,
 * conf/kismet_httpd.conf + bukti perangkat 2026-11-02):
 * - Key yang benar adalah `httpd_bind_address` (DENGAN d,
 *   konsisten dengan semua opsi httpd_* lainnya: httpd_port,
 *   httpd_auth_file, httpd_session_db, dst).
 * - Key LAMA `http_bind_address` (tanpa d, dari PROSA upstream)
 *   SALAH dan DITINGGALKAN: Kismet 2026.10.R1 mengabaikannya
 *   (ss tetap 0.0.0.0:2501, log "HTTP server listening on
 *   0.0.0.0:2501"); prosa upstream typo, sementara baris
 *   contoh upstream sendiri `# httpd_bind_address=127.0.0.1`.
 * - Bukti perangkat: tes manual menambahkan
 *   `httpd_bind_address=127.0.0.1` ke site conf lalu restart
 *   via panel → panel tampil Bind 127.0.0.1 (hijau).
 * - File `~/.kismet/kismet_httpd.conf` adalah FILE AUTH
 *   (httpd_auth_file — tempat login digenerasi saat koneksi
 *   pertama), BUKAN override config — menulis baris config ke
 *   sana BERISIKO merusak flow login, jadi TIDAK dilakukan.
 * - Mekanisme override yang benar: `/etc/kismet/kismet_site.conf`
 *   (site override, dimuat terakhir, tidak ditimpa upgrade).
 *   Manager menulis `httpd_bind_address=127.0.0.1` ke sana
 *   (idempotent: KEDUA baris lama, `http_` maupun `httpd_`,
 *   dihapus dulu lewat sed, lalu ditambah satu baris benar —
 *   supaya tidak ada baris basi yang tertinggal).
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
 * - btActive = launch manager terakhir menyertakan BT DAN
 *   server yang jalan masih milik manager (owned) — server
 *   asing tidak pernah diklaim BT-aktif.
 *
 * Sumber Bluetooth (toggle terpisah, 2026-11-02):
 * - Toggle persist `kismet_bt` (default OFF = WiFi saja).
 *   ON = launch `kismet -c wlan0 -c hci0`; OFF = `kismet -c wlan0`.
 * - Bukti PoC perangkat: `kismet -c hci0` dengan bluebinder
 *   aktif → "Found type 'linuxbluetooth' for 'hci0'" +
 *   "Data source 'hci0' launched successfully" + perangkat BTLE
 *   D6:8C:A7:79:82:99 terdeteksi di WebUI. Auto-deteksi tipe —
 *   tanpa fallback type=.
 * - Prasyarat: bila toggle ON, start() memastikan bluebinder +
 *   hci0 UP dulu (BluebinderController.ensureActive, batas
 *   10 detik). Gagal = start GAGAL dengan pesan jelas, BUKAN
 *   fallback WiFi-only diam-diam.
 * - Stop OWNERSHIP-AWARE (koreksi user 2026-11-02 — "stopnya
 *   juga harus di improve"): stop mematikan bluebinder HANYA
 *   bila launch Kismet terakhir yang meng-auto-start-nya
 *   (field btBluebinderOwned); bila bluebinder sudah jalan
 *   sebelum start (manual/menu lain) = pre-existing, TIDAK
 *   disentuh. Alasan: bluebinder adalah service bersama (menu
 *   Bluetooth, Keys+Mouse) — membunuh tanpa pandang bulu
 *   merusak menu lain; membiarkan hasil auto-start jadi orphan
 *   juga tidak bersih. Prinsip sama seperti Frida: hanya bunuh
 *   instance yang manager-owned. Kill bluebinder best-effort
 *   (tidak pernah throw, tidak menghambat restore WiFi).
 *
 * Sumber GPS (toggle terpisah, 2026-11-02 — keputusan user:
 * toggle, BUKAN selalu aktif; default ON "idealnya gps on"):
 * - Toggle persist `kismet_gps` (default ON). ON = Start
 *   membangun rantai GPS PENUH sebelum wlan0 disentuh:
 *   publisher NMEA Android (NmeaPublisher: kalimat mentah
 *   hardware GPS -> UDP 127.0.0.1:10110) + gpsd chroot atas
 *   feed udp:// dokumenter (gpsd(8)) + baris gps= di site
 *   conf. OFF = baris gps= DIHAPUS (Kismet tidak mencoba
 *   gpsd) dan tidak ada publisher/gpsd yang di-start.
 * - BUKTI PoC perangkat: flag --use-gpsd-gps TIDAK
 *   mengaktifkan GPS di Kismet 2026.10 (help tidak menyebut
 *   gps, ss tanpa ESTAB, log tanpa baris gpsd). Yang
 *   mengaktifkan adalah definisi sumber di config:
 *   `gps=gpsd:host=localhost,port=2947` di site conf ->
 *   log "(GPS) Connected to gpsd server ::1:2947" + JSON
 *   mode + WebUI "GPS locked (3d)". Launch TETAP polos
 *   `kismet -c wlan0` (+ `-c hci0` bila BT ON).
 * - Fail-fast seperti BT: GPS ON tapi rantai gagal (izin
 *   lokasi belum diberikan / gpsd tidak ada / 2947 sudah
 *   dipakai gpsd asing / gpsd tidak menjawab) = start GAGAL
 *   dengan pesan jelas, TANPA fallback diam-diam ke capture
 *   tanpa GPS. Pengguna bisa toggle GPS OFF lalu Start lagi.
 *   gpsd asing TIDAK PERNAH dibunuh (tidak ada keputusan
 *   user seperti fallback kismet di 2501).
 * - Stop OWNERSHIP-AWARE untuk gpsd (pola bluebinder):
 *   publisher SELALU dihentikan (hanya manager yang
 *   men-start-nya); gpsd HANYA bila launch terakhir yang
 *   men-start-nya (field gpsGpsdOwned, volatile, default
 *   jangan-bunuh bila proses app mati). Kill gpsd via PID
 *   file SAJA (bukan pkill nama) supaya gpsd asing tidak
 *   pernah kena.
 *
 * Importer Kismet -> Atlas (lihat KismetImporter):
 * - Tombol import manual + toggle "auto import saat Stop"
 *   (persist `kismet_import_auto`, default OFF). Auto-import
 *   ON = Stop juga mengimpor sesudah server berhenti;
 *   OFF = Stop tidak pernah mengimpor. Toggle auto-import
 *   BOLEH diubah saat server ON (mengatur perilaku Stop,
 *   bukan Start); toggle GPS TIDAK (ganti sumber butuh
 *   restart, sama seperti BT).
 * - Import membaca .kismet terbaru di /tmp/astro_kismet dan
 *   menulis langsung ke GeoStore (tanpa UI pencarian).
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
    /** Interface sumber Bluetooth (via bluebinder, VHCI). */
    private static final String BT_IFACE = "hci0";
    /** Pref toggle Bluetooth (persist; default OFF = WiFi saja). */
    private static final String PREF_BT = "kismet_bt";
    /** Pref penanda: launch manager terakhir menyertakan BT. */
    private static final String PREF_BT_LAUNCHED = "kismet_bt_launched";
    /** Batas tunggu ensure bluebinder (detik). */
    private static final int BT_ENSURE_TIMEOUT_S = 10;
    /** Pref toggle GPS (persist; default ON = "idealnya gps on").
     *  Core.getBoolean default false, jadi baca default-ON lewat
     *  contains (belum pernah disimpan = ON). */
    private static final String PREF_GPS = "kismet_gps";
    /** Pref penanda: launch manager terakhir menyertakan GPS. */
    private static final String PREF_GPS_LAUNCHED = "kismet_gps_launched";
    /** Pref toggle auto-import saat Stop (persist; default OFF). */
    private static final String PREF_IMPORT_AUTO = "kismet_import_auto";
    /** Port gpsd (TCP, klien Kismet + cek hidup). */
    private static final int GPSD_PORT = 2947;
    /** Host loopback untuk feed UDP publisher -> gpsd. */
    private static final String GPSD_UDP_HOST = "127.0.0.1";
    /** Port UDP publisher NetHunter (10110). */
    private static final int GPSD_UDP_PORT = 10110;
    /** PID file gpsd (ditulis gpsd sendiri via -P). */
    private static final String GPSD_PID = LOG_DIR + "/gpsd.pid";
    /** Log launch gpsd (error awal saja; daemon lepas sesudah fork). */
    private static final String GPSD_LOG = LOG_DIR + "/gpsd.log";
    /** Baris sumber GPS di site conf (bukti PoC — lihat atas). */
    private static final String GPS_CONF_LINE = "gps=gpsd:host=localhost,port=2947";
    /** Batas tunggu gpsd menjawab di 2947 (detik). */
    private static final int GPSD_POLL_TRIES = 10;

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
        /** Server ini diluncurkan manager DENGAN sumber BT
         *  (bukan klaim untuk server asing — lihat status()). */
        public final boolean btActive;
        /** Server ini diluncurkan manager DENGAN rantai GPS DAN
         *  gpsd masih menjawab di 2947 (bukan klaim untuk
         *  server asing — lihat status()). */
        public final boolean gpsActive;

        Status(boolean kismetRunning, boolean owned, boolean bindUnsafe,
               String wifiType, String wifiChannel, boolean btActive,
               boolean gpsActive) {
            this.kismetRunning = kismetRunning;
            this.owned = owned;
            this.bindUnsafe = bindUnsafe;
            this.wifiType = wifiType;
            this.wifiChannel = wifiChannel;
            this.btActive = btActive;
            this.gpsActive = gpsActive;
        }
    }

    private final Core core;
    private final Context appContext;
    /** Pesan gagal BT, di-resolve sekali di konstruktor (UI thread)
     *  supaya thread latar tidak pernah memanggil getString(). */
    private final String btFailedText;
    /** Pesan gagal GPS, di-resolve sekali di konstruktor (pola sama). */
    private final String gpsPermText;
    private final String gpsBusyText;
    private final String gpsNoAnswerText;
    private final String gpsNoGpsdText;
    private final String gpsFailedText;
    /** Ownership bluebinder untuk Stop (lihat start()/stop()):
     *  true HANYA bila launch Kismet terakhir dengan BT ON yang
     *  meng-auto-start bluebinder (hci0 belum UP sebelum ensure).
     *  Bila bluebinder sudah jalan sebelum start (manual/menu
     *  lain) = pre-existing, false — stop TIDAK menyentuhnya.
     *  Field instance (bukan pref) dengan sengaja: bila proses
     *  app mati, nilainya hilang -> default false (aman: jangan
     *  bunuh); pref yang basi justru berbahaya (bisa membunuh
     *  bluebinder milik menu lain yang di-start belakangan).
     *  Di-clear di akhir stop() apa pun hasilnya. */
    private boolean btBluebinderOwned = false;
    /** Ownership gpsd untuk Stop (pola btBluebinderOwned):
     *  true HANYA bila launch Kismet terakhir dengan GPS ON
     *  yang men-start gpsd (2947 kosong sebelum start).
     *  Field instance dengan sengaja: proses app mati ->
     *  false (aman: jangan bunuh); gpsd pre-existing/asing
     *  tidak pernah disentuh. Di-clear di akhir stop(). */
    private boolean gpsGpsdOwned = false;
    /** Publisher NMEA milik launch ini (null bila GPS OFF /
     *  belum start / sudah stop). Selalu dihentikan di stop(). */
    private NmeaPublisher publisher;

    public KismetManager(@NonNull Context context) {
        this.core = new Core(context.getApplicationContext());
        this.appContext = context.getApplicationContext();
        this.btFailedText = context.getString(R.string.kismet_bt_failed);
        this.gpsPermText = context.getString(R.string.kismet_gps_perm);
        this.gpsBusyText = context.getString(R.string.kismet_gps_busy);
        this.gpsNoAnswerText = context.getString(R.string.kismet_gps_noanswer);
        this.gpsNoGpsdText = context.getString(R.string.kismet_gps_nogpsd);
        this.gpsFailedText = context.getString(R.string.kismet_gps_failed);
    }

    /** Toggle Bluetooth (persist). Default false = WiFi saja. */
    public boolean isBtEnabled() {
        return core.getBoolean(PREF_BT);
    }

    /** Simpan toggle Bluetooth (apply; aman dari UI thread). */
    public void setBtEnabled(boolean enabled) {
        core.putBoolean(PREF_BT, enabled);
    }

    /** Toggle GPS (persist). Default true = "idealnya gps on":
     *  belum pernah disimpan = ON; sesudah disimpan = nilai itu. */
    public boolean isGpsEnabled() {
        return !core.contains(PREF_GPS) || core.getBoolean(PREF_GPS);
    }

    /** Simpan toggle GPS (apply; aman dari UI thread). */
    public void setGpsEnabled(boolean enabled) {
        core.putBoolean(PREF_GPS, enabled);
    }

    /** Toggle auto-import saat Stop (persist). Default false. */
    public boolean isAutoImportEnabled() {
        return core.getBoolean(PREF_IMPORT_AUTO);
    }

    /** Simpan toggle auto-import (apply; aman dari UI thread). */
    public void setAutoImportEnabled(boolean enabled) {
        core.putBoolean(PREF_IMPORT_AUTO, enabled);
    }

    /**
     * Import .kismet terbaru ke pin Atlas (lihat KismetImporter).
     * Sinkron, WAJIB thread latar (lookup jaringan + shell chroot).
     * Tidak pernah throw ke UI: gagal dikembalikan lewat error.
     */
    public KismetImporter.Result importNewest() {
        return new KismetImporter(appContext).importNewest();
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
        // Prasyarat BT DULU, sebelum wlan0 disentuh: bila toggle
        // ON tapi bluebinder/hci0 tidak bisa diaktifkan, start
        // GAGAL dengan pesan jelas — TIDAK ada fallback diam-diam
        // ke WiFi-only (pengguna eksplisit minta BT).
        boolean btOn = isBtEnabled();
        // Ownership bluebinder dicatat SEBELUM ensure: hci0 UP
        // sebelum ensure = pre-existing (manual/menu lain, bukan
        // milik Kismet); belum UP lalu ensure berhasil = milik
        // Kismet (auto-start). Reset dulu supaya launch BT OFF
        // maupun ensure yang gagal tidak mewarisi nilai lama.
        // Bila BT OFF, field tidak relevan (false).
        btBluebinderOwned = false;
        if (btOn) {
            boolean hciWasUp = isHciUpBeforeBt();
            ensureBtActive();
            btBluebinderOwned = !hciWasUp;
        }
        // Prasyarat GPS, posisi yang sama (sebelum wlan0 disentuh):
        // GPS ON = bangun rantai penuh (publisher -> gpsd -> baris
        // gps=). Gagal = start GAGAL, tanpa fallback diam-diam.
        // Bila gagal sesudah BT di-auto-start, bluebinder yang
        // baru di-start di-rollback (jangan orphan) lalu gagal
        // diteruskan — urutan ini membuat kegagalan prasyarat
        // mana pun tidak meninggalkan rantai setengah jalan.
        // GPS OFF = hapus baris gps= (termasuk baris PoC manual
        // yang sudah ada — writer ini mengambil alih) supaya
        // Kismet tidak mencoba gpsd; tidak ada publisher/gpsd
        // yang di-start. Baris httpd_ tidak disentuh di sini
        // (pola sed terpisah; writer bind di bawah menjamin
        // satu baris httpd_).
        boolean gpsOn = isGpsEnabled();
        gpsGpsdOwned = false;
        if (gpsOn) {
            try {
                ensureGpsChain();
            } catch (IOException e) {
                // Gagal GPS sesudah BT di-auto-start: rollback
                // prereq (jangan orphan) lalu gagal diteruskan.
                rollbackPrereqs();
                throw e;
            }
        } else {
            chroot("mkdir -p /etc/kismet && touch " + SITE_CONF
                    + " && sed -i '/^gps=/d' " + SITE_CONF);
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
            // Gagal masuk monitor sesudah prereq berhasil: rollback
            // BT+GPS (jangan orphan — tanpa ini, Start berikutnya
            // dengan GPS ON langsung gagal "2947 busy" karena
            // gpsd orphan masih listen).
            rollbackPrereqs();
            throw new IOException("wlan0 gagal masuk mode monitor sesudah "
                    + MAX_DANCE_TRIES + "x percobaan — nyalakan WiFi dari "
                    + "Quick Settings untuk kembali normal");
        }
        // Paksa bind localhost via site override. Key benar
        // (bukti perangkat 2026-11-02): httpd_bind_address —
        // http_bind_address (tanpa d) diabaikan Kismet. Sed
        // menghapus KEDUA baris lama supaya site conf hanya
        // berisi satu baris bind yang benar, tanpa sisa basi.
        chroot("mkdir -p /etc/kismet && touch " + SITE_CONF
                + " && sed -i '/^http_bind_address=/d;/^httpd_bind_address=/d' " + SITE_CONF
                + " && printf 'httpd_bind_address=127.0.0.1\\n' >> " + SITE_CONF);
        // Launch detached (pola Frida: setsid + log + PID file),
        // cwd = direktori log eksplisit supaya .kismet tidak
        // mengotori /. Kismet menaikkan interface sendiri.
        // Sumber capture: WiFi saja, atau WiFi + BT (toggle ON).
        // BT = `-c hci0` (auto-deteksi linuxbluetooth, bukti PoC
        // perangkat 2026-11-02 — tanpa fallback type=).
        String sourceArgs = "-c " + IFACE + (btOn ? " -c " + BT_IFACE : "");
        chroot("mkdir -p " + LOG_DIR + " && cd " + LOG_DIR
                + " && HOME=" + KISMET_HOME + " setsid kismet " + sourceArgs
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
        // Catat launch ini menyertakan BT atau tidak (persist —
        // Fragment bisa dibuat ulang / proses app mati sementara
        // server detached tetap jalan; field volatile akan hilang
        // dalam kedua kasus itu). status() mengAND-kan dengan
        // owned, jadi server asing TIDAK PERNAH diklaim BT-aktif.
        try {
            core.putBoolean(PREF_BT_LAUNCHED, btOn);
        } catch (Throwable ignored) {
        }
        // Penanda launch GPS (pola sama: persist, diAND-kan
        // dengan owned di status() supaya server asing tidak
        // pernah diklaim GPS-aktif).
        try {
            core.putBoolean(PREF_GPS_LAUNCHED, gpsOn);
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
     * kill gagal total. Stop OWNERSHIP-AWARE untuk bluebinder:
     * HANYA dibunuh (`pkill -f bluebinder`, best-effort) bila
     * launch terakhir yang meng-auto-start-nya (field
     * btBluebinderOwned); bila pre-existing, TIDAK disentuh
     * sama sekali (service bersama menu Bluetooth/Keys+Mouse —
     * prinsip Frida: hanya bunuh instance manager-owned).
     * Kill bluebinder tidak pernah throw dan tidak menghambat
     * restore WiFi. WAJIB thread latar.
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
        // Kill bluebinder OWNERSHIP-AWARE (koreksi user
        // 2026-11-02): HANYA bila launch Kismet terakhir yang
        // meng-auto-start-nya. Pre-existing (manual/menu lain)
        // TIDAK disentuh sama sekali. Best-effort: blok try
        // sendiri, tidak pernah throw, tidak menghambat restore
        // WiFi di bawah (pola yang sama dengan kill kismet).
        // Perintah sama dengan stop() BluebinderController.
        try {
            if (btBluebinderOwned) {
                chroot("pkill -f bluebinder");
            }
        } catch (Throwable ignored) {
        }
        // Hentikan rantai GPS OWNERSHIP-AWARE (pola bluebinder):
        // publisher SELALU dihentikan (hanya manager yang
        // men-start-nya); gpsd HANYA bila launch terakhir yang
        // men-start-nya (gpsGpsdOwned). gpsd pre-existing/asing
        // TIDAK disentuh — dan TIDAK ADA fallback pkill -x gpsd
        // (tidak ada keputusan user untuk membunuh gpsd asing,
        // beda dengan kismet di 2501). Kill gpsd via PID file
        // SAJA: SIGTERM -> sleep 1 -> SIGKILL via PID bila
        // masih hidup. Best-effort, tidak pernah throw, tidak
        // menghambat restore WiFi di bawah.
        try {
            if (publisher != null) {
                publisher.stop();
                publisher = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (gpsGpsdOwned) {
                Integer gpsdPid = readGpsdPid();
                if (gpsdPid != null && isPidAlive(gpsdPid)) {
                    chroot("kill " + gpsdPid + "; sleep 1");
                }
                if (gpsdPid != null && isPidAlive(gpsdPid)) {
                    chroot("kill -9 " + gpsdPid + "; sleep 1");
                }
                chroot("rm -f " + GPSD_PID);
            }
        } catch (Throwable ignored) {
        }
        try {
            chroot("rm -f " + LOG_DIR + "/kismet.pid");
            core.putBoolean(PREF_BT_LAUNCHED, false);
            core.putBoolean(PREF_GPS_LAUNCHED, false);
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
        // Bersihkan penanda launch di akhir stop, apa pun yang
        // terjadi di blok best-effort di atas (blok di atas bisa
        // terpotong bila chroot throw di tengah) — stop
        // berikutnya tidak mewarisi klaim BT/ownership launch
        // sebelumnya. Penugasan field tidak bisa throw.
        try {
            core.putBoolean(PREF_BT_LAUNCHED, false);
            core.putBoolean(PREF_GPS_LAUNCHED, false);
        } catch (Throwable ignored) {
        }
        btBluebinderOwned = false;
        gpsGpsdOwned = false;
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
        // BT-aktif HANYA bila launch manager terakhir menyertakan
        // BT DAN server yang jalan masih milik manager (owned).
        // Server asing (terminal) selalu btActive=false — tidak
        // pernah ada klaim BT tanpa bukti launch.
        boolean btActive = owned && core.getBoolean(PREF_BT_LAUNCHED);
        // GPS-aktif: pola yang sama (launch + owned) DITAMBAH
        // bukti hidup rantai — gpsd masih menjawab di 2947.
        // Cek 2947 tidak bergantung proses app (gpsd detached
        // selamat dari restart app), jadi marker persist tetap
        // bermakna sesudah fragment dibuat ulang. Satu catatan
        // jujur: publisher Android tidak bisa dicek sesudah
        // proses app mati (ikut mati bersama proses) — bila
        // itu terjadi, gpsActive bisa tampil true sementara
        // NMEA sudah berhenti; Kismet sendiri akan kehilangan
        // fix (bukan menampilkan fix basi sebagai aktif).
        boolean gpsActive = owned && core.getBoolean(PREF_GPS_LAUNCHED)
                && WebUiLauncher.isListening(GPSD_PORT);
        return new Status(running, owned, unsafe, wifi.type, wifi.channel, btActive, gpsActive);
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
     * hci0 UP atau tidak SEBELUM ensure (untuk ownership Stop).
     * Memakai BluebinderController.checkStatus (satu-satunya
     * sumber kebenaran bluebinder) + latch, pola yang sama
     * seperti ensureBtActive. Default bila tak diketahui
     * (timeout/interrupt) = TRUE (pre-existing) — fail-safe ke
     * arah TIDAK membunuh: orphan lebih aman daripada membunuh
     * bluebinder milik menu lain. WAJIB thread latar (callback
     * dipost ke main thread; deadlock bila dari UI thread).
     */
    private boolean isHciUpBeforeBt() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean up = new AtomicBoolean(true);
        new BluebinderController(appContext).checkStatus((running, hciUp, detail) -> {
            up.set(hciUp);
            latch.countDown();
        });
        try {
            latch.await(BT_ENSURE_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return up.get();
    }

    /**
     * Pastikan bluebinder + hci0 UP sebelum start dengan BT ON.
     * Memakai BluebinderController.ensureActive (satu-satunya
     * sumber kebenaran bluebinder, sama seperti menu Bluetooth):
     * start bila perlu (`nohup bluebinder > /tmp/bluebinder.log
     * 2>&1 &`) + `hciconfig hci0 up` + verifikasi running &&
     * hciUp. Callback-nya dipost ke main thread, jadi start()
     * (thread latar) menunggu lewat latch — start() TIDAK PERNAH
     * boleh dipanggil dari UI thread (deadlock bila begitu).
     * Gagal/timeout (>10 detik) = IOException dengan pesan jelas;
     * TIDAK ADA fallback WiFi-only.
     */
    private void ensureBtActive() throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        AtomicReference<String> detail = new AtomicReference<>("");
        new BluebinderController(appContext).ensureActive((okCb, detailCb) -> {
            ok.set(okCb);
            detail.set(detailCb);
            latch.countDown();
        });
        boolean done;
        try {
            done = latch.await(BT_ENSURE_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(btFailedText);
        }
        if (!done) {
            throw new IOException(btFailedText + " (timeout " + BT_ENSURE_TIMEOUT_S + " detik)");
        }
        if (!ok.get()) {
            String d = detail.get();
            throw new IOException(btFailedText + (d != null && !d.isEmpty() ? " (" + d + ")" : ""));
        }
    }

    /**
     * PID gpsd dari PID file -P, atau null bila tak ada / rusak.
     * (Pola readPid, untuk file gpsd terpisah.)
     */
    private Integer readGpsdPid() {
        try {
            for (String line : chroot("cat " + GPSD_PID)) {
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
     * Bangun rantai GPS PENUH sebelum wlan0 disentuh (dipanggil
     * start() bila toggle GPS ON). Urutan: izin lokasi -> gpsd
     * ada di chroot -> 2947 kosong -> publisher Android ->
     * gpsd atas feed udp:// -> baris gps= di site conf.
     * Gagal di langkah mana pun = IOException dengan pesan
     * jelas; TIDAK ADA fallback diam-diam. Bila gagal sesudah
     * publisher di-start, publisher dimatikan dulu (jangan
     * orphan) sebelum gagal diteruskan.
     *
     * Feed udp://: gpsd(8) mendokumentasikannya sebagai sumber
     * first-class ("UDP feed ... The daemon will open a socket
     * listening for UDP datagrams ... interpreted as though
     * they had been issued by a serial device. Example:
     * udp://127.0.0.1:5000") — persis pola NetHunter
     * (Android GPS Publisher -> UDP 10110 -> gpsd). Karena itu
     * tidak dipakai feeder PTY: PTY adalah fallback yang
     * disiapkan untuk gpsfake, sementara udp:// menghilangkan
     * satu proses + satu pasang PTY dari rantai.
     * gpsd di-start dengan -P (PID file sendiri) karena gpsd
     * me-daemonize (fork) — $! setsid tidak bisa dipercaya
     * (pelajaran yang sama dengan bug PID Kismet). Verifikasi
     * hidup = 2947 LISTEN (2947 kosong sebelum start, jadi
     * LISTEN sesudahnya = gpsd kita). gpsd default hanya
     * listen loopback (butuh -G untuk 0.0.0.0) — localhost-only
     * terjaga tanpa flag tambahan.
     */
    private void ensureGpsChain() throws IOException {
        // 1. Izin lokasi: gerbang keras, jangan lanjut diam-diam.
        if (ContextCompat.checkSelfPermission(appContext,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new IOException(gpsPermText);
        }
        // 2. gpsd harus terinstal di chroot (PoC: apt install
        // gpsd gpsd-clients). Bila tidak ada, gagal jelas —
        // jangan start publisher sia-sia.
        boolean hasGpsd = false;
        try {
            for (String line : chroot("command -v gpsd")) {
                if (line != null && !line.trim().isEmpty()) {
                    hasGpsd = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!hasGpsd) {
            throw new IOException(gpsNoGpsdText);
        }
        // 3. 2947 harus kosong — ATAU diisi gpsd milik Astro yang
        // bisa dipakai ulang (batch gpsd-bridge): kartu menu GPS
        // atau launch panel sebelumnya. gpsd ASING tetap TIDAK
        // PERNAH dibunuh: gagal jelas, pengguna menghentikan
        // gpsd itu sendiri (terminal: pkill -x gpsd, atau tombol
        // Stop kartu menu GPS) lalu Start lagi.
        // Catatan PoC: paket Debian gpsd AUTO-START daemon tanpa
        // device saat install — daemon itulah yang paling
        // mungkin mengisi 2947 di sini.
        // Reuse: gpsd yang ada dipakai apa adanya (tidak start
        // kedua). Publisher: bila publisher menu masih hidup di
        // proses ini, ia sudah memberi makan gpsd — panel tidak
        // start publisher lagi. Bila gpsd orphan (publisher mati
        // bersama proses lama), panel start publisher sendiri
        // untuk memberi makan gpsd yang ada; gpsd tetap BUKAN
        // milik panel (gpsGpsdOwned false) sehingga Stop panel
        // tidak membunuhnya — ia kembali jadi orphan yang bisa
        // di-Stop dari kartu menu GPS.
        boolean reuseGpsd = false;
        if (WebUiLauncher.isListening(GPSD_PORT)) {
            if (!GpsdBridge.isOurGpsd(appContext)) {
                throw new IOException(gpsBusyText);
            }
            reuseGpsd = true;
        }
        // 4. Publisher Android (sumber NMEA) dulu, baru gpsd —
        // supaya tidak ada jeda tanpa sumber sesudah gpsd up.
        // Jalur reuse: publisher hanya di-start bila tidak ada
        // publisher menu yang hidup (orphan butuh feeder baru);
        // gpsd tidak di-start ulang (langkah 5 dilewati).
        if (!reuseGpsd || !GpsdBridge.isMenuFeeding()) {
            NmeaPublisher pub = new NmeaPublisher(appContext);
            try {
                pub.start();
            } catch (IOException e) {
                String m = e.getMessage();
                throw new IOException(m != null ? m : gpsFailedText);
            }
            publisher = pub;
        }
        if (!reuseGpsd) {
            // 5. gpsd atas feed UDP. Daemonize sendiri; -P menulis
            // PID daemon (bukan parent setsid).
            chroot("mkdir -p " + LOG_DIR + " && setsid gpsd -P " + GPSD_PID
                    + " udp://" + GPSD_UDP_HOST + ":" + GPSD_UDP_PORT
                    + " > " + GPSD_LOG + " 2>&1 < /dev/null &");
            boolean gpsdUp = false;
            for (int i = 0; i < GPSD_POLL_TRIES && !gpsdUp; i++) {
                sleepQuietly(POLL_MS);
                gpsdUp = WebUiLauncher.isListening(GPSD_PORT);
            }
            if (!gpsdUp) {
                try {
                    publisher.stop();
                } catch (Throwable ignored) {
                }
                publisher = null;
                throw new IOException(gpsNoAnswerText);
            }
            gpsGpsdOwned = true;
        }
        // 6. Baris gps= ke site conf (bukti PoC — flag
        // --use-gpsd-gps TIDAK mengaktifkan GPS di 2026.10).
        // Sed menghapus baris gps= lama dulu (idempotent, dan
        // mengambil alih baris PoC manual). Baris httpd_ TIDAK
        // disentuh (pola sed terpisah; writer bind di start()
        // menjamin satu baris httpd_).
        chroot("mkdir -p /etc/kismet && touch " + SITE_CONF
                + " && sed -i '/^gps=/d' " + SITE_CONF
                + " && printf '" + GPS_CONF_LINE + "\\n' >> " + SITE_CONF);
    }

    /**
     * Rollback prereq BT+GPS best-effort (dipakai bila start()
     * gagal sesudah salah satu prereq berhasil — jangan orphan).
     * Tidak pernah throw; tidak menyentuh PID file Kismet,
     * penanda launch, maupun restore WiFi (itu urusan stop()).
     * Duplikasi kill yang disengaja dengan stop(): stop() punya
     * urutan + penanda sendiri yang sudah terbukti — helper
     * ini hanya untuk jalur gagal start.
     */
    private void rollbackPrereqs() {
        try {
            if (publisher != null) {
                publisher.stop();
                publisher = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (gpsGpsdOwned) {
                Integer gpsdPid = readGpsdPid();
                if (gpsdPid != null && isPidAlive(gpsdPid)) {
                    chroot("kill " + gpsdPid + "; sleep 1");
                }
                if (gpsdPid != null && isPidAlive(gpsdPid)) {
                    chroot("kill -9 " + gpsdPid + "; sleep 1");
                }
                chroot("rm -f " + GPSD_PID);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (btBluebinderOwned) {
                chroot("pkill -f bluebinder");
            }
        } catch (Throwable ignored) {
        }
        btBluebinderOwned = false;
        gpsGpsdOwned = false;
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
