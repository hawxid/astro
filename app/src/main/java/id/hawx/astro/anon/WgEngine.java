package id.hawx.astro.anon;

import android.text.TextUtils;
import id.hawx.astro.engine.Apt;
import id.hawx.astro.utils.Core;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engine WireGuard Batch 1: kernel wg0 lewat wireguard-tools di chroot
 * (root, satu network namespace dengan Android — jadi tunnel ini
 * device-wide, bukan cuma chroot).
 *
 * Semua perintah dijalankan dengan nolog=true: kunci privat tidak boleh
 * mendarat di log Astro. up() memverifikasi hasil dan rollback otomatis
 * (down) kalau verifikasi gagal, supaya tidak ada state setengah jalan.
 * Kill switch BELUM diimplementasikan di Batch 1 (tidak ada toggle palsu).
 */
public class WgEngine {

    public static final String IFACE = "astro-wg0";
    private static final String KEY_FILE = "/tmp/astro-wg-key";

    private static final Pattern DEFAULT_ROUTE =
            Pattern.compile("default via (\\S+) dev (\\S+)");
    private static final Pattern DEV_IN_ROUTE =
            Pattern.compile("dev (\\S+)");
    private static final Pattern IPV4 =
            Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    public static class Result {
        public final boolean ok;
        public final String message;

        public Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    public static class Status {
        public boolean up;
        public String handshake = "";
        public String defaultDev = "";
    }

    private static ArrayList<String> run(Core core, String cmd) {
        String full = TextUtils.join("; ", Apt.env()) + "; " + cmd;
        return core.customChrootCommand(full, true);
    }

    private static String join(ArrayList<String> lines) {
        StringBuilder sb = new StringBuilder();
        if (lines != null) {
            for (String l : lines) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static boolean toolsOk(Core core) {
        return join(run(core, "command -v wg")).contains("/wg");
    }

    /** Kunci baru dari `wg genkey`, null kalau gagal. */
    public static String genKey(Core core) {
        for (String l : run(core, "wg genkey")) {
            String t = l.trim();
            if (t.length() == 44 && t.matches("[A-Za-z0-9+/]{43}=")) return t;
        }
        return null;
    }

    public static Status status(Core core) {
        Status s = new Status();
        String show = join(run(core, "wg show " + IFACE));
        s.up = show.contains("interface: " + IFACE);
        for (String l : show.split("\\n")) {
            String t = l.trim();
            if (t.startsWith("latest handshake:")) {
                s.handshake = t.substring("latest handshake:".length()).trim();
            }
        }
        String route = join(run(core, "ip route show default"));
        Matcher m = DEV_IN_ROUTE.matcher(route);
        if (m.find()) s.defaultDev = m.group(1);
        return s;
    }

    private static String resolveEndpoint(Core core, String host) {
        if (host == null || host.isEmpty()) return null;
        if (IPV4.matcher(host).matches() || host.contains(":")) return host;
        String out = join(run(core, "getent hosts " + Apt.shellQuote(host)));
        for (String l : out.split("\\n")) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+");
            if (parts.length > 0 && !parts[0].isEmpty()) return parts[0];
        }
        return null;
    }

    public static Result up(Core core, WgConfig cfg) {
        if (!cfg.isComplete()) {
            return new Result(false, "config belum lengkap");
        }
        // Idempotent: bersihkan sisa dari crash sebelumnya.
        run(core, "ip link del dev " + IFACE + " 2>/dev/null; true");

        String host = cfg.endpointHost();
        String port = cfg.endpointPort();
        String endpointIp = resolveEndpoint(core, host);
        if (endpointIp == null) {
            return new Result(false, "endpoint tidak bisa di-resolve: " + host);
        }
        String routeOut = join(run(core, "ip route show default"));
        Matcher rm = DEFAULT_ROUTE.matcher(routeOut);
        if (!rm.find()) {
            return new Result(false, "tidak ada rute default aktif");
        }
        String gw = rm.group(1);
        String dev = rm.group(2);
        core.putString(WgConfig.P_RT_GW, gw);
        core.putString(WgConfig.P_RT_DEV, dev);
        core.putString(WgConfig.P_RT_ENDPOINT_IP, endpointIp);

        String key = cfg.privateKey.trim();
        run(core, "umask 077; printf '%s' " + Apt.shellQuote(key)
                + " > " + KEY_FILE);
        String set = "ip link add dev " + IFACE + " type wireguard; "
                + "ip link set " + IFACE + " up; "
                + "wg set " + IFACE + " private-key " + KEY_FILE
                + " peer " + Apt.shellQuote(cfg.peerPublicKey.trim())
                + " allowed-ips " + Apt.shellQuote(cfg.allowedIps.trim())
                + " endpoint " + Apt.shellQuote(endpointIp + ":" + port)
                + " persistent-keepalive " + cfg.keepalive
                + "; rm -f " + KEY_FILE;
        run(core, set);
        run(core, "ip addr add " + Apt.shellQuote(cfg.address.trim())
                + " dev " + IFACE);
        run(core, "ip route add " + Apt.shellQuote(endpointIp)
                + " via " + Apt.shellQuote(gw)
                + " dev " + Apt.shellQuote(dev));
        run(core, "ip route replace default dev " + IFACE);

        Status s = status(core);
        if (!s.up || !IFACE.equals(s.defaultDev)) {
            down(core);
            return new Result(false, "verifikasi gagal, rute dipulihkan");
        }
        return new Result(true, "aktif");
    }

    public static Result down(Core core) {
        run(core, "ip link del dev " + IFACE + " 2>/dev/null; true");
        String gw = core.getString(WgConfig.P_RT_GW);
        String dev = core.getString(WgConfig.P_RT_DEV);
        String endpointIp = core.getString(WgConfig.P_RT_ENDPOINT_IP);
        if (gw != null && !gw.isEmpty() && dev != null && !dev.isEmpty()) {
            run(core, "ip route replace default via " + Apt.shellQuote(gw)
                    + " dev " + Apt.shellQuote(dev) + " 2>/dev/null; true");
        }
        if (endpointIp != null && !endpointIp.isEmpty()
                && gw != null && !gw.isEmpty()
                && dev != null && !dev.isEmpty()) {
            run(core, "ip route del " + Apt.shellQuote(endpointIp)
                    + " via " + Apt.shellQuote(gw)
                    + " dev " + Apt.shellQuote(dev) + " 2>/dev/null; true");
        }
        core.putString(WgConfig.P_RT_GW, "");
        core.putString(WgConfig.P_RT_DEV, "");
        core.putString(WgConfig.P_RT_ENDPOINT_IP, "");
        Status s = status(core);
        if (s.up) {
            return new Result(false, "interface masih ada setelah dimatikan");
        }
        return new Result(true, "mati");
    }
}
