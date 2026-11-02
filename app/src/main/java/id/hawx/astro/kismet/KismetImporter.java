package id.hawx.astro.kismet;

import android.content.Context;

import androidx.annotation.NonNull;

import id.hawx.astro.geomac.GeoLookup;
import id.hawx.astro.geomac.model.GeoPin;
import id.hawx.astro.geomac.store.GeoStore;
import id.hawx.astro.utils.Core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Importer Kismet -> pin Atlas (penulis langsung ke pin store,
 * MELEWATI UI pencarian manual sepenuhnya — BSSID tidak pernah
 * diketik; itulah inti permintaan user 2026-11-02).
 *
 * Sumber: file .kismet TERBARU di direktori log panel
 * (/tmp/astro_kismet — panel memakai cwd ini; PoC memakai
 * /tmp/gpspoc, tapi yang dibaca importer HANYA run panel).
 * Dump dibaca lewat script asset (stdlib Python, pola delivery
 * Frida: asset -> filesDir -> copyFile ke chroot) karena query
 * inline `python3 -c` lewat dua lapis quoting (string Java ->
 * shell chroot) rapuh; script-nya bisa di-py_compile.
 * Hanya tiga kolom yang dibaca — devmac, avg_lat, avg_lon —
 * karena HANYA ketiganya yang terbukti ada di KismetDB v11
 * lewat PoC (row D6:C5:DB:99:1E:66, 48.1173/11.516666667).
 * SSID sengaja TIDAK dibaca (tidak ada kolom SSID yang
 * terbukti); pin tanpa SSID menampilkan BSSID sebagai judul
 * (fallback yang sudah ada di GeoMac).
 *
 * Logika per BSSID — SERAGAM, tidak perlu tahu toggle GPS
 * (data yang bicara; keputusan user 2026-11-02):
 * 1. Lookup database GeoMac dulu (AppleWloc + fallback mylnikov,
 *    fungsi yang SAMA dengan pencarian manual): kena -> pin di
 *    TITIK DATABASE.
 * 2. Bila tidak kena DAN record punya lokasi deteksi non-nol
 *    (avg_lat/avg_lon bukan 0,0) -> pin di TITIK DETEKSI dengan
 *    label jujur "lokasi deteksi, bukan titik pasti AP".
 * 3. Bila keduanya tidak ada -> LEWATI (tidak pernah ada pin
 *    tanpa koordinat; skip dihitung di summary, bukan hilang
 *    diam-diam).
 * Konsekuensi otomatis yang dikunci user: capture GPS-OFF
 * (lokasi deteksi kosong) -> hanya yang ada di database yang
 * jadi pin; capture GPS-ON -> semua dapat pin (DB atau deteksi).
 * Perangkat BTLE tidak punya database sama sekali, jadi hanya
 * pernah dapat pin deteksi (atau dilewati bila GPS OFF) —
 * logika yang sama, tanpa kasus khusus.
 *
 * Dedup: kunci = BSSID. Satu .kismet kumulatif, jadi import
 * berulang atas file yang sama tidak menggandakan pin:
 * GeoStore.upsert menimpa pin lama (bukan menambah duplikat).
 * Perilaku rank GeoStore berlaku: pin CRACKED_* (rank lebih
 * tinggi) mempertahankan posisinya, hanya timestamp/SSID yang
 * disegarkan — itu pengecualian satu-satunya atas "update
 * posisi", dan disengaja (pin hasil crack tidak ditimpa pin
 * observasi).
 *
 * Kategori pin: KISMET (baru, rank 2 = SCAN — observasi live).
 * Label dialog "Kismet", warna cyan info (#22D3EE, beda dari
 * semua kategori lama); note membedakan titik database vs
 * lokasi deteksi, dan dialog pin menampilkan note itu.
 *
 * Threading: importNewest() melakukan I/O jaringan (lookup)
 * dan shell chroot — WAJIB thread latar. Tidak pernah throw
 * ke pemanggil UI: semua kegagalan dikembalikan lewat
 * Result.error.
 */
public final class KismetImporter {

    /** Hasil import untuk summary toast (skip terlihat, bukan hilang). */
    public static final class Result {
        /** BSSID yang belum ada di store sebelum import. */
        public final int added;
        /** BSSID yang sudah ada (posisi/label disegarkan). */
        public final int updated;
        /** BSSID dilewati (tidak di database + tidak ada deteksi). */
        public final int skipped;
        /** Pesan gagal, atau null bila import berjalan (meski 0 pin). */
        public final String error;
        /** True bila tidak ada file .kismet sama sekali (bukan gagal baca). */
        public final boolean noCapture;

        Result(int added, int updated, int skipped, String error, boolean noCapture) {
            this.added = added;
            this.updated = updated;
            this.skipped = skipped;
            this.error = error;
            this.noCapture = noCapture;
        }
    }

    /** Script dump asset (delivery pola Frida). */
    private static final String DUMP_ASSET = "astro_kismet_devices.py";
    /** Lokasi script di chroot (di /tmp, BUKAN di direktori log
     *  supaya tidak ikut glob *.kismet). */
    private static final String DUMP_CHROOT_PATH = "/tmp/astro_kismet_devices.py";
    /** Root chroot (sama seperti FridaManager). */
    private static final String CHROOT_ROOT = "/data/local/astro/release";
    /** Direktori log panel (cwd saat launch Kismet). */
    private static final String LOG_DIR = "/tmp/astro_kismet";

    /** Note pin titik database (jujur: bukan observasi Kismet). */
    private static final String NOTE_DB = "Kismet · titik database GeoMac";
    /** Note pin lokasi deteksi (jujur: bukan titik pasti AP). */
    private static final String NOTE_DETECTION =
            "Kismet · lokasi deteksi, bukan titik pasti AP";

    private final Context appContext;
    private final Core core;

    public KismetImporter(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.core = new Core(appContext);
    }

    /**
     * Import file .kismet terbaru. Sinkron, WAJIB thread latar.
     * Tidak pernah throw: kegagalan (tidak ada capture, DB
     * tidak terbaca, asset gagal) dikembalikan sebagai error.
     */
    public Result importNewest() {
        ArrayList<String> lines;
        try {
            stageDumpScript();
            lines = core.customChrootCommand(
                    "python3 " + DUMP_CHROOT_PATH + " " + LOG_DIR, true);
        } catch (Exception e) {
            return new Result(0, 0, 0, "gagal membaca capture: "
                    + (e.getMessage() != null ? e.getMessage() : "error"), false);
        }
        if (lines == null || lines.isEmpty()) {
            return new Result(0, 0, 0, "tidak ada capture Kismet", true);
        }
        // Dedupe dalam satu file (satu baris per BSSID, terakhir menang).
        LinkedHashMap<String, double[]> rows = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            if (line.equals("NOFILE")) {
                return new Result(0, 0, 0, "tidak ada capture Kismet", true);
            }
            if (line.startsWith("DBERROR:")) {
                return new Result(0, 0, 0, "database capture tidak terbaca: "
                        + line.substring("DBERROR:".length()), false);
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 3) continue;
            String bssid = parts[0].trim();
            if (bssid.isEmpty()) continue;
            try {
                double lat = Double.parseDouble(parts[1].trim());
                double lon = Double.parseDouble(parts[2].trim());
                rows.put(bssid.toUpperCase(), new double[]{lat, lon});
            } catch (NumberFormatException ignored) {
                // Baris koordinat rusak dilewati (bukan digagalkan).
            }
        }
        if (rows.isEmpty()) {
            return new Result(0, 0, 0, null, false);
        }
        GeoStore store = new GeoStore(appContext);
        Set<String> existing = new HashSet<>();
        try {
            for (GeoPin p : store.all()) {
                if (p.bssid != null) existing.add(p.bssid);
            }
        } catch (Exception ignored) {
        }
        int added = 0;
        int updated = 0;
        int skipped = 0;
        long now = System.currentTimeMillis();
        for (String bssid : rows.keySet()) {
            double[] det = rows.get(bssid);
            double lat = det[0];
            double lon = det[1];
            // 1. Database dulu (fungsi yang sama dengan pencarian manual).
            double[] db = null;
            try {
                db = GeoLookup.coordsFor(core, bssid);
            } catch (Exception ignored) {
            }
            GeoPin pin = null;
            if (db != null) {
                pin = new GeoPin(bssid, "", db[0], db[1],
                        GeoPin.Category.KISMET, now, NOTE_DB);
            } else if (hasDetection(lat, lon)) {
                // 2. Fallback lokasi deteksi (hanya bila non-nol).
                pin = new GeoPin(bssid, "", lat, lon,
                        GeoPin.Category.KISMET, now, NOTE_DETECTION);
            } else {
                // 3. Tidak ada koordinat dari mana pun -> lewati.
                skipped++;
                continue;
            }
            try {
                store.upsert(pin);
            } catch (Exception ignored) {
                skipped++;
                continue;
            }
            if (existing.contains(bssid)) {
                updated++;
            } else {
                added++;
                existing.add(bssid);
            }
        }
        return new Result(added, updated, skipped, null, false);
    }

    /** Lokasi deteksi ada = bukan sentinel 0,0 Kismet ("tidak ada lokasi"). */
    private static boolean hasDetection(double lat, double lon) {
        return lat != 0.0 || lon != 0.0;
    }

    /**
     * Salin script dump asset -> filesDir -> chroot. Selalu
     * ditimpa ulang tiap import (KB-an, murah) supaya tidak ada
     * risiko script basi dari build lama — beda dari Frida yang
     * memakai version gate, karena script ini tidak diedit user.
     */
    private void stageDumpScript() throws IOException {
        File filesScript = new File(appContext.getFilesDir(), DUMP_ASSET);
        try (InputStream in = appContext.getAssets().open(DUMP_ASSET);
             OutputStream os = new FileOutputStream(filesScript)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
            os.flush();
        }
        core.copyFile(filesScript.getAbsolutePath(), CHROOT_ROOT + DUMP_CHROOT_PATH);
        core.customChrootCommand("chmod +x " + DUMP_CHROOT_PATH, true);
    }
}
