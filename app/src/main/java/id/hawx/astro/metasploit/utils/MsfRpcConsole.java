package id.hawx.astro.metasploit.utils;


import id.hawx.astro.utils.Core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import com.stryker.terminal.bridge.StrykerLog;

public class MsfRpcConsole {

    private static final String TAG = "MsfRpcConsole";

    public enum State { IDLE, BOOTING, READY, DEAD }

    public interface Listener {
        void onLine(String line);
        void onState(State state, String reason);
        /**
         * Called when a chunk was terminated by '\r' (carriage return)
         * instead of '\n'. The UI should update the current line in place
         * instead of appending a new line. Default falls back to onLine
         * for backward compatibility.
         */
        default void onCarriageReturn(String line) {
            onLine(line);
        }
    }

    private static final String CHROOT_LAUNCH = Core.EXECUTE + "'msfconsole'\n";

    private final Core core;
    private final String label;

    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile BufferedReader stdout;
    private volatile boolean eof;
    private Thread stderrPump;

    private volatile State state = State.IDLE;
    private volatile String version = "";
    private final Object ioLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private static final long CMD_TIMEOUT_MS = 60_000L;
    private static final long BOOT_IDLE_MS = 90_000L;
    private static final long BOOT_CAP_MS = 20 * 60_000L;
    private static final long CMD_CAP_MS = 10 * 60_000L;

    private Listener listener;

    public MsfRpcConsole(Core core, String label) {
        this.core = core;
        this.label = label;
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public State getState() { return state; }
    public String getVersion() { return version; }
    public boolean isReady() { return state == State.READY && isProcessAlive(); }

    public synchronized boolean boot() {
        if (state == State.READY && isProcessAlive()) return true;
        teardown("respawn");
        publishState(State.BOOTING, "starting msfconsole");
        String failure = openSuChannel();
        if (failure != null) {
            publishState(State.DEAD, failure);
            return false;
        }
        try {
            long cap = System.currentTimeMillis() + BOOT_CAP_MS;
            long idle = System.currentTimeMillis() + BOOT_IDLE_MS;
            LineChunk chunk;
            while ((chunk = readChunk(Math.min(cap, idle))) != null) {
                idle = System.currentTimeMillis() + BOOT_IDLE_MS;
                String line = clean(chunk.text);
                if (line.contains("metasploit v")) {
                    version = parseVersion(line);
                    publishState(State.READY, "v" + version);
                    return true;
                }
                if (isMissingBinary(line)) {
                    publishState(State.DEAD, "msfconsole not installed");
                    return false;
                }
                if (!shouldSuppress(line)) emitChunk(new LineChunk(line, chunk.carriageReturn));
            }
            publishState(State.DEAD, eof ? "msfconsole exited" : "boot timeout");
            return false;
        } catch (IOException e) {
            StrykerLog.e(TAG, label + " boot failed", e);
            publishState(State.DEAD, e.getMessage() == null ? "io error" : e.getMessage());
            return false;
        }
    }

    /**
     * Result of terminator-aware line reading.
     */
    private static class LineChunk {
        final String text;
        /** true if terminated by lone '\r' (in-place update), false for '\n' or '\r\n' */
        final boolean carriageReturn;
        LineChunk(String text, boolean carriageReturn) {
            this.text = text;
            this.carriageReturn = carriageReturn;
        }
    }

    private String readLine(long deadline) throws IOException {
        LineChunk chunk = readChunk(deadline);
        return chunk != null ? chunk.text : null;
    }

    /**
     * Reads until '\n', '\r\n', or lone '\r', preserving which terminator
     * was seen so the UI can do in-place updates for '\r'.
     */
    private LineChunk readChunk(long deadline) throws IOException {
        BufferedReader r = stdout;
        if (r == null) return null;
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            try {
                int c = r.read();
                if (c == -1) {
                    eof = true;
                    return sb.length() > 0 ? new LineChunk(sb.toString(), false) : null;
                }
                if (c == '\n') {
                    return new LineChunk(sb.toString(), false);
                }
                if (c == '\r') {
                    // Check for \r\n sequence
                    r.mark(1);
                    int next = r.read();
                    if (next == '\n') {
                        return new LineChunk(sb.toString(), false);
                    }
                    if (next != -1) r.reset();
                    return new LineChunk(sb.toString(), true);
                }
                sb.append((char) c);
            } catch (java.net.SocketTimeoutException idle) {
                eof = true;
                return null;
            }
        }
        return null;
    }

    private static boolean isMissingBinary(String line) {
        return line.contains("command not found")
                || line.contains("No such file")
                || line.contains("msfconsole: not found");
    }

    private String openSuChannel() {
        process = core.generateSuProcess();
        if (process == null) return "su denied";
        try {
            stdin = process.getOutputStream();
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            startStderrPump(process.getErrorStream());
            stdin.write(CHROOT_LAUNCH.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            return null;
        } catch (IOException e) {
            StrykerLog.e(TAG, label + " su channel failed", e);
            return e.getMessage() == null ? "io error" : e.getMessage();
        }
    }

    public ArrayList<String> command(String cmd) {
        return command(cmd, CMD_TIMEOUT_MS);
    }

    public ArrayList<String> command(String cmd, long timeoutMs) {
        ArrayList<String> out = new ArrayList<>();
        if (!isProcessAlive()) {
            if (!boot()) return out;
        }
        synchronized (ioLock) {
            String sentinel = "__STRYKER_END_" + Core.generateString().substring(0, 12) + "__";
            try {
                stdin.write((cmd + "\n").getBytes());
                stdin.write(("echo " + sentinel + "\n").getBytes());
                stdin.flush();
                long cap = System.currentTimeMillis() + CMD_CAP_MS;
                long idle = System.currentTimeMillis() + timeoutMs;
                LineChunk chunk;
                while ((chunk = readChunk(Math.min(cap, idle))) != null) {
                    idle = System.currentTimeMillis() + timeoutMs;
                    if (chunk.text.contains(sentinel)) return out;
                    String line = clean(chunk.text);
                    if (shouldSuppress(line)) continue;
                    out.add(line);
                    emitChunk(new LineChunk(line, chunk.carriageReturn));
                }
                publishState(State.DEAD, eof ? "msfconsole exited" : "command timeout");
            } catch (Exception e) {
                StrykerLog.e(TAG, label + " io error in command", e);
                publishState(State.DEAD, e.getMessage() == null ? "io error" : e.getMessage());
            }
        }
        return out;
    }

    public void send(String cmd) {
        if (!isProcessAlive()) return;
        synchronized (ioLock) {
            try {
                stdin.write((cmd + "\n").getBytes());
                stdin.flush();
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized boolean restart() {
        shuttingDown.set(false);
        teardown("manual restart");
        return boot();
    }

    public synchronized void shutdown() {
        shuttingDown.set(true);
        teardown("shutdown");
        publishState(State.IDLE, "shut down");
    }

    public boolean isProcessAlive() {
        if (eof) return false;
        if (process == null) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    private void teardown(String why) {
        try { if (stdin != null) stdin.close(); } catch (IOException ignored) {}
        try { if (stdout != null) stdout.close(); } catch (IOException ignored) {}
        if (process != null) {
            try { process.destroy(); } catch (Exception ignored) {}
        }
        process = null;
        stdin = null;
        stdout = null;
        eof = false;
        StrykerLog.d(TAG, label + " torn down (" + why + ")");
    }

    private void startStderrPump(final InputStream err) {
        if (err == null) return;
        stderrPump = new Thread(() -> {
            BufferedReader br = new BufferedReader(new InputStreamReader(err));
            StringBuilder sb = new StringBuilder();
            try {
                int c;
                while ((c = br.read()) != -1) {
                    if (c == '\n') {
                        String cleaned = clean(sb.toString());
                        sb.setLength(0);
                        if (shouldSuppress(cleaned)) continue;
                        emitChunk(new LineChunk("[stderr] " + cleaned, false));
                    } else if (c == '\r') {
                        br.mark(1);
                        int next = br.read();
                        if (next == '\n') {
                            String cleaned = clean(sb.toString());
                            sb.setLength(0);
                            if (shouldSuppress(cleaned)) continue;
                            emitChunk(new LineChunk("[stderr] " + cleaned, false));
                        } else {
                            if (next != -1) br.reset();
                            String cleaned = clean(sb.toString());
                            sb.setLength(0);
                            if (shouldSuppress(cleaned)) continue;
                            emitChunk(new LineChunk("[stderr] " + cleaned, true));
                        }
                    } else {
                        sb.append((char) c);
                    }
                }
                if (sb.length() > 0) {
                    String cleaned = clean(sb.toString());
                    if (!shouldSuppress(cleaned)) emitChunk(new LineChunk("[stderr] " + cleaned, false));
                }
            } catch (IOException ignored) {
            }
        }, "MsfRpcStderr-" + label);
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    private void publishState(State newState, String reason) {
        state = newState;
        if (listener != null) listener.onState(newState, reason);
    }

    private void emitLine(String line) {
        if (listener != null) listener.onLine(line);
    }

    private void emitChunk(LineChunk chunk) {
        if (listener == null || chunk == null) return;
        if (chunk.carriageReturn) {
            listener.onCarriageReturn(chunk.text);
        } else {
            listener.onLine(chunk.text);
        }
    }

    private static String clean(String line) {
        if (line == null) return "";
        line = line.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
        line = line.replaceAll(".*0m>", "");
        line = line.replaceFirst("^msf\\d?[^>]*>\\s?", "");
        return line.trim();
    }

    static boolean shouldSuppress(String line) {
        if (line == null || line.isEmpty()) return true;
        if (line.contains("__STRYKER_END_")) return true;
        if (line.contains("Starting the Metasploit Framework console")) return true;
        if (line.contains("stty: standard input: Not a tty")) return true;
        if (line.contains("Inappropriate ioctl for device")) return true;
        if (line.startsWith("echo __STRYKER_END_")) return true;
        return false;
    }

    private static String parseVersion(String bannerLine) {
        return bannerLine.replace("metasploit v", "")
                .replace("=", "")
                .replace("[", "")
                .replace("]", "")
                .trim();
    }
}
