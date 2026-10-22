package id.hawx.astro.x11.window;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Lapisan jendela X di atas desktop Astro (overlay di activity_main).
 *
 * Mengelola: jendela-jendela floating (satu per display), strip softkey
 * selebar layar yang menempel tepat di atas keyboard (aturan Hawx: strip
 * TIDAK di dalam jendela), dan chip restore untuk jendela yang diminimalkan.
 *
 * Semantik ✕ (keputusan Hawx): aplikasi + server display mati sekalian —
 * layer melapor lewat {@link KillListener} agar pemanggil yang membunuh
 * lewat XSessionManager.
 */
public class XWindowLayer extends FrameLayout implements XFloatingWindow.Listener, XWindowHost.Listener {

    public interface KillListener {
        /** ✕ ditekan: bunuh aplikasi + server untuk display ini. */
        void onWindowKillRequested(int display);
    }

    private final FrameLayout windowsBox;
    private final LinearLayout chipsBar;
    private final LinearLayout strip;
    private final Map<Integer, XFloatingWindow> windows = new HashMap<>();
    private final Map<Integer, Button> chips = new HashMap<>();
    private XFloatingWindow active;
    private KillListener killListener;
    private int latchedMeta;

    public XWindowLayer(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setClickable(false);

        windowsBox = new FrameLayout(ctx);
        addView(windowsBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        chipsBar = new LinearLayout(ctx);
        chipsBar.setOrientation(LinearLayout.HORIZONTAL);
        LayoutParams chipsLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsLp.gravity = Gravity.BOTTOM | Gravity.START;
        chipsLp.leftMargin = dp(8);
        chipsLp.bottomMargin = dp(48);
        addView(chipsBar, chipsLp);

        strip = buildStrip(ctx);
        LayoutParams stripLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stripLp.gravity = Gravity.BOTTOM;
        addView(strip, stripLp);
        strip.setVisibility(GONE);
    }

    public void setKillListener(KillListener l) {
        killListener = l;
    }

    // ---- siklus jendela ----

    public void openWindow(Activity activity, int display, String title) {
        XFloatingWindow existing = windows.get(display);
        if (existing != null) {
            restoreWindow(existing);
            return;
        }
        XWindowHost host = new XWindowHost(activity, display);
        XFloatingWindow win = new XFloatingWindow(activity, host, title);
        win.setListener(this);
        host.setListener(this);

        int w = (int) (getWidth() * 0.86f);
        int h = (int) (getHeight() * 0.58f);
        if (w <= 0) { // layer belum ter-layout (dibuka dari onCreate) — pakai metrik layar
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = (int) (dm.widthPixels * 0.86f);
            h = (int) (dm.heightPixels * 0.5f);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = dp(14) + display * dp(18);
        lp.topMargin = dp(52) + display * dp(18);
        windowsBox.addView(win, lp);
        windows.put(display, win);
        setActive(win);
        host.attach();
    }

    public void closeWindow(XFloatingWindow win, boolean kill) {
        int display = win.host().display();
        win.host().detach();
        windowsBox.removeView(win);
        windows.remove(display);
        Button chip = chips.remove(display);
        if (chip != null) chipsBar.removeView(chip);
        if (active == win) {
            active = null;
            strip.setVisibility(GONE);
        }
        if (kill && killListener != null) killListener.onWindowKillRequested(display);
    }

    private void restoreWindow(XFloatingWindow win) {
        win.setVisibility(VISIBLE);
        Button chip = chips.remove(win.host().display());
        if (chip != null) chipsBar.removeView(chip);
        setActive(win);
    }

    private void setActive(XFloatingWindow win) {
        active = win;
        win.bringToFront();
        // strip + chips selalu di atas jendela
        chipsBar.bringToFront();
        strip.bringToFront();
    }

    // ---- XFloatingWindow.Listener ----

    @Override
    public void onFocusGained(XFloatingWindow w) {
        if (active != w) setActive(w);
    }

    @Override
    public void onContentTapped(XFloatingWindow w) {
        // Aturan Hawx: tap jendela X = fokus + keyboard naik, strip mengikuti.
        if (active != w) setActive(w);
        w.host().showKeyboardAndStrip();
    }

    @Override
    public void onCloseClicked(XFloatingWindow w) {
        closeWindow(w, true);
    }

    @Override
    public void onMinimizeClicked(XFloatingWindow w) {
        w.setVisibility(GONE);
        int display = w.host().display();
        Button chip = new Button(getContext());
        chip.setText("▣ :" + display);
        chip.setTextSize(11);
        chip.setOnClickListener(v -> restoreWindow(w));
        chipsBar.addView(chip);
        chips.put(display, chip);
        if (active == w) strip.setVisibility(GONE);
    }

    // ---- XWindowHost.Listener ----

    @Override
    public void onCloseRequested(XWindowHost host) {
        XFloatingWindow w = windows.get(host.display());
        if (w != null) closeWindow(w, false);
    }

    @Override
    public void onConnectionChanged(XWindowHost host, boolean connected) {
        XFloatingWindow w = windows.get(host.display());
        if (w != null) w.setConnectedDot(connected);
    }

    @Override
    public void onStripVisibilityRequested(XWindowHost host, boolean visible) {
        XFloatingWindow w = windows.get(host.display());
        if (w != null) setActive(w);
        strip.setVisibility(visible ? VISIBLE : GONE);
    }

    // ---- strip softkey (level layar, di atas keyboard) ----

    private LinearLayout buildStrip(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xF7232C4E);
        root.setGravity(Gravity.CENTER_VERTICAL);

        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        root.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.addView(stripToggle("CTRL", KeyEvent.META_CTRL_ON));
        row.addView(stripToggle("ALT", KeyEvent.META_ALT_ON));
        row.addView(stripKey("ESC", KeyEvent.KEYCODE_ESCAPE));
        row.addView(stripKey("TAB", KeyEvent.KEYCODE_TAB));
        row.addView(stripKey("HOME", KeyEvent.KEYCODE_MOVE_HOME));
        row.addView(stripKey("END", KeyEvent.KEYCODE_MOVE_END));
        row.addView(stripKey("PGUP", KeyEvent.KEYCODE_PAGE_UP));
        row.addView(stripKey("PGDN", KeyEvent.KEYCODE_PAGE_DOWN));
        row.addView(stripKey("←", KeyEvent.KEYCODE_DPAD_LEFT));
        row.addView(stripKey("↑", KeyEvent.KEYCODE_DPAD_UP));
        row.addView(stripKey("↓", KeyEvent.KEYCODE_DPAD_DOWN));
        row.addView(stripKey("→", KeyEvent.KEYCODE_DPAD_RIGHT));
        for (int i = 1; i <= 12; i++) {
            final int code = KeyEvent.KEYCODE_F1 + (i - 1);
            row.addView(stripKey("F" + i, code));
        }

        TextView hide = stripButton("⌄");
        hide.setOnClickListener(v -> hideKeyboardAndStrip());
        root.addView(hide);
        return root;
    }

    private TextView stripButton(String label) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextColor(0xFFE8EBFA);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(7), dp(10), dp(7));
        return b;
    }

    private TextView stripKey(String label, int keyCode) {
        TextView b = stripButton(label);
        b.setOnClickListener(v -> {
            if (active == null) return;
            active.host().dispatchKey(keyCode, latchedMeta);
            clearLatch();
        });
        return b;
    }

    private TextView stripToggle(String label, int meta) {
        TextView b = stripButton(label);
        b.setOnClickListener(v -> {
            if ((latchedMeta & meta) != 0) {
                latchedMeta &= ~meta;
                b.setTextColor(0xFFE8EBFA);
            } else {
                latchedMeta |= meta;
                b.setTextColor(0xFF3DDC84);
            }
        });
        return b;
    }

    private void clearLatch() {
        latchedMeta = 0;
        // reset warna semua toggle (dua anak pertama baris tombol)
        LinearLayout row = (LinearLayout) ((HorizontalScrollView) strip.getChildAt(0)).getChildAt(0);
        for (int i = 0; i < 2 && i < row.getChildCount(); i++)
            ((TextView) row.getChildAt(i)).setTextColor(0xFFE8EBFA);
    }

    private void hideKeyboardAndStrip() {
        strip.setVisibility(GONE);
        if (active != null) {
            InputMethodManager imm = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(active.host().view().getWindowToken(), 0);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
