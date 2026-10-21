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
