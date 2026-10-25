package id.hawx.astro.x11;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Satu entri launcher (sistem custom launcher ala XFCE, rencana §3.4).
 *
 * Penyimpangan sadar dari skema rencana: `env` disimpan sebagai teks mentah
 * "KUNCI=nilai per baris" (bukan objek JSON) — lebih gampang diedit tangan
 * pada berkas ekspor/impor, dan editor menampilkannya apa adanya.
 * `stopPattern` rencana DROPPED (kritik Gemini): stop memakai file PID.
 * `resolution` per-entri DROPPED (bundle Hawx): satu resolusi global + DPI.
 */
public class LauncherEntry {

    public String id;
    public String name;
    /** Emoji/teks pendek; kosong -> monogram huruf pertama nama. "file:<nama>" -> PNG di /sdcard/Astro/icons/. */
    public String icon;
    public String command;
    /** "gui" | "cli" | "webview" | "webui" */
    public String type;
    /** Port web UI (mode "webui" saja); 0 = belum diatur. Host dikunci 127.0.0.1. */
    public int port;
    /** Path URL web UI (mode "webui" saja); default "/". */
    public String urlPath;
    public String workdir;
    /** "KUNCI=nilai" per baris; boleh kosong. */
    public String env;
    public boolean allowMultiple;
    /** true = buka sebagai jendela floating (display sendiri >= :1), bukan viewer fullscreen :0. */
    public boolean floating;
    public boolean builtin;
    public boolean hidden;

    public LauncherEntry() {
        id = "";
        name = "";
        icon = "";
        command = "";
        type = "gui";
        port = 0;
        urlPath = "/";
        workdir = "/root";
        env = "";
        allowMultiple = false;
        floating = false;
        builtin = false;
        hidden = false;
    }

    /** Salinan dengan id lain — buat instance tambahan entri multi. */
    public LauncherEntry copyWithId(String newId) {
        LauncherEntry c = new LauncherEntry();
        c.id = newId;
        c.name = name;
        c.icon = icon;
        c.command = command;
        c.type = type;
        c.port = port;
        c.urlPath = urlPath;
        c.workdir = workdir;
        c.env = env;
        c.allowMultiple = allowMultiple;
        c.floating = floating;
        c.builtin = builtin;
        c.hidden = hidden;
        return c;
    }

    public String workdirOrDefault() {
        return (workdir == null || workdir.trim().isEmpty()) ? "/root" : workdir.trim();
    }

    /** Kata pertama perintah — dipakai verifikasi cmdline proses (anti PID-reuse). */
    public String firstWord() {
        if (command == null) return "";
        String c = command.trim();
        int sp = c.indexOf(' ');
        String w = sp < 0 ? c : c.substring(0, sp);
        int slash = w.lastIndexOf('/');
        return slash >= 0 ? w.substring(slash + 1) : w;
    }

    public static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "launcher" : s;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("icon", icon);
        o.put("command", command);
        o.put("type", type);
        o.put("port", port);
        o.put("urlPath", urlPath);
        o.put("workdir", workdir);
        o.put("env", env);
        o.put("allowMultiple", allowMultiple);
        o.put("floating", floating);
        o.put("builtin", builtin);
        o.put("hidden", hidden);
        return o;
    }

    public static LauncherEntry fromJson(JSONObject o) {
        LauncherEntry e = new LauncherEntry();
        e.id = o.optString("id", "");
        e.name = o.optString("name", "");
        e.icon = o.optString("icon", "");
        e.command = o.optString("command", "");
        // Dulu: semua yang bukan "cli" dipaksa "gui" — tipe "webview"
        // yang tersimpan BENAR ikut hilang tiap reload (temuan Hawx:
        // ikon webview selalu fallback ke GUI Layar X). Kini keempat
        // tipe dipertahankan; yang tak dikenal tetap jatuh ke "gui".
        // "webui" WAJIB ada di daftar ini — kalau tidak, entri webui
        // yang tersimpan ikut hilang tiap reload (bug webview dulu,
        // terulang).
        String t = o.optString("type", "gui");
        e.type = "cli".equals(t) || "webview".equals(t) || "webui".equals(t) ? t : "gui";
        e.port = o.optInt("port", 0);
        e.urlPath = o.optString("urlPath", "/");
        e.workdir = o.optString("workdir", "/root");
        e.env = o.optString("env", "");
        e.allowMultiple = o.optBoolean("allowMultiple", false);
        e.floating = o.optBoolean("floating", false);
        e.builtin = o.optBoolean("builtin", false);
        e.hidden = o.optBoolean("hidden", false);
        if (e.id.isEmpty()) e.id = slug(e.name);
        return e;
    }
}
