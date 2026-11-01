package id.hawx.astro.geomac.layers;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

/**
 * GPS live + follow untuk Atlas (B6). Toggle-only: update lokasi hanya
 * berjalan saat layer ON dan Atlas terbuka — onPause melepas listener,
 * onResume memasangnya lagi, hideAll mematikan semuanya. Tidak ada
 * tracking latar belakang, tidak ada data yang disimpan.
 *
 * Provider: GPS dulu bila menyala, fallback lokasi jaringan bila GPS
 * mati (tetap posisi kasar daripada tidak ada). Follow = setiap update
 * menggeser peta ke posisi; pengguna yang menggeser peta manual akan
 * digeser kembali pada update berikutnya — itu definisi follow di sini.
 */
public final class GpsLive {

    private static final long MIN_TIME_MS = 2000L;
    private static final float MIN_DISTANCE_M = 5f;

    private final Activity activity;
    private final Context context;
    private final MapView map;
    private final AtlasLayers.Alive alive;

    private LocationManager locationManager;
    private Marker marker;
    private boolean on;

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            updatePosition(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    public GpsLive(Activity activity, Context context, MapView map,
                   AtlasLayers.Alive alive) {
        this.activity = activity;
        this.context = context;
        this.map = map;
        this.alive = alive;
    }

    /**
     * Idempotent. Mengembalikan false bila tidak ada provider lokasi yang
     * menyala (pemanggil membatalkan toggle + toast). Tidak melempar
     * SecurityException: izin sudah digerbang di GeoMac sebelum setOn(true).
     */
    public boolean setOn(boolean on) {
        if (on == this.on) return true;
        this.on = on;
        if (on) {
            return start();
        }
        stop();
        removeMarker();
        return true;
    }

    /** Berhenti update tapi pertahankan flag ON + marker (backgrounding). */
    public void onPause() {
        stop();
    }

    /** Pasang lagi update bila layer masih ON. */
    public void onResume() {
        if (on) {
            start();
        }
    }

    /** Matikan total + hapus marker (view dihancurkan). */
    public void hideAll() {
        on = false;
        stop();
        removeMarker();
    }

    private String pickProvider() {
        if (locationManager == null) {
            locationManager = (LocationManager) context.getSystemService(
                    Context.LOCATION_SERVICE);
        }
        if (locationManager == null) return null;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean start() {
        String provider = pickProvider();
        if (provider == null) {
            on = false;
            return false;
        }
        try {
            locationManager.requestLocationUpdates(provider, MIN_TIME_MS,
                    MIN_DISTANCE_M, listener);
            Location last = locationManager.getLastKnownLocation(provider);
            if (last != null) {
                updatePosition(last);
            }
        } catch (SecurityException e) {
            on = false;
            return false;
        } catch (Exception e) {
            on = false;
            return false;
        }
        return true;
    }

    private void stop() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }
    }

    private void removeMarker() {
        if (marker != null) {
            map.getOverlays().remove(marker);
            marker = null;
            map.invalidate();
        }
    }

    private void updatePosition(final Location location) {
        if (location == null || activity == null) return;
        activity.runOnUiThread(() -> {
            if (alive != null && !alive.isAlive()) return;
            GeoPoint point = new GeoPoint(location.getLatitude(),
                    location.getLongitude());
            if (marker == null) {
                marker = new Marker(map);
                marker.setIcon(LayerMarkers.dot(context,
                        Color.parseColor("#1E88E5"), 16));
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                marker.setTitle("Lokasi saya");
                map.getOverlays().add(marker);
            }
            marker.setPosition(point);
            if (location.hasAccuracy()) {
                marker.setSnippet("±" + Math.round(location.getAccuracy()) + " m");
            }
            map.getController().animateTo(point);
            map.invalidate();
        });
    }
}
