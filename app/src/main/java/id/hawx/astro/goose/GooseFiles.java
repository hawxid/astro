package id.hawx.astro.goose;

import id.hawx.astro.utils.Core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * File operations inside the Debian chroot for Goose output files.
 * Goose saves generated files to /root/goose_output/; this helper
 * lists them and exports to /sdcard for download.
 */
public class GooseFiles {

    public static final String OUTPUT_DIR = "/root/goose_output";
    public static final String EXPORT_DIR = "/sdcard/Astro/goose_files";

    /** Run a command in the chroot via Core.customChrootCommand (proven pattern).
     *  Needs a Core instance (new Core(context)). Uses nolog variant. */
    private static List<String> runChroot(Core core, String cmd) {
        try {
            ArrayList<String> out = core.customChrootCommand(cmd, true);
            List<String> result = new ArrayList<>();
            for (String line : out) {
                if (line != null && !line.trim().isEmpty()) {
                    result.add(line);
                }
            }
            return result;
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    /** Legacy runChroot without Core — deprecated, returns empty. */
    private static List<String> runChroot(String cmd) {
        return new ArrayList<>();
    }

    /** Ensure output and export directories exist. */
    public static void ensureDirs() {
        runChroot("mkdir -p " + OUTPUT_DIR + " " + EXPORT_DIR);
    }

    /** Base directory for Ghost-generated files. */
    public static final String GOOSE_DIR = "/sdcard/Astro/goose";

    /** Ensure the goose output dir exists. */
    public static void ensureGooseDir() {
        try {
            new File(GOOSE_DIR).mkdirs();
        } catch (Exception ignored) {}
    }

    /** List files AND directories in the given path (for browser). */
    public static List<File> listDir(String path) {
        List<File> result = new ArrayList<>();
        try {
            File dir = new File(path);
            File[] list = dir.listFiles();
            if (list != null) {
                // Directories first, then files, alphabetical
                java.util.Arrays.sort(list, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory())
                        return a.isDirectory() ? -1 : 1;
                    boolean aHtml = a.getName().toLowerCase().endsWith(".html");
                    boolean bHtml = b.getName().toLowerCase().endsWith(".html");
                    if (!a.isDirectory() && aHtml != bHtml) return aHtml ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File f : list) result.add(f);
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** List all files directly from /sdcard/Astro/ (no chroot needed). */
    public static List<String> listSdcardFiles() {
        List<String> files = new ArrayList<>();
        try {
            File dir = new File("/sdcard/Astro");
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (f.isFile()) {
                        files.add(f.getName());
                    }
                }
                // Sort: html first, then alphabetical
                files.sort((a, b) -> {
                    boolean aHtml = a.toLowerCase().endsWith(".html");
                    boolean bHtml = b.toLowerCase().endsWith(".html");
                    if (aHtml != bHtml) return aHtml ? -1 : 1;
                    return a.compareToIgnoreCase(b);
                });
            }
        } catch (Exception ignored) {}
        return files;
    }

    /** List HTML files directly from /sdcard/Astro/ (no chroot needed). */
    public static List<String> listSdcardHtml() {
        return listSdcardFiles();
    }

    /** Debug: return raw ls output for troubleshooting. */
    public static List<String> debugLsRaw() {
        return runChroot("ls -1 " + OUTPUT_DIR);
    }

    /**
     * Read session history from Goose SQLite DB.
     * Path: /root/.local/share/goose/sessions/sessions.db
     * Returns list of [role, text] pairs, last N messages (chronological).
     */
    public static List<String[]> getSessionHistory(Core core, String sessionId, int maxMessages) {
        List<String[]> out = new ArrayList<>();
        try {
            if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_-]+")) return out;
            String db = "/root/.local/share/goose/sessions/sessions.db";
            // Pakai -json biar output terstruktur, pakai separator aman
            String sql = "SELECT role, content_json FROM messages WHERE session_id='" +
                    sessionId + "' ORDER BY created_timestamp DESC LIMIT " + maxMessages;
            List<String> lines = runChroot(core,
                    "sqlite3 -json '" + db + "' \"" + sql + "\"");
            if (lines.isEmpty()) return out;
            // Gabung semua line jadi satu JSON
            StringBuilder jsonSb = new StringBuilder();
            for (String l : lines) jsonSb.append(l);
            String json = jsonSb.toString().trim();
            if (!json.startsWith("[")) return out;
            org.json.JSONArray arr = new org.json.JSONArray(json);
            // Hasil DESC, jadi reverse biar kronologis
            List<String[]> reversed = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject row = arr.getJSONObject(i);
                String role = row.optString("role", "");
                if (!role.equals("user") && !role.equals("assistant")) continue;
                String contentJson = row.optString("content_json", "");
                if (contentJson.isEmpty()) continue;
                try {
                    org.json.JSONArray contentArr = new org.json.JSONArray(contentJson);
                    StringBuilder textSb = new StringBuilder();
                    for (int j = 0; j < contentArr.length(); j++) {
                        org.json.JSONObject item = contentArr.getJSONObject(j);
                        if ("text".equals(item.optString("type"))) {
                            String t = item.optString("text", "");
                            // Skip turn-context (metadata, bukan pesan asli)
                            if (t.startsWith("<turn-context>")) continue;
                            if (textSb.length() > 0) textSb.append("\n");
                            textSb.append(t);
                        }
                    }
                    String text = textSb.toString().trim();
                    if (!text.isEmpty()) {
                        if (text.length() > 2000) {
                            text = text.substring(0, 2000) + "\n…[truncated]";
                        }
                        reversed.add(new String[]{role, text});
                    }
                } catch (Exception ignored) {}
            }
            // Reverse biar kronologis (tertua dulu)
            for (int i = reversed.size() - 1; i >= 0; i--) {
                out.add(reversed.get(i));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** List files in the goose output directory. */
    public static List<String> listOutputFiles() {
        ensureDirs();
        List<String> files = new ArrayList<>();
        // Jangan pake shell redirection (2>/dev/null) — chroot_exec nggak interpret itu
        List<String> lines = runChroot("ls -1 " + OUTPUT_DIR);
        for (String l : lines) {
            l = l.trim();
            // Skip error messages dan direktori
            if (!l.isEmpty() && !l.endsWith("/") && !l.startsWith("ls:") && !l.contains("No such")) {
                files.add(l);
            }
        }
        return files;
    }

    /**
     * Copy a file from chroot output dir to /sdcard for download.
     * Returns the export path on success, null on failure.
     */
    public static String exportFile(String filename) {
        // Sanitize: no path traversal
        if (filename.contains("/") || filename.contains("..")) return null;
        ensureDirs();
        List<String> res = runChroot(
                "cp " + OUTPUT_DIR + "/" + filename + " " + EXPORT_DIR + "/" + filename +
                " && echo OK");
        for (String l : res) {
            if (l.trim().equals("OK")) {
                return EXPORT_DIR + "/" + filename;
            }
        }
        return null;
    }
}
