package id.hawx.astro.x11;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Log debug X11 — permintaan Hawx 2026-10-21 setelah empat ronde buta
 * gara-gara manajer sesi tidak punya catatan yang bisa dibaca.
 *
 * Semua peristiwa penting XSessionManager ditulis ke
 * /sdcard/Astro/x11-debug.log (mudah dibuka/dibagikan dari pengelola
 * berkas), format satu baris per peristiwa:
 *   [21:49:11.203] [PROBE] RUNNING/sock/zombie=false (server yatim diadopsi) PID=22771 RSS=62080 kB
 * Rotasi sederhana: >256 KB diganti nama jadi .old. Juga dicerminkan ke
 * logcat tag "AstroX11". Menulis tidak pernah melempar — kegagalan log
 * tidak boleh menjatuhkan fitur.
 */
public final class X11DebugLog {

    private static final File FILE = new File("/sdcard/Astro/x11-debug.log");
    private static final long MAX_BYTES = 256 * 1024L;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private X11DebugLog() {}

    public static synchronized void log(String tag, String msg) {
        String line = "[" + TS.format(new Date()) + "] [" + tag + "] " + msg;
        try { Log.d("AstroX11", line); } catch (Exception ignored) {}
        try {
            File parent = FILE.getParentFile();
            if (parent != null) parent.mkdirs();
            if (FILE.exists() && FILE.length() > MAX_BYTES) {
                File old = new File(FILE.getPath() + ".old");
                if (old.exists()) old.delete();
                FILE.renameTo(old);
            }
            FileWriter w = new FileWriter(FILE, true);
            w.write(line);
            w.write("\n");
            w.close();
        } catch (Exception ignored) {}
    }

    /** Ekor log (n baris terakhir), untuk panel debug di layar X11. */
    public static synchronized String tail(int n) {
        if (!FILE.exists()) return "(log debug belum ada — jalankan Start Server dulu)";
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(FILE))) {
            String ln;
            while ((ln = r.readLine()) != null) lines.add(ln);
        } catch (Exception e) {
            return "(gagal membaca log: " + e + ")";
        }
        if (lines.isEmpty()) return "(log kosong)";
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - n);
        for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        return sb.toString().trim();
    }

    public static synchronized void clear() {
        try { if (FILE.exists()) FILE.delete(); } catch (Exception ignored) {}
        log("LOG", "log dibersihkan dari UI");
    }
}
