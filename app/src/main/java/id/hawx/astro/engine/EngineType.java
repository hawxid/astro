package id.hawx.astro.engine;

import android.content.Context;
import android.os.Build;

import id.hawx.astro.utils.Core;

public enum EngineType {
    CHROOT,
    ROOTLESS,
    UML;

    public static final String PREF_KEY = "engine_type";

    public static final String PREF_VERIFIED = "engine_boot_verified";

    public static final String PREF_FORCED = "engine_forced_unsupported";

    // AMPUTASI VM/QEMU — FASE 1 (perintah Hawx 2026-10-22: "sisakan
    // Astro sebagai front-end chroot-nya, VM itu bloated"). Mode
    // dikunci CHROOT permanen di satu-satunya sumber kebenaran ini:
    // semua cabang isRootless()/guest di seluruh app (yang memang
    // bermuara ke kelas ini) mati tanpa disentuh satu per satu, dan
    // biner libqemu/libuml dkk sudah dihapus dari repo. Enum + pref
    // dipertahankan supaya data lama tidak korup; persist() memaksa
    // CHROOT. Fase 2 (nanti): sapu kode engine yang mati.
    public static EngineType active(Core core) {
        return CHROOT;
    }

    public static boolean isRootless(Core core) {
        return false;
    }

    public static boolean isVm(Core core) {
        return false;
    }

    public static boolean isUml(Core core) {
        return false;
    }

    public static boolean isChosen(Core core) {
        if (core == null) return false;
        String v = core.getString(PREF_KEY);
        return v != null && !v.isEmpty();
    }

    public static EngineType configured(Core core) {
        return CHROOT;
    }

    public static void persist(Core core, EngineType type) {
        type = CHROOT; // amputasi fase 1: pilihan VM tidak lagi berlaku
        core.putString(PREF_KEY, type.name());
        try {
            java.io.File flag = RootlessPaths.activeFlag(core.context);
            if (type == ROOTLESS || type == UML) {
                java.io.File dir = flag.getParentFile();
                if (dir != null && !dir.exists())
                    dir.mkdirs();
                try (java.io.FileWriter w = new java.io.FileWriter(flag, false)) { w.write("1"); }
            } else if (flag.exists()) {
                flag.delete();
            }
        } catch (Exception ignored) {}
    }

    public static boolean rootlessSupported(Context context) {
        if (Build.SUPPORTED_ABIS == null) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }
}
