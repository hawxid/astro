package id.hawx.astro.ota;

public final class AstroEndpoints {

    public static final String GITHUB_REPO = "https://github.com/hawxid/astro";

    public static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/hawxid/astro/main/stryker_manifest.json";

    public static final String FALLBACK_CHROOT_64 =
            "https://github.com/hawxid/astro/releases/download/chroot-650/chroot64-debian.tar.gz";

    // Hash + ukuran tarball fallback, disemat dari manifest hawxid/astro
    // (debian_v2 chroot64) — jalur fallback TIDAK BOLEH tanpa verifikasi.
    public static final String FALLBACK_CHROOT_64_SHA256 =
            "c67549857b7741e5f2c809e701c6f5cfcacb971fe4a76ad166fecb7b2d4a9d74";
    public static final long FALLBACK_CHROOT_64_SIZE = 296674820L;

    public static final String PREFS = "stryker_ota";

    private AstroEndpoints() {
    }
}
