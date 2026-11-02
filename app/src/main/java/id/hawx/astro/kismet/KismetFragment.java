package id.hawx.astro.kismet;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import id.hawx.astro.R;
import id.hawx.astro.utils.Core;
import id.hawx.astro.x11.window.WebOverlayWindows;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Panel kontrol Kismet (pola FridaFragment Batch17/19).
 *
 * Panel ini memasang KismetManager ke UI supaya start/stop
 * Kismet gampang: tiga baris status (Kismet, Bind, WiFi) +
 * tombol Start + tombol Stop + tombol Buka WebUI (web UI
 * Kismet di jendela melayang, seperti webui Frida/bettercap).
 * Kismet tidak punya token sesi seperti Frida — WebUI dibuka
 * langsung (login pertama dibuat pengguna di WebUI sendiri,
 * itu flow Kismet, bukan urusan panel).
 *
 * Threading (pola FridaFragment): start()/stop()/status()
 * KismetManager WAJIB thread latar; pembaruan UI lewat
 * runOnUiThread dengan guard alive + isAdded; busy guard
 * mencegah Start/Stop tumpang tindih.
 *
 * Status yang jujur: ON berarti "ada yang listening di port
 * itu", BUKAN "di-start panel ini" — server manual/asing
 * tampil ON dan stop() memang tidak membunuhnya (lihat
 * KismetManager). Baris Bind menampilkan "TIDAK AMAN" bila
 * Kismet mendengarkan di alamat selain 127.0.0.1.
 *
 * Tombol Stop tetap aktif bila WiFi masih monitor meski
 * Kismet sudah mati — supaya restore managed selalu bisa
 * dipicu (Kismet yang mati mendadak meninggalkan wlan0
 * dalam mode monitor).
 */
public class KismetFragment extends Fragment {

    private Activity activity;
    private Context context;
    private Core core;
    private KismetManager manager;

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private MaterialTextView serverValue;
    private MaterialTextView bindValue;
    private MaterialTextView wifiValue;
    private MaterialButton startBtn;
    private MaterialButton stopBtn;
    private MaterialButton webuiBtn;
    /** Teks fallback error + catatan QS, di-resolve di UI thread
     *  (onViewCreated) supaya thread latar tidak pernah memanggil
     *  getString(). */
    private String startFailedText;
    private String wifiQsText;

    private boolean uiReady() {
        return alive.get() && activity != null && isAdded();
    }

    private void runBg(Runnable r) {
        new Thread(r).start();
    }

    private void runUi(Runnable r) {
        if (uiReady()) activity.runOnUiThread(r);
    }

    private void toast(String msg) {
        runUi(() -> core.toaster(activity, msg));
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        manager = new KismetManager(context);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.fragment_kismet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle saved) {
        alive.set(true);
        bindViews(view);
        startFailedText = getString(R.string.kismet_start_failed);
        wifiQsText = getString(R.string.kismet_qs_note);
        startBtn.setOnClickListener(v -> startServers());
        stopBtn.setOnClickListener(v -> stopServers());
        webuiBtn.setOnClickListener(v -> openWebUi());
        refreshStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public void onDestroyView() {
        alive.set(false);
        super.onDestroyView();
    }

    private void bindViews(View view) {
        serverValue = view.findViewById(R.id.kismet_server_value);
        bindValue = view.findViewById(R.id.kismet_bind_value);
        wifiValue = view.findViewById(R.id.kismet_wifi_value);
        startBtn = view.findViewById(R.id.kismet_start_btn);
        stopBtn = view.findViewById(R.id.kismet_stop_btn);
        webuiBtn = view.findViewById(R.id.kismet_open_webui_btn);
    }

    private void startServers() {
        if (!busy.compareAndSet(false, true)) return;
        setButtonsEnabled(false);
        runBg(() -> {
            try {
                manager.start();
            } catch (IOException e) {
                String msg = e.getMessage();
                toast(msg != null ? msg : startFailedText);
            } finally {
                busy.set(false);
                refreshStatus();
            }
        });
    }

    private void stopServers() {
        if (!busy.compareAndSet(false, true)) return;
        setButtonsEnabled(false);
        runBg(() -> {
            try {
                manager.stop();
                KismetManager.Status s = manager.status();
                if ("managed".equals(s.wifiType) && s.wifiChannel == null) {
                    // Managed tapi belum terasosiasi: UP harus
                    // lewat toggle WiFi Quick Settings (bukan ip up).
                    toast(wifiQsText);
                }
            } finally {
                busy.set(false);
                refreshStatus();
            }
        });
    }

    /**
     * Buka WebUI Kismet di jendela melayang. Bila belum ON,
     * start dulu (busy guard yang sama). onClose = closeHook():
     * ✕ jendela menghentikan Kismet + merestore WiFi (keputusan
     * ini dicatat — pengguna akan mengoreksi setelah melihat
     * hasil bila maunya ✕ tidak menghentikan service).
     */
    private void openWebUi() {
        if (!busy.compareAndSet(false, true)) return;
        setButtonsEnabled(false);
        runBg(() -> {
            try {
                KismetManager.Status s = manager.status();
                if (!s.kismetRunning) {
                    manager.start();
                }
                runUi(() -> {
                    if (!uiReady()) return;
                    WebOverlayWindows.show(context, "Kismet",
                            "http://127.0.0.1:2501/",
                            manager.closeHook());
                });
            } catch (IOException e) {
                String msg = e.getMessage();
                toast(msg != null ? msg : startFailedText);
            } finally {
                busy.set(false);
                refreshStatus();
            }
        });
    }

    private void refreshStatus() {
        if (!refreshing.compareAndSet(false, true)) return;
        runUi(() -> {
            serverValue.setText(R.string.kismet_checking);
            bindValue.setText(R.string.kismet_checking);
            wifiValue.setText(R.string.kismet_checking);
        });
        runBg(() -> {
            try {
                final KismetManager.Status s = manager.status();
                runUi(() -> applyStatus(s));
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void applyStatus(KismetManager.Status s) {
        if (!uiReady()) return;
        serverValue.setText(s.kismetRunning ? R.string.kismet_on : R.string.kismet_off);
        serverValue.setTextColor(s.kismetRunning ? 0xFF43A047 : 0xFF9E9E9E);
        if (!s.kismetRunning) {
            bindValue.setText("—");
            bindValue.setTextColor(0xFF9E9E9E);
        } else if (s.bindUnsafe) {
            bindValue.setText(R.string.kismet_bind_unsafe);
            bindValue.setTextColor(0xFFEF5350);
        } else {
            bindValue.setText(R.string.kismet_bind_safe);
            bindValue.setTextColor(0xFF43A047);
        }
        if ("monitor".equals(s.wifiType)) {
            wifiValue.setText(R.string.kismet_wifi_monitor);
            wifiValue.setTextColor(0xFF43A047);
        } else if ("managed".equals(s.wifiType)) {
            String text = getString(R.string.kismet_wifi_managed);
            if (s.wifiChannel != null) {
                text = text + " · ch " + s.wifiChannel;
            }
            wifiValue.setText(text);
            wifiValue.setTextColor(0xFF9E9E9E);
        } else {
            wifiValue.setText(R.string.kismet_wifi_unknown);
            wifiValue.setTextColor(0xFF9E9E9E);
        }
        boolean b = busy.get();
        startBtn.setEnabled(!b && !s.kismetRunning);
        // Stop tetap aktif bila WiFi masih monitor (restore
        // managed harus selalu bisa dipicu, meski server mati).
        stopBtn.setEnabled(!b && (s.kismetRunning || "monitor".equals(s.wifiType)));
        webuiBtn.setEnabled(!b);
    }

    private void setButtonsEnabled(boolean enabled) {
        runUi(() -> {
            startBtn.setEnabled(enabled);
            stopBtn.setEnabled(enabled);
            webuiBtn.setEnabled(enabled);
        });
    }
}
