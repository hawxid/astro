package id.hawx.astro.settings;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import id.hawx.astro.x11.XSessionManager;

/**
 * Desktop — pengatur antarmuka (kategori System di menu Applications,
 * keputusan Hawx 2026-10-24). Rumah baru pengaturan tampilan yang
 * dulu tercecer di halaman X11 (yang pensiun): ronde pertama berisi
 * slider DPI; isinya bertambah di sini, bukan di halaman lain.
 */
public class DesktopSettingsFragment extends Fragment {

    private XSessionManager mgr;
    private TextView dpiLabel;
    private SeekBar dpiSeek;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mgr = XSessionManager.get(requireContext());

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
        sub.setPadding(0, dp(2), 0, dp(14));
        root.addView(sub);

        // ---- skala DPI (pindahan dari halaman X11; semantik sama:
        // 96..288 langkah 24, Terapkan = setDpi + applyDpiLive) ----
        LinearLayout dpiRow = new LinearLayout(requireContext());
        dpiRow.setOrientation(LinearLayout.HORIZONTAL);
        dpiRow.setGravity(Gravity.CENTER_VERTICAL);
        dpiRow.setPadding(0, dp(10), 0, 0);
        dpiLabel = new TextView(requireContext());
        dpiLabel.setTextColor(0xFFE8EBFA);
        dpiLabel.setTextSize(13);
        dpiSeek = new SeekBar(requireContext());
        dpiSeek.setMax(8); // 96..288 langkah 24
        dpiSeek.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        dpiSeek.setProgress((mgr.getDpi() - 96) / 24);
        dpiRow.addView(dpiLabel);
        dpiRow.addView(dpiSeek);
        Button apply = new Button(requireContext());
        apply.setText("Terapkan");
        apply.setTextSize(12);
        apply.setOnClickListener(v -> {
            mgr.setDpi(96 + dpiSeek.getProgress() * 24);
            mgr.applyDpiLive((ok, msg) ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show());
        });
        dpiRow.addView(apply);
        root.addView(dpiRow);
        updateDpiLabel();
        dpiSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                updateDpiLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                mgr.setDpi(96 + sb.getProgress() * 24);
            }
        });

        // ---- Atur Ikon Desktop: PAKET PENSIL PENUH (keputusan
        // Hawx: bukan cuma tombol add — saklar tampil/sembunyi per
        // tool bawaan + per launcher, datanya sama persis dengan
        // sheet pensil lama & popup ruang kosong) ----
        root.addView(sectionTitle("Atur Ikon Desktop"));
        id.hawx.astro.utils.Core prefs2 = new id.hawx.astro.utils.Core(requireContext());
        TextView th = new TextView(requireContext());
        th.setText("Tools bawaan");
        th.setTextColor(0xFF8B93B8);
        th.setTextSize(12);
        root.addView(th);
        for (Object[] meta : id.hawx.astro.dashboard.Dashboard.toolMetas()) {
            String key = id.hawx.astro.dashboard.Dashboard.PREF_TOOL + meta[0];
            com.google.android.material.switchmaterial.SwitchMaterial sw =
                    new com.google.android.material.switchmaterial.SwitchMaterial(requireContext());
            sw.setText(getString((Integer) meta[1]).replace('\n', ' '));
            sw.setTextSize(14);
            sw.setChecked(prefs2.contains(key) ? prefs2.getBoolean(key) : (Boolean) meta[2]);
            sw.setPadding(0, dp(8), 0, dp(8));
            sw.setOnCheckedChangeListener((b, checked) -> prefs2.putBoolean(key, checked));
            root.addView(sw);
        }
        TextView lh2 = new TextView(requireContext());
        lh2.setText("Launcher");
        lh2.setTextColor(0xFF8B93B8);
        lh2.setTextSize(12);
        lh2.setPadding(0, dp(10), 0, 0);
        root.addView(lh2);
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
            root.addView(sw);
        }
        Button addL = new Button(requireContext());
        addL.setText("+ Add launcher");
        addL.setTextSize(13);
        addL.setOnClickListener(v -> startActivity(new android.content.Intent(
                requireContext(), id.hawx.astro.x11.LauncherEditActivity.class)));
        root.addView(addL);

        // ---- Dock: pemilihan item tetap yang tampil ----
        root.addView(sectionTitle("Dock"));
        TextView dh = new TextView(requireContext());
        dh.setText("Pilih ikon tetap yang tampil di dock (pintasan jendela aktif selalu ikut). Berlaku pas Dashboard dibuka lagi.");
        dh.setTextColor(0xFF8B93B8);
        dh.setTextSize(12);
        dh.setPadding(0, 0, 0, dp(6));
        root.addView(dh);
        id.hawx.astro.utils.Core prefs = new id.hawx.astro.utils.Core(requireContext());
        root.addView(dockSwitch(prefs, "terminal", "Terminal"));
        root.addView(dockSwitch(prefs, "viewer", "Layar X (viewer)"));
        root.addView(dockSwitch(prefs, "sessions", "Sesi & Log"));

        return scroll;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextColor(0xFFE8EBFA);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
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

    private void updateDpiLabel() {
        if (dpiLabel == null || dpiSeek == null) return;
        dpiLabel.setText("Skala teks GTK (DPI): " + (96 + dpiSeek.getProgress() * 24) + "  ");
    }
}
