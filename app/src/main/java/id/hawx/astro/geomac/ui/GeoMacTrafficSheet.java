package id.hawx.astro.geomac.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.switchmaterial.SwitchMaterial;

import id.hawx.astro.R;
import id.hawx.astro.geomac.layers.TrafficLive;

/**
 * Sheet trafik Atlas (batch BAR FINAL 2026-11-02, gaya sheet Lapisan):
 * ketuk tab TRAFFIC SELALU membuka sheet ini, apa pun status on/off.
 * Header = judul + toggle on/off + X; di bawahnya daftar trafik live
 * (logika TrafficLive utuh, dipindahkan ke sini lewat rebind()).
 * Toggle = jalur applyLayerToggle TRAFFIC yang sama (sinkron dengan
 * toggle Traffic di sheet Lapisan). Menutup sheet TIDAK menghentikan
 * trafik (polling + marker/garis peta tetap jalan); sheet dibuka lagi
 * = tabel dirender ulang (rebind + poll berikutnya, maks 2 detik).
 */
public class GeoMacTrafficSheet extends BottomSheetDialogFragment {

    public interface Host {
        void onTrafficToggle(boolean on);
        boolean isTrafficOn();
    }

    private TrafficLive trafficLive;
    private Host host;
    private SwitchMaterial toggle;
    private boolean updating;

    public void bind(TrafficLive trafficLive, Host host) {
        this.trafficLive = trafficLive;
        this.host = host;
    }

    /** Re-read the host state into the toggle (e.g. after a toggle from
     *  the Layer sheet while this sheet is open). */
    public void refresh() {
        if (toggle == null || host == null) return;
        updating = true;
        toggle.setChecked(host.isTrafficOn());
        updating = false;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (trafficLive == null || host == null) {
            // Only reachable on system recreation without bind(); GeoMac
            // always binds a fresh sheet before showing it.
            return new View(requireContext());
        }
        float density = requireContext().getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, Math.round(24 * density));
        root.setBackgroundResource(R.drawable.sheet_bg_gold);

        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(requireContext());
        title.setText(R.string.geomac_traffic_header);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        header.addView(title);

        // Toggle DIBUAT dulu tanpa listener, dicentang dari host, listener
        // dipasang setelahnya — supaya centang awal tidak memicu callback
        // (pola yang sama seperti centang awal chip di GeoMacSearchSheet).
        toggle = new SwitchMaterial(requireContext());
        toggle.setChecked(host.isTrafficOn());
        toggle.setOnCheckedChangeListener((btn, checked) -> {
            if (updating) return;
            if (host != null) host.onTrafficToggle(checked);
        });
        header.addView(toggle);

        // X = tutup sheet (trafik tetap ON bila toggle ON — menutup sheet
        // bukan mematikan trafik, sama seperti X jendela lama).
        ImageButton close = new ImageButton(requireContext());
        close.setImageResource(R.drawable.close);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setImageTintList(ColorStateList.valueOf(
                requireContext().getResources().getColor(R.color.grey, requireContext().getTheme())));
        int closePad = Math.round(8 * density);
        close.setPadding(closePad, closePad, closePad, closePad);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = Math.round(8 * density);
        close.setLayoutParams(closeLp);
        close.setOnClickListener(v -> dismiss());
        header.addView(close);
        root.addView(header);

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setId(R.id.geomac_traffic_rows_scroll);
        LinearLayout rows = new LinearLayout(requireContext());
        rows.setId(R.id.geomac_traffic_rows);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll);

        TextView empty = new TextView(requireContext());
        empty.setId(R.id.geomac_traffic_empty);
        empty.setText(R.string.geomac_traffic_empty);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        empty.setTextColor(requireContext().getResources().getColor(
                R.color.grey, requireContext().getTheme()));
        empty.setVisibility(View.GONE);
        root.addView(empty);

        // Pindahkan tabel TrafficLive ke view sheet ini (rebind). Dibuat
        // SETELAH semua view ditambahkan ke root supaya findViewById di
        // dalam rebind menemukan scroll (handle tidak ada di sheet —
        // di-guard null di TrafficLive).
        trafficLive.rebind(root, rows, empty);
        return root;
    }
}
