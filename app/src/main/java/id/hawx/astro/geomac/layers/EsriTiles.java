package id.hawx.astro.geomac.layers;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/**
 * Esri World Imagery tiles (global satellite/aerial imagery). ArcGIS REST
 * uses z/y/x order (row = y, column = x), which matches osmdroid's
 * MapTileIndex directly. Replaces the old USGS_SAT source, whose imagery
 * covers the U.S. only and would show empty tiles in Indonesia.
 *
 * Free for non-commercial use; attribution is shown in the Atlas UI while
 * this source is active. Tiles are heavier than the street map, so the
 * satellite view is opt-in per session.
 */
public final class EsriTiles extends OnlineTileSourceBase {

    private static final String BASE =
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer";

    public EsriTiles() {
        super("EsriWorldImagery", 0, 19, 256, ".jpg", new String[]{ BASE });
    }

    @Override
    public String getTileURLString(long pMapTileIndex) {
        int z = MapTileIndex.getZoom(pMapTileIndex);
        int x = MapTileIndex.getX(pMapTileIndex);
        int y = MapTileIndex.getY(pMapTileIndex);
        return BASE + "/tile/" + z + "/" + y + "/" + x;
    }
}
