package com.termux.x11;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * DIAGNOSTIK SEMENTARA (2026-10-22, regresi viewer PoC 1): logger berkas
 * mini yang menulis ke file yang sama dengan X11DebugLog milik app
 * (/sdcard/Astro/x11-debug.log) karena modul lorie tidak bisa melihat
 * kelas app. HAPUS bersama semua titik XDebug.log setelah diagnosis.
 */
public final class XDebug {
    private static final File FILE = new File("/sdcard/Astro/x11-debug.log");
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private XDebug() {}

    public static synchronized void log(String tag, String msg) {
        String line = "[" + TS.format(new Date()) + "] [" + tag + "] " + msg;
        try {
            File parent = FILE.getParentFile();
            if (parent != null) parent.mkdirs();
            FileWriter w = new FileWriter(FILE, true);
            w.write(line);
            w.write("\n");
            w.close();
        } catch (Exception ignored) {}
    }
}
