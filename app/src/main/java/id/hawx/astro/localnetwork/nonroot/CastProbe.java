package id.hawx.astro.localnetwork.nonroot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class CastProbe {

    private CastProbe() {
    }

    private static final int TIMEOUT_MS = 2500;
    private static final int MAX_BODY = 128 * 1024;
    private static final int READ_CHUNK = 4096;
    private static final int MAX_THREADS = 8;
    private static final long POOL_WAIT_S = 20L;
    private static final String PATH = "/setup/eureka_info?options=detail";

    public static Node.Cast probe(String ip) {
        if (ip == null || ip.trim().isEmpty()) return null;
        String body = fetch(ip.trim());
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject dev = root.optJSONObject("device_info");

            String name = firstNonEmpty(
                    str(root, "name"), str(dev, "name"),
                    str(root, "device_name"), str(dev, "device_name"),
                    str(root, "ssdp_udn"), str(dev, "ssdp_udn"));
            String model = firstNonEmpty(str(root, "model_name"), str(dev, "model_name"));
            String build = firstNonEmpty(
                    str(root, "build_version"), str(root, "cast_build_revision"),
                    str(dev, "build_version"), str(dev, "cast_build_revision"));
            String ssid = firstNonEmpty(str(root, "ssid"), str(dev, "ssid"));
            String mac = Node.normalizeMac(firstNonEmpty(
                    str(root, "mac_address"), str(dev, "mac_address")));

            if (name.isEmpty() && model.isEmpty() && build.isEmpty() && ssid.isEmpty() && mac == null) {
                return null;
            }

            Node.Cast cast = new Node.Cast();
            cast.name = name;
            cast.model = model;
            cast.build = build;
            cast.ssid = ssid;
            if (mac != null) cast.mac = mac;
            return cast;
        } catch (Exception e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Map<String, Node.Cast> probeAll(Collection<String> ips, int threads) {
        final Map<String, Node.Cast> results = new ConcurrentHashMap<>();
        if (ips == null || ips.isEmpty()) return results;

        int size = Math.max(1, Math.min(threads, MAX_THREADS));
        ExecutorService pool = null;
        try {
            pool = Executors.newFixedThreadPool(size);
            for (String raw : ips) {
                if (raw == null || raw.trim().isEmpty()) continue;
                final String target = raw.trim();
                try {
                    pool.execute(() -> {
                        try {
                            Node.Cast cast = probe(target);
                            if (cast != null) results.put(target, cast);
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        if (pool != null) {
            try {
                pool.shutdown();
                pool.awaitTermination(POOL_WAIT_S, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            }
            try {
                pool.shutdownNow();
            } catch (Exception ignored) {
            }
        }
        return results;
    }

    // Hardening 2026-10-25: dulunya HttpURLConnection — pindah ke
    // soket mentah supaya network-security-config (cleartext hanya
    // loopback) tidak membunuh pemindai Chromecast LAN ini. Perilaku
    // dipertahankan persis: GET yang sama, timeout sama, hanya status
    // 200 yang dipakai, body dibatasi MAX_BODY, gagal -> null, tanpa
    // mengikuti redirect. Soket mentah tidak tunduk pada
    // network-security-config (yang hanya mengatur stack HTTP
    // platform); IP target arbitrary hasil scan LAN memang tidak bisa
    // ditulis sebagai domain di config.
    private static String fetch(String ip) {
        Socket socket = null;
        InputStream in = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, ScanConfig.CAST_PORT), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            String req = "GET " + PATH + " HTTP/1.1\r\n"
                    + "Host: " + ip + ":" + ScanConfig.CAST_PORT + "\r\n"
                    + "User-Agent: Astro\r\n"
                    + "Accept: application/json\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();
            in = new java.io.BufferedInputStream(socket.getInputStream());
            String status = readLine(in);
            if (status == null) return null;
            String[] parts = status.split(" ", 3);
            if (parts.length < 2 || !"200".equals(parts[1])) return null;
            boolean chunked = false;
            String header;
            while ((header = readLine(in)) != null) {
                if (header.isEmpty()) break;
                String lower = header.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("transfer-encoding:")
                        && lower.contains("chunked")) {
                    chunked = true;
                }
            }
            if (header == null) return null;
            ByteArrayOutputStream sink = new ByteArrayOutputStream(READ_CHUNK);
            byte[] chunk = new byte[READ_CHUNK];
            int total = 0;
            if (chunked) {
                while (total < MAX_BODY) {
                    String sizeLine = readLine(in);
                    if (sizeLine == null) break;
                    int semi = sizeLine.indexOf(';');
                    String sizeStr = (semi >= 0
                            ? sizeLine.substring(0, semi) : sizeLine).trim();
                    int size;
                    try {
                        size = Integer.parseInt(sizeStr, 16);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    if (size == 0) break;
                    int remaining = size;
                    while (remaining > 0) {
                        int want = Math.min(chunk.length,
                                Math.min(remaining, MAX_BODY - total));
                        if (want <= 0) break;
                        int read = in.read(chunk, 0, want);
                        if (read < 0) break;
                        if (read == 0) continue;
                        sink.write(chunk, 0, read);
                        total += read;
                        remaining -= read;
                    }
                    while (remaining > 0) {
                        long skipped = in.skip(remaining);
                        if (skipped <= 0) break;
                        remaining -= skipped;
                    }
                    readLine(in);
                    if (total >= MAX_BODY) break;
                }
            } else {
                while (total < MAX_BODY) {
                    int want = Math.min(chunk.length, MAX_BODY - total);
                    int read = in.read(chunk, 0, want);
                    if (read < 0) break;
                    if (read == 0) continue;
                    sink.write(chunk, 0, read);
                    total += read;
                }
            }
            if (sink.size() == 0) return null;
            return new String(sink.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // Baca satu baris CRLF/LF dari stream (tanpa BufferedReader
    // terpisah supaya body tetap terbaca dari stream yang sama).
    // Mengembalikan null hanya bila stream habis tanpa satu byte pun.
    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b = -1;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (b < 0 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String str(JSONObject source, String key) {
        if (source == null || key == null) return "";
        try {
            Object value = source.opt(key);
            if (value == null || value == JSONObject.NULL) return "";
            if (value instanceof JSONObject || value instanceof JSONArray) return "";
            return String.valueOf(value).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstNonEmpty(String... candidates) {
        if (candidates == null) return "";
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) return candidate;
        }
        return "";
    }
}
