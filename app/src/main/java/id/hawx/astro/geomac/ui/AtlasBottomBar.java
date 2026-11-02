package id.hawx.astro.geomac.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;

import id.hawx.astro.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bilah tab Atlas gaya OSIRIS (batch gede 2026-11-01, bentuk final batch
 * BAR FINAL 2026-11-02): pill gelap, border emas, tiap tab = IKON di atas
 * + TULISAN kecil caps di bawahnya persis seperti bar bawah OSIRIS
 * (bukan ikon saja, bukan ikon + teks berdampingan). Tab kini SEARCH +
 * LAYERS + FIT + TRAFFIC + SETTING; tab RECON dihapus (batch BAR FINAL:
 * percuma, toh hanya balik ke menu Osint).
 *
 * Daftar tab TERPUSAT di pemanggil: tambah tab = tambah satu {@link Tab}
 * di daftar yang diberikan ke konstruktor — bilah ini tidak perlu diubah.
 * Tab tidak membuka apa-apa sendiri; aksinya dijalankan pemanggil
 * (SEARCH -> GeoMacSearchSheet, LAYERS -> LayerSheet, FIT -> fitToPins
 * langsung, TRAFFIC -> sheet trafik, SETTING -> sheet pengaturan).
 * Status aktif diatur pemanggil (setActive saat sheet dibuka, clearActive
 * saat sheet ditutup); bilah hanya merender warnanya. Pengecualian:
 * tab FIT bersifat momentary (aksi sekali, tanpa status aktif permanen),
 * dan tab TRAFFIC punya highlight + dot hidup yang mengikuti status
 * TRAFFIC ON (bukan status sheet dibuka) — diatur pemanggil lewat
 * setHighlighted/setTabDot.
 */
public class AtlasBottomBar {

    /** Satu entri tab. Tambah tab = tambah satu objek ini. */
    public static class Tab {
        public final String id;
        public final int labelRes;
        public final int iconRes;
        public final Runnable action;
        public final boolean momentary;

        public Tab(String id, int labelRes, int iconRes, Runnable action) {
            this(id, labelRes, iconRes, action, false);
        }

        public Tab(String id, int labelRes, int iconRes, Runnable action, boolean momentary) {
            this.id = id;
            this.labelRes = labelRes;
            this.iconRes = iconRes;
            this.action = action;
            this.momentary = momentary;
        }
    }

    private static final int GOLD = 0xFFD4AF37;
    private static final int GREY = 0xFF8A8A95;

    private final List<MaterialButton> buttons = new ArrayList<>();
    private final List<String> ids = new ArrayList<>();
    private final Map<String, View> dots = new HashMap<>();
    private final Set<String> highlighted = new HashSet<>();
    private String activeId = null;

    public AtlasBottomBar(LinearLayout container, List<Tab> tabs) {
        Context ctx = container.getContext();
        for (Tab tab : tabs) {
            // Tiap tab dibungkus FrameLayout supaya dot hidup (tab TRAFFIC)
            // bisa menempel di sudut ikon tanpa menggeser tombol.
            FrameLayout wrap = new FrameLayout(ctx);
            LinearLayout.LayoutParams wrapLp =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            wrap.setLayoutParams(wrapLp);
            MaterialButton b = new MaterialButton(ctx);
            // Tab transparan di atas pill (bukan tombol filled): bentuk +
            // ripple bawaan MaterialButton dipertahankan, tint dibuat
            // transparan supaya pill gelap yang terlihat.
            b.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            b.setStrokeWidth(0);
            b.setCornerRadius(dp(ctx, 20));
            b.setIconResource(tab.iconRes);
            b.setIconSize(dp(ctx, 22));
            // Ikon DI ATAS tulisan (persis bar OSIRIS), bukan di kiri teks.
            b.setIconGravity(MaterialButton.ICON_GRAVITY_TOP);
            b.setText(tab.labelRes);
            b.setTextSize(10);
            b.setLetterSpacing(0.08f);
            b.setAllCaps(true);
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setPadding(0, dp(ctx, 8), 0, dp(ctx, 6));
            FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            b.setLayoutParams(btnLp);
            final String id = tab.id;
            final Runnable action = tab.action;
            final boolean momentary = tab.momentary;
            b.setOnClickListener(v -> {
                if (!momentary) setActive(id);
                if (action != null) action.run();
            });
            wrap.addView(b);
            // Dot hidup (default sembunyi): hanya tab TRAFFIC yang
            // dinyalakan pemanggil saat trafik ON.
            View dot = new View(ctx);
            dot.setBackgroundResource(R.drawable.traffic_live_dot);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8));
            dotLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            dotLp.topMargin = dp(ctx, 6);
            dotLp.leftMargin = dp(ctx, 16);
            dot.setLayoutParams(dotLp);
            dot.setVisibility(View.GONE);
            wrap.addView(dot);
            dots.put(id, dot);
            container.addView(wrap);
            buttons.add(b);
            ids.add(id);
        }
        refresh();
    }

    /** Tandai tab aktif (emas). Dipanggil saat sheet tab dibuka. */
    public void setActive(String id) {
        activeId = id;
        refresh();
    }

    /** Hapus status aktif (semua tab abu, kecuali yang di-highlight).
     *  Dipanggil saat sheet ditutup. Highlight tidak ikut terhapus:
     *  tab TRAFFIC tetap emas selama trafik ON. */
    public void clearActive() {
        activeId = null;
        refresh();
    }

    /** Sorot satu tab emas tanpa menjadikannya tab aktif (dipakai tab
     *  TRAFFIC: emas saat trafik ON, apa pun sheet yang sedang dibuka).
     *  Tidak memengaruhi activeId / clearActive. */
    public void setHighlighted(String id, boolean on) {
        if (on) highlighted.add(id);
        else highlighted.remove(id);
        refresh();
    }

    /** Tampilkan/sembunyikan dot hidup di sudut ikon satu tab (dipakai
     *  tab TRAFFIC saat trafik ON). */
    public void setTabDot(String id, boolean visible) {
        View dot = dots.get(id);
        if (dot != null) dot.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Id tab yang sedang aktif (null bila tidak ada). Dipakai GeoMac
     *  supaya listener dismiss sheet lama tidak menghapus status aktif
     *  sheet baru (race saat membuka sheet lain). */
    public String getActiveId() {
        return activeId;
    }

    private void refresh() {
        for (int i = 0; i < buttons.size(); i++) {
            String id = ids.get(i);
            boolean on = id.equals(activeId) || highlighted.contains(id);
            int c = on ? GOLD : GREY;
            buttons.get(i).setTextColor(c);
            buttons.get(i).setIconTint(ColorStateList.valueOf(c));
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
