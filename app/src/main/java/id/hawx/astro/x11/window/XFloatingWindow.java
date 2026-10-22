package id.hawx.astro.x11.window;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Satu jendela X floating di desktop Astro: chrome milik Astro sendiri
 * (bar judul bisa didrag, tombol ⌨ / — / ▢ / ✕), isi = LorieView murni
 * 100% (aturan Hawx: tidak ada bar apa pun di dalam area display).
 *
 * Semantik tombol (keputusan Hawx 2026-10-22):
 *   ✕ = aplikasi + server display-nya mati sekalian (dieksekusi layer).
 *   — = sembunyikan jendela saja, aplikasi tetap jalan (chip restore).
 *   ▢ = maximize/pulihkan — resize dinamis, sesi X tidak pernah restart.
 */
public class XFloatingWindow extends FrameLayout {

    public interface Listener {
        void onFocusGained(XFloatingWindow w);

        void onContentTapped(XFloatingWindow w);

        void onCloseClicked(XFloatingWindow w);

        void onMinimizeClicked(XFloatingWindow w);
    }

    private final XWindowHost host;
    private final TextView titleView;
    private final FrameLayout content;
    private Listener listener;
    private boolean maximized;
    private int savedLeft, savedTop, savedWidth, savedHeight;

    public XFloatingWindow(Context ctx, XWindowHost host, String title) {
        super(ctx);
        this.host = host;

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21A2138);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFF3D4A7A);
        setBackground(bg);
        setClipToOutline(true);
        setElevation(dp(10));

        // ---- chrome bar ----
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), 0, dp(4), 0);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xFF232C4E);
        bar.setBackground(barBg);
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        TextView dot = new TextView(ctx);
        dot.setText("●");
        dot.setTextSize(9);
        dot.setTextColor(0xFF8B93B8);
        bar.addView(dot);

        titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextColor(0xFFE8EBFA);
        titleView.setTextSize(12);
        titleView.setSingleLine(true);
        titleView.setPadding(dp(6), 0, 0, 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        bar.addView(chromeButton("⌨", v -> host.showKeyboardAndStrip()));
        bar.addView(chromeButton("—", v -> {
            if (listener != null) listener.onMinimizeClicked(this);
        }));
        bar.addView(chromeButton("▢", v -> toggleMaximize()));
        bar.addView(chromeButton("✕", v -> {
            if (listener != null) listener.onCloseClicked(this);
        }));

        // drag lewat bar judul (kecuali tombol — tombol mengonsumsi sendiri)
        bar.setOnTouchListener(new DragListener());

        // ---- isi: LorieView murni ----
        content = new FrameLayout(ctx);
        LayoutParams contentLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        contentLp.topMargin = dp(32);
        addView(content, contentLp);
        content.addView(host.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.view().setBackgroundColor(Color.BLACK);

        // ---- grip resize pojok kanan-bawah ----
        TextView grip = new TextView(ctx);
        grip.setText("◢");
        grip.setTextColor(0x99AAB4E8);
        grip.setTextSize(13);
        grip.setGravity(Gravity.BOTTOM | Gravity.END);
        LayoutParams gripLp = new LayoutParams(dp(30), dp(30));
        gripLp.gravity = Gravity.BOTTOM | Gravity.END;
        addView(grip, gripLp);
        grip.setOnTouchListener(new ResizeListener());
    }

    public XWindowHost host() {
        return host;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setTitleText(String t) {
        titleView.setText(t);
    }

    public void setConnectedDot(boolean connected) {
        // titik status di chrome: hijau = X terhubung
        // (diambil dari anak pertama bar — TextView dot)
        LinearLayout bar = (LinearLayout) getChildAt(0);
        ((TextView) bar.getChildAt(0)).setTextColor(connected ? 0xFF3DDC84 : 0xFF8B93B8);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && listener != null) {
            listener.onFocusGained(this);
            // tap di area display (di bawah chrome) = tap konten X
            if (ev.getY() > content.getTop()) listener.onContentTapped(this);
        }
        return super.dispatchTouchEvent(ev);
    }

    private TextView chromeButton(String label, OnClickListener onClick) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextColor(0xFFD6DBF5);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(7), dp(2), dp(7), dp(2));
        b.setOnClickListener(onClick);
        return b;
    }

    public boolean isMaximized() {
        return maximized;
    }

    public void toggleMaximize() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) return;
        LayoutParams lp = (LayoutParams) getLayoutParams();
        if (!maximized) {
            savedLeft = lp.leftMargin;
            savedTop = lp.topMargin;
            savedWidth = lp.width;
            savedHeight = lp.height;
            lp.leftMargin = dp(6);
            lp.topMargin = dp(6);
            lp.width = parent.getWidth() - dp(12);
            lp.height = parent.getHeight() - dp(12);
            maximized = true;
        } else {
            lp.leftMargin = savedLeft;
            lp.topMargin = savedTop;
            lp.width = savedWidth;
            lp.height = savedHeight;
            maximized = false;
        }
        setLayoutParams(lp);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Drag jendela lewat bar judul. */
    private class DragListener implements OnTouchListener {
        private float downX, downY;
        private int startLeft, startTop;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (maximized) return false;
            LayoutParams lp = (LayoutParams) getLayoutParams();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startLeft = lp.leftMargin;
                    startTop = lp.topMargin;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    ViewGroup parent = (ViewGroup) getParent();
                    int nl = startLeft + Math.round(e.getRawX() - downX);
                    int nt = startTop + Math.round(e.getRawY() - downY);
                    if (parent != null) {
                        nl = Math.max(-lp.width + dp(80), Math.min(nl, parent.getWidth() - dp(80)));
                        nt = Math.max(0, Math.min(nt, parent.getHeight() - dp(40)));
                    }
                    lp.leftMargin = nl;
                    lp.topMargin = nt;
                    setLayoutParams(lp);
                    return true;
            }
            return false;
        }
    }

    /** Resize lewat grip pojok — surface berubah, sesi X menyesuaikan dinamis. */
    private class ResizeListener implements OnTouchListener {
        private float downX, downY;
        private int startW, startH;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (maximized) return false;
            LayoutParams lp = (LayoutParams) getLayoutParams();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startW = lp.width;
                    startH = lp.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.width = Math.max(dp(280), startW + Math.round(e.getRawX() - downX));
                    lp.height = Math.max(dp(200), startH + Math.round(e.getRawY() - downY));
                    setLayoutParams(lp);
                    return true;
            }
            return false;
        }
    }
}
