package id.hawx.astro.x11.window;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.stryker.terminal.frontend.session.view.extrakey.XKeyRouter;

import java.util.HashMap;
import java.util.Map;

import id.hawx.astro.AstroApp;
import id.hawx.astro.x11.X11DebugLog;
import id.hawx.astro.x11.XSessionManager;

/**
 * Rumah kedua jendela melayang: OVERLAY sistem (keputusan Hawx
 * 2026-10-24 — inti motivasi floating: panggilan dari terminal
 * muncul melayang DI TEMPAT, tanpa beralih ke desktop). Sifat ini
 * HANYA buat kelahiran terminal (xrun); kelahiran launcher desktop
 * tetap di XWindowLayer seperti biasa. Yang manggil terakhir jadi
 * tuan rumah: show() menutup jendela layer display yang sama, dan
 * XWindowLayer.openWindow menutup overlay display yang sama.
 *
 * Strip softkey TIDAK diduplikasi di sini: bar bawaan terminal
 * melayani jendela ini lewat XKeyRouter (modul terminal).
 * Kontainer WindowManager seukuran jendela persis (sentuhan di
 * luar jendela jatuh ke aplikasi di bawahnya); drag/resize jendela
 * disinkronkan ke parameter kontainer; maximize mengembang
 * kontainer jadi layar penuh (matematika maximize/drag jendela
 * membaca ukuran parent = kontainer).
 */
public class XOverlayWindows implements XFloatingWindow.Listener, XWindowHost.Listener {

    private static final Map<Integer, XOverlayWindows> sByDisplay = new HashMap<>();
    private static XOverlayWindows sActive;

    private final Activity activity;
    private final WindowManager wm;
    private final int display;
    private final XWindowHost host;
    private final XFloatingWindow win;
    private final FrameLayout container;
    private final WindowManager.LayoutParams wlp;
    private boolean attached;
    private boolean syncing;
    private boolean wasMaximized;
    private int lastMX, lastMY;
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable settle = this::settleMargins;

    // ---------------- statis ----------------

    public static boolean isShowing(int display) {
        return sByDisplay.containsKey(display);
    }

    /** Sembunyikan overlay display ini (server tetap hidup). */
    public static void hideForDisplay(int display) {
        XOverlayWindows o = sByDisplay.get(display);
        if (o != null) o.hide();
    }

    /**
     * Tampilkan jendela overlay buat display ini.
     *
     * @return false bila tak bisa tampil (izin/foreground tak ada) —
     *         pemanggil silakan jatuh ke jalur desktop.
     */
    public static boolean show(Context ctx, int display, String title) {
        if (!Settings.canDrawOverlays(ctx)) {
            X11DebugLog.log("OVERLAY", "izin tampil-di-atas BELUM ada");
            Activity act = AstroApp.foregroundActivity();
            if (act != null) {
                Toast.makeText(act, "Aktifkan izin \"Tampil di atas aplikasi lain\" "
                        + "buat Astro, lalu ulangi", Toast.LENGTH_LONG).show();
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()));
                act.startActivity(i);
            }
            return false;
        }
        Activity act = AstroApp.foregroundActivity();
        if (act == null) {
            X11DebugLog.log("OVERLAY", "tak ada activity foreground — batal");
            return false;
        }
        XOverlayWindows existing = sByDisplay.get(display);
        if (existing != null) {
            existing.raise();
            return true;
        }
        XWindowLayer.closeForDisplay(display); // rumah lama melepas
        new XOverlayWindows(act, display, title).attach();
        return true;
    }

    // ---------------- instance ----------------

    private XOverlayWindows(Activity act, int display, String title) {
        this.activity = act;
        this.display = display;
        wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);

        host = new XWindowHost(act, display);
        host.setListener(this);
        win = new XFloatingWindow(act, host, title);
        win.setListener(this);

        container = new FrameLayout(act);
        int w = (int) (m.widthPixels * 0.88f);
        int h = (int) (m.heightPixels * 0.55f);
        win.setLayoutParams(new FrameLayout.LayoutParams(w, h));
        container.addView(win);

        wlp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        wlp.x = dp(14) + (display - 1) * dp(26);
        wlp.y = dp(70) + (display - 1) * dp(30);
        win.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> syncContainer());
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void attach() {
        sByDisplay.put(display, this);
        wm.addView(container, wlp);
        attached = true;
        host.attach();
        setActive();
        X11DebugLog.log("OVERLAY", "tampil :" + display);
    }

    private void raise() {
        if (!attached) {
            wm.addView(container, wlp);
            attached = true;
        }
        setActive();
    }

    /** — : lepas dari WindowManager saja; sesi X tetap hidup. */
    public void hide() {
        ui.removeCallbacks(settle);
        if (attached) {
            wm.removeView(container);
            attached = false;
        }
        if (sActive == this) {
            sActive = null;
            XKeyRouter.clear();
        }
        X11DebugLog.log("OVERLAY", "sembunyi :" + display);
    }

    /** ✕ / host minta tutup: lepas total (server diurus pemanggil). */
    private void close() {
        hide();
        host.detach();
        sByDisplay.remove(display);
    }

    private void setActive() {
        boolean changed = sActive != this;
        sActive = this;
        registerRouter();
        if (changed) {
            // Permukaan SurfaceView menumpuk ikut urutan lahir —
            // lahirkan ulang permukaan jendela aktif biar paling atas.
            host.view().setVisibility(android.view.View.INVISIBLE);
            host.view().post(() -> host.view().setVisibility(android.view.View.VISIBLE));
        }
    }

    /** Bar terminal melayani jendela ini selama dia aktif.
     *  Jalur = LorieView.sendKeyEvent(scanCode, keyCode, down) —
     *  kanal hardware-key yang DIPAKAI EK Lorie sendiri. Versi
     *  pertama router pakai dispatchKeyEvent generik: event-nya
     *  tidak sampai ke server X (temuan Hawx: softkey ga ngaruh). */
    private void registerRouter() {
        final XWindowHost h = host;
        XKeyRouter.keySink = code -> {
            h.view().sendKeyEvent(0, code, true);
            h.view().sendKeyEvent(0, code, false);
        };
        XKeyRouter.textSink = text -> h.view().dispatchKeyEvent(new KeyEvent(
                SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
        XKeyRouter.modifierSink = (name, down) -> {
            int code = "Ctrl".equals(name) ? KeyEvent.KEYCODE_CTRL_LEFT
                    : "Alt".equals(name) ? KeyEvent.KEYCODE_ALT_LEFT
                    : KeyEvent.KEYCODE_SHIFT_LEFT;
            h.view().sendKeyEvent(0, code, down);
        };
    }

    /**
     * Sinkronkan parameter WindowManager kontainer dengan geometri
     * yang diminta jendela. Margin jendela bersifat KUMULATIF dari
     * awal gestur (model DragListener-nya) — kontainer menyerap
     * DELTA margin tiap perubahan, dan ukurannya = ukuran jendela +
     * margin (permukaan harus menampung anak yang bergeser).
     * 120 md setelah perubahan terakhir (gestur selesai), settle
     * menolkan margin di objek LayoutParams yang sama: geometri
     * visual identik, keadaan kembali bersih buat gestur berikut.
     */
    private void syncContainer() {
        if (syncing || !attached) return;
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) win.getLayoutParams();
        syncing = true;
        try {
            if (win.isMaximized()) {
                wasMaximized = true;
                ui.removeCallbacks(settle);
                DisplayMetrics m = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(m);
                wlp.x = 0;
                wlp.y = 0;
                wlp.width = m.widthPixels;
                wlp.height = m.heightPixels;
                wm.updateViewLayout(container, wlp);
                lp.width = m.widthPixels - dp(12);
                lp.height = m.heightPixels - dp(12);
                lp.leftMargin = dp(6);
                lp.topMargin = dp(6);
                lastMX = lp.leftMargin;
                lastMY = lp.topMargin;
                win.setLayoutParams(lp);
                return;
            }
            if (wasMaximized) {
                // Jendela memulihkan rect tersimpannya sendiri; adopsi
                // marginnya apa adanya (tanpa delta), posisi kontainer
                // tak berubah — posisi visual kembali persis.
                wasMaximized = false;
                lastMX = lp.leftMargin;
                lastMY = lp.topMargin;
            } else {
                wlp.x += lp.leftMargin - lastMX;
                wlp.y += lp.topMargin - lastMY;
                lastMX = lp.leftMargin;
                lastMY = lp.topMargin;
            }
            wlp.width = lp.width + lp.leftMargin;
            wlp.height = lp.height + lp.topMargin;
            wm.updateViewLayout(container, wlp);
            ui.removeCallbacks(settle);
            ui.postDelayed(settle, 120);
        } finally {
            syncing = false;
        }
    }

    /** Gestur selesai: nolkan margin (objek sama), kontainer pas. */
    private void settleMargins() {
        if (!attached || win.isMaximized()) return;
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) win.getLayoutParams();
        if (lp.leftMargin == 0 && lp.topMargin == 0) return;
        wlp.width = lp.width;
        wlp.height = lp.height;
        lp.leftMargin = 0;
        lp.topMargin = 0;
        lastMX = 0;
        lastMY = 0;
        win.setLayoutParams(lp);
        wm.updateViewLayout(container, wlp);
    }

    // ---------------- XFloatingWindow.Listener ----------------

    @Override
    public void onFocusGained(XFloatingWindow w) {
        host.view().requestFocus();
        setActive();
    }

    @Override
    public void onContentTapped(XFloatingWindow w) {
        // Fokus saja — keyboard hanya dari tombol ⌨. requestFocus
        // eksplisit: tanpa ini LorieView tak terikat IME/input.
        host.view().requestFocus();
        setActive();
    }

    @Override
    public void onMinimizeClicked(XFloatingWindow w) {
        hide();
    }

    @Override
    public void onCloseClicked(XFloatingWindow w) {
        close();
        XSessionManager.get(activity).stopDisplay(display, null);
    }

    // ---------------- XWindowHost.Listener ----------------

    @Override
    public void onCloseRequested(XWindowHost h) {
        close();
    }

    @Override
    public void onConnectionChanged(XWindowHost h, boolean connected) {
        win.setConnectedDot(connected);
    }

    @Override
    public void onStripVisibilityRequested(XWindowHost h, boolean visible) {
        // Overlay TANPA strip: keyboard layar diurus host sendiri;
        // tombol khususnya bar bawaan terminal via XKeyRouter.
        X11DebugLog.log("OVERLAY", "strip diminta " + visible + " — diabaikan (bar terminal)");
    }
}
