package id.hawx.astro.x11;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;

import id.hawx.astro.utils.Core;

/**
 * Mode launcher "webui" (ronde webui, 2026-10-25): tool yang menyajikan
 * web UI dari dalam chroot, dibuka di webview Astro (overlay melayang
 * bila floating, GhostViz fullscreen bila tidak).
 *
 * Mekanisme — chroot TIDAK mengisolasi network (cuma ganti root fs),
 * jadi server di 127.0.0.1 dalam chroot = 127.0.0.1 yang bisa dibuka
 * WebView Android. Tanpa port-forward, tanpa VNC.
 *
 * Host DIKUNCI 127.0.0.1 — tidak bisa diubah user, tidak pernah
 * 0.0.0.0. Web UI tool biasanya tanpa auth; bind ke semua interface
 * berarti satu jaringan bisa buka (pelajaran payload lama: shell root
 * tanpa auth di 0.0.0.0 — jangan diulang).
 *
 * Single-instance per port: tap saat port sudah listening TIDAK
 * men-start ulang — langsung tampilkan webview-nya (pola "sudah
 * jalan" don). PID server ditulis ke /tmp chroot (pola yang sama
 * dengan PID file R1: setsid + log + echo $!); ✕ / Kill membaca
 * PID file itu untuk mematikan server. Sembunyikan (—) TIDAK
 * mematikan server (cerminan semantik X: hide = server hidup).
 *
 * MODE 2 JENDELA (opsi launchTerminal pada entri webui):
 * fresh start TIDAK memakai proses detached di atas — perintah
 * server dijalankan INTERAKTIF di dalam jendela xterm lewat jalur
 * launch floating XSessionManager (pool desktop :3-:4, font xterm
 * tetap disuntik scaledCommand), lalu port di-poll seperti biasa
 * dan webview dibuka. Terminal yang MEMILIKI proses:
 * - Tutup jendela xterm (✕ layer -> stopDisplay -> server X display
 *   mati -> xterm keluar -> server menerima SIGHUP dan mati).
 * - Tutup webview TIDAK membunuh server di mode ini (hook tutup
 *   webview = no-op, dan GhostViz tidak diberi port) — server hidup
 *   selama xterm hidup; tap ulang entry hanya membuka lagi
 *   webview-nya (port sudah listening).
 * - File PID webui (/tmp/astro-webui-<port>.pid) TIDAK PERNAH dibuat
 *   di mode ini, jadi killServer tidak punya PID untuk dibunuh
 *   (file hilang = no-op); file basi berisi PID mati dibersihkan
 *   saat fresh start supaya killServer masa depan tidak mengenai
 *   PID daur ulang. File berisi PID hidup DIBIARKAN (server detached
 *   lama yang belum sempat listen).
 * Port SUDAH listening saat tap: perilaku lama dipertahankan persis
 * (webview saja, TANPA jendela terminal — terminal tidak bisa
 * disambungkan ke instance yang sudah jalan).
 */
public class WebUiLauncher {

    /** Host TERKUNCI — satu-satunya host yang pernah dipakai. */
    public static final String HOST = "127.0.0.1";

    private static final int POLL_MS = 500;
    private static final int POLL_TRIES = 30; // ~15 detik
    private static final int CONNECT_TIMEOUT_MS = 500;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface KillCallback {
        void onResult(boolean killed);
    }

    public static boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }

    /** Kosong -> "/"; pastikan diawali "/". */
    public static String normalizePath(String p) {
        if (p == null) return "/";
        String t = p.trim();
        if (t.isEmpty()) return "/";
        return t.startsWith("/") ? t : "/" + t;
    }

    public static String urlFor(int port, String path) {
        return "http://" + HOST + ":" + port + normalizePath(path);
    }

    public static String urlFor(LauncherEntry e) {
        return urlFor(e.port, e.urlPath);
    }

    /** Path SISI CHROOT (bukan Android) — sama seperti PID file R1. */
    public static String pidPath(int port) {
        return "/tmp/astro-webui-" + port + ".pid";
    }

    public static String logPath(int port) {
        return "/tmp/astro-webui-" + port + ".log";
    }

    /** ID entri xterm turunan untuk satu entri webui — dipakai file
     *  PID jalur X (/tmp/astro-launch-<id>.pid). Diekspos supaya
     *  jalur Kill desktop kelak bisa menghentikan entri ini juga. */
    public static String termEntryIdFor(LauncherEntry e) {
        return e.id + "-term";
    }

    /** Entri GUI xterm sementara untuk mode 2 jendela: perintah server
     *  (sudah substitusi {PORT}) dijalankan interaktif lewat
     *  `xterm -e sh -c ...` — sh -c menjaga semantik shell mode
     *  detached (pipa, &&, dsb). Font xterm tetap disuntik
     *  XSessionManager.scaledCommand karena perintah diawali kata
     *  `xterm` (tidak diduplikasi di sini). workdir/env diwariskan
     *  dari entri webui; allowMultiple=false supaya tap ganda tidak
     *  melahirkan xterm kedua (tabrakan port). */
    public static LauncherEntry termEntryFor(LauncherEntry e, String serverCmd) {
        LauncherEntry t = new LauncherEntry();
        t.id = termEntryIdFor(e);
        t.name = e.name + " terminal";
        t.command = "xterm -e sh -c " + XServerOps.shq(serverCmd);
        t.type = "gui";
        t.floating = true;
        t.workdir = e.workdir;
        t.env = e.env;
        t.allowMultiple = false;
        return t;
    }

    /**
     * Tes dari sisi Android — persis sudut pandang WebView: socket
     * connect ke 127.0.0.1:port. Harus dipanggil dari thread latar
     * (bukan UI thread).
     */
    public static boolean isListening(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException ignored) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Kill fire-and-forget (dipakai hook tutup window). */
    public static void killServer(Context ctx, int port) {
        killServer(ctx, port, null);
    }

    /**
     * Matikan server via PID file: baca PID (baris angka pertama),
     * kill bila /proc-nya masih ada, selalu hapus PID file (PID basi
     * = proses sudah mati -> file dibersihkan). cb (bila ada)
     * dipanggil di main thread dengan true bila PID file ditemukan
     * (server pernah jalan), false bila tidak ada jejak sama sekali.
     */
    public static void killServer(final Context ctx, final int port,
                                  final KillCallback cb) {
        new Thread(() -> {
            boolean killed = false;
            try {
                Core core = new Core(ctx);
                ArrayList<String> out = core.customChrootCommand(
                        "cat " + pidPath(port) + " 2>/dev/null");
                String pid = "";
                for (String line : out) {
                    String t = line == null ? "" : line.trim();
                    if (!t.isEmpty()) {
                        try {
                            Integer.parseInt(t);
                            pid = t;
                            break;
                        } catch (NumberFormatException ignored) {
                            // Bukan baris PID — lanjut cari baris angka.
                        }
                    }
                }
                if (!pid.isEmpty()) {
                    killed = true;
                    core.customChrootCommand("if [ -d /proc/" + pid + " ]; then "
                            + "kill " + pid + " 2>/dev/null; fi; "
                            + "rm -f " + pidPath(port));
                }
            } catch (Throwable ignored) {
            }
            if (cb != null) {
                final boolean result = killed;
                MAIN.post(() -> cb.onResult(result));
            }
        }).start();
    }

    /**
     * Alur launch lengkap — boleh dipanggil dari UI thread (kerja
     * berat jalan di thread latar sendiri):
     * port sudah listening -> langsung buka webview (tanpa start
     * ulang); belum -> substitusi {PORT}, start server chroot di
     * background (pola R1: setsid + log + PID file), poll port
     * ~15 detik; hidup -> buka webview, gagal -> toast error dan
     * TIDAK buka halaman kosong.
     */
    public static void launch(final Context ctx, final LauncherEntry e) {
        if (!validPort(e.port)) {
            Toast.makeText(ctx, "Port webui tidak valid (1–65535)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String url = urlFor(e);
        new Thread(() -> {
            Core core = new Core(ctx);
            if (!core.isMounted()) {
                toast(ctx, "Chroot belum terpasang");
                return;
            }
            boolean started = false;
            if (!isListening(e.port)) {
                String cmd = e.command == null ? ""
                        : e.command.replace("{PORT}", String.valueOf(e.port));
                if (cmd.trim().isEmpty()) {
                    toast(ctx, "Perintah webui kosong");
                    return;
                }
                if (e.launchTerminal) {
                    // MODE 2 JENDELA — fresh start: server dijalankan
                    // INTERAKTIF di jendela xterm (bukan detached).
                    // TIDAK ada proses setsid dan TIDAK ada file PID
                    // webui di mode ini (lihat komentar kelas).
                    // Bersihkan file PID basi (PID sudah mati) supaya
                    // killServer masa depan tidak mengenai PID daur
                    // ulang; file berisi PID hidup DIBIARKAN.
                    core.threadChrootCommand("P=$(cat " + pidPath(e.port)
                            + " 2>/dev/null); if [ -n \"$P\" ] && ! [ -d /proc/$P ]; "
                            + "then rm -f " + pidPath(e.port) + "; fi");
                    final LauncherEntry term = termEntryFor(e, cmd);
                    XSessionManager mgr = XSessionManager.get(ctx);
                    // Latch menunggu callback jendela (main thread)
                    // supaya jendela xterm naik sebelum poll port;
                    // timeout longgar karena start server X baru
                    // bisa makan waktu — timeout bukan gagal,
                    // poll di bawah yang memutuskan.
                    final java.util.concurrent.CountDownLatch termDone =
                            new java.util.concurrent.CountDownLatch(1);
                    final java.util.concurrent.atomic.AtomicReference<String> termErr =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    if (!mgr.entryRunningSync(term.id, term.firstWord())) {
                        mgr.launchFloating(term, (ok, d, msg) -> {
                            if (ok && d > 0) {
                                id.hawx.astro.MainActivity.openXWindowFrom(
                                        ctx, d, term.name + " · :" + d);
                                termDone.countDown();
                            } else if (!ok && msg != null && msg.contains("bukan floating")) {
                                // Balapan tap ganda: xterm sudah jalan
                                // tapi catatan floating-nya hilang
                                // (restart app) — dulu cabang ini
                                // diam-diam lanjut ke poll tanpa
                                // membuka jendela (bug 2026-10-31).
                                // Sekarang pulihkan dari state live.
                                mgr.openFloatingWindowFor(term, (ok2, d2, msg2) -> {
                                    if (ok2 && d2 > 0) {
                                        id.hawx.astro.MainActivity.openXWindowFrom(
                                                ctx, d2, term.name + " · :" + d2);
                                    } else {
                                        termErr.set(msg2 != null ? msg2
                                                : "Terminal jalan tapi jendelanya tidak ketemu");
                                    }
                                    termDone.countDown();
                                });
                            } else if (!ok) {
                                termErr.set(msg != null ? msg
                                        : "Gagal meluncurkan terminal");
                                termDone.countDown();
                            } else {
                                termDone.countDown();
                            }
                        });
                    } else {
                        // SUDAH JALAN: dulu jalur ini tidak membuka
                        // jendela sama sekali (langsung poll) —
                        // itu yang bikin xterm "cuma di dock, buka
                        // manual" (bug 2026-10-31). Sekarang buka
                        // ulang jendelanya dari state live.
                        mgr.openFloatingWindowFor(term, (ok, d, msg) -> {
                            if (ok && d > 0) {
                                id.hawx.astro.MainActivity.openXWindowFrom(
                                        ctx, d, term.name + " · :" + d);
                            } else {
                                termErr.set(msg != null ? msg
                                        : "Terminal jalan tapi jendelanya tidak ketemu");
                            }
                            termDone.countDown();
                        });
                    }
                    try {
                        termDone.await(60, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        // Lanjut ke poll — xterm mungkin tetap naik.
                    }
                    if (termErr.get() != null) {
                        toast(ctx, termErr.get());
                        return;
                    }
                    // Jendela xterm sudah dibuka (baru diluncurkan /
                    // dibuka ulang dari tap sebelumnya) — lanjut
                    // ke poll port.
                } else {
                    // Jalur lama, tidak berubah: server detached +
                    // PID file. Pola peluncuran yang sama dengan entri
                    // X (R1): subshell (setsid server > log 2>&1 &
                    // echo $! > pid). Perintah harus satu invokasi
                    // server (bukan pipeline) supaya $! = PID server
                    // yang sebenarnya.
                    String wrap = "(setsid " + cmd + " >" + logPath(e.port)
                            + " 2>&1 & echo $! > " + pidPath(e.port) + ")";
                    core.threadChrootCommand(wrap);
                }
                started = true;
                boolean up = false;
                for (int i = 0; i < POLL_TRIES; i++) {
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                    if (isListening(e.port)) {
                        up = true;
                        break;
                    }
                }
                if (!up) {
                    if (e.launchTerminal) {
                        toast(ctx, "Server " + e.name + " tidak hidup di port "
                                + e.port + " (cek jendela terminal)");
                    } else {
                        toast(ctx, "Server " + e.name + " tidak hidup di port "
                                + e.port + " (cek log: " + logPath(e.port) + ")");
                    }
                    return;
                }
            }
            // Port SUDAH listening saat tap: server sudah jalan
            // (mungkin milik xterm dari tap sebelumnya). Dulu jalur
            // ini webview saja TANPA jendela terminal — itu yang
            // bikin tap ulang tidak pernah memunculkan xterm
            // (bug 2026-10-31). Sekarang, bila ini tap ulang
            // (bukan fresh start di atas): buka ulang jendela
            // terminal dari state live, tanpa start ulang apa pun;
            // gagal = toast, webview tetap dibuka.
            // killOnClose juga dimatikan untuk entri 2 jendela di
            // SEMUA jalur (fresh start maupun sudah listening):
            // server mungkin milik terminal, dan tutup webview tidak
            // boleh membunuhnya.
            if (e.launchTerminal && !started) {
                String cmd0 = e.command == null ? ""
                        : e.command.replace("{PORT}", String.valueOf(e.port));
                final LauncherEntry term0 = termEntryFor(e, cmd0);
                XSessionManager mgr0 = XSessionManager.get(ctx);
                final java.util.concurrent.CountDownLatch latch0 =
                        new java.util.concurrent.CountDownLatch(1);
                final java.util.concurrent.atomic.AtomicReference<Integer> disp0 =
                        new java.util.concurrent.atomic.AtomicReference<>();
                mgr0.openFloatingWindowFor(term0, (ok0, d0, msg0) -> {
                    if (ok0 && d0 > 0) {
                        disp0.set(d0);
                    } else {
                        toast(ctx, "Jendela terminal tidak ketemu — buka dari dock display");
                    }
                    latch0.countDown();
                });
                try {
                    latch0.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                if (disp0.get() != null) {
                    final int dd = disp0.get();
                    MAIN.post(() -> id.hawx.astro.MainActivity.openXWindowFrom(
                            ctx, dd, term0.name + " · :" + dd));
                }
            }
            final boolean didStart = started;
            final boolean killOnClose = !e.launchTerminal;
            MAIN.post(() -> openWebview(ctx, e, url, didStart, killOnClose));
        }).start();
    }

    private static void toast(final Context ctx, final String msg) {
        MAIN.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    /**
     * Buka webview. floating -> overlay (fallback GhostViz bila
     * overlay tak bisa tampil, sama seperti dispatch webview).
     * Bila server BARU di-start dan overlay lama untuk URL ini
     * sudah ada (kasus: window disembunyikan, server sempat mati),
     * reload supaya halaman basi (connection refused) diganti
     * halaman tool yang sudah hidup — reload HANYA di kasus ini,
     * bukan tiap re-show (state halaman tool yang sedang dipakai
     * tidak boleh kereset tiap tap).
     */
    private static void openWebview(Context ctx, LauncherEntry e, String url,
                                    boolean didStart, boolean killOnClose) {
        if (e.floating) {
            boolean existed =
                    id.hawx.astro.x11.window.WebOverlayWindows.isShowing(url);
            // Mode 2 jendela: hook tutup = no-op — server milik
            // terminal, tutup webview tidak boleh membunuhnya.
            boolean shown = id.hawx.astro.x11.window.WebOverlayWindows.show(
                    ctx, e.name, url,
                    killOnClose ? () -> killServer(ctx, e.port) : () -> { });
            if (shown) {
                // Label dock (ikon entry) — jalur raise ikut segar.
                id.hawx.astro.x11.window.WebOverlayWindows.setDockLabelForPath(
                        url,
                        id.hawx.astro.x11.window.WebOverlayWindows.dockLabelFor(e.icon, e.name));
                if (didStart && existed) {
                    id.hawx.astro.x11.window.WebOverlayWindows.reloadByPath(url);
                }
                return;
            }
        }
        Intent i = new Intent(ctx, id.hawx.astro.goose.GhostVizActivity.class);
        i.putExtra(id.hawx.astro.goose.GhostVizActivity.EXTRA_PATH, url);
        i.putExtra(id.hawx.astro.goose.GhostVizActivity.EXTRA_TITLE, e.name);
        // Mode 2 jendela: port TIDAK diberikan — onDestroy GhostViz
        // membunuh server bila port ada; di mode ini server milik
        // terminal dan tutup webview tidak boleh membunuhnya.
        if (killOnClose) {
            i.putExtra(id.hawx.astro.goose.GhostVizActivity.EXTRA_WEBUI_PORT, e.port);
        }
        if (!(ctx instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }
}
