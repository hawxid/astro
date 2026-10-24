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
        // Toggle "GUI melayang (xrun)" tinggal di menu Tools terminal
        // bawaan; terminal kirim broadcast, sisi app yang menyalakan
        // watcher + deploy shim lewat XSessionManager.
        androidx.core.content.ContextCompat.registerReceiver(this,
                new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, android.content.Intent i) {
                boolean en = i.getBooleanExtra("enabled", false);
                // Ronde perbaikan: daftar PID dibawa pengirim
                // (NeoTermActivity); bisa absen dari pengirim lama.
                java.util.ArrayList<Integer> pids =
                        i.getIntegerArrayListExtra("pids");
                id.hawx.astro.x11.XSessionManager.get(c)
                        .setTerminalFloat(en, pids);
                id.hawx.astro.x11.X11DebugLog.log("XRUN",
                        "toggle dari menu terminal -> " + en);
            }
        }, new android.content.IntentFilter("id.hawx.astro.TOGGLE_TERMINAL_FLOAT"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        // R2: tab terminal lahir/tutup -> klaim/bersihkan display per-tab
        // (modul terminal tak boleh merujuk kelas app; sinyal lewat
        // broadcast paket-lokal, pola yang sama dengan toggle).
        androidx.core.content.ContextCompat.registerReceiver(this,
                new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(android.content.Context c,
                                          android.content.Intent i) {
                        int pid = i.getIntExtra("pid", 0);
                        // Ronde perbaikan: receipt dicatat dulu supaya
                        // log bisa membedakan "broadcast tak sampai"
                        // vs "klaim gagal".
                        id.hawx.astro.x11.X11DebugLog.log("TAB",
                                "broadcast TAB_ADDED diterima pid=" + pid);
                        if (pid > 0) {
                            id.hawx.astro.x11.XSessionManager.get(c)
                                    .onTerminalTabAdded(pid);
                        }
                    }
                }, new android.content.IntentFilter("id.hawx.astro.TERMINAL_TAB_ADDED"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        androidx.core.content.ContextCompat.registerReceiver(this,
                new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(android.content.Context c,
                                          android.content.Intent i) {
                        int pid = i.getIntExtra("pid", 0);
                        id.hawx.astro.x11.X11DebugLog.log("TAB",
                                "broadcast TAB_REMOVED diterima pid=" + pid);
                        if (pid > 0) {
                            id.hawx.astro.x11.XSessionManager.get(c)
                                    .onTerminalTabRemoved(pid);
                        }
                    }
                }, new android.content.IntentFilter("id.hawx.astro.TERMINAL_TAB_REMOVED"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        // Ronde perbaikan: penolakan filter onTabAdded (modul
        // terminal) dititipkan lewat broadcast ini dan dicatat
        // sebagai baris TAB — tanpa logger baru di modul terminal.
        androidx.core.content.ContextCompat.registerReceiver(this,
                new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(android.content.Context c,
                                          android.content.Intent i) {
                        int pid = i.getIntExtra("pid", 0);
                        String path = i.getStringExtra("path");
                        id.hawx.astro.x11.X11DebugLog.log("TAB",
                                "tab ditolak filter: pid=" + pid
                                        + " path=" + path);
                    }
                }, new android.content.IntentFilter("id.hawx.astro.TERMINAL_TAB_DEBUG"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        androidx.core.content.ContextCompat.registerReceiver(this,
                new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(android.content.Context c,
                                          android.content.Intent i) {
                        id.hawx.astro.x11.window.XOverlayWindows.releaseFocus();
                    }
                }, new android.content.IntentFilter("id.hawx.astro.RELEASE_OVERLAY_FOCUS"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
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

    // Activity yang lagi di depan — dipakai jendela overlay X buat
    // konteks host-nya (kelahiran terminal: NeoTermActivity).
    private static volatile Activity sForeground;

    public static Activity foregroundActivity() {
        return sForeground;
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
            public void onActivityResumed(@NonNull Activity activity) {
                sForeground = activity;
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (sForeground == activity) sForeground = null;
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle b) {}

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }
}
