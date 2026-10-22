package id.hawx.astro.x11.window;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.core.content.ContextCompat;

import com.termux.x11.CmdEntryPoint;
import com.termux.x11.ICmdEntryInterface;
import com.termux.x11.LorieApp;
import com.termux.x11.LorieView;
import com.termux.x11.Prefs;
import com.termux.x11.XHost;
import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.TermuxX11ExtraKeys;

import static android.view.KeyEvent.KEYCODE_META_LEFT;
import static android.view.KeyEvent.KEYCODE_META_RIGHT;

/**
 * Host tertanam untuk satu display X di dalam jendela floating Astro
 * (implementasi {@link XHost} kedua setelah MainActivity lorie).
 *
 * Mengelola untuk display-nya sendiri: LorieView, tumpukan input
 * (TouchInputHandler + InputEventSender), dan koneksi Binder ke proses
 * server X — meniru alur connectToService/tryConnect milik MainActivity
 * upstream, tetapi terikat ke tag display ini ("d1", "d2", ...).
 */
public class XWindowHost implements XHost {

    public interface Listener {
        /** Minta jendela ditutup (tanpa membunuh aplikasi/display). */
        void onCloseRequested(XWindowHost host);

        /** Keadaan koneksi X berubah (buat judul/indikator jendela). */
        void onConnectionChanged(XWindowHost host, boolean connected);

        /** Minta strip softkey ditampilkan/disembunyikan untuk host ini. */
        void onStripVisibilityRequested(XWindowHost host, boolean visible);
    }

    private final Activity activity;
    private final int display;
    private final String tag;
    private final LorieView view;
    private final Prefs prefs;
    private final TouchInputHandler inputHandler;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable connectRetry = this::tryConnect;

    private ICmdEntryInterface service;
    private Listener listener;
    private BroadcastReceiver knockReceiver;
    private boolean stripVisible;

    private final IBinder.DeathRecipient deathRecipient = () -> handler.post(this::disconnectService);

    public XWindowHost(Activity activity, int display) {
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "host dibuat display=" + display);
        this.activity = activity;
        this.display = display;
        this.tag = "d" + display;
        LorieApp app = (LorieApp) activity.getApplicationContext();
        this.prefs = app.getPrefs(activity);
        this.view = new LorieView(activity, null);
        view.setHost(this);
        InputEventSender sender = new InputEventSender(this, view);
        this.inputHandler = new TouchInputHandler(this, sender);
        view.setOnKeyListener((v, keyCode, e) -> inputHandler.sendKeyEvent(e));
    }

    /** Teruskan event sentuh dari parent view (pola MainActivity upstream:
     *  listener dipasang di parent, handler menerima parent + view). */
    public boolean handleTouch(View parent, android.view.MotionEvent e) {
        return inputHandler.handleTouchEvent(parent, view, e);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public int display() {
        return display;
    }

    public LorieView view() {
        return view;
    }

    public boolean connected() {
        return view.connected();
    }

    /** Mulai mendengarkan ketukan server + ambil koneksi yang sudah pending. */
    public void attach() {
        knockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                if (!tag.equals(intent.getStringExtra(CmdEntryPoint.EXTRA_DOCUMENT_TAG))) return;
                Bundle extras = intent.getBundleExtra(null);
                if (extras == null) return;
                IBinder binder = extras.getBinder(null);
                if (binder != null) connectToService(binder);
            }
        };
        ContextCompat.registerReceiver(activity, knockReceiver,
                new IntentFilter(CmdEntryPoint.ACTION_START),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        LorieApp app = (LorieApp) activity.getApplicationContext();
        IBinder pending = app.pendingConnections.get(tag);
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "attach tag=" + tag + " pending=" + (pending != null));
        if (pending != null) connectToService(pending);
        else tryConnect();
    }

    /** Berhenti total: lepas receiver, input, dan koneksi. */
    public void detach() {
        if (knockReceiver != null) {
            try {
                activity.unregisterReceiver(knockReceiver);
            } catch (Exception ignored) {
            }
            knockReceiver = null;
        }
        handler.removeCallbacks(connectRetry);
        inputHandler.onDestroy();
        disconnectService();
    }

    // ---- koneksi (cermin MainActivity upstream) ----

    private void connectToService(IBinder binder) {
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "connectToService tag=" + tag);
        service = ICmdEntryInterface.Stub.asInterface(binder);
        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                service.asBinder().linkToDeath(deathRecipient, 0);
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null) view.startLogcat(logcatOutput.detachFd());
                tryConnect();
            }
        } catch (Exception e) {
            Log.e("XWindowHost", "gagal menyambung ke server X :" + display, e);
        }
    }

    private void disconnectService() {
        service = null;
        view.connect(-1);
        if (listener != null) listener.onConnectionChanged(this, false);
    }

    private boolean tryConnect() {
        if (view.connected()) {
            handler.removeCallbacks(connectRetry);
            return false;
        }
        if (service == null) {
            view.requestConnection();
            scheduleConnect();
            return true;
        }
        try {
            ParcelFileDescriptor fd = service.getXConnection();
            id.hawx.astro.x11.X11DebugLog.log("FLOAT", "tryConnect fd=" + (fd != null) + " tag=" + tag);
            if (fd != null) {
                view.connect(fd.detachFd());
                view.triggerCallback();
                view.reloadPreferences(prefs);
                if (listener != null) listener.onConnectionChanged(this, true);
            } else {
                scheduleConnect();
            }
        } catch (Exception e) {
            Log.e("XWindowHost", "tryConnect gagal :" + display, e);
            service = null;
            scheduleConnect();
        }
        return false;
    }

    private void scheduleConnect() {
        handler.removeCallbacks(connectRetry);
        handler.postDelayed(connectRetry, 250);
    }

    /** Tampilkan keyboard lunak + minta strip softkey naik (satu gerakan). */
    public void showKeyboardAndStrip() {
        toggleKeyboardVisibility();
        stripVisible = true;
        if (listener != null) listener.onStripVisibilityRequested(this, true);
    }

    /** Kirim satu key event utuh (down+up) — dipakai strip softkey. */
    public void dispatchKey(int keyCode, int metaState) {
        long now = android.os.SystemClock.uptimeMillis();
        inputHandler.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState));
        inputHandler.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState));
    }

    // ---- XHost ----

    @Override
    public LorieView getLorieView() {
        return view;
    }

    @Override
    public Prefs getPrefs() {
        return prefs;
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public Context context() {
        return activity;
    }

    @Override
    public Object getSystemService(String name) {
        return activity.getSystemService(name);
    }

    @Override
    public void getRealMetrics(DisplayMetrics m) {
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(m);
    }

    @Override
    public void finishHost() {
        // "Exit"/Back dari dalam sesi X = tutup jendelanya saja; membunuh
        // aplikasi + display adalah keputusan tombol ✕ di chrome jendela.
        if (listener != null) listener.onCloseRequested(this);
    }

    @Override
    public void toggleKeyboardVisibility() {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        view.requestFocus();
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public void toggleExtraKeys(boolean visible, boolean saveState) {
        stripVisible = visible;
        if (listener != null) listener.onStripVisibilityRequested(this, visible);
    }

    @Override
    public void toggleExtraKeys() {
        toggleExtraKeys(!stripVisible, false);
    }

    @Override
    public boolean handleKey(KeyEvent e) {
        if (prefs.filterOutWinkey.get()
                && (e.getKeyCode() == KEYCODE_META_LEFT || e.getKeyCode() == KEYCODE_META_RIGHT || e.isMetaPressed()))
            return false;
        return inputHandler.sendKeyEvent(e);
    }

    @Override
    public TermuxX11ExtraKeys extraKeys() {
        return null; // strip softkey Astro menggantikan bar bawaan
    }

    @Override
    public boolean useTermuxEKBarBehaviour() {
        return false;
    }

    @Override
    public void refreshInputDevices() {
        inputHandler.refreshInputDevices();
    }

    @Override
    public void setExternalKeyboardConnected(boolean connected) {
        // tidak ada UI khusus di mode jendela (PoC)
    }

    @Override
    public void setCapturingEnabled(boolean enabled) {
        // penangkapan pointer adalah urusan viewer layar-penuh (PoC: lewati)
    }

    @Override
    public void startActivityHost(Intent intent) {
        activity.startActivity(intent);
    }
}
