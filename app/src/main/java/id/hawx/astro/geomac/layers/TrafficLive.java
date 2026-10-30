package id.hawx.astro.geomac.layers;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.TrafficStats;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import id.hawx.astro.R;

import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
 * Reads the kernel socket tables (/proc/net/tcp, tcp6, udp, udp6) via su
 * (root). Direct reads from the app UID do NOT work on modern Android:
 * since Android P, /proc/net is locked down for apps with targetSdk >= 28,
 * so other apps' sockets are hidden and the list came back silently empty.
 * No VPN, no packet capture. While ON, a poll every 2 s rebuilds the
 * connection list, resolves destination GeoIP via ip-api.com (free, no key,
 * HTTP-only free tier; results cached per IP in RAM), and draws destination
 * markers plus lines from the device's last known location when one exists.
 * Polling stops completely on OFF / pause / destroy, mirroring AtlasLayers.
 * If the su table read fails, the card shows a failure status instead of
 * the silent empty text.
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

    // Reputation coloring (idea #4): exactly ONE free, no-key, plain-text
    // IP blocklist — abuse.ch Feodo Tracker botnet-C2 IPs, one per line.
    // Matched by EXACT destination IP only, never by ASN (ASNs mix
    // legitimate and bad traffic). Fetched at most once per TrafficLive
    // instance (i.e. once per Atlas open while Traffic is ON), kept in RAM
    // only; a failed fetch silently leaves the default colors in place.
    private static final String THREAT_URL =
            "https://feodotracker.abuse.ch/downloads/ipblocklist.txt";
    private static final String COLOR_NORMAL = "#FF6D00";
    private static final String COLOR_FLAGGED = "#D32F2F";

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
    private boolean lastReadFailed;
    private boolean lastConnsEmpty = true;

    // Foldable rows list (independent of Traffic ON/OFF: folding hides only
    // the rows; polling and map markers/lines keep running while ON).
    private View rowsScroll;
    private MaterialButton foldBtn;
    private boolean rowsFolded;

    private final ArrayList<Conn> markerConns = new ArrayList<>();
    private volatile Set<String> threatIps = new HashSet<>();
    private boolean threatFetchAttempted;

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
        if (card != null) {
            rowsScroll = card.findViewById(R.id.geomac_traffic_rows_scroll);
            foldBtn = card.findViewById(R.id.geomac_traffic_fold_btn);
            if (foldBtn != null) {
                foldBtn.setOnClickListener(v -> {
                    rowsFolded = !rowsFolded;
                    foldBtn.setRotation(rowsFolded ? 180f : 0f);
                    refreshRowsVisibility();
                });
            }
        }
    }

    private void refreshRowsVisibility() {
        if (rowsFolded) {
            if (rowsScroll != null) rowsScroll.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
            return;
        }
        if (rowsScroll != null) rowsScroll.setVisibility(View.VISIBLE);
        if (lastReadFailed) {
            emptyView.setText(R.string.geomac_traffic_failed);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setText(R.string.geomac_traffic_empty);
            emptyView.setVisibility(lastConnsEmpty ? View.VISIBLE : View.GONE);
        }
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
        if (!threatFetchAttempted) {
            threatFetchAttempted = true;
            new Thread(this::fetchThreatList, "atlas-threatlist").start();
        }
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

    /** One-shot reputation-list fetch; silent default colors on any failure. */
    private void fetchThreatList() {
        try {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            okhttp3.Request req = new okhttp3.Request.Builder().url(THREAT_URL).build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return;
                HashSet<String> set = new HashSet<>();
                for (String raw : resp.body().string().split("\n")) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // Plain IPs only: IPv4 dotted quads, or IPv6 (colon form).
                    if (line.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                            || (line.contains(":") && line.matches("[0-9a-fA-F:]+"))) {
                        set.add(line);
                    }
                }
                threatIps = set;
            }
        } catch (Exception ignored) {
            // Silent default: no coloring change, traffic list unaffected.
        }
    }

    private List<Conn> collect() {
        ArrayList<Conn> out = new ArrayList<>();
        // One su call per poll; the app-UID reads are NOT retried as a
        // fallback — on modern Android they hide other apps' sockets and
        // would silently report an empty list again.
        lastReadFailed = !readTablesViaSu(out);
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

    /**
     * Reads all 4 socket tables in ONE su call, with section markers.
     * Returns false when su is unavailable, denied, or exits non-zero, or
     * when no section marker arrives (read did not actually happen).
     * A successful read with zero connections returns true with an empty
     * list — that is the genuine-empty case, distinct from failure.
     */
    private boolean readTablesViaSu(List<Conn> out) {
        final String script = "echo __ATLAS_TCP__; cat /proc/net/tcp;"
                + " echo __ATLAS_TCP6__; cat /proc/net/tcp6;"
                + " echo __ATLAS_UDP__; cat /proc/net/udp;"
                + " echo __ATLAS_UDP6__; cat /proc/net/udp6";
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String proto = null;
            boolean sawMarker = false;
            String line;
            while ((line = br.readLine()) != null && out.size() < MAX_RAW) {
                String t = line.trim();
                if (t.equals("__ATLAS_TCP__")) {
                    proto = "TCP";
                    sawMarker = true;
                    continue;
                }
                if (t.equals("__ATLAS_TCP6__")) {
                    proto = "TCP";
                    sawMarker = true;
                    continue;
                }
                if (t.equals("__ATLAS_UDP__")) {
                    proto = "UDP";
                    sawMarker = true;
                    continue;
                }
                if (t.equals("__ATLAS_UDP6__")) {
                    proto = "UDP";
                    sawMarker = true;
                    continue;
                }
                if (proto == null) continue; // noise before the first marker
                if (t.startsWith("sl ")) continue; // per-table header line
                parseTableLine(t, proto, out);
            }
            br.close();
            int exit = p.waitFor();
            return sawMarker && exit == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private void parseTableLine(String line, String proto, List<Conn> out) {
        String[] f = line.trim().split("\\s+");
        if (f.length < 8) return;
        String remote = f[2];
        int colon = remote.lastIndexOf(':');
        if (colon < 0) return;
        String ip = parseIp(remote.substring(0, colon));
        if (ip == null || isZeroIp(ip)) return; // no remote peer
        int port;
        int uid;
        try {
            port = Integer.parseInt(remote.substring(colon + 1), 16);
            uid = Integer.parseInt(f[7]);
        } catch (NumberFormatException e) {
            return;
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
        // Read failure is NOT an empty list: say so instead of the
        // silent empty text that hid the /proc/net lockdown. Folding hides
        // the rows area but never stops polling or map drawing.
        lastConnsEmpty = conns.isEmpty();
        refreshRowsVisibility();
        if (markerOverlay != null) map.getOverlays().remove(markerOverlay);
        markerOverlay = null;
        for (Polyline line : lines) map.getOverlays().remove(line);
        lines.clear();
        GeoPoint self = deviceLocation();
        ArrayList<OverlayItem> items = new ArrayList<>();
        markerConns.clear();
        Set<String> threats = threatIps;
        for (Conn c : conns) {
            if (c.geo == null) continue;
            boolean flagged = threats != null && threats.contains(c.remoteIp);
            String color = flagged ? COLOR_FLAGGED : COLOR_NORMAL;
            GeoPoint dest = new GeoPoint(c.geo.lat, c.geo.lon);
            String title = c.label + " → " + c.geo.city + ", " + c.geo.country;
            String snippet = c.remoteIp + ":" + c.remotePort;
            OverlayItem item = new OverlayItem(title, snippet, dest);
            item.setMarker(LayerMarkers.dot(context, Color.parseColor(color), 12));
            item.setMarkerHotspot(OverlayItem.HotspotPlace.CENTER);
            items.add(item);
            markerConns.add(c);
            if (self != null) {
                Polyline line = new Polyline();
                ArrayList<GeoPoint> pts = new ArrayList<>();
                pts.add(self);
                pts.add(dest);
                line.setPoints(pts);
                line.setColor(Color.parseColor(color));
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
                            if (index >= 0 && index < markerConns.size()) {
                                showTrafficDetail(markerConns.get(index));
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

    /** LEVEL 1: full connection facts for one destination marker. */
    private void showTrafficDetail(final Conn c) {
        final Dialog d = new Dialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(context);
        title.setTextSize(15f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setText(c.label);
        root.addView(title);
        // Created early so the IP row's tap handler can use it; it is added
        // to the layout further below, after the flagged note.
        final TextView whoisResult = new TextView(context);
        whoisResult.setTextSize(13f);
        whoisResult.setPadding(0, 8, 0, 4);
        whoisResult.setVisibility(View.GONE);
        // The IP row is the entry point to the IP actions: link-styled
        // (underline + blue) with a tap hint, so the tap target is visible.
        TextView ipRow = new TextView(context);
        ipRow.setTextSize(13f);
        ipRow.setPadding(0, 4, 0, 4);
        ipRow.setText(c.remoteIp + ":" + c.remotePort);
        ipRow.setTextColor(Color.parseColor("#1565C0"));
        ipRow.setPaintFlags(ipRow.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        ipRow.setOnClickListener(v -> {
            if (c.isPrivate) {
                // Never fetch for private/LAN IPs: show the local note
                // instead of opening the action area.
                whoisResult.setVisibility(View.VISIBLE);
                whoisResult.setText(R.string.geomac_traffic_local_no_whois);
            } else {
                showIpActions(c, d, whoisResult);
            }
        });
        root.addView(ipRow);
        TextView ipHint = new TextView(context);
        ipHint.setTextSize(11f);
        ipHint.setPadding(0, 0, 0, 4);
        ipHint.setText(R.string.geomac_traffic_ip_hint);
        root.addView(ipHint);
        addDetailLine(root, c.proto + " · " + c.state);
        if (c.txRate >= 0 && c.rxRate >= 0) {
            addDetailLine(root, "↑ " + formatRate(c.txRate)
                    + "  ↓ " + formatRate(c.rxRate));
        }
        if (c.geo != null) {
            String loc = c.geo.city;
            if (!c.geo.country.isEmpty()) {
                loc = loc.isEmpty() ? c.geo.country : loc + ", " + c.geo.country;
            }
            if (!c.geo.isp.isEmpty()) {
                loc = loc.isEmpty() ? c.geo.isp : loc + " · " + c.geo.isp;
            }
            if (!loc.isEmpty()) addDetailLine(root, loc);
        } else if (c.isPrivate) {
            addDetailLine(root, "LAN");
        }
        boolean flagged = threatIps != null && threatIps.contains(c.remoteIp);
        if (flagged) {
            TextView flag = new TextView(context);
            flag.setTextSize(13f);
            flag.setPadding(0, 4, 0, 4);
            flag.setTextColor(Color.parseColor(COLOR_FLAGGED));
            flag.setText(R.string.geomac_traffic_flagged);
            root.addView(flag);
        }
        root.addView(whoisResult);
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(12 * density);
        btnRow.setLayoutParams(rowLp);
        Button close = new Button(context);
        close.setText(R.string.geomac_streetview_close);
        close.setOnClickListener(v -> d.dismiss());
        btnRow.addView(close);
        root.addView(btnRow);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    /**
     * One entry in the IP-action area. To add a future IP action, append
     * one entry to the list in showIpActions — no other tap plumbing
     * changes. Title is what the menu shows; run does the work.
     */
    private static class IpAction {
        final String title;
        final Runnable run;

        IpAction(String title, Runnable run) {
            this.title = title;
            this.run = run;
        }
    }

    /**
     * IP-ACTION AREA: opened by tapping the IP row in the level-1 dialog.
     * A second dialog listing the actions for this IP (AlertDialog.Builder
     * with setItems — the standard single-choice list, already used across
     * this app; tapping an entry dismisses the menu, then runs the entry).
     * Currently exactly one entry: Whois, which runs the existing level-2
     * logic unchanged in the level-1 dialog.
     */
    private void showIpActions(final Conn c, final Dialog detailDialog,
                               final TextView whoisResult) {
        final List<IpAction> actions = new ArrayList<>();
        actions.add(new IpAction(
                context.getString(R.string.geomac_traffic_whois_action), () -> {
                    // LEVEL 2 (on demand only, never during polling):
                    // identity verification for this IP via ipwho.is.
                    whoisResult.setVisibility(View.VISIBLE);
                    whoisResult.setText(R.string.geomac_traffic_whois_checking);
                    fetchWhois(c.remoteIp, detailDialog, whoisResult);
                }));
        String[] titles = new String[actions.size()];
        for (int i = 0; i < actions.size(); i++) titles[i] = actions.get(i).title;
        new android.app.AlertDialog.Builder(context)
                .setTitle(c.remoteIp)
                .setItems(titles, (dialog, which) -> actions.get(which).run.run())
                .show();
    }

    private void addDetailLine(LinearLayout root, String text) {
        TextView tv = new TextView(context);
        tv.setTextSize(13f);
        tv.setPadding(0, 4, 0, 4);
        tv.setText(text);
        root.addView(tv);
    }

    private void fetchWhois(final String ip, final Dialog d, final TextView out) {
        new Thread(() -> {
            String result = null;
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url("https://ipwho.is/" + ip).build();
                try (okhttp3.Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONObject o = new JSONObject(resp.body().string());
                        if (o.optBoolean("success", false)) {
                            JSONObject conn = o.optJSONObject("connection");
                            StringBuilder sb = new StringBuilder();
                            if (conn != null) {
                                Object asnObj = conn.opt("asn");
                                if (asnObj != null) {
                                    String asn = String.valueOf(asnObj);
                                    if (!asn.startsWith("AS")) asn = "AS" + asn;
                                    sb.append("ASN: ").append(asn).append("\n");
                                }
                                String org = conn.optString("org", "");
                                if (!org.isEmpty()) {
                                    sb.append("Organisasi: ").append(org).append("\n");
                                }
                                String isp = conn.optString("isp", "");
                                if (!isp.isEmpty()) {
                                    sb.append("ISP: ").append(isp).append("\n");
                                }
                            }
                            String country = o.optString("country", "");
                            if (!country.isEmpty()) {
                                sb.append("Negara: ").append(country).append("\n");
                            }
                            String city = o.optString("city", "");
                            if (!city.isEmpty()) {
                                sb.append("Kota: ").append(city);
                            }
                            String text = sb.toString().trim();
                            if (!text.isEmpty()) result = text;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            final String finalResult = result;
            postToUi(() -> {
                if (!d.isShowing()) return;
                if (finalResult == null) {
                    out.setText(R.string.geomac_traffic_whois_failed);
                } else {
                    out.setText(finalResult);
                }
            });
        }, "atlas-whois").start();
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
