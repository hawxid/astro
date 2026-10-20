package id.hawx.astro.goose;

import android.util.Log;

import id.hawx.astro.utils.Core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ACP (Agent Client Protocol) client for goose.
 *
 * Spawns {@code goose acp} inside the Debian chroot via su + chroot_exec and
 * speaks JSON-RPC 2.0 over stdio (newline-delimited).
 *
 * Protocol validated against goose 1.53.0 (2026-10-11):
 *   initialize -> session/new -> session/prompt (async)
 *   agent -> client: session/update notifications, session/request_permission requests
 *
 * All Listener callbacks arrive on the internal reader thread (NOT the main thread).
 * The UI layer is responsible for posting to the main thread.
 */
public class GooseAcpClient {

    private static final String TAG = "GooseAcpClient";
    private static final int PROTOCOL_VERSION = 1;

    /** One permission option offered by the agent (allow_once, reject_once, ...). */
    public static class PermissionOption {
        public final String optionId;
        public final String name;
        public final String kind;

        public PermissionOption(String optionId, String name, String kind) {
            this.optionId = optionId;
            this.name = name;
            this.kind = kind;
        }
    }

    /** Info returned by session/new. */
    public static class SessionInfo {
        public final String sessionId;
        public final String modeId;

        public SessionInfo(String sessionId, String modeId) {
            this.sessionId = sessionId;
            this.modeId = modeId;
        }
    }

    /** One entry from session/list. */
    public static class SessionEntry {
        public final String sessionId;
        public final String title;
        public final String cwd;
        public final String updatedAt;

        public SessionEntry(String sessionId, String title, String cwd, String updatedAt) {
            this.sessionId = sessionId;
            this.title = title;
            this.cwd = cwd;
            this.updatedAt = updatedAt;
        }
    }

    public interface Listener {
        void onMessageChunk(String messageId, String text);
        void onToolCall(String toolCallId, String title, String rawInputJson);
        void onToolCallUpdate(String toolCallId, String status, String text);
        void onPermissionRequest(String requestId, String toolCallId, String title,
                                 String command, List<PermissionOption> options);
        void onTurnEnd(String sessionId, String stopReason);
        void onUsage(long used, long size, double costUsd);
        void onSessionTitle(String sessionId, String title);
        void onError(String message);
        void onDisconnected();
    }

    private static class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        JSONObject result;
        String error;
    }

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Integer, String> promptSessions = new ConcurrentHashMap<>();

    private volatile Listener listener;
    private volatile Process process;
    private volatile OutputStream out;
    private volatile boolean connected = false;

    /**
     * Spawn {@code goose acp} in the chroot and start the reader loop.
     * Does NOT send initialize — call {@link #initialize(long)} after connect.
     */
    public void connect(Listener listener) throws IOException {
        this.listener = listener;
        Process p = Runtime.getRuntime().exec("su");
        OutputStream os = p.getOutputStream();
        // Enter chroot, then replace the shell with goose acp so our pipes
        // talk directly to the ACP server. Use a login shell (-lc) so the
        // user's PATH (e.g. ~/.local/bin where goose lives) is loaded —
        // non-interactive bash does not source ~/.profile.
        os.write((Core.EXECUTE + "'bash'\n").getBytes(StandardCharsets.UTF_8));
        os.write("exec bash -lc \"goose acp\"\n".getBytes(StandardCharsets.UTF_8));
        os.flush();

        this.process = p;
        this.out = os;
        this.connected = true;

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        Thread t = new Thread(() -> readLoop(reader), "goose-acp-reader");
        t.setDaemon(true);
        t.start();

        // Drain stderr so a chatty agent can never block on a full pipe.
        BufferedReader errReader = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
        Thread et = new Thread(() -> {
            try {
                String el;
                while ((el = errReader.readLine()) != null) {
                }
            } catch (IOException ignored) { }
        }, "goose-acp-stderr");
        et.setDaemon(true);
        et.start();
    }

    /** ACP initialize handshake. Returns true on success. */
    public boolean initialize(long timeoutMs) {
        try {
            int id = nextId.getAndIncrement();
            JSONObject params = new JSONObject();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.put("clientCapabilities", new JSONObject());
            Pending p = sendRequest(id, "initialize", params);
            if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pending.remove(id);
                emitError("initialize timed out");
                return false;
            }
            if (p.error != null) {
                emitError("initialize failed: " + p.error);
                return false;
            }
            return true;
        } catch (Exception e) {
            emitError("initialize exception: " + e.getMessage());
            return false;
        }
    }

    /** Create a new session. Returns null on failure. */
    public SessionInfo newSession(String cwd, long timeoutMs) {        try {
            int id = nextId.getAndIncrement();
            JSONObject params = new JSONObject();
            params.put("cwd", cwd);
            params.put("mcpServers", new JSONArray());
            Pending p = sendRequest(id, "session/new", params);
            if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pending.remove(id);
                emitError("session/new timed out");
                return null;
            }
            if (p.error != null || p.result == null) {
                emitError("session/new failed: " + p.error);
                return null;
            }
            String sessionId = p.result.getString("sessionId");
            String modeId = "";
            JSONObject modes = p.result.optJSONObject("modes");
            if (modes != null) modeId = modes.optString("currentModeId", "");
            return new SessionInfo(sessionId, modeId);
        } catch (Exception e) {
            emitError("session/new exception: " + e.getMessage());
            return null;
        }
    }

    /** Resume an existing session. Returns null on failure. */
    public SessionInfo loadSession(String sessionId, String cwd, long timeoutMs) {
        try {
            int id = nextId.getAndIncrement();
            JSONObject params = new JSONObject();
            params.put("sessionId", sessionId);
            params.put("cwd", cwd);
            params.put("mcpServers", new JSONArray());
            Pending p = sendRequest(id, "session/load", params);
            if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pending.remove(id);
                emitError("session/load timed out");
                return null;
            }
            if (p.error != null || p.result == null) {
                emitError("session/load failed: " + p.error);
                return null;
            }
            String modeId = "";
            JSONObject modes = p.result.optJSONObject("modes");
            if (modes != null) modeId = modes.optString("currentModeId", "");
            return new SessionInfo(sessionId, modeId);
        } catch (Exception e) {
            emitError("session/load exception: " + e.getMessage());
            return null;
        }
    }

    /** List all sessions. Returns empty list on failure. */
    public List<SessionEntry> listSessions(long timeoutMs) {
        List<SessionEntry> out = new ArrayList<>();
        try {
            int id = nextId.getAndIncrement();
            Pending p = sendRequest(id, "session/list", new JSONObject());
            if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pending.remove(id);
                emitError("session/list timed out");
                return out;
            }
            if (p.error != null || p.result == null) {
                emitError("session/list failed: " + p.error);
                return out;
            }
            JSONArray arr = p.result.optJSONArray("sessions");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                out.add(new SessionEntry(
                        s.getString("sessionId"),
                        s.optString("title", s.getString("sessionId")),
                        s.optString("cwd", ""),
                        s.optString("updatedAt", "")));
            }
        } catch (Exception e) {
            emitError("session/list exception: " + e.getMessage());
        }
        return out;
    }

    /** Delete a session. Returns true on success. */
    public boolean deleteSession(String sessionId, long timeoutMs) {
        try {
            int id = nextId.getAndIncrement();
            JSONObject params = new JSONObject();
            params.put("sessionId", sessionId);
            Pending p = sendRequest(id, "session/delete", params);
            if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pending.remove(id);
                emitError("session/delete timed out");
                return false;
            }
            return p.error == null;
        } catch (Exception e) {
            emitError("session/delete exception: " + e.getMessage());
            return false;
        }
    }

    /** Send a user prompt. Results stream back via Listener (async). */
    public void prompt(String sessionId, String text) throws IOException, JSONException {
        promptWithImage(sessionId, text, null, null);
    }

    /**
     * Send a prompt with optional image. imageBase64 is base64-encoded image data,
     * mimeType e.g. "image/jpeg". If imageBase64 is null, sends text only.
     */
    public void promptWithImage(String sessionId, String text, String imageBase64, String mimeType)
            throws IOException, JSONException {
        int id = nextId.getAndIncrement();
        promptSessions.put(id, sessionId);
        JSONObject params = new JSONObject();
        params.put("sessionId", sessionId);
        JSONArray prompt = new JSONArray();
        if (imageBase64 != null && mimeType != null) {
            JSONObject imgBlock = new JSONObject();
            imgBlock.put("type", "image");
            imgBlock.put("mimeType", mimeType);
            imgBlock.put("data", imageBase64);
            prompt.put(imgBlock);
        }
        if (text != null && !text.isEmpty()) {
            JSONObject block = new JSONObject();
            block.put("type", "text");
            block.put("text", text);
            prompt.put(block);
        }
        params.put("prompt", prompt);
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", "session/prompt");
        msg.put("params", params);
        send(msg);
    }

    /**
     * Answer a permission request. optionId must be one of the optionIds
     * delivered in onPermissionRequest (e.g. "allow_once").
     */
    public void respondPermission(String requestId, String optionId) throws IOException, JSONException {
        JSONObject outcome = new JSONObject();
        outcome.put("outcome", "selected");
        outcome.put("optionId", optionId);
        JSONObject result = new JSONObject();
        result.put("outcome", outcome);
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", "2.0");
        msg.put("id", requestId);
        msg.put("result", result);
        send(msg);
    }

    /** Cooperative cancel of the in-flight turn (notification, no response). */
    public void cancel(String sessionId) throws IOException, JSONException {
        JSONObject params = new JSONObject();
        params.put("sessionId", sessionId);
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", "2.0");
        msg.put("method", "session/cancel");
        msg.put("params", params);
        send(msg);
    }

    public void disconnect() {
        connected = false;
        promptSessions.clear();
        for (Pending p : pending.values()) p.latch.countDown();
        pending.clear();
        try {
            if (out != null) out.close();
        } catch (IOException ignored) { }
        if (process != null) process.destroy();
    }

    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private Pending sendRequest(int id, String method, JSONObject params) throws IOException, JSONException {
        Pending p = new Pending();
        pending.put(id, p);
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.put("params", params);
        send(msg);
        return p;
    }

    private synchronized void send(JSONObject msg) throws IOException {
        if (out == null) throw new IOException("not connected");
        String line = msg.toString();
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }


    private void readLoop(BufferedReader reader) {
        try {
            String line;
            while (connected && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject msg;
                try {
                    msg = new JSONObject(line);
                } catch (Exception e) {
                    Log.w(TAG, "skipping non-JSON line: " + line.substring(0, Math.min(120, line.length())));
                    continue;
                }
                try {
                    handleMessage(msg);
                } catch (Exception e) {
                    Log.e(TAG, "handleMessage failed", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "ACP read loop ended", e);
        } finally {
            connected = false;
            Listener l = listener;
            if (l != null) l.onDisconnected();
        }
    }

    private void handleMessage(JSONObject msg) {
        if (msg.has("method")) {
            if (msg.has("id")) {
                handleAgentRequest(msg);
            } else {
                handleNotification(msg);
            }
            return;
        }
        if (msg.has("id")) {
            handleResponse(msg);
        }
    }

    private void handleResponse(JSONObject msg) {
        int id;
        try {
            id = msg.getInt("id");
        } catch (Exception e) {
            return; // not one of ours
        }
        // Prompt turn finished?
        String sessionId = promptSessions.remove(id);
        if (sessionId != null) {
            JSONObject result = msg.optJSONObject("result");
            String stopReason = result != null ? result.optString("stopReason", "") : "";
            JSONObject err = msg.optJSONObject("error");
            if (err != null) {
                emitError("prompt failed: " + err.optString("message", err.toString()));
            }
            Listener l = listener;
            if (l != null) l.onTurnEnd(sessionId, stopReason);
            return;
        }
        Pending p = pending.remove(id);
        if (p == null) return;
        JSONObject err = msg.optJSONObject("error");
        if (err != null) {
            p.error = err.optString("message", err.toString());
        } else {
            p.result = msg.optJSONObject("result");
        }
        p.latch.countDown();
    }

    private void handleAgentRequest(JSONObject msg) {
        String method = msg.optString("method", "");
        if (!"session/request_permission".equals(method)) {
            Log.w(TAG, "unknown agent request: " + method);
            return;
        }
        try {
            String requestId = msg.get("id").toString();
            JSONObject params = msg.getJSONObject("params");
            JSONObject toolCall = params.getJSONObject("toolCall");
            String toolCallId = toolCall.getString("toolCallId");
            String title = toolCall.optString("title", "");
            String command = "";
            JSONObject rawInput = toolCall.optJSONObject("rawInput");
            if (rawInput != null) {
                command = rawInput.optString("command", "");
                if (command.isEmpty()) command = rawInput.toString();
            }
            List<PermissionOption> options = new ArrayList<>();
            JSONArray arr = params.optJSONArray("options");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    options.add(new PermissionOption(
                            o.getString("optionId"),
                            o.optString("name", ""),
                            o.optString("kind", "")));
                }
            }
            Listener l = listener;
            if (l != null) l.onPermissionRequest(requestId, toolCallId, title, command, options);
        } catch (Exception e) {
            Log.e(TAG, "bad permission request", e);
        }
    }

    private void handleNotification(JSONObject msg) {
        if (!"session/update".equals(msg.optString("method", ""))) return;
        try {
            JSONObject params = msg.getJSONObject("params");
            String sessionId = params.optString("sessionId", "");
            JSONObject update = params.getJSONObject("update");
            String kind = update.getString("sessionUpdate");
            Listener l = listener;
            if (l == null) return;
            switch (kind) {
                case "agent_message_chunk": {
                    JSONObject content = update.optJSONObject("content");
                    String text = content != null ? content.optString("text", "") : "";
                    l.onMessageChunk(update.optString("messageId", ""), text);
                    break;
                }
                case "tool_call": {
                    Object raw = update.opt("rawInput");
                    l.onToolCall(update.getString("toolCallId"),
                            update.optString("title", ""),
                            raw != null ? raw.toString() : "");
                    break;
                }
                case "tool_call_update": {
                    l.onToolCallUpdate(update.getString("toolCallId"),
                            update.optString("status", ""),
                            extractText(update));
                    break;
                }
                case "usage_update": {
                    JSONObject cost = update.optJSONObject("cost");
                    l.onUsage(update.optLong("used", 0), update.optLong("size", 0),
                            cost != null ? cost.optDouble("amount", 0) : 0);
                    break;
                }
                case "session_info_update": {
                    String title = update.optString("title", null);
                    if (title != null) l.onSessionTitle(sessionId, title);
                    break;
                }
                default:
                    break; // ignore session_info noise etc.
            }
        } catch (Exception e) {
            Log.e(TAG, "bad session/update", e);
        }
    }

    /** Pull human-readable text out of a tool_call_update (content[] or live output). */
    private String extractText(JSONObject update) {
        StringBuilder sb = new StringBuilder();
        JSONArray content = update.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject item = content.optJSONObject(i);
                if (item == null) continue;
                JSONObject inner = item.optJSONObject("content");
                if (inner != null && "text".equals(inner.optString("type"))) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(inner.optString("text", ""));
                }
            }
        }
        JSONObject meta = update.optJSONObject("_meta");
        if (meta != null) {
            JSONObject notif = meta.optJSONObject("toolNotification");
            if (notif != null && "live_output".equals(notif.optString("type"))) {
                JSONObject p = notif.optJSONObject("params");
                if (p != null) {
                    JSONArray chunks = p.optJSONArray("chunks");
                    if (chunks != null) {
                        for (int i = 0; i < chunks.length(); i++) {
                            JSONObject c = chunks.optJSONObject(i);
                            if (c != null) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(c.optString("output", ""));
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private void emitError(String message) {
        Log.e(TAG, message);
        Listener l = listener;
        if (l != null) l.onError(message);
    }
}
