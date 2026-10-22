package id.hawx.astro.dashboard;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import id.hawx.astro.engine.GuestEngine;
import id.hawx.astro.utils.Core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import id.hawx.astro.x11.LauncherEditActivity;
import id.hawx.astro.x11.LauncherEntry;
import id.hawx.astro.x11.LauncherStore;
import id.hawx.astro.x11.X11PocActivity;
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
 *  - dock bawah melayang: Terminal, Layar X, Sesi & Log, Tambah
 * Semua kartu status/banner/recent/social/VM lama sudah dihapus;
 * riwayatnya ada di git.
 */
public class Dashboard extends Fragment {

    private static final String PREF_TOOL = "dash_tool_";

    private LinearLayout toolsGrid;
    private XSessionManager xMgr;
    private LauncherStore launcherStore;
    private final java.util.Set<String> xRunning = new java.util.HashSet<>();
    private TextView xfceTaskBtn;
    private TextView xfceClock;

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
        checkPermission();
        xMgr = XSessionManager.get(context);
        launcherStore = new LauncherStore(context);
        buildTools();
        buildDesktopChrome(view);
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

    private boolean toolShown(Tool t) {
        String key = PREF_TOOL + t.key;
        return core.contains(key) ? core.getBoolean(key) : t.on;
    }

    private void showCustomize() {
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

    private void buildTools() {
        if (toolsGrid == null) return;
        toolsGrid.removeAllViews();

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
        cell.setOnClickListener(v -> xMgr.launch(e, (ok, msg) -> {
            core.toaster(msg);
            refreshXState();
        }));
        cell.setOnLongClickListener(v -> {
            Intent i = new Intent(context, LauncherEditActivity.class);
            i.putExtra("entry_id", e.id);
            startActivity(i);
            return true;
        });
        return cell;
    }

    // ================= panel + dock =================

    private TextView panelText(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFFE8E6F0);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        return tv;
    }

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

    // ================= Menu Applications (popup accordion) =================
    // Kategori + keanggotaan meniru seksi drawer_content.xml persis;
    // judul/ikon/aksi tiap baris diambil dari MainActivity.drawerSpecs()
    // dan dijalankan lewat receiver.changeFragment(rowId) — aksi yang
    // sama persis dengan klik baris drawer.

    private static final Object[][] APPS_CATS = {
            {"\u2B50", "Essentials", 0xFFA088F0, new int[]{R.id.dasboard_item, R.id.terminal_item, R.id.logs_item}},
            {"\uD83D\uDCF6", "Wireless", 0xFF5B8DEF, new int[]{R.id.wifi_item, R.id.hs_item, R.id.wpair_item, R.id.bt_item, R.id.osint_item}},
            {"\uD83C\uDF10", "Network", 0xFFE06BC0, new int[]{R.id.lan_item, R.id.nmap_item}},
            {"\uD83C\uDFAF", "Offensive", 0xFFFF6B6B, new int[]{R.id.nuclei_item, R.id.arsenal_item, R.id.metasploit_item}},
            {"\uD83E\uDDF0", "Tools", 0xFFA088F0, new int[]{R.id.wordlists_item, R.id.geomac_item, R.id.vnc_item, R.id.manager_item, R.id.about_item}},
            {"\uD83D\uDEE1\uFE0F", "Root", 0xFF8B93A7, new int[]{R.id.hid_item, R.id.usb_arsenal_item, R.id.macchanger_item}},
    };

    private android.graphics.drawable.GradientDrawable roundBg(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp((int) radiusDp));
        return g;
    }

    private void showAppsMenu(View anchor) {
        if (context == null) return;
        java.util.Map<Integer, MainActivity.DrawerSpec> specs = MainActivity.drawerSpecs();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        android.graphics.drawable.GradientDrawable bg = roundBg(0xFF0B1120, 16);
        bg.setStroke(dp(1), 0xFF2A3A63);
        root.setBackground(bg);

        // Kepala menu: elang + nama + versi
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(6), dp(6), dp(4));
        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.ic_astro_foreground);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        head.addView(logo);
        LinearLayout nameCol = new LinearLayout(context);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ncLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ncLp.setMargins(dp(10), 0, 0, 0);
        nameCol.setLayoutParams(ncLp);
        TextView nm = new TextView(context);
        nm.setText("Astro");
        nm.setTextSize(15);
        nm.setTypeface(null, android.graphics.Typeface.BOLD);
        nm.setTextColor(0xFFE8E6F0);
        nameCol.addView(nm);
        TextView ver = new TextView(context);
        ver.setText("v" + BuildConfig.VERSION_NAME);
        ver.setTextSize(10.5f);
        ver.setTextColor(0xFF8B93A7);
        nameCol.addView(ver);
        head.addView(nameCol);
        root.addView(head);

        // Baris status chroot
        LinearLayout stat = new LinearLayout(context);
        stat.setOrientation(LinearLayout.HORIZONTAL);
        stat.setGravity(android.view.Gravity.CENTER_VERTICAL);
        stat.setBackground(roundBg(0xFF0D1424, 10));
        stat.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.setMargins(dp(4), dp(6), dp(4), dp(4));
        stat.setLayoutParams(stLp);
        View dot = new View(context);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        boolean mounted = core != null && Boolean.TRUE.equals(core.isMounted());
        dg.setColor(mounted ? 0xFF3DDC84 : 0xFF5B6475);
        dot.setBackground(dg);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
        stat.addView(dot);
        TextView stTxt = new TextView(context);
        stTxt.setText(mounted ? "Chroot mounted" : "Chroot not mounted");
        stTxt.setTextSize(11.5f);
        stTxt.setTextColor(0xFFE8E6F0);
        LinearLayout.LayoutParams sttLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sttLp.setMargins(dp(7), 0, 0, 0);
        stTxt.setLayoutParams(sttLp);
        stat.addView(stTxt);
        View stSp = new View(context);
        stSp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        stat.addView(stSp);
        TextView chip = new TextView(context);
        chip.setText("arm64");
        chip.setTextSize(10);
        chip.setTextColor(0xFF8B93A7);
        chip.setBackground(roundBg(0xFF182238, 6));
        chip.setPadding(dp(7), dp(2), dp(7), dp(2));
        stat.addView(chip);
        root.addView(stat);

        // Kategori accordion
        final java.util.List<LinearLayout> boxes = new java.util.ArrayList<>();
        final java.util.List<TextView> arrows = new java.util.ArrayList<>();
        for (Object[] cat : APPS_CATS) {
            String emoji = (String) cat[0];
            String label = (String) cat[1];
            int color = (int) cat[2];
            int[] ids = (int[]) cat[3];

            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(0xFF0A0F1E);
            box.setVisibility(label.equals("Essentials") ? View.VISIBLE : View.GONE);

            for (int itemId : ids) {
                MainActivity.DrawerSpec spec = specs.get(itemId);
                if (spec == null) continue;
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(26), dp(7), dp(14), dp(7));
                // lingkaran + ikon vektor di tengahnya
                android.widget.FrameLayout fr = new android.widget.FrameLayout(context);
                fr.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
                View circ = new View(context);
                circ.setBackground(roundBg(0xFF131D33, 16));
                fr.addView(circ, new android.widget.FrameLayout.LayoutParams(dp(32), dp(32)));
                ImageView ic = new ImageView(context);
                ic.setImageResource(spec.iconRes);
                ic.setColorFilter(0xFF9AA0AE);
                android.widget.FrameLayout.LayoutParams icLp = new android.widget.FrameLayout.LayoutParams(dp(18), dp(18));
                icLp.gravity = android.view.Gravity.CENTER;
                fr.addView(ic, icLp);
                row.addView(fr);
                TextView tt = new TextView(context);
                tt.setText(spec.title);
                tt.setTextSize(13.5f);
                tt.setTextColor(0xFFE8E6F0);
                LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ttLp.setMargins(dp(11), 0, 0, 0);
                tt.setLayoutParams(ttLp);
                row.addView(tt);
                final int fid = itemId;
                row.setOnClickListener(v -> {
                    if (appsPopup != null) appsPopup.dismiss();
                    receiver.changeFragment(fid);
                });
                box.addView(row);
            }
            boxes.add(box);

            LinearLayout hdr = new LinearLayout(context);
            hdr.setOrientation(LinearLayout.HORIZONTAL);
            hdr.setGravity(android.view.Gravity.CENTER_VERTICAL);
            hdr.setPadding(dp(14), dp(10), dp(14), dp(10));
            TextView em = new TextView(context);
            em.setText(emoji);
            em.setTextSize(16);
            em.setGravity(android.view.Gravity.CENTER);
            em.setBackground(roundBg(0xFF131D33, 17));
            em.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
            hdr.addView(em);
            TextView lb = new TextView(context);
            lb.setText(label);
            lb.setTextSize(14);
            lb.setTypeface(null, android.graphics.Typeface.BOLD);
            lb.setTextColor(color);
            LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lbLp.setMargins(dp(11), 0, 0, 0);
            lb.setLayoutParams(lbLp);
            hdr.addView(lb);
            View hSp = new View(context);
            hSp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            hdr.addView(hSp);
            TextView cnt = new TextView(context);
            cnt.setText(String.valueOf(ids.length));
            cnt.setTextSize(10.5f);
            cnt.setTextColor(0xFF5B6475);
            hdr.addView(cnt);
            TextView arr = new TextView(context);
            arr.setText(label.equals("Essentials") ? "\u2304" : "\u203A");
            arr.setTextSize(15);
            arr.setTextColor(0xFF5B6475);
            LinearLayout.LayoutParams arrLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            arrLp.setMargins(dp(6), 0, 0, 0);
            arr.setLayoutParams(arrLp);
            hdr.addView(arr);
            arrows.add(arr);
            final LinearLayout fBox = box;
            final TextView fArr = arr;
            hdr.setOnClickListener(v -> {
                boolean willOpen = fBox.getVisibility() != View.VISIBLE;
                for (LinearLayout b : boxes) b.setVisibility(View.GONE);
                for (TextView a : arrows) a.setText("\u203A");
                if (willOpen) {
                    fBox.setVisibility(View.VISIBLE);
                    fArr.setText("\u2304");
                }
            });
            root.addView(hdr);
            root.addView(box);
        }

        TextView foot = new TextView(context);
        foot.setText("Open-source \u00B7 authorized testing only");
        foot.setTextSize(10);
        foot.setTextColor(0xFF5B6475);
        foot.setPadding(dp(14), dp(10), dp(14), dp(6));
        root.addView(foot);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);

        int w = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.74);
        int cap = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.74);
        appsPopup = new PopupWindow(scroll, w, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        appsPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        appsPopup.setOutsideTouchable(true);
        appsPopup.setElevation(dp(12));
        scroll.post(() -> {
            if (scroll.getHeight() > cap) {
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = cap;
                scroll.setLayoutParams(lp);
            }
        });
        appsPopup.showAsDropDown(anchor, 0, dp(6));
    }

    private PopupWindow appsPopup;

    private void buildDesktopChrome(View view) {
        LinearLayout panel = view.findViewById(R.id.xfce_panel);
        LinearLayout dock = view.findViewById(R.id.xfce_dock);
        if (panel != null) {
            TextView apps = panelText("\uD83E\uDD85 Applications");
            apps.setTypeface(null, android.graphics.Typeface.BOLD);
            // UI pass 2026-10-22: Applications sekarang membuka MENU
            // POPUP accordion nempel panel (mockup v10 yang dikunci
            // Hawx), bukan drawer layar-penuh. Drawer tetap ada buat
            // layar lain (tombol \u2630 app bar).
            apps.setOnClickListener(v -> showAppsMenu(apps));
            panel.addView(apps);
            xfceTaskBtn = panelText("\uD83D\uDDA5 Layar X");
            xfceTaskBtn.setVisibility(View.GONE);
            xfceTaskBtn.setOnClickListener(v -> { if (xMgr != null) xMgr.openViewer(); });
            panel.addView(xfceTaskBtn);
            View spacer = new View(context);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            panel.addView(spacer);
            TextView cust = panelText("\u270E");
            cust.setTextSize(17f);
            cust.setOnClickListener(v -> showCustomize());
            panel.addView(cust);
            TextView gear = panelText("\u2699");
            gear.setTextSize(20f);
            // Settings beneran (bukan Customize): picu klik ikon settings
            // di bar app MainActivity — listener-nya yang mengurus
            // buka/tutup SettingsNew, persis kayak bar app masih tampil.
            gear.setOnClickListener(v -> {
                if (getActivity() != null) {
                    View si = getActivity().findViewById(R.id.settings_icon);
                    if (si != null) si.performClick();
                }
            });
            panel.addView(gear);
            xfceClock = panelText("");
            panel.addView(xfceClock);
            // Ikon power VEKTOR (bukan glyph ⏻ — ke-render kotak di font HP).
            android.widget.ImageView power = new android.widget.ImageView(context);
            power.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(48)));
            power.setPadding(dp(10), dp(12), dp(10), dp(12));
            power.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            power.setImageResource(R.drawable.ic_power);
            power.setColorFilter(0xFFE8E6F0);
            power.setOnClickListener(v -> confirmUnmountDesktop());
            panel.addView(power);
        }
        if (dock != null) {
            dock.addView(dockBtn("\u2328", v -> openTerminal()));
            dock.addView(dockBtn("\uD83D\uDDA5", v -> { if (xMgr != null) xMgr.openViewer(); }));
            dock.addView(dockBtn("\uD83E\uDDFE", v ->
                    startActivity(new Intent(context, X11PocActivity.class))));
        }
        refreshXState();
    }

    private void refreshXState() {
        if (xMgr == null) return;
        if (xfceClock != null) {
            xfceClock.setText(new java.text.SimpleDateFormat(
                    "EEE d MMM \u2022 HH:mm", Locale.US).format(new java.util.Date()));
        }
        xMgr.probe((state, detail) -> {
            boolean run = state == XSessionManager.State.RUNNING;
            if (xfceTaskBtn != null) {
                xfceTaskBtn.setVisibility(run ? View.VISIBLE : View.GONE);
            }
        });
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
        if (!core.isRootless()) {
            launchTerminal();
            return;
        }
        if (core.guest().status() == GuestEngine.State.READY) {
            launchTerminal();
            return;
        }
        new Thread(() -> {
            boolean ready = core.guest().statusBlocking() == GuestEngine.State.READY;
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (ready) launchTerminal();
                else core.toaster(getString(R.string.vm_required_terminal));
            });
        }, "terminal-vm-check").start();
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
