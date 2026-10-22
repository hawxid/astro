package id.hawx.astro.engine;

import id.hawx.astro.R;
import id.hawx.astro.utils.Core;

public final class EngineStatus {

    public final String label;
    public final int colorRes;
    public final String badge;
    public final boolean isVm;
    public final boolean ready;

    private EngineStatus(String label, int colorRes, String badge, boolean isVm, boolean ready) {
        this.label = label;
        this.colorRes = colorRes;
        this.badge = badge;
        this.isVm = isVm;
        this.ready = ready;
    }

    public static EngineStatus current(Core core, boolean chrootMounted) {
        return current(core, chrootMounted, false);
    }

    public static EngineStatus current(Core core, boolean chrootMounted, boolean probeGuest) {
        return new EngineStatus(
                chrootMounted ? "Chroot mounted" : "Chroot detached",
                chrootMounted ? R.color.accent_chroot : R.color.status_offline,
                "CHROOT", false, chrootMounted);
    }
}
