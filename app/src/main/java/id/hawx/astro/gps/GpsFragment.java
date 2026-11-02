package id.hawx.astro.gps;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import id.hawx.astro.R;
import id.hawx.astro.utils.Core;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Menu GPS Astro (pola panel Frida/Kismet).
 *
 * Isi v1 (spek final Hawx 2026-11-02, dari ss GPS Locker):
 * - Tombol LOCK / UNLOCK GPS: driving foreground service
 *   (GpsLockService) + request agresif, tahan meski Astro
 *   background — inilah yang tidak bisa dilakukan publisher
 *   Kismet sendirian (throttling lokasi Android).
 * - Header live: Used / In View / Time to fix / Accuracy /
 *   Speed / Koordinat / Altitude.
 * - Daftar satelit (PRN + bar + dB-Hz, used vs in-view) +
 *   sky plot dari kalimat GSV/GSA.
 * - Tombol "Buka di Maps" (geo intent, butuh fix dulu).
 * - SATU opsi: tombol "Perbarui data A-GPS" (sendExtraCommand
 *   force_xtra_injection + force_time_injection). Opsi GPS
 *   Locker lainnya (bunyi, getar, keep screen on, dst.)
 *   DICORET atas perintah Hawx — bukan kelupaan.
 *
 * Komposisi dengan Kismet: menu ini TIDAK menyentuh gpsd/UDP.
 * Panel Kismet (GPS ON) tetap pakai publisher+gpsd sendiri;
 * begitu menu mengunci engine, Kismet ikut lock karena
 * publisher meneruskan kalimat fix yang sama.
 *
 * Threading: service mengirim broadcast tick; fragment hanya
 * membaca snapshot statis + update UI di UI thread dengan
 * guard alive + isAdded (pola KismetFragment).
 */
public class GpsFragment extends Fragment {

    private static final int REQ_LOCATION = 1001;

    private static final int COLOR_GOLD = 0xFFD4AF37;
    private static final int COLOR_LIVE = 0xFF00FF88;
    private static final int COLOR_INFO = 0xFF22D3EE;
    private static final int COLOR_GREY = 0xFF9E9E9E;

    private Activity activity;
    private Context context;
    private Core core;

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean agpsBusy = new AtomicBoolean(false);
    private boolean receiverRegistered;

    private MaterialTextView statusValue;
    private MaterialTextView usedValue;
    private MaterialTextView inViewValue;
    private MaterialTextView timeValue;
    private MaterialTextView accuracyValue;
    private MaterialTextView speedValue;
    private MaterialTextView coordsValue;
    private MaterialTextView altitudeValue;
    private MaterialButton lockBtn;
    private MaterialButton mapsBtn;
    private MaterialButton agpsBtn;
    private SkyPlotView skyPlot;
    private LinearLayout satsContainer;

    private String lastSatSignature = "";
    private String permDeniedText;
    private String providerOffText;
    private String noFixMapsText;
    private String agpsOkText;
    private String agpsRejectedText;
    private String agpsDeniedText;
    private String agpsFailedFormat;

    private final BroadcastReceiver snapshotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            runUi(() -> applySnapshot());
        }
    };

    private boolean uiReady() {
        return alive.get() && activity != null && isAdded();
    }

    private void runBg(Runnable r) {
        new Thread(r).start();
    }

    private void runUi(Runnable r) {
        if (uiReady()) activity.runOnUiThread(r);
    }

    private void toast(String msg) {
        runUi(() -> core.toaster(activity, msg));
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        activity = getActivity();
        context = getContext();
        core = new Core(context);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.fragment_gps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle saved) {
        alive.set(true);
        bindViews(view);
        permDeniedText = getString(R.string.gps_perm_denied);
        providerOffText = getString(R.string.gps_provider_off);
        noFixMapsText = getString(R.string.gps_no_fix_maps);
        agpsOkText = getString(R.string.gps_agps_ok);
        agpsRejectedText = getString(R.string.gps_agps_rejected);
        agpsDeniedText = getString(R.string.gps_agps_denied);
        agpsFailedFormat = getString(R.string.gps_agps_failed);
        lockBtn.setOnClickListener(v -> toggleDriving());
        mapsBtn.setOnClickListener(v -> openMaps());
        agpsBtn.setOnClickListener(v -> forceAgps());
        applySnapshot();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(context, snapshotReceiver,
                    new IntentFilter(GpsLockService.ACTION_SNAPSHOT),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        applySnapshot();
    }

    @Override
    public void onPause() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(snapshotReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        alive.set(false);
        super.onDestroyView();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDrivingService();
        } else {
            toast(permDeniedText);
        }
    }

    private void bindViews(View view) {
        statusValue = view.findViewById(R.id.gps_status_value);
        usedValue = view.findViewById(R.id.gps_used_value);
        inViewValue = view.findViewById(R.id.gps_inview_value);
        timeValue = view.findViewById(R.id.gps_time_value);
        accuracyValue = view.findViewById(R.id.gps_accuracy_value);
        speedValue = view.findViewById(R.id.gps_speed_value);
        coordsValue = view.findViewById(R.id.gps_coords_value);
        altitudeValue = view.findViewById(R.id.gps_altitude_value);
        lockBtn = view.findViewById(R.id.gps_lock_btn);
        mapsBtn = view.findViewById(R.id.gps_maps_btn);
        agpsBtn = view.findViewById(R.id.gps_agps_btn);
        skyPlot = view.findViewById(R.id.gps_sky_plot);
        satsContainer = view.findViewById(R.id.gps_sats_container);
    }

    /** Tombol LOCK/UNLOCK: start/stop service (idempotent). */
    private void toggleDriving() {
        if (GpsLockService.RUNNING) {
            Intent stop = new Intent(context, GpsLockService.class);
            stop.setAction(GpsLockService.ACTION_STOP);
            context.stopService(stop);
            return;
        }
        if (ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOCATION);
            return;
        }
        LocationManager lm = (LocationManager) context.getSystemService(
                Context.LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(
                LocationManager.GPS_PROVIDER)) {
            toast(providerOffText);
            return;
        }
        startDrivingService();
    }

    private void startDrivingService() {
        Intent start = new Intent(context, GpsLockService.class);
        start.setAction(GpsLockService.ACTION_START);
        ContextCompat.startForegroundService(context, start);
    }

    private void openMaps() {
        GpsLockService.Snapshot s = GpsLockService.LAST_SNAPSHOT;
        if (s == null || s.state < GpsLockService.STATE_2D) {
            toast(noFixMapsText);
            return;
        }
        String uri = String.format(Locale.US, "geo:%f,%f?q=%f,%f",
                s.lat, s.lon, s.lat, s.lon);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
        } else {
            toast(noFixMapsText);
        }
    }

    /** Satu-satunya opsi v1: perbarui data A-GPS (manual). */
    private void forceAgps() {
        if (!agpsBusy.compareAndSet(false, true)) return;
        if (ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            agpsBusy.set(false);
            toast(permDeniedText);
            return;
        }
        runBg(() -> {
            try {
                String result = GpsLockService.forceAgpsUpdate(context);
                if ("accepted".equals(result)) {
                    toast(agpsOkText);
                } else if ("rejected".equals(result)) {
                    toast(agpsRejectedText);
                } else if ("denied".equals(result)) {
                    toast(agpsDeniedText);
                } else {
                    toast(String.format(Locale.US, agpsFailedFormat, result));
                }
            } finally {
                agpsBusy.set(false);
            }
        });
    }

    /** Terapkan snapshot terakhir ke UI (UI thread, guard uiReady). */
    private void applySnapshot() {
        if (!uiReady()) return;
        GpsLockService.Snapshot s = GpsLockService.LAST_SNAPSHOT;
        boolean running = GpsLockService.RUNNING;
        lockBtn.setText(running ? R.string.gps_unlock_btn : R.string.gps_lock_btn);
        if (s == null || s.state == GpsLockService.STATE_OFF) {
            statusValue.setText(R.string.gps_off);
            statusValue.setTextColor(COLOR_GREY);
            usedValue.setText("—");
            inViewValue.setText("—");
            timeValue.setText("—");
            accuracyValue.setText("—");
            speedValue.setText("—");
            coordsValue.setText("—");
            altitudeValue.setText("—");
            skyPlot.setSats(null);
            rebuildSatList(null);
            return;
        }
        if (s.state == GpsLockService.STATE_SEARCHING) {
            statusValue.setText(R.string.gps_searching);
            statusValue.setTextColor(COLOR_GOLD);
        } else if (s.state == GpsLockService.STATE_2D) {
            statusValue.setText(R.string.gps_lock2d);
            statusValue.setTextColor(COLOR_INFO);
        } else {
            statusValue.setText(R.string.gps_lock3d);
            statusValue.setTextColor(COLOR_LIVE);
        }
        usedValue.setText(String.valueOf(s.used));
        inViewValue.setText(String.valueOf(s.inView));
        if (s.ttffSec >= 0) {
            timeValue.setText(s.ttffSec + " dtk");
        } else if (running) {
            timeValue.setText("mencari… " + s.elapsedSec + " dtk");
        } else {
            timeValue.setText("—");
        }
        accuracyValue.setText(s.accuracyM > 0
                ? String.format(Locale.US, "±%.0f m", s.accuracyM) : "—");
        speedValue.setText(String.format(Locale.US, "%.1f km/jam", s.speedKmh));
        if (s.state >= GpsLockService.STATE_2D) {
            coordsValue.setText(String.format(Locale.US, "%.6f, %.6f",
                    s.lat, s.lon));
            altitudeValue.setText(String.format(Locale.US, "%.0f m", s.altitude));
        } else {
            coordsValue.setText("—");
            altitudeValue.setText("—");
        }
        skyPlot.setSats(s.sats);
        rebuildSatList(s);
    }

    /**
     * Bangun ulang daftar satelit HANYA bila isinya berubah
     * (signature PRN+SNR+used) — snapshot datang 2x/detik, rebuild
     * view tiap tick boros dan bikin daftar berkedip.
     */
    private void rebuildSatList(GpsLockService.Snapshot s) {
        StringBuilder sig = new StringBuilder();
        if (s != null) {
            for (GpsLockService.Sat sat : s.sats) {
                sig.append(sat.key).append(':').append(sat.snr)
                        .append(':').append(sat.used ? '1' : '0').append(';');
            }
        }
        String signature = sig.toString();
        if (signature.equals(lastSatSignature)) return;
        lastSatSignature = signature;
        satsContainer.removeAllViews();
        if (s == null) return;
        for (GpsLockService.Sat sat : s.sats) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);
            MaterialTextView label = new MaterialTextView(context);
            label.setText(sat.constellation + " " + sat.prn);
            label.setTextSize(13f);
            label.setTextColor(sat.used ? COLOR_LIVE : 0xFFE8E8EA);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    (int) (110 * context.getResources().getDisplayMetrics().density),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(label, labelParams);
            ProgressBar bar = new ProgressBar(context, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(50);
            bar.setProgress(Math.max(0, Math.min(50, sat.snr)));
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            barParams.setMargins(8, 0, 8, 0);
            row.addView(bar, barParams);
            MaterialTextView value = new MaterialTextView(context);
            value.setText(sat.snr >= 0 ? String.valueOf(sat.snr) : "—");
            value.setTextSize(13f);
            value.setTextColor(COLOR_GREY);
            row.addView(value, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            satsContainer.addView(row);
        }
    }
}
