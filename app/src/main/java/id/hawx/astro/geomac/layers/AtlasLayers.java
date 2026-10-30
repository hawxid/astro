package id.hawx.astro.geomac.layers;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

    public interface KeyProvider {
        String windyKey();
    }

    public enum Layer {
        WEATHER, FLIGHTS, QUAKES, CAMERAS
    }

    private static final long FLIGHTS_REFRESH_MS = 60_000L;
    private static final long QUAKES_REFRESH_MS = 600_000L;
    private static final long WEATHER_REFRESH_MS = 600_000L;
    private static final int MAX_FLIGHTS = 300;
    private static final int MAX_QUAKES = 500;
    private static final long CAMERAS_REFRESH_MS = 600_000L;
    private static final int MAX_CAMERAS = 100;

    private final Activity activity;
    private final Context context;
    private final MapView map;
    private final Alive alive;
    private final TextView creditView;
    private final KeyProvider keyProvider;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean weatherOn;
    private boolean flightsOn;
    private boolean quakesOn;
    private boolean camerasOn;

    private TilesOverlay weatherOverlay;
    private ItemizedIconOverlay<OverlayItem> flightsOverlay;
    private ItemizedIconOverlay<OverlayItem> quakesOverlay;
    private ItemizedIconOverlay<OverlayItem> camerasOverlay;
    private final ArrayList<String> cameraIds = new ArrayList<>();

    private Runnable flightsPending;
    private Runnable quakesPending;
    private Runnable weatherPending;
    private Runnable camerasPending;

    public AtlasLayers(Activity activity, Context context, MapView map, Alive alive,
                       TextView creditView, KeyProvider keyProvider) {
        this.activity = activity;
        this.context = context;
        this.map = map;
        this.alive = alive;
        this.creditView = creditView;
        this.keyProvider = keyProvider;
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
            case CAMERAS:
                if (on == camerasOn) return;
                camerasOn = on;
                if (on) {
                    fetchCameras(false);
                    scheduleCamerasRefresh();
                } else {
                    cancelCameras();
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
        if (camerasOn) {
            fetchCameras(true);
            scheduleCamerasRefresh();
        }
    }

    /** Removes every layer overlay and stops every loop (view destroyed). */
    public void hideAll() {
        weatherOn = false;
        flightsOn = false;
        quakesOn = false;
        camerasOn = false;
        cancelWeather();
        cancelFlights();
        cancelQuakes();
        cancelCameras();
    }

    private void cancelPendingOnly() {
        if (flightsPending != null) handler.removeCallbacks(flightsPending);
        if (quakesPending != null) handler.removeCallbacks(quakesPending);
        if (weatherPending != null) handler.removeCallbacks(weatherPending);
        if (camerasPending != null) handler.removeCallbacks(camerasPending);
        flightsPending = null;
        quakesPending = null;
        weatherPending = null;
        camerasPending = null;
    }

    private void cancelWeather() {
        if (weatherPending != null) handler.removeCallbacks(weatherPending);
        weatherPending = null;
        if (weatherOverlay != null) map.getOverlays().remove(weatherOverlay);
        weatherOverlay = null;
        updateCredit();
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

    private void cancelCameras() {
        if (camerasPending != null) handler.removeCallbacks(camerasPending);
        camerasPending = null;
        if (camerasOverlay != null) map.getOverlays().remove(camerasOverlay);
        camerasOverlay = null;
        cameraIds.clear();
        updateCredit();
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

    private void scheduleCamerasRefresh() {
        if (camerasPending != null) handler.removeCallbacks(camerasPending);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!camerasOn || !alive.isAlive()) return;
                fetchCameras(true);
                scheduleCamerasRefresh();
            }
        };
        camerasPending = r;
        handler.postDelayed(r, CAMERAS_REFRESH_MS);
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
                updateCredit();
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

    private void fetchCameras(final boolean quiet) {
        BoundingBox box = map.getBoundingBox();
        final double south = clampLat(box.getLatSouth());
        final double north = clampLat(box.getLatNorth());
        double west = clampLon(box.getLonWest());
        double east = clampLon(box.getLonEast());
        // Antimeridian crossing: fall back to the full longitude range.
        if (west > east) {
            west = -180.0;
            east = 180.0;
        }
        final double finalWest = west;
        final double finalEast = east;
        new Thread(() -> {
            String key = keyProvider == null ? "" : keyProvider.windyKey();
            if (key == null || key.isEmpty()) return;
            ArrayList<OverlayItem> items = new ArrayList<>();
            ArrayList<String> ids = new ArrayList<>();
            try {
                // Windy v3: bbox order is north,east,south,west; limit caps at 50,
                // so a second page (offset 50) is fetched when the first is full.
                boolean ok = fetchCameraPage(key, north, finalEast, south, finalWest,
                        0, items, ids);
                if (!ok) {
                    if (!quiet) postToast(R.string.geomac_layer_failed);
                    return;
                }
                if (items.size() == 50) {
                    fetchCameraPage(key, north, finalEast, south, finalWest,
                            50, items, ids);
                }
            } catch (Exception ex) {
                if (!quiet) postToast(R.string.geomac_layer_failed);
                return;
            }
            final ArrayList<OverlayItem> finalItems = items;
            final ArrayList<String> finalIds = ids;
            postToUi(() -> {
                if (!camerasOn) return;
                if (camerasOverlay != null) map.getOverlays().remove(camerasOverlay);
                cameraIds.clear();
                cameraIds.addAll(finalIds);
                camerasOverlay = buildCameraOverlay(finalItems);
                map.getOverlays().add(camerasOverlay);
                updateCredit();
                map.invalidate();
            });
        }, "atlas-cameras").start();
    }

    private boolean fetchCameraPage(String key, double north, double east, double south,
                                    double west, int offset,
                                    ArrayList<OverlayItem> items, ArrayList<String> ids) {
        try {
            String url = "https://api.windy.com/webcams/api/v3/webcams"
                    + "?bbox=" + north + "," + east + "," + south + "," + west
                    + "&limit=50&offset=" + offset
                    + "&include=images,location&lang=en";
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("X-WINDY-API-KEY", key)
                    .build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return false;
                JSONObject root = new JSONObject(resp.body().string());
                JSONArray webcams = root.optJSONArray("webcams");
                if (webcams == null) return false;
                for (int i = 0; i < webcams.length() && items.size() < MAX_CAMERAS; i++) {
                    JSONObject o = webcams.optJSONObject(i);
                    if (o == null) continue;
                    if (!"active".equals(o.optString("status", ""))) continue;
                    long webcamId = o.optLong("webcamId", 0);
                    if (webcamId == 0) continue;
                    JSONObject loc = o.optJSONObject("location");
                    if (loc == null) continue;
                    double lat = loc.optDouble("latitude", Double.NaN);
                    double lon = loc.optDouble("longitude", Double.NaN);
                    if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                    String imageUrl = cameraImageUrl(o);
                    if (imageUrl.isEmpty()) continue;
                    String title = o.optString("title", "");
                    String city = loc.optString("city", "");
                    String country = loc.optString("country", "");
                    if (title.isEmpty()) title = city.isEmpty() ? "Camera" : city;
                    String snippet = city;
                    if (!country.isEmpty()) {
                        snippet = snippet.isEmpty() ? country : snippet + ", " + country;
                    }
                    OverlayItem item = new OverlayItem(title, snippet, new GeoPoint(lat, lon));
                    item.setMarker(LayerMarkers.dot(context,
                            Color.parseColor("#8E24AA"), 12));
                    item.setMarkerHotspot(OverlayItem.HotspotPlace.CENTER);
                    items.add(item);
                    ids.add(String.valueOf(webcamId));
                }
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String cameraImageUrl(JSONObject o) {
        JSONObject images = o.optJSONObject("images");
        if (images == null) return "";
        JSONObject current = images.optJSONObject("current");
        if (current == null) return "";
        // Free tier: only small sizes. Prefer "preview", then "icon",
        // then any other URL in the current object.
        String url = current.optString("preview", "");
        if (!url.isEmpty()) return url;
        url = current.optString("icon", "");
        if (!url.isEmpty()) return url;
        JSONArray names = current.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String v = current.optString(names.optString(i), "");
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    private ItemizedIconOverlay<OverlayItem> buildCameraOverlay(
            final ArrayList<OverlayItem> items) {
        return new ItemizedIconOverlay<>(items,
                new ItemizedIconOverlay.OnItemGestureListener<OverlayItem>() {
                    @Override
                    public boolean onItemSingleTapUp(int index, OverlayItem item) {
                        if (index >= 0 && index < cameraIds.size()) {
                            showCameraDialog(cameraIds.get(index));
                        }
                        return true;
                    }

                    @Override
                    public boolean onItemLongPress(int index, OverlayItem item) {
                        return false;
                    }
                }, context);
    }

    private void showCameraDialog(final String webcamId) {
        new Thread(() -> {
            String key = keyProvider == null ? "" : keyProvider.windyKey();
            String title = "";
            String place = "";
            String player = "";
            Bitmap bmp = null;
            if (key != null && !key.isEmpty()) {
                try {
                    // Re-fetched on every open: free-tier image URLs expire
                    // after 10 minutes (401 when stale).
                    String url = "https://api.windy.com/webcams/api/v3/webcams/" + webcamId
                            + "?include=images,location,player&lang=en";
                    okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                    okhttp3.Request req = new okhttp3.Request.Builder()
                            .url(url)
                            .header("X-WINDY-API-KEY", key)
                            .build();
                    try (okhttp3.Response resp = client.newCall(req).execute()) {
                        if (resp.isSuccessful() && resp.body() != null) {
                            JSONObject o = new JSONObject(resp.body().string());
                            title = o.optString("title", "");
                            // Timelapse player: Windy v3 `player` is an OBJECT
                            // of embed links per timespan (day/month/year/
                            // lifetime/live), NOT a plain string — parsing it
                            // as a string would always yield "" and the button
                            // would never appear. Day timelapse first, then
                            // longer spans, live stream only as last resort.
                            // No raw mp4 is offered by the API, so the player
                            // page itself is the timelapse — opened in-app.
                            JSONObject playerObj = o.optJSONObject("player");
                            if (playerObj != null) {
                                for (String span : new String[]{"day", "month",
                                        "year", "lifetime", "live"}) {
                                    player = playerObj.optString(span, "");
                                    if (!player.isEmpty()) break;
                                }
                            }
                            JSONObject loc = o.optJSONObject("location");
                            String city = loc == null ? "" : loc.optString("city", "");
                            String country = loc == null ? "" : loc.optString("country", "");
                            place = city;
                            if (!country.isEmpty()) {
                                place = place.isEmpty() ? country : place + ", " + country;
                            }
                            String imageUrl = cameraImageUrl(o);
                            if (!imageUrl.isEmpty()) bmp = loadBitmap(imageUrl);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            final String finalTitle = title;
            final String finalPlace = place;
            final String finalPlayer = player;
            final Bitmap finalBmp = bmp;
            postToUi(() -> {
                if (finalBmp == null) {
                    Toast.makeText(context, R.string.geomac_layer_failed,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                final Dialog d = new Dialog(context);
                LinearLayout root = new LinearLayout(context);
                root.setOrientation(LinearLayout.VERTICAL);
                float density = context.getResources().getDisplayMetrics().density;
                int pad = Math.round(14 * density);
                root.setPadding(pad, pad, pad, pad);
                ImageView img = new ImageView(context);
                LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        Math.round(280 * density));
                img.setLayoutParams(imgLp);
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                img.setImageBitmap(finalBmp);
                TextView caption = new TextView(context);
                caption.setTextSize(11f);
                caption.setTextColor(Color.GRAY);
                LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                capLp.topMargin = Math.round(8 * density);
                caption.setLayoutParams(capLp);
                String captionText = finalTitle;
                if (!finalPlace.isEmpty()) {
                    captionText = captionText.isEmpty()
                            ? finalPlace : captionText + " · " + finalPlace;
                }
                caption.setText(captionText);
                LinearLayout btnRow = new LinearLayout(context);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setGravity(android.view.Gravity.END);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = Math.round(12 * density);
                btnRow.setLayoutParams(rowLp);
                if (finalPlayer != null && !finalPlayer.isEmpty()) {
                    Button timelapse = new Button(context);
                    timelapse.setText(R.string.geomac_camera_timelapse);
                    LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    tlLp.setMarginEnd(Math.round(8 * density));
                    timelapse.setLayoutParams(tlLp);
                    timelapse.setOnClickListener(v -> showTimelapseDialog(finalPlayer));
                    btnRow.addView(timelapse);
                }
                Button close = new Button(context);
                close.setText(R.string.geomac_streetview_close);
                close.setOnClickListener(v -> d.dismiss());
                btnRow.addView(close);
                root.addView(img);
                root.addView(caption);
                root.addView(btnRow);
                d.setContentView(root);
                Window w = d.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                }
                d.show();
            });
        }, "atlas-camera-photo").start();
    }

    /** Opens the Windy timelapse player page in an in-app WebView dialog. */
    private void showTimelapseDialog(final String playerUrl) {
        final Dialog d = new Dialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        final WebView web = new WebView(context);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        web.setLayoutParams(webLp);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.loadUrl(playerUrl);
        Button close = new Button(context);
        close.setText(R.string.geomac_streetview_close);
        close.setOnClickListener(v -> d.dismiss());
        root.addView(web);
        root.addView(close);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
        }
        d.setOnDismissListener(dialog -> {
            web.stopLoading();
            web.destroy();
        });
        d.show();
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

    private void updateCredit() {
        if (creditView == null) return;
        StringBuilder sb = new StringBuilder();
        if (weatherOn) sb.append(context.getString(R.string.geomac_layer_credit));
        if (camerasOn) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(context.getString(R.string.geomac_layer_credit_cameras));
        }
        if (sb.length() == 0) {
            creditView.setVisibility(View.GONE);
        } else {
            creditView.setText(sb.toString());
            creditView.setVisibility(View.VISIBLE);
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
