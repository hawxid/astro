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
    private int homeX, homeY;

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
        homeX = wlp.x;
        homeY = wlp.y;
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

    /** Bar terminal melayani jendela ini selama dia aktif. */
    private void registerRouter() {
        final XWindowHost h = host;
        XKeyRouter.keySink = code -> {
            long t = SystemClock.uptimeMillis();
            h.view().dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            h.view().dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        };
        XKeyRouter.textSink = text -> h.view().dispatchKeyEvent(new KeyEvent(
                SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
        XKeyRouter.modifierSink = (name, down) -> {
            int code = "Ctrl".equals(name) ? KeyEvent.KEYCODE_CTRL_LEFT
                    : "Alt".equals(name) ? KeyEvent.KEYCODE_ALT_LEFT
                    : KeyEvent.KEYCODE_SHIFT_LEFT;
            long t = SystemClock.uptimeMillis();
            h.view().dispatchKeyEvent(new KeyEvent(t, t,
                    down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0));
        };
    }

    /**
     * Sinkronkan parameter WindowManager kontainer dengan geometri
     * yang diminta jendela (margins/ukuran FrameLayout-nya). Jendela
     * selalu mengisi kontainernya persis (margins dinolkan); gerakan
     * dipindah ke kontainer. Maximize = kontainer layar penuh.
     */
    private void syncContainer() {
        if (syncing || !attached) return;
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) win.getLayoutParams();
        syncing = true;
        try {
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
            if (win.isMaximized()) {
                if (!wasMaximized) {
                    homeX = wlp.x;
                    homeY = wlp.y;
                }
                wasMaximized = true;
                wlp.x = 0;
                wlp.y = 0;
                wlp.width = m.widthPixels;
                wlp.height = m.heightPixels;
                wm.updateViewLayout(container, wlp);
                win.setLayoutParams(new FrameLayout.LayoutParams(
                        m.widthPixels - dp(12), m.heightPixels - dp(12)));
                FrameLayout.LayoutParams nlp =
                        (FrameLayout.LayoutParams) win.getLayoutParams();
                nlp.leftMargin = dp(6);
                nlp.topMargin = dp(6);
                win.setLayoutParams(nlp);
                return;
            }
            if (wasMaximized) {
                wasMaximized = false;
                wlp.x = homeX;
                wlp.y = homeY;
            } else {
                wlp.x += lp.leftMargin;
                wlp.y += lp.topMargin;
                homeX = wlp.x;
                homeY = wlp.y;
            }
            wlp.width = lp.width;
            wlp.height = lp.height;
            wm.updateViewLayout(container, wlp);
            if (lp.leftMargin != 0 || lp.topMargin != 0) {
                win.setLayoutParams(new FrameLayout.LayoutParams(lp.width, lp.height));
            }
        } finally {
            syncing = false;
        }
    }

    // ---------------- XFloatingWindow.Listener ----------------

    @Override
    public void onFocusGained(XFloatingWindow w) {
        setActive();
    }

    @Override
    public void onContentTapped(XFloatingWindow w) {
        setActive(); // fokus saja — keyboard hanya dari tombol ⌨
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
