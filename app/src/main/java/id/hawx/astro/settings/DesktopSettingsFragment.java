package id.hawx.astro.settings;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import id.hawx.astro.x11.XSessionManager;

/**
 * Desktop — pengatur antarmuka (kategori System di menu Applications,
 * keputusan Hawx 2026-10-24). Isi halaman = sub-sub accordion yang
 * bisa diperluas/dipersempit: Skala (DPI nilai + skala global klasik),
 * Atur Ikon Desktop (paket pensil penuh), Dock (pemilihan item).
 */
public class DesktopSettingsFragment extends Fragment {

    private XSessionManager mgr;
    private TextView wpState;

    private final androidx.activity.result.ActivityResultLauncher<String> pickImage =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        try {
                            java.io.InputStream in = requireContext()
                                    .getContentResolver().openInputStream(uri);
                            if (in == null) return;
                            java.io.File dir = new java.io.File("/sdcard/Astro");
                            if (!dir.exists()) dir.mkdirs();
                            java.io.File out =
                                    new java.io.File(dir, "wallpaper.jpg");
                            java.io.OutputStream os =
                                    new java.io.FileOutputStream(out);
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                            os.close();
                            in.close();
                            new id.hawx.astro.utils.Core(requireContext())
                                    .putString(id.hawx.astro.dashboard.Dashboard.PREF_WALLPAPER,
                                            out.getAbsolutePath());
                            if (wpState != null) wpState.setText(
                                    "Kustom: wallpaper.jpg (buka Dashboard untuk melihat)");
                            Toast.makeText(requireContext(),
                                    "Wallpaper dipasang", Toast.LENGTH_SHORT).show();
                        } catch (Exception ex) {
                            Toast.makeText(requireContext(),
                                    "Gagal memasang: " + ex.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Kepala sub accordion + badannya; kepala diketuk = lipat/buka. */
    private LinearLayout section(LinearLayout root, String title, boolean open) {
        LinearLayout hdr = new LinearLayout(requireContext());
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, dp(18), 0, dp(6));
        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setTextColor(0xFFE8EBFA);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        hdr.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = new TextView(requireContext());
        arrow.setText(open ? "⌄" : "›");
        arrow.setTextColor(0xFF8B93B8);
        arrow.setTextSize(16);
        hdr.addView(arrow);
        root.addView(hdr);

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        root.addView(body);
        hdr.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            arrow.setText(show ? "⌄" : "›");
        });
        return body;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mgr = XSessionManager.get(requireContext());
        id.hawx.astro.utils.Core prefs = new id.hawx.astro.utils.Core(requireContext());

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setBackgroundColor(0xFF0A0F1E);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(requireContext());
        title.setText("Desktop");
        title.setTextColor(0xFFE8EBFA);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(requireContext());
        sub.setText("Pengatur antarmuka — semua setelan tampilan tinggal di sini.");
        sub.setTextColor(0xFF8B93B8);
        sub.setTextSize(12);
        sub.setPadding(0, dp(2), 0, dp(2));
        root.addView(sub);

        // ================= sub: Skala =================
        LinearLayout skala = section(root, "Skala", true);
        TextView dpiLabel = new TextView(requireContext());
        dpiLabel.setText("Skala teks GTK (DPI) — nilai, bukan slider (keputusan Hawx). "
                + "GTK nurut lewat Xft.dpi; xterm & klasik nurut lewat ukuran font "
                + "pas jendela dibuka.");
        dpiLabel.setTextColor(0xFF8B93B8);
        dpiLabel.setTextSize(12);
        dpiLabel.setPadding(0, 0, 0, dp(6));
        skala.addView(dpiLabel);
        LinearLayout dpiRow = new LinearLayout(requireContext());
        dpiRow.setOrientation(LinearLayout.HORIZONTAL);
        dpiRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText dpiField = new EditText(requireContext());
        dpiField.setInputType(InputType.TYPE_CLASS_NUMBER);
        dpiField.setText(String.valueOf(mgr.getDpi()));
        dpiField.setTextColor(0xFFE8EBFA);
        dpiField.setTextSize(15);
        dpiField.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        dpiRow.addView(dpiField);
        Button apply = new Button(requireContext());
        apply.setText("Terapkan");
        apply.setTextSize(12);
        apply.setOnClickListener(v -> {
            int val;
            try {
                val = Integer.parseInt(dpiField.getText().toString().trim());
            } catch (NumberFormatException ex) {
                Toast.makeText(requireContext(), "Nilai DPI tidak valid",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            int clamped = Math.max(96, Math.min(400, val));
            if (clamped != val) {
                dpiField.setText(String.valueOf(clamped));
                Toast.makeText(requireContext(),
                        "DPI dikunci ke rentang 96–400", Toast.LENGTH_SHORT).show();
            }
            mgr.setDpi(clamped);
            mgr.applyDpiLive((ok, msg) ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show());
        });
        dpiRow.addView(apply);
        skala.addView(dpiRow);

        // ================= sub: Atur Ikon Desktop (paket pensil) ====
        LinearLayout ikon = section(root, "Atur Ikon Desktop", false);
        TextView th = new TextView(requireContext());
        th.setText("Tools bawaan");
        th.setTextColor(0xFF8B93B8);
        th.setTextSize(12);
        ikon.addView(th);
        for (Object[] meta : id.hawx.astro.dashboard.Dashboard.toolMetas()) {
            String key = id.hawx.astro.dashboard.Dashboard.PREF_TOOL + meta[0];
            com.google.android.material.switchmaterial.SwitchMaterial sw =
                    new com.google.android.material.switchmaterial.SwitchMaterial(requireContext());
            sw.setText(getString((Integer) meta[1]).replace('\n', ' '));
            sw.setTextSize(14);
            sw.setChecked(prefs.contains(key) ? prefs.getBoolean(key) : (Boolean) meta[2]);
            sw.setPadding(0, dp(8), 0, dp(8));
            sw.setOnCheckedChangeListener((b, checked) -> prefs.putBoolean(key, checked));
            ikon.addView(sw);
        }
        TextView lh2 = new TextView(requireContext());
        lh2.setText("Launcher");
        lh2.setTextColor(0xFF8B93B8);
        lh2.setTextSize(12);
        lh2.setPadding(0, dp(10), 0, 0);
        ikon.addView(lh2);
        id.hawx.astro.x11.LauncherStore store =
                new id.hawx.astro.x11.LauncherStore(requireContext());
        for (id.hawx.astro.x11.LauncherEntry e : store.list()) {
            com.google.android.material.switchmaterial.SwitchMaterial sw =
                    new com.google.android.material.switchmaterial.SwitchMaterial(requireContext());
            sw.setText(e.name);
            sw.setTextSize(14);
            sw.setChecked(!e.hidden);
            sw.setPadding(0, dp(8), 0, dp(8));
            sw.setOnCheckedChangeListener((b, checked) -> {
                e.hidden = !checked;
                store.upsert(e);
                store.save();
            });
            ikon.addView(sw);
        }
        Button addL = new Button(requireContext());
        addL.setText("+ Add launcher");
        addL.setTextSize(13);
        addL.setOnClickListener(v -> startActivity(new android.content.Intent(
                requireContext(), id.hawx.astro.x11.LauncherEditActivity.class)));
        ikon.addView(addL);

        // ================= sub: Dock =================
        LinearLayout dockSec = section(root, "Dock", false);
        TextView dh = new TextView(requireContext());
        dh.setText("Pilih ikon tetap yang tampil di dock (pintasan jendela aktif selalu ikut). "
                + "Berlaku pas Dashboard dibuka lagi.");
        dh.setTextColor(0xFF8B93B8);
        dh.setTextSize(12);
        dh.setPadding(0, 0, 0, dp(6));
        dockSec.addView(dh);
        dockSec.addView(dockSwitch(prefs, "terminal", "Terminal"));
        dockSec.addView(dockSwitch(prefs, "viewer", "Layar X (viewer)"));
        // Label mengikuti fungsi baru tombol 🧾 (dulu buka halaman
        // PoC "Sesi & Log", sekarang pintasan tambah launcher);
        // kunci pref "sessions" dipertahankan supaya pref lama
        // tidak rusak.
        dockSec.addView(dockSwitch(prefs, "sessions", "Tambah Launcher"));

        // ================= sub: Wallpaper (v1) =================
        LinearLayout wpSec = section(root, "Wallpaper", false);
        TextView wpInfo = new TextView(requireContext());
        wpInfo.setText("Gambar dipilih dari galeri/berkas, disalin ke "
                + "/sdcard/Astro/wallpaper.jpg, lalu dipakai Dashboard "
                + "(center-crop, tanpa gepeng).");
        wpInfo.setTextColor(0xFF8B93B8);
        wpInfo.setTextSize(12);
        wpInfo.setPadding(0, 0, 0, dp(6));
        wpSec.addView(wpInfo);
        wpState = new TextView(requireContext());
        String cur = prefs.getString(
                id.hawx.astro.dashboard.Dashboard.PREF_WALLPAPER);
        wpState.setText(cur == null || cur.isEmpty()
                ? "Saat ini: bawaan" : "Kustom: wallpaper.jpg");
        wpState.setTextColor(0xFF8B93B8);
        wpState.setTextSize(12);
        wpState.setPadding(0, 0, 0, dp(6));
        wpSec.addView(wpState);
        Button pick = new Button(requireContext());
        pick.setText("Pilih gambar");
        pick.setTextSize(13);
        pick.setOnClickListener(v -> pickImage.launch("image/*"));
        wpSec.addView(pick);
        TextView dimLabel = new TextView(requireContext());
        dimLabel.setTextColor(0xFF8B93B8);
        dimLabel.setTextSize(12);
        dimLabel.setPadding(0, dp(8), 0, 0);
        wpSec.addView(dimLabel);
        android.widget.SeekBar dimBar = new android.widget.SeekBar(requireContext());
        dimBar.setMax(80);
        int curDim = prefs.getInt(
                id.hawx.astro.dashboard.Dashboard.PREF_DIM, 0);
        dimBar.setProgress(curDim);
        dimLabel.setText("Redupkan wallpaper: " + curDim
                + "% (gambar terang jadi gelap, ikon kebaca)");
        dimBar.setOnSeekBarChangeListener(
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            android.widget.SeekBar sb, int v, boolean fromUser) {
                        prefs.putInt(id.hawx.astro.dashboard.Dashboard.PREF_DIM, v);
                        dimLabel.setText("Redupkan wallpaper: " + v
                                + "% (gambar terang jadi gelap, ikon kebaca)");
                    }
                    @Override public void onStartTrackingTouch(
                            android.widget.SeekBar sb) {}
                    @Override public void onStopTrackingTouch(
                            android.widget.SeekBar sb) {}
                });
        wpSec.addView(dimBar);
        Button reset = new Button(requireContext());
        reset.setText("Kembalikan default");
        reset.setTextSize(13);
        reset.setOnClickListener(v -> {
            prefs.putString(
                    id.hawx.astro.dashboard.Dashboard.PREF_WALLPAPER, "");
            if (wpState != null) wpState.setText("Saat ini: bawaan");
            Toast.makeText(requireContext(),
                    "Wallpaper kembali ke bawaan", Toast.LENGTH_SHORT).show();
        });
        wpSec.addView(reset);

        return scroll;
    }

    private View dockSwitch(id.hawx.astro.utils.Core prefs, String name, String label) {
        com.google.android.material.switchmaterial.SwitchMaterial sw =
                new com.google.android.material.switchmaterial.SwitchMaterial(requireContext());
        sw.setText(label);
        sw.setTextSize(14);
        String key = "dock_item_" + name;
        sw.setChecked(!prefs.contains(key) || prefs.getBoolean(key));
        sw.setPadding(0, dp(8), 0, dp(8));
        sw.setOnCheckedChangeListener((b, checked) -> prefs.putBoolean(key, checked));
        return sw;
    }
}
