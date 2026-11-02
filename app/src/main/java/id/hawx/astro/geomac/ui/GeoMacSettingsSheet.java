package id.hawx.astro.geomac.ui;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import id.hawx.astro.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Sheet pengaturan Atlas (batch BAR FINAL 2026-11-02, gaya sheet Lapisan):
 * dibuka dari tab SETTING (gerigi). Berisi aksi pin (Import, Export,
 * Clear — handler yang sudah ada di GeoMac) + pengaturan API key layer
 * (OpenCellID, Windy, Mapillary — membuka dialog kunci yang sudah ada;
 * dialog Mapillary dibuatkan varian simpan-saja karena dialog lama
 * terikat pin + foto). Membuka kembali menu API key yang dulu diparkir,
 * dengan scope persis ini — tidak ada pengaturan lain di sini.
 */
public class GeoMacSettingsSheet extends BottomSheetDialogFragment {

    public interface Host {
        void onImport();
        void onExport();
        void onClear();
        void onEditApiKey(String key); // "opencellid" | "windy" | "mapillary"
        boolean hasApiKey(String key);
    }

    private Host host;
    private final Map<String, TextView> apiSubs = new HashMap<>();

    public void bind(Host host) {
        this.host = host;
    }

    /** Re-read key status into the API rows (e.g. after a key dialog saved). */
    public void refresh() {
        if (host == null) return;
        for (Map.Entry<String, TextView> e : apiSubs.entrySet()) {
            e.getValue().setText(host.hasApiKey(e.getKey())
                    ? R.string.atlas_settings_key_set
                    : R.string.atlas_settings_key_empty);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (host == null) {
            // Only reachable on system recreation without bind(); GeoMac
            // always binds a fresh sheet before showing it.
            return new View(requireContext());
        }
        float density = requireContext().getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, Math.round(24 * density));
        root.setBackgroundResource(R.drawable.sheet_bg_gold);
        scroll.addView(root);

        TextView title = new TextView(requireContext());
        title.setText(R.string.atlas_settings_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        root.addView(sectionHeader(R.string.atlas_settings_pins));
        root.addView(actionRow(R.string.atlas_settings_import, () -> {
            if (host != null) host.onImport();
        }));
        root.addView(actionRow(R.string.atlas_settings_export, () -> {
            if (host != null) host.onExport();
        }));
        root.addView(actionRow(R.string.atlas_settings_clear, () -> {
            if (host != null) host.onClear();
        }));

        root.addView(sectionHeader(R.string.atlas_settings_apikey));
        root.addView(apiRow(R.string.atlas_settings_key_opencellid, "opencellid"));
        root.addView(apiRow(R.string.atlas_settings_key_windy, "windy"));
        root.addView(apiRow(R.string.atlas_settings_key_mapillary, "mapillary"));

        refresh();
        return scroll;
    }

    private TextView sectionHeader(int textRes) {
        TextView header = new TextView(requireContext());
        header.setText(textRes);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(requireContext().getResources().getColor(
                R.color.grey, requireContext().getTheme()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        float density = requireContext().getResources().getDisplayMetrics().density;
        lp.topMargin = Math.round(16 * density);
        lp.bottomMargin = Math.round(4 * density);
        header.setLayoutParams(lp);
        return header;
    }

    private LinearLayout actionRow(int titleRes, Runnable action) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(10 * density), 0, Math.round(10 * density));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        if (requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true)) {
            row.setForeground(androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), tv.resourceId));
        }
        TextView title = new TextView(requireContext());
        title.setText(titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        row.addView(title);
        TextView chevron = new TextView(requireContext());
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chevron.setTextColor(requireContext().getResources().getColor(
                R.color.grey, requireContext().getTheme()));
        row.addView(chevron);
        // Listener hanya memakai host + action dari parameter (tidak ada
        // self-reference ke row yang sedang dibuat).
        row.setOnClickListener(v -> {
            if (action != null) action.run();
        });
        return row;
    }

    private LinearLayout apiRow(int titleRes, String key) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(8 * density), 0, Math.round(8 * density));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        if (requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true)) {
            row.setForeground(androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), tv.resourceId));
        }
        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(textsLp);
        TextView title = new TextView(requireContext());
        title.setText(titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        texts.addView(title);
        TextView sub = new TextView(requireContext());
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(requireContext().getResources().getColor(
                R.color.grey, requireContext().getTheme()));
        texts.addView(sub);
        apiSubs.put(key, sub);
        row.addView(texts);
        TextView chevron = new TextView(requireContext());
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chevron.setTextColor(requireContext().getResources().getColor(
                R.color.grey, requireContext().getTheme()));
        row.addView(chevron);
        // Listener hanya memakai host + key dari parameter (tidak ada
        // self-reference ke row yang sedang dibuat).
        row.setOnClickListener(v -> {
            if (host != null) host.onEditApiKey(key);
        });
        return row;
    }
}
