package id.hawx.astro.metasploit.utils;

public interface MsfUtilsHelper {
    void onNewLineConsole(String line);
    void onNewLineShell(String line);
    /**
     * Called for '\r'-terminated output (in-place update).
     * Default falls back to onNewLine for backward compatibility.
     */
    default void onCarriageReturnConsole(String line) {
        onNewLineConsole(line);
    }
    default void onCarriageReturnShell(String line) {
        onNewLineShell(line);
    }
}
