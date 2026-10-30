package id.hawx.astro.geomac.layers;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import id.hawx.astro.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;
import org.osmdroid.views.overlay.TilesOverlay;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Toggleable world layers for Atlas (formerly GeoMac). Every layer fetches
 * on demand while it is ON and stops completely while OFF: no background
 * polling, no network use from a hidden layer.
 *
 * Refresh loops run on the main thread and re-fetch only while the layer is
 * still ON and the fragment is alive. onPause stops the loops; onResume
 * restarts them for layers that are still ON.
 */
public final class AtlasLayers {

    public interface Alive {
        boolean isAlive();
    }

    public enum Layer {
        WEATHER, FLIGHTS, QUAKES
    }

    private static final long FLIGHTS_REFRESH_MS = 60_000L;
    private static final long QUAKES_REFRESH_MS = 600_000L;
    private static final long WEATHER_REFRESH_MS = 600_000L;
    private static final int MAX_FLIGHTS = 300;
    private static final int MAX_QUAKES = 500;

    private final Activity activity;
    private final Context context;
    private final MapView map;
    private final Alive alive;
    private final TextView creditView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean weatherOn;
    private boolean flightsOn;
    private boolean quakesOn;

    private TilesOverlay weatherOverlay;
    private ItemizedIconOverlay<OverlayItem> flightsOverlay;
    private ItemizedIconOverlay<OverlayItem> quakesOverlay;

    private Runnable flightsPending;
    private Runnable quakesPending;
    private Runnable weatherPending;

    public AtlasLayers(Activity activity, Context context, MapView map, Alive alive,
                       TextView creditView) {
        this.activity = activity;
        this.context = context;
        this.map = map;
        this.alive = alive;
        this.creditView = creditView;
    }

    /** Idempotent: toggling to the state the layer is already in does nothing. */
    public void toggle(Layer layer, boolean on) {
        switch (layer) {
            case WEATHER:
                if (on == weatherOn) return;
                weatherOn = on;
                if (on) {
                    fetchWeather(false);
                    scheduleWeatherRefresh();
                } else {
                    cancelWeather();
                }
                break;
            case FLIGHTS:
                if (on == flightsOn) return;
                flightsOn = on;
                if (on) {
                    fetchFlights(false);
                    scheduleFlightsRefresh();
                } else {
                    cancelFlights();
                }
                break;
            case QUAKES:
                if (on == quakesOn) return;
                quakesOn = on;
                if (on) {
                    fetchQuakes(false);
                    scheduleQuakesRefresh();
                } else {
                    cancelQuakes();
                }
                break;
        }
    }

    /** Stops refresh loops but keeps overlays and ON flags (backgrounding). */
    public void onPause() {
        cancelPendingOnly();
    }

    /** Re-fetches and restarts loops for layers that are still ON. */
    public void onResume() {
        if (!alive.isAlive()) return;
        if (weatherOn) {
            fetchWeather(true);
            scheduleWeatherRefresh();
        }
        if (flightsOn) {
            fetchFlights(true);
            scheduleFlightsRefresh();
        }
        if (quakesOn) {
            fetchQuakes(true);
            scheduleQuakesRefresh();
        }
    }

    /** Removes every layer overlay and stops every loop (view destroyed). */
    public void hideAll() {
        weatherOn = false;
        flightsOn = false;
        quakesOn = false;
        cancelWeather();
        cancelFlights();
        cancelQuakes();
    }

    private void cancelPendingOnly() {
        if (flightsPending != null) handler.removeCallbacks(flightsPending);
        if (quakesPending != null) handler.removeCallbacks(quakesPending);
        if (weatherPending != null) handler.removeCallbacks(weatherPending);
        flightsPending = null;
        quakesPending = null;
        weatherPending = null;
    }

    private void cancelWeather() {
        if (weatherPending != null) handler.removeCallbacks(weatherPending);
        weatherPending = null;
        if (weatherOverlay != null) map.getOverlays().remove(weatherOverlay);
        weatherOverlay = null;
        setCreditVisible(false);
        map.invalidate();
    }

    private void cancelFlights() {
        if (flightsPending != null) handler.removeCallbacks(flightsPending);
        flightsPending = null;
        if (flightsOverlay != null) map.getOverlays().remove(flightsOverlay);
        flightsOverlay = null;
        map.invalidate();
    }

    private void cancelQuakes() {
        if (quakesPending != null) handler.removeCallbacks(quakesPending);
        quakesPending = null;
        if (quakesOverlay != null) map.getOverlays().remove(quakesOverlay);
        quakesOverlay = null;
        map.invalidate();
    }

    private void scheduleFlightsRefresh() {
        if (flightsPending != null) handler.removeCallbacks(flightsPending);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!flightsOn || !alive.isAlive()) return;
                fetchFlights(true);
                scheduleFlightsRefresh();
            }
        };
        flightsPending = r;
        handler.postDelayed(r, FLIGHTS_REFRESH_MS);
    }

    private void scheduleQuakesRefresh() {
        if (quakesPending != null) handler.removeCallbacks(quakesPending);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!quakesOn || !alive.isAlive()) return;
                fetchQuakes(true);
                scheduleQuakesRefresh();
            }
        };
        quakesPending = r;
        handler.postDelayed(r, QUAKES_REFRESH_MS);
    }

    private void scheduleWeatherRefresh() {
        if (weatherPending != null) handler.removeCallbacks(weatherPending);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!weatherOn || !alive.isAlive()) return;
                fetchWeather(true);
                scheduleWeatherRefresh();
            }
        };
        weatherPending = r;
        handler.postDelayed(r, WEATHER_REFRESH_MS);
    }

    private void fetchWeather(final boolean quiet) {
        new Thread(() -> {
            String host = "";
            String path = "";
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url("https://api.rainviewer.com/public/weather-maps.json").build();
                try (okhttp3.Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONObject root = new JSONObject(resp.body().string());
                        host = root.optString("host", "");
                        JSONObject radar = root.optJSONObject("radar");
                        if (radar != null) {
                            JSONArray past = radar.optJSONArray("past");
                            if (past != null && past.length() > 0) {
                                path = past.optJSONObject(past.length() - 1).optString("path", "");
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            if (host.isEmpty() || path.isEmpty()) {
                if (!quiet) postToast(R.string.geomac_layer_failed);
                return;
            }
            final String finalHost = host;
            final String finalPath = path;
            postToUi(() -> {
                if (weatherOverlay != null) map.getOverlays().remove(weatherOverlay);
                RainViewerTiles source = new RainViewerTiles(finalHost, finalPath);
                weatherOverlay = new TilesOverlay(
                        new MapTileProviderBasic(context, source), context);
                map.getOverlays().add(0, weatherOverlay);
                setCreditVisible(true);
                map.invalidate();
            });
        }, "atlas-weather").start();
    }

    private void fetchFlights(final boolean quiet) {
        BoundingBox box = map.getBoundingBox();
        final double south = clampLat(box.getLatSouth());
        final double north = clampLat(box.getLatNorth());
        final double west = clampLon(box.getLonWest());
        final double east = clampLon(box.getLonEast());
        // Antimeridian crossing: drop the longitude bounds instead of a bad request.
        final boolean useLon = west <= east;
        new Thread(() -> {
            ArrayList<OverlayItem> items = new ArrayList<>();
            try {
                StringBuilder url = new StringBuilder(
                        "https://opensky-network.org/api/states/all?lamin=")
                        .append(south).append("&lamax=").append(north);
                if (useLon) url.append("&lomin=").append(west).append("&lomax=").append(east);
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Request req = new okhttp3.Request.Builder().url(url.toString()).build();
                try (okhttp3.Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        if (!quiet) postToast(R.string.geomac_layer_failed);
                        return;
                    }
                    JSONObject root = new JSONObject(resp.body().string());
                    JSONArray states = root.optJSONArray("states");
                    if (states == null) return;
                    for (int i = 0; i < states.length() && items.size() < MAX_FLIGHTS; i++) {
                        JSONArray s = states.optJSONArray(i);
                        if (s == null || s.length() < 9) continue;
                        if (s.isNull(5) || s.isNull(6)) continue;
                        double lon = s.optDouble(5, Double.NaN);
                        double lat = s.optDouble(6, Double.NaN);
                        if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                        if (s.optBoolean(8, false)) continue; // on ground
                        String callsign = s.optString(1, "").trim();
                        if (callsign.isEmpty()) callsign = s.optString(0, "");
                        String country = s.optString(2, "");
                        double alt = s.optDouble(7, Double.NaN);
                        String snippet = country;
                        if (!Double.isNaN(alt)) {
                            snippet += " · " + Math.round(alt) + " m";
                        }
                        OverlayItem item = new OverlayItem(callsign, snippet,
                                new GeoPoint(lat, lon));
                        item.setMarker(LayerMarkers.dot(context,
                                Color.parseColor("#039BE5"), 12));
                        item.setMarkerHotspot(OverlayItem.HotspotPlace.CENTER);
                        items.add(item);
                    }
                }
            } catch (Exception ex) {
                if (!quiet) postToast(R.string.geomac_layer_failed);
                return;
            }
            final ArrayList<OverlayItem> finalItems = items;
            postToUi(() -> {
                if (flightsOverlay != null) map.getOverlays().remove(flightsOverlay);
                flightsOverlay = buildMarkerOverlay(finalItems);
                map.getOverlays().add(flightsOverlay);
                map.invalidate();
            });
        }, "atlas-flights").start();
    }

    private void fetchQuakes(final boolean quiet) {
        new Thread(() -> {
            ArrayList<OverlayItem> items = new ArrayList<>();
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson")
                        .build();
                try (okhttp3.Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        if (!quiet) postToast(R.string.geomac_layer_failed);
                        return;
                    }
                    JSONObject root = new JSONObject(resp.body().string());
                    JSONArray features = root.optJSONArray("features");
                    if (features == null) return;
                    for (int i = 0; i < features.length() && items.size() < MAX_QUAKES; i++) {
                        JSONObject f = features.optJSONObject(i);
                        if (f == null) continue;
                        JSONObject props = f.optJSONObject("properties");
                        JSONObject geom = f.optJSONObject("geometry");
                        if (props == null || geom == null) continue;
                        JSONArray coords = geom.optJSONArray("coordinates");
                        if (coords == null || coords.length() < 2) continue;
                        double lon = coords.optDouble(0, Double.NaN);
                        double lat = coords.optDouble(1, Double.NaN);
                        double depth = coords.optDouble(2, Double.NaN);
                        if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                        double mag = props.optDouble("mag", Double.NaN);
                        if (Double.isNaN(mag)) mag = 0.0;
                        String place = props.optString("place", "");
                        int color;
                        if (mag < 3.0) color = Color.parseColor("#43A047");
                        else if (mag < 5.0) color = Color.parseColor("#FB8C00");
                        else color = Color.parseColor("#E53935");
                        int sizeDp = 12 + (int) Math.min(mag, 8.0) * 2;
                        String title = "M" + String.format(Locale.US, "%.1f", mag)
                                + (place.isEmpty() ? "" : " " + place);
                        String snippet = Double.isNaN(depth)
                                ? "" : "Depth " + Math.round(depth) + " km";
                        OverlayItem item = new OverlayItem(title, snippet,
                                new GeoPoint(lat, lon));
                        item.setMarker(LayerMarkers.dot(context, color, sizeDp));
                        item.setMarkerHotspot(OverlayItem.HotspotPlace.CENTER);
                        items.add(item);
                    }
                }
            } catch (Exception ex) {
                if (!quiet) postToast(R.string.geomac_layer_failed);
                return;
            }
            final ArrayList<OverlayItem> finalItems = items;
            postToUi(() -> {
                if (quakesOverlay != null) map.getOverlays().remove(quakesOverlay);
                quakesOverlay = buildMarkerOverlay(finalItems);
                map.getOverlays().add(quakesOverlay);
                map.invalidate();
            });
        }, "atlas-quakes").start();
    }

    private ItemizedIconOverlay<OverlayItem> buildMarkerOverlay(
            final ArrayList<OverlayItem> items) {
        return new ItemizedIconOverlay<>(items,
                new ItemizedIconOverlay.OnItemGestureListener<OverlayItem>() {
                    @Override
                    public boolean onItemSingleTapUp(int index, OverlayItem item) {
                        if (item != null) {
                            String text = item.getTitle() == null ? "" : item.getTitle();
                            if (item.getSnippet() != null && !item.getSnippet().isEmpty()) {
                                text += "\n" + item.getSnippet();
                            }
                            if (!text.trim().isEmpty()) {
                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
                            }
                        }
                        return true;
                    }

                    @Override
                    public boolean onItemLongPress(int index, OverlayItem item) {
                        return false;
                    }
                }, context);
    }

    private void postToUi(Runnable r) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (!alive.isAlive()) return;
            r.run();
        });
    }

    private void postToast(final int resId) {
        postToUi(() -> Toast.makeText(context, resId, Toast.LENGTH_SHORT).show());
    }

    private void setCreditVisible(boolean visible) {
        if (creditView != null) {
            creditView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private static double clampLat(double v) {
        if (v < -90.0) return -90.0;
        if (v > 90.0) return 90.0;
        return v;
    }

    private static double clampLon(double v) {
        if (v < -180.0) return -180.0;
        if (v > 180.0) return 180.0;
        return v;
    }
}
