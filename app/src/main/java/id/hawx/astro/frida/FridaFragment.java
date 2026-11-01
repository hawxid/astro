package id.hawx.astro.frida;

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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Panel kontrol Frida (Batch17) — start/stop/status SAJA.
 *
 * BUKAN web UI Frida (pilih proses, cari/saring/tulis nilai, freeze,
 * tabel cheat — semua itu masih parkir). Panel ini hanya memasang
 * FridaManager (Batch16) ke UI supaya start/stop server gampang:
 * dua baris status (frida-server, bridge) + tombol Start + tombol
 * Stop. Token sesi TIDAK PERNAH ditampilkan (status saja).
 *
 * Threading (pola AnonFragment): start()/stop()/status() FridaManager
 * WAJIB thread latar; pembaruan UI lewat runOnUiThread dengan guard
 * alive + isAdded; busy guard mencegah Start/Stop tumpang tindih.
 *
 * Status yang jujur: ON berarti "ada yang listening di port itu",
 * BUKAN "di-start panel ini" — server manual/asing tampil ON dan
 * stop() memang tidak membunuhnya (lihat FridaManager).
 */
public class FridaFragment extends Fragment {

    private Activity activity;
    private Context context;
    private Core core;
    private FridaManager manager;

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private MaterialTextView serverValue;
    private MaterialTextView bridgeValue;
    private MaterialButton startBtn;
    private MaterialButton stopBtn;
    /** Teks fallback error, di-resolve di UI thread (onViewCreated)
     *  supaya thread latar tidak pernah memanggil getString(). */
    private String startFailedText;

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
        manager = new FridaManager(context);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.fragment_frida, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle saved) {
        alive.set(true);
        bindViews(view);
        startFailedText = getString(R.string.frida_start_failed);
        startBtn.setOnClickListener(v -> onStart());
        stopBtn.setOnClickListener(v -> onStop());
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
        serverValue = view.findViewById(R.id.frida_server_value);
        bridgeValue = view.findViewById(R.id.frida_bridge_value);
        startBtn = view.findViewById(R.id.frida_start_btn);
        stopBtn = view.findViewById(R.id.frida_stop_btn);
    }

    private void onStart() {
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

    private void onStop() {
        if (!busy.compareAndSet(false, true)) return;
        setButtonsEnabled(false);
        runBg(() -> {
            try {
                manager.stop();
            } finally {
                busy.set(false);
                refreshStatus();
            }
        });
    }

    private void refreshStatus() {
        if (!refreshing.compareAndSet(false, true)) return;
        runUi(() -> {
            serverValue.setText(R.string.frida_checking);
            bridgeValue.setText(R.string.frida_checking);
        });
        runBg(() -> {
            try {
                final FridaManager.Status s = manager.status();
                runUi(() -> applyStatus(s));
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void applyStatus(FridaManager.Status s) {
        if (!uiReady()) return;
        serverValue.setText(s.serverRunning ? R.string.frida_on : R.string.frida_off);
        serverValue.setTextColor(s.serverRunning ? 0xFF43A047 : 0xFF9E9E9E);
        bridgeValue.setText(s.bridgeRunning ? R.string.frida_on : R.string.frida_off);
        bridgeValue.setTextColor(s.bridgeRunning ? 0xFF43A047 : 0xFF9E9E9E);
        boolean b = busy.get();
        startBtn.setEnabled(!b && !(s.serverRunning && s.bridgeRunning));
        stopBtn.setEnabled(!b && (s.serverRunning || s.bridgeRunning));
    }

    private void setButtonsEnabled(boolean enabled) {
        runUi(() -> {
            startBtn.setEnabled(enabled);
            stopBtn.setEnabled(enabled);
        });
    }
}
