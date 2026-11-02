package id.hawx.astro.geomac.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Pill mode basemap Atlas gaya OSIRIS (batch fix+atlas 2026-11-02):
 * pill gelap rata kiri di atas bilah tab, tombol aktif berbingkai emas
 * + ikon/teks emas, non-aktif abu tanpa bingkai. Hanya 2 mode yang
 * Atlas punya (Jalan + Satelit) — TANPA tombol bulan/night karena Atlas
 * tidak punya sumber tile gelap (tombol mati = fitur palsu).
 *
 * Pola sama seperti AtlasBottomBar: daftar mode TERPUSAT di pemanggil
 * (GeoMac.wireModePill); tambah mode = tambah satu objek Mode.
 * Status aktif diatur pemanggil (setActive saat basemap berubah).
 */
public class AtlasModePill {

    /** Satu entri mode. Tambah mode = tambah satu objek ini. */
    public static class Mode {
        public final String id;
        public final int labelRes;
        public final int iconRes;
        public final Runnable action;

        public Mode(String id, int labelRes, int iconRes, Runnable action) {
            this.id = id;
            this.labelRes = labelRes;
            this.iconRes = iconRes;
            this.action = action;
        }
    }

    private static final int GOLD = 0xFFD4AF37;
    private static final int GREY = 0xFF8A8A95;
    private static final int BTN_BG = 0xFF12121A;

    private final List<MaterialButton> buttons = new ArrayList<>();
    private final List<String> ids = new ArrayList<>();
    private String activeId = null;

    public AtlasModePill(LinearLayout container, List<Mode> modes) {
        Context ctx = container.getContext();
        for (Mode mode : modes) {
            MaterialButton b = new MaterialButton(ctx);
            b.setBackgroundTintList(ColorStateList.valueOf(BTN_BG));
            b.setCornerRadius(dp(ctx, 12));
            b.setIconResource(mode.iconRes);
            b.setIconSize(dp(ctx, 16));
            b.setText(mode.labelRes);
            b.setTextSize(12);
            b.setLetterSpacing(0.08f);
            b.setAllCaps(true);
            b.setInsetTop(0);
            b.setInsetBottom(0);
            // Tombol dipadatkan (40dp, ikon 16dp — dulu 44dp/18dp) supaya
            // pill mode muat berdampingan dengan tombol aksi peta dalam
            // satu baris bar tunggal (batch #7+RECON 2026-11-02).
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 40));
            lp.setMarginEnd(dp(ctx, 4));
            b.setLayoutParams(lp);
            final String id = mode.id;
            final Runnable action = mode.action;
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

    /** Tandai mode aktif (bingkai emas + ikon/teks emas). */
    public void setActive(String id) {
        activeId = id;
        refresh();
    }

    /** Id mode yang sedang aktif (null bila tidak ada). */
    public String getActiveId() {
        return activeId;
    }

    private void refresh() {
        for (int i = 0; i < buttons.size(); i++) {
            boolean on = ids.get(i).equals(activeId);
            MaterialButton b = buttons.get(i);
            int c = on ? GOLD : GREY;
            b.setTextColor(c);
            b.setIconTint(ColorStateList.valueOf(c));
            // Bingkai emas hanya untuk tombol aktif (ala OSIRIS); tombol
            // non-aktif tanpa stroke supaya pill tetap tenang.
            b.setStrokeColor(ColorStateList.valueOf(GOLD));
            b.setStrokeWidth(on ? dp(b.getContext(), 2) : 0);
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
