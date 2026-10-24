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

    /**
     * Lepas fokus jendela overlay (balik NOT_FOCUSABLE) — dipanggil
     * dari tombol ⌨ bar terminal: input harus pindah rumah ke
     * terminal, dan showSoftInput terminal gagal selama jendela
     * overlay masih pegang fokus window.
     */
    public static void releaseFocus() {
        for (XOverlayWindows o : sByDisplay.values()) {
            if (o.attached && (o.wlp.flags
                    & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                o.wlp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                o.wm.updateViewLayout(o.container, o.wlp);
            }
        }
        XKeyRouter.clear();
        X11DebugLog.log("OVERLAY", "fokus dilepas (tombol keyboard terminal)");
    }

    // ---- Patokan TUNGGAL pengunci di atas softkey ----
    // Patokannya: tepi bawah TerminalView (= tepi atas bar EK,
    // karena TerminalView layout_above extra_keys). Dulu ada
    // DUA penegak yang tarik-menarik (koreksi watcher 400 md +
    // clamp seret dari tangkapan awal + batas keyboard) hingga
    // jendela jitter (temuan Hawx: "tarik-menarik antara batas
    // keyboard dan batas softkey"). Kini satu penegak saja:
    // listener layout TerminalView — sinkron tiap layout pass,
    // termasuk selama animasi keyboard, jadi jendela bergerak
    // mulus mengikuti, bukan lompatan 400 md. Watcher 400 md
    // tinggal bertugas menempelkan ulang listener kalau view
    // TerminalView-nya berganti (ganti tab/dll), TANPA
    // menggerakkan jendela.
    private static android.view.View sAnchorView;
    private static android.view.ViewTreeObserver.OnGlobalLayoutListener sAnchorListener;

    private static void enforceAnchor() {
        if (sAnchorView == null || sByDisplay.isEmpty()) return;
        android.view.View tv = sAnchorView;
        if (tv.getWidth() <= 0 || tv.getHeight() <= 0) return;
        int[] loc = new int[2];
        tv.getLocationOnScreen(loc);
        int anchorT = loc[1];
        int anchorB = loc[1] + tv.getHeight();
        for (XOverlayWindows o : sByDisplay.values()) {
            if (!o.attached || o.win.isMaximized()) continue;
            int bottom = o.wlp.y + o.wlp.height;
            int top = o.wlp.y;
            if (bottom > anchorB) {
                o.wlp.y -= (bottom - anchorB);
                o.wm.updateViewLayout(o.container, o.wlp);
            } else if (top < anchorT) {
                o.wlp.y = anchorT;
                o.wm.updateViewLayout(o.container, o.wlp);
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

        container = new OverlayContainer(act);
        int w = (int) (m.widthPixels * 0.88f);
        int h = (int) (m.heightPixels * 0.55f);
        win.setLayoutParams(new FrameLayout.LayoutParams(w, h));
        container.addView(win);

        // NOT_FOCUSABLE selagi idle: terminal di bawah tetap pegang
        // fokus jendela (ketuk terminal = keyboard muncul normal).
        // Sentuhan pertama di jendela membalik flag ini + fokus.
        wlp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
        startAnchorWatch();
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
        refocusTerminalSoon();
    }

    /**
     * Setelah overlay turun, terminal utama sering kehilangan
     * keyboard (fokus IME nyangkut di view yang sudah dilepas).
     * Kembalikan fokus + keyboard ke TerminalView foreground —
     * hanya bila activity depan memang NeoTermActivity.
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

    /**
     * Kontainer overlay: (a) membalik NOT_FOCUSABLE pada sentuhan
     * pertama di dalam jendela; (b) MENYERET jendela sendiri lewat
     * parameter WindowManager (bukan lewat margin internal
     * XFloatingWindow — model margin kumulatifnya beradu dengan
     * sinkronisasi kontainer: jendela jatuh 2x kecepatan jari dan
     * mentok di batas semu). Grip resize tetap milik jendela
     * (hanya mengubah ukuran, tanpa margin — sinkron lama jalan).
     */
    private class OverlayContainer extends FrameLayout {
        private boolean dragArmed;
        private float dragDownX, dragDownY;
        private int dragStartX, dragStartY;

        OverlayContainer(Context ctx) {
            super(ctx);
        }

        private int dp2(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            int action = e.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                // Balik fokus: sentuhan pertama di jendela mana pun.
                if ((wlp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
                        && e.getX() >= win.getLeft() && e.getX() <= win.getRight()
                        && e.getY() >= win.getTop() && e.getY() <= win.getBottom()) {
                    wlp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    wm.updateViewLayout(container, wlp);
                    host.view().requestFocus();
                }
                // Arm drag hanya dari bar judul (32dp atas jendela).
                if (e.getY() >= win.getTop() && e.getY() <= win.getTop() + dp2(32)
                        && e.getX() >= win.getLeft() && e.getX() <= win.getRight()) {
                    dragArmed = true;
                    dragDownX = e.getRawX();
                    dragDownY = e.getRawY();
                    dragStartX = wlp.x;
                    dragStartY = wlp.y;
                }
                return false;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE && dragArmed) {
                if (Math.abs(e.getRawX() - dragDownX) > dp2(10)
                        || Math.abs(e.getRawY() - dragDownY) > dp2(10)) {
                    return true; // ambil alih: jendela tak lagi diseret
                }
            }
            if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                dragArmed = false;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            int action = e.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_MOVE && dragArmed) {
                DisplayMetrics m = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(m);
                int nx = dragStartX + Math.round(e.getRawX() - dragDownX);
                int ny = dragStartY + Math.round(e.getRawY() - dragDownY);
                // Patokan dibaca HIDUP tiap MOVE (bukan tangkapan
                // awal seret): selama animasi keyboard, rect
                // berubah tiap frame — tangkapan awal + watcher =
                // dua batas yang tarik-menarik (jitter). Satu
                // patokan: tepi bawah TerminalView (= atas softkey).
                boolean hasAnchor = false;
                int anchorL = 0, anchorT = 0, anchorR = 0, anchorB = 0;
                Activity fg = AstroApp.foregroundActivity();
                if (fg != null
                        && fg.getClass().getName().contains("NeoTermActivity")) {
                    android.view.View tv = findTerminalView(
                            fg.getWindow().getDecorView());
                    if (tv != null && tv.getWidth() > 0 && tv.getHeight() > 0) {
                        int[] loc = new int[2];
                        tv.getLocationOnScreen(loc);
                        anchorL = loc[0];
                        anchorT = loc[1];
                        anchorR = loc[0] + tv.getWidth();
                        anchorB = loc[1] + tv.getHeight();
                        hasAnchor = true;
                    }
                }
                if (hasAnchor) {
                    // Di dalam rect bg terminal: kiri/kanan boleh
                    // menyisakan 80px, atas mentok tepi rect, bawah
                    // mentok tepi rect + 40px kelihatan (bar EK di
                    // bawah rect tak pernah tertutup).
                    nx = Math.max(anchorL - (wlp.width - dp2(80)),
                            Math.min(nx, anchorR - dp2(80)));
                    ny = Math.max(anchorT,
                            Math.min(ny, Math.max(anchorT, anchorB - dp2(40))));
                } else {
                    nx = Math.max(-(wlp.width - dp2(80)),
                            Math.min(nx, m.widthPixels - dp2(80)));
                    ny = Math.max(0,
                            Math.min(ny, m.heightPixels - dp2(40)));
                }
                if (nx != wlp.x || ny != wlp.y) {
                    wlp.x = nx;
                    wlp.y = ny;
                    wm.updateViewLayout(container, wlp);
                }
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                dragArmed = false;
                return true;
            }
            return false;
        }
    }

    /** ✕ / host minta tutup: lepas total (server diurus pemanggil). */
    private void close() {
        hide();
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
        // Aturan input overlay (Hawx): mode floating — ketuk isi
        // memunculkan keyboard, sama kaya terminal di belakangnya;
        // mode maximize — ketuk CUMA fokus, keyboard hanya dari
        // tombol ⌨ (biar ga tiap ketuk layar penuh keyboard muncul).
        // requestFocus eksplisit: tanpa ini LorieView tak terikat
        // IME/input.
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
        // Overlay TANPA strip: keyboard layar diurus host sendiri;
        // tombol khususnya bar bawaan terminal via XKeyRouter.
        X11DebugLog.log("OVERLAY", "strip diminta " + visible + " — diabaikan (bar terminal)");
    }
}
