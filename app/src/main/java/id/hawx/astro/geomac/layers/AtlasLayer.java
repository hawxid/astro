package id.hawx.astro.geomac.layers;

/**
 * One Atlas layer: a single row in the Layer Sheet (redesign Fase 1).
 * The store owns on/off state; the sheet only renders it and the host
 * (GeoMac) applies it. Adding a layer later means adding one object
 * here plus its fetch logic — never a new chip, boolean, or dialog.
 */
public final class AtlasLayer {

    public enum Kind {
        TILE,    // raster drawn over the map (weather radar)
        MARKER,  // point markers (flights, quakes, cameras)
        FEATURE  // behaviour, not map content (live traffic)
    }

    public final String id;
    public final int titleRes;
    public final Kind kind;
    public final String source;
    private boolean on;

    public AtlasLayer(String id, int titleRes, Kind kind, String source) {
        this.id = id;
        this.titleRes = titleRes;
        this.kind = kind;
        this.source = source;
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean on) {
        this.on = on;
    }
}
