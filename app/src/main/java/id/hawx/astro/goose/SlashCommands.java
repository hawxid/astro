package id.hawx.astro.goose;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Customizable slash commands for Ghost.
 * Config lives at /sdcard/Stryker/goose/slash_commands.json — edit without rebuilding!
 *
 * Format:
 * {
 *   "commands": [
 *     {"name": "/nmap", "desc": "Quick nmap scan", "prompt": "run a quick nmap scan on {args}"},
 *     {"name": "/cve", "desc": "Lookup CVE details", "prompt": "lookup details for {args}"}
 *   ]
 * }
 *
 * {args} = everything after the command name.
 * Built-in: /help (list), /clear (clear chat) — handled by the activity, not sent to Ghost.
 */
public class SlashCommands {

    public static final String CONFIG_PATH = "/sdcard/Stryker/goose/slash_commands.json";

    public static class Cmd {
        public final String name;
        public final String desc;
        public final String prompt;
        Cmd(String name, String desc, String prompt) {
            this.name = name;
            this.desc = desc;
            this.prompt = prompt;
        }
    }

    /** Default commands created on first run. */
    private static String defaultJson() {
        return "{\n" +
            "  \"commands\": [\n" +
            "    {\"name\": \"/nmap\", \"desc\": \"Quick nmap scan\", \"prompt\": \"run a quick nmap scan on {args} and explain the results\"},\n" +
            "    {\"name\": \"/ping\", \"desc\": \"Ping a host\", \"prompt\": \"ping {args} with 4 packets\"},\n" +
            "    {\"name\": \"/cve\", \"desc\": \"Lookup CVE details\", \"prompt\": \"lookup details for {args} including severity, description, and affected versions\"},\n" +
            "    {\"name\": \"/scan\", \"desc\": \"Full port scan\", \"prompt\": \"do a comprehensive port scan on {args} using nmap\"},\n" +
            "    {\"name\": \"/report\", \"desc\": \"Generate report\", \"prompt\": \"create a summary report of our conversation and save it to /sdcard/Stryker/goose/report.md\"}\n" +
            "  ]\n" +
            "}\n";
    }

    /** Ensure config exists (create default if missing). */
    public static void ensureConfig() {
        try {
            File f = new File(CONFIG_PATH);
            if (!f.exists()) {
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), defaultJson().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    /** Load commands from config. Returns empty list on error. */
    public static List<Cmd> load() {
        List<Cmd> out = new ArrayList<>();
        try {
            ensureConfig();
            String json = new String(Files.readAllBytes(new File(CONFIG_PATH).toPath()),
                    StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("commands");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.optString("name", "").trim();
                if (!name.startsWith("/")) name = "/" + name;
                out.add(new Cmd(name, o.optString("desc", ""),
                        o.optString("prompt", "{args}")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Find a command by name (e.g. "/nmap"). Returns null if not found. */
    public static Cmd find(String name) {
        for (Cmd c : load()) {
            if (c.name.equalsIgnoreCase(name)) return c;
        }
        return null;
    }

    /** Expand a slash input into the full prompt. Returns null if not a slash command. */
    public static String expand(String input) {
        if (input == null || !input.trim().startsWith("/")) return null;
        String trimmed = input.trim();
        int sp = trimmed.indexOf(' ');
        String name = sp < 0 ? trimmed : trimmed.substring(0, sp);
        String args = sp < 0 ? "" : trimmed.substring(sp + 1).trim();
        Cmd cmd = find(name);
        if (cmd == null) return null;
        return cmd.prompt.replace("{args}", args);
    }

    /** Build help text listing all commands. */
    public static String helpText() {
        StringBuilder sb = new StringBuilder("**Goose Commands:**\n\n");
        sb.append("`/help` — Show this list\n");
        sb.append("`/clear` — Clear chat\n\n");
        for (Cmd c : load()) {
            sb.append("`").append(c.name).append("` — ").append(c.desc).append("\n");
        }
        sb.append("\n_Edit `/sdcard/Stryker/goose/slash_commands.json` to customize._");
        return sb.toString();
    }
}
