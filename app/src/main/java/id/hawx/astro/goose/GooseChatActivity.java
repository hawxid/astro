package id.hawx.astro.goose;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import id.hawx.astro.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Goose AI chat (Fase 2). Minimal functional UI over GooseAcpClient:
 * message list, input, streaming, and a basic permission dialog.
 * Approval cards (Fase 3) will replace the dialog with inline cards.
 */
public class GooseChatActivity extends AppCompatActivity implements GooseAcpClient.Listener {

    private static final String WORK_DIR = "/root";
    private static final int REQ_PICK_IMAGE = 1001;

    private GooseAcpClient client;
    private String sessionId;
    private volatile boolean working = false;

    private ChatAdapter adapter;
    private RecyclerView messagesView;
    private MaterialTextView statusView;
    private TextInputEditText inputView;
    private MaterialButton sendButton;
    private ImageButton attachButton;
    private LinearLayout attachBar;
    private MaterialTextView attachNameView;
    private ImageButton attachClearButton;

    // Pending image to send (base64 + mime)
    private String pendingImageBase64;
    private String pendingImageMime;
    // Pending text file content
    private String pendingTextFile;
    private String pendingFileName;
    private String pendingUploadCopyPath;

    private String copyUploadToGooseDir(String name, byte[] data) {
        try {
            java.io.File dir = new java.io.File("/sdcard/Astro/goose/uploads");
            if (!dir.exists()) dir.mkdirs();
            String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safe.isEmpty()) safe = "file";
            java.io.File outf = new java.io.File(dir, safe);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outf);
            fos.write(data);
            fos.close();
            return outf.getAbsolutePath();
        } catch (Exception e) { return null; }
    }

    private int streamingPos = -1;
    private final Map<String, Integer> toolPositions = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_goose_chat);

        MaterialToolbar toolbar = findViewById(R.id.goose_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.goose_chat_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.goose_menu_sessions) {
                showSessionsDialog();
                return true;
            }
            if (item.getItemId() == R.id.goose_menu_files) {
                showFilesDialog();
                return true;
            }
            if (item.getItemId() == R.id.goose_menu_model) {
                showProfileDialog();
                return true;
            }
            return false;
        });

        statusView = findViewById(R.id.goose_status);
        messagesView = findViewById(R.id.goose_messages);
        inputView = findViewById(R.id.goose_input);
        sendButton = findViewById(R.id.goose_send);
        attachButton = findViewById(R.id.goose_attach);
        attachBar = findViewById(R.id.goose_attach_bar);
        attachNameView = findViewById(R.id.goose_attach_name);
        attachClearButton = findViewById(R.id.goose_attach_clear);
        attachClearButton.setOnClickListener(v -> clearAttachment());

        adapter = new ChatAdapter();
        adapter.setOnFileClickListener(p -> openGhostFile(new java.io.File(p)));
        adapter.setOnImageClickListener(p -> showFullscreenImage(p));
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        messagesView.setLayoutManager(lm);
        messagesView.setAdapter(adapter);

        sendButton.setOnClickListener(v -> onSendOrCancel());
        attachButton.setOnClickListener(v -> pickImage());

        addSystem("Starting goose…");
        new Thread(this::connectBlocking, "goose-connect").start();
    }

    @Override
    protected void onDestroy() {
        if (client != null) client.disconnect();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    private void connectBlocking() {
        try {
            client = new GooseAcpClient();
            client.connect(this);
        } catch (Exception e) {
            ui(() -> {
                setStatus("Failed to start goose: " + e.getMessage());
                addSystem("Could not launch 'goose acp' in the chroot. " +
                        "Make sure goose is installed: " +
                        "curl -fsSL https://github.com/aaif-goose/goose/releases/download/stable/download_cli.sh | bash");
            });
            return;
        }
        ui(() -> setStatus("Handshaking…"));
        if (!client.initialize(20000)) {
            ui(() -> {
                setStatus("ACP handshake failed");
                addSystem("No initialize response from goose acp.");
            });
            return;
        }
        GooseAcpClient.SessionInfo info = null;
        String lastId = getPrefs().getString("last_session_id", null);
        boolean resumed = false;
        if (lastId != null) {
            ui(() -> setStatus("Resuming…"));
            info = client.loadSession(lastId, WORK_DIR, 20000);
            if (info != null) resumed = true;
        }
        if (info == null) {
            ui(() -> setStatus("Creating session…"));
            info = client.newSession(WORK_DIR, 20000);
        }
        if (info == null) {
            ui(() -> {
                setStatus("Session creation failed");
                addSystem("goose did not create a session.");
            });
            return;
        }
        sessionId = info.sessionId;
        String mode = info.modeId;
        // Simpan sesi terakhir buat auto-resume
        getPrefs().edit().putString("last_session_id", sessionId).apply();
        final boolean wasResumed = resumed;
        final String fSessionId = sessionId;
        ui(() -> {
            fetchModelAndSetStatus(mode, sessionId);
            if (wasResumed) {
                loadHistory(fSessionId);
            } else {
                addSystem("Session ready (" + fSessionId + "). Ask anything.");
            }
        });
    }

    private android.content.SharedPreferences getPrefs() {
        return getSharedPreferences("ghost_prefs", MODE_PRIVATE);
    }

    /** Load previous chat history when resuming a session. */
    private void fetchModelAndSetStatus(String mode, String sessionId) {
        new Thread(() -> {
            String label = null;
            try {
                id.hawx.astro.utils.Core core = new id.hawx.astro.utils.Core(this);
                String safeId = sessionId.replaceAll("[^a-zA-Z0-9_]", "");
                String cmd = "sqlite3 -json /root/.local/share/goose/sessions/sessions.db \"SELECT provider_name, model_config_json FROM sessions WHERE id='" + safeId + "' LIMIT 1\"";
                java.util.ArrayList<String> out = core.customChrootCommand(cmd, true);
                StringBuilder sb = new StringBuilder();
                for (String l : out) sb.append(l);
                String json = sb.toString().trim();
                if (json.startsWith("[")) {
                    org.json.JSONArray arr = new org.json.JSONArray(json);
                    if (arr.length() > 0) {
                        org.json.JSONObject row = arr.getJSONObject(0);
                        String mcj = row.optString("model_config_json", "");
                        String model = "";
                        if (!mcj.isEmpty()) model = new org.json.JSONObject(mcj).optString("model_name", "");
                        if (!model.isEmpty()) label = model;
                        else label = row.optString("provider_name", "");
                    }
                }
            } catch (Exception ignored) {}
            final String fl = label;
            ui(() -> {
                if (fl != null && !fl.isEmpty()) setStatus("Connected \u00b7 " + fl + " \u00b7 " + mode + " \u00b7 " + sessionId);
                else setStatus("Connected \u00b7 mode: " + mode + " \u00b7 " + sessionId);
            });
        }).start();
    }

    private void loadHistory(String sid) {
        ui(() -> {
            adapter.clear();
            addSystem("Resumed session (" + sid + "). Loading history…");
        });
        new Thread(() -> {
            id.hawx.astro.utils.Core core = new id.hawx.astro.utils.Core(this);
            List<String[]> history = GooseFiles.getSessionHistory(core, sid, 30);
            ui(() -> {
                if (history.isEmpty()) {
                    addSystem("No previous messages found.");
                    return;
                }
                addSystem("📜 Showing last " + history.size() + " messages:");
                for (String[] msg : history) {
                    String role = msg[0];
                    String text = msg[1];
                    if (role.equals("user")) {
                        adapter.add(new ChatMessage(ChatMessage.Type.USER, text));
                    } else {
                        adapter.add(new ChatMessage(ChatMessage.Type.AGENT, text));
                    }
                }
                scrollToBottom();
            });
        }, "goose-history").start();
    }

    // ------------------------------------------------------------------
    // Sending / Cancel / Attach
    // ------------------------------------------------------------------

    private void onSendOrCancel() {
        try {
            if (working) {
                cancelTurn();
            } else {
                sendPrompt();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Send error: " + e.getClass().getSimpleName() +
                    ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateSendButton() {
        if (working) {
            sendButton.setText("Cancel");
            sendButton.setEnabled(true);
        } else {
            sendButton.setText("Send");
            sendButton.setEnabled(true);
        }
    }

    private void cancelTurn() {
        if (client == null || sessionId == null) return;
        new Thread(() -> {
            try {
                client.cancel(sessionId);
            } catch (Exception ignored) {}
        }, "goose-cancel").start();
        ui(() -> {
            addSystem("Cancel requested…");
            // Reset langsung, jangan nunggu onTurnEnd (kadang nggak dateng)
            working = false;
            updateSendButton();
        });
    }

    private void showAttachBar(String filename) {
        ui(() -> {
            attachNameView.setText("📎 " + filename);
            attachBar.setVisibility(android.view.View.VISIBLE);
        });
    }

    private void hideAttachBar() {
        if (attachBar != null) attachBar.setVisibility(android.view.View.GONE);
    }

    private void clearAttachment() {
        pendingImageBase64 = null;
        pendingImageMime = null;
        pendingTextFile = null;
        pendingFileName = null;
        hideAttachBar();
        inputView.setHint("Ask goose…");
        Toast.makeText(this, "Attachment removed", Toast.LENGTH_SHORT).show();
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Select file"), REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            String mime = getContentResolver().getType(uri);
            boolean isImage = mime != null && mime.startsWith("image/");
            new Thread(() -> {
                try {
                    if (isImage) {
                        // Gambar: kirim sebagai image block
                        InputStream is = getContentResolver().openInputStream(uri);
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (is != null) is.close();
                        if (bmp == null) {
                            ui(() -> Toast.makeText(this, "Could not read image",
                                    Toast.LENGTH_SHORT).show());
                            return;
                        }
                        int w = bmp.getWidth(), h = bmp.getHeight();
                        float scale = Math.min(1.0f, 1024.0f / Math.max(w, h));
                        if (scale < 1.0f) {
                            bmp = Bitmap.createScaledBitmap(bmp,
                                    (int)(w*scale), (int)(h*scale), true);
                        }
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                        byte[] bytes = baos.toByteArray();
                        pendingImageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                        pendingImageMime = "image/jpeg";
                        String imgName = "image_" + System.currentTimeMillis() + ".jpg";
                        pendingUploadCopyPath = copyUploadToGooseDir(imgName, bytes);
                        pendingTextFile = null;
                        pendingFileName = imgName;
                        ui(() -> {
                            showAttachBar(imgName);
                            inputView.setHint("Ask about the image…");
                        });
                    } else {
                        // File teks: baca isi, taruh di pending
                        InputStream is = getContentResolver().openInputStream(uri);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n, total = 0;
                        while ((n = is.read(buf)) != -1 && total < 100*1024) {
                            baos.write(buf, 0, n);
                            total += n;
                        }
                        if (is != null) is.close();
                        String content = baos.toString("UTF-8");
                        // Ambil nama file
                        String name = "file";
                        try {
                            android.database.Cursor c = getContentResolver()
                                    .query(uri, null, null, null, null);
                            if (c != null && c.moveToFirst()) {
                                int idx = c.getColumnIndex(
                                        android.provider.OpenableColumns.DISPLAY_NAME);
                                if (idx >= 0) name = c.getString(idx);
                                c.close();
                            }
                        } catch (Exception ignored) {}
                        pendingTextFile = "File: " + name + "\n```\n" + content + "\n```\n";
                        pendingFileName = name;
                        pendingUploadCopyPath = copyUploadToGooseDir(name, baos.toByteArray());
                        pendingImageBase64 = null;
                        pendingImageMime = null;
                        final String fName = name;
                        ui(() -> {
                            showAttachBar(fName);
                            inputView.setHint("Ask about " + fName + "…");
                        });
                    }
                } catch (Exception e) {
                    ui(() -> Toast.makeText(this, "Attach failed: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show());
                }
            }, "goose-attach").start();
        }
    }

    private void sendPrompt() {
        try {
        if (working || client == null || sessionId == null) return;
        String userText = inputView.getText() != null ? inputView.getText().toString().trim() : "";
        String img64 = pendingImageBase64;
        String imgMime = pendingImageMime;
        String textFile = pendingTextFile;
        String fileName = pendingFileName;
        if (userText.isEmpty() && img64 == null && textFile == null) return;

        // --- Slash commands (intercept sebelum kirim ke Ghost) ---
        if (userText.startsWith("/") && img64 == null && textFile == null) {
            String cmdName = userText.split("\\s+")[0].toLowerCase();
            if (cmdName.equals("/help") || cmdName.equals("/?")) {
                inputView.setText("");
                final String help = SlashCommands.helpText();
                final String uText = userText;
                ui(() -> {
                    adapter.add(new ChatMessage(ChatMessage.Type.USER, uText));
                    adapter.add(new ChatMessage(ChatMessage.Type.AGENT, help));
                    scrollToBottom();
                });
                return;
            }
            if (cmdName.equals("/clear")) {
                inputView.setText("");
                ui(() -> {
                    adapter.clear();
                    addSystem("Chat cleared.");
                });
                return;
            }
            String expanded = SlashCommands.expand(userText);
            if (expanded != null) {
                // Tampilkan command asli, kirim expanded ke Ghost
                final String origDisplay = userText;
                userText = expanded;
                // Lanjut ke bawah dengan userText = expanded, display = orig
                inputView.setText("");
                inputView.setHint("Ask goose…");
                hideAttachBar();
                pendingImageBase64 = null;
                pendingImageMime = null;
                pendingTextFile = null;
                pendingFileName = null;
                pendingUploadCopyPath = null;
                working = true;
                updateSendButton();
                final String fText = userText;
                final String fDisplay = origDisplay + "\n→ " + expanded;
                ui(() -> {
                    adapter.add(new ChatMessage(ChatMessage.Type.USER, fDisplay));
                    scrollToBottom();
                    streamingPos = -1;
                    scrollToBottom();
                });
                final String sText = fText;
                final String sImg64 = img64;
                final String sImgMime = imgMime;
                new Thread(() -> {
                    try {
                        client.promptWithImage(sessionId, sText, sImg64, sImgMime);
                    } catch (Exception e) {
                        ui(() -> {
                            addSystem("Send failed: " + e.getMessage());
                            working = false;
                            updateSendButton();
                        });
                    }
                }, "goose-prompt").start();
                return;
            }
            // Unknown slash command: kirim apa adanya ke Ghost
        }

        // Prompt lengkap (dengan isi file) untuk goose
        String fullText = userText;
        if (textFile != null) {
            fullText = textFile + (userText.isEmpty() ? "" : "\n" + userText);
        }
        if (img64 != null && pendingUploadCopyPath != null) {
            fullText = (fullText.isEmpty() ? "" : fullText + "\n") + "[file:" + pendingUploadCopyPath + "]";
        }
        // Display untuk chat (cuma nama file, jangan dump isi)
        final String display;
        if (fileName != null) {
            String dx = "📎 " + fileName + (userText.isEmpty() ? "" : "\n" + userText);
            if (pendingUploadCopyPath != null) dx += "\n[file:" + pendingUploadCopyPath + "]";
            display = dx;
        } else if (img64 != null) {
            String dx2 = (userText.isEmpty() ? "" : userText + " ") + "🖼 [image]";
            if (pendingUploadCopyPath != null) dx2 += "\n[file:" + pendingUploadCopyPath + "]";
            display = dx2;
        } else {
            display = userText;
        }
        inputView.setText("");
        inputView.setHint("Ask goose…");
        hideAttachBar();
        pendingImageBase64 = null;
        pendingImageMime = null;
        pendingTextFile = null;
        pendingFileName = null;
        working = true;
        updateSendButton();
        final String fText = fullText;
        ui(() -> {
            adapter.add(new ChatMessage(ChatMessage.Type.USER, display));
            scrollToBottom();
            // Jangan bikin bubble agent dulu — bikin pas teks pertama masuk
            // biar urutannya bener: tool dulu, baru teks agent
            streamingPos = -1;
            scrollToBottom();
        });
        new Thread(() -> {
            try {
                client.promptWithImage(sessionId, fText, img64, imgMime);
            } catch (Exception e) {
                ui(() -> {
                    addSystem("Send failed: " + e.getMessage());
                    working = false;
                    updateSendButton();
                });
            }
        }, "goose-prompt").start();
        } catch (Exception e) {
            Toast.makeText(this, "sendPrompt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // GooseAcpClient.Listener (called on reader thread)
    // ------------------------------------------------------------------

    @Override
    public void onMessageChunk(String messageId, String text) {
        if (text == null || text.isEmpty()) return;
        ui(() -> {
            // Lazy-create bubble agent pas teks pertama masuk
            if (streamingPos < 0) {
                streamingPos = adapter.lastPosition() + 1;
                adapter.add(new ChatMessage(ChatMessage.Type.AGENT, ""));
            }
            if (streamingPos >= 0) {
                adapter.appendText(streamingPos, text);
                scrollToBottom();
            }
        });
    }

    @Override
    public void onToolCall(String toolCallId, String title, String rawInputJson) {
        ui(() -> {
            int pos = adapter.lastPosition() + 1;
            adapter.add(new ChatMessage(ChatMessage.Type.TOOL,
                    rawInputJson, "$ " + title + " — ● running"));
            toolPositions.put(toolCallId, pos);
            scrollToBottom();
        });
    }

    @Override
    public void onToolCallUpdate(String toolCallId, String status, String text) {
        ui(() -> {
            Integer pos = toolPositions.get(toolCallId);
            if (pos == null) return;
            String label = "⚙ " + status;
            if (text != null && !text.isEmpty()) {
                String short_ = text.length() > 400 ? text.substring(0, 400) + "…" : text;
                adapter.setText(pos, short_);
            }
            // update title via re-bind: store in message
            // (simplest: keep text, title stays)
            scrollToBottom();
        });
    }

    @Override
    public void onPermissionRequest(String requestId, String toolCallId, String title,
                                    String command, List<GooseAcpClient.PermissionOption> options) {
        ui(() -> showPermissionDialog(requestId, title, command, options));
    }

    @Override
    public void onTurnEnd(String sessionId, String stopReason) {
        ui(() -> {
            working = false;
            updateSendButton();
            streamingPos = -1;
            toolPositions.clear();
            adapter.markToolsDone();
            setStatus("Connected · " + this.sessionId + " · idle");
        });
    }

    @Override
    public void onUsage(long used, long size, double costUsd) {
        // lightweight: only update on turn end to avoid spam; ignore here
    }

    @Override
    public void onSessionTitle(String sessionId, String title) {
        // could show as system message; skip for now
    }

    @Override
    public void onError(String message) {
        ui(() -> addSystem("Error: " + message));
    }

    @Override
    public void onDisconnected() {
        ui(() -> {
            setStatus("Disconnected");
            addSystem("goose acp process ended.");
            working = false;
            updateSendButton();
        });
    }

    // ------------------------------------------------------------------
    // Permission dialog (Fase 2 minimal — Fase 3 ganti jadi inline cards)
    // ------------------------------------------------------------------

    private void showPermissionDialog(String requestId, String title, String command,
                                      List<GooseAcpClient.PermissionOption> options) {
        // Fallback: jangan pernah bikin user stuck. Kalo agent nggak kirim options,
        // sediain default allow_once / reject_once.
        if (options == null || options.isEmpty()) {
            options = new ArrayList<>();
            options.add(new GooseAcpClient.PermissionOption("allow_once", "allow_once", "allow_once"));
            options.add(new GooseAcpClient.PermissionOption("reject_once", "reject_once", "reject_once"));
        }
        final List<GooseAcpClient.PermissionOption> opts = options;

        // Pakai tombol eksplisit (bukan setItems) — lebih reliable di semua device.
        // Mapping: cari allow_once, reject_once, allow_always; sisanya diabaikan.
        String allowOnce = findOption(opts, "allow_once");
        String rejectOnce = findOption(opts, "reject_once");
        String allowAlways = findOption(opts, "allow_always");
        if (allowOnce == null && !opts.isEmpty()) allowOnce = opts.get(0).optionId;
        if (rejectOnce == null && opts.size() > 1) rejectOnce = opts.get(1).optionId;

        final String fAllow = allowOnce;
        final String fReject = rejectOnce;
        final String fAlways = allowAlways;

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(command.isEmpty() ? "Allow this tool call?" : command)
                .setCancelable(false);
        if (fAllow != null) {
            b.setPositiveButton(prettyOption(fAllow), (d, w) -> replyPermission(requestId, fAllow));
        }
        if (fReject != null) {
            b.setNegativeButton(prettyOption(fReject), (d, w) -> replyPermission(requestId, fReject));
        }
        if (fAlways != null) {
            b.setNeutralButton(prettyOption(fAlways), (d, w) -> replyPermission(requestId, fAlways));
        }
        // Safety: kalo nggak ada satupun tombol, kasih default
        if (fAllow == null && fReject == null && fAlways == null) {
            b.setPositiveButton("Allow once", (d, w) -> replyPermission(requestId, "allow_once"));
            b.setNegativeButton("Deny", (d, w) -> replyPermission(requestId, "reject_once"));
        }
        b.show();
    }

    private String findOption(List<GooseAcpClient.PermissionOption> opts, String id) {
        for (GooseAcpClient.PermissionOption o : opts) {
            if (id.equals(o.optionId)) return o.optionId;
        }
        return null;
    }

    private void replyPermission(String requestId, String optionId) {
        new Thread(() -> {
            try {
                client.respondPermission(requestId, optionId);
            } catch (Exception e) {
                ui(() -> Toast.makeText(this,
                        "Permission reply failed: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        }, "goose-permit").start();
    }

    private String prettyOption(String optionId) {
        switch (optionId) {
            case "allow_always": return "Allow always";
            case "allow_once": return "Allow once";
            case "reject_once": return "Deny once";
            case "reject_always": return "Deny always";
            default: return optionId;
        }
    }

    // ------------------------------------------------------------------
    // Sessions (new / resume / delete)
    // ------------------------------------------------------------------

    private void showSessionsDialog() {
        if (client == null) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        setStatus("Loading sessions…");
        new Thread(() -> {
            List<GooseAcpClient.SessionEntry> sessions = client.listSessions(15000);
            ui(() -> showSessionList(sessions));
        }, "goose-list").start();
    }

    private void showSessionList(List<GooseAcpClient.SessionEntry> sessions) {
        String[] names = new String[sessions.size() + 1];
        names[0] = "＋ New session";
        for (int i = 0; i < sessions.size(); i++) {
            GooseAcpClient.SessionEntry s = sessions.get(i);
            String label = s.title;
            if (s.sessionId.equals(sessionId)) label += " (current)";
            names[i + 1] = label;
        }
        new AlertDialog.Builder(this)
                .setTitle("Sessions")
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        startNewSession();
                    } else {
                        showSessionOptions(sessions.get(which - 1));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
        setStatus("Connected · " + sessionId + " · idle");
    }

    private void showSessionOptions(GooseAcpClient.SessionEntry s) {
        new AlertDialog.Builder(this)
                .setTitle(s.title)
                .setMessage(s.sessionId)
                .setPositiveButton("Resume", (d, w) -> resumeSession(s))
                .setNegativeButton("Delete", (d, w) -> confirmDeleteSession(s))
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void startNewSession() {
        if (client == null || working) return;
        setStatus("Creating session…");
        new Thread(() -> {
            GooseAcpClient.SessionInfo info = client.newSession(WORK_DIR, 20000);
            ui(() -> {
                if (info == null) {
                    setStatus("Session creation failed");
                    addSystem("Could not create session.");
                    return;
                }
                switchToSession(info.sessionId, info.modeId);
            });
        }, "goose-newsession").start();
    }

    private void resumeSession(GooseAcpClient.SessionEntry s) {
        if (client == null || working) return;
        if (s.sessionId.equals(sessionId)) return;
        setStatus("Resuming session…");
        new Thread(() -> {
            GooseAcpClient.SessionInfo info =
                    client.loadSession(s.sessionId, s.cwd.isEmpty() ? WORK_DIR : s.cwd, 20000);
            ui(() -> {
                if (info == null) {
                    setStatus("Resume failed");
                    addSystem("Could not resume session " + s.sessionId);
                    return;
                }
                switchToSession(info.sessionId, info.modeId);
                loadHistory(s.sessionId);
            });
        }, "goose-resume").start();
    }

    private void confirmDeleteSession(GooseAcpClient.SessionEntry s) {
        new AlertDialog.Builder(this)
                .setTitle("Delete session?")
                .setMessage(s.title + "\n" + s.sessionId)
                .setPositiveButton("Delete", (d, w) -> deleteSession(s))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSession(GooseAcpClient.SessionEntry s) {
        new Thread(() -> {
            boolean ok = client.deleteSession(s.sessionId, 15000);
            ui(() -> {
                Toast.makeText(this, ok ? "Session deleted" : "Delete failed",
                        Toast.LENGTH_SHORT).show();
                if (ok && s.sessionId.equals(sessionId)) {
                    startNewSession();
                }
            });
        }, "goose-delete").start();
    }

    private void switchToSession(String newSessionId, String modeId) {
        sessionId = newSessionId;
        streamingPos = -1;
        toolPositions.clear();
        adapter.clear();
        // Simpan buat auto-resume
        getPrefs().edit().putString("last_session_id", newSessionId).apply();
        setStatus("Connected · mode: " + modeId + " · " + sessionId);
        addSystem("Session ready (" + sessionId + "). Ask anything.");
    }

    // ------------------------------------------------------------------
    // Output files (download from chroot)
    // ------------------------------------------------------------------

    // Current browser path for the file explorer
    private String browserPath = GooseFiles.GOOSE_DIR;

    // Guard biar history cuma di-load sekali per sesi (bukan per activity)
    // TODO: rollback - guard dihapus, balik ke versi simple

    
    private static final String GOOSE_PROFILE_DIR = "/sdcard/Astro/goose/profile/";

    /** Load profiles from /sdcard/Astro/goose/profile/*.json */
    private java.util.List<String[]> loadProfiles() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            java.io.File dir = new java.io.File(GOOSE_PROFILE_DIR);
            if (!dir.exists()) dir.mkdirs();
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                for (java.io.File f : files) {
                    try {
                        String t = new String(java.nio.file.Files.readAllBytes(f.toPath()));
                        String name = extractJson(t, "name");
                        String provider = extractJson(t, "provider");
                        String model = extractJson(t, "model");
                        if (name.isEmpty()) name = f.getName().replace(".json", "");
                        if (!provider.isEmpty() && !model.isEmpty())
                            out.add(new String[]{name, provider, model});
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private String extractJson(String json, String key) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    /** Save a profile JSON to the profile dir. */
    private boolean saveProfileFile(String name, String provider, String model) {
        try {
            java.io.File dir = new java.io.File(GOOSE_PROFILE_DIR);
            if (!dir.exists()) dir.mkdirs();
            String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safe.isEmpty()) return false;
            String json = "{\"name\": \"" + name.replace("\"", "") + "\", "
                + "\"provider\": \"" + provider.replace("\"", "") + "\", "
                + "\"model\": \"" + model.replace("\"", "") + "\"}";
            java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, safe + ".json"));
            w.write(json);
            w.close();
            return true;
        } catch (Exception e) { return false; }
    }

    /** Profile-based model switcher: profile (provider+model) + reasoning level. */
    private void showProfileDialog() {
        new Thread(() -> {
            final java.util.List<String[]> profiles = loadProfiles();
            String curEffort = "medium";
            try {
                id.hawx.astro.utils.Core core = new id.hawx.astro.utils.Core(this);
                java.util.ArrayList<String> out = core.customChrootCommand(
                    "grep -m1 '^GOOSE_THINKING_EFFORT:' /root/.config/goose/config.yaml 2>/dev/null", true);
                for (String l : out) {
                    if (l.contains(":")) curEffort = l.split(":", 2)[1].trim();
                }
            } catch (Exception ignored) {}
            final String fEffort = curEffort;

            runOnUiThread(() -> {
                android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
                layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                int pad = (int) (16 * getResources().getDisplayMetrics().density);
                layout.setPadding(pad, pad, pad, pad);

                // Profile spinner
                android.widget.TextView pl = new android.widget.TextView(this);
                pl.setText("Profile");
                pl.setTextSize(16);
                layout.addView(pl);

                java.util.List<String> names = new java.util.ArrayList<>();
                for (String[] pr : profiles) names.add(pr[0] + "  (" + pr[1] + " / " + pr[2] + ")");
                if (names.isEmpty()) names.add("(no profiles yet — tap + to add)");

                android.widget.Spinner pSpin = new android.widget.Spinner(this);
                android.widget.ArrayAdapter<String> pAd = new android.widget.ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, names);
                pAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                pSpin.setAdapter(pAd);
                layout.addView(pSpin);

                // Add-profile button
                android.widget.Button addBtn = new android.widget.Button(this);
                addBtn.setText("+ New profile");
                layout.addView(addBtn);

                // Reasoning spinner
                final String[] efforts = {"low", "medium", "high"};
                android.widget.TextView el = new android.widget.TextView(this);
                el.setText("Reasoning");
                el.setTextSize(16);
                layout.addView(el);
                android.widget.Spinner eSpin = new android.widget.Spinner(this);
                android.widget.ArrayAdapter<String> eAd = new android.widget.ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, efforts);
                eAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                eSpin.setAdapter(eAd);
                int eIdx = java.util.Arrays.asList(efforts).indexOf(fEffort);
                if (eIdx >= 0) eSpin.setSelection(eIdx);
                layout.addView(eSpin);

                android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                    .setTitle("Model Profile")
                    .setView(layout)
                    .setPositiveButton("Apply", (d, w) -> {
                        int pos = pSpin.getSelectedItemPosition();
                        if (profiles.isEmpty() || pos < 0 || pos >= profiles.size()) {
                            android.widget.Toast.makeText(this, "No profile selected",
                                android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String[] pr = profiles.get(pos);
                        String newEffort = efforts[eSpin.getSelectedItemPosition()];
                        applyProfile(pr[1], pr[2], newEffort);
                    })
                    .setNegativeButton("Cancel", null)
                    .create();

                addBtn.setOnClickListener(v -> showNewProfileDialog(() -> {
                    dlg.dismiss();
                    showProfileDialog();
                }));

                dlg.show();
            });
        }).start();
    }

    /** Dialog to create a new profile (name + provider + model). */
    private void showNewProfileDialog(Runnable onDone) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        android.widget.EditText nInput = new android.widget.EditText(this);
        nInput.setHint("Profile name (e.g. Cepat)");
        nInput.setSingleLine(true);
        layout.addView(nInput);

        android.widget.EditText pInput = new android.widget.EditText(this);
        pInput.setHint("Provider (e.g. google)");
        pInput.setSingleLine(true);
        layout.addView(pInput);

        android.widget.EditText mInput = new android.widget.EditText(this);
        mInput.setHint("Model (e.g. gemini-3.5-flash-lite)");
        mInput.setSingleLine(true);
        layout.addView(mInput);

        new android.app.AlertDialog.Builder(this)
            .setTitle("New profile")
            .setView(layout)
            .setPositiveButton("Save", (d, w) -> {
                String n = nInput.getText().toString().trim();
                String p = pInput.getText().toString().trim();
                String m = mInput.getText().toString().trim();
                if (n.isEmpty() || p.isEmpty() || m.isEmpty()) {
                    android.widget.Toast.makeText(this, "Fill all fields",
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (saveProfileFile(n, p, m)) {
                    android.widget.Toast.makeText(this, "Profile saved: " + n,
                        android.widget.Toast.LENGTH_SHORT).show();
                    if (onDone != null) onDone.run();
                } else {
                    android.widget.Toast.makeText(this, "Failed to save",
                        android.widget.Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Apply profile: write provider/model + thinking effort to goose config. */
    private void applyProfile(String provider, String model, String effort) {
        new Thread(() -> {
            try {
                id.hawx.astro.utils.Core core = new id.hawx.astro.utils.Core(this);
                String safeP = provider.replace("'", "");
                String safeM = model.replace("'", "");
                String safeE = effort.replace("'", "");
                String py = "python3 - << 'PYEOF'\n"
                    + "import re\n"
                    + "p='/root/.config/goose/config.yaml'\n"
                    + "t=open(p).read()\n"
                    + "t=re.sub(r'^active_provider:.*', 'active_provider: " + safeP + "', t, flags=re.M)\n"
                    + "t=re.sub(r'^GOOSE_THINKING_EFFORT:.*', 'GOOSE_THINKING_EFFORT: " + safeE + "', t, flags=re.M)\n"
                    + "t=re.sub(r'(?m)^(  " + safeP + ":\\s*\\n(?:.*\\n)*?    model: ).*$', r'\\1" + safeM + "', t)\n"
                    + "open(p,'w').write(t)\n"
                    + "print('ok')\n"
                    + "PYEOF\n";
                core.customChrootCommand(py, true);
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                    "Applied: " + safeM + " (" + safeE + ")", android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                    "Failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }

private void showFilesDialog() {
        GooseFiles.ensureGooseDir();
        browserPath = GooseFiles.GOOSE_DIR;
        showBrowserDialog();
    }

    /** Open file with external app via Android chooser (e.g. MT Manager). */
    private void showFullscreenImage(String path) {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.getSettings().setBuiltInZoomControls(true);
        wv.getSettings().setDisplayZoomControls(false);
        wv.getSettings().setLoadWithOverviewMode(true);
        wv.getSettings().setUseWideViewPort(true);
        wv.getSettings().setAllowFileAccess(true);
        wv.loadUrl("file://" + path);
        wv.setOnLongClickListener(v -> { d.dismiss(); return true; });
        d.setContentView(wv);
        d.show();
        android.widget.Toast.makeText(this, "Pinch to zoom, long-press to close", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void openGhostFile(File file) {
        String n = file.getName().toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) {
            android.content.Intent i = new android.content.Intent(this, GhostVizActivity.class);
            i.putExtra(GhostVizActivity.EXTRA_PATH, file.getAbsolutePath());
            i.putExtra(GhostVizActivity.EXTRA_TITLE, file.getName());
            startActivity(i);
        } else openWith(file);
    }

    private void openWith(File file) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".provider", file);
            String mime = getContentResolver().getType(uri);
            if (mime == null) {
                String n = file.getName().toLowerCase();
                if (n.endsWith(".html") || n.endsWith(".htm")) mime = "text/html";
                else if (n.endsWith(".md")) mime = "text/markdown";
                else if (n.endsWith(".json")) mime = "application/json";
                else if (n.endsWith(".txt") || n.endsWith(".log")) mime = "text/plain";
                else mime = "*/*";
            }
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(intent,
                    "Open with..."));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Simple file browser dialog with back navigation. */
    private void showBrowserDialog() {
        List<File> items = GooseFiles.listDir(browserPath);
        List<String> names = new ArrayList<>();
        List<File> files = new ArrayList<>();

        // ".." buat mundur (kecuali udah di root sdcard)
        File cur = new File(browserPath);
        File parent = cur.getParentFile();
        boolean canUp = parent != null && browserPath.startsWith("/sdcard/Astro");
        if (canUp) {
            names.add("📁 ..");
            files.add(parent);
        }
        for (File f : items) {
            if (f.isDirectory()) {
                names.add("📁 " + f.getName());
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".html") || n.endsWith(".htm")) {
                    names.add("📊 " + f.getName());
                } else {
                    names.add("📄 " + f.getName());
                }
            }
            files.add(f);
        }

        if (names.isEmpty()) {
            names.add("(empty)");
            files.add(null);
        }

        String title = "Files: " + browserPath.replace("/sdcard/", "~/");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    File sel = files.get(which);
                    if (sel == null) return;
                    if (sel.isDirectory()) {
                        browserPath = sel.getAbsolutePath();
                        showBrowserDialog();
                    } else {
                        String n = sel.getName().toLowerCase();
                        if (n.endsWith(".html") || n.endsWith(".htm")) {
                            // Kasih pilihan: Ghost viewer atau app lain
                            new AlertDialog.Builder(this)
                                    .setTitle(sel.getName())
                                    .setItems(new String[]{"👁️ Open in Goose viewer",
                                            "📂 Open with other app..."},
                                            (d2, which2) -> {
                                        if (which2 == 0) {
                                            android.content.Intent i =
                                                    new android.content.Intent(
                                                    this, GhostVizActivity.class);
                                            i.putExtra(GhostVizActivity.EXTRA_PATH,
                                                    sel.getAbsolutePath());
                                            i.putExtra(GhostVizActivity.EXTRA_TITLE,
                                                    sel.getName());
                                            startActivity(i);
                                        } else {
                                            openWith(sel);
                                        }
                                    })
                                    .show();
                        } else {
                            openWith(sel);
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }


    /** Open an HTML file in the WebView visualization activity. */
    private void openViz(String filename) {
        // Cek dulu di /sdcard/Astro/ (langsung), baru coba export dari chroot
        File sdcardFile = new File("/sdcard/Astro/" + filename);
        if (sdcardFile.exists()) {
            android.content.Intent i = new android.content.Intent(
                    this, GhostVizActivity.class);
            i.putExtra(GhostVizActivity.EXTRA_PATH, sdcardFile.getAbsolutePath());
            i.putExtra(GhostVizActivity.EXTRA_TITLE, filename);
            startActivity(i);
            return;
        }
        // Fallback: export dari chroot
        new Thread(() -> {
            String exported = GooseFiles.exportFile(filename);
            ui(() -> {
                if (exported != null) {
                    android.content.Intent i = new android.content.Intent(
                            this, GhostVizActivity.class);
                    i.putExtra(GhostVizActivity.EXTRA_PATH, exported);
                    i.putExtra(GhostVizActivity.EXTRA_TITLE, filename);
                    startActivity(i);
                } else {
                    Toast.makeText(this, "Failed to open " + filename,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "goose-viz").start();
    }

    private void exportFile(String filename) {
        new Thread(() -> {
            String path = GooseFiles.exportFile(filename);
            ui(() -> {
                if (path != null) {
                    Toast.makeText(this, "Saved to " + path, Toast.LENGTH_LONG).show();
                    addSystem("File exported: " + path);
                } else {
                    Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show();
                }
            });
        }, "goose-export").start();
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    private void setStatus(String s) {
        statusView.setText(s);
    }

    private void addSystem(String s) {
        adapter.add(new ChatMessage(ChatMessage.Type.SYSTEM, s));
        scrollToBottom();
    }

    private void scrollToBottom() {
        int last = adapter.lastPosition();
        if (last >= 0) messagesView.smoothScrollToPosition(last);
    }
}
