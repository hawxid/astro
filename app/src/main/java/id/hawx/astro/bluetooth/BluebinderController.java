package id.hawx.astro.bluetooth;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import id.hawx.astro.utils.Core;

import java.util.List;

/**
 * Single source of truth for the Bluebinder daemon (chroot) shared by the
 * Bluetooth menu and the Keys+Mouse screen.
 *
 * Unlike the old fragment-local boolean, every state here comes from a real
 * check: the daemon process (pgrep) and the hci0 interface state (hciconfig).
 * The bracket trick in the pgrep pattern keeps pgrep from matching its own
 * command line.
 */
public final class BluebinderController {

    public interface StatusCallback {
        void onStatus(boolean running, boolean hciUp, @NonNull String detail);
    }

    public interface DoneCallback {
        void onDone(boolean ok, @NonNull String detail);
    }

    private final Core core;
    private final Handler main = new Handler(Looper.getMainLooper());

    public BluebinderController(@NonNull Context context) {
        this.core = new Core(context.getApplicationContext());
    }

    public void checkStatus(@NonNull StatusCallback cb) {
        new Thread(() -> {
            boolean running = isRunningOnWorker();
            boolean hciUp = isHciUpOnWorker();
            String detail = "bluebinder=" + (running ? "jalan" : "mati")
                    + ", hci0=" + (hciUp ? "UP" : "DOWN");
            post(() -> cb.onStatus(running, hciUp, detail));
        }, "bluebinder-status").start();
    }

    public void start(@NonNull DoneCallback cb) {
        new Thread(() -> {
            core.customChrootCommand(
                    "nohup bluebinder > /tmp/bluebinder.log 2>&1 & echo 'started pid '$!", true);
            sleepQuietly(2000);
            core.customChrootCommand("hciconfig hci0 up", true);
            sleepQuietly(1000);
            boolean running = isRunningOnWorker();
            boolean hciUp = isHciUpOnWorker();
            boolean ok = running && hciUp;
            String detail = ok
                    ? "Bluebinder jalan, hci0 UP"
                    : "Gagal: bluebinder=" + (running ? "jalan" : "mati")
                            + ", hci0=" + (hciUp ? "UP" : "DOWN")
                            + " — cek /tmp/bluebinder.log";
            post(() -> cb.onDone(ok, detail));
        }, "bluebinder-start").start();
    }

    public void stop(@NonNull DoneCallback cb) {
        new Thread(() -> {
            core.customChrootCommand("pkill -f bluebinder; echo stopped", true);
            sleepQuietly(500);
            boolean running = isRunningOnWorker();
            post(() -> cb.onDone(!running, running ? "Bluebinder masih jalan" : "Bluebinder berhenti"));
        }, "bluebinder-stop").start();
    }

    /** Start (if needed) + bring hci0 up, then report the verified end state. */
    public void ensureActive(@NonNull DoneCallback cb) {
        new Thread(() -> {
            if (isRunningOnWorker() && isHciUpOnWorker()) {
                post(() -> cb.onDone(true, "Bluebinder sudah jalan, hci0 UP"));
                return;
            }
            if (!isRunningOnWorker()) {
                core.customChrootCommand(
                        "nohup bluebinder > /tmp/bluebinder.log 2>&1 & echo 'started pid '$!", true);
                sleepQuietly(2000);
            }
            core.customChrootCommand("hciconfig hci0 up", true);
            sleepQuietly(1000);
            boolean running = isRunningOnWorker();
            boolean hciUp = isHciUpOnWorker();
            boolean ok = running && hciUp;
            String detail = ok
                    ? "Bluebinder jalan, hci0 UP"
                    : "Gagal: bluebinder=" + (running ? "jalan" : "mati")
                            + ", hci0=" + (hciUp ? "UP" : "DOWN")
                            + " — cek /tmp/bluebinder.log";
            post(() -> cb.onDone(ok, detail));
        }, "bluebinder-ensure").start();
    }

    private boolean isRunningOnWorker() {
        List<String> out = core.customChrootCommand("pgrep -f 'bluebinde[r]' || true", true);
        if (out == null) return false;
        for (String line : out) {
            if (line != null && !line.trim().isEmpty()) return true;
        }
        return false;
    }

    private boolean isHciUpOnWorker() {
        List<String> out = core.customChrootCommand("hciconfig", true);
        if (out == null) return false;
        boolean seenHci0 = false;
        for (String line : out) {
            if (line == null) continue;
            if (line.contains("hci0:")) seenHci0 = true;
            if (seenHci0 && line.contains("UP RUNNING")) return true;
        }
        return false;
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
