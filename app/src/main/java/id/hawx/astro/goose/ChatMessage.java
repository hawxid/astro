package id.hawx.astro.goose;

/** Single chat message (user, agent, tool, or system). */
public class ChatMessage {

    public enum Type { USER, AGENT, TOOL, SYSTEM }

    public final Type type;
    public String text;
    public String title; // tool name / status for TOOL messages
    public boolean expanded = false; // for collapsible TOOL output

    public ChatMessage(Type type, String text) {
        this(type, text, null);
    }

    public ChatMessage(Type type, String text, String title) {
        this.type = type;
        this.text = text;
        this.title = title;
        // TOOL messages start collapsed
        this.expanded = type != Type.TOOL;
    }
}
