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
import android.view.WindowManager;
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
    public static final String EXTRA_DISPLAY = "astro_xwin_display";
    public static final String EXTRA_TITLE = "astro_xwin_title";

    private XWindowHost host;
    private int display;
    private TextView dot;
    private boolean maximized;
    private int[] savedRect; // x, y, w, h sebelum maximize

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        display = getIntent().getIntExtra(EXTRA_DISPLAY, 1);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "XTerm";
        X11DebugLog.log("FLOAT", "XWindowActivity onCreate d=" + display);

        host = new XWindowHost(this, display);
        host.setListener(this);
        LorieView view = host.view();
        view.setBackgroundColor(Color.BLACK);

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

        bar.addView(chromeButton("⌨", v -> host.showKeyboardAndStrip()));
        bar.addView(chromeButton("—", v -> finish()));
        bar.addView(chromeButton("▢", v -> toggleMaximize()));
        bar.addView(chromeButton("✕", v -> {
            XSessionManager.get(this).stopDisplay(display, null);
            finish();
        }));
        bar.setOnTouchListener(new DragListener());

        // ---- isi: LorieView murni + grip resize ----
        FrameLayout content = new FrameLayout(this);
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
            if (e.getAction() == MotionEvent.ACTION_DOWN
                    && android.os.Build.VERSION.SDK_INT >= 29)
                v.requestUnbufferedDispatch(e);
            return host.handleTouch(v, e);
        });
        content.setOnHoverListener((v, e) -> host.handleTouch(v, e));
        content.setOnGenericMotionListener((v, e) -> host.handleTouch(v, e));
        if (android.os.Build.VERSION.SDK_INT >= 26)
            view.setOnCapturedPointerListener((v, e) -> host.handleTouch(view, e));

        setContentView(root);
        applyInitialWindowRect();
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
        // Strip softkey = tahap 2; di sini keyboard saja yang diatur host.
    }

    @Override protected void onDestroy() {
        if (host != null) host.detach();
        super.onDestroy();
    }

    public static void launch(Activity from, int display) {
        launch(from, display, null);
    }

    public static void launch(Activity from, int display, String title) {
        Intent i = new Intent(from, XWindowActivity.class);
        i.putExtra(EXTRA_DISPLAY, display);
        if (title != null) i.putExtra(EXTRA_TITLE, title);
        from.startActivity(i);
    }
}
