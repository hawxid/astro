package id.hawx.astro.x11.window;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.stryker.terminal.frontend.session.view.extrakey.XKeyRouter;

import java.util.HashMap;
import java.util.Map;

import id.hawx.astro.AstroApp;
import id.hawx.astro.x11.X11DebugLog;
import id.hawx.astro.x11.XSessionManager;

/**
 * Rumah jendela melayang kelahiran terminal (xrun) — METODE SAMA
 * PERSIS dengan desktop (gas Hawx 2026-10-24): layer View di dalam
 * window activity sendiri, BUKAN lagi WindowManager overlay sistem.
 * Dulu overlay sistem (TYPE_APPLICATION_OVERLAY) gagal diikat di
 * atas softkey justru karena beda layer/window dengan terminal:
 * dua window = dua otoritas layout yang tarik-menarik (jitter).
 * Kini jendela ini anak decor NeoTermActivity: satu window, satu
 * otoritas layout — keyboard buka, layout activity bergerak, dan
 * jendela dikunci di atas softkey lewat margin di window yang
 * sama. Konsekuensi yang disetujui Hawx: jendela terminal TAK
 * bisa dibawa keluar activity terminal (keluar terminal =
 * jendela ikut tersembunyi; sesi X tetap hidup, balik ke
 * terminal muncul lagi). Izin "tampil di atas aplikasi lain"
 * tidak lagi dibutuhkan buat floating terminal.
 *
 * Yang manggil terakhir jadi tuan rumah: show() menutup jendela
 * layer desktop display yang sama, dan XWindowLayer.openWindow
 * menyembunyikan jendela terminal display yang sama.
 *
 * Strip softkey TIDAK diduplikasi: bar bawaan terminal melayani
 * jendela ini lewat XKeyRouter (modul terminal). Fokus IME pun
 * native satu window: ketuk LorieView = fokus ke xterm, ketuk
 * terminal = fokus ke terminal (tak ada lagi flip
 * NOT_FOCUSABLE / broadcast lepas-fokus antar window).
 */
public class XOverlayWindows implements XFloatingWindow.Listener, XWindowHost.Listener {

    private static final Map<Integer, XOverlayWindows> sByDisplay = new HashMap<>();
    private static XOverlayWindows sActive;

    private final Activity activity;
    private final int display;
    private final XWindowHost host;
    private final XFloatingWindow win;
    private final FrameLayout container;
    private boolean attached;
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    // ---------------- statis ----------------

    public static boolean isShowing(int display) {
        XOverlayWindows o = sByDisplay.get(display);
        return o != null && o.attached
                && o.win.getVisibility() == android.view.View.VISIBLE;
    }

    /**
     * Lepas input dari jendela terminal (dipanggil tombol keyboard
     * bar terminal). Dulu membalik flag NOT_FOCUSABLE window
     * overlay; kini satu window dengan terminal, jadi cukup
     * bersihkan router — fokus IME pindah native lewat
     * ketuk/showSoftInput.
     */
    public static void releaseFocus() {
        XKeyRouter.clear();
        X11DebugLog.log("OVERLAY", "router dilepas (tombol keyboard terminal)");
    }

    // ---- Patokan TUNGGAL pengunci di atas softkey ----
    // Patokannya: tepi bawah TerminalView (= tepi atas bar EK,
    // karena TerminalView layout_above extra_keys), dalam
    // koordinat decor. Penegaknya listener layout TerminalView
    // — sinkron tiap layout pass, termasuk selama animasi
    // keyboard, jadi jendela bergerak mulus mengikuti. Watcher
    // 400 md tinggal bertugas menempelkan ulang listener kalau
    // view TerminalView-nya berganti (ganti tab/dll), TANPA
    // menggerakkan jendela.
    private static android.view.View sAnchorView;
    private static android.view.ViewTreeObserver.OnGlobalLayoutListener sAnchorListener;

    private static void enforceAnchor() {
        if (sAnchorView == null || sByDisplay.isEmpty()) return;
        int[] r = terminalRectInDecor();
        if (r == null) return;
        int anchorT = r[1];
        int anchorB = r[3];
        for (XOverlayWindows o : sByDisplay.values()) {
            if (!o.attached || o.win.isMaximized()
                    || o.win.getVisibility() != android.view.View.VISIBLE) continue;
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) o.win.getLayoutParams();
            int bottom = lp.topMargin + lp.height;
            int top = lp.topMargin;
            if (bottom > anchorB) {
                lp.topMargin -= (bottom - anchorB);
                o.win.setLayoutParams(lp);
            } else if (top < anchorT) {
                lp.topMargin = anchorT;
                o.win.setLayoutParams(lp);
            }
        }
    }

    private static void attachAnchorListener(android.view.View tv) {
        if (tv == sAnchorView && sAnchorListener != null) return;
        detachAnchorListener();
        sAnchorView = tv;
        sAnchorListener = () -> enforceAnchor();
        tv.getViewTreeObserver().addOnGlobalLayoutListener(sAnchorListener);
    }

    private static void detachAnchorListener() {
        if (sAnchorView != null && sAnchorListener != null) {
            try {
                sAnchorView.getViewTreeObserver()
                        .removeOnGlobalLayoutListener(sAnchorListener);
            } catch (Throwable ignored) {
            }
        }
        sAnchorView = null;
        sAnchorListener = null;
    }

    private static final android.os.Handler sWatch =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean sWatchRunning;
    private static final Runnable sWatchTask = () -> {
        sWatchRunning = false;
        if (sByDisplay.isEmpty()) {
            detachAnchorListener();
            return;
        }
        Activity fg = AstroApp.foregroundActivity();
        if (fg != null
                && fg.getClass().getName().contains("NeoTermActivity")) {
            android.view.View tv = findTerminalView(fg.getWindow().getDecorView());
            if (tv != null && tv.getWidth() > 0 && tv.getHeight() > 0) {
                attachAnchorListener(tv);
            }
        }
        startAnchorWatch();
    };

    private static void startAnchorWatch() {
        if (!sWatchRunning && !sByDisplay.isEmpty()) {
            sWatchRunning = true;
            sWatch.postDelayed(sWatchTask, 400);
        }
    }

    /** Rect TerminalView dalam koordinat decor (null = tak ada). */
    private static int[] terminalRectInDecor() {
        Activity fg = AstroApp.foregroundActivity();
        if (fg == null
                || !fg.getClass().getName().contains("NeoTermActivity")) return null;
        android.view.View decorView = fg.getWindow().getDecorView();
        android.view.View tv = findTerminalView(decorView);
        if (tv == null || tv.getWidth() <= 0 || tv.getHeight() <= 0) return null;
        int[] dl = new int[2];
        decorView.getLocationOnScreen(dl);
        int[] tl = new int[2];
        tv.getLocationOnScreen(tl);
        return new int[]{
                tl[0] - dl[0], tl[1] - dl[1],
                tl[0] - dl[0] + tv.getWidth(), tl[1] - dl[1] + tv.getHeight()};
    }

    /** Sembunyikan jendela terminal display ini (server tetap hidup). */
    public static void hideForDisplay(int display) {
        XOverlayWindows o = sByDisplay.get(display);
        if (o != null) o.hide();
    }

    /**
     * Tampilkan jendela terminal buat display ini (layer decor).
     *
     * @return false bila foreground bukan NeoTermActivity —
     *         pemanggil silakan jatuh ke jalur desktop.
     */
    public static boolean show(Context ctx, int display, String title) {
        Activity act = AstroApp.foregroundActivity();
        if (act == null
                || !act.getClass().getName().contains("NeoTermActivity")) {
            X11DebugLog.log("OVERLAY", "foreground bukan terminal — jatuh ke desktop");
            return false;
        }
        XOverlayWindows existing = sByDisplay.get(display);
        if (existing != null) {
            if (existing.container.getParent() == null) {
                // Decor lama mati (activity recreate) — buang
                // zombie, buat baru (sesi X selamat, posisi reset).
                existing.host.detach();
                sByDisplay.remove(display);
            } else {
                existing.raise();
                return true;
            }
        }
        XWindowLayer.closeForDisplay(display); // rumah lama melepas
        new XOverlayWindows(act, display, title).attach();
        return true;
    }

    // ---------------- instance ----------------

    private XOverlayWindows(Activity act, int display, String title) {
        this.activity = act;
        this.display = display;

        host = new XWindowHost(act, display);
        host.setListener(this);
        win = new XFloatingWindow(act, host, title);
        win.setListener(this);
        // Drag jendela dikunci ke rect TerminalView hidup (satu
        // patokan dengan enforceAnchor, dalam koordinat decor).
        win.setAnchorSupplier(() -> terminalRectInDecor());

        // Kontainer selayar di decor: transparan + tak menghalangi
        // sentuhan di luar jendela (jatuh ke terminal di bawahnya).
        container = new FrameLayout(act);
        container.setClickable(false);
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getRealMetrics(m);
        int w = (int) (m.widthPixels * 0.88f);
        int h = (int) (m.heightPixels * 0.55f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = dp(14) + (display - 1) * dp(26);
        lp.topMargin = dp(70) + (display - 1) * dp(30);
        win.setLayoutParams(lp);
        container.addView(win);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void attach() {
        sByDisplay.put(display, this);
        android.view.ViewGroup decor =
                (android.view.ViewGroup) activity.getWindow().getDecorView();
        decor.addView(container, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        attached = true;
        host.attach();
        setActive();
        startAnchorWatch();
        X11DebugLog.log("OVERLAY", "tampil (decor) :" + display);
    }

    private void raise() {
        if (container.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) container.getParent())
                    .bringChildToFront(container);
        }
        win.setVisibility(android.view.View.VISIBLE);
        setActive();
    }

    /** — : sembunyikan jendela saja; sesi X tetap hidup. */
    public void hide() {
        win.setVisibility(android.view.View.GONE);
        if (sActive == this) {
            sActive = null;
            XKeyRouter.clear();
        }
        X11DebugLog.log("OVERLAY", "sembunyi :" + display);
        refocusTerminalSoon();
    }

    /**
     * Setelah jendela turun, kembalikan fokus + keyboard ke
     * TerminalView foreground (satu window, jadi ini sekadar
     * memastikan IME pulang — tanpa drama antar window).
     */
    private void refocusTerminalSoon() {
        ui.postDelayed(() -> {
            Activity act = AstroApp.foregroundActivity();
            if (act == null
                    || !act.getClass().getName().contains("NeoTermActivity")) return;
            android.view.View term =
                    findTerminalView(act.getWindow().getDecorView());
            if (term == null) return;
            term.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(term, 0);
        }, 150);
    }

    private static android.view.View findTerminalView(android.view.View v) {
        if (v.getClass().getName().endsWith("TerminalView")) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View f = findTerminalView(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** ✕ / host minta tutup: lepas total (server diurus pemanggil). */
    private void close() {
        win.setVisibility(android.view.View.GONE);
        if (container.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) container.getParent())
                    .removeView(container);
        }
        attached = false;
        host.detach();
        sByDisplay.remove(display);
        if (sByDisplay.isEmpty()) detachAnchorListener();
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

    // ---------------- XFloatingWindow.Listener ----------------

    @Override
    public void onFocusGained(XFloatingWindow w) {
        host.view().requestFocus();
        setActive();
    }

    @Override
    public void onContentTapped(XFloatingWindow w) {
        // Aturan input overlay (Hawx): mode floating — ketuk isi
        // memunculkan keyboard, sama kaya terminal di belakangnya;
        // mode maximize — ketuk CUMA fokus, keyboard hanya dari
        // tombol keyboard (biar ga tiap ketuk layar penuh keyboard
        // muncul). Satu window: requestFocus saja sudah memindah
        // target IME secara native.
        host.view().requestFocus();
        setActive();
        if (!w.isMaximized()) {
            host.view().setKeyboardVisible(true);
        }
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
        // Layer terminal TANPA strip: tombol khususnya bar bawaan
        // terminal via XKeyRouter.
        X11DebugLog.log("OVERLAY", "strip diminta " + visible + " — diabaikan (bar terminal)");
    }
}
