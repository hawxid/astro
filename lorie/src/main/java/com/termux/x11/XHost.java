package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.view.KeyEvent;

import com.termux.x11.utils.TermuxX11ExtraKeys;

/**
 * ASTRO VENDOR ADDITION (2026-10-22, PoC jendela X): tuan rumah bagi sebuah
 * {@link LorieView} + tumpukan inputnya.
 *
 * Secara historis LorieView, TouchInputHandler, dan InputEventSender memegang
 * referensi konkret ke {@link MainActivity}. Interface ini memutus kopel itu:
 * MainActivity mengimplementasikannya (perilaku lama tidak berubah), dan host
 * tertanam Astro (jendela floating di desktop) mengimplementasikannya juga —
 * sehingga satu LorieView bisa hidup di dalam jendela Astro, bukan hanya di
 * activity layar-penuh upstream.
 */
public interface XHost {
    /** View permukaan X milik host ini. */
    LorieView getLorieView();

    /** Preferensi tampilan (dibangun LorieApp.getPrefs). */
    Prefs getPrefs();

    /** Tag dokumen server X yang dilayani host ini ("" = display bawaan). */
    String getTag();

    /** Context untuk kebutuhan framework (Intent, resource, dsb). */
    Context context();

    /** Layanan sistem (Context.getSystemService). */
    Object getSystemService(String name);

    /** Metrik display riil (sama seperti MainActivity.getRealMetrics). */
    void getRealMetrics(DisplayMetrics m);

    /** Host selesai: activity finish / jendela ditutup. */
    void finishHost();

    /** Tampilkan/sembunyikan keyboard lunak untuk view X. */
    void toggleKeyboardVisibility();

    /** Tampilkan/sembunyikan bar tombol ekstra bawaan (versi tertanam: strip). */
    void toggleExtraKeys(boolean visible, boolean saveState);

    /** Balik keadaan bar tombol ekstra. */
    void toggleExtraKeys();

    /** Saring + teruskan key event keras (jalur MainActivity.handleKey). */
    boolean handleKey(KeyEvent e);

    /** Bar tombol ekstra bawaan, atau null bila host memakai strip sendiri. */
    TermuxX11ExtraKeys extraKeys();

    /** Perilaku bar ekstra ala Termux (unset modifier setelah commit). */
    boolean useTermuxEKBarBehaviour();

    /** Segarkan daftar perangkat input (TouchInputHandler.refreshInputDevices). */
    void refreshInputDevices();

    /** Keadaan keyboard eksternal berubah (untuk UI host). */
    void setExternalKeyboardConnected(boolean connected);

    /** Penangkapan pointer aktif/nonaktif (untuk UI host). */
    void setCapturingEnabled(boolean enabled);

    /** Jalankan activity dari host (mis. layar preferensi). */
    void startActivityHost(Intent intent);
}
