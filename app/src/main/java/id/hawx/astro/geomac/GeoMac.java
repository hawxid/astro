package id.hawx.astro.geomac;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.switchmaterial.SwitchMaterial;
import id.hawx.astro.MainActivity;
import id.hawx.astro.R;
import id.hawx.astro.dashboard.Dashboard;
import id.hawx.astro.geomac.io.GeoExporter;
import id.hawx.astro.geomac.layers.AtlasLayers;
import id.hawx.astro.geomac.layers.GpsLive;
import id.hawx.astro.geomac.layers.LayerStore;
import id.hawx.astro.geomac.layers.EsriTiles;
import id.hawx.astro.geomac.layers.TrafficLive;
import id.hawx.astro.geomac.model.GeoPin;
import id.hawx.astro.geomac.store.GeoStore;
import id.hawx.astro.geomac.ui.GeoMarkers;
import id.hawx.astro.geomac.ui.GeoMacSearchSheet;
import id.hawx.astro.geomac.ui.LayerSheet;
import id.hawx.astro.geomac.ui.AtlasBottomBar;
import id.hawx.astro.geomac.ui.AtlasModePill;
import id.hawx.astro.utils.Core;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeoMac extends Fragment {

    private final MainActivity.Receiver receiver = new MainActivity.Receiver();

    private Core core;
    private Context context;
    private Activity activity;
    private GeoStore store;

    private MapView map;
    private IMapController mapController;
    private ItemizedIconOverlay<OverlayItem> overlay;
    private final ArrayList<OverlayItem> items = new ArrayList<>();
    private final ArrayList<GeoPin> shownPins = new ArrayList<>();

    private TextView subtitle;
    private TextView counter;
    private TextView layerCredit;
    private CircularProgressIndicator busy;
    private AtlasLayers layers;
    private TrafficLive trafficLive;
    private GpsLive gpsLive;
    private LayerStore layerStore;
    private LayerSheet layerSheet;
    private GeoMacSearchSheet searchSheet;
    private AtlasBottomBar bottomBar;
    private AtlasModePill modePill;
    private TextView satCredit;
    private SwitchMaterial trafficSwitch;
    private View trafficDot;
    private boolean updatingTrafficRow = false;

    private GeoPin.Category filter = null;

    private static final String PREF_FILTER = "geomac_filter";
    private static final String PREF_CRACKED_FILTER = "geomac_cracked_filter";
    private static final String PREF_MAPILLARY_TOKEN = "mapillary_token";
    private static final String PREF_WINDY_KEY = "windy_key";
    private static final String PREF_OPEN_CELL_KEY = "opencellid_key";
    private static final int REQ_GPS = 0x4750;

    private final AtomicBoolean alive = new AtomicBoolean(false);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_geomac, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        store = new GeoStore(core);
        alive.set(true);

        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context));

        map = view.findViewById(R.id.geomap);
        map.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        layerStore = new LayerStore(core);
        map.setTileSource(layerStore.isSatellite() ? new EsriTiles() : TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        mapController = map.getController();
        mapController.setZoom(3.0);

        subtitle = view.findViewById(R.id.geomac_subtitle);
        counter = view.findViewById(R.id.geomac_counter_text);
        busy = view.findViewById(R.id.geomac_busy);
        layerCredit = view.findViewById(R.id.geomac_layer_credit);
        satCredit = view.findViewById(R.id.geomac_sat_credit);
        satCredit.setVisibility(layerStore.isSatellite() ? View.VISIBLE : View.GONE);
        layers = new AtlasLayers(activity, context, map,
                () -> alive.get() && isAdded(), layerCredit,
                new AtlasLayers.KeyProvider() {
                    @Override
                    public String windyKey() {
                        return core == null ? "" : core.getString(PREF_WINDY_KEY);
                    }

                    @Override
                    public String opencellidKey() {
                        return core == null ? "" : core.getString(PREF_OPEN_CELL_KEY);
                    }

                    @Override
                    public void onOpenCellIdKeyInvalid() {
                        // Key ditolak server (mis. token dihapus di
                        // dashboard): matikan dulu layernya supaya alur
                        // Save dialog sama persis seperti gate key kosong
                        // (setLayer + toggle ON memicu fetch ulang dengan
                        // key baru). Tanpa ini, toggle ON sudah aktif jadi
                        // Save tidak memicu fetch apa-apa.
                        if (!alive.get() || !isAdded()) return;
                        if (layerStore != null) {
                            layerStore.setLayer(LayerStore.CELLTOWERS, false);
                        }
                        if (layers != null) {
                            layers.toggle(AtlasLayers.Layer.CELLTOWERS, false);
                        }
                        refreshLayerSheet();
                        askOpenCellIdKey();
                    }
                });
        trafficLive = new TrafficLive(activity, context, map,
                () -> alive.get() && isAdded(),
                view.findViewById(R.id.geomac_traffic_card),
                view.findViewById(R.id.geomac_traffic_rows),
                view.findViewById(R.id.geomac_traffic_empty));
        gpsLive = new GpsLive(activity, context, map,
                () -> alive.get() && isAdded());

        wireBottomBar(view);
        wireModePill(view);
        wireTrafficRow(view);
        wireActionBar(view);
        restoreFilterState();
        refreshOverlay();
        refreshBottomClearance();

        receiver.setTitle(getString(R.string.geomac_title));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        if (layers != null) layers.onResume();
        if (trafficLive != null) trafficLive.onResume();
        if (gpsLive != null) gpsLive.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (layers != null) layers.onPause();
        if (trafficLive != null) trafficLive.onPause();
        if (gpsLive != null) gpsLive.onPause();
        if (map != null) map.onPause();
    }

    @Override
    public void onDestroyView() {
        if (layerSheet != null) {
            layerSheet.dismissAllowingStateLoss();
            layerSheet = null;
        }
        if (searchSheet != null) {
            searchSheet.dismissAllowingStateLoss();
            searchSheet = null;
        }
        bottomBar = null;
        modePill = null;
        trafficSwitch = null;
        trafficDot = null;
        if (layers != null) {
            layers.hideAll();
            layers = null;
        }
        if (trafficLive != null) {
            trafficLive.hideAll();
            trafficLive = null;
        }
        if (gpsLive != null) {
            gpsLive.hideAll();
            gpsLive = null;
        }
        alive.set(false);
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        alive.set(false);
        receiver.restoreTitle();
        super.onDestroy();
    }

    /** Bilah bawah (batch gede 2026-11-01 + fix+atlas 2026-11-02): daftar
     *  tab TERPUSAT di sini. Tambah tab = tambah satu baris di daftar ini
     *  (id, label, ikon, aksi) — tidak perlu mengubah AtlasBottomBar maupun
     *  layout. Pill mode Jalan/Satelit hidup terpisah (wireModePill). */
    private void wireBottomBar(View view) {
        LinearLayout tabs = view.findViewById(R.id.geomac_bar_tabs);
        if (tabs == null) return;
        List<AtlasBottomBar.Tab> list = new ArrayList<>();
        list.add(new AtlasBottomBar.Tab("search", R.string.atlas_bar_search,
                R.drawable.search, this::openSearchSheet));
        list.add(new AtlasBottomBar.Tab("layers", R.string.atlas_bar_layers,
                R.drawable.map, this::openLayerSheet));
        bottomBar = new AtlasBottomBar(tabs, list);
    }

    /** Tandai tab aktif + reset saat sheet-nya ditutup (swipe/backdrop).
     *  Hook dipasang lewat post() supaya transaksi show() sudah jalan
     *  (getDialog() baru ada setelah itu); bila dialog null, status aktif
     *  tetap sampai tab lain diketuk — tidak merusak fungsi.
     *  Listener hanya clearActive bila tab itu MASIH aktif: tanpa ini,
     *  dismiss sheet lama (saat membuka sheet baru) akan menghapus status
     *  aktif sheet baru yang baru saja di-set (race). */
    private void markBarTab(String tabId, Object sheet) {
        if (bottomBar == null) return;
        bottomBar.setActive(tabId);
        View root = getView();
        if (root == null) return;
        root.post(() -> {
            android.app.Dialog d = null;
            if (sheet instanceof androidx.fragment.app.DialogFragment) {
                d = ((androidx.fragment.app.DialogFragment) sheet).getDialog();
            }
            if (d != null) {
                d.setOnDismissListener(dlg -> {
                    if (bottomBar != null && tabId.equals(bottomBar.getActiveId())) {
                        bottomBar.clearActive();
                    }
                });
            }
        });
    }

    /** Tutup sheet layer sebelum menampilkan sheet search (anti-tumpuk).
     *  Dipanggil di awal openSearchSheet; pasangan simetrisnya ada di
     *  openLayerSheet. Field yang ditutup di-null supaya refreshLayerSheet
     *  tidak menyentuh fragment yang sudah di-dismiss. */
    private void dismissLayerSheet() {
        if (layerSheet != null) {
            layerSheet.dismissAllowingStateLoss();
            layerSheet = null;
        }
    }

    private void dismissSearchSheet() {
        if (searchSheet != null) {
            searchSheet.dismissAllowingStateLoss();
            searchSheet = null;
        }
    }

    private void openSearchSheet() {
        if (!isAdded() || store == null) return;
        if (getParentFragmentManager().findFragmentByTag("atlas_search") != null) return;
        dismissLayerSheet();
        GeoMacSearchSheet sheet = new GeoMacSearchSheet();
        searchSheet = sheet;
        sheet.bind(new GeoMacSearchSheet.Host() {
            @Override
            public void onSearchQuery(String q) {
                runLookup(q);
            }

            @Override
            public void onYourLocation() {
                // Gate GPS/B6 yang sudah ada: izin runtime dulu, provider
                // mati ditolak + toast, sudah ON = follow jalan terus.
                applyLayerToggle(LayerStore.GPS, true);
            }

            @Override
            public void onFilterChip(int chipId) {
                applyFilterChip(chipId);
            }
        }, recentLookupBssids(), currentFilterChipId());
        sheet.show(getParentFragmentManager(), "atlas_search");
        markBarTab("search", sheet);
    }

    /** BSSID lookup terakhir (maks 3, terbaru dulu) untuk chip TRY. */
    private List<String> recentLookupBssids() {
        List<String> out = new ArrayList<>();
        if (store == null) return out;
        List<GeoPin> pins = new ArrayList<>(store.all());
        pins.sort((a, b) -> Long.compare(b.timestampMs, a.timestampMs));
        for (GeoPin pin : pins) {
            if (pin.category != GeoPin.Category.LOOKUP) continue;
            if (pin.bssid == null || pin.bssid.isEmpty()) continue;
            if (out.contains(pin.bssid)) continue;
            out.add(pin.bssid);
            if (out.size() >= 3) break;
        }
        return out;
    }

    /** Filter kategori pin (batch temuan 2026-11-02): logika yang dulu
     *  hidup di listener ChipGroup tumpukan bawah, sekarang dipanggil dari
     *  seksi KATEGORI di sheet SEARCH. Pemetaan id chip -> filter,
     *  persist, dan refresh overlay IDENTIK — hanya pemicunya pindah. */
    private void applyFilterChip(int chipId) {
        if (chipId == R.id.geomac_chip_lookup) filter = GeoPin.Category.LOOKUP;
        else if (chipId == R.id.geomac_chip_scan) filter = GeoPin.Category.SCAN;
        else if (chipId == R.id.geomac_chip_cracked) filter = null;
        else if (chipId == R.id.geomac_chip_manual) filter = GeoPin.Category.MANUAL;
        else filter = null;
        crackedFilter = chipId == R.id.geomac_chip_cracked;
        persistFilterState();
        refreshOverlay();
    }

    private void openLayerSheet() {
        if (!isAdded() || layerStore == null) return;
        if (getParentFragmentManager().findFragmentByTag("atlas_layers") != null) return;
        dismissSearchSheet();
        LayerSheet sheet = new LayerSheet();
        layerSheet = sheet;
        sheet.bind(layerStore, new LayerSheet.Host() {
            @Override
            public void onLayerToggle(String id, boolean on) {
                applyLayerToggle(id, on);
            }
        });
        sheet.show(getParentFragmentManager(), "atlas_layers");
        markBarTab("layers", sheet);
    }

    private void refreshLayerSheet() {
        if (layerSheet != null) {
            layerSheet.refresh();
        }
    }

    private void applyLayerToggle(String id, boolean on) {
        if (layerStore == null) return;
        if (LayerStore.CAMERAS.equals(id) && on
                && (core == null || core.getString(PREF_WINDY_KEY).isEmpty())) {
            askWindyKey();
            refreshLayerSheet();
            return;
        }
        if (LayerStore.CELLTOWERS.equals(id) && on
                && (core == null || core.getString(PREF_OPEN_CELL_KEY).isEmpty())) {
            askOpenCellIdKey();
            refreshLayerSheet();
            return;
        }
        // GPS: izin runtime dulu, sebelum store diubah — sakelar kembali
        // mati sendiri lewat refresh bila dialog izin muncul/ditolak.
        if (LayerStore.GPS.equals(id) && on
                && (context == null || ContextCompat.checkSelfPermission(context,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_GPS);
            refreshLayerSheet();
            return;
        }
        layerStore.setLayer(id, on);
        if (LayerStore.WEATHER.equals(id)) {
            if (layers != null) layers.toggle(AtlasLayers.Layer.WEATHER, on);
        } else if (LayerStore.FLIGHTS.equals(id)) {
            if (layers != null) layers.toggle(AtlasLayers.Layer.FLIGHTS, on);
        } else if (LayerStore.QUAKES.equals(id)) {
            if (layers != null) layers.toggle(AtlasLayers.Layer.QUAKES, on);
        } else if (LayerStore.CAMERAS.equals(id)) {
            if (layers != null) layers.toggle(AtlasLayers.Layer.CAMERAS, on);
        } else if (LayerStore.CELLTOWERS.equals(id)) {
            if (layers != null) layers.toggle(AtlasLayers.Layer.CELLTOWERS, on);
        } else if (LayerStore.TRAFFIC.equals(id)) {
            if (trafficLive != null) trafficLive.setOn(on);
        } else if (LayerStore.GPS.equals(id)) {
            // Provider mati (GPS + jaringan dua-duanya off) = gagal:
            // store dibatalkan + toast, sakelar kembali mati lewat refresh.
            if (gpsLive != null && on && !gpsLive.setOn(true)) {
                layerStore.setLayer(id, false);
                Toast.makeText(context, R.string.geomac_gps_disabled,
                        Toast.LENGTH_SHORT).show();
            } else if (gpsLive != null) {
                gpsLive.setOn(on);
            }
        }
        refreshLayerSheet();
        refreshTrafficRow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_GPS) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (layerStore != null) layerStore.setLayer(LayerStore.GPS, true);
                if (gpsLive != null) gpsLive.setOn(true);
            }
            refreshLayerSheet();
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /** Pill mode basemap (batch fix+atlas, gaya OSIRIS): 2 tombol saja —
     *  Jalan + Satelit, rata kiri di atas bilah tab. Tombol aktif berbingkai
     *  emas + ikon/teks emas; non-aktif abu. TANPA tombol bulan/night
     *  (Atlas tidak punya sumber tile gelap). Ketuk = jalur basemap yang
     *  sama seperti radio lama (setSatellite + applyBasemap, pref sama). */
    private void wireModePill(View view) {
        LinearLayout buttons = view.findViewById(R.id.geomac_mode_buttons);
        if (buttons == null) return;
        List<AtlasModePill.Mode> list = new ArrayList<>();
        list.add(new AtlasModePill.Mode("street", R.string.atlas_sheet_basemap_street,
                R.drawable.map, () -> setBasemap(false)));
        list.add(new AtlasModePill.Mode("satellite", R.string.atlas_mode_satellite,
                R.drawable.web, () -> setBasemap(true)));
        modePill = new AtlasModePill(buttons, list);
        refreshModePill();
    }

    private void refreshModePill() {
        if (modePill == null || layerStore == null) return;
        modePill.setActive(layerStore.isSatellite() ? "satellite" : "street");
    }

    /** Satu-satunya jalur ganti basemap (dulu radio di LayerSheet). */
    private void setBasemap(boolean satellite) {
        if (layerStore == null) return;
        layerStore.setSatellite(satellite);
        applyBasemap(satellite);
        refreshModePill();
        refreshLayerSheet();
    }

    /** Jarak elemen overlay peta dari tumpukan bawah, diukur runtime.
     *  Latar: bilah bawah terukur ~70-80dp di perangkat padahal hitungan
     *  layout hanya 52dp — menaikkan angka margin hardcoded tidak akan
     *  pernah dijamin benar. Karena itu tinggi tumpukan (status + baris
     *  TRAFIK + baris aksi + pill mode + tab) dibaca setelah layout, lalu
     *  margin bawah overlay dihitung dari tepi atas tumpukan + gap 12dp.
     *  Elemen ditumpuk vertikal (counter, lalu kredit satelit, lalu
     *  jendela trafik) supaya tidak saling menutupi. Pill FAB mengambang
     *  SUDAH TIDAK ADA (batch temuan 2026-11-02): jangkar tumpukan bawah
     *  sekarang pill counter (kiri). Dipanggil sekali setelah wiring (via
     *  post) + setiap ganti basemap (visibilitas kredit satelit berubah).
     *  Margin di XML hanya fallback sebelum post-layout pertama jalan. */
    private void refreshBottomClearance() {
        View root = getView();
        if (root == null) return;
        root.post(() -> {
            if (!isAdded()) return;
            View stack = root.findViewById(R.id.geomac_bottom_stack);
            View counterCard = root.findViewById(R.id.geomac_counter);
            View trafficCard = root.findViewById(R.id.geomac_traffic_card);
            if (stack == null) return;
            int stackTop = stack.getHeight()
                    + ((ViewGroup.MarginLayoutParams) stack.getLayoutParams()).bottomMargin;
            int gap = dp(12);
            int base = stackTop + gap;
            setBottomMargin(counterCard, base);
            int counterTop = base + (counterCard != null ? counterCard.getHeight() : 0) + gap;
            if (satCredit != null) setBottomMargin(satCredit, counterTop);
            int trafficBase = counterTop;
            if (satCredit != null && satCredit.getVisibility() == View.VISIBLE) {
                trafficBase = counterTop + satCredit.getHeight() + gap;
            }
            setBottomMargin(trafficCard, trafficBase);
        });
    }

    private void setBottomMargin(View v, int margin) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).bottomMargin = margin;
            v.setLayoutParams(lp);
        }
    }

    private int dp(int v) {
        if (context == null) return v;
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private void applyBasemap(boolean satellite) {
        if (map == null) return;
        map.setTileSource(satellite ? new EsriTiles() : TileSourceFactory.MAPNIK);
        if (satCredit != null) {
            satCredit.setVisibility(satellite ? View.VISIBLE : View.GONE);
        }
        map.invalidate();
        refreshBottomClearance();
    }

    private void askWindyKey() {
        if (core == null) return;
        final EditText input = new EditText(context);
        input.setHint(R.string.geomac_windy_key_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.geomac_windy_key_title)
                .setMessage(R.string.geomac_windy_key_message)
                .setView(input)
                .setPositiveButton(R.string.geomac_streetview_token_save, (d2, w) -> {
                    String t = input.getText() == null ? "" : input.getText().toString().trim();
                    if (t.isEmpty()) return;
                    core.putString(PREF_WINDY_KEY, t);
                    if (layerStore != null) {
                        layerStore.setLayer(LayerStore.CAMERAS, true);
                    }
                    if (layers != null) layers.toggle(AtlasLayers.Layer.CAMERAS, true);
                    refreshLayerSheet();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askOpenCellIdKey() {
        if (core == null) return;
        final EditText input = new EditText(context);
        input.setHint(R.string.geomac_opencellid_key_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.geomac_opencellid_key_title)
                .setMessage(R.string.geomac_opencellid_key_message)
                .setView(input)
                .setPositiveButton(R.string.geomac_streetview_token_save, (d2, w) -> {
                    String t = input.getText() == null ? "" : input.getText().toString().trim();
                    if (t.isEmpty()) return;
                    core.putString(PREF_OPEN_CELL_KEY, t);
                    if (layerStore != null) {
                        layerStore.setLayer(LayerStore.CELLTOWERS, true);
                    }
                    if (layers != null) layers.toggle(AtlasLayers.Layer.CELLTOWERS, true);
                    refreshLayerSheet();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void persistFilterState() {
        if (core == null) return;
        core.putString(PREF_FILTER, filter == null ? "" : filter.name());
        core.putBoolean(PREF_CRACKED_FILTER, crackedFilter);
    }

    /** Baca state filter tersimpan ke field saja (batch temuan 2026-11-02):
     *  tidak ada lagi ChipGroup di layout untuk dicentang — chip hidup di
     *  sheet SEARCH dan membaca state lewat currentFilterChipId() saat
     *  sheet dibuka. */
    private void restoreFilterState() {
        if (core == null) return;
        crackedFilter = core.getBoolean(PREF_CRACKED_FILTER);
        String saved = core.getString(PREF_FILTER);
        filter = null;
        if (!saved.isEmpty()) {
            try {
                GeoPin.Category c = GeoPin.Category.valueOf(saved);
                // Hanya kategori yang punya chip yang dipulihkan — persis
                // seperti pemetaan lama (CRACKED_* tidak punya chip sendiri,
                // dulu jatuh ke "tidak ada chip dicentang" = tampil semua).
                if (c == GeoPin.Category.LOOKUP || c == GeoPin.Category.SCAN
                        || c == GeoPin.Category.MANUAL) {
                    filter = c;
                }
            } catch (IllegalArgumentException ignored) {
                filter = null;
            }
        }
    }

    /** Id chip yang sesuai state filter saat ini, untuk centang awal di
     *  sheet SEARCH (0 = All bila tidak ada filter tersimpan). */
    private int currentFilterChipId() {
        if (crackedFilter) return R.id.geomac_chip_cracked;
        if (filter == null) return 0;
        switch (filter) {
            case LOOKUP: return R.id.geomac_chip_lookup;
            case SCAN: return R.id.geomac_chip_scan;
            case MANUAL: return R.id.geomac_chip_manual;
            default: return 0;
        }
    }

    /** Baris kategori TRAFIK di tumpukan bawah (batch temuan 2026-11-02):
     *  toggle = jalur applyLayerToggle yang sama dengan toggle TRAFFIC di
     *  sheet Lapisan (satu state, dua sakelar sinkron). Ketuk baris di
     *  luar toggle saat ON = buka jendela tabel di atas tumpukan.
     *  Guard updatingTrafficRow mencegah setChecked programatik memicu
     *  callback toggle (pola yang sama seperti LayerSheet.updating). */
    private void wireTrafficRow(View view) {
        trafficSwitch = view.findViewById(R.id.geomac_traffic_row_switch);
        trafficDot = view.findViewById(R.id.geomac_traffic_row_dot);
        View row = view.findViewById(R.id.geomac_traffic_row);
        if (trafficSwitch != null) {
            trafficSwitch.setOnCheckedChangeListener((btn, checked) -> {
                if (updatingTrafficRow) return;
                applyLayerToggle(LayerStore.TRAFFIC, checked);
            });
        }
        if (row != null) {
            row.setOnClickListener(v -> {
                if (layerStore != null && layerStore.isOn(LayerStore.TRAFFIC)
                        && trafficLive != null) {
                    trafficLive.setExpanded(true);
                }
            });
        }
        refreshTrafficRow();
    }

    /** Sinkronkan baris TRAFIK dengan store (dipanggil setelah wiring dan
     *  setiap applyLayerToggle — termasuk saat toggle diubah dari sheet
     *  Lapisan, supaya kedua sakelar tidak pernah berbeda). */
    private void refreshTrafficRow() {
        if (layerStore == null) return;
        boolean on = layerStore.isOn(LayerStore.TRAFFIC);
        if (trafficDot != null) {
            trafficDot.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (trafficSwitch != null) {
            updatingTrafficRow = true;
            trafficSwitch.setChecked(on);
            updatingTrafficRow = false;
        }
    }

    private boolean crackedFilter = false;

    private void wireActionBar(View view) {
        MaterialButton fit = view.findViewById(R.id.geomac_btn_fit);
        MaterialButton importBtn = view.findViewById(R.id.geomac_btn_import);
        MaterialButton exportBtn = view.findViewById(R.id.geomac_btn_export);
        MaterialButton clearBtn = view.findViewById(R.id.geomac_btn_clear);

        fit.setOnClickListener(v -> fitToPins());
        importBtn.setOnClickListener(v -> openImportPicker());
        exportBtn.setOnClickListener(v -> openExportPicker());
        clearBtn.setOnClickListener(v -> confirmClear());
    }

    private boolean matches(GeoPin p) {
        if (crackedFilter) {
            return p.category == GeoPin.Category.CRACKED_HANDSHAKE
                    || p.category == GeoPin.Category.CRACKED_PIXIE;
        }
        return filter == null || p.category == filter;
    }

    private void refreshOverlay() {
        if (map == null) return;
        if (overlay != null) map.getOverlays().remove(overlay);
        items.clear();
        shownPins.clear();
        List<GeoPin> all = store.all();
        for (GeoPin pin : all) {
            if (!matches(pin)) continue;
            String title = pin.ssid == null || pin.ssid.isEmpty() ? pin.bssid : pin.ssid;
            OverlayItem item = new OverlayItem(title, pin.bssid,
                    new GeoPoint(pin.lat, pin.lon));
            item.setMarker(GeoMarkers.forCategory(context, pin.category));
            item.setMarkerHotspot(OverlayItem.HotspotPlace.BOTTOM_CENTER);
            items.add(item);
            shownPins.add(pin);
        }
        overlay = new ItemizedIconOverlay<>(items,
                new ItemizedIconOverlay.OnItemGestureListener<OverlayItem>() {
                    @Override
                    public boolean onItemSingleTapUp(int index, OverlayItem item) {
                        if (index >= 0 && index < shownPins.size()) {
                            showPinDialog(shownPins.get(index));
                        }
                        return true;
                    }

                    @Override
                    public boolean onItemLongPress(int index, OverlayItem item) {
                        if (index >= 0 && index < shownPins.size()) {
                            GeoPin p = shownPins.get(index);
                            setClipboard(p.lat + "," + p.lon);
                        }
                        return true;
                    }
                }, context);
        map.getOverlays().add(overlay);
        map.invalidate();
        updateCounter(all.size());
    }

    @SuppressLint("SetTextI18n")
    private void updateCounter(int total) {
        int hs = store.countByCategory(GeoPin.Category.CRACKED_HANDSHAKE);
        int px = store.countByCategory(GeoPin.Category.CRACKED_PIXIE);
        int sc = store.countByCategory(GeoPin.Category.SCAN);
        counter.setText(total + " · " + sc + "S " + (hs + px) + "C");
        subtitle.setText(getString(R.string.geomac_subtitle_count, total));
    }

    private void runLookup(String mac) {
        if (activity == null) return;
        busy.setVisibility(View.VISIBLE);
        subtitle.setText(getString(R.string.geomac_subtitle_lookup, mac));
        new Thread(() -> {
            double[] coords = GeoLookup.coordsFor(core, mac);
            if (activity == null || !isAdded() || !alive.get()) return;
            activity.runOnUiThread(() -> {
                if (activity == null || !isAdded() || !alive.get()) return;
                busy.setVisibility(View.GONE);
                if (coords == null) {
                    subtitle.setText(R.string.geomac_subtitle_no_match);
                    Toast.makeText(context, R.string.no_results, Toast.LENGTH_SHORT).show();
                    return;
                }
                GeoPin pin = new GeoPin(mac, "", coords[0], coords[1],
                        GeoPin.Category.LOOKUP, System.currentTimeMillis(), null);
                store.upsert(pin);
                refreshOverlay();
                mapController.animateTo(new GeoPoint(coords[0], coords[1]));
                mapController.setZoom(18.0);
                subtitle.setText(getString(R.string.geomac_subtitle_found, mac));
            });
        }, "geomac-lookup").start();
    }

    private void fitToPins() {
        if (shownPins.isEmpty()) {
            Toast.makeText(context, R.string.geomac_no_pins_to_fit, Toast.LENGTH_SHORT).show();
            return;
        }
        double north = -90, south = 90, east = -180, west = 180;
        for (GeoPin p : shownPins) {
            if (p.lat > north) north = p.lat;
            if (p.lat < south) south = p.lat;
            if (p.lon > east) east = p.lon;
            if (p.lon < west) west = p.lon;
        }
        BoundingBox box = new BoundingBox(north, east, south, west);
        map.zoomToBoundingBox(box, true, 80);
    }

    private void openExportPicker() {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.geomac_export_title)
                .setItems(new CharSequence[]{
                        getString(R.string.geomac_export_json),
                        getString(R.string.geomac_export_kml)
                }, (d, which) -> {
                    if (which == 0) doExport(true);
                    else doExport(false);
                })
                .show();
    }

    private void doExport(boolean json) {
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            File out;
            try {
                List<GeoPin> all = store.all();
                if (all.isEmpty()) {
                    if (activity == null || !isAdded() || !alive.get()) return;
                    activity.runOnUiThread(() -> {
                        if (activity == null || !isAdded() || !alive.get()) return;
                        busy.setVisibility(View.GONE);
                        Toast.makeText(context, R.string.geomac_export_empty, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                out = json ? GeoExporter.exportJson(core, all) : GeoExporter.exportKml(core, all);
            } catch (Exception e) {
                if (activity == null || !isAdded() || !alive.get()) return;
                activity.runOnUiThread(() -> {
                    if (activity == null || !isAdded() || !alive.get()) return;
                    busy.setVisibility(View.GONE);
                    Toast.makeText(context, getString(R.string.geomac_export_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            File finalOut = out;
            if (activity == null || !isAdded() || !alive.get()) return;
            activity.runOnUiThread(() -> {
                if (activity == null || !isAdded() || !alive.get()) return;
                busy.setVisibility(View.GONE);
                Toast.makeText(context,
                        getString(R.string.geomac_export_ok, finalOut.getAbsolutePath()),
                        Toast.LENGTH_LONG).show();
            });
        }, "geomac-export").start();
    }

    private void openImportPicker() {
        List<File> files = GeoExporter.listJsonExports(core);
        if (files.isEmpty()) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.geomac_import_title)
                    .setMessage(getString(R.string.geomac_import_empty,
                            GeoExporter.dir(core).getAbsolutePath()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        CharSequence[] labels = new CharSequence[files.size()];
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (int i = 0; i < files.size(); i++) {
            labels[i] = files.get(i).getName() + "\n"
                    + fmt.format(new Date(files.get(i).lastModified()));
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.geomac_import_title)
                .setItems(labels, (d, which) -> doImport(files.get(which)))
                .show();
    }

    private void doImport(File source) {
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            int added;
            try {
                List<GeoPin> incoming = GeoExporter.importJson(source);
                added = store.merge(incoming);
            } catch (Exception e) {
                if (activity == null || !isAdded() || !alive.get()) return;
                activity.runOnUiThread(() -> {
                    if (activity == null || !isAdded() || !alive.get()) return;
                    busy.setVisibility(View.GONE);
                    Toast.makeText(context, getString(R.string.geomac_import_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            int finalAdded = added;
            if (activity == null || !isAdded() || !alive.get()) return;
            activity.runOnUiThread(() -> {
                if (activity == null || !isAdded() || !alive.get()) return;
                busy.setVisibility(View.GONE);
                Toast.makeText(context, getString(R.string.geomac_import_ok, finalAdded),
                        Toast.LENGTH_LONG).show();
                refreshOverlay();
            });
        }, "geomac-import").start();
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.geomac_clear_title)
                .setMessage(R.string.geomac_clear_message)
                .setPositiveButton(R.string.geomac_clear_yes, (d, w) -> {
                    store.clear();
                    refreshOverlay();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @SuppressLint("SetTextI18n")
    private void showPinDialog(GeoPin pin) {
        final android.app.Dialog d = new android.app.Dialog(context);
        d.setContentView(R.layout.dialog_geomac_pin);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        TextView title = d.findViewById(R.id.geopin_title);
        TextView bssid = d.findViewById(R.id.geopin_bssid);
        TextView lat = d.findViewById(R.id.geopin_lat);
        TextView lon = d.findViewById(R.id.geopin_lon);
        TextView age = d.findViewById(R.id.geopin_age);
        TextView cat = d.findViewById(R.id.geopin_category);
        View swatch = d.findViewById(R.id.geopin_swatch);
        FrameLayout swatchWrap = d.findViewById(R.id.geopin_swatch_wrap);
        MaterialButton copy = d.findViewById(R.id.geopin_btn_copy);
        MaterialButton streetview = d.findViewById(R.id.geopin_btn_streetview);
        MaterialButton delete = d.findViewById(R.id.geopin_btn_delete);

        title.setText(pin.ssid == null || pin.ssid.isEmpty() ? pin.bssid : pin.ssid);
        bssid.setText(pin.bssid);
        lat.setText(String.format(Locale.US, "%.6f", pin.lat));
        lon.setText(String.format(Locale.US, "%.6f", pin.lon));
        cat.setText(GeoMarkers.labelOf(pin.category).toUpperCase());
        cat.setTextColor(GeoMarkers.colorOf(pin.category));
        if (cat.getBackground() != null) {
            cat.getBackground().mutate().setColorFilter(
                    new android.graphics.PorterDuffColorFilter(
                            GeoMarkers.colorOf(pin.category) & 0x33FFFFFF | 0x33000000,
                            android.graphics.PorterDuff.Mode.SRC_IN));
        }
        if (swatchWrap.getBackground() != null) {
            swatchWrap.getBackground().mutate().setColorFilter(
                    new android.graphics.PorterDuffColorFilter(
                            GeoMarkers.colorOf(pin.category) & 0x33FFFFFF | 0x33000000,
                            android.graphics.PorterDuff.Mode.SRC_IN));
        }
        if (swatch.getBackground() != null) {
            swatch.getBackground().mutate().setColorFilter(
                    new android.graphics.PorterDuffColorFilter(GeoMarkers.colorOf(pin.category),
                            android.graphics.PorterDuff.Mode.SRC_IN));
        }
        age.setText(getString(R.string.geomac_pin_age, formatAge(pin.timestampMs)));

        copy.setOnClickListener(v -> {
            setClipboard(pin.lat + "," + pin.lon);
            d.dismiss();
        });
        streetview.setOnClickListener(v -> openStreetView(pin));
        delete.setOnClickListener(v -> {
            store.delete(pin.bssid);
            refreshOverlay();
            d.dismiss();
        });

        d.show();
        mapController.animateTo(new GeoPoint(pin.lat, pin.lon));
    }

    private void openStreetView(GeoPin pin) {
        String token = core == null ? "" : core.getString(PREF_MAPILLARY_TOKEN);
        if (token == null || token.isEmpty()) {
            askMapillaryToken(pin);
            return;
        }
        fetchStreetView(pin, token);
    }

    private void askMapillaryToken(final GeoPin pin) {
        final EditText input = new EditText(context);
        input.setHint(R.string.geomac_streetview_token_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.geomac_streetview_token_title)
                .setMessage(R.string.geomac_streetview_token_message)
                .setView(input)
                .setPositiveButton(R.string.geomac_streetview_token_save, (d2, w) -> {
                    String t = input.getText() == null ? "" : input.getText().toString().trim();
                    if (t.isEmpty()) return;
                    core.putString(PREF_MAPILLARY_TOKEN, t);
                    fetchStreetView(pin, t);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void fetchStreetView(final GeoPin pin, final String token) {
        if (activity == null) return;
        Toast.makeText(context, R.string.geomac_streetview_searching, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Mapillary.Photo photo = Mapillary.nearest(token, pin.lat, pin.lon);
            if (activity == null || !isAdded() || !alive.get()) return;
            activity.runOnUiThread(() -> {
                if (activity == null || !isAdded() || !alive.get()) return;
                if (photo == null) {
                    Toast.makeText(context, R.string.geomac_streetview_none,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showPhotoDialog(photo);
            });
        }, "atlas-streetview").start();
    }

    private void showPhotoDialog(Mapillary.Photo photo) {
        final android.app.Dialog d = new android.app.Dialog(context);
        d.setContentView(R.layout.dialog_mapillary_photo);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        ImageView img = d.findViewById(R.id.mapillary_photo);
        TextView caption = d.findViewById(R.id.mapillary_caption);
        MaterialButton close = d.findViewById(R.id.mapillary_btn_close);
        caption.setText(getString(R.string.geomac_streetview_caption, photo.id));
        close.setOnClickListener(v -> d.dismiss());
        d.show();
        new Thread(() -> {
            Bitmap bmp = loadBitmap(photo.thumbUrl);
            if (activity == null || !isAdded() || !alive.get()) return;
            activity.runOnUiThread(() -> {
                if (activity == null || !isAdded() || !alive.get()) return;
                if (bmp != null) {
                    img.setImageBitmap(bmp);
                } else {
                    Toast.makeText(context, R.string.geomac_streetview_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "atlas-photo").start();
    }

    private Bitmap loadBitmap(String url) {
        try {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                byte[] bytes = resp.body().bytes();
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void setClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Atlas", text));
        Toast.makeText(context, R.string.geomac_copied, Toast.LENGTH_SHORT).show();
    }

    private String formatAge(long ts) {
        long delta = (System.currentTimeMillis() - ts) / 1000L;
        if (delta < 60) return delta + "s";
        if (delta < 3600) return (delta / 60) + "m";
        if (delta < 86400) return (delta / 3600) + "h";
        return (delta / 86400) + "d";
    }
}
