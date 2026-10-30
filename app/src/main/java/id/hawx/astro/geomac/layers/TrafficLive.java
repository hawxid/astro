package id.hawx.astro.geomac.layers;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.net.TrafficStats;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live traffic of this phone for Atlas (v1 scope: TCP/UDP only).
 *
 * Reads the kernel socket tables (/proc/net/tcp, tcp6, udp, udp6) — no VPN,
 * no packet capture, no root. While ON, a poll every 2 s rebuilds the
 * connection list, resolves destination GeoIP via ip-api.com (free, no key,
 * HTTP-only free tier; results cached per IP in RAM), and draws destination
 * markers plus lines from the device's last known location when one exists.
 * Polling stops completely on OFF / pause / destroy, mirroring AtlasLayers.
 *
 * ICMP/ping traffic does not appear in socket tables and is deliberately
 * out of scope for v1 (it needs root packet capture = phase 2).
 *
 * Per-UID byte rates come from TrafficStats, which aggregates per app, not
 * per socket — so every row of one app shows that app's total rate.
 */
public final class TrafficLive {

    private static final long POLL_MS = 2000L;
    private static final int MAX_RAW = 200;
    private static final int MAX_ROWS = 40;
    private static final int GEOIP_PER_POLL = 8;

    private static final class Geo {
        final double lat;
        final double lon;
        final String city;
        final String country;
        final String isp;

        Geo(double lat, double lon, String city, String country, String isp) {
            this.lat = lat;
            this.lon = lon;
            this.city = city;
            this.country = country;
            this.isp = isp;
        }
    }

    private static final class Conn {
        int uid;
        String label;
        String proto;
        String remoteIp;
        int remotePort;
        String state;
        long txRate = -1; // bytes/s, -1 = unknown
        long rxRate = -1;
        Geo geo; // null = not resolved yet (or private)
        boolean isPrivate;
    }

    private final Activity activity;
    private final Context context;
    private final MapView map;
    private final AtlasLayers.Alive alive;
    private final View card;
    private final LinearLayout rows;
    private final TextView emptyView;

    private boolean trafficOn;
    private volatile boolean running;
    private Thread pollThread;

    private final Map<String, Geo> geoCache = new HashMap<>();
    private final Set<String> geoFailed = new HashSet<>();
    private boolean geoBlocked;
    private final Map<Integer, String> uidLabelCache = new HashMap<>();
    private final Map<String, String> pkgLabelCache = new HashMap<>();
    private final Map<Integer, long[]> prevBytes = new HashMap<>();
    private long prevPollMs;

    private ItemizedIconOverlay<OverlayItem> markerOverlay;
    private final ArrayList<Polyline> lines = new ArrayList<>();

    public TrafficLive(Activity activity, Context context, MapView map,
                       AtlasLayers.Alive alive, View card, LinearLayout rows,
                       TextView emptyView) {
        this.activity = activity;
        this.context = context;
        this.map = map;
        this.alive = alive;
        this.card = card;
        this.rows = rows;
        this.emptyView = emptyView;
    }

    /** Idempotent. */
    public void setOn(boolean on) {
        if (on == trafficOn) return;
        trafficOn = on;
        if (on) {
            card.setVisibility(View.VISIBLE);
            start();
        } else {
            stop();
            clearOverlays();
            card.setVisibility(View.GONE);
        }
    }

    /** Stops polling but keeps overlays and the ON flag (backgrounding). */
    public void onPause() {
        stop();
    }

    /** Restarts polling if still ON. */
    public void onResume() {
        if (trafficOn) start();
    }

    /** Stops everything and clears overlays + panel (view destroyed). */
    public void hideAll() {
        trafficOn = false;
        stop();
        clearOverlays();
        card.setVisibility(View.GONE);
    }

    private void start() {
        if (pollThread != null && pollThread.isAlive()) return;
        running = true;
        Thread t = new Thread(() -> {
            while (running && alive.isAlive()) {
                long t0 = System.currentTimeMillis();
                List<Conn> conns = collect();
                resolveGeo(conns);
                final List<Conn> finalConns = conns;
                postToUi(() -> apply(finalConns));
                try {
                    long wait = POLL_MS - (System.currentTimeMillis() - t0);
                    if (wait > 0) Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "atlas-traffic");
        pollThread = t;
        t.start();
    }

    private void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private List<Conn> collect() {
        ArrayList<Conn> out = new ArrayList<>();
        readTable("/proc/net/tcp", "TCP", out);
        readTable("/proc/net/tcp6", "TCP", out);
        readTable("/proc/net/udp", "UDP", out);
        readTable("/proc/net/udp6", "UDP", out);
        Map<String, Conn> uniq = new HashMap<>();
        for (Conn c : out) {
            String key = c.proto + "|" + c.uid + "|" + c.remoteIp + "|" + c.remotePort;
            if (!uniq.containsKey(key)) uniq.put(key, c);
        }
        ArrayList<Conn> list = new ArrayList<>(uniq.values());
        long now = System.currentTimeMillis();
        long elapsed = prevPollMs == 0 ? 0 : now - prevPollMs;
        Map<Integer, long[]> cur = new HashMap<>();
        for (Conn c : list) {
            long tx = TrafficStats.getUidTxBytes(c.uid);
            long rx = TrafficStats.getUidRxBytes(c.uid);
            cur.put(c.uid, new long[]{tx, rx});
            long[] prev = prevBytes.get(c.uid);
            if (elapsed > 0 && prev != null && tx >= 0 && rx >= 0
                    && prev[0] >= 0 && prev[1] >= 0) {
                c.txRate = (tx - prev[0]) * 1000L / elapsed;
                c.rxRate = (rx - prev[1]) * 1000L / elapsed;
                if (c.txRate < 0) c.txRate = 0;
                if (c.rxRate < 0) c.rxRate = 0;
            } else {
                c.txRate = -1;
                c.rxRate = -1;
            }
        }
        prevBytes.clear();
        prevBytes.putAll(cur);
        prevPollMs = now;
        Collections.sort(list, new Comparator<Conn>() {
            @Override
            public int compare(Conn a, Conn b) {
                int d = a.label.compareToIgnoreCase(b.label);
                if (d != 0) return d;
                d = a.remoteIp.compareTo(b.remoteIp);
                if (d != 0) return d;
                return Integer.compare(a.remotePort, b.remotePort);
            }
        });
        if (list.size() > MAX_ROWS) return new ArrayList<>(list.subList(0, MAX_ROWS));
        return list;
    }

    private void readTable(String path, String proto, List<Conn> out) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null && out.size() < MAX_RAW) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 8) continue;
                String remote = f[2];
                int colon = remote.lastIndexOf(':');
                if (colon < 0) continue;
                String ip = parseIp(remote.substring(0, colon));
                if (ip == null || isZeroIp(ip)) continue; // no remote peer
                int port;
                int uid;
                try {
                    port = Integer.parseInt(remote.substring(colon + 1), 16);
                    uid = Integer.parseInt(f[7]);
                } catch (NumberFormatException e) {
                    continue;
                }
                Conn c = new Conn();
                c.uid = uid;
                c.label = uidLabel(uid);
                c.proto = proto;
                c.remoteIp = ip;
                c.remotePort = port;
                c.state = "UDP".equals(proto) ? "UDP" : tcpState(f[3]);
                c.isPrivate = isPrivateIp(ip);
                c.geo = c.isPrivate ? null : geoCache.get(ip);
                out.add(c);
            }
        } catch (Exception ignored) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String parseIp(String hex) {
        try {
            if (hex.length() == 8) {
                // IPv4: bytes stored little-endian in /proc/net/tcp.
                int b0 = Integer.parseInt(hex.substring(0, 2), 16);
                int b1 = Integer.parseInt(hex.substring(2, 4), 16);
                int b2 = Integer.parseInt(hex.substring(4, 6), 16);
                int b3 = Integer.parseInt(hex.substring(6, 8), 16);
                return b3 + "." + b2 + "." + b1 + "." + b0;
            }
            if (hex.length() == 32) {
                // IPv6: each 32-bit word stored little-endian, words in order.
                byte[] addr = new byte[16];
                for (int w = 0; w < 4; w++) {
                    for (int b = 0; b < 4; b++) {
                        int v = Integer.parseInt(
                                hex.substring(w * 8 + b * 2, w * 8 + b * 2 + 2), 16);
                        addr[w * 4 + (3 - b)] = (byte) v;
                    }
                }
                return InetAddress.getByAddress(addr).getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isZeroIp(String ip) {
        return "0.0.0.0".equals(ip) || "::".equals(ip);
    }

    private static boolean isPrivateIp(String ip) {
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")
                || ip.equals("::1") || ip.startsWith("fe80:")
                || ip.startsWith("fc") || ip.startsWith("fd")) return true;
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    private static String tcpState(String hex) {
        if (hex == null) return "?";
        switch (hex.toUpperCase(Locale.US)) {
            case "01": return "ESTABLISHED";
            case "02": return "SYN_SENT";
            case "03": return "SYN_RECV";
            case "04": return "FIN_WAIT1";
            case "05": return "FIN_WAIT2";
            case "06": return "TIME_WAIT";
            case "07": return "CLOSE";
            case "08": return "CLOSE_WAIT";
            case "09": return "LAST_ACK";
            case "0A": return "LISTEN";
            case "0B": return "CLOSING";
            default: return "ST_" + hex;
        }
    }

    private String uidLabel(int uid) {
        String cached = uidLabelCache.get(uid);
        if (cached != null) return cached;
        String label;
        if (uid < 10000) {
            label = "system";
        } else {
            label = "uid " + uid;
            try {
                PackageManager pm = context.getPackageManager();
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) {
                    String pkgLabel = pkgLabelCache.get(pkgs[0]);
                    if (pkgLabel == null) {
                        ApplicationInfo ai = pm.getApplicationInfo(pkgs[0], 0);
                        CharSequence l = ai.loadLabel(pm);
                        pkgLabel = l == null ? pkgs[0] : l.toString();
                        pkgLabelCache.put(pkgs[0], pkgLabel);
                    }
                    label = pkgLabel;
                }
            } catch (Exception ignored) {
            }
        }
        uidLabelCache.put(uid, label);
        return label;
    }

    private void resolveGeo(List<Conn> conns) {
        if (geoBlocked) return;
        int budget = GEOIP_PER_POLL;
        for (Conn c : conns) {
            if (budget <= 0) break;
            if (c.isPrivate || c.geo != null || geoFailed.contains(c.remoteIp)) continue;
            Geo g = fetchGeo(c.remoteIp);
            if (g != null) {
                geoCache.put(c.remoteIp, g);
                c.geo = g;
            } else {
                geoFailed.add(c.remoteIp);
            }
            budget--;
        }
    }

    private Geo fetchGeo(String ip) {
        try {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            String url = "http://ip-api.com/json/" + ip
                    + "?fields=status,lat,lon,city,country,isp";
            okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (resp.code() == 429) {
                    geoBlocked = true; // rate limit: stop GeoIP for this session
                    return null;
                }
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JSONObject o = new JSONObject(resp.body().string());
                if (!"success".equals(o.optString("status", ""))) return null;
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
                return new Geo(lat, lon, o.optString("city", ""),
                        o.optString("country", ""), o.optString("isp", ""));
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void apply(List<Conn> conns) {
        if (!trafficOn) return;
        rows.removeAllViews();
        for (Conn c : conns) {
            TextView tv = new TextView(context);
            tv.setTextSize(11f);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(0, 4, 0, 4);
            StringBuilder sb = new StringBuilder();
            sb.append(c.label).append(" · ").append(c.remoteIp).append(":").append(c.remotePort)
                    .append(" · ").append(c.state);
            if (c.txRate >= 0 && c.rxRate >= 0) {
                sb.append("\n↑").append(formatRate(c.txRate))
                        .append(" ↓").append(formatRate(c.rxRate));
            }
            if (c.isPrivate) {
                sb.append(" · LAN");
            } else if (c.geo != null) {
                sb.append(" · ").append(c.geo.city);
                if (!c.geo.country.isEmpty()) sb.append(", ").append(c.geo.country);
            }
            tv.setText(sb.toString());
            rows.addView(tv);
        }
        emptyView.setVisibility(conns.isEmpty() ? View.VISIBLE : View.GONE);
        if (markerOverlay != null) map.getOverlays().remove(markerOverlay);
        markerOverlay = null;
        for (Polyline line : lines) map.getOverlays().remove(line);
        lines.clear();
        GeoPoint self = deviceLocation();
        ArrayList<OverlayItem> items = new ArrayList<>();
        for (Conn c : conns) {
            if (c.geo == null) continue;
            GeoPoint dest = new GeoPoint(c.geo.lat, c.geo.lon);
            String title = c.label + " → " + c.geo.city + ", " + c.geo.country;
            String snippet = c.remoteIp + ":" + c.remotePort;
            OverlayItem item = new OverlayItem(title, snippet, dest);
            item.setMarker(LayerMarkers.dot(context, Color.parseColor("#FF6D00"), 12));
            item.setMarkerHotspot(OverlayItem.HotspotPlace.CENTER);
            items.add(item);
            if (self != null) {
                Polyline line = new Polyline();
                ArrayList<GeoPoint> pts = new ArrayList<>();
                pts.add(self);
                pts.add(dest);
                line.setPoints(pts);
                line.setColor(Color.parseColor("#FF6D00"));
                line.setWidth(3f);
                lines.add(line);
                map.getOverlays().add(line);
            }
        }
        if (!items.isEmpty()) {
            markerOverlay = new ItemizedIconOverlay<>(items,
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
            map.getOverlays().add(markerOverlay);
        }
        map.invalidate();
    }

    private void clearOverlays() {
        if (markerOverlay != null) map.getOverlays().remove(markerOverlay);
        markerOverlay = null;
        for (Polyline line : lines) map.getOverlays().remove(line);
        lines.clear();
        map.invalidate();
    }

    private GeoPoint deviceLocation() {
        try {
            LocationManager lm =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location loc = null;
            try {
                loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {
            }
            if (loc == null) {
                try {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                } catch (Exception ignored) {
                }
            }
            if (loc == null) return null;
            return new GeoPoint(loc.getLatitude(), loc.getLongitude());
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatRate(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    private void postToUi(Runnable r) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (!alive.isAlive()) return;
            r.run();
        });
    }
}
