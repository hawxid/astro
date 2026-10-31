package id.hawx.astro.anon;

import id.hawx.astro.utils.Core;

/**
 * Konfigurasi WireGuard satu peer, disimpan di EncryptedSharedPreferences
 * milik Core (kunci privat tidak pernah ditulis ke log atau file biasa).
 * Batch 1: satu peer saja, cukup untuk toggle mode anonim pertama.
 */
public class WgConfig {

    public static final String P_PRIVATE = "anon_wg_private";
    public static final String P_ADDRESS = "anon_wg_address";
    public static final String P_PEER = "anon_wg_peer";
    public static final String P_ENDPOINT = "anon_wg_endpoint";
    public static final String P_ALLOWED = "anon_wg_allowed";
    public static final String P_KEEPALIVE = "anon_wg_keepalive";
    public static final String P_NAME = "anon_wg_name";
    public static final String P_KILL = "anon_wg_killswitch";

    /** State runtime (bukan config): dipakai down() untuk memulihkan rute. */
    public static final String P_RT_GW = "anon_wg_rt_gw";
    public static final String P_RT_DEV = "anon_wg_rt_dev";
    public static final String P_RT_TABLE = "anon_wg_rt_table";
    public static final String P_RT_ENDPOINT_IP = "anon_wg_rt_endpoint_ip";

    public String name = "";
    public String privateKey = "";
    public String address = "";
    public String peerPublicKey = "";
    public String endpoint = "";
    public String allowedIps = "0.0.0.0/0, ::/0";
    public int keepalive = 25;
    /** Kill switch default MENYALA (absen = true): proteksi adalah default,
     * mematikannya harus eksplisit lewat sakelar. */
    public boolean killswitch = true;

    public static WgConfig load(Core core) {
        WgConfig c = new WgConfig();
        c.name = core.getString(P_NAME);
        c.privateKey = core.getString(P_PRIVATE);
        c.address = core.getString(P_ADDRESS);
        c.peerPublicKey = core.getString(P_PEER);
        c.endpoint = core.getString(P_ENDPOINT);
        String allowed = core.getString(P_ALLOWED);
        if (allowed != null && !allowed.trim().isEmpty()) c.allowedIps = allowed;
        String keep = core.getString(P_KEEPALIVE);
        try {
            if (keep != null && !keep.trim().isEmpty()) c.keepalive = Integer.parseInt(keep.trim());
        } catch (NumberFormatException ignored) {
            c.keepalive = 25;
        }
        c.killswitch = !core.contains(P_KILL) || core.getBoolean(P_KILL);
        return c;
    }

    public void save(Core core) {
        core.putString(P_NAME, name == null ? "" : name);
        core.putString(P_PRIVATE, privateKey == null ? "" : privateKey);
        core.putString(P_ADDRESS, address == null ? "" : address);
        core.putString(P_PEER, peerPublicKey == null ? "" : peerPublicKey);
        core.putString(P_ENDPOINT, endpoint == null ? "" : endpoint);
        core.putString(P_ALLOWED, allowedIps == null ? "" : allowedIps);
        core.putString(P_KEEPALIVE, String.valueOf(keepalive));
        core.putBoolean(P_KILL, killswitch);
    }

    /** Validasi keras: kunci WireGuard selalu 44 karakter base64. */
    public boolean isComplete() {
        return notEmpty(privateKey) && privateKey.trim().length() == 44
                && notEmpty(peerPublicKey) && peerPublicKey.trim().length() == 44
                && notEmpty(address) && address.contains("/")
                && notEmpty(endpoint) && endpoint.contains(":");
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Host dari endpoint "host:port" (port dipisah di titik dua terakhir). */
    public String endpointHost() {
        int i = endpoint.lastIndexOf(':');
        return i > 0 ? endpoint.substring(0, i) : endpoint;
    }

    public String endpointPort() {
        int i = endpoint.lastIndexOf(':');
        return i > 0 && i + 1 < endpoint.length() ? endpoint.substring(i + 1) : "";
    }

    /**
     * Parser .conf minimal: hanya [Interface] PrivateKey/Address dan
     * [Peer] PublicKey/Endpoint/AllowedIPs/PersistentKeepalive. Direktif
     * lain (DNS, PreUp, PostUp, ...) sengaja diabaikan di Batch 1.
     */
    public static WgConfig parseConf(String text) {
        WgConfig c = new WgConfig();
        if (text == null) return c;
        // BOM di awal salinan file bisa bikin baris "[Interface]" pertama
        // tidak dikenali sebagai section, lalu seluruh parse gagal diam-diam.
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String section = "";
        for (String raw : text.split("\\n")) {
            String line = raw.trim();
            if (line.startsWith("#") || line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if (section.equalsIgnoreCase("Interface")) {
                if (key.equalsIgnoreCase("PrivateKey")) c.privateKey = val;
                else if (key.equalsIgnoreCase("Address")) c.address = val;
            } else if (section.equalsIgnoreCase("Peer")) {
                if (key.equalsIgnoreCase("PublicKey")) c.peerPublicKey = val;
                else if (key.equalsIgnoreCase("Endpoint")) c.endpoint = val;
                else if (key.equalsIgnoreCase("AllowedIPs")) c.allowedIps = val;
                else if (key.equalsIgnoreCase("PersistentKeepalive")) {
                    try {
                        c.keepalive = Integer.parseInt(val);
                    } catch (NumberFormatException ignored) {
                        // pakai default
                    }
                }
            }
        }
        return c;
    }
}
