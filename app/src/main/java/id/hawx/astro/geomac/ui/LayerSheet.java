package id.hawx.astro.geomac.ui;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.switchmaterial.SwitchMaterial;

import id.hawx.astro.R;
import id.hawx.astro.geomac.layers.AtlasLayer;
import id.hawx.astro.geomac.layers.LayerStore;

import java.util.HashMap;
import java.util.Map;

/**
 * The single place to change Atlas layers (redesign Fase 1).
 * Replaces the two chip groups + fold buttons + Settings satellite switch:
 * basemap as radio rows, layers and features as switch rows, all rendered
 * from LayerStore. User changes go to Host, which gates (Windy key),
 * writes the store, applies, then calls refresh() — the sheet never
 * applies a layer itself, so it can never disagree with the map.
 */
public class LayerSheet extends BottomSheetDialogFragment {

    public interface Host {
        void onLayerToggle(String id, boolean on);
        void onBasemapSelected(boolean satellite);
    }

    private LayerStore store;
    private Host host;
    private boolean updating;
    private final Map<String, SwitchMaterial> switches = new HashMap<>();
    private final Map<String, TextView> subtitles = new HashMap<>();
    private RadioButton streetRadio;
    private RadioButton satelliteRadio;

    public void bind(LayerStore store, Host host) {
        this.store = store;
        this.host = host;
    }

    /** Re-reads the store into the visible rows (e.g. after a gated toggle). */
    public void refresh() {
        if (store == null) return;
        updating = true;
        for (AtlasLayer layer : store.all()) {
            SwitchMaterial sw = switches.get(layer.id);
            if (sw != null) {
                sw.setChecked(layer.isOn());
            }
            TextView sub = subtitles.get(layer.id);
            if (sub != null) {
                sub.setText(layer.source + " · " + (layer.isOn() ? "Aktif" : "Mati"));
            }
        }
        if (streetRadio != null && satelliteRadio != null) {
            streetRadio.setChecked(!store.isSatellite());
            satelliteRadio.setChecked(store.isSatellite());
        }
        updating = false;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (store == null) {
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
        scroll.addView(root);

        TextView title = new TextView(requireContext());
        title.setText(R.string.atlas_sheet_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        root.addView(sectionHeader(R.string.atlas_sheet_basemap));
        RadioGroup basemap = new RadioGroup(requireContext());
        basemap.setOrientation(RadioGroup.VERTICAL);
        streetRadio = new RadioButton(requireContext());
        streetRadio.setId(View.generateViewId());
        streetRadio.setText(R.string.atlas_sheet_basemap_street);
        streetRadio.setChecked(!store.isSatellite());
        satelliteRadio = new RadioButton(requireContext());
        satelliteRadio.setId(View.generateViewId());
        satelliteRadio.setText(R.string.geomac_layer_satellite);
        satelliteRadio.setChecked(store.isSatellite());
        basemap.addView(streetRadio);
        basemap.addView(satelliteRadio);
        basemap.setOnCheckedChangeListener((group, checkedId) -> {
            if (updating) return;
            if (host != null) {
                // State dari checkedId, BUKAN dari isChecked() salah satu
                // tombol: RadioButton tanpa id membuat RadioGroup gagal
                // mutual exclusion (mCheckedId=-1), sehingga radio Jalan
                // yang sudah checked sejak awal tidak pernah bisa ditekan
                // lagi setelah satelit dipilih. Id eksplisit + checkedId
                // menutup kelas bug itu sepenuhnya.
                host.onBasemapSelected(checkedId == satelliteRadio.getId());
            }
        });
        root.addView(basemap);

        root.addView(sectionHeader(R.string.atlas_sheet_layers));
        for (AtlasLayer layer : store.all()) {
            if (layer.kind != AtlasLayer.Kind.FEATURE) {
                root.addView(layerRow(layer));
            }
        }

        root.addView(sectionHeader(R.string.atlas_sheet_features));
        for (AtlasLayer layer : store.all()) {
            if (layer.kind == AtlasLayer.Kind.FEATURE) {
                root.addView(layerRow(layer));
            }
        }

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

    private LinearLayout layerRow(AtlasLayer layer) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float density = requireContext().getResources().getDisplayMetrics().density;
        row.setPadding(0, Math.round(8 * density), 0, Math.round(8 * density));

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(textsLp);
        TextView title = new TextView(requireContext());
        title.setText(layer.titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        TextView sub = new TextView(requireContext());
        sub.setText(layer.source + " · " + (layer.isOn() ? "Aktif" : "Mati"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(requireContext().getResources().getColor(
                R.color.grey, requireContext().getTheme()));
        texts.addView(title);
        texts.addView(sub);

        SwitchMaterial sw = new SwitchMaterial(requireContext());
        sw.setChecked(layer.isOn());
        sw.setOnCheckedChangeListener((btn, checked) -> {
            if (updating) return;
            if (host != null) {
                host.onLayerToggle(layer.id, checked);
            }
        });
        switches.put(layer.id, sw);
        subtitles.put(layer.id, sub);

        row.addView(texts);
        row.addView(sw);
        return row;
    }
}
