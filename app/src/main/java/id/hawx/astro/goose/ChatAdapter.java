package id.hawx.astro.goose;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textview.MaterialTextView;
import id.hawx.astro.R;

import java.util.ArrayList;
import java.util.List;

/** RecyclerView adapter for goose chat messages. */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

    public interface OnFileClickListener { void onFileClick(String path); }
    public interface OnImageClickListener { void onImageClick(String path); }
    private OnImageClickListener imageClickListener;
    public void setOnImageClickListener(OnImageClickListener l) { imageClickListener = l; }
    private OnFileClickListener fileClickListener;
    public void setOnFileClickListener(OnFileClickListener l) { fileClickListener = l; }

    private static boolean isGoosePath(String cp) {
        return cp.startsWith("/sdcard/Stryker/goose/") || cp.startsWith("/storage/emulated/0/Stryker/goose/");
    }

    private final List<ChatMessage> messages = new ArrayList<>();

    static class VH extends RecyclerView.ViewHolder {
        final LinearLayout bubble;
        final MaterialTextView titleView;
        final MaterialTextView bodyView;
        final android.widget.ImageView mediaView;
        final android.widget.Button fileCard;
        View.OnClickListener expandClickListener;

        VH(@NonNull View itemView) {
            super(itemView);
            bubble = itemView.findViewById(R.id.chat_bubble);
            titleView = itemView.findViewById(R.id.chat_title);
            bodyView = itemView.findViewById(R.id.chat_body);
            mediaView = new android.widget.ImageView(itemView.getContext());
            mediaView.setVisibility(View.GONE);
            mediaView.setAdjustViewBounds(true);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = 8;
            mediaView.setLayoutParams(mlp);
            mediaView.setMinimumHeight(200);
            mediaView.setMinimumWidth(200);
            mediaView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            bubble.addView(mediaView);
            fileCard = new android.widget.Button(itemView.getContext());
            fileCard.setVisibility(View.GONE);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            flp.topMargin = 8;
            fileCard.setLayoutParams(flp);
            bubble.addView(fileCard);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        VH h = new VH(v);
        // Expand listener: dipasang di sini (sekali) + di onBind (backup).
        // TOOL message nggak selectable, jadi tap reliable.
        View.OnClickListener expandListener = view -> {
            int pos = h.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (pos < 0 || pos >= messages.size()) return;
            ChatMessage m = messages.get(pos);
            if (m.type == ChatMessage.Type.TOOL) {
                m.expanded = !m.expanded;
                notifyItemChanged(pos);
            }
        };
        h.expandClickListener = expandListener;
        h.titleView.setOnClickListener(expandListener);
        h.bubble.setOnClickListener(expandListener);
        h.bodyView.setOnClickListener(expandListener);
        h.titleView.setClickable(true);
        h.bubble.setClickable(true);
        // Long-press di TOOL message = copy full text ke clipboard
        View.OnLongClickListener copyListener = view -> {
            int pos = h.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            if (pos < 0 || pos >= messages.size()) return false;
            ChatMessage m = messages.get(pos);
            if (m.type == ChatMessage.Type.TOOL && m.text != null) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) view.getContext()
                                .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "tool_output", m.text));
                android.widget.Toast.makeText(view.getContext(),
                        "Tool output copied", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        };
        h.titleView.setOnLongClickListener(copyListener);
        h.bubble.setOnLongClickListener(copyListener);
        h.bodyView.setOnLongClickListener(copyListener);
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ChatMessage m = messages.get(position);

        // Collapsible TOOL output: collapsed = first 5 lines + hint
        String displayText = m.text != null ? m.text : "";
        if (m.type == ChatMessage.Type.USER) {
            // 24: blok "File: <nama>" + fenced content dari sessions.db (resume)
            // bisa muncul di tengah teks, bukan cuma di awal pesan. Ganti tiap
            // blok fence jadi kartu file; teks user di sekitarnya tetap utuh.
            java.util.regex.Matcher fbm = java.util.regex.Pattern.compile(
                "File: ([^\\n\\r]+)\\r?\\n```[\\s\\S]*?(```|\\u2026\\[truncated\\])")
                .matcher(displayText);
            if (fbm.find()) {
                StringBuffer fbsb = new StringBuffer();
                do {
                    String fname = fbm.group(1).trim();
                    String safe = fname.replaceAll("[^a-zA-Z0-9._-]", "_");
                    if (safe.isEmpty()) safe = "file";
                    String upath = "/sdcard/Stryker/goose/uploads/" + safe;
                    fbm.appendReplacement(fbsb, java.util.regex.Matcher.quoteReplacement(
                        "\uD83D\uDCC4 " + fname + "\n[file:" + upath + "]"));
                } while (fbm.find());
                fbm.appendTail(fbsb);
                displayText = fbsb.toString();
            }
        }
        if (m.type == ChatMessage.Type.TOOL && !m.expanded) {
            String[] lines = displayText.split("\n");
            if (lines.length > 5) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 5; i++) sb.append(lines[i]).append("\n");
                sb.append("… (").append(lines.length - 5)
                  .append(" more lines — tap to expand)");
                displayText = sb.toString();
            } else if (!displayText.isEmpty()) {
                displayText += "\n(tap to expand)";
            }
        }
        String mediaPath = null;
        String filePath = null;
        String fileName = null;
        if (m.type == ChatMessage.Type.AGENT || m.type == ChatMessage.Type.USER) {
            java.util.regex.Matcher autoM = java.util.regex.Pattern.compile(
                "(/sdcard/Stryker/goose/[^\\s`'\"\\]]+?\\.(png|jpg|jpeg|gif))",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(displayText);
            if (autoM.find()) {
                try {
                    java.io.File f = new java.io.File(autoM.group(1));
                    String cp = f.getCanonicalPath();
                    if (isGoosePath(cp)) mediaPath = cp;
                } catch (Exception ignored) {}
            }
            java.util.regex.Matcher im = java.util.regex.Pattern.compile("\\[img:(.+?)\\]").matcher(displayText);
            if (im.find()) {
                try {
                    java.io.File f = new java.io.File(im.group(1).trim());
                    String cp = f.getCanonicalPath();
                    String l = cp.toLowerCase();
                    if (isGoosePath(cp) && (l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".gif")))
                        mediaPath = cp;
                } catch (Exception ignored) {}
                displayText = im.replaceAll("").trim();
            }
            java.util.regex.Matcher fm = java.util.regex.Pattern.compile("\\[file:(.+?)\\]").matcher(displayText);
            if (fm.find()) {
                try {
                    java.io.File f = new java.io.File(fm.group(1).trim());
                    String cp = f.getCanonicalPath();
                    if (isGoosePath(cp)) { filePath = cp; fileName = f.getName(); }
                } catch (Exception ignored) {}
                displayText = fm.replaceAll("").trim();
            }
        }
        h.bodyView.setText(displayText);

        boolean showTitle = m.title != null && !m.title.isEmpty();
        h.titleView.setVisibility(showTitle ? View.VISIBLE : View.GONE);
        if (showTitle) h.titleView.setText(m.title);
        // Body: textIsSelectable cuma untuk USER & AGENT (biar bisa copy).
        // TOOL: monospace terminal look, nggak selectable biar tap-to-expand reliable.
        if (m.type == ChatMessage.Type.TOOL) {
            h.bodyView.setTextIsSelectable(false);
            h.bodyView.setClickable(true);
            h.bodyView.setFocusable(true);
            h.bodyView.setTypeface(android.graphics.Typeface.MONOSPACE);
            h.bodyView.setTextSize(12);
            h.bodyView.setTextColor(0xFF7EE787); // hijau terminal
        } else {
            h.bodyView.setTextIsSelectable(true);
            h.bodyView.setTypeface(android.graphics.Typeface.DEFAULT);
            h.bodyView.setTextSize(14);
            h.bodyView.setTextColor(0xFFFFFFFF);
        }

        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) h.bubble.getLayoutParams();
        switch (m.type) {
            case USER:
                lp.gravity = Gravity.END;
                h.bodyView.setBackgroundResource(R.drawable.chat_bubble_user);
                break;
            case TOOL:
                lp.gravity = Gravity.START;
                h.bodyView.setBackgroundResource(R.drawable.chat_bubble_tool);
                break;
            case SYSTEM:
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                h.bodyView.setBackgroundResource(R.drawable.chat_bubble_system);
                break;
            case AGENT:
            default:
                lp.gravity = Gravity.START;
                h.bodyView.setBackgroundResource(R.drawable.chat_bubble_agent);
                break;
        }
        h.bubble.setLayoutParams(lp);
        // Backup: pastikan expand listener kepasang (jaga-jaga kalo onCreate ke-skip)
        if (m.type == ChatMessage.Type.TOOL && h.expandClickListener != null) {
            h.titleView.setOnClickListener(h.expandClickListener);
            h.bubble.setOnClickListener(h.expandClickListener);
            h.bodyView.setOnClickListener(h.expandClickListener);
        }

        if (mediaPath != null) {
            final String fp = mediaPath;
            h.mediaView.setVisibility(View.VISIBLE);
            h.mediaView.setTag(fp);
            h.mediaView.setClickable(true);
            h.mediaView.setOnClickListener(v -> {
                if (imageClickListener != null) imageClickListener.onImageClick(fp);
            });
            new Thread(() -> {
                try {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 2;
                    final android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(fp, o);
                    h.mediaView.post(() -> {
                        if (fp.equals(h.mediaView.getTag())) {
                            if (b != null) h.mediaView.setImageBitmap(b);
                            else h.mediaView.setVisibility(View.GONE);
                        }
                    });
                } catch (Exception e) {
                    h.mediaView.post(() -> {
                        if (fp.equals(h.mediaView.getTag())) h.mediaView.setVisibility(View.GONE);
                    });
                }
            }).start();
        } else {
            h.mediaView.setTag(null);
            h.mediaView.setVisibility(View.GONE);
            h.mediaView.setImageBitmap(null);
        }

        if (filePath != null) {
            final String fp2 = filePath;
            h.fileCard.setVisibility(View.VISIBLE);
            h.fileCard.setText("\uD83D\uDCC4 " + fileName);
            h.fileCard.setOnClickListener(v -> {
                if (fileClickListener != null) fileClickListener.onFileClick(fp2);
            });
        } else h.fileCard.setVisibility(View.GONE);
    }

    /** Mark all running tools as done (update title from ● running to ✓ done). */
    public void markToolsDone() {
        boolean changed = false;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.type == ChatMessage.Type.TOOL && m.title != null &&
                m.title.contains("● running")) {
                m.title = m.title.replace("● running", "✓ done");
                notifyItemChanged(i);
                changed = true;
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void add(ChatMessage m) {
        messages.add(m);
        notifyItemInserted(messages.size() - 1);
    }

    public void clear() {
        int n = messages.size();
        messages.clear();
        if (n > 0) notifyItemRangeRemoved(0, n);
    }

    /** Append text to message at position (for streaming). */
    public void appendText(int position, String chunk) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage m = messages.get(position);
        m.text = (m.text == null ? "" : m.text) + chunk;
        notifyItemChanged(position);
    }

    /** Update text of message at position. */
    public void setText(int position, String text) {
        if (position < 0 || position >= messages.size()) return;
        messages.get(position).text = text;
        notifyItemChanged(position);
    }

    public int lastPosition() {
        return messages.size() - 1;
    }
}
