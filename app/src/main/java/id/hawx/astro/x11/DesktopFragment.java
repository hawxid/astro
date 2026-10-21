package id.hawx.astro.x11;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Astro Desktop (Milestone 3) — wajah utama Astro di dalam MainActivity.
 *
 * Awalnya activity terpisah (DesktopActivity, build 195c799e jalan),
 * lalu Hawx mengoreksi bentuk produknya 2026-10-21: bukan "app kedua",
 * melainkan RE-UI app utamanya — desktop ini FRAGMENT pembuka
 * MainActivity, berdampingan dengan semua tool lama lewat drawer
 * (model XFCE: desktop = permukaan, drawer = whisker menu).
 * Isi: kartu status sesi X, grid launcher (store yang sama), taskbar.
 * Diagnostik rinci tetap di X11PocActivity (kini internal, tanpa ikon).
 */
public class DesktopFragment extends Fragment {

    private android.content.Context appCtx;
    private XSessionManager mgr;
    private LauncherStore store;

    private TextView dotView;
    private TextView statusText;
    private TextView detailText;
    private Button btnToggle;
    private GridView grid;
    private GridAdapter adapter;
    private TextView chipLayar;

    private final List<LauncherEntry> entries = new ArrayList<>();
    private final Map<String, Boolean> running = new HashMap<>();
    private volatile boolean serverRunning = false;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable cardBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), 0x33A088F0);
        return g;
    }

    private Button flatButton(String text) {
        Button b = new Button(requireContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFE8E6F0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1B2440);
        g.setCornerRadius(dp(24));
        b.setBackground(g);
        return b;
    }

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        appCtx = requireContext().getApplicationContext();
        mgr = XSessionManager.get(requireContext());
        store = new LauncherStore(requireContext());

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF070B16);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(requireContext());
        title.setText("Astro Desktop");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFF2F0FA);
        root.addView(title);

        // ---- kartu status sesi ----
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(0xFF101828));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp(12), 0, dp(6));
        card.setLayoutParams(cardLp);

        LinearLayout srow = new LinearLayout(requireContext());
        srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        dotView = new TextView(requireContext());
        dotView.setText("●");
        dotView.setTextSize(16);
        dotView.setTextColor(Color.GRAY);
        srow.addView(dotView);
        statusText = new TextView(requireContext());
        statusText.setText("Server X: …");
        statusText.setTypeface(null, Typeface.BOLD);
        statusText.setTextColor(0xFFF2F0FA);
        statusText.setPadding(dp(8), 0, 0, 0);
        srow.addView(statusText);
        card.addView(srow);

        detailText = new TextView(requireContext());
        detailText.setTextSize(11);
        detailText.setTextColor(0xFF9A94B8);
        detailText.setPadding(0, dp(4), 0, dp(8));
        card.addView(detailText);

        LinearLayout brow = new LinearLayout(requireContext());
        brow.setOrientation(LinearLayout.HORIZONTAL);
        btnToggle = flatButton("Start Server");
        btnToggle.setOnClickListener(v -> {
            if (serverRunning) {
                mgr.stopSession((ok, msg) -> { toast(msg); refresh(); });
            } else {
                mgr.start((ok, msg) -> { toast(msg); refresh(); });
            }
        });
        brow.addView(btnToggle);
        Button btnView = flatButton("Buka Layar X");
        btnView.setOnClickListener(v -> mgr.openViewer());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(8), 0, 0, 0);
        btnView.setLayoutParams(blp);
        brow.addView(btnView);
        card.addView(brow);
        root.addView(card);

        // ---- grid launcher ----
        TextView gtitle = new TextView(requireContext());
        gtitle.setText("Launcher");
        gtitle.setTypeface(null, Typeface.BOLD);
        gtitle.setTextColor(0xFFF2F0FA);
        gtitle.setPadding(0, dp(10), 0, dp(6));
        root.addView(gtitle);

        grid = new GridView(requireContext());
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(10));
        grid.setHorizontalSpacing(dp(6));
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            LauncherEntry e = entries.get(pos);
            mgr.launch(e, (ok, msg) -> { toast(msg); refresh(); });
        });
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            LauncherEntry e = entries.get(pos);
            Intent i = new Intent(requireContext(), LauncherEditActivity.class);
            i.putExtra("entry_id", e.id);
            startActivity(i);
            return true;
        });
        root.addView(grid);

        // ---- taskbar ----
        LinearLayout bar = new LinearLayout(requireContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(cardBg(0xFF101828));
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        Button btnSesi = flatButton("☰ Sesi & Log");
        btnSesi.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), X11PocActivity.class)));
        bar.addView(btnSesi);
        chipLayar = new TextView(requireContext());
        chipLayar.setText("🖥 Layar X");
        chipLayar.setTextColor(0xFF9A94B8);
        chipLayar.setPadding(dp(14), dp(8), dp(14), dp(8));
        chipLayar.setOnClickListener(v -> mgr.openViewer());
        bar.addView(chipLayar);
        View spacer = new View(requireContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(spacer);
        Button btnAdd = flatButton("+ Baru");
        btnAdd.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), LauncherEditActivity.class)));
        bar.addView(btnAdd);
        root.addView(bar);

        X11DebugLog.log("DESKTOP", "DesktopFragment dibuat (di dalam MainActivity)");
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        mgr.probe((state, detail) -> {
            serverRunning = state == XSessionManager.State.RUNNING;
            dotView.setTextColor(serverRunning ? 0xFF39D98A : Color.GRAY);
            statusText.setText(serverRunning ? "Server X: JALAN" : "Server X: BERHENTI");
            detailText.setText(detail);
            btnToggle.setText(serverRunning ? "Stop Server" : "Start Server");
            chipLayar.setTextColor(serverRunning ? 0xFF00E5FF : 0xFF9A94B8);
        });
        new Thread(() -> {
            List<LauncherEntry> list = store.list();
            Map<String, Boolean> run = new HashMap<>();
            for (LauncherEntry e : list) {
                if (!e.hidden) run.put(e.id, mgr.entryRunningSync(e.id, e.firstWord()));
            }
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                entries.clear();
                running.clear();
                for (LauncherEntry e : list) {
                    if (e.hidden) continue;
                    entries.add(e);
                    running.put(e.id, Boolean.TRUE.equals(run.get(e.id)));
                }
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void toast(String msg) {
        // Callback manajer sudah diposting ke main thread.
        Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show();
    }

    private View iconView(LauncherEntry e) {
        if (e.icon != null && e.icon.startsWith("file:")) {
            File f = new File("/sdcard/Astro/icons", e.icon.substring(5));
            if (f.exists()) {
                ImageView iv = new ImageView(requireContext());
                iv.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
                iv.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
                return iv;
            }
        }
        TextView tv = new TextView(requireContext());
        String label = (e.icon != null && !e.icon.trim().isEmpty() && !e.icon.startsWith("file:"))
                ? e.icon.trim()
                : (e.name == null || e.name.isEmpty() ? "?" : e.name.substring(0, 1).toUpperCase());
        tv.setText(label);
        tv.setTextSize(24);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return tv;
    }

    private class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int pos) { return entries.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LauncherEntry e = entries.get(pos);
            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(2), dp(8), dp(2), dp(8));
            cell.addView(iconView(e));
            TextView name = new TextView(requireContext());
            boolean isRun = Boolean.TRUE.equals(running.get(e.id));
            name.setText((isRun ? "● " : "") + e.name);
            name.setTextSize(11);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(isRun ? 0xFF39D98A : 0xFFE8E6F0);
            name.setMaxLines(1);
            cell.addView(name);
            return cell;
        }
    }
}
