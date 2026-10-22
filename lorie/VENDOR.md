# VENDOR.md — :lorie di Astro

Snapshot dari **termux/termux-x11** (repo https://github.com/termux/termux-x11),
commit `5986cfc` (2026-10-20), diambil 2026-10-21.
Isi: modul `lorie/` utuh (termasuk sumber submodule cpp: xserver, pixman, libx11, dll)
+ `shell-loader/stub/` (stub API framework, compileOnly). Lisensi: GPL v3 (lihat
`LICENSE.upstream` di direktori ini) — kompatibel dengan Astro (GPL v3).
Modul `shell-loader` utama dan app `lorie-app` SENGAJA tidak diambil: Astro sendiri
yang menjalankan CmdEntryPoint dari shell root-nya.

## Vendor patch (seluruh perubahan dari upstream)

1. `lorie/build.gradle` — kotlin-stdlib-jdk8 2.4.21 → 1.8.22 dan
   lifecycle-viewmodel-ktx 2.11.0 → 2.6.2 (metadata Kotlin harus bisa dibaca
   compiler Kotlin 1.8.22 milik Astro).
2. `lorie/build.gradle` — tambah `implementation project(':terminal')`
   (syarat patch no. 4).
3. `lorie/version.gradle` — termuxX11NdkVersion 29.0.14206865 → 25.1.8937393
   (CI Astro hanya memasang NDK r25; satu toolchain untuk semua modul).
4. `lorie/.../LorieApp.java` — `extends Application` →
   `extends com.stryker.terminal.App` (rantai Application disambung:
   AstroApp → LorieApp → terminal App → Application; MainActivity melakukan
   cast `getApplication()` ke LorieApp sehingga ini wajib).
5. Di luar direktori ini: `settings.gradle` mendaftarkan `:lorie` +
   `:shell-loader:stub`; `app/build.gradle` depend ke `:lorie` (syarat
   host-detection di build.gradle lorie); `AstroApp` extends LorieApp.
6. **Patch build upstream DITERAPKAN DULUAN ke sumber vendor** (28 berkas):
   ketujuh `src/main/cpp/patches/*.patch` (pixman, xkbcomp, xkbfile, x11,
   Xtrans, libepoxy, xserver) biasanya diterapkan CMake di tempat saat
   configure — di build multi-ABI, configure paralel menulis berkas yang
   sama bersamaan dan merusaknya (build 39568059860: definisi ganda
   `lorieRecheckGpuCopies` + baris terpotong di `present_scmd.c`).
   Dengan patch sudah diterapkan di snapshot ini, penjaga bawaan CMake
   (`patch -R --dry-run`) mendeteksi keadaan terapan dan tidak menulis
   apa pun — balapan hilang. Verifikasi lokal: semua patch menempel
   bersih (0 reject), `-R --dry-run` OK untuk ketujuhnya,
   `present_scmd.c` hasil identik byte-per-byte dengan penerapan bersih.
7. `app/src/main/AndroidManifest.xml` — `tools:replace="android:name"` pada
   `<application>` (+ deklarasi `xmlns:tools`): manifest library lorie
   mendeklarasikan `android:name=".LorieApp"` dan merger menolak konflik
   dengan `.AstroApp` milik app (build 39583230666). Nilai app yang menang;
   rantai kelasnya memang AstroApp → LorieApp.
8. `lorie/src/main/AndroidManifest.xml` — filter MAIN/LAUNCHER pada
   `com.termux.x11.MainActivity` DICABUT (2026-10-21, pasca-M2): viewer
   tidak lagi punya ikon "Termux:X11" sendiri di launcher HP. Satu pintu X
   = X11PocActivity "Astro X11"; viewer dibuka intent eksplisit se-package
   (override `exported=false` di manifest app tetap berlaku & kini benar:
   tanpa filter launcher tidak ada ikon hantu "app tidak terinstall").
9. `MainActivity.setImeHeight` — log diagnostik IME (2026-10-22):
   setiap perubahan tinggi IME dicatat ke `/sdcard/Astro/x11-debug.log`
   (tinggi yang dipercaya app + tinggi decor) untuk mendiagnosis bar
   softkey yang nongkrong kejauhan di atas keyboard di ROM Android 17.
   Murni pencatatan; perilaku tidak berubah. Dicabut setelah diagnosis
   tuntas.
10. Keyboard DINAMIS via OS (2026-10-22, arah Hawx): manifest viewer
    `adjustNothing` -> `adjustResize` + `ImeHeightProvider` dimatikan
    di `MainActivity.onCreate`. Diagnosis: log membuktikan IME=837px
    waras, tapi bar nangkring di ~1660px dari bawah = 2x — jendela
    rupanya tetap di-resize OS (Android 17) sementara lorie menghitung
    IME sekali lagi secara manual. Dengan patch ini permukaan menyusut
    native mengikuti keyboard, bar gravity-bottom ikut sendiri,
    imeHeight kekal 0. Konsekuensi yang diterima: display X re-seed
    tiap keyboard buka/tutup (jendela openbox tertata ulang) — itu
    perilaku dinamis yang diminta.

## Cara jalan (resep resmi chroot, README upstream)

Server dijalankan dari shell root dengan TMPDIR menunjuk ke tmp chroot:

```
export TMPDIR=/data/local/astro/tmp
export XKB_CONFIG_ROOT=<chroot>/usr/share/X11/xkb   (dari sisi host: /data/local/astro/usr/share/X11/xkb)
export CLASSPATH=$(pm path id.hawx.astro | cut -d: -f2)
/system/bin/app_process / --nice-name=termux-x11 com.termux.x11.CmdEntryPoint :0
```

Stop: `pkill -f termux-x11`. Socket X lahir di `$TMPDIR/.X11-unix/X0`.
Catatan: resep upstream menulis `setenforce 0` untuk topologi Termux tanpa root;
di Astro proses berjalan di domain root KernelSU — PoC membuktikan perlu/tidak.
