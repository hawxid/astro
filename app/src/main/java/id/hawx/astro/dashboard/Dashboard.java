package id.hawx.astro.dashboard;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import id.hawx.astro.BuildConfig;
import id.hawx.astro.MainActivity;
import id.hawx.astro.R;
import id.hawx.astro.arsenal.ArsenalFragment;
import id.hawx.astro.utils.Core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import id.hawx.astro.x11.LauncherEditActivity;
import id.hawx.astro.x11.LauncherEntry;
import id.hawx.astro.x11.LauncherStore;
import id.hawx.astro.x11.XServerOps;
import id.hawx.astro.x11.XSessionManager;

/**
 * Dashboard = DESKTOP murni (2026-10-22, perintah Hawx: UI asli dibuang
 * total, banner ikut mati karena rumahnya dibongkar).
 *
 * Yang tersisa hanya mesin desktop ala home XFCE (gambaran v7):
 *  - tools(): katalog 18 tool bawaan + toggle Customize (pref dash_tool_*)
 *  - buildTools(): grid ikon polos di wallpaper — tool bawaan, lalu
 *    launcher chroot dari LauncherStore, lalu sel Tambah
 *  - panel atas XFCE: Applications (drawer), tombol Layar X, Customize, jam
 *  - dock bawah melayang: Terminal, Layar X, Tambah Launcher, Tambah
 * Semua kartu status/banner/recent/social/VM lama sudah dihapus;
 * riwayatnya ada di git.
 */
public class Dashboard extends Fragment {

    public static final String PREF_TOOL = "dash_tool_";

    /** Path wallpaper kustom (disalin ke /sdcard/Astro); kosong = bawaan. */
    public static final String PREF_WALLPAPER = "dash_wallpaper_path";

    /** Redup wallpaper 0-80 (persen hitam di atas gambar). */
    public static final String PREF_DIM = "dash_wallpaper_dim";

    private LinearLayout toolsGrid;
    private LinearLayout dockBar;

    /** Titik sentuh terakhir (koordinat layar) — jangkar popup
     *  long-press desktop, supaya menu muncul di jari, bukan di
     *  bawah anchor raksasa (grid/parent) sampai kepotong layar. */
    private float lastTouchRawX = -1;
    private float lastTouchRawY = -1;
    private final View.OnTouchListener touchRecorder = (v, ev) -> {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchRawX = ev.getRawX();
            lastTouchRawY = ev.getRawY();
        }
        return false;
    };
    private int dockFixedCount = 0;
    private static final String PREF_DOCK = "dock_item_";
    private XSessionManager xMgr;
    private LauncherStore launcherStore;
    private final java.util.Set<String> xRunning = new java.util.HashSet<>();

    private Activity activity;
    private Context context;
    private Core core;
    private final MainActivity.Receiver receiver = new MainActivity.Receiver();

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @SuppressLint({"SetTextI18n", "SdCardPath"})
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        toolsGrid = view.findViewById(R.id.tools_grid);
        dockBar = view.findViewById(R.id.xfce_dock);
        checkPermission();
        xMgr = XSessionManager.get(context);
        launcherStore = new LauncherStore(context);
        buildTools();
        buildDesktopChrome(view);
        applyWallpaper(view);
        applyDim(view);
    }

    /** Lapisan hitam transparan di atas wallpaper (bawaan maupun
     *  kustom) — gambar terang diredupkan supaya ikon kebaca. */
    private void applyDim(View root) {
        View dim = root.findViewById(R.id.wallpaper_dim);
        if (dim == null) return;
        int v = core.getInt(PREF_DIM, 0);
        if (v <= 0) {
            dim.setVisibility(View.GONE);
            return;
        }
        if (v > 80) v = 80;
        dim.setAlpha(v / 100f);
        dim.setVisibility(View.VISIBLE);
    }

    /** Wallpaper kustom (v1): gambar disalin ke /sdcard/Astro, diskala
     *  center-crop ke ukuran layar pas Dashboard dibuka. Gagal baca =
     *  diam-diam pakai wallpaper bawaan (drawable di layout). */
    private void applyWallpaper(View root) {
        String path = core.getString(PREF_WALLPAPER);
        if (path == null || path.isEmpty()) return;
        java.io.File f = new java.io.File(path);
        if (!f.exists()) return;
        try {
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options o =
                    new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return;
            int sample = 1;
            while (o.outWidth / sample > m.widthPixels * 2
                    || o.outHeight / sample > m.heightPixels * 2) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            android.graphics.Bitmap raw =
                    android.graphics.BitmapFactory.decodeFile(path, o);
            if (raw == null) return;
            float scale = Math.max((float) m.widthPixels / raw.getWidth(),
                    (float) m.heightPixels / raw.getHeight());
            int tw = Math.max(1, Math.round(raw.getWidth() * scale));
            int th = Math.max(1, Math.round(raw.getHeight() * scale));
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                    raw, tw, th, true);
            if (scaled != raw) raw.recycle();
            int x = Math.max(0, (tw - m.widthPixels) / 2);
            int y = Math.max(0, (th - m.heightPixels) / 2);
            android.graphics.Bitmap fin = android.graphics.Bitmap.createBitmap(
                    scaled, x, y,
                    Math.min(m.widthPixels, tw), Math.min(m.heightPixels, th));
            if (fin != scaled) scaled.recycle();
            root.setBackground(new android.graphics.drawable.BitmapDrawable(
                    getResources(), fin));
        } catch (Throwable ignored) {
            // wallpaper rusak/berat — bawaan tetap tampil
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Store WAJIB dibaca segar tiap resume: editor menyimpan lewat
        // instans store-nya sendiri ke file — instans di sini basi
        // (bug 2026-10-22: shortcut baru tersimpan tapi tak pernah
        // muncul di desktop). Grid juga digambar ulang penuh di sini;
        // sebelumnya hanya saat status sesi X berubah.
        if (context != null) launcherStore = new LauncherStore(context);
        buildTools();
        refreshXState();
    }

    private void checkPermission() {
        if (context.checkSelfPermission(WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{WRITE_EXTERNAL_STORAGE}, 123);
        }
    }

    // ================= katalog tool bawaan =================

    private static final class Tool {
        final String key;
        final int label;
        final int icon;
        final int tile;
        final int tint;
        final boolean on;
        final Runnable open;

        Tool(String key, int label, int icon, int tile, int tint, boolean on, Runnable open) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.tile = tile;
            this.tint = tint;
            this.on = on;
            this.open = open;
        }
    }

    private List<Tool> tools() {
        int accent = androidx.core.content.ContextCompat.getColor(context, R.color.astro_accent);
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("wifi", R.string.wifi_networks, R.drawable.wifi,
                R.drawable.dashboard_tile_bg_blue, accent, true,
                () -> receiver.changeFragment(R.id.wifi_item)));
        t.add(new Tool("lan", R.string.local_network, R.drawable.lan,
                R.drawable.dashboard_tile_bg_purple, 0xFFAB47BC, true,
                () -> receiver.changeFragment(R.id.lan_item)));
        t.add(new Tool("handshakes", R.string.dashboard_tile_handshakes, R.drawable.file,
                R.drawable.dashboard_tile_bg_teal, 0xFF00897B, true,
                () -> receiver.changeFragment(R.id.hs_item)));
        t.add(new Tool("exploits", R.string.dashboard_tile_exploits, R.drawable.motion_blur,
                R.drawable.dashboard_tile_bg_orange, 0xFFEF6C00, true,
                () -> receiver.changeFragment(R.id.arsenal_item,
                        ArsenalFragment.forTab(ArsenalFragment.TAB_HUB))));
        t.add(new Tool("searchsploit", R.string.dashboard_tile_searchsploit, R.drawable.database,
                R.drawable.dashboard_tile_bg_pink, 0xFFD81B60, true,
                () -> receiver.changeFragment(R.id.arsenal_item,
                        ArsenalFragment.forTab(ArsenalFragment.TAB_DB))));
        t.add(new Tool("nuclei", R.string.website, R.drawable.webscan,
                R.drawable.dashboard_tile_bg_indigo, 0xFF3949AB, true,
                () -> receiver.changeFragment(R.id.nuclei_item)));
        t.add(new Tool("geomac", R.string.dashboard_tile_geomac, R.drawable.map,
                R.drawable.dashboard_tile_bg_cyan, 0xFF00838F, true,
                () -> receiver.changeFragment(R.id.geomac_item)));
        t.add(new Tool("metasploit", R.string.dashboard_tile_metasploit, R.drawable.shield,
                R.drawable.dashboard_tile_bg_red, 0xFFC62828, true,
                () -> receiver.changeFragment(R.id.metasploit_item)));
        t.add(new Tool("goose", R.string.dashboard_card_goose_title, R.drawable.ghost,
                R.drawable.dashboard_tile_bg_green, 0xFF43A047, true,
                () -> openGooseAi()));
        t.add(new Tool("nmap", R.string.dashboard_tile_nmap, R.drawable.scanner,
                R.drawable.dashboard_tile_bg_purple, 0xFFAB47BC, false,
                () -> receiver.changeFragment(R.id.nmap_item)));
        t.add(new Tool("hid", R.string.dashboard_tile_hid, R.drawable.keyboard,
                R.drawable.dashboard_tile_bg_red, 0xFFC62828, false,
                () -> receiver.changeFragment(R.id.hid_item)));
        t.add(new Tool("wordlists", R.string.dashboard_tile_wordlists, R.drawable.summarize,
                R.drawable.dashboard_tile_bg_indigo, 0xFF5E35B1, false,
                () -> receiver.changeFragment(R.id.wordlists_item)));
        t.add(new Tool("macchanger", R.string.dashboard_tile_macchanger, R.drawable.password,
                R.drawable.dashboard_tile_bg_blue, accent, false,
                () -> receiver.changeFragment(R.id.macchanger_item)));
        t.add(new Tool("wpair", R.string.dashboard_tile_wpair, R.drawable.wpair,
                R.drawable.dashboard_tile_bg_indigo, 0xFF3949AB, false,
                () -> receiver.changeFragment(R.id.wpair_item)));
        t.add(new Tool("vnc", R.string.dashboard_tile_vnc, R.drawable.vnc,
                R.drawable.dashboard_tile_bg_purple, 0xFF5E35B1, false,
                () -> receiver.changeFragment(R.id.vnc_item)));
        t.add(new Tool("usb", R.string.dashboard_tile_usb, R.drawable.usb,
                R.drawable.dashboard_tile_bg_blue, accent, false,
                () -> receiver.changeFragment(R.id.usb_arsenal_item)));
        t.add(new Tool("manager", R.string.dashboard_tile_manager, R.drawable.tune,
                R.drawable.dashboard_tile_bg_teal, 0xFF00897B, false,
                () -> receiver.changeFragment(R.id.manager_item)));
        t.add(new Tool("logs", R.string.dashboard_tile_logs, R.drawable.bug_report,
                R.drawable.dashboard_tile_bg_orange, 0xFFEF6C00, false,
                () -> receiver.changeFragment(R.id.logs_item)));
        return t;
    }

    /** Metadata tool desktop buat halaman pengatur (System >
     *  Desktop): kunci + label + bawaan tampil. Isi WAJIB selaras
     *  dengan tools() — di sana versi lengkapnya (dengan aksi buka). */
    public static Object[][] toolMetas() {
        return new Object[][]{
                {"wifi", R.string.wifi_networks, true},
                {"lan", R.string.local_network, true},
                {"handshakes", R.string.dashboard_tile_handshakes, true},
                {"exploits", R.string.dashboard_tile_exploits, true},
                {"searchsploit", R.string.dashboard_tile_searchsploit, true},
                {"nuclei", R.string.website, true},
                {"geomac", R.string.dashboard_tile_geomac, true},
                {"metasploit", R.string.dashboard_tile_metasploit, true},
                {"goose", R.string.dashboard_card_goose_title, true},
                {"nmap", R.string.dashboard_tile_nmap, false},
                {"hid", R.string.dashboard_tile_hid, false},
                {"wordlists", R.string.dashboard_tile_wordlists, false},
                {"macchanger", R.string.dashboard_tile_macchanger, false},
                {"wpair", R.string.dashboard_tile_wpair, false},
                {"vnc", R.string.dashboard_tile_vnc, false},
                {"usb", R.string.dashboard_tile_usb, false},
                {"manager", R.string.dashboard_tile_manager, false},
                {"logs", R.string.dashboard_tile_logs, false},
        };
    }

    private boolean toolShown(Tool t) {
        String key = PREF_TOOL + t.key;
        return core.contains(key) ? core.getBoolean(key) : t.on;
    }

    public void showCustomize() {
        if (context == null) return;
        final com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(context);
        View body = LayoutInflater.from(context).inflate(R.layout.dashboard_customize_sheet, null);
        sheet.setContentView(body);

        LinearLayout list = body.findViewById(R.id.customize_tools);
        final List<Tool> all = tools();
        final List<com.google.android.material.switchmaterial.SwitchMaterial> switches =
                new ArrayList<>();
        for (Tool tool : all) {
            com.google.android.material.switchmaterial.SwitchMaterial sw =
                    new com.google.android.material.switchmaterial.SwitchMaterial(context);
            sw.setText(getString(tool.label).replace('\n', ' '));
            sw.setTextSize(15f);
            sw.setChecked(toolShown(tool));
            sw.setPadding(0, dp(12), 0, dp(12));
            list.addView(sw);
            switches.add(sw);
        }

        View.OnClickListener setAll = v -> {
            boolean on = v.getId() == R.id.customize_all;
            for (com.google.android.material.switchmaterial.SwitchMaterial sw : switches) {
                sw.setChecked(on);
            }
        };
        body.findViewById(R.id.customize_all).setOnClickListener(setAll);
        body.findViewById(R.id.customize_none).setOnClickListener(setAll);

        // Shortcut chroot ikut diatur di sini (satu pintu manajemen
        // desktop): saklar tampil/sembunyi per entri + tombol tambah.
        LinearLayout lList = body.findViewById(R.id.customize_launchers);
        final List<LauncherEntry> entries = launcherStore != null
                ? launcherStore.list() : new ArrayList<>();
        final List<com.google.android.material.switchmaterial.SwitchMaterial> lSwitches =
                new ArrayList<>();
        for (LauncherEntry e : entries) {
            com.google.android.material.switchmaterial.SwitchMaterial sw =
                    new com.google.android.material.switchmaterial.SwitchMaterial(context);
            sw.setText(e.name);
            sw.setTextSize(15f);
            sw.setChecked(!e.hidden);
            sw.setPadding(0, dp(12), 0, dp(12));
            lList.addView(sw);
            lSwitches.add(sw);
        }
        body.findViewById(R.id.customize_add).setOnClickListener(v -> {
            sheet.dismiss();
            startActivity(new Intent(context, LauncherEditActivity.class));
        });

        body.findViewById(R.id.customize_done).setOnClickListener(v -> {
            for (int i = 0; i < all.size(); i++) {
                core.putBoolean(PREF_TOOL + all.get(i).key, switches.get(i).isChecked());
            }
            if (launcherStore != null) {
                for (int i = 0; i < entries.size(); i++) {
                    entries.get(i).hidden = !lSwitches.get(i).isChecked();
                    launcherStore.upsert(entries.get(i));
                }
                launcherStore.save();
            }
            sheet.dismiss();
            buildTools();
        });
        sheet.show();
    }

    // ================= grid desktop =================

    /** Popup ruang kosong desktop (tahan area kosong grid). */
    private void showDesktopMenu(View anchor) {
        // Jangkar = titik sentuh, bukan view raksasa: PopupMenu
        // diposisikan relatif terhadap anchor — kalau anchor =
        // grid/parent setinggi layar, menu jatuh di bawah layar
        // dan kepotong (laporan 16:42 WIB). View 1x1 transparan di
        // titik sentuh membuat helper flip atas/bawah dengan benar.
        // Tanpa koordinat (long-press non-sentuh): fallback anchor lama.
        View menuAnchor = anchor;
        View tempAnchor = null;
        if (getView() instanceof ViewGroup && lastTouchRawX >= 0 && lastTouchRawY >= 0) {
            int[] rootLoc = new int[2];
            getView().getLocationOnScreen(rootLoc);
            int x = Math.round(lastTouchRawX) - rootLoc[0];
            int y = Math.round(lastTouchRawY) - rootLoc[1];
            x = Math.max(0, Math.min(x, getView().getWidth() - 1));
            y = Math.max(0, Math.min(y, getView().getHeight() - 1));
            tempAnchor = new View(context);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    1, 1, Gravity.TOP | Gravity.START);
            lp.leftMargin = x;
            lp.topMargin = y;
            tempAnchor.setLayoutParams(lp);
            ((ViewGroup) getView()).addView(tempAnchor);
            menuAnchor = tempAnchor;
        }
        android.widget.PopupMenu pm = new android.widget.PopupMenu(context, menuAnchor);
        pm.getMenu().add("Add launcher");
        pm.getMenu().add("Atur ikon desktop");
        pm.setOnMenuItemClickListener(item -> {
            if ("Atur ikon desktop".contentEquals(item.getTitle())) {
                showCustomize();
            } else {
                startActivity(new Intent(context, LauncherEditActivity.class));
            }
            return true;
        });
        if (tempAnchor != null) {
            View toRemove = tempAnchor;
            pm.setOnDismissListener(m -> {
                if (getView() instanceof ViewGroup) ((ViewGroup) getView()).removeView(toRemove);
            });
        }
        pm.show();
    }

    private void buildTools() {
        if (toolsGrid == null) return;
        toolsGrid.removeAllViews();
        toolsGrid.setOnLongClickListener(v -> { showDesktopMenu(v); return true; });
        toolsGrid.setOnTouchListener(touchRecorder);
        if (toolsGrid.getParent() instanceof View) {
            ((View) toolsGrid.getParent()).setOnLongClickListener(
                    v -> { showDesktopMenu(v); return true; });
            ((View) toolsGrid.getParent()).setOnTouchListener(touchRecorder);
        }

        List<View> cells = new ArrayList<>();
        for (Tool t : tools()) if (toolShown(t)) cells.add(desktopCell(t));
        if (launcherStore != null && xMgr != null) {
            for (LauncherEntry e : launcherStore.list()) {
                if (!e.hidden) cells.add(chrootCell(e));
            }
        }

        final int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setOnLongClickListener(v -> { showDesktopMenu(v); return true; });
                row.setOnTouchListener(touchRecorder);
                toolsGrid.addView(row);
            }
            row.addView(cells.get(i));
        }
        if (row != null) {
            for (int i = cells.size() % perRow; i > 0 && i < perRow; i++) {
                View pad = new View(context);
                pad.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                row.addView(pad);
            }
        }
    }

    private LinearLayout baseCell() {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setPadding(0, dp(10), 0, dp(10));
        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cell.setClickable(true);
        cell.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(
                androidx.appcompat.R.attr.selectableItemBackgroundBorderless, ripple, true);
        cell.setBackgroundResource(ripple.resourceId);
        return cell;
    }

    private TextView cellLabel(String text, boolean running) {
        TextView label = new TextView(context);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(6);
        label.setLayoutParams(llp);
        label.setText(text);
        label.setGravity(android.view.Gravity.CENTER);
        label.setTextSize(11f);
        label.setTextColor(running ? 0xFF39D98A : 0xFFFFFFFF);
        label.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        return label;
    }

    private View desktopCell(Tool tool) {
        LinearLayout cell = baseCell();
        android.widget.ImageView icon = new android.widget.ImageView(context);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        icon.setImageResource(tool.icon);
        icon.setColorFilter(tool.tint);
        cell.addView(icon);
        cell.addView(cellLabel(getString(tool.label), false));
        cell.setOnClickListener(v -> tool.open.run());
        cell.setOnLongClickListener(v -> {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(context, v);
            pm.getMenu().add("Buka");
            pm.getMenu().add("Sembunyikan");
            pm.setOnMenuItemClickListener(item -> {
                if ("Sembunyikan".contentEquals(item.getTitle())) {
                    core.putBoolean(PREF_TOOL + tool.key, false);
                    core.toaster(getString(tool.label).replace('\n', ' ') + " disembunyikan");
                    buildTools();
                } else {
                    v.performClick();
                }
                return true;
            });
            pm.show();
            return true;
        });
        return cell;
    }

    private View chrootIconView(LauncherEntry e) {
        if (e.icon != null && e.icon.startsWith("file:")) {
            java.io.File f = new java.io.File("/sdcard/Astro/icons", e.icon.substring(5));
            if (f.exists()) {
                android.widget.ImageView iv = new android.widget.ImageView(context);
                iv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
                iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath()));
                return iv;
            }
        }
        TextView tv = new TextView(context);
        String label = (e.icon != null && !e.icon.trim().isEmpty() && !e.icon.startsWith("file:"))
                ? e.icon.trim()
                : (e.name == null || e.name.isEmpty() ? "?" : e.name.substring(0, 1).toUpperCase());
        tv.setText(label);
        tv.setTextSize(30);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setTextColor(0xFFFFFFFF);
        tv.setShadowLayer(5f, 0f, 2f, 0xCC000000);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return tv;
    }

    private View chrootCell(LauncherEntry e) {
        LinearLayout cell = baseCell();
        cell.addView(chrootIconView(e));
        boolean run = xRunning.contains(e.id);
        cell.addView(cellLabel((run ? "\u25CF " : "") + e.name, run));
        cell.setOnClickListener(v -> {
            if ("webview".equals(e.type)) {
                if (e.floating) {
                    boolean shown = id.hawx.astro.x11.window.WebOverlayWindows
                            .show(context, e.name, e.command);
                    if (shown) return;
                }
                Intent i = new Intent(context, id.hawx.astro.goose.GhostVizActivity.class);
                i.putExtra(id.hawx.astro.goose.GhostVizActivity.EXTRA_PATH, e.command);
                i.putExtra(id.hawx.astro.goose.GhostVizActivity.EXTRA_TITLE, e.name);
                startActivity(i);
                return;
            }
            if (e.floating) {
                xMgr.launchFloating(e, (ok, d, msg) -> {
                    core.toaster(msg);
                    if (ok && d > 0)
                        id.hawx.astro.MainActivity.openXWindowFrom(context, d,
                                e.name + " · :" + d);
                    refreshXState();
                });
                return;
            }
            xMgr.launch(e, (ok, msg) -> {
                core.toaster(msg);
                refreshXState();
            });
        });
        cell.setOnLongClickListener(v -> {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(context, v);
            pm.getMenu().add("Buka");
            pm.getMenu().add("Edit launcher");
            pm.getMenu().add("Kill");
            pm.setOnMenuItemClickListener(item -> {
                String t = item.getTitle().toString();
                if (t.equals("Edit launcher")) {
                    Intent i = new Intent(context, LauncherEditActivity.class);
                    i.putExtra("entry_id", e.id);
                    startActivity(i);
                } else if (t.equals("Kill")) {
                    if ("webview".equals(e.type)) {
                        boolean closed = id.hawx.astro.x11.window.WebOverlayWindows
                                .closeByPath(e.command)
                                || id.hawx.astro.goose.GhostVizActivity
                                        .closeByPath(e.command);
                        core.toaster(closed ? e.name + " ditutup"
                                : e.name + " tidak sedang terbuka");
                    } else {
                        xMgr.stopEntry(e, (ok, msg) -> {
                            core.toaster(msg);
                            refreshXState();
                        });
                    }
                } else {
                    v.performClick();
                }
                return true;
            });
            pm.show();
            return true;
        });
        return cell;
    }

    // ================= panel + dock =================

    private View dockBtn(String glyph, View.OnClickListener cb) {
        TextView b = new TextView(context);
        b.setText(glyph);
        b.setTextSize(20);
        b.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xFF1B2440);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(cb);
        return b;
    }

    /** Tombol dock berikon vektor (samakan bahasa ikon dock dengan
     *  sidebar — permintaan Hawx: ikon terminal dock = ikon terminal
     *  Essentials, bukan glif keyboard). */
    private View dockIconBtn(int iconRes, View.OnClickListener cb) {
        ImageView b = new ImageView(context);
        b.setImageResource(iconRes);
        b.setColorFilter(0xFF9AA0AE);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xFF1B2440);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(cb);
        return b;
    }

    private boolean dockItemOn(String name) {
        String key = PREF_DOCK + name;
        return core.contains(key) ? core.getBoolean(key) : true;
    }

    private void buildDesktopChrome(View view) {
        // Panel XFCE sekarang milik activity (MainActivity.buildPanel)
        // supaya tetap hidup saat tools kebuka sebagai jendela.
        LinearLayout dock = view.findViewById(R.id.xfce_dock);
        if (dock != null) {
            dockBar = dock;
            dockFixedCount = 0;
            // Item tetap dock DIPILIH user (System > Desktop): pref
            // dock_item_<nama>, bawaan tampil semua.
            if (dockItemOn("terminal")) {
                dock.addView(dockIconBtn(id.hawx.astro.R.drawable.terminal, v -> openTerminal()));
                dockFixedCount++;
            }
            if (dockItemOn("viewer")) {
                dock.addView(dockBtn("\uD83D\uDDA5", v -> { if (xMgr != null) xMgr.openViewer(); }));
                dockFixedCount++;
            }
            if (dockItemOn("sessions")) {
                // Dulu: buka halaman PoC (Sesi & Log). Halaman PoC
                // dipensiunkan — tombol ini jadi pintasan tambah
                // launcher (editor launcher baru).
                dock.addView(dockBtn("\uD83E\uDDFE", v ->
                        startActivity(new Intent(context, LauncherEditActivity.class))));
                dockFixedCount++;
            }
        }
        refreshXState();
    }

    /** Pintasan jendela di dock — SATU indikator gaya XFCE (keputusan
     *  Hawx): jendela aktif & yang tersembunyi sama-sama tinggal di
     *  sini; tak ada lagi daftar terpisah di atas. Tap = angkat,
     *  tahan = popup (Sembunyikan / Matikan). */
    private View dockWinBtn(XSessionManager.DisplayInfo di) {
        boolean open = id.hawx.astro.x11.window.XWindowLayer.isWindowOpen(di.display);
        boolean min = id.hawx.astro.x11.window.XWindowLayer.isWindowMinimized(di.display);
        TextView b = new TextView(context);
        b.setText(":" + di.display);
        b.setTextSize(14);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setGravity(android.view.Gravity.CENTER);
        b.setContentDescription(di.name != null ? di.name : "Display " + di.display);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(min ? 0xFF12182C : 0xFF2A3B6E);
        b.setTextColor(min ? 0xFF8A93A8 : 0xFFFFFFFF);
        if (!open && !min) { // server hidup, jendela belum ada
            g.setStroke(dp(1), 0xFF54628F);
        }
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        String title = (di.name != null ? di.name : "Display") + " \u00B7 :" + di.display;
        b.setOnClickListener(v ->
                id.hawx.astro.MainActivity.openXWindowFrom(context, di.display, title));
        b.setOnLongClickListener(v -> {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(context, v);
            pm.getMenu().add(title + (min ? " \u2014 tersembunyi"
                    : open ? " \u2014 terbuka" : " \u2014 server saja"));
            if (open) pm.getMenu().add("Sembunyikan");
            pm.getMenu().add("Matikan");
            pm.setOnMenuItemClickListener(item -> {
                String t = item.getTitle().toString();
                if (t.equals("Sembunyikan")) {
                    id.hawx.astro.x11.window.XWindowLayer.minimizeForDisplay(di.display);
                } else if (t.equals("Matikan")) {
                    // Pisah sebagian (3b): display terminal ikut boleh
                    // dibunuh dari dock, TAPI lewat release penuh
                    // manager terminal (viewer tutup + server mati +
                    // map bebas) — BUKAN stopDisplay telanjang (log 35).
                    if (id.hawx.astro.x11.XTerminalManager.isTerminalDisplay(di.display)) {
                        id.hawx.astro.x11.XTerminalManager.get(context).releaseDisplay(di.display, (ok, msg) -> {
                            core.toaster(msg);
                            refreshXState();
                        });
                    } else {
                        xMgr.stopDisplay(di.display, (ok, msg) -> {
                            core.toaster(msg);
                            refreshXState();
                        });
                    }
                } else {
                    id.hawx.astro.MainActivity.openXWindowFrom(context, di.display, title);
                }
                return true;
            });
            pm.show();
            return true;
        });
        return b;
    }

    /** Pintasan display :0 di dock — :0 itu fullscreen, bukan
     *  jendela layer, jadi tidak pakai dockWinBtn (tap layer /
     *  status layer tak berlaku). Tap = viewer fullscreen lewat
     *  rute yang sudah ada; tahan = Matikan (stopDisplay).
     *  Muncul hanya selagi server :0 jalan. */
    private View dockZeroBtn() {
        TextView b = new TextView(context);
        b.setText(":0");
        b.setTextSize(14);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setGravity(android.view.Gravity.CENTER);
        b.setContentDescription("Display :0");
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xFF2A3B6E);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        b.setTag("dock-zero");
        b.setOnClickListener(v -> {
            if (xMgr != null) xMgr.openViewerForDisplay(0);
        });
        b.setOnLongClickListener(v -> {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(context, v);
            pm.getMenu().add("Display · :0");
            pm.getMenu().add("Matikan");
            pm.setOnMenuItemClickListener(item -> {
                if (item.getTitle().toString().equals("Matikan")) {
                    if (xMgr != null) {
                        xMgr.stopDisplay(0, (ok, msg) -> {
                            core.toaster(msg);
                            refreshXState();
                        });
                    }
                } else {
                    if (xMgr != null) xMgr.openViewerForDisplay(0);
                }
                return true;
            });
            pm.show();
            return true;
        });
        return b;
    }

    /** Dipanggil MainActivity tiap jendela layer berubah (buka /
     *  tutup / sembunyi / pulih) supaya indikator dock selalu segar. */
    public void refreshDisplays() {
        if (isAdded() && getView() != null) rebuildDisplays();
    }

    /** Panel aktivitas: daftar display floating yang servernya hidup —
     *  termasuk yang jendelanya sudah tertutup (buka lagi / matikan). */
    private void rebuildDisplays() {
        // Tahap tasklist XFCE: daftar "Jendela Aktif" yang dulu berupa
        // blok baris di atas DILEBUR jadi indikator dock (satu-satunya
        // daftar) — rebuildDisplays sekarang mengurus pintasan dock.
        if (dockBar == null || xMgr == null) return;
        xMgr.listDisplays(list -> {
            if (dockBar == null) return;
            while (dockBar.getChildCount() > dockFixedCount)
                dockBar.removeViewAt(dockBar.getChildCount() - 1);
            if (!list.isEmpty()) {
                // Pemisah antara ikon tetap dan pintasan jendela aktif
                View sep = new View(context);
                android.widget.LinearLayout.LayoutParams slp =
                        new android.widget.LinearLayout.LayoutParams(dp(1), dp(28));
                slp.setMargins(dp(6), 0, dp(6), 0);
                slp.gravity = android.view.Gravity.CENTER_VERTICAL;
                sep.setLayoutParams(slp);
                sep.setBackgroundColor(0xFF3A4767);
                dockBar.addView(sep);
            }
            for (XSessionManager.DisplayInfo di : list)
                dockBar.addView(dockWinBtn(di));
            // Display :0 TIDAK masuk listDisplays (loop 1..4) —
            // probe terpisah di sini, tanpa menyentuh loop itu dan
            // tanpa mutasi state terminal (aturan log 35). Trim di
            // awal callback sudah menghapus entry :0 lama; probe ini
            // menambahkannya lagi hanya selagi server :0 jalan.
            if (xMgr != null) {
                xMgr.probe((state, detail) -> {
                    if (dockBar == null) return;
                    if (state == XServerOps.State.RUNNING
                            && dockBar.findViewWithTag("dock-zero") == null) {
                        // Pemisah tetap-vs-dinamis bila belum ada
                        // (kasus: tak ada :1-:4 jalan, cuma :0).
                        if (dockBar.getChildCount() == dockFixedCount) {
                            View sep = new View(context);
                            android.widget.LinearLayout.LayoutParams slp =
                                    new android.widget.LinearLayout.LayoutParams(dp(1), dp(28));
                            slp.setMargins(dp(6), 0, dp(6), 0);
                            slp.gravity = android.view.Gravity.CENTER_VERTICAL;
                            sep.setLayoutParams(slp);
                            sep.setBackgroundColor(0xFF3A4767);
                            dockBar.addView(sep);
                        }
                        dockBar.addView(dockZeroBtn());
                    }
                });
            }
        });
    }

    private void refreshXState() {
        if (xMgr == null) return;
        rebuildDisplays();
        // Jam + tombol Layar X di panel sekarang diurus MainActivity
        // (panel milik activity); di sini cuma status jalan launcher
        // buat titik indikator di grid desktop.
        if (launcherStore != null) {
            new Thread(() -> {
                java.util.Set<String> now = new java.util.HashSet<>();
                for (LauncherEntry e : launcherStore.list()) {
                    if (!e.hidden && xMgr.entryRunningSync(e.id, e.firstWord())) now.add(e.id);
                }
                if (!now.equals(xRunning)) {
                    xRunning.clear();
                    xRunning.addAll(now);
                    if (activity != null) activity.runOnUiThread(this::buildTools);
                }
            }).start();
        }
    }

    /**
     * Popup sesi ala tombol shutdown desktop (pojok panel): konfirmasi
     * unmount chroot — semantik sama persis tombol unmount Dashboard
     * lama / Settings: unmountCore -> sukses = toast + tutup app.
     */
    private void confirmUnmountDesktop() {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_unmount_confirm_title)
                .setMessage(R.string.settings_unmount_confirm_message)
                .setPositiveButton(R.string.settings_unmount_title, (d, w) -> {
                    new Thread(() -> {
                        boolean ok = core.unmountCore();
                        if (ok) {
                            core.toaster(getString(R.string.settings_unmount_success));
                            if (activity != null) {
                                activity.runOnUiThread(() -> activity.finishAffinity());
                            }
                        } else {
                            core.toaster(getString(R.string.settings_unmount_failed));
                        }
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ================= pembuka layar lain =================

    private void openTerminal() {
        launchTerminal();
    }

    private void launchTerminal() {
        Intent terminal = new Intent(context, com.stryker.terminal.ui.term.NeoTermActivity.class);
        terminal.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        terminal.putExtra(com.stryker.terminal.ui.term.NeoTermActivity.EXTRA_NEW_SESSION, true);
        context.startActivity(terminal);
    }

    private void openGooseAi() {
        Intent goose = new Intent(context, id.hawx.astro.goose.GooseChatActivity.class);
        goose.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(goose);
    }
}
