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
    private final com.termux.x11.utils.TermuxX11ExtraKeys extraKeys;
    private final com.termux.x11.extrakeys.ExtraKeysView extraKeysView;
    private final Prefs prefs;
    private final TouchInputHandler inputHandler;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable connectRetry = this::tryConnect;

    private ICmdEntryInterface service;
    private Listener listener;
    private boolean surfaceReady;
    private BroadcastReceiver knockReceiver;
    private boolean stripVisible;

    private final IBinder.DeathRecipient deathRecipient = () -> handler.post(this::disconnectService);
    private final Runnable handoffPoll = this::pollHandoffStep;
    private int fitW, fitH;
    private final Runnable fitNotify = this::fireFitNotify;

    private void fireFitNotify() {
        if (display > 0)
            id.hawx.astro.x11.XSessionManager.get(activity)
                    .notifyFloatingResized(display, fitW, fitH);
    }
    private int handoffTries;
    private boolean recoveryTried;
    private int knocksWhileConnected;
    private boolean halfOpenStage1Tried;
    private boolean serverRecoveryTried;

    public XWindowHost(Activity activity, int display) {
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "host dibuat display=" + display);
        this.activity = activity;
        this.display = display;
        this.tag = "d" + display;
        LorieApp app = (LorieApp) activity.getApplicationContext();
        this.prefs = app.getPrefs(activity);
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "sebelum LorieView display=" + display);
        this.view = new LorieView(activity, null);
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "LorieView jadi display=" + display);
        view.setHost(this);
        InputEventSender sender = new InputEventSender(this, view);
        this.inputHandler = new TouchInputHandler(this, sender);
        view.setCallback((screenWidth, screenHeight, inputTransform) ->
                inputHandler.handleInputTransformChanged(screenWidth, screenHeight, inputTransform));
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "handler jadi display=" + display);
        android.view.View.OnKeyListener keyListener =
                (v, keyCode, e) -> inputHandler.sendKeyEvent(e);
        view.setOnKeyListener(keyListener);
        // EK asli Lorie (bukan strip rakitan): modifier ditahan beneran di
        // server, jadi combo CTRL+huruf dari keyboard soft berfungsi.
        com.termux.x11.extrakeys.ExtraKeysView ekView =
                (com.termux.x11.extrakeys.ExtraKeysView) android.view.LayoutInflater.from(activity)
                        .inflate(com.termux.x11.R.layout.lorie_view_terminal_toolbar_extra_keys,
                                null, false);
        ekView.setHostOverride(this);
        this.extraKeysView = ekView;
        this.extraKeys = new com.termux.x11.utils.TermuxX11ExtraKeys(keyListener, this, ekView);
        ekView.reload();
        // Urutan upstream: surface lahir DULU, koneksi X diambil SESUDAHNYA.
        // Host tertanam yang connect sebelum surface ada bikin renderer
        // native nggak pernah nampilin frame (layar hitam, koneksi sehat).
        surfaceReady = view.getHolder().getSurface() != null
                && view.getHolder().getSurface().isValid();
        view.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder h) {
                surfaceReady = true;
                id.hawx.astro.x11.X11DebugLog.log("FLOAT", "surface lahir tag=" + tag);
                tryConnect();
            }
            @Override public void surfaceChanged(android.view.SurfaceHolder h,
                                                 int f, int w, int hh) {
                // Resize jendela Android -> layar X ikut (RANDR): minta
                // manager fit ulang konten SEKALI (debounce 300 ms). Tanpa
                // watcher/polling — nol beban saat diam.
                fitW = w; fitH = hh;
                handler.removeCallbacks(fitNotify);
                handler.postDelayed(fitNotify, 300);
            }
            @Override public void surfaceDestroyed(android.view.SurfaceHolder h) {
                surfaceReady = false;
            }
        });
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
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "attach mulai tag=" + tag);
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
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "receiver terdaftar tag=" + tag);

        LorieApp app = (LorieApp) activity.getApplicationContext();
        IBinder pending = app.pendingConnections.get(tag);
        id.hawx.astro.x11.X11DebugLog.log("FLOAT", "attach tag=" + tag + " pending=" + (pending != null));
        if (pending != null) connectToService(pending);
        else tryConnect();
        // SERAH-TERIMA: selama viewer fullscreen hidup, knock diabisin dia
        // dan pending selalu kosong — poll untuk mengusirnya lalu menunggu
        // knock berikutnya mampir ke pending.
        handoffTries = 0;
        handler.postDelayed(handoffPoll, 400);
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
        handler.removeCallbacks(handoffPoll);
        inputHandler.onDestroy();
        disconnectService();
    }

    private void pollHandoffStep() {
        if (view.connected()) return;
        if (++handoffTries > 24) {
            id.hawx.astro.x11.X11DebugLog.log("HANDOFF", "poll menyerah tag=" + tag);
            return;
        }
        // Binder tak datang-datang (±6 dtk), tak ada instance fullscreen:
        // server macet ala log 21 — minta manager restart server display
        // ini SEKALI, lalu jendela tunggu dibuka lagi untuk knock segar.
        if (handoffTries == 12 && !recoveryTried && display > 0) {
            recoveryTried = true;
            handoffTries = 0;
            id.hawx.astro.x11.X11DebugLog.log("HANDOFF",
                    "poll: binder macet tag=" + tag + " -> minta pemulihan server");
            id.hawx.astro.x11.XSessionManager.get(activity).recoverStuckDisplay(display);
        }
        com.termux.x11.MainActivity inst = com.termux.x11.MainActivity.getInstance(tag);
        if (inst != null) {
            id.hawx.astro.x11.X11DebugLog.log("HANDOFF",
                    "poll: tutup viewer fullscreen tag=" + tag + " try=" + handoffTries);
            inst.finish();
        } else {
            LorieApp app = (LorieApp) activity.getApplicationContext();
            IBinder pending = app.pendingConnections.get(tag);
            if (pending != null) {
                id.hawx.astro.x11.X11DebugLog.log("HANDOFF", "poll: pending ketemu tag=" + tag);
                connectToService(pending);
                if (view.connected()) return;
            }
        }
        handler.postDelayed(handoffPoll, 500);
    }

    // ---- koneksi (cermin MainActivity upstream) ----

    private void connectToService(IBinder binder) {
        // KONEKSI SETENGAH TERBUKA (temuan log 23): status connected=true
        // tapi server terus nge-knock tiap detik = server tak menganggap
        // ada client (slot client lama belum bersih pas koneksi baru masuk).
        // Knock yang persisten saat "connected" adalah sinyalnya:
        // tahap 1 (knock ke-3): putus paksa (view.connect(-1)) lalu sambung
        // ulang pakai binder segar ini; tahap 2 (ke-6): restart server.
        if (view.connected()) {
            knocksWhileConnected++;
            if (knocksWhileConnected == 3 && !halfOpenStage1Tried) {
                halfOpenStage1Tried = true;
                knocksWhileConnected = 0;
                id.hawx.astro.x11.X11DebugLog.log("HANDOFF",
                        "koneksi setengah terbuka tag=" + tag + " -> putus + sambung ulang");
                disconnectService();
                // jatuh ke bawah: sambung ulang dengan binder segar ini
            } else if (knocksWhileConnected >= 3 && halfOpenStage1Tried
                    && !serverRecoveryTried) {
                serverRecoveryTried = true;
                id.hawx.astro.x11.X11DebugLog.log("HANDOFF",
                        "setengah terbuka bandel tag=" + tag + " -> restart server");
                id.hawx.astro.x11.XSessionManager.get(activity)
                        .recoverStuckDisplay(display);
                return;
            } else {
                return; // knock sisa / menunggu efek tahap pemulihan
            }
        } else {
            knocksWhileConnected = 0;
        }
        // SERAH-TERIMA VIEWER: satu display = satu koneksi viewer. Bila
        // viewer fullscreen (MainActivity bertag) masih hidup untuk tag ini,
        // tutup dia dulu dan TUNGGU knock berikutnya — menabrak konek saat
        // dia masih terdaftar = error arbitrase upstream.
        com.termux.x11.MainActivity fsViewer = com.termux.x11.MainActivity.getInstance(tag);
        if (fsViewer != null) {
            id.hawx.astro.x11.X11DebugLog.log("HANDOFF",
                    "tutup viewer fullscreen tag=" + tag + ", tunggu knock berikut");
            fsViewer.finish();
            return;
        }
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
        if (!surfaceReady) {
            // Tahan: surfaceCreated akan memanggil tryConnect lagi.
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
                // DIAGNOSTIK render: dump status view/surface 1,5 dtk pasca-koneksi.
                handler.postDelayed(() -> {
                    android.view.Surface sfc = view.getHolder().getSurface();
                    id.hawx.astro.x11.X11DebugLog.log("FLOAT",
                            "dump render d=" + display
                            + " view=" + view.getWidth() + "x" + view.getHeight()
                            + " attached=" + view.isAttachedToWindow()
                            + " visible=" + view.getVisibility()
                            + " shown=" + view.isShown()
                            + " surfaceValid=" + (sfc != null && sfc.isValid())
                            + " connected=" + view.connected());
                }, 1500);
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
        id.hawx.astro.x11.X11DebugLog.log("KB", "showKeyboardAndStrip d=" + display
                + " focused=" + view.isFocused() + " winFocus=" + view.hasWindowFocus());
        view.setKeyboardVisible(true);
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
        // Samakan upstream: LorieView yang mengatur IME (flag eksplisit),
        // bukan showSoftInput SHOW_IMPLICIT dari host (bisa ditolak IME).
        view.toggleKeyboardVisible();
    }

    @Override public void toggleMouseAuxButtons() { /* tak ada di host tertanam */ }

    @Override public void toggleStylusAuxButtons() { /* tak ada di host tertanam */ }

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
        return extraKeys;
    }

    public com.termux.x11.extrakeys.ExtraKeysView extraKeysView() {
        return extraKeysView;
    }

    @Override
    public boolean useTermuxEKBarBehaviour() {
        return true;
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
