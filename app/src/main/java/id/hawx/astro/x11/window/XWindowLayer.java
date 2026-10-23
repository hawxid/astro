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
    private FrameLayout ekBox;
    private final Map<Integer, XFloatingWindow> windows = new HashMap<>();
    private final Map<Integer, Button> chips = new HashMap<>();
    private XFloatingWindow active;
    private KillListener killListener;

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

        strip = buildEkStrip(ctx);
        LayoutParams stripLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(45));
        stripLp.gravity = Gravity.BOTTOM;
        addView(strip, stripLp);
        strip.setVisibility(GONE);
    }

    public void setKillListener(KillListener l) {
        killListener = l;
    }

    // ---- akses statis buat serah-terima (Spawn membuka display ini) ----
    private static XWindowLayer sActive;

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sActive = this;
    }

    @Override protected void onDetachedFromWindow() {
        if (sActive == this) sActive = null;
        super.onDetachedFromWindow();
    }

    /** Tutup jendela tertanam untuk display ini TANPA membunuh (viewer
     *  fullscreen mengambil alih koneksinya). No-op bila tak ada. */
    public static void closeForDisplay(int display) {
        XWindowLayer l = sActive;
        if (l == null) return;
        XFloatingWindow w = l.windows.get(display);
        if (w != null) {
            id.hawx.astro.x11.X11DebugLog.log("HANDOFF",
                    "layer closeForDisplay d=" + display);
            l.closeWindow(w, false);
        }
    }

    // ---- siklus jendela ----

    public void openWindow(Activity activity, int display, String title) {
        XFloatingWindow existing = windows.get(display);
        if (existing != null) {
            restoreWindow(existing);
            return;
        }
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "openWindow mulai display=" + display);
      try {
        XWindowHost host = new XWindowHost(activity, display);
        XFloatingWindow win = new XFloatingWindow(activity, host, title);
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "window jadi display=" + display);
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
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "addView ok display=" + display);
        windows.put(display, win);
        setActive(win);
        host.attach();
      } catch (Throwable t) {
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "openWindow GAGAL: "
                + android.util.Log.getStackTraceString(t));
      }
    }

    public void closeWindow(XFloatingWindow win, boolean kill) {
        int display = win.host().display();
        win.host().detach();
        windowsBox.removeView(win);
        windows.remove(display);
        Button chip = chips.remove(display);
        if (chip != null) chipsBar.removeView(chip);
        if (ekBox != null && ekBox.getChildCount() > 0
                && ekBox.getChildAt(0) == win.host().extraKeysView())
            ekBox.removeAllViews();
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
        // Tap konten = FOKUS SAJA (aturan Hawx — keyboard HANYA dari
        // tombol ⌨ di bar judul jendela; tap micu keyboard sudah
        // dimatikan permanen buat semua badan jendela kita).
        if (active != w) setActive(w);
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
        if (visible) {
            if (w != null) setActive(w);
            android.view.View ek = host.extraKeysView();
            if (ek.getParent() != null)
                ((ViewGroup) ek.getParent()).removeView(ek);
            ek.setBackgroundColor(0xF7232C4E);
            ekBox.removeAllViews();
            ekBox.addView(ek, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            strip.setVisibility(VISIBLE);
            strip.bringToFront();
        } else {
            strip.setVisibility(GONE);
        }
    }

    // ---- strip softkey: EK ASLI Lorie milik jendela aktif (disatukan
    //  2026-10-24 — strip custom lama pensiun; posisi bener gratis karena
    //  strip ini anak layout di activity adjustResize: duduk tepat di
    //  atas keyboard, tanpa overlay / matematika kbTop) ----

    private LinearLayout buildEkStrip(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xF7232C4E);
        root.setGravity(Gravity.CENTER_VERTICAL);
        ekBox = new FrameLayout(ctx);
        root.addView(ekBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView hide = stripButton("\u2304");
        hide.setOnClickListener(v -> hideKeyboardAndStrip());
        root.addView(hide);
        return root;
    }

    private TextView stripButton(String label) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextColor(0xFFE8EBFA);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(7), dp(10), dp(7));
        return b;
    }

    private void hideKeyboardAndStrip() {
        strip.setVisibility(GONE);
        if (active != null) active.host().toggleExtraKeys(false, false);
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
