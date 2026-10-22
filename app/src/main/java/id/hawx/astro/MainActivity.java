package id.hawx.astro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import id.hawx.astro.about.AboutFragment;
import id.hawx.astro.appintro.AppIntroActivity;
import id.hawx.astro.coremanger.CoreManager;
import id.hawx.astro.custom.Device;
import id.hawx.astro.custom.WiFINetwork;
import id.hawx.astro.dashboard.Dashboard;
import id.hawx.astro.arsenal.ArsenalFragment;
import id.hawx.astro.geomac.GeoMac;
import id.hawx.astro.handshakes.HandshakeStorage;
import id.hawx.astro.localnetwork.LocalMain;
import id.hawx.astro.macchanger.MACChangerFragment;
import id.hawx.astro.metasploit.InstallMetasploit;
import id.hawx.astro.metasploit.utils.MetasploitUtils;
import id.hawx.astro.nmap.NmapScanner;
import id.hawx.astro.nuclei.InstallNuclei;
import id.hawx.astro.ota.UpdateManager;
import id.hawx.astro.settings.SettingsNew;
import id.hawx.astro.utils.Core;
import id.hawx.astro.hid.ui.HidFragment;
import id.hawx.astro.usbarsenal.ui.UsbArsenalFragment;
import id.hawx.astro.vnc.VNCFragment;
import id.hawx.astro.wifi.Wifi;
import id.hawx.astro.wpair.WpairFragment;
import id.hawx.astro.osint.OsintFragment;
import id.hawx.astro.bluetooth.BluetoothFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_UPDATE = "open_update";

    private static TextView logo;
    private static ImageView menu;
    private static ImageView settings;
    private static int lastSelectedItemId = 0;
    private Core core;
    private static FragmentManager fragmentManager;
    private TempFragment tempFragment;
    private static final HashMap<Integer, View> drawerRows = new HashMap<>();
    private static volatile boolean rootlessEngine;
    private static volatile boolean vmReady;
    private View engineStatusView;
    private DrawerLayout engineStatusDrawer;
    private static final java.util.LinkedHashMap<Integer, DrawerSpec> DRAWER_SPECS = buildDrawerSpecs();
    private static final java.util.Set<Integer> ROOT_ONLY_IDS = new java.util.HashSet<>(java.util.Arrays.asList(
            R.id.hid_item, R.id.usb_arsenal_item, R.id.macchanger_item));
    private static final java.util.Set<Integer> VM_INDEPENDENT_IDS = new java.util.HashSet<>(java.util.Arrays.asList(
            R.id.dasboard_item, R.id.logs_item, R.id.about_item,
            R.id.wpair_item, R.id.bt_item, R.id.osint_item, R.id.geomac_item,
            R.id.wordlists_item));
    private MetasploitUtils metasploitUtils;
    private ArrayList<WiFINetwork> networks;
    private ArrayList<Device> devices = new ArrayList<>();
    private boolean usbState = false;
    private BottomSheetDialog usbDialog;
    private id.hawx.astro.netdetect.UsbDialogRenderer usbRenderer;
    private final Receiver receiver = new Receiver();

    private volatile boolean landed;
    private volatile boolean launchRunning;
    private boolean firstResume = true;

    // === Panel activity + jendela tools (UI pass 2026-10-22) ===
    // Panel XFCE diangkat dari Dashboard ke activity supaya tetap hidup
    // saat tools kebuka; tools tampil sebagai JENDELA (win_frame =
    // margin + latar rounded + win_bar berisi ikon/judul/✕) di atas
    // desktop, bukan ganti layar. flContent TIDAK berubah id-nya —
    // semua transaksi fragmen lama (termasuk replace mentah di belasan
    // fragmen) tetap mendarat di dalam jendela tanpa disentuh.
    private boolean desktopWorld;
    private boolean windowMode;
    private static TextView winTitleS;
    private static ImageView winIconS;
    private TextView panelClock;
    private TextView panelTaskBtn;
    private PopupWindow appsPopup;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @SuppressLint({"NonConstantResourceId", "UseCompatLoadingForDrawables", "SetTextI18n", "SdCardPath"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        core = new Core(this);
        id.hawx.astro.logger.LogStore.from(getApplicationContext());
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS;
            if (checkSelfPermission(NOTIFICATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{NOTIFICATION_PERMISSION}, 1337);
            }
        }
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        fragmentManager = getSupportFragmentManager();
        // Desktop = SATU panel + tools sebagai jendela (UI pass
        // 2026-10-22): sejak Dashboard pertama mendarat (desktopWorld),
        // bar app pensiun permanen — panel XFCE di activity mengambil
        // alih semua fungsinya. Fragmen selain Dashboard tampil di
        // dalam bingkai jendela (win_frame); Dashboard = desktop polos.
        final View appBar = findViewById(R.id.app_bar);
        fragmentManager.registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm,
                                                  @NonNull Fragment f) {
                        if (f.getParentFragment() != null || appBar == null) return;
                        if (f instanceof Dashboard) {
                            if (!desktopWorld) {
                                desktopWorld = true;
                                View p = findViewById(R.id.xfce_panel);
                                if (p != null) p.setVisibility(View.VISIBLE);
                                DrawerLayout dl = findViewById(R.id.drawerLayout);
                                if (dl != null) dl.setDrawerLockMode(
                                        DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
                            }
                            setWindowMode(false);
                        } else if (desktopWorld) {
                            if (f instanceof SettingsNew) {
                                setWinTitle("Pengaturan", R.drawable.settings);
                            }
                            setWindowMode(true);
                        }
                        appBar.setVisibility(desktopWorld ? View.GONE : View.VISIBLE);
                    }
                }, true);
        fragmentManager.beginTransaction().replace(R.id.flContent, new Initializing()).commit();
        settings = findViewById(R.id.settings_icon);
        settings.setOnClickListener(view -> {
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.flContent);
            if (currentFragment instanceof SettingsNew) {
                if (((SettingsNew) currentFragment).popChild()) return;
                closeSettings();
            } else {
                openSettings();
            }
        });

        buildPanel();

        new Thread(this::copyAssets).start();
        if (core.getBoolean("clean_after_update")) {
            new Thread(() -> core.deleteFile("/data/data/id.hawx.astro/files/Astro.apk")).start();
            core.remove("clean_after_update");
        }

        runLaunchFlow();

        DrawerLayout drawer = findViewById(R.id.drawerLayout);

        logo = findViewById(R.id.stryker_main_logo);
        menu = findViewById(R.id.menu_img);
        menu.setOnClickListener(view -> {
            if (core != null) {
                if (core.getBoolean("nav_type")) {
                    drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
                } else {
                    drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
                    if (drawer.isDrawerOpen(GravityCompat.START)) {
                        drawer.closeDrawer(GravityCompat.START);
                    } else {
                        drawer.openDrawer(GravityCompat.START);
                    }
                }
            }
        });

        View navView = findViewById(R.id.nav_view);
        wireDrawerRows(navView, drawer);

        View menuCloseDrawer = navView.findViewById(R.id.menu_close_drawer);
        menuCloseDrawer.setOnClickListener(v -> {
            if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawers();
        });

        TextView arch = navView.findViewById(R.id.drawer_arch_badge);
        if (arch != null) arch.setText("arm64");
        engineStatusView = navView;
        engineStatusDrawer = drawer;
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                refreshEngineStatus();
                wireDrawerRows(navView, drawer);
            }
        });
        refreshEngineStatus();

        TextView versionTv = navView.findViewById(R.id.drawer_version);
        if (versionTv != null) versionTv.setText("v" + BuildConfig.VERSION_NAME);

        if (lastSelectedItemId == 0) {
            receiver.changeFragmentQuiet(R.id.dasboard_item);
        }

        boolean openUpdate = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_OPEN_UPDATE, false);
        UpdateManager.checkAndPrompt(this, openUpdate);
    }

    private void refreshEngineStatus() {
        View navView = engineStatusView;
        if (navView == null || core == null) return;
        TextView chrootStatus = navView.findViewById(R.id.drawer_chroot_status);
        View statusDot = navView.findViewById(R.id.drawer_status_dot);
        if (chrootStatus == null && statusDot == null) return;
        if (!id.hawx.astro.engine.EngineType.isChosen(core)) {
            vmReady = false;
            if (chrootStatus != null) chrootStatus.setText(R.string.engine_not_chosen);
            if (statusDot != null) {
                try {
                    statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.status_offline)));
                } catch (Exception ignored) {}
            }
            return;
        }
        new Thread(() -> {
            boolean mounted = core.isMounted();
            id.hawx.astro.engine.EngineStatus es =
                    id.hawx.astro.engine.EngineStatus.current(core, mounted, false);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (chrootStatus != null) chrootStatus.setText(es.label);
                if (statusDot != null) {
                    try {
                        statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                                ContextCompat.getColor(this, es.colorRes)));
                    } catch (Exception ignored) {}
                }
                boolean changed = vmReady != es.ready;
                vmReady = es.ready;
                if (changed && engineStatusDrawer != null) {
                    wireDrawerRows(navView, engineStatusDrawer);
                }
            });
        }, "drawer-engine-status").start();
    }

    private void wireDrawerRows(View navView, DrawerLayout drawer) {
        drawerRows.clear();
        boolean rootless = core != null && core.isRootless();
        rootlessEngine = rootless;

        View rootNote = navView.findViewById(R.id.root_section_note);
        if (rootNote != null) rootNote.setVisibility(rootless ? View.VISIBLE : View.GONE);

        for (java.util.Map.Entry<Integer, DrawerSpec> e : DRAWER_SPECS.entrySet()) {
            int rowId = e.getKey();
            DrawerSpec spec = e.getValue();
            View row = navView.findViewById(rowId);
            if (row == null) continue;
            TextView title = row.findViewById(R.id.row_title);
            ImageView icon = row.findViewById(R.id.row_icon);
            TextView badge = row.findViewById(R.id.row_badge);
            if (title != null) title.setText(spec.title);
            if (icon != null) {
                icon.setImageResource(spec.iconRes);
                icon.setColorFilter(ContextCompat.getColor(this, R.color.grey));
            }
            drawerRows.put(rowId, row);

            boolean rootOnly = ROOT_ONLY_IDS.contains(rowId);
            boolean needsVm = rootless && !vmReady && !VM_INDEPENDENT_IDS.contains(rowId);
            boolean locked = rootless && (rootOnly || needsVm);

            if (badge != null && rootOnly) {
                badge.setText("ROOT");
                badge.setTextColor(ContextCompat.getColor(this,
                        locked ? R.color.grey : R.color.red));
                badge.setVisibility(View.VISIBLE);
            }

            if (locked) {
                final String why = rootOnly
                        ? spec.title + " needs root hardware — unavailable on the rootless VM"
                        : spec.title + " needs the VM — start it from the dashboard first";
                row.setAlpha(0.45f);
                row.setOnClickListener(v -> android.widget.Toast.makeText(this, why,
                        android.widget.Toast.LENGTH_SHORT).show());
            } else {
                row.setAlpha(1f);
                row.setOnClickListener(v -> {
                    if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawers();
                    settings.setImageDrawable(ContextCompat.getDrawable(getApplicationContext(), R.drawable.settings));
                    receiver.changeFragment(rowId);
                });
            }
        }
        if (lastSelectedItemId != 0) receiver.changeFragmentQuiet(lastSelectedItemId);
    }

    public MetasploitUtils getMetasploitUtils() {
        return metasploitUtils;
    }

    public void setMetasploitUtils(MetasploitUtils metasploitUtils) {
        this.metasploitUtils = metasploitUtils;
    }

    public ArrayList<Device> getDevices() {
        return devices;
    }

    public void setDevices(ArrayList<Device> devices) {
        this.devices = devices;
    }

    public ArrayList<WiFINetwork> getNetworks() {
        return networks;
    }

    public void setNetworks(ArrayList<WiFINetwork> networks) {
        this.networks = networks;
    }

    private id.hawx.astro.netdetect.UsbFlow usbFlow;

    private void usbDialog() {
        if (usbDialog != null && usbDialog.isShowing()) {
            startUsbFlow();
            return;
        }
        usbDialog = new BottomSheetDialog(this, R.style.ThemeOverlay_Stryker_BottomSheetDialog);
        usbDialog.setDismissWithAnimation(true);
        usbDialog.setContentView(R.layout.usb_dialog);

        id.hawx.astro.netdetect.UsbDialogRenderer renderer =
                new id.hawx.astro.netdetect.UsbDialogRenderer(
                        usbDialog.getWindow() == null
                                ? usbDialog.findViewById(android.R.id.content)
                                : usbDialog.getWindow().getDecorView(),
                        this::applyUsbFix);
        usbRenderer = renderer;
        renderer.renderEmpty();

        View refresh = usbDialog.findViewById(R.id.usb_refresh_btn);
        if (refresh != null) refresh.setOnClickListener(v -> startUsbFlow());

        usbDialog.setOnDismissListener(d -> {
            if (usbFlow != null) usbFlow.cancel();
            usbFlow = null;
            usbRenderer = null;
        });
        usbDialog.show();
        startUsbFlow();
    }

    private void startUsbFlow() {
        if (usbFlow != null) usbFlow.cancel();
        final id.hawx.astro.netdetect.UsbDialogRenderer renderer = usbRenderer;
        if (renderer == null) return;
        usbFlow = new id.hawx.astro.netdetect.UsbFlow(this, (report, chain) ->
                runOnUiThread(() -> {
                    if (usbDialog == null || !usbDialog.isShowing() || usbRenderer != renderer) {
                        return;
                    }
                    renderer.render(report, chain);
                }));
        usbFlow.start();
    }

    private void applyUsbFix(id.hawx.astro.netdetect.UsbChain.Fix fix,
                             id.hawx.astro.netdetect.UsbDeviceReport report) {
        switch (fix) {
            case SETUP_GUEST:
                if (usbDialog != null) usbDialog.dismiss();
                break;

            case SEARCH_DRIVER:
                openUrl(id.hawx.astro.netdetect.UsbChain.searchUrl(report));
                break;

            case RETRY:
                startUsbFlow();
                break;

            default:
                break;
        }
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private boolean isConnected() {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) return false;
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getDeviceClass() != UsbConstants.USB_CLASS_HUB) return true;
        }
        return false;
    }

    public void openUsbSheet() {
        runOnUiThread(this::usbDialog);
    }

    private static boolean emptyPref(String value) {
        return value == null || value.isEmpty();
    }

    private void runLaunchFlow() {
        if (core == null || landed || launchRunning) return;
        launchRunning = true;
        new Thread(() -> {
            if (!core.getBoolean("first_open")) {
                // Adoption: a current chroot already lives at the shared
                // path (installed by the previous app). Adopt it instead of
                // opening the setup wizard — the wizard's install step
                // purges whatever chroot it finds there. The wizard (or
                // the legacy migration flow) only runs when no current
                // chroot exists.
                boolean chrootPresent =
                        core.probeFile(Core.CHROOT_MARKER) != Core.Presence.NO
                                && core.probeFolder(Core.CHROOT_ROOT + "/usr") != Core.Presence.NO;
                if (!chrootPresent) {
                    core.putString("username", "User");
                    launchRunning = false;
                    if (core.hasLegacyChroot()) {
                        Intent update = AppIntroActivity.updateIntent(this,
                                id.hawx.astro.engine.EngineType.CHROOT);
                        update.putExtra(AppIntroActivity.EXTRA_MIGRATE, true);
                        startActivity(update);
                    } else {
                        startActivity(new Intent(this, AppIntroActivity.class));
                    }
                    return;
                }
                core.putBoolean("first_open", true);
                core.putString("username", "User");
                // Seed the same defaults the setup wizard writes in
                // Slide2.runSetup() — without them the interface prefs stay
                // empty and scan/capture features silently find nothing.
                core.customCommand("pm grant id.hawx.astro android.permission.WRITE_EXTERNAL_STORAGE", true);
                core.customCommand("pm grant id.hawx.astro android.permission.READ_EXTERNAL_STORAGE", true);
                core.customCommand("dumpsys deviceidle whitelist +id.hawx.astro", true);
                core.putString("vnc_passwd", "stryker");
                core.putInt("max_par", 3);
                core.remove("installed_modules");
                core.putBoolean("store_scan", true);
                core.putBoolean("auto_update", true);
                core.putBoolean("save_aps", true);
                core.putBoolean("autoScan", true);
                core.putBoolean("dash", true);
                core.putInt("night", 2);
                core.putInt("threads", 100);
                core.chmodFolder("/data/data/id.hawx.astro/files");
            }
            // Fill any interface prefs that are still empty — the wizard
            // normally writes them (Slide2), and an install that adopted
            // its chroot before this seeding existed can reach this point
            // with them unset, which leaves scan/capture features
            // silently finding nothing. Seed-if-empty only: never
            // overwrites an interface the user picked.
            if (emptyPref(core.getString("wlan_wifi"))
                    || emptyPref(core.getString("wlan_scan"))
                    || emptyPref(core.getString("wlan_deauth"))
                    || emptyPref(core.getString("wlan_wps"))) {
                String detected = "wlan0";
                if (core.getInterfacesList().contains("swlan0")) detected = "swlan0";
                if (emptyPref(core.getString("wlan_wifi"))) core.putString("wlan_wifi", detected);
                if (emptyPref(core.getString("wlan_scan"))) core.putString("wlan_scan", detected);
                if (emptyPref(core.getString("wlan_deauth"))) core.putString("wlan_deauth", detected);
                if (emptyPref(core.getString("wlan_wps"))) core.putString("wlan_wps", detected);
            }
            // The engine choice is normally persisted by the wizard
            // (SlideCapabilities); an install that adopted its chroot
            // never passed through it, so the drawer status reads
            // "Not set up yet" forever even though the chroot engine is
            // the one actually in use (EngineType.active() defaults to
            // CHROOT). Seed the choice when a chroot is really present —
            // never on a fresh install, where the wizard must choose.
            if (!id.hawx.astro.engine.EngineType.isChosen(core)
                    && core.probeFile(Core.CHROOT_MARKER) != Core.Presence.NO
                    && core.probeFolder(Core.CHROOT_ROOT + "/usr") != Core.Presence.NO) {
                id.hawx.astro.engine.EngineType.persist(core,
                        id.hawx.astro.engine.EngineType.CHROOT);
            }
            if (core.probeFile(Core.CHROOT_MARKER) == Core.Presence.NO
                    || core.probeFolder(Core.CHROOT_ROOT + "/usr") == Core.Presence.NO) {
                launchRunning = false;
                Intent update = AppIntroActivity.updateIntent(this,
                        id.hawx.astro.engine.EngineType.CHROOT);
                if (core.hasLegacyChroot()) {
                    update.putExtra(AppIntroActivity.EXTRA_MIGRATE, true);
                }
                startActivity(update);
                return;
            }
            boolean mounted = core.isMounted() || core.mountCore();
            if (!mounted) {
                landOn(new Error());
                return;
            }
            landOn(new Dashboard());
            runOnUiThread(() -> {
                checkForUsb();
            });
            if (!isConnected()) {
                new Thread(() -> core.getInterfacesList()).start();
            }
            new Thread(() -> {
                if (core != null && core.getBoolean("msf")) {
                    metasploitUtils = new MetasploitUtils(MainActivity.this, MainActivity.this);
                }
                if (core != null) core.chmodFolder("/data/data/id.hawx.astro/files");
            }).start();
        }).start();
    }


    // ================= Panel XFCE (di activity) =================

    private TextView panelText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFFE8E6F0);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        return tv;
    }

    private void buildPanel() {
        LinearLayout panel = findViewById(R.id.xfce_panel);
        if (panel == null) return;
        TextView apps = panelText("\uD83E\uDD85 Applications");
        apps.setTypeface(null, android.graphics.Typeface.BOLD);
        apps.setOnClickListener(v -> showAppsMenu(apps));
        panel.addView(apps);
        panelTaskBtn = panelText("\uD83D\uDDA5 Layar X");
        panelTaskBtn.setVisibility(View.GONE);
        panelTaskBtn.setOnClickListener(v ->
                id.hawx.astro.x11.XSessionManager.get(this).openViewer());
        panel.addView(panelTaskBtn);
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        panel.addView(spacer);
        TextView cust = panelText("\u270E");
        cust.setTextSize(17f);
        cust.setOnClickListener(v -> {
            Fragment f = fragmentManager.findFragmentById(R.id.flContent);
            if (f instanceof Dashboard) ((Dashboard) f).showCustomize();
        });
        panel.addView(cust);
        TextView gear = panelText("\u2699");
        gear.setTextSize(20f);
        gear.setOnClickListener(v -> {
            if (settings != null) settings.performClick();
        });
        panel.addView(gear);
        panelClock = panelText("");
        panel.addView(panelClock);
        ImageView power = new ImageView(this);
        power.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(48)));
        power.setPadding(dp(10), dp(12), dp(10), dp(12));
        power.setScaleType(ImageView.ScaleType.FIT_CENTER);
        power.setImageResource(R.drawable.ic_power);
        power.setColorFilter(0xFFE8E6F0);
        power.setOnClickListener(v -> confirmUnmountMain());
        panel.addView(power);

        winTitleS = findViewById(R.id.win_title);
        winIconS = findViewById(R.id.win_icon);
        if (winIconS != null) winIconS.setColorFilter(0xFFE8E6F0);
        TextView winClose = findViewById(R.id.win_close);
        if (winClose != null) {
            android.graphics.drawable.GradientDrawable cg =
                    new android.graphics.drawable.GradientDrawable();
            cg.setColor(0xFF26314F);
            cg.setCornerRadius(dp(8));
            winClose.setBackground(cg);
            winClose.setOnClickListener(v -> {
                Fragment cur = fragmentManager.findFragmentById(R.id.flContent);
                if (cur instanceof SettingsNew) {
                    if (settings != null) settings.performClick();
                } else {
                    receiver.changeFragment(R.id.dasboard_item);
                }
            });
        }
        refreshPanel();
    }

    private void refreshPanel() {
        if (panelClock != null) {
            panelClock.setText(new java.text.SimpleDateFormat(
                    "EEE d MMM \u2022 HH:mm", java.util.Locale.US).format(new java.util.Date()));
        }
        id.hawx.astro.x11.XSessionManager.get(this).probe((state, detail) -> {
            if (panelTaskBtn != null) {
                panelTaskBtn.setVisibility(
                        state == id.hawx.astro.x11.XSessionManager.State.RUNNING
                                ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void confirmUnmountMain() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_unmount_confirm_title)
                .setMessage(R.string.settings_unmount_confirm_message)
                .setPositiveButton(R.string.settings_unmount_title, (d, w) -> {
                    new Thread(() -> {
                        boolean ok = core.unmountCore();
                        if (ok) {
                            core.toaster(getString(R.string.settings_unmount_success));
                            runOnUiThread(this::finishAffinity);
                        } else {
                            core.toaster(getString(R.string.settings_unmount_failed));
                        }
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ================= Bingkai jendela =================

    private void setWindowMode(boolean on) {
        windowMode = on;
        View frame = findViewById(R.id.win_frame);
        View bar = findViewById(R.id.win_bar);
        if (frame == null || bar == null) return;
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams lp0 = frame.getLayoutParams();
        if (lp0 instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) lp0;
            int m = on ? dp(10) : 0;
            lp.setMargins(m, m, m, m);
            frame.setLayoutParams(lp);
        }
        if (on) {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(0xFF101828);
            g.setCornerRadius(dp(16));
            g.setStroke(dp(1), 0xFF2A3A63);
            frame.setBackground(g);
            frame.setClipToOutline(true);
        } else {
            frame.setBackground(null);
            frame.setClipToOutline(false);
        }
    }

    private static void setWinTitle(String title, int iconRes) {
        if (winTitleS != null) winTitleS.setText(title);
        if (winIconS != null) winIconS.setImageResource(iconRes);
    }

    private static void noteNavTarget(int itemId) {
        DrawerSpec spec = DRAWER_SPECS.get(itemId);
        if (spec != null && itemId != R.id.dasboard_item) {
            setWinTitle(spec.title, spec.iconRes);
        }
    }

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
        final Context context = this;
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

    private void landOn(Fragment fragment) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            landed = true;
            launchRunning = false;
            fragmentManager.beginTransaction().replace(R.id.flContent, fragment).commitAllowingStateLoss();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPanel();
        refreshEngineStatus();
        if (firstResume) {
            firstResume = false;
            return;
        }
        runLaunchFlow();
    }

    @SuppressLint("SdCardPath")
    private void copyAssets() {
        AssetManager assetManager = getAssets();
        String[] files = null;
        try {
            files = assetManager.list("");
        } catch (IOException e) {
        }
        if (files != null) for (String filename : files) {
            if (filename.equals(Core.BUSYBOX_ASSET)) continue;
            if (filename.equals("rootless")) continue;
            InputStream in = null;
            OutputStream out = null;
            try {
                in = assetManager.open(filename);
                File outFile = new File("/data/data/id.hawx.astro/files/", filename);
                out = new FileOutputStream(outFile);
                copyFile(in, out);
                out.close();
                out = null;
                outFile.setExecutable(true, false);
            } catch (IOException ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        Core.extractBusybox(this);
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void checkForUsb() {
        Timer usb = new Timer();
        usb.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                boolean temp = usbState;
                usbState = isConnected();
                if (temp != usbState && usbState) {
                    runOnUiThread(() -> usbDialog());
                } else if (temp != usbState) {
                    runOnUiThread(() -> {
                        if (usbDialog != null) {
                            usbDialog.dismiss();
                        }
                    });
                }
            }
        }, 0, 300);
    }

    private void openSettings() {
        settings.setImageDrawable(getDrawable(R.drawable.close));
        tempFragment = new TempFragment(getSupportFragmentManager().findFragmentById(R.id.flContent),
                lastSelectedItemId != 0 ? lastSelectedItemId : R.id.dasboard_item);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.settings_open_enter, R.anim.settings_open_exit)
                .replace(R.id.flContent, new SettingsNew())
                .commit();
        receiver.changeFragmentQuiet(R.id.dasboard_item);
    }

    private void closeSettings() {
        settings.setImageDrawable(getDrawable(R.drawable.settings));
        receiver.changeFragment(tempFragment != null ? tempFragment.getItemId() : R.id.dasboard_item,
                R.anim.settings_close_enter, R.anim.settings_close_exit);
    }

    @Override
    public void onBackPressed() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment current = fm.findFragmentById(R.id.flContent);
        if (current instanceof SettingsNew) {
            if (((SettingsNew) current).popChild()) return;
            closeSettings();
            return;
        }
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStack();
        } else if (windowMode) {
            receiver.changeFragment(R.id.dasboard_item);
        }
        // di desktop: Back tidak melakukan apa-apa (perilaku launcher)
    }

    private static class TempFragment {

        private final Fragment fragment;
        private final int itemId;

        public TempFragment(Fragment fragment, int itemId) {
            this.fragment = fragment;
            this.itemId = itemId;
        }

        public Fragment getFragment() {
            return fragment;
        }

        public int getItemId() {
            return itemId;
        }
    }

    private interface MainActivityReceiver {
        void setTitle(String title);
        void restoreTitle();
        String getTitle();
        void setToolBarMenuEnabled(boolean bool);
        boolean isToolBarMenuEnabled();
        void changeFragmentQuiet(int itemId);
        void changeFragment(int itemId);
        void changeFragment(int motherItemId, Fragment fragment);
        void changeFragment(int motherItemId, Fragment fragment, String backstackName);
    }

    public static class Receiver implements MainActivityReceiver {

        @Override
        public void setTitle(String title) {
            logo.setText(title);
        }

        @SuppressLint("SetTextI18n")
        @Override
        public void restoreTitle() {
            logo.setText("Astro");
        }

        @Override
        public String getTitle() {
            return logo.getText().toString();
        }

        @Override
        public void setToolBarMenuEnabled(boolean bool) {
            menu.setEnabled(bool);
            menu.setAlpha(bool ? 1f : .75f);
            settings.setEnabled(bool);
            settings.setAlpha(bool ? 1f : .75f);
        }

        @Override
        public boolean isToolBarMenuEnabled() {
            return (menu.isEnabled() && settings.isEnabled());
        }

        @Override
        public void changeFragmentQuiet(int itemId) {
            View prev = drawerRows.get(lastSelectedItemId);
            if (prev != null) styleDrawerRow(prev, false, lastSelectedItemId);
            View next = drawerRows.get(itemId);
            if (next != null) styleDrawerRow(next, true, itemId);
            lastSelectedItemId = itemId;
        }

        private void styleDrawerRow(View row, boolean active, int itemId) {
            row.setActivated(active);
            Context ctx = row.getContext();
            TextView title = row.findViewById(R.id.row_title);
            ImageView icon = row.findViewById(R.id.row_icon);
            DrawerSpec spec = DRAWER_SPECS.get(itemId);
            if (active) {
                if (title != null) {
                    title.setTextColor(ContextCompat.getColor(ctx, R.color.astro_accent));
                    title.setTypeface(null, android.graphics.Typeface.BOLD);
                }
                if (icon != null && spec != null) {
                    icon.setColorFilter(spec.activeTint);
                }
            } else {
                if (title != null) {
                    title.setTextColor(ContextCompat.getColor(ctx, R.color.night_contrast));
                    title.setTypeface(null, android.graphics.Typeface.NORMAL);
                }
                if (icon != null) {
                    icon.setColorFilter(ContextCompat.getColor(ctx, R.color.grey));
                }
            }
        }

        @Override
        public void changeFragment(int itemId) {
            changeFragment(itemId, R.anim.nav_fade_enter, R.anim.nav_fade_exit);
        }

        public void changeFragment(int itemId, int enterAnim, int exitAnim) {
            if (rootlessEngine && settings != null) {
                DrawerSpec spec = DRAWER_SPECS.get(itemId);
                String name = spec == null ? "This tool" : spec.title;
                if (ROOT_ONLY_IDS.contains(itemId)) {
                    android.widget.Toast.makeText(settings.getContext(),
                            name + " needs root hardware — unavailable on the rootless VM",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!vmReady && !VM_INDEPENDENT_IDS.contains(itemId)) {
                    android.widget.Toast.makeText(settings.getContext(),
                            name + " needs the VM — start it from the dashboard first",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (settings != null) {
                settings.setImageDrawable(settings.getContext().getDrawable(R.drawable.settings));
            }
            changeFragmentQuiet(itemId);
            if (itemId == R.id.terminal_item) {
                Intent terminal = new Intent(settings.getContext(),
                        com.stryker.terminal.ui.term.NeoTermActivity.class);
                terminal.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                terminal.putExtra(com.stryker.terminal.ui.term.NeoTermActivity.EXTRA_NEW_SESSION, true);
                settings.getContext().startActivity(terminal);
                return;
            }
            Fragment target = fragmentFor(itemId);
            if (target != null) {
                noteNavTarget(itemId);
                if (fragmentManager.getBackStackEntryCount() > 0) {
                    fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                }
                fragmentManager.beginTransaction()
                        .setCustomAnimations(enterAnim, exitAnim)
                        .replace(R.id.flContent, target)
                        .commit();
            }
        }

        private static Fragment fragmentFor(int itemId) {
            if (itemId == R.id.wifi_item) return new Wifi();
            if (itemId == R.id.lan_item) return new LocalMain();
            if (itemId == R.id.macchanger_item) return new MACChangerFragment();
            if (itemId == R.id.dasboard_item) return new Dashboard();
            if (itemId == R.id.arsenal_item) return new ArsenalFragment();
            if (itemId == R.id.manager_item) return new CoreManager();
            if (itemId == R.id.nuclei_item) return new InstallNuclei();
            if (itemId == R.id.hs_item) return new HandshakeStorage();
            if (itemId == R.id.metasploit_item) return new InstallMetasploit();
            if (itemId == R.id.wordlists_item) return new id.hawx.astro.wordlists.WordlistsFragment();
            if (itemId == R.id.geomac_item) return new GeoMac();
            if (itemId == R.id.nmap_item) return new NmapScanner();
            if (itemId == R.id.wpair_item) return new WpairFragment();
            if (itemId == R.id.bt_item) return new BluetoothFragment();
            if (itemId == R.id.osint_item) return new OsintFragment();
            if (itemId == R.id.about_item) return new AboutFragment();
            if (itemId == R.id.logs_item) return new id.hawx.astro.logger.LoggerFragment();
            if (itemId == R.id.vnc_item) return new VNCFragment();
            if (itemId == R.id.hid_item) return new HidFragment();
            if (itemId == R.id.usb_arsenal_item) return new UsbArsenalFragment();
            return null;
        }

        @Override
        public void changeFragment(int motherItemId, Fragment fragment) {
            changeFragment(motherItemId, fragment, null);
        }

        @Override
        public void changeFragment(int motherItemId, Fragment fragment, String backstackName) {
            noteNavTarget(motherItemId);
            changeFragmentQuiet(motherItemId);
            FragmentTransaction transaction = fragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.nav_fade_enter, R.anim.nav_fade_exit,
                            R.anim.nav_fade_enter, R.anim.nav_fade_exit)
                    .replace(R.id.flContent, fragment);
            if (backstackName != null && !backstackName.isEmpty()) {
                transaction.addToBackStack(backstackName);
            }
            transaction.commit();
        }
    }

    // Dibuka publik (UI pass 2026-10-22): menu Applications popup di
    // Dashboard memakai tabel yang sama dengan drawer — satu sumber
    // kebenaran judul/ikon/aksi (receiver.changeFragment(rowId)).
    public static final class DrawerSpec {
        public final String title;
        public final int iconRes;
        public final int activeTint;

        DrawerSpec(String title, int iconRes, int activeTint) {
            this.title = title;
            this.iconRes = iconRes;
            this.activeTint = activeTint;
        }
    }

    public static java.util.Map<Integer, DrawerSpec> drawerSpecs() {
        return DRAWER_SPECS;
    }

    private static java.util.LinkedHashMap<Integer, DrawerSpec> buildDrawerSpecs() {
        java.util.LinkedHashMap<Integer, DrawerSpec> m = new java.util.LinkedHashMap<>();
        m.put(R.id.dasboard_item,     new DrawerSpec("Dashboard",        R.drawable.home,        0xFF1565C0));
        m.put(R.id.terminal_item,     new DrawerSpec("Terminal",         R.drawable.terminal,    0xFF1565C0));
        m.put(R.id.logs_item,         new DrawerSpec("Logs",             R.drawable.bug_report,  0xFF1565C0));
        m.put(R.id.wifi_item,         new DrawerSpec("WiFi networks",    R.drawable.wifi,        0xFF1565C0));
        m.put(R.id.hs_item,           new DrawerSpec("Handshakes",       R.drawable.storage,     0xFF00897B));
        m.put(R.id.macchanger_item,   new DrawerSpec("MAC changer",      R.drawable.password,    0xFF1565C0));
        m.put(R.id.wpair_item,        new DrawerSpec("WhisperPair (BLE)", R.drawable.wpair,      0xFF3949AB));
        m.put(R.id.bt_item,           new DrawerSpec("Bluetooth", R.drawable.wpair, 0xFF3949AB));
        m.put(R.id.osint_item,         new DrawerSpec("OSINT Recon",       R.drawable.scanner,    0xFF00E5FF));
        m.put(R.id.lan_item,          new DrawerSpec("Local network",    R.drawable.lan,         0xFFAB47BC));
        m.put(R.id.nmap_item,         new DrawerSpec("Nmap",             R.drawable.scanner,     0xFFAB47BC));
        m.put(R.id.nuclei_item,       new DrawerSpec("Web scanner",      R.drawable.webscan,     0xFF3949AB));
        m.put(R.id.arsenal_item,      new DrawerSpec("Arsenal",          R.drawable.motion_blur, 0xFFEF6C00));
        m.put(R.id.hid_item,          new DrawerSpec("HID Attacks",      R.drawable.keyboard,    0xFFC62828));
        m.put(R.id.metasploit_item,   new DrawerSpec("Metasploit",       R.drawable.shield,      0xFFC62828));
        m.put(R.id.wordlists_item,    new DrawerSpec("Wordlists",        R.drawable.summarize,   0xFF5E35B1));
        m.put(R.id.geomac_item,       new DrawerSpec("GeoMac",           R.drawable.map,         0xFF00838F));
        m.put(R.id.vnc_item,          new DrawerSpec("VNC desktop",      R.drawable.vnc,         0xFF5E35B1));
        m.put(R.id.usb_arsenal_item,  new DrawerSpec("USB Arsenal",      R.drawable.usb,         0xFF1565C0));
        m.put(R.id.manager_item,      new DrawerSpec("Core manager",     R.drawable.tune,        0xFF5E35B1));
        m.put(R.id.about_item,        new DrawerSpec("About",            R.drawable.info_outlined, 0xFF1565C0));
        return m;
    }
}
