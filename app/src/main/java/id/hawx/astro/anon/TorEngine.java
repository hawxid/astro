package id.hawx.astro.anon;

import android.text.TextUtils;
import id.hawx.astro.engine.Apt;
import id.hawx.astro.utils.Core;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engine Tor: daemon di chroot + transparent proxy iptables (device-wide).
 *
 * Semua yang dipakai di sini TERBUKTI di perangkat lewat PoC terminal
 * tahap 1-4 (2026-11-01), bukan asumsi:
 * - tor 0.4.9.15 sudah terpasang; user debian-tor UID 104.
 * - Tulis iptables dari chroot MATI (nft: genid; legacy: -N/-I ENOENT);
 *   dari su shell Android jalan (filter + nat REDIRECT terbukti).
 * - Bootstrap 6-18 detik dengan cache consensus; exit terbukti IsTor:true.
 * - Cleanup: HAPUS ATURAN DULU, baru stop daemon (kebalikannya = REDIRECT
 *   ke port mati = blackhole).
 *
 * Jalur file persisten di /var/lib/astro-tor (BUKAN /root: /root mode 700
 * sehingga debian-tor tidak bisa masuk; /var/lib bisa dilalui semua user).
 * Datadir + dir induk di-chown ke debian-tor supaya daemon bisa menulis
 * log/pidfile; torrc tetap milik root 644 (bisa dibaca daemon).
 *
 * Aturan (Android-side, dipasang SEBELUM bootstrap 100%: fail-closed):
 * nat/OUTPUT: owner-RETURN UID 104 (trafik Tor sendiri lolos),
 * lo-RETURN (layanan lokal tidak ikut di-redirect), DNS REDIRECT ke
 * 5353, TCP REDIRECT ke 9040.
 * filter/OUTPUT (penutup bocor UDP): 127.0.0.1-ACCEPT (paket hasil
 * REDIRECT + layanan lokal), UID 104-ACCEPT (trafik Tor sendiri),
 * lalu DROP — UDP non-DNS (termasuk QUIC 443) dan ICMP ikut
 * terblokir, bukan bocor langsung. Tradeoff: NTP (UDP 123) juga
 * terblokir selama Tor aktif (sinkronisasi waktu lanjut lewat
 * NITZ/carrier; QUIC jatuh ke TCP lewat Tor). Spesifikasi aturan
 * filter ini sengaja TIDAK memakai "-o lo" supaya tidak bentrok
 * dengan aturan kill switch WG (mode eksklusif, tapi spesifikasi
 * yang berbeda membuat cleanup masing-masing tidak saling hapus).
 * ip6tables/OUTPUT (penutup bocor IPv6): lo-ACCEPT lalu DROP —
 * tanpa ini, IPv6 keluar langsung tanpa lewat Tor (celah yang sama
 * masih ada di kill switch WG, yang hanya IPv4).
 * Bila ip6tables tidak tersedia, up() GAGAL dengan pesan jelas
 * (fail-closed) daripada diam-diam membiarkan IPv6 bocor.
 * Daemon diluncurkan terpisah (setsid) lewat threadChrootCommand; status
 * dibaca dari pidfile + log (parse "Bootstrapped X% (stage)").
 */
public class TorEngine {

    public static final String P_MODE = "anon_mode";
    public static final String MODE_WG = "wg";
    public static final String MODE_TOR = "tor";

    private static final String DIR = "/var/lib/astro-tor";
    private static final String TORRC = DIR + "/torrc";
    private static final String DATADIR = DIR + "/data";
    private static final String LOG = DIR + "/tor.log";
    private static final String PIDFILE = DIR + "/tor.pid";

    private static final String TOR_USER = "debian-tor";
    private static final int TOR_UID = 104;

    private static final Pattern BOOTSTRAP =
            Pattern.compile("Bootstrapped (\\d+)% \\(([^)]+)\\)");
    private static final Pattern EXIT_IP =
            Pattern.compile("\"IP\"\\s*:\\s*\"([^\"]+)\"");

    public static class Result {
        public final boolean ok;
        public final String message;

        public Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    public enum State {
        OFF, CONNECTING, ON, BROKEN
    }

    public static class Status {
        public State state = State.OFF;
        public int percent = 0;
        public String stageTag = "";
        public String lastLog = "";
        public boolean daemonAlive = false;
        public boolean rulesActive = false;
        public boolean leakPlugActive = false;
    }

    private static ArrayList<String> run(Core core, String cmd) {
        String full = TextUtils.join("; ", Apt.env()) + "; " + cmd;
        return core.customChrootCommand(full, true);
    }

    private static String runAndroid(Core core, String cmd) {
        try {
            String out = core.executeCommand(cmd);
            return out == null ? "" : out;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String join(ArrayList<String> lines) {
        StringBuilder sb = new StringBuilder();
        if (lines != null) {
            for (String l : lines) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static boolean toolsOk(Core core) {
        return join(run(core, "command -v tor")).contains("/tor");
    }

    private static boolean androidIptablesOk(Core core) {
        return runAndroid(core, "command -v iptables").contains("/iptables");
    }

    private static boolean androidIp6tablesOk(Core core) {
        return runAndroid(core, "command -v ip6tables").contains("/ip6tables");
    }

    /** Penutup bocor Tor terpasang: filter UID 104 + DROP, dan ip6 lo + DROP. */
    private static boolean filterActive(Core core) {
        String out = runAndroid(core, "iptables -S OUTPUT");
        return out.contains("--uid-owner " + TOR_UID + " -j ACCEPT")
                && out.contains("-j DROP");
    }

    private static boolean ip6Active(Core core) {
        String out = runAndroid(core, "ip6tables -S OUTPUT");
        return out.contains("-o lo -j ACCEPT") && out.contains("-j DROP");
    }

    private static boolean leakPlugActive(Core core) {
        return filterActive(core) && ip6Active(core);
    }

    /** Daemon hidup: pidfile ada dan PID-nya masih jalan. */
    public static boolean daemonAlive(Core core) {
        String out = join(run(core,
                "pid=$(cat " + PIDFILE + " 2>/dev/null); "
                        + "[ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null && echo TOR_ALIVE"));
        return out.contains("TOR_ALIVE");
    }

    /** Aturan Tor terpasang (verifikasi live, bukan setelan). */
    public static boolean rulesActive(Core core) {
        String out = runAndroid(core, "iptables -t nat -S OUTPUT");
        return out.contains("-m owner --uid-owner " + TOR_UID + " -j RETURN")
                && out.contains("-j REDIRECT --to-ports 5353")
                && out.contains("-j REDIRECT --to-ports 9040");
    }

    /** Cabut aturan Tor: hapus nat + filter + ip6 by-spec (toleran bila tak ada). */
    public static void removeRules(Core core) {
        runAndroid(core, "iptables -t nat -D OUTPUT -m owner --uid-owner "
                + TOR_UID + " -j RETURN 2>/dev/null; true");
        runAndroid(core, "iptables -t nat -D OUTPUT -o lo -j RETURN 2>/dev/null; true");
        runAndroid(core, "iptables -t nat -D OUTPUT -p udp --dport 53 "
                + "-j REDIRECT --to-ports 5353 2>/dev/null; true");
        runAndroid(core, "iptables -t nat -D OUTPUT -p tcp "
                + "-j REDIRECT --to-ports 9040 2>/dev/null; true");
        runAndroid(core, "iptables -D OUTPUT -d 127.0.0.1 -j ACCEPT 2>/dev/null; true");
        runAndroid(core, "iptables -D OUTPUT -m owner --uid-owner "
                + TOR_UID + " -j ACCEPT 2>/dev/null; true");
        runAndroid(core, "iptables -D OUTPUT -j DROP 2>/dev/null; true");
        runAndroid(core, "ip6tables -D OUTPUT -o lo -j ACCEPT 2>/dev/null; true");
        runAndroid(core, "ip6tables -D OUTPUT -j DROP 2>/dev/null; true");
    }

    /** Pasang aturan Tor: bersih dulu, sisip nat + filter + ip6, verifikasi live. */
    public static Result applyRules(Core core) {
        if (!androidIptablesOk(core)) {
            return new Result(false, "iptables tidak tersedia");
        }
        if (!androidIp6tablesOk(core)) {
            return new Result(false,
                    "ip6tables tidak tersedia — IPv6 tidak bisa diblokir");
        }
        removeRules(core);
        // Sisip berurutan di posisi 1: hasil akhir = owner, lo, DNS, TCP.
        runAndroid(core, "iptables -t nat -I OUTPUT 1 -p tcp -j REDIRECT --to-ports 9040");
        runAndroid(core, "iptables -t nat -I OUTPUT 1 -p udp --dport 53 -j REDIRECT --to-ports 5353");
        runAndroid(core, "iptables -t nat -I OUTPUT 1 -o lo -j RETURN");
        runAndroid(core, "iptables -t nat -I OUTPUT 1 -m owner --uid-owner "
                + TOR_UID + " -j RETURN");
        // Filter: hasil akhir = 127-ACCEPT, UID-ACCEPT, DROP.
        runAndroid(core, "iptables -I OUTPUT 1 -j DROP");
        runAndroid(core, "iptables -I OUTPUT 1 -m owner --uid-owner "
                + TOR_UID + " -j ACCEPT");
        runAndroid(core, "iptables -I OUTPUT 1 -d 127.0.0.1 -j ACCEPT");
        // IPv6: hasil akhir = lo-ACCEPT, DROP.
        runAndroid(core, "ip6tables -I OUTPUT 1 -j DROP");
        runAndroid(core, "ip6tables -I OUTPUT 1 -o lo -j ACCEPT");
        if (rulesActive(core) && leakPlugActive(core)) {
            return new Result(true, "aturan aktif");
        }
        removeRules(core);
        return new Result(false, "aturan gagal diterapkan");
    }

    /** Tulis torrc (idempotent) + siapkan dir untuk debian-tor. */
    private static void ensureSetup(Core core) {
        String[] lines = new String[] {
                "DataDirectory " + DATADIR,
                "SocksPort 127.0.0.1:9050",
                "TransPort 127.0.0.1:9040",
                "DNSPort 127.0.0.1:5353",
                "AutomapHostsOnResolve 1",
                "AvoidDiskWrites 1",
                "Log notice file " + LOG,
                "PidFile " + PIDFILE,
        };
        StringBuilder cmd = new StringBuilder();
        cmd.append("mkdir -p ").append(DIR).append(" ").append(DATADIR).append("; ");
        cmd.append("printf '%s\\n'");
        for (String line : lines) {
            cmd.append(" ").append(Apt.shellQuote(line));
        }
        cmd.append(" > ").append(TORRC).append("; ");
        cmd.append("chmod 644 ").append(TORRC).append("; ");
        cmd.append("chown ").append(TOR_USER).append(":").append(TOR_USER)
                .append(" ").append(DIR).append(" ").append(DATADIR);
        run(core, cmd.toString());
    }

    /** Luncurkan daemon terpisah (setsid) sebagai debian-tor. */
    private static void startDaemon(Core core) {
        String launch = "setsid su " + TOR_USER + " -s /bin/sh -c "
                + Apt.shellQuote("tor -f " + TORRC)
                + " >/dev/null 2>&1 </dev/null &";
        String full = TextUtils.join("; ", Apt.env()) + "; " + launch;
        core.threadChrootCommand(full);
    }

    /** Hentikan daemon: kill via pidfile + sapu pola torrc (toleran). */
    private static void stopDaemon(Core core) {
        run(core, "pid=$(cat " + PIDFILE + " 2>/dev/null); "
                + "[ -n \"$pid\" ] && kill \"$pid\" 2>/dev/null; true");
        run(core, "pkill -f " + Apt.shellQuote("tor -f " + TORRC)
                + " 2>/dev/null; true");
    }

    /** Log terakhir + persen/stage bootstrap dari baris Bootstrapped. */
    private static void parseLog(Status s, String log) {
        if (log == null) return;
        String last = "";
        for (String raw : log.split("\\n")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            last = t;
            Matcher m = BOOTSTRAP.matcher(t);
            if (m.find()) {
                try {
                    s.percent = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    // Baris persen rusak: pertahankan persen sebelumnya.
                }
                s.stageTag = m.group(2);
            }
        }
        s.lastLog = last;
    }

    private static boolean listenersReady(String log) {
        return log != null
                && log.contains("Opened Socks listener")
                && log.contains("Opened DNS listener")
                && log.contains("Opened Transparent pf/netfilter listener");
    }

    public static Status status(Core core) {
        Status s = new Status();
        s.daemonAlive = daemonAlive(core);
        s.rulesActive = rulesActive(core);
        s.leakPlugActive = leakPlugActive(core);
        String log = join(run(core, "tail -n 80 " + LOG + " 2>/dev/null"));
        parseLog(s, log);
        if (!s.daemonAlive && !s.rulesActive && !s.leakPlugActive) {
            s.state = State.OFF;
        } else if (s.daemonAlive && s.rulesActive && s.leakPlugActive) {
            s.state = s.percent >= 100 ? State.ON : State.CONNECTING;
        } else {
            // BROKEN mencakup dua arah: daemon mati + aturan masih ada =
            // fail-closed (diblokir, bukan bocor); daemon hidup tapi
            // penutup bocor hilang = perlindungan tidak lengkap (UDP/IPv6
            // bisa bocor) — bukan ON.
            s.state = State.BROKEN;
        }
        return s;
    }

    /**
     * Nyalakan Tor: daemon + aturan. Kembali setelah aturan terpasang dan
     * daemon hidup — bootstrap 0-100% dilanjutkan lewat polling status
     * di fragment (bukan diblokir di sini), supaya persen terlihat live.
     * Aturan dipasang SEBELUM bootstrap selesai: fail-closed, trafik
     * menunggu sirkuit, bukan bocor langsung selama menghubungkan.
     */
    public static Result up(Core core) {
        if (!toolsOk(core)) {
            return new Result(false, "tor belum terpasang");
        }
        // Tor lain (mis. sisa PoC) yang masih jalan = konflik port yang
        // gagalnya generik ("daemon gagal jalan") — gagal cepat dengan
        // pesan jelas. pgrep diasumsikan ada (sama seperti pkill di
        // stopDaemon); bila tidak ada, cek ini lolos begitu saja.
        String other = join(run(core, "pgrep -f "
                + Apt.shellQuote("tor -f") + " 2>/dev/null"));
        if (!other.trim().isEmpty()) {
            return new Result(false,
                    "tor lain sedang jalan — matikan dulu (tor PoC?)");
        }
        // Idempotent: bersihkan sisa dari crash sebelumnya.
        removeRules(core);
        stopDaemon(core);
        ensureSetup(core);
        startDaemon(core);
        boolean alive = false;
        boolean listeners = false;
        for (int i = 0; i < 8; i++) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                return new Result(false, "start terganggu");
            }
            alive = daemonAlive(core);
            String log = join(run(core, "tail -n 100 " + LOG + " 2>/dev/null"));
            listeners = listenersReady(log);
            if (alive && listeners) break;
        }
        if (!alive) {
            stopDaemon(core);
            return new Result(false, "daemon Tor gagal jalan");
        }
        Result r = applyRules(core);
        if (!r.ok) {
            stopDaemon(core);
            return new Result(false, r.message);
        }
        return new Result(true, "menghubungkan");
    }

    /**
     * Matikan Tor: URUTAN WAJIB aturan dulu, baru daemon — stop daemon
     * dulu = REDIRECT ke port mati = blackhole sampai aturan dihapus.
     */
    public static Result down(Core core) {
        removeRules(core);
        stopDaemon(core);
        if (rulesActive(core) || leakPlugActive(core)) {
            return new Result(false, "aturan masih ada setelah dimatikan");
        }
        if (daemonAlive(core)) {
            return new Result(false, "daemon masih jalan setelah dimatikan");
        }
        return new Result(true, "mati");
    }

    /** Ekor log untuk konsol (tombol "Lihat log"). */
    public static String logTail(Core core, int n) {
        return join(run(core, "tail -n " + n + " " + LOG + " 2>/dev/null"));
    }

    /**
     * IP publik lewat Tor (hanya bila IsTor true; bila Tor mati atau
     * trafik tidak lewat Tor, null). Dipakai tombol "Lihat IP publik".
     */    public static String exitIp(Core core) {
        String out = join(run(core,
                "curl -s --max-time 20 https://check.torproject.org/api/ip"));
        if (!out.contains("\"IsTor\":true") && !out.contains("\"IsTor\": true")) {
            return null;
        }
        Matcher m = EXIT_IP.matcher(out);
        if (m.find()) return m.group(1);
        return null;
    }
}
