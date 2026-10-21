package id.hawx.astro.x11;

import android.content.Context;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Penyimpanan launcher (bundle Hawx 2026-10-21, membalik usulan rencana):
 * simpanan OPERASIONAL di storage privat app (filesDir/x11-launchers.json) —
 * editor pihak ketiga di sdcard bisa memotong berkas di tengah tulis dan
 * merusak JSON. Berkas /sdcard/Astro/launchers.json hanya jalur EKSPOR/IMPOR
 * manual yang eksplisit.
 *
 * Tulis atomik (tmp + rename). Berkas rusak -> diganti nama .bak, di-seed
 * ulang dari bawaan, dan peringatannya disimpan untuk ditampilkan UI —
 * tidak pernah menghapus diam-diam.
 */
public class LauncherStore {

    private static final String FILE_NAME = "x11-launchers.json";
    private static final File SDCARD_FILE = new File("/sdcard/Astro", "launchers.json");

    private final File file;
    private final List<LauncherEntry> entries = new ArrayList<>();
    public String lastLoadWarning = null;

    public LauncherStore(Context ctx) {
        this.file = new File(ctx.getFilesDir(), FILE_NAME);
        load();
    }

    // ------------------------------------------------------------ seed

    private static LauncherEntry builtinXterm() {
        LauncherEntry e = new LauncherEntry();
        e.id = "xterm";
        e.name = "XTerm";
        e.icon = "🖥";
        // -fa: font TrueType sisi klien (fontconfig chroot) — terbukti di perangkat
        // mengobati kegagalan font bitmap server pada locale UTF-8.
        e.command = "xterm -fa Monospace -fs 14";
        e.type = "gui";
        e.workdir = "/root";
        e.env = "";
        e.allowMultiple = false;
        e.builtin = true;
        e.hidden = false;
        return e;
    }

    private void seed() {
        entries.clear();
        entries.add(builtinXterm());
    }

    // ------------------------------------------------------------ load/save

    private synchronized void load() {
        if (!file.exists()) {
            seed();
            save();
            return;
        }
        try {
            String text = new String(readAll(file), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(text);
            entries.clear();
            for (int i = 0; i < arr.length(); i++) {
                entries.add(LauncherEntry.fromJson(arr.getJSONObject(i)));
            }
            boolean hasXterm = false;
            for (LauncherEntry e : entries) if ("xterm".equals(e.id)) { hasXterm = true; break; }
            if (!hasXterm) entries.add(0, builtinXterm());
        } catch (Exception e) {
            File bak = new File(file.getParent(), FILE_NAME + ".bak-" + System.currentTimeMillis());
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(bak);
            lastLoadWarning = "Berkas launcher rusak (" + e.getMessage() + ") — cadangan disimpan sebagai "
                    + bak.getName() + ", daftar diisi ulang dari bawaan.";
            seed();
            save();
        }
    }

    public synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            for (LauncherEntry e : entries) arr.put(e.toJson());
            File tmp = new File(file.getParent(), FILE_NAME + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        } catch (Exception ignored) {
            // kegagalan simpan tidak boleh menjatuhkan UI; pemanggil memeriksa lewat refresh
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = fis.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        }
    }

    // ------------------------------------------------------------ CRUD

    public synchronized List<LauncherEntry> list() {
        return new ArrayList<>(entries);
    }

    public synchronized LauncherEntry get(String id) {
        for (LauncherEntry e : entries) if (e.id.equals(id)) return e;
        return null;
    }

    /** Id unik dari slug nama (dipakai entri baru). */
    public synchronized String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (get(id) != null) id = base + "-" + (n++);
        return id;
    }

    public synchronized void upsert(LauncherEntry e) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(e.id)) {
                entries.set(i, e);
                save();
                return;
            }
        }
        entries.add(e);
        save();
    }

    public synchronized void remove(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                entries.remove(i);
                break;
            }
        }
        save();
    }

    // ------------------------------------------------------------ ekspor / impor

    /** Tulis salinan ke /sdcard/Astro/launchers.json. @return pesan hasil (untuk toast). */
    public synchronized String exportToSdcard() {
        try {
            File dir = SDCARD_FILE.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return "Gagal membuat folder /sdcard/Astro";
            }
            JSONArray arr = new JSONArray();
            for (LauncherEntry e : entries) arr.put(e.toJson());
            try (FileOutputStream fos = new FileOutputStream(SDCARD_FILE)) {
                fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return "Diekspor ke /sdcard/Astro/launchers.json (" + entries.size() + " entri)";
        } catch (Exception e) {
            return "Ekspor gagal: " + e.getMessage();
        }
    }

    /** Baca /sdcard/Astro/launchers.json menggantikan daftar saat ini (cadangan .bak dibuat dulu). */
    public synchronized String importFromSdcard() {
        try {
            if (!SDCARD_FILE.exists()) return "Tidak ada /sdcard/Astro/launchers.json";
            String text = new String(readAll(SDCARD_FILE), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(text); // validasi dulu sebelum menyentuh state
            List<LauncherEntry> loaded = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) loaded.add(LauncherEntry.fromJson(arr.getJSONObject(i)));
            if (file.exists()) {
                File bak = new File(file.getParent(), FILE_NAME + ".pre-import.bak");
                try (FileOutputStream fos = new FileOutputStream(bak)) {
                    fos.write(readAll(file));
                }
            }
            entries.clear();
            entries.addAll(loaded);
            save();
            return "Impor berhasil: " + loaded.size() + " entri (daftar lama dicadangkan .pre-import.bak)";
        } catch (Exception e) {
            return "Impor gagal (daftar saat ini tidak berubah): " + e.getMessage();
        }
    }
}
