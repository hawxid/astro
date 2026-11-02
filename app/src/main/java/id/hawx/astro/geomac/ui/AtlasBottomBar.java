package id.hawx.astro.geomac.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Bilah bawah Atlas gaya OSIRIS (batch gede 2026-11-01): pill gelap,
 * border emas, tab aktif emas, sisanya abu. Tab kini SEARCH + LAYERS
 * + RECON (batch #7+RECON 2026-11-02); tab RECON membuka panel RECON
 * in-layout, bukan sheet.
 *
 * Daftar tab TERPUSAT di pemanggil: tambah tab = tambah satu {@link Tab}
 * di daftar yang diberikan ke konstruktor — bilah ini tidak perlu diubah.
 * Tab tidak membuka apa-apa sendiri; aksinya dijalankan pemanggil
 * (SEARCH -> GeoMacSearchSheet, LAYERS -> LayerSheet yang sudah ada,
 * RECON -> panel RECON in-layout).
 * Status aktif diatur pemanggil (setActive saat sheet/panel dibuka,
 * clearActive saat sheet/panel ditutup); bilah hanya merender warnanya.
 */
public class AtlasBottomBar {

    /** Satu entri tab. Tambah tab = tambah satu objek ini. */
    public static class Tab {
        public final String id;
        public final int labelRes;
        public final int iconRes;
        public final Runnable action;

        public Tab(String id, int labelRes, int iconRes, Runnable action) {
            this.id = id;
            this.labelRes = labelRes;
            this.iconRes = iconRes;
            this.action = action;
        }
    }

    private static final int GOLD = 0xFFD4AF37;
    private static final int GREY = 0xFF8A8A95;

    private final List<MaterialButton> buttons = new ArrayList<>();
    private final List<String> ids = new ArrayList<>();
    private String activeId = null;

    public AtlasBottomBar(LinearLayout container, List<Tab> tabs) {
        Context ctx = container.getContext();
        for (Tab tab : tabs) {
            MaterialButton b = new MaterialButton(ctx);
            // Tab transparan di atas pill (bukan tombol filled): bentuk +
            // ripple bawaan MaterialButton dipertahankan, tint dibuat
            // transparan supaya pill gelap yang terlihat.
            b.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            b.setStrokeWidth(0);
            b.setCornerRadius(dp(ctx, 20));
            b.setIconResource(tab.iconRes);
            b.setIconSize(dp(ctx, 18));
            b.setText(tab.labelRes);
            b.setTextSize(12);
            b.setLetterSpacing(0.08f);
            b.setAllCaps(true);
            b.setInsetTop(0);
            b.setInsetBottom(0);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, dp(ctx, 44), 1f);
            b.setLayoutParams(lp);
            final String id = tab.id;
            final Runnable action = tab.action;
            b.setOnClickListener(v -> {
                setActive(id);
                if (action != null) action.run();
            });
            container.addView(b);
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

    /** Hapus status aktif (semua tab abu). Dipanggil saat sheet ditutup. */
    public void clearActive() {
        activeId = null;
        refresh();
    }

    /** Id tab yang sedang aktif (null bila tidak ada). Dipakai GeoMac
     *  supaya listener dismiss sheet lama tidak menghapus status aktif
     *  sheet baru (race saat membuka sheet lain). */
    public String getActiveId() {
        return activeId;
    }

    private void refresh() {
        for (int i = 0; i < buttons.size(); i++) {
            boolean on = ids.get(i).equals(activeId);
            int c = on ? GOLD : GREY;
            buttons.get(i).setTextColor(c);
            buttons.get(i).setIconTint(ColorStateList.valueOf(c));
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
