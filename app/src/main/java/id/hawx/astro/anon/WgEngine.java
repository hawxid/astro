package id.hawx.astro.anon;

import android.text.TextUtils;
import id.hawx.astro.engine.Apt;
import id.hawx.astro.utils.Core;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engine WireGuard: kernel wg0 lewat wireguard-tools di chroot
 * (root, satu network namespace dengan Android — jadi tunnel ini
 * device-wide, bukan cuma chroot).
 *
 * Semua perintah dijalankan dengan nolog=true: kunci privat tidak boleh
 * mendarat di log Astro. up() memverifikasi hasil dan rollback otomatis
 * (down) kalau verifikasi gagal, supaya tidak ada state setengah jalan.
 *
 * Kill switch (refinement pasca-Batch 1): rantai iptables khusus
 * ASTRO_KILL (loopback + via-tunnel + handshake UDP ke endpoint diizinkan,
 * sisanya DROP), dijump dari posisi 1 OUTPUT saat tunnel naik dan dicabut
 * saat tunnel mati. Rantai khusus = pencabutan tidak pernah menyentuh
 * aturan sistem (cukup hapus jump + flush chain). Fail-closed: bila aturan
 * gagal diterapkan, up() dibatalkan (rollback), bukan jalan tanpa proteksi.
 * Cakupan IPv4 saja di batch ini; IPv6 masih bisa lolos (limitasi dicatat).
 */
public class WgEngine {

    public static final String IFACE = "astro-wg0";
    private static final String KEY_FILE = "/tmp/astro-wg-key";
    private static final String KS_CHAIN = "ASTRO_KILL";

    /**
     * Android memakai policy routing: tabel main sering TIDAK punya rute
     * default; default tinggal di tabel per-jaringan (di HP ini 1011/1013).
     * Karena itu engine tidak pernah membaca main-table saja: rute efektif
     * dibaca dari `ip route get` (yang memperhitungkan semua rule), dan
     * default tunnel dipasang/dikembalikan di tabel itu juga.
     */
    private static final Pattern ROUTE_GET_VIA =
            Pattern.compile("via (\\S+) dev (\\S+) table (\\S+)");
    private static final Pattern ROUTE_GET_DEV =
            Pattern.compile("dev (\\S+) table (\\S+)");
    private static final Pattern TABLE_DEFAULT =
            Pattern.compile("default (?:via (\\S+) )?dev (\\S+) table (\\S+)");
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

    /** Kunci publik turunan dari private key (untuk ditempel ke server). */
    public static String pubKey(Core core, String privateKey) {
        if (privateKey == null) return null;
        String out = join(run(core, "printf '%s' " + Apt.shellQuote(privateKey.trim())
                + " | wg pubkey"));
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.length() == 44 && t.matches("[A-Za-z0-9+/]{43}=")) return t;
        }
        return null;
    }

    public static boolean iptablesOk(Core core) {
        return join(run(core, "command -v iptables")).contains("/iptables");
    }

    /** Jump kill switch sedang terpasang (verifikasi live, bukan setelan). */
    public static boolean killSwitchActive(Core core) {
        return join(run(core, "iptables -S OUTPUT")).contains("-j " + KS_CHAIN);
    }

    /** Cabut kill switch: hapus jump (diulang, toleran terhadap duplikat
     * sisa crash) lalu flush + hapus chain. Tidak menyentuh aturan lain. */
    public static void removeKillSwitch(Core core) {
        for (int i = 0; i < 3; i++) {
            run(core, "iptables -D OUTPUT -j " + KS_CHAIN + " 2>/dev/null; true");
        }
        run(core, "iptables -F " + KS_CHAIN + " 2>/dev/null; true");
        run(core, "iptables -X " + KS_CHAIN + " 2>/dev/null; true");
    }

    /**
     * Pasang kill switch: bersihkan sisa crash dulu, lalu isi chain
     * (loopback, via-tunnel, handshake UDP ke endpoint) + DROP, dan jump
     * dari posisi 1 OUTPUT. Gagal = rollback aturan + Result gagal.
     */
    public static Result applyKillSwitch(Core core, String endpointIp, String port) {
        if (!iptablesOk(core)) {
            return new Result(false, "iptables tidak tersedia");
        }
        removeKillSwitch(core);
        run(core, "iptables -N " + KS_CHAIN + " 2>/dev/null; true");
        run(core, "iptables -A " + KS_CHAIN + " -o lo -j ACCEPT");
        run(core, "iptables -A " + KS_CHAIN + " -o " + IFACE + " -j ACCEPT");
        String ep = "iptables -A " + KS_CHAIN + " -d " + Apt.shellQuote(endpointIp)
                + " -p udp";
        if (port != null && !port.isEmpty()) {
            ep += " --dport " + Apt.shellQuote(port);
        }
        run(core, ep + " -j ACCEPT");
        run(core, "iptables -A " + KS_CHAIN + " -j DROP");
        run(core, "iptables -I OUTPUT 1 -j " + KS_CHAIN);
        if (killSwitchActive(core)) {
            return new Result(true, "kill switch aktif");
        }
        removeKillSwitch(core);
        return new Result(false, "kill switch gagal diterapkan");
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
        String route = join(run(core, "ip route show table all"));
        // Tunnel aktif bila ada default lewat astro-wg0 di tabel mana pun.
        for (String raw : route.split("\\n")) {
            String t = raw.trim();
            if (t.startsWith("default dev " + IFACE + " ")
                    || t.equals("default dev " + IFACE)) {
                s.defaultDev = IFACE;
                break;
            }
        }
        return s;
    }

    /** Satu rute efektif: gw null untuk bentuk dev-only. */
    private static class Route {
        String gw;
        String dev;
        String table;
    }

    /** Parse satu baris hasil `ip route get` (baris pertama yang cocok). */
    private static Route parseRouteGet(String output) {
        if (output == null) return null;
        for (String raw : output.split("\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("local ")
                    || line.contains("RTNETLINK")) continue;
            Matcher via = ROUTE_GET_VIA.matcher(line);
            if (via.find()) {
                Route r = new Route();
                r.gw = via.group(1);
                r.dev = via.group(2);
                r.table = via.group(3);
                return r;
            }
            Matcher devOnly = ROUTE_GET_DEV.matcher(line);
            if (devOnly.find()) {
                Route r = new Route();
                r.gw = null;
                r.dev = devOnly.group(1);
                r.table = devOnly.group(2);
                return r;
            }
        }
        return null;
    }

    /**
     * Fallback: pilih satu default dari `ip route show table all`.
     * dummy0 dilewati (bukan jalur trafik nyata); yang ber-gateway menang.
     */
    private static Route pickTableDefault(String output) {
        Route devOnly = null;
        if (output == null) return null;
        for (String raw : output.split("\\n")) {
            String line = raw.trim();
            Matcher m = TABLE_DEFAULT.matcher(line);
            if (!m.find()) continue;
            if ("dummy0".equals(m.group(2))) continue;
            Route r = new Route();
            r.gw = m.group(1);
            r.dev = m.group(2);
            r.table = m.group(3);
            if (r.gw != null) return r;
            if (devOnly == null) devOnly = r;
        }
        return devOnly;
    }

    /** Rute efektif untuk endpoint, dengan fallback berlapis. */
    private static Route effectiveRoute(Core core, String endpointIp) {
        Route r = parseRouteGet(join(run(core, "ip route get " + Apt.shellQuote(endpointIp))));
        if (r != null) return r;
        r = parseRouteGet(join(run(core, "ip route get 8.8.8.8")));
        if (r != null) return r;
        return pickTableDefault(join(run(core, "ip route show table all")));
    }

    /** Qualifier tabel untuk perintah ip route ("" = main, perilaku lama). */
    private static String tableArg(String table) {
        return (table == null || table.isEmpty()) ? "" : " table " + table;
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
        // Pesan jelas bila tools belum terpasang, bukan gagal samar di verifikasi.
        if (!toolsOk(core)) {
            return new Result(false, "wireguard-tools belum terpasang");
        }
        // Idempotent: bersihkan sisa dari crash sebelumnya.
        run(core, "ip link del dev " + IFACE + " 2>/dev/null; true");

        String host = cfg.endpointHost();
        String port = cfg.endpointPort();
        String endpointIp = resolveEndpoint(core, host);
        if (endpointIp == null) {
            return new Result(false, "endpoint tidak bisa di-resolve: " + host);
        }
        // Rute efektif dari `ip route get`: tabel main HP ini tidak punya
        // default (policy routing Android), jadi main-table tidak dibaca.
        Route rt = effectiveRoute(core, endpointIp);
        if (rt == null) {
            return new Result(false, "tidak ada rute default aktif");
        }
        String gw = rt.gw;
        String dev = rt.dev;
        String table = rt.table;
        core.putString(WgConfig.P_RT_GW, gw == null ? "" : gw);
        core.putString(WgConfig.P_RT_DEV, dev);
        core.putString(WgConfig.P_RT_TABLE, table == null ? "" : table);
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
        // Pengecualian endpoint DAN default tunnel dipasang di tabel yang
        // sama dengan rute efektif (bukan main): di tabel itulah kernel
        // benar-benar me-routing trafik di HP ini.
        String tArg = tableArg(table);
        if (gw != null && !gw.isEmpty()) {
            run(core, "ip route add " + Apt.shellQuote(endpointIp)
                    + " via " + Apt.shellQuote(gw)
                    + " dev " + Apt.shellQuote(dev) + tArg);
        } else {
            run(core, "ip route add " + Apt.shellQuote(endpointIp)
                    + " dev " + Apt.shellQuote(dev) + tArg);
        }
        run(core, "ip route replace default dev " + IFACE + tArg);

        Status s = status(core);
        if (!s.up || !IFACE.equals(s.defaultDev)) {
            down(core);
            return new Result(false, "verifikasi gagal, rute dipulihkan");
        }
        // Kill switch dipasang SETELAH tunnel terverifikasi, dan gagalnya
        // fail-closed: tidak ada tunnel tanpa proteksi saat sakelar menyala.
        if (cfg.killswitch) {
            Result ks = applyKillSwitch(core, endpointIp, port);
            if (!ks.ok) {
                down(core);
                return new Result(false, ks.message);
            }
        }
        return new Result(true, "aktif");
    }

    public static Result down(Core core) {
        // Cabut kill switch dulu supaya konektivitas langsung pulih saat
        // toggle dimatikan (bukan sesudah interface dihapus).
        removeKillSwitch(core);
        run(core, "ip link del dev " + IFACE + " 2>/dev/null; true");
        String gw = core.getString(WgConfig.P_RT_GW);
        String dev = core.getString(WgConfig.P_RT_DEV);
        String table = core.getString(WgConfig.P_RT_TABLE);
        String endpointIp = core.getString(WgConfig.P_RT_ENDPOINT_IP);
        // Restore di tabel yang sama dengan saat up(); tabel kosong berarti
        // state lama (main-table) — qualifier kosong = perilaku lama.
        String tArg = tableArg(table);
        if (dev != null && !dev.isEmpty()) {
            if (gw != null && !gw.isEmpty()) {
                run(core, "ip route replace default via " + Apt.shellQuote(gw)
                        + " dev " + Apt.shellQuote(dev) + tArg + " 2>/dev/null; true");
            } else {
                run(core, "ip route replace default dev " + Apt.shellQuote(dev)
                        + tArg + " 2>/dev/null; true");
            }
        }
        if (endpointIp != null && !endpointIp.isEmpty()
                && dev != null && !dev.isEmpty()) {
            if (gw != null && !gw.isEmpty()) {
                run(core, "ip route del " + Apt.shellQuote(endpointIp)
                        + " via " + Apt.shellQuote(gw)
                        + " dev " + Apt.shellQuote(dev) + tArg + " 2>/dev/null; true");
            } else {
                run(core, "ip route del " + Apt.shellQuote(endpointIp)
                        + " dev " + Apt.shellQuote(dev) + tArg + " 2>/dev/null; true");
            }
        }
        core.putString(WgConfig.P_RT_GW, "");
        core.putString(WgConfig.P_RT_DEV, "");
        core.putString(WgConfig.P_RT_TABLE, "");
        core.putString(WgConfig.P_RT_ENDPOINT_IP, "");
        Status s = status(core);
        if (s.up) {
            return new Result(false, "interface masih ada setelah dimatikan");
        }
        return new Result(true, "mati");
    }
}
