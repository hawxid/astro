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
    /** "gui" | "cli" */
    public String type;
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
        workdir = "/root";
        env = "";
        allowMultiple = false;
        floating = false;
        builtin = false;
        hidden = false;
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
        e.type = "cli".equals(o.optString("type", "gui")) ? "cli" : "gui";
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
