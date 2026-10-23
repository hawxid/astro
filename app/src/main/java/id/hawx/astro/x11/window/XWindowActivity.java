package id.hawx.astro.x11.window;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.PopupWindow;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.LorieView;

import id.hawx.astro.x11.X11DebugLog;
import id.hawx.astro.x11.XSessionManager;

/**
 * Jendela X melayang berbentuk Activity sendiri (PoC 1b terbukti render;
 * view nempel di hierarchy MainActivity tidak). Window-nya parsial +
 * NOT_TOUCH_MODAL: desktop di belakang tetap kelihatan & bisa disentuh.
 *
 * Chrome: bar judul (drag) + titik status koneksi + tombol ⌨/—/▢/✕ +
 * grip resize pojok kanan-bawah. — = tutup jendela saja (server+app
 * hidup terus); ✕ = bunuh display + app-nya (aturan Hawx).
 */
public class XWindowActivity extends Activity implements XWindowHost.Listener {

    /** Registry jendela floating yang sedang terbuka (untuk serah-terima). */
    private static final java.util.Map<Integer, XWindowActivity> OPEN =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Tutup jendela floating untuk display ini (viewer fullscreen mengambil alih). */
    public static void closeForDisplay(int d) {
        XWindowActivity a = OPEN.get(d);
        if (a != null) {
            X11DebugLog.log("HANDOFF", "tutup jendela floating d=" + d + " (fullscreen ambil alih)");
            a.finish();
        }
    }
    public static final String EXTRA_DISPLAY = "astro_xwin_display";
    public static final String EXTRA_TITLE = "astro_xwin_title";

    private XWindowHost host;
    private int display;
    private TextView dot;
    private FrameLayout contentFrame;
    private boolean maximized;
    private int[] savedRect; // x, y, w, h sebelum maximize
    private PopupWindow ekPopup;
    private boolean ekRequested;
    // Strip model B final: jendela OVERLAY WindowManager sendiri (koordinat
    // layar absolut, tanpa jangkar ke jendela floating) — butuh izin
    // SYSTEM_ALERT_WINDOW; tanpa izin, jatuh ke popup lama.
    // Overlay strip dimiliki STATIS (satu per proses): instance berganti
    // tak boleh ninggalin strip yatim; pemilik tercatat agar onStop
    // instance lain tak membunuh strip instance yang sedang tampil.
    private static android.view.WindowManager sEkWm;
    private static android.view.WindowManager.LayoutParams sEkLp;
    private static android.view.View sEkOverlayView;
    private static Object sEkOwner;
    private static int learnedKbTopY = -1;
    private boolean overlayHintShown;
    private int imeBottomPx;
    private boolean imeWasVisible;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        display = getIntent().getIntExtra(EXTRA_DISPLAY, 1);
        OPEN.put(display, this);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "XTerm";
        X11DebugLog.log("FLOAT", "XWindowActivity onCreate d=" + display);

        host = new XWindowHost(this, display);
        host.setListener(this);
        LorieView view = host.view();
        // JANGAN set background ke LorieView: SurfaceView kacanya di belakang
        // canvas window — background opak menutup lubangnya (layar hitam
        // sempurna walau koneksi sehat). Konstruktor LorieView sudah masang
        // background transparan custom-nya sendiri.

        // ---- kerangka jendela ----
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xFF0B0F1E);
        rootBg.setStroke(dp(1), 0xFF232C4E);
        rootBg.setCornerRadius(dp(10));
        root.setBackground(rootBg);

        // ---- bar judul ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xFF232C4E);
        barBg.setCornerRadii(new float[]{dp(10), dp(10), dp(10), dp(10), 0, 0, 0, 0});
        bar.setBackground(barBg);
        bar.setPadding(dp(10), 0, dp(4), 0);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(9);
        dot.setTextColor(0xFF8B93B8);
        bar.addView(dot);

        TextView titleView = new TextView(this);
        titleView.setText(title + " · :" + display);
        titleView.setTextColor(0xFFE8EBFA);
        titleView.setTextSize(12);
        titleView.setSingleLine(true);
        titleView.setPadding(dp(6), 0, 0, 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        bar.addView(chromeButton("⌨", v -> {
            if (stripShowing()) hideKeyboardAndStrip();
            else host.showKeyboardAndStrip();
        }));
        bar.addView(chromeButton("—", v -> finish()));
        bar.addView(chromeButton("▢", v -> toggleMaximize()));
        bar.addView(chromeButton("✕", v -> {
            XSessionManager.get(this).stopDisplay(display, null);
            finish();
        }));
        bar.setOnTouchListener(new DragListener());

        // ---- isi: LorieView murni + grip resize ----
        FrameLayout content = new FrameLayout(this);
        contentFrame = content;
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView grip = new TextView(this);
        grip.setText("◢");
        grip.setTextSize(11);
        grip.setTextColor(0xFF8B93B8);
        grip.setGravity(Gravity.BOTTOM | Gravity.END);
        FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(dp(30), dp(30));
        gripLp.gravity = Gravity.BOTTOM | Gravity.END;
        content.addView(grip, gripLp);
        grip.setOnTouchListener(new ResizeListener());

        // Wiring sentuh meniru MainActivity upstream: listener di PARENT,
        // diteruskan ke TouchInputHandler host.
        content.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    v.requestUnbufferedDispatch(e);
                // Tap konten = fokus saja. Keyboard HANYA dari tombol ⌨
                // di bar judul (keputusan Hawx).
                X11DebugLog.log("KB", "tap konten d=" + display + " (fokus saja)");
            }
            return host.handleTouch(v, e);
        });
        content.setOnHoverListener((v, e) -> host.handleTouch(v, e));
        content.setOnGenericMotionListener((v, e) -> host.handleTouch(v, e));
        if (android.os.Build.VERSION.SDK_INT >= 26)
            view.setOnCapturedPointerListener((v, e) -> host.handleTouch(view, e));

        setContentView(root);
        applyInitialWindowRect();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                boolean imeVisible = insets.isVisible(android.view.WindowInsets.Type.ime());
                int newBottom = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                if (imeVisible != imeWasVisible || newBottom != imeBottomPx)
                    X11DebugLog.log("KB", "insets ime=" + imeVisible + " bottom=" + newBottom
                            + " ekReq=" + ekRequested);
                boolean wasVisible = imeWasVisible;
                imeWasVisible = imeVisible;
                imeBottomPx = newBottom;
                if (imeVisible) tryShowStrip();
                else if (wasVisible && stripShowing())
                    hideKeyboardAndStrip();
                return insets;
            });
        }
        host.attach();
    }

    private TextView chromeButton(String label, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFFE8EBFA);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(32)));
        return b;
    }

    private void applyInitialWindowRect() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.round(dm.widthPixels * 0.88f);
        int h = Math.round(dm.heightPixels * 0.58f);
        int cascade = (display % 5) * dp(22);
        int x = Math.max(0, (dm.widthPixels - w) / 2 + cascade - dp(44));
        int y = dp(56) + cascade;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = w;
        lp.height = h;
        lp.x = x;
        lp.y = y;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        lp.dimAmount = 0f;
        getWindow().setAttributes(lp);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private void toggleMaximize() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (!maximized) {
            savedRect = new int[]{lp.x, lp.y, lp.width, lp.height};
            lp.x = 0;
            lp.y = 0;
            lp.width = dm.widthPixels;
            lp.height = dm.heightPixels;
            maximized = true;
        } else if (savedRect != null) {
            lp.x = savedRect[0];
            lp.y = savedRect[1];
            lp.width = savedRect[2];
            lp.height = savedRect[3];
            maximized = false;
        }
        getWindow().setAttributes(lp);
        updateStripInset();
    }

    /** Seret bar judul = pindahkan window. */
    private class DragListener implements View.OnTouchListener {
        private int startX, startY;
        private float downRawX, downRawY;

        @Override public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (maximized) return true;
                    lp.x = startX + Math.round(e.getRawX() - downRawX);
                    lp.y = Math.max(0, startY + Math.round(e.getRawY() - downRawY));
                    getWindow().setAttributes(lp);
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Seret grip pojok = ubah ukuran window (sesi X ikut via RANDR). */
    private class ResizeListener implements View.OnTouchListener {
        private int startW, startH;
        private float downRawX, downRawY;

        @Override public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startW = lp.width;
                    startH = lp.height;
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (maximized) return true;
                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    lp.width = clamp(startW + Math.round(e.getRawX() - downRawX),
                            dp(280), dm.widthPixels);
                    lp.height = clamp(startH + Math.round(e.getRawY() - downRawY),
                            dp(200), dm.heightPixels);
                    getWindow().setAttributes(lp);
                    return true;
                default:
                    return false;
            }
        }

        private int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    // ---- XWindowHost.Listener ----
    @Override public void onCloseRequested(XWindowHost h) {
        finish();
    }

    @Override public void onConnectionChanged(XWindowHost h, boolean connected) {
        if (dot != null)
            dot.setTextColor(connected ? 0xFF3DDC84 : 0xFF8B93B8);
    }

    @Override public void onStripVisibilityRequested(XWindowHost h, boolean visible) {
        X11DebugLog.log("KB", "stripReq visible=" + visible + " imeBottom=" + imeBottomPx);
        ekRequested = visible;
        if (visible) {
            tryShowStrip();
            // Polling pendek: IME naiknya asinkron; cek insets view 5x2 detik.
            for (int i = 1; i <= 5; i++) {
                final int n = i;
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> {
                            if (!ekRequested) return;
                            X11DebugLog.log("KB", "poll#" + n);
                            tryShowStrip();
                            if (stripShowing()) return;
                        }, 400L * i);
            }
        }
        else dismissStrip();
    }

    private void hideKeyboardAndStrip() {
        ekRequested = false;
        dismissStrip();
        InputMethodManager imm = (InputMethodManager)
                getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null && host != null)
            imm.hideSoftInputFromWindow(host.view().getWindowToken(), 0);
    }

    // ---- lapisan EK terpisah: PopupWindow selebar layar di atas keyboard ----
    // (aturan Hawx: lapisan softkey dipisah dari lapisan render X)

    /** Tebal strip: 45dp (dari 37.5 — konten terlalu mepet, kata Hawx). */
    private int stripHeightPx() {
        return Math.round(45f * getResources().getDisplayMetrics().density);
    }

    /** Pas MAXIMIZED, strip tak boleh nutupin layar X: area konten
     *  disusutkan setebal strip (surface ngecil -> RANDR ngikut -> konten
     *  reflow ke atas strip). Setelan Hawx: cukup SETENGAH tebal strip —
     *  layar turun dikit, strip naik dikit, ketemu di tengah. */
    private void updateStripInset() {
        if (contentFrame == null) return;
        int pad = (maximized && stripShowing()) ? stripHpx / 2 : 0;
        if (contentFrame.getPaddingBottom() != pad)
            contentFrame.setPadding(0, 0, 0, pad);
    }

    private boolean stripShowing() {
        return sEkOverlayView != null
                || (ekPopup != null && ekPopup.isShowing());
    }

    /** Pintu strip: overlay bila izin ada, popup lama bila belum. */
    private void tryShowStrip() {
        if (android.provider.Settings.canDrawOverlays(this)) {
            tryShowEkOverlay();
        } else {
            if (!overlayHintShown) {
                overlayHintShown = true;
                android.widget.Toast.makeText(this,
                        "Strip mode kompat: aktifkan 'Tampil di atas aplikasi lain' "
                        + "untuk Astro agar strip diam di atas keyboard",
                        android.widget.Toast.LENGTH_LONG).show();
            }
            tryShowEkPopup();
        }
    }

    /** Tepi atas keyboard di koordinat LAYAR. Dari jendela sendiri:
     *  dasar jendela - intrusi IME (persis saat jendela menyusut/tertindih);
     *  kasus celah (jendela parkir di atas keyboard): cache yang dipelajari
     *  dari bacaan intrusi > 0. -1 bila IME tak terlihat. */
    private int kbTopScreenY() {
        if (host == null) return -1;
        android.view.View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT < 30) return -1;
        android.view.WindowInsets in = decor.getRootWindowInsets();
        if (in == null || !in.isVisible(android.view.WindowInsets.Type.ime()))
            return -1;
        int imeB = in.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        int[] loc = new int[2];
        decor.getLocationOnScreen(loc);
        int winBottom = loc[1] + decor.getHeight();
        int kbTop = winBottom - imeB;
        if (imeB > 0 && kbTop > 0) learnedKbTopY = kbTop;
        if (imeB == 0 && learnedKbTopY > 0 && winBottom < learnedKbTopY - 8)
            kbTop = learnedKbTopY;
        return kbTop;
    }

    private void tryShowEkOverlay() {
        if (!ekRequested || host == null) return;
        if (isFinishing() || isDestroyed()) return;
        if (sEkOverlayView != null && sEkOwner == this) return;
        android.view.View ekView = host.extraKeysView();
        if (ekView == null) return;
        int kbTop = kbTopScreenY();
        if (kbTop < 0) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::tryShowStrip, 150);
            X11DebugLog.log("KB", "overlay TUNDA (kbTop belum kebaca)");
            return;
        }
        // Rebut strip dari pemilik lama (instance/display lain): satu
        // strip per proses, tak ada yatim.
        if (sEkOverlayView != null && sEkWm != null) {
            try { sEkWm.removeView(sEkOverlayView); } catch (Exception ignored) {}
            sEkOverlayView = null;
        }
        if (ekView.getParent() != null)
            ((ViewGroup) ekView.getParent()).removeView(ekView);
        ekView.setBackgroundColor(0xF7232C4E);
        int hPx = stripHeightPx();
        stripHpx = hPx;
        sEkLp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, hPx,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        sEkLp.gravity = Gravity.TOP | Gravity.START;
        sEkLp.x = 0;
        sEkLp.y = kbTop - hPx;
        sEkWm = (android.view.WindowManager) getSystemService(
                android.content.Context.WINDOW_SERVICE);
        try {
            sEkWm.addView(ekView, sEkLp);
            sEkOverlayView = ekView;
            sEkOwner = this;
            updateStripInset();
            X11DebugLog.log("KB", "overlay SHOW y=" + sEkLp.y + " kbTop=" + kbTop);
            startEkOverlayWatchdog();
        } catch (Exception e) {
            X11DebugLog.log("KB", "overlay GAGAL (" + e.getMessage() + ") -> popup");
            sEkOverlayView = null;
            sEkOwner = null;
            tryShowEkPopup();
        }
    }

    /** Watchdog overlay: reposisi HANYA dari kbTop (jendela floating bebas
     *  gerak — overlay tak peduli), tutup saat IME hilang. */
    private void startEkOverlayWatchdog() {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(new Runnable() {
                    int hiddenStreak = 0;
                    @Override public void run() {
                        if (sEkOverlayView == null || sEkOwner != XWindowActivity.this) return;
                        int kbTop = kbTopScreenY();
                        if (kbTop >= 0) {
                            hiddenStreak = 0;
                            int y = kbTop - stripHpx;
                            if (y != sEkLp.y) {
                                sEkLp.y = y;
                                try { sEkWm.updateViewLayout(sEkOverlayView, sEkLp); }
                                catch (Exception ignored) {}
                                X11DebugLog.log("KB", "overlay reposisi y=" + y);
                            }
                        } else if (++hiddenStreak >= 3) {
                            X11DebugLog.log("KB", "watchdog overlay: IME hilang -> tutup");
                            hideKeyboardAndStrip();
                            return;
                        }
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(this, 400);
                    }
                }, 400);
    }

    private void tryShowEkPopup() {
        if (!ekRequested || host == null) return;
        if (isFinishing() || isDestroyed()) return; // BadToken di log 20
        if (ekPopup != null && ekPopup.isShowing()) return;
        android.view.View ekView = host.extraKeysView();
        if (ekView == null) return;
        if (ekView.getParent() != null)
            ((ViewGroup) ekView.getParent()).removeView(ekView);
        // Tinggi TETAP 37.5dp seperti pager toolbar upstream: layout EK
        // match_parent — di popup WRAP_CONTENT dia kolaps ke 0 (gepeng).
        int hPx = stripHeightPx();
        // Lebar EKSPLISIT selebar display (keputusan Hawx: lapisan keyboard,
        // diam): MATCH_PARENT popup ngikut lebar jendela jangkar.
        int wPx = getResources().getDisplayMetrics().widthPixels;
        try {
            wPx = getWindowManager().getCurrentWindowMetrics().getBounds().width();
        } catch (Exception ignored) {}
        ekPopup = new PopupWindow(ekView, wPx, hPx, false);
        ekPopup.setTouchable(true);
        ekPopup.setOutsideTouchable(false);
        ekPopup.setBackgroundDrawable(new ColorDrawable(0xF7232C4E));
        ekPopup.setAnimationStyle(0);
        // Posisi dari TEPI ATAS KEYBOARD (bukan dasar jendela — jangkar lama
        // bikin strip kadang nangkring DI ATAS keyboard): insets IME jendela
        // sendiri = jarak tepi keyboard dari dasar jendela; kbTop dihitung
        // di koordinat layar lalu diubah ke koordinat frame popup.
        stripHpx = hPx;
        int yLocal = stripYLocal();
        if (yLocal < 0) {
            // Frame keyboard belum kebaca (IME masih naik): TUNDA tampil —
            // jangan nebak posisi (fallback gravity beda = strip terbang).
            ekPopup = null;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::tryShowEkPopup, 150);
            X11DebugLog.log("KB", "popup TUNDA (frame IME belum siap)");
            return;
        }
        // SELALU gravity TOP + offset yang sama dengan reposisi watchdog.
        ekPopup.showAtLocation(getWindow().getDecorView(),
                Gravity.TOP | Gravity.START, 0, yLocal);
        X11DebugLog.log("KB", "popup SHOW hPx=" + hPx + " yLocal=" + yLocal);
        startEkWatchdog();
        updateStripInset();
    }

    private int stripHpx;

    /**
     * Y lokal (frame popup) agar strip duduk TEPAT di atas keyboard,
     * dari frame tampil jendela. -1 bila IME belum terlihat.
     */
    private int stripYLocal() {
        if (host == null || stripHpx <= 0) return -1;
        android.view.View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT < 30) return -1;
        android.view.WindowInsets in = decor.getRootWindowInsets();
        if (in == null || !in.isVisible(android.view.WindowInsets.Type.ime()))
            return -1;
        // Tepi atas keyboard = tepi bawah frame TAMPIL jendela ini di layar
        // (frame tampil terpotong IME untuk jendela target keyboard).
        android.graphics.Rect frame = new android.graphics.Rect();
        decor.getWindowVisibleDisplayFrame(frame);
        int[] loc = new int[2];
        decor.getLocationOnScreen(loc);
        return frame.bottom - stripHpx - loc[1];
    }

    /** Selama popup tampil: pantau IME dari insets view; keyboard turun -> tutup. */
    private void startEkWatchdog() {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(new Runnable() {
                    int hiddenStreak = 0;
                    int lastStripY = -1;
                    @Override public void run() {
                        if (ekPopup == null || !ekPopup.isShowing()) return;
                        boolean imeVis = false;
                        if (android.os.Build.VERSION.SDK_INT >= 30
                                && host != null && host.view().getRootWindowInsets() != null)
                            imeVis = host.view().getRootWindowInsets()
                                    .isVisible(android.view.WindowInsets.Type.ime());
                        // Fixed di atas keyboard: reposisi tiap insets berubah
                        // (animasi keyboard, ganti keyboard, jendela pindah).
                        if (imeVis) {
                            int y = stripYLocal();
                            if (y >= 0 && y != lastStripY) {
                                lastStripY = y;
                                ekPopup.update(-1, y, -1, -1);
                                X11DebugLog.log("KB", "popup reposisi y=" + y);
                            }
                        }
                        hiddenStreak = imeVis ? 0 : hiddenStreak + 1;
                        if (hiddenStreak >= 3) {
                            X11DebugLog.log("KB", "watchdog: IME hilang -> tutup popup");
                            hideKeyboardAndStrip();
                            return;
                        }
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(this, 400);
                    }
                }, 400);
    }

    private void dismissEkPopup() {
        if (ekPopup != null && ekPopup.isShowing()) ekPopup.dismiss();
    }

    private void dismissStrip() {
        if (sEkOverlayView != null && sEkOwner == this && sEkWm != null) {
            try { sEkWm.removeView(sEkOverlayView); } catch (Exception ignored) {}
            sEkOverlayView = null;
            sEkOwner = null;
        }
        dismissEkPopup();
        updateStripInset();
    }

    /** Activity tertutup layar lain / ke background: strip nutup INSTAN
     *  (tak nunggu watchdog — strip yatim di launcher, temuan Hawx). */
    @Override protected void onStop() {
        if (sEkOwner == this && stripShowing()) {
            ekRequested = false;
            dismissStrip();
            X11DebugLog.log("KB", "onStop -> strip ditutup");
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        OPEN.remove(display, this);
        dismissStrip();
        if (host != null) host.detach();
        super.onDestroy();
    }

    public static void launch(android.content.Context from, int display) {
        launch(from, display, null);
    }

    public static void launch(android.content.Context from, int display, String title) {
        Intent i = new Intent(from, XWindowActivity.class);
        i.putExtra(EXTRA_DISPLAY, display);
        if (title != null) i.putExtra(EXTRA_TITLE, title);
        // TASK SENDIRI per jendela (temuan multi-window 2026-10-23): dua
        // activity di task yang sama -> yang bawah kehilangan window focus
        // (winFocus=false, IME tak pernah naik, jendela "beku"). Data unik
        // per display + intoExisting: relaunch display yang sama mengangkat
        // task/jendela lamanya, bukan bikin duplikat.
        i.setData(android.net.Uri.parse("astro-x-window://d" + display));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        from.startActivity(i);
    }
}
