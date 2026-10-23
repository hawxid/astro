package id.hawx.astro.goose;

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
 */
public class ToolsBridge {

    private final Core core;
    private final WebView webView;

    public ToolsBridge(Core core, WebView webView) {
        this.core = core;
        this.webView = webView;
    }

    @JavascriptInterface
    public void run(String command, String callbackId) {
        new Thread(() -> {
            String out;
            try {
                ArrayList<String> lines = core.customChrootCommand(command);
                StringBuilder sb = new StringBuilder();
                for (String l : lines) sb.append(l).append('\n');
                out = sb.toString();
            } catch (Exception e) {
                out = "ERROR: " + e;
            }
            String js = "window.AstroBridgeCb&&AstroBridgeCb("
                    + JSONObject.quote(callbackId) + ","
                    + JSONObject.quote(out) + ")";
            webView.post(() -> webView.evaluateJavascript(js, null));
        }).start();
    }
}
