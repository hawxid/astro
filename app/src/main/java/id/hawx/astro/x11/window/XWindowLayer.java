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
    private final LinearLayout strip;
    private FrameLayout ekBox;
    private final Map<Integer, XFloatingWindow> windows = new HashMap<>();
    private XFloatingWindow active;
    private KillListener killListener;
    private Runnable windowsChangedListener;

    public XWindowLayer(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setClickable(false);

        windowsBox = new FrameLayout(ctx);
        addView(windowsBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

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

    public void setWindowsChangedListener(Runnable r) {
        windowsChangedListener = r;
    }

    private void fireWindowsChanged() {
        if (windowsChangedListener != null) windowsChangedListener.run();
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
    /** Status view jendela buat tasklist/dock di Dashboard. */
    public static boolean isWindowOpen(int display) {
        XWindowLayer l = sActive;
        if (l == null) return false;
        XFloatingWindow w = l.windows.get(display);
        return w != null && w.getVisibility() == VISIBLE;
    }

    public static boolean isWindowMinimized(int display) {
        XWindowLayer l = sActive;
        if (l == null) return false;
        XFloatingWindow w = l.windows.get(display);
        return w != null && w.getVisibility() != VISIBLE;
    }

    /** Sembunyikan jendela display ini (dari popup dock). */
    public static void minimizeForDisplay(int display) {
        XWindowLayer l = sActive;
        if (l == null) return;
        XFloatingWindow w = l.windows.get(display);
        if (w != null) l.onMinimizeClicked(w);
    }

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
        XOverlayWindows.hideForDisplay(display); // rumah overlay melepas
        XFloatingWindow existing = windows.get(display);
        if (existing != null) {
            restoreWindow(existing);
            fireWindowsChanged();
            id.hawx.astro.x11.XSessionManager.get(activity).watchEntryDisplay(display);
            return;
        }
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "openWindow mulai display=" + display);
      try {
        XWindowHost host = new XWindowHost(activity, display);
        XFloatingWindow win = new XFloatingWindow(activity, host, title);
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "window jadi display=" + display);
        win.setListener(this);
        host.setListener(this);

        int w = (int) (getWidth() * 0.43f);
        int h = (int) (getHeight() * 0.29f);
        if (w <= 0) { // layer belum ter-layout (dibuka dari onCreate) — pakai metrik layar
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = (int) (dm.widthPixels * 0.43f);
            h = (int) (dm.heightPixels * 0.25f);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = dp(14) + display * dp(18);
        lp.topMargin = dp(52) + display * dp(18);
        windowsBox.addView(win, lp);
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "addView ok display=" + display);
        windows.put(display, win);
        setActive(win);
        host.attach();
        fireWindowsChanged();
        id.hawx.astro.x11.XSessionManager.get(activity).watchEntryDisplay(display);
      } catch (Throwable t) {
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "openWindow GAGAL: "
                + android.util.Log.getStackTraceString(t));
      }
    }

    public void closeWindow(XFloatingWindow win, boolean kill) {
        int display = win.host().display();
        id.hawx.astro.x11.XSessionManager.get(getContext()).unwatchEntryDisplay(display);
        win.host().detach();
        windowsBox.removeView(win);
        windows.remove(display);
        if (ekBox != null && ekBox.getChildCount() > 0
                && ekBox.getChildAt(0) == win.host().extraKeysView())
            ekBox.removeAllViews();
        if (active == win) {
            active = null;
            strip.setVisibility(GONE);
        }
        if (kill && killListener != null) killListener.onWindowKillRequested(display);
        fireWindowsChanged();
    }

    private void restoreWindow(XFloatingWindow win) {
        win.setVisibility(VISIBLE);
        setActive(win);
    }

    private void setActive(XFloatingWindow win) {
        boolean changed = active != win;
        active = win;
        win.bringToFront();
        // strip selalu di atas jendela
        strip.bringToFront();
        if (changed) {
            // Permukaan SurfaceView ditumpuk menurut URUTAN LAHIR,
            // bukan z-order view: bringToFront cuma mengangkat RANGKA
            // jendela, konten X jendela yang dibuka belakangan tetap
            // menutupi (temuan Hawx: rangka :1 ke depan, kontennya
            // ketahan di belakang konten :2). Lahirkan ulang permukaan
            // jendela aktif supaya ia paling akhir = paling atas.
            final android.view.View surf = win.host().view();
            surf.setVisibility(View.INVISIBLE);
            surf.post(() -> surf.setVisibility(View.VISIBLE));
        }
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
        // Chip restore pojok DIBUANG (tahap tasklist XFCE): jendela
        // tersembunyi diwakili tab panel atas + pintasan dock.
        w.setVisibility(GONE);
        if (active == w) strip.setVisibility(GONE);
        fireWindowsChanged();
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
            // RESIZE LAYAR, bukan geser posisi (model Hawx): pas softkey
            // muncul, area layar desktop (flContent) menyusut setinggi
            // strip — dock yang berjangkar di bawah IKUT NAIK dan
            // beristirahat tepat di atas softkey. Urutan dari bawah:
            // keyboard -> softkey -> dock.
            setScreenInset(true);
            strip.setVisibility(VISIBLE);
            strip.bringToFront();
            // Bangun ulang EK di rumah barunya, lalu PAKSA tombol ikon
            // (panah dkk) jadi teks glif: di konteks MainActivity ikon
            // foreground EK tidak ter-render (tombolnya ada tapi kosong
            // — temuan Hawx, panah hilang cuma di layer). Inventaris
            // tombol sekalian ditulis ke log KB buat bukti.
            host.extraKeysView().reload();
            final android.view.View ekF = ek;
            ek.post(() -> {
                StringBuilder keys = new StringBuilder();
                ViewGroup g = (ViewGroup) ekF;
                for (int i = 0; i < g.getChildCount(); i++) {
                    android.view.View ch = g.getChildAt(i);
                    if (!(ch instanceof Button)) continue;
                    Button b = (Button) ch;
                    CharSequence cd = b.getContentDescription();
                    String key = cd != null ? cd.toString() : "?";
                    CharSequence tx = b.getText();
                    keys.append(key).append('=')
                            .append(tx != null ? tx.toString() : "null").append(' ');
                    String glyph = null;
                    if ("UP".equals(key)) glyph = "\u2191";
                    else if ("DOWN".equals(key)) glyph = "\u2193";
                    else if ("LEFT".equals(key)) glyph = "\u2190";
                    else if ("RIGHT".equals(key)) glyph = "\u2192";
                    if (glyph != null && (tx == null || tx.length() == 0)) {
                        b.setForeground(null);
                        b.setText(glyph);
                        b.setTextColor(0xFFFFFFFF);
                    }
                }
                id.hawx.astro.x11.X11DebugLog.log("KB",
                        "layer EK: anak=" + g.getChildCount() + " keys: " + keys);
            });
        } else {
            setScreenInset(false);
            strip.setVisibility(GONE);
        }
    }

    /** Susutkan/kembalikan area layar desktop (flContent) setinggi
     *  strip softkey. Strip sendiri tetap di dasar layer (tepi atas
     *  keyboard); yang bergerak adalah LAYAR di atasnya — persis
     *  perilaku desktop pas panel bawah muncul. */
    private void setScreenInset(boolean on) {
        android.view.View root = getRootView();
        if (root == null) return;
        android.view.View content =
                root.findViewById(id.hawx.astro.R.id.flContent);
        if (content == null) return;
        int h = 0;
        if (on) {
            h = strip.getLayoutParams() != null
                    ? strip.getLayoutParams().height : 0;
            if (h <= 0) h = (int) (45 * getResources().getDisplayMetrics().density);
        }
        content.setPadding(0, 0, 0, h);
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
