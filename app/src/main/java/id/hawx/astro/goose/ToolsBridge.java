package id.hawx.astro.goose;

import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;

import id.hawx.astro.utils.Core;

/**
 * Fondasi platform tools WebView (ide Hawx 2026-10-23): jembatan
 * JavaScript -> perintah chroot. Halaman memanggil
 * `AstroBridge.run("perintah", "id")`; hasil (gabungan baris stdout)
 * dikembalikan lewat `window.AstroBridgeCb("id", "output")`.
 *
 * Sengaja ramping: satu-satunya kemampuan yang diekspos adalah eksekusi
 * perintah chroot — persis choke point yang sudah ada (Core).
 * Hanya dipasang di permukaan lokal non-exported (GhostViz).
 *
 * Hardening 2026-10-25 (ronde hardening): dua lapis pertahanan.
 * (1) Origin gate di run(): eksekusi HANYA bila URL WebView saat ini
 * adalah file:// atau loopback (127.0.0.1/localhost) — dicek PER
 * PANGGILAN, bukan saat attach, karena WebView yang sama bisa
 * bernavigasi. URL null/tak dikenal = tolak (fail closed); penolakan
 * tetap dijawab lewat protokol callback yang sama.
 * (2) Navigation guard di WebViewClient kedua permukaan (lihat
 * GhostVizActivity + WebOverlayWindows): URL di luar daftar izin
 * dibuka di browser eksternal, bukan dimuat di webview ber-bridge.
 */
public class ToolsBridge {

    /**
     * Listener opsional: halaman webview memberi tahu bahwa sebuah
     * input teks mendapat fokus (dipakai jendela overlay melayang
     * untuk auto keyboard). null = tidak ada yang mendengarkan
     * (default, mis. GhostViz) — panggilan JS jadi no-op.
     * Sengaja TANPA origin gate seperti run(): memunculkan keyboard
     * bukan operasi privileged, dan halaman yang bisa memanggil
     * bridge sudah dibatasi guard navigasi.
     */
    public interface InputFocusListener {
        void onInputFocused();
    }

    private final Core core;
    private final WebView webView;
    private InputFocusListener inputFocusListener;

    public ToolsBridge(Core core, WebView webView) {
        this.core = core;
        this.webView = webView;
    }

    public void setInputFocusListener(InputFocusListener l) {
        inputFocusListener = l;
    }

    /**
     * Dipanggil halaman saat input teks mendapat fokus (lihat
     * suntikan focusin di WebOverlayWindows). Berjalan di thread
     * WebView, bukan UI thread — listener yang dipasang harus
     * mem-post sendiri ke UI thread bila menyentuh view/window.
     */
    @JavascriptInterface
    public void onInputFocused() {
        if (inputFocusListener != null) {
            inputFocusListener.onInputFocused();
        }
    }

    @JavascriptInterface
    public void run(String command, String callbackId) {
        new Thread(() -> {
            String out;
            try {
                if (!isAllowedOrigin()) {
                    out = "ERROR: origin tidak diizinkan";
                } else {
                    ArrayList<String> lines = core.customChrootCommand(command);
                    StringBuilder sb = new StringBuilder();
                    for (String l : lines) sb.append(l).append('\n');
                    out = sb.toString();
                }
            } catch (Exception e) {
                out = "ERROR: " + e;
            }
            String js = "window.AstroBridgeCb&&AstroBridgeCb("
                    + JSONObject.quote(callbackId) + ","
                    + JSONObject.quote(out) + ")";
            webView.post(() -> webView.evaluateJavascript(js, null));
        }).start();
    }

    /**
     * Origin gate per panggilan: true hanya untuk halaman file:// atau
     * loopback http(s). Segala yang lain (termasuk URL null, skema
     * tak dikenal, dan data:/about:) = false (fail closed).
     */
    private boolean isAllowedOrigin() {
        String url;
        try {
            url = webView.getUrl();
        } catch (Exception e) {
            return false;
        }
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        if (scheme == null) return false;
        if ("file".equalsIgnoreCase(scheme)) return true;
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            String host = u.getHost();
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        }
        return false;
    }

    /**
     * Daftar izin navigasi untuk WebViewClient kedua permukaan
     * ber-bridge. file:// (halaman lokal), loopback http(s) (webui),
     * data: (halaman internal "Berkas tidak ditemukan"), dan about:
     * (about:blank) boleh dimuat di dalam; sisanya harus dibuka di
     * browser eksternal lewat openExternal().
     */
    public static boolean isAllowedNavigationUrl(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        if (scheme == null) return false;
        if ("file".equalsIgnoreCase(scheme)) return true;
        if ("data".equalsIgnoreCase(scheme)) return true;
        if ("about".equalsIgnoreCase(scheme)) return true;
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            String host = u.getHost();
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        }
        return false;
    }

    /**
     * Buka URL di browser eksternal. FLAG_ACTIVITY_NEW_TASK hanya
     * ditambahkan bila context bukan Activity (dari context aplikasi
     * startActivity tanpa flag itu melempar).
     */
    public static void openExternal(android.content.Context ctx, String url) {
        if (ctx == null || url == null) return;
        android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_VIEW, Uri.parse(url));
        if (!(ctx instanceof android.app.Activity)) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            ctx.startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}
