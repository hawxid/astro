package id.hawx.astro.geomac;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * Nearest street-level photo from Mapillary (free, token-only). v1 shows a
 * single flat photo, not a 360 viewer. If the first radius comes up empty the
 * search widens once (about 200 m, then about 1 km).
 */
public final class Mapillary {

    public static final class Photo {
        public final String id;
        public final String thumbUrl;
        public final double lat;
        public final double lon;

        public Photo(String id, String thumbUrl, double lat, double lon) {
            this.id = id;
            this.thumbUrl = thumbUrl;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private Mapillary() {}

    public static Photo nearest(String token, double lat, double lon) {
        if (token == null || token.isEmpty()) return null;
        Photo p = search(token, lat, lon, 0.002);
        if (p != null) return p;
        return search(token, lat, lon, 0.01);
    }

    private static Photo search(String token, double lat, double lon, double halfDeg) {
        try {
            double minLon = lon - halfDeg;
            double minLat = lat - halfDeg;
            double maxLon = lon + halfDeg;
            double maxLat = lat + halfDeg;
            String bbox = minLon + "," + minLat + "," + maxLon + "," + maxLat;
            String url = "https://graph.mapillary.com/images?access_token="
                    + URLEncoder.encode(token, "UTF-8")
                    + "&bbox=" + URLEncoder.encode(bbox, "UTF-8")
                    + "&fields=id,thumb_1024_url,geometry,computed_geometry"
                    + "&limit=20";
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JSONObject root = new JSONObject(resp.body().string());
                JSONArray data = root.optJSONArray("data");
                if (data == null || data.length() == 0) return null;
                Photo best = null;
                double bestDist = Double.MAX_VALUE;
                for (int i = 0; i < data.length(); i++) {
                    JSONObject o = data.optJSONObject(i);
                    if (o == null) continue;
                    String thumb = o.optString("thumb_1024_url", "");
                    if (thumb.isEmpty()) continue;
                    double[] coords = coordsOf(o);
                    if (coords == null) continue;
                    double d = haversineKm(lat, lon, coords[0], coords[1]);
                    if (d < bestDist) {
                        bestDist = d;
                        best = new Photo(o.optString("id", ""), thumb, coords[0], coords[1]);
                    }
                }
                return best;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] coordsOf(JSONObject o) {
        JSONObject g = o.optJSONObject("geometry");
        if (g == null) g = o.optJSONObject("computed_geometry");
        if (g == null) return null;
        JSONArray c = g.optJSONArray("coordinates");
        if (c == null || c.length() < 2) return null;
        double lon = c.optDouble(0, Double.NaN);
        double lat = c.optDouble(1, Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        return new double[]{ lat, lon };
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.asin(Math.sqrt(a));
    }
}
