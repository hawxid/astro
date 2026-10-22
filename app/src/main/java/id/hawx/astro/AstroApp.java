package id.hawx.astro;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import id.hawx.astro.logger.LogEntry;
import id.hawx.astro.logger.LogStore;
import id.hawx.astro.ota.NotificationCenter;
import id.hawx.astro.utils.Utils;

public class AstroApp extends com.termux.x11.LorieApp {

    @Override
    public void onCreate() {
        // DIAGNOSTIK SEMENTARA: tangkap stack trace crash ke log debug X11.
        final Thread.UncaughtExceptionHandler prevCrash =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                id.hawx.astro.x11.X11DebugLog.log("CRASH",
                        t.getName() + ": " + android.util.Log.getStackTraceString(e));
            } catch (Throwable ignored) {}
            if (prevCrash != null) prevCrash.uncaughtException(t, e);
        });
        super.onCreate();
        LogStore store = LogStore.init(this);
        store.add(LogEntry.INFO, "session", "==== Astro " + BuildConfig.VERSION_NAME
                + " session start ====");
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        store.add(LogEntry.INFO, "session", "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE
                + " (" + abi + ")");
        com.stryker.terminal.bridge.StrykerLog.install((level, tag, message, error) -> {
            LogStore s = LogStore.peek();
            if (s == null) return;
            String text = message;
            if (error != null) {
                text = (text == null || text.isEmpty() ? "" : text + System.lineSeparator())
                        + android.util.Log.getStackTraceString(error);
            }
            s.add(level, tag, text);
        });

        NotificationCenter.ensureChannel(this);
        // Hardening 2026-10-22 (perintah Hawx): poller OTA upstream
        // dimatikan permanen. Batalkan pekerjaan berkala warisan build
        // lama supaya tidak pernah jalan lagi di perangkat.
        try {
            androidx.work.WorkManager.getInstance(this)
                    .cancelUniqueWork("stryker_update_poll");
        } catch (Exception ignored) {}
        applyEdgeToEdgeInsets();
        com.stryker.terminal.backend.TerminalSession.setShellChooser(() -> {
            try {
                id.hawx.astro.utils.Core core = new id.hawx.astro.utils.Core(this);
                if (!id.hawx.astro.engine.EngineType.isChosen(core)) return null;
                return id.hawx.astro.engine.EngineType.isRootless(core) ? "ssh:guest" : null;
            } catch (Throwable t) {
                return null;
            }
        });
    }

    private void applyEdgeToEdgeInsets() {
        registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                Utils.applySystemBarInsets(activity);
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {}

            @Override
            public void onActivityResumed(@NonNull Activity activity) {}

            @Override
            public void onActivityPaused(@NonNull Activity activity) {}

            @Override
            public void onActivityStopped(@NonNull Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle b) {}

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }
}
