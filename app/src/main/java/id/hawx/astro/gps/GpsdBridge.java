package id.hawx.astro.gps;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import id.hawx.astro.R;
import id.hawx.astro.kismet.NmeaPublisher;
import id.hawx.astro.utils.Core;
import id.hawx.astro.x11.WebUiLauncher;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Jembatan gpsd bersama untuk kartu menu GPS + panel Kismet
 * (batch gpsd-bridge, 2026-11-03 — permintaan Hawx: gpsd kadang
 * kelupaan dan nyangkut tanpa ada yang bisa menghentikannya).
 *
 * Satu gpsd, dipakai bersama — bukan dua starter yang berantem.
 * Bukti kepemilikan = PID file gpsd sendiri
 * (/tmp/astro_kismet/gpsd.pid, ditulis gpsd via -P), BUKAN pref:
 * pref kehilangan makna sesudah proses app mati, sementara PID
 * file + daemon gpsd selamat dari restart app. Karena itu:
 * - gpsd yang menjawab di 2947 + PID file cocok dengan proses
 *   gpsd yang hidup = MILIK ASTRO (menu/panel/orphan).
 * - gpsd yang menjawab tanpa PID file yang cocok = ASING —
 *   terlihat saja, TIDAK PERNAH dibunuh.
 * - Publisher menu dipegang statis di sini (hidup melampaui
 *   fragment); publisher panel dipegang KismetManager sendiri.
 *   Keduanya tidak pernah saling menghentikan.
 *
 * Klasifikasi (dipakai kartu menu + reuse panel):
 * - STOPPED: 2947 tidak listen.
 * - MENU: gpsd jalan + publisher menu hidup di proses ini.
 * - MENU_SHARED: gpsd menu + Kismet sedang jalan (meminjam).
 * - PANEL: gpsd milik Astro + Kismet jalan (bukan menu).
 * - ORPHAN: gpsd milik Astro + Kismet tidak jalan + bukan menu
 *   (inilah yang "nyangkut": app/panel mati, daemon tertinggal).
 * - FOREIGN: 2947 listen tanpa bukti PID milik Astro.
 *
 * Aturan Stop menu: hanya MENU dan ORPHAN yang boleh dibunuh
 * (ORPHAN hanya bila Kismet tidak jalan — klasifikasi sudah
 * menjamin itu). PANEL/MENU_SHARED/FOREIGN: tombol Stop
 * nonaktif dengan penjelasan. Bunuh selalu verifikasi cmdline
 * mengandung "gpsd" dulu (lawan PID reuse basi).
 *
 * SEMUA method di sini WAJIB thread latar (chroot su + socket).
 */
public final class GpsdBridge {

    private GpsdBridge() {
    }

    /** Port gpsd (TCP, sama dengan rantai panel). */
    public static final int GPSD_PORT = 2947;
    /** Port Kismet (dipakai untuk "Kismet sedang jalan?"). */
    public static final int KISMET_PORT = 2501;
    /** Feed UDP publisher -> gpsd (sama dengan rantai panel). */
    private static final String UDP_HOST = "127.0.0.1";
    private static final int UDP_PORT = 10110;
    /** Direktori log + PID file (sama dengan rantai panel). */
    private static final String LOG_DIR = "/tmp/astro_kismet";
    private static final String GPSD_PID = LOG_DIR + "/gpsd.pid";
    private static final String GPSD_LOG = LOG_DIR + "/gpsd.log";
    /** Batas tunggu gpsd menjawab sesudah start (x 1 detik). */
    private static final int POLL_TRIES = 10;
    private static final int POLL_MS = 1000;

    /** Publisher milik menu (null bila menu tidak men-start). */
    private static NmeaPublisher menuPublisher;
    /** True selama menu yang men-start gpsd di proses ini. */
    private static volatile boolean menuLive;

    public enum State {
        STOPPED, MENU, MENU_SHARED, PANEL, ORPHAN, FOREIGN
    }

    /** True bila state boleh di-Stop dari kartu menu. */
    public static boolean isStoppable(State s) {
        return s == State.MENU || s == State.ORPHAN;
    }

    /** True bila publisher menu sedang memberi makan gpsd. */
    public static boolean isMenuFeeding() {
        return menuLive && menuPublisher != null;
    }

    private static ArrayList<String> chroot(Context ctx, String cmd) {
        return new Core(ctx.getApplicationContext())
                .customChrootCommand(cmd, true);
    }

    private static boolean isListening(int port) {
        return WebUiLauncher.isListening(port);
    }

    private static boolean isPidAlive(Context ctx, int pid) {
        try {
            for (String line : chroot(ctx, "test -d /proc/" + pid + " && echo ALIVE")) {
                if (line.trim().equals("ALIVE")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** PID gpsd dari PID file, atau null bila tak ada / rusak. */
    private static Integer readGpsdPid(Context ctx) {
        try {
            for (String line : chroot(ctx, "cat " + GPSD_PID)) {
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

    /** Cmdline proses (spasi, bukan NUL) atau "" bila tak terbaca. */
    private static String readCmdline(Context ctx, int pid) {
        try {
            for (String line : chroot(ctx, "tr '\\0' ' ' < /proc/" + pid + "/cmdline")) {
                if (line != null && !line.trim().isEmpty()) return line.trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * True bila gpsd yang menjawab di 2947 adalah milik Astro:
     * PID file ada + prosesnya hidup + cmdline-nya gpsd. Tanpa
     * ketiganya, gpsd dianggap ASING (fail-safe: jangan bunuh).
     */
    public static boolean isOurGpsd(Context ctx) {
        if (!isListening(GPSD_PORT)) return false;
        Integer pid = readGpsdPid(ctx);
        if (pid == null || !isPidAlive(ctx, pid)) return false;
        return readCmdline(ctx, pid).contains("gpsd");
    }

    /** Klasifikasi status gpsd saat ini. WAJIB thread latar. */
    public static State classify(Context ctx) {
        if (!isListening(GPSD_PORT)) return State.STOPPED;
        boolean kismetRunning = isListening(KISMET_PORT);
        if (menuLive && menuPublisher != null) {
            return kismetRunning ? State.MENU_SHARED : State.MENU;
        }
        if (!isOurGpsd(ctx)) return State.FOREIGN;
        if (kismetRunning) return State.PANEL;
        return State.ORPHAN;
    }

    /**
     * Start rantai dari kartu menu: izin -> gpsd terinstal ->
     * 2947 kosong -> publisher -> gpsd -> poll. Cermin langkah
     * 1-5 ensureGpsChain panel (tanpa baris gps= site conf —
     * itu urusan panel Kismet). Gagal = IOException dengan pesan
     * jelas; publisher yang sempat start dimatikan dulu (jangan
     * orphan). WAJIB thread latar.
     */
    public static synchronized void startFromMenu(@NonNull Context ctx)
            throws IOException {
        Context app = ctx.getApplicationContext();
        if (ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new IOException(ctx.getString(R.string.kismet_gps_perm));
        }
        boolean hasGpsd = false;
        try {
            for (String line : chroot(app, "command -v gpsd")) {
                if (line != null && !line.trim().isEmpty()) {
                    hasGpsd = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!hasGpsd) {
            throw new IOException(ctx.getString(R.string.kismet_gps_nogpsd));
        }
        if (isListening(GPSD_PORT)) {
            throw new IOException(ctx.getString(R.string.kismet_gps_busy));
        }
        NmeaPublisher pub = new NmeaPublisher(app);
        try {
            pub.start();
        } catch (IOException e) {
            String m = e.getMessage();
            throw new IOException(m != null ? m
                    : ctx.getString(R.string.kismet_gps_failed));
        }
        menuPublisher = pub;
        menuLive = true;
        chroot(app, "mkdir -p " + LOG_DIR + " && setsid gpsd -P " + GPSD_PID
                + " udp://" + UDP_HOST + ":" + UDP_PORT
                + " > " + GPSD_LOG + " 2>&1 < /dev/null &");
        boolean up = false;
        for (int i = 0; i < POLL_TRIES && !up; i++) {
            sleepQuietly(POLL_MS);
            up = isListening(GPSD_PORT);
        }
        if (!up) {
            try {
                pub.stop();
            } catch (Throwable ignored) {
            }
            menuPublisher = null;
            menuLive = false;
            throw new IOException(ctx.getString(R.string.kismet_gps_noanswer));
        }
    }

    /**
     * Stop dari kartu menu. Hanya MENU (publisher menu + gpsd)
     * dan ORPHAN (gpsd saja, publisher sudah mati bersama proses
     * lama) yang boleh dibunuh; state lain = IOException dengan
     * alasan. Bunuh selalu verifikasi cmdline dulu. WAJIB thread
     * latar. Gagal bunuh = throw (jangan klaim berhenti padahal
     * masih jalan).
     */
    public static synchronized void stopFromMenu(@NonNull Context ctx)
            throws IOException {
        Context app = ctx.getApplicationContext();
        State s = classify(app);
        if (!isStoppable(s)) {
            throw new IOException(ctx.getString(R.string.gps_gpsd_not_stoppable));
        }
        if (s == State.MENU && menuPublisher != null) {
            try {
                menuPublisher.stop();
            } catch (Throwable ignored) {
            }
            menuPublisher = null;
            menuLive = false;
        }
        killGpsd(app);
    }

    /**
     * Bunuh gpsd via PID file (SIGTERM -> sleep 1 -> SIGKILL bila
     * masih hidup), lalu hapus PID file. PID diverifikasi sebagai
     * gpsd lewat cmdline dulu — PID basi yang ter-recycle tidak
     * boleh dibunuh. Bila PID file tak ada / proses sudah mati,
     * dianggap sudah berhenti (bersihkan PID file saja).
     */
    private static void killGpsd(Context ctx) throws IOException {
        Integer pid = readGpsdPid(ctx);
        if (pid == null || !isPidAlive(ctx, pid)) {
            try {
                chroot(ctx, "rm -f " + GPSD_PID);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (!readCmdline(ctx, pid).contains("gpsd")) {
            throw new IOException("PID " + pid
                    + " tidak terverifikasi sebagai gpsd — tidak dibunuh");
        }
        chroot(ctx, "kill " + pid + "; sleep 1");
        if (isPidAlive(ctx, pid)) {
            chroot(ctx, "kill -9 " + pid + "; sleep 1");
        }
        if (isPidAlive(ctx, pid)) {
            throw new IOException("gpsd (PID " + pid
                    + ") menolak berhenti — coba dari terminal: kill -9 " + pid);
        }
        try {
            chroot(ctx, "rm -f " + GPSD_PID);
        } catch (Throwable ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
