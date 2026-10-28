package id.hawx.astro.keysmouse;

import android.content.Context;

import androidx.annotation.NonNull;

import id.hawx.astro.hid.capability.HidCapabilities;
import id.hawx.astro.hid.capability.HidCapabilityProbe;
import id.hawx.astro.hid.configfs.GadgetProfile;
import id.hawx.astro.hid.configfs.UsbGadgetController;
import id.hawx.astro.hid.report.HidReportStream;
import id.hawx.astro.hid.report.KeyReport;
import id.hawx.astro.hid.report.MouseReport;
import id.hawx.astro.usbarsenal.DefaultProfiles;

import java.io.IOException;
import java.util.List;

/**
 * USB gadget transport on top of the existing hidg nodes.
 *
 * If no keyboard+mouse gadget is active yet, the default
 * "HID Keyboard + Mouse" profile is applied (this replaces the current USB
 * configuration, like USB Arsenal does). On close, a profile applied by us
 * is unbound again so the port goes back to Android.
 */
public final class UsbKeysMouseTransport implements KeysMouseTransport {

    private final Context appContext;
    private HidReportStream kb;
    private HidReportStream mouse;
    private boolean open;
    private boolean appliedByUs;
    private int heldButtons;

    public UsbKeysMouseTransport(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public String name() {
        return "USB";
    }

    @Override
    public synchronized void open() throws IOException {
        if (open) return;
        UsbGadgetController gadget = new UsbGadgetController(appContext);
        HidCapabilities caps = new HidCapabilityProbe(appContext).probe();
        if (!caps.canInjectKeyboard()) {
            GadgetProfile combo = findComboProfile();
            if (combo != null && gadget.apply(combo)) {
                appliedByUs = true;
                caps = new HidCapabilityProbe(appContext).probe();
            }
        }
        if (!caps.canInjectKeyboard()) {
            throw new IOException("Gadget keyboard belum aktif — terapkan profil "
                    + "HID Keyboard + Mouse di USB Arsenal, lalu hubungkan lagi.");
        }
        gadget.chmodHidNodes();
        kb = HidReportStream.openKeyboard();
        try {
            if (caps.mouseNodePresent) {
                try {
                    mouse = HidReportStream.openMouse();
                } catch (IOException ignored) {
                    mouse = null;
                }
            }
        } catch (Exception ignored) {
            mouse = null;
        }
        heldButtons = 0;
        open = true;
    }

    @Override
    public synchronized void close() {
        if (!open) return;
        open = false;
        try {
            if (kb != null) kb.releaseAllKeys();
        } catch (Exception ignored) {
        }
        try {
            if (mouse != null) mouse.send(new MouseReport(0, 0, 0, 0).toBytes());
        } catch (Exception ignored) {
        }
        try {
            if (kb != null) kb.close();
        } catch (Exception ignored) {
        }
        try {
            if (mouse != null) mouse.close();
        } catch (Exception ignored) {
        }
        kb = null;
        mouse = null;
        heldButtons = 0;
        if (appliedByUs) {
            appliedByUs = false;
            try {
                new UsbGadgetController(appContext).unbind();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized boolean hasMouse() {
        return open && mouse != null;
    }

    @Override
    public void sendKey(int modifier, int keycode) throws IOException {
        HidReportStream stream = currentKeyboard();
        stream.sendKey(KeyReport.singleKey(modifier, keycode));
        stream.sendKey(KeyReport.EMPTY);
    }

    @Override
    public void sendMouseMove(int dx, int dy) throws IOException {
        HidReportStream stream = currentMouse();
        stream.sendMouse(new MouseReport(heldButtons, dx, dy, 0));
    }

    @Override
    public void sendMouseButton(int button, boolean pressed) throws IOException {
        HidReportStream stream = currentMouse();
        if (pressed) heldButtons |= button;
        else heldButtons &= ~button;
        stream.sendMouse(new MouseReport(heldButtons, 0, 0, 0));
    }

    @Override
    public void sendMouseClick(int button) throws IOException {
        sendMouseButton(button, true);
        sendMouseButton(button, false);
    }

    @Override
    public void sendMouseWheel(int delta) throws IOException {
        HidReportStream stream = currentMouse();
        stream.sendMouse(new MouseReport(heldButtons, 0, 0, delta));
    }

    private synchronized HidReportStream currentKeyboard() throws IOException {
        if (!open || kb == null) throw new IOException("Transport USB belum terhubung");
        return kb;
    }

    private synchronized HidReportStream currentMouse() throws IOException {
        if (!open || mouse == null) throw new IOException("Mouse belum tersedia (hidg1 hilang)");
        return mouse;
    }

    private static GadgetProfile findComboProfile() {
        List<GadgetProfile> profiles = DefaultProfiles.list();
        for (GadgetProfile p : profiles) {
            if ("HID Keyboard + Mouse".equals(p.name)) return p;
        }
        return null;
    }
}
