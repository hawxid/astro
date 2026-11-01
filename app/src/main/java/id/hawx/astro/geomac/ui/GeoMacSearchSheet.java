package id.hawx.astro.geomac.ui;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import id.hawx.astro.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Panel pencarian Atlas gaya OSIRIS (re-UI 2026-11-01).
 * Mengikuti pola LayerSheet: BottomSheetDialogFragment + host callback —
 * sheet tidak pernah mencari sendiri; semua aksi diteruskan ke Host
 * (GeoMac), sehingga mesin runLookup dan gate GPS/B6 tidak berubah.
 *
 * Isi (meniru panel SEARCH OSIRIS): judul SEARCH + tombol tutup,
 * field dengan magnifier emas, baris "Your location" (START HERE),
 * dan chip TRY berisi BSSID lookup terakhir yang pernah ditemukan.
 * TRY memakai BSSID tersimpan — BUKAN nama tempat/koordinat — karena
 * runLookup hanya melayani BSSID (AppleWloc + mylnikov); preset nama
 * tempat akan selalu gagal ("no results") sehingga tidak ditawarkan.
 */
public class GeoMacSearchSheet extends BottomSheetDialogFragment {

    public interface Host {
        void onSearchQuery(String q);
        void onYourLocation();
    }

    private Host host;
    private final List<String> tryQueries = new ArrayList<>();

    public void bind(Host host, List<String> tryQueries) {
        this.host = host;
        this.tryQueries.clear();
        if (tryQueries != null) {
            this.tryQueries.addAll(tryQueries);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (host == null) {
            // Hanya terjangkau bila sistem me-recreate sheet tanpa bind();
            // GeoMac selalu bind sheet baru sebelum show().
            return new View(requireContext());
        }
        float density = requireContext().getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        int gold = color(R.color.astro_accent);
        int dim = color(R.color.grey);

        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, Math.round(24 * density));
        root.setBackgroundColor(color(R.color.dialog_background_color));
        scroll.addView(root);

        // Baris judul: SEARCH + tutup.
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(requireContext());
        title.setText(R.string.atlas_search_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        TextView close = new TextView(requireContext());
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        close.setOnClickListener(v -> dismiss());
        titleRow.addView(title);
        titleRow.addView(close);
        root.addView(titleRow);

        // Field: hint BSSID (makna asli field Atlas), magnifier emas.
        TextInputLayout fieldLayout = new TextInputLayout(requireContext());
        fieldLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        fieldLayout.setHint(R.string.geomac_search_hint);
        fieldLayout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        fieldLayout.setEndIconDrawable(R.drawable.search);
        fieldLayout.setEndIconTintList(ColorStateList.valueOf(gold));
        TextInputEditText field = new TextInputEditText(requireContext());
        field.setMaxLines(1);
        field.setTypeface(Typeface.MONOSPACE);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        fieldLayout.addView(field);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldLp.topMargin = Math.round(12 * density);
        fieldLayout.setLayoutParams(fieldLp);
        fieldLayout.setEndIconOnClickListener(v -> submitFromField(field));
        field.setOnEditorActionListener((v, actionId, event) -> {
            submitFromField(field);
            return true;
        });
        root.addView(fieldLayout);

        // START HERE: baris "Your location".
        root.addView(sectionLabel(R.string.atlas_search_start_here));
        LinearLayout locRow = new LinearLayout(requireContext());
        locRow.setOrientation(LinearLayout.HORIZONTAL);
        locRow.setGravity(Gravity.CENTER_VERTICAL);
        locRow.setClickable(true);
        locRow.setFocusable(true);
        GradientDrawable locBg = new GradientDrawable();
        locBg.setColor(color(R.color.light_lite_contrast));
        locBg.setCornerRadius(Math.round(12 * density));
        locRow.setBackground(locBg);
        int rowPad = Math.round(12 * density);
        locRow.setPadding(rowPad, rowPad, rowPad, rowPad);
        View goldBar = new View(requireContext());
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                Math.round(3 * density), LinearLayout.LayoutParams.MATCH_PARENT);
        goldBar.setLayoutParams(barLp);
        goldBar.setBackgroundColor(gold);
        ImageView locIcon = new ImageView(requireContext());
        locIcon.setImageResource(R.drawable.point);
        locIcon.setImageTintList(ColorStateList.valueOf(gold));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Math.round(20 * density), Math.round(20 * density));
        iconLp.leftMargin = Math.round(10 * density);
        locIcon.setLayoutParams(iconLp);
        LinearLayout locTexts = new LinearLayout(requireContext());
        locTexts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.leftMargin = Math.round(10 * density);
        locTexts.setLayoutParams(textsLp);
        TextView locTitle = new TextView(requireContext());
        locTitle.setText(R.string.atlas_search_your_location);
        locTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        locTitle.setTypeface(null, Typeface.BOLD);
        TextView locSub = new TextView(requireContext());
        locSub.setText(R.string.atlas_search_your_location_sub);
        locSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        locSub.setTextColor(dim);
        locTexts.addView(locTitle);
        locTexts.addView(locSub);
        locRow.addView(goldBar);
        locRow.addView(locIcon);
        locRow.addView(locTexts);
        locRow.setOnClickListener(v -> {
            host.onYourLocation();
            dismiss();
        });
        root.addView(locRow);

        // TRY: chip BSSID lookup terakhir (atau keterangan bila belum ada).
        root.addView(sectionLabel(R.string.atlas_search_try));
        if (tryQueries.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.atlas_search_no_recent);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            empty.setTextColor(dim);
            root.addView(empty);
        } else {
            HorizontalScrollView chipScroll = new HorizontalScrollView(requireContext());
            chipScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout chipRow = new LinearLayout(requireContext());
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            for (String bssid : tryQueries) {
                Chip chip = new Chip(requireContext());
                chip.setText(bssid);
                chip.setTypeface(Typeface.MONOSPACE);
                chip.setTextColor(gold);
                chip.setChipBackgroundColor(
                        ColorStateList.valueOf(color(R.color.light_lite_contrast)));
                chip.setChipStrokeColor(ColorStateList.valueOf(color(R.color.grey)));
                chip.setChipStrokeWidth(Math.round(1 * density));
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                chipLp.rightMargin = Math.round(8 * density);
                chip.setLayoutParams(chipLp);
                chip.setOnClickListener(v -> {
                    host.onSearchQuery(bssid);
                    dismiss();
                });
                chipRow.addView(chip);
            }
            chipScroll.addView(chipRow);
            root.addView(chipScroll);
        }

        return scroll;
    }

    private void submitFromField(TextInputEditText field) {
        String q = field.getText() == null ? "" : field.getText().toString().trim();
        if (q.isEmpty()) return;
        host.onSearchQuery(q);
        dismiss();
    }

    private TextView sectionLabel(int textRes) {
        TextView label = new TextView(requireContext());
        label.setText(textRes);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTypeface(null, Typeface.BOLD);
        label.setLetterSpacing(0.08f);
        label.setTextColor(color(R.color.grey));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        float density = requireContext().getResources().getDisplayMetrics().density;
        lp.topMargin = Math.round(20 * density);
        lp.bottomMargin = Math.round(6 * density);
        label.setLayoutParams(lp);
        return label;
    }

    private int color(int res) {
        return requireContext().getResources().getColor(res, requireContext().getTheme());
    }
}
