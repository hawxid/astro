package id.hawx.astro.hid.keymap;

import id.hawx.astro.hid.report.KeyReport;

public final class KeyEntry {

    public final int modifier;
    public final int keycode;

    public KeyEntry(int modifier, int keycode) {
        this.modifier = modifier;
        this.keycode = keycode;
    }

    public KeyReport toReport() {
        return KeyReport.singleKey(modifier, keycode);
    }
}
