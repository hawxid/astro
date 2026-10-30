package id.hawx.astro.geomac.layers;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/**
 * RainViewer radar tiles. The source pyramid tops out at zoom 7 (per RainViewer
 * docs), so at street zoom the radar layer may show no tiles; the base map and
 * pins are unaffected. Radar is a regional layer, so this is acceptable for v1.
 */
public final class RainViewerTiles extends OnlineTileSourceBase {

    private final String host;
    private final String path;

    public RainViewerTiles(String host, String path) {
        super("RainViewer", 0, 7, 256, ".png", new String[]{ host });
        this.host = host;
        this.path = path;
    }

    @Override
    public String getTileURLString(long pMapTileIndex) {
        int z = MapTileIndex.getZoom(pMapTileIndex);
        int x = MapTileIndex.getX(pMapTileIndex);
        int y = MapTileIndex.getY(pMapTileIndex);
        // Color scheme 2, options smooth=1 snow=1 (RainViewer defaults).
        return host + path + "/256/" + z + "/" + x + "/" + y + "/2/1_1.png";
    }
}
