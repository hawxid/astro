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
                // Pola peluncuran yang sama dengan entri X (R1):
                // subshell (setsid server > log 2>&1 & echo $! > pid).
                // Perintah harus satu invokasi server (bukan pipeline)
                // supaya $! = PID server yang sebenarnya.
                String wrap = "(setsid " + cmd + " >" + logPath(e.port)
                        + " 2>&1 & echo $! > " + pidPath(e.port) + ")";
                core.threadChrootCommand(wrap);
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
                    toast(ctx, "Server " + e.name + " tidak hidup di port "
                            + e.port + " (cek log: " + logPath(e.port) + ")");
                    return;
                }
            }
            final boolean didStart = started;
            MAIN.post(() -> openWebview(ctx, e, url, didStart));
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
                                    boolean didStart) {
        if (e.floating) {
            boolean existed =
                    id.hawx.astro.x11.window.WebOverlayWindows.isShowing(url);
            boolean shown = id.hawx.astro.x11.window.WebOverlayWindows.show(
                    ctx, e.name, url, () -> killServer(ctx, e.port));
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
        i.putExtra(id.hawx.astro.goose.GhostVizActivity.EXTRA_WEBUI_PORT, e.port);
        if (!(ctx instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }
}
