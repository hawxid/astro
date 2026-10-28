package id.hawx.astro.keysmouse;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import id.hawx.astro.bluetooth.BluebinderController;
import id.hawx.astro.utils.Core;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bluetooth transport: talks JSON lines over TCP loopback to bluez_hid.py
 * running in the chroot (see assets/bluez_hid.py). The script owns the
 * BlueZ HID profile; this class only forwards live events + surfaces the
 * script's pairing/connection status.
 */
public final class BluezKeysMouseTransport implements KeysMouseTransport {

    public interface StatusListener {
        void onStatus(@NonNull String text, boolean hostConnected);
    }

    public interface CheckCallback {
        void onResult(@NonNull String report);
    }

    private static final int CONTROL_PORT = 18923;
    private static final String CHROOT_SCRIPT = "/tmp/bluez_hid.py";
    private static final String CHROOT_ROOT = "/data/local/astro/release";

    private final Context appContext;
    private final Core core;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StatusListener listener;

    private Socket socket;
    private PrintWriter out;
    private Thread readerThread;
    private volatile boolean open;

    public BluezKeysMouseTransport(@NonNull Context context,
                                   @NonNull StatusListener listener) {
        this.appContext = context.getApplicationContext();
        this.core = new Core(appContext);
        this.listener = listener;
    }

    @Override
    public String name() {
        return "Bluetooth";
    }

    @Override
    public synchronized void open() throws IOException {
        if (open) return;
        ensureBluebinder();
        ensureScript();
        core.customChrootCommand("pkill -f bluez_hid.py; sleep 1", true);
        core.customChrootCommand(
                "nohup python3 " + CHROOT_SCRIPT + " > /tmp/bluez_hid.log 2>&1 & echo started",
                true);
        Socket connected = null;
        IOException lastError = null;
        for (int i = 0; i < 10; i++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", CONTROL_PORT), 2000);
                connected = s;
                break;
            } catch (IOException e) {
                lastError = e;
                sleepQuietly(1000);
            }
        }
        if (connected == null) {
            throw new IOException("Skrip bluez_hid tidak menjawab di 127.0.0.1:"
                    + CONTROL_PORT + " — cek /tmp/bluez_hid.log di chroot"
                    + (lastError != null ? " (" + lastError.getMessage() + ")" : ""));
        }
        socket = connected;
        out = new PrintWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8), true);
        open = true;
        Thread reader = new Thread(this::readerLoop, "bluez-hid-reader");
        readerThread = reader;
        reader.start();
        sendLine("{\"type\":\"status\"}");
    }

    @Override
    public synchronized void close() {
        if (!open && socket == null) return;
        try {
            sendLine("{\"type\":\"quit\"}");
        } catch (Exception ignored) {
        }
        open = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        core.customChrootCommand("pkill -f bluez_hid.py", true);
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized boolean hasMouse() {
        return open;
    }

    @Override
    public void sendKey(int modifier, int keycode) throws IOException {
        sendLine("{\"type\":\"key\",\"mod\":" + modifier + ",\"code\":" + keycode + "}");
    }

    @Override
    public void sendMouseMove(int dx, int dy) throws IOException {
        sendLine("{\"type\":\"move\",\"dx\":" + dx + ",\"dy\":" + dy + "}");
    }

    @Override
    public void sendMouseButton(int button, boolean pressed) throws IOException {
        sendLine("{\"type\":\"btn\",\"button\":" + button + ",\"pressed\":" + pressed + "}");
    }

    @Override
    public void sendMouseClick(int button) throws IOException {
        sendMouseButton(button, true);
        sendMouseButton(button, false);
    }

    @Override
    public void sendMouseWheel(int delta) throws IOException {
        sendLine("{\"type\":\"wheel\",\"delta\":" + delta + "}");
    }

    /** Runs the script's --check mode and reports the environment JSON. */
    public void checkEnvironment(@NonNull CheckCallback cb) {
        new Thread(() -> {
            try {
                ensureScript();
            } catch (Exception e) {
                post(() -> cb.onResult("Gagal menyalin skrip: " + e.getMessage()));
                return;
            }
            List<String> lines = core.customChrootCommand(
                    "python3 " + CHROOT_SCRIPT + " --check", true);
            StringBuilder sb = new StringBuilder();
            if (lines != null) {
                for (String line : lines) {
                    if (line != null) sb.append(line).append('\n');
                }
            }
            String report = sb.toString().trim();
            if (report.isEmpty()) report = "(tidak ada output dari --check)";
            final String finalReport = report;
            post(() -> cb.onResult(finalReport));
        }, "bluez-check").start();
    }

    private void ensureBluebinder() throws IOException {
        BluebinderController controller = new BluebinderController(appContext);
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        final String[] detail = new String[1];
        controller.ensureActive((success, text) -> {
            ok[0] = success;
            detail[0] = text;
            latch.countDown();
        });
        try {
            if (!latch.await(25, TimeUnit.SECONDS)) {
                throw new IOException("Timeout menunggu Bluebinder aktif");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Dibatalkan saat menunggu Bluebinder");
        }
        if (!ok[0]) {
            throw new IOException("Bluebinder gagal: " + detail[0]);
        }
    }

    private void ensureScript() throws IOException {
        File filesScript = new File(appContext.getFilesDir(), "bluez_hid.py");
        if (!filesScript.exists()) {
            AssetManager am = appContext.getAssets();
            try (InputStream in = am.open("bluez_hid.py");
                 OutputStream o = new FileOutputStream(filesScript)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            }
        }
        core.copyFile(filesScript.getAbsolutePath(),
                CHROOT_ROOT + CHROOT_SCRIPT);
        core.customChrootCommand("chmod +x " + CHROOT_SCRIPT, true);
    }

    private synchronized void sendLine(String json) throws IOException {
        if (!open || out == null) throw new IOException("Transport Bluetooth belum terhubung");
        out.println(json);
        out.flush();
        if (out.checkError()) throw new IOException("Koneksi ke skrip bluez_hid putus");
    }

    private void readerLoop() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                handleStatusLine(line);
            }
        } catch (Exception ignored) {
        } finally {
            if (open) {
                open = false;
                post(() -> listener.onStatus(
                        "Koneksi ke skrip bluez_hid putus", false));
            }
        }
    }

    private void handleStatusLine(String line) {
        String status;
        String host;
        try {
            JSONObject obj = new JSONObject(line);
            if (!obj.has("status")) return;
            status = obj.optString("status", "");
            host = obj.optString("host", "");
        } catch (Exception ignored) {
            return;
        }
        boolean connected = "connected".equals(status);
        String text;
        if (connected) {
            text = "Terhubung ke host " + host + " (Bluetooth).";
        } else {
            text = "Menunggu host pairing ke 'Astro Keys' (Bluetooth) — "
                    + "buka pengaturan Bluetooth di host dan pairing.";
        }
        final String finalText = text;
        post(() -> listener.onStatus(finalText, connected));
    }

    private void post(@NonNull Runnable r) {
        main.post(r);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
