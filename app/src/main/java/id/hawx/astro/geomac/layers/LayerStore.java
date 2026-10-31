package id.hawx.astro.geomac.layers;

import id.hawx.astro.R;
import id.hawx.astro.utils.Core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for Atlas layer state (redesign Fase 0).
 * Replaces the scattered booleans + chip state + satellite pref reflection
 * that made every new layer a patch: GeoMac, the Layer Sheet, and the
 * credit line all read from here, and only the host writes through it.
 * Layers start OFF on every open (as before); only the basemap choice
 * persists, under the same pref key the old satellite chip used, so an
 * existing user setting survives the migration.
 */
public final class LayerStore {

    public static final String WEATHER = "weather";
    public static final String FLIGHTS = "flights";
    public static final String QUAKES = "quakes";
    public static final String CAMERAS = "cameras";
    public static final String TRAFFIC = "traffic";

    private static final String PREF_SATELLITE = "geomac_satellite";

    private final Core core;
    private final Map<String, AtlasLayer> layers = new LinkedHashMap<>();
    private boolean satellite;

    public LayerStore(Core core) {
        this.core = core;
        layers.put(WEATHER, new AtlasLayer(WEATHER, R.string.geomac_layer_weather,
                AtlasLayer.Kind.TILE, "RainViewer"));
        layers.put(FLIGHTS, new AtlasLayer(FLIGHTS, R.string.geomac_layer_flights,
                AtlasLayer.Kind.MARKER, "OpenSky"));
        layers.put(QUAKES, new AtlasLayer(QUAKES, R.string.geomac_layer_quakes,
                AtlasLayer.Kind.MARKER, "USGS"));
        layers.put(CAMERAS, new AtlasLayer(CAMERAS, R.string.geomac_layer_cameras,
                AtlasLayer.Kind.MARKER, "Windy"));
        layers.put(TRAFFIC, new AtlasLayer(TRAFFIC, R.string.geomac_layer_traffic,
                AtlasLayer.Kind.FEATURE, "Perangkat"));
        satellite = core != null && core.getBoolean(PREF_SATELLITE);
    }

    public List<AtlasLayer> all() {
        return new ArrayList<>(layers.values());
    }

    public boolean isOn(String id) {
        AtlasLayer layer = layers.get(id);
        return layer != null && layer.isOn();
    }

    public void setLayer(String id, boolean on) {
        AtlasLayer layer = layers.get(id);
        if (layer != null) {
            layer.setOn(on);
        }
    }

    public boolean isSatellite() {
        return satellite;
    }

    public void setSatellite(boolean on) {
        satellite = on;
        if (core != null) {
            core.putBoolean(PREF_SATELLITE, on);
        }
    }
}
