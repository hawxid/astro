package id.hawx.astro.kismet;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textview.MaterialTextView;
import id.hawx.astro.R;
import id.hawx.astro.utils.Core;
import id.hawx.astro.x11.window.WebOverlayWindows;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Panel kontrol Kismet (pola FridaFragment Batch17/19).
 *
 * Panel ini memasang KismetManager ke UI supaya start/stop
 * Kismet gampang: tiga baris status (Kismet, Bind, WiFi) +
 * toggle Bluetooth + toggle GPS + tombol Start + tombol Stop
 * + tombol Buka WebUI (web UI Kismet di jendela melayang,
 * seperti webui Frida/bettercap) + tombol Import ke Atlas +
 * toggle auto-import saat Stop.
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
    /** Bridge alat mentah (Fase 2): start best-effort sesudah
     *  Kismet start, stop SEBELUM Kismet stop. Token-nya
     *  diteruskan ke WebUI lewat cookie (lihat openWebUi). */
    private KismetToolsManager toolsManager;

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    /** Guard import (tombol manual + auto-import berbagi guard
     *  yang sama — tidak ada dua import tumpang tindih). */
    private final AtomicBoolean importing = new AtomicBoolean(false);

    private MaterialTextView serverValue;
    private MaterialTextView bindValue;
    private MaterialTextView wifiValue;
    private SwitchMaterial btSwitch;
    private MaterialTextView btNote;
    private SwitchMaterial gpsSwitch;
    private MaterialTextView gpsNote;
    private MaterialButton startBtn;
    private MaterialButton stopBtn;
    private MaterialButton webuiBtn;
    private MaterialButton importBtn;
    private SwitchMaterial autoImportSwitch;
    /** Teks fallback error + catatan QS, di-resolve di UI thread
     *  (onViewCreated) supaya thread latar tidak pernah memanggil
     *  getString(). Format import juga di-resolve di sini. */
    private String startFailedText;
    private String wifiQsText;
    private String importStartText;
    private String importNoneText;
    private String importDoneFormat;
    private String importFailedFormat;
    private String toolsFailedText;

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
        toolsManager = new KismetToolsManager(context);
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
        importStartText = getString(R.string.kismet_import_start);
        importNoneText = getString(R.string.kismet_import_none);
        importDoneFormat = getString(R.string.kismet_import_done);
        importFailedFormat = getString(R.string.kismet_import_failed);
        toolsFailedText = getString(R.string.kismet_tools_failed);
        // Toggle = pref manager (persist). setChecked DULU, baru
        // pasang listener — supaya set awal tidak memicu tulis.
        btSwitch.setChecked(manager.isBtEnabled());
        btSwitch.setOnCheckedChangeListener((v, checked) -> manager.setBtEnabled(checked));
        gpsSwitch.setChecked(manager.isGpsEnabled());
        gpsSwitch.setOnCheckedChangeListener((v, checked) -> manager.setGpsEnabled(checked));
        // Toggle auto-import BOLEH diubah saat server ON (mengatur
        // perilaku Stop, bukan Start) — karena itu tidak pernah
        // di-disable di applyStatus/setButtonsEnabled.
        autoImportSwitch.setChecked(manager.isAutoImportEnabled());
        autoImportSwitch.setOnCheckedChangeListener((v, checked) -> manager.setAutoImportEnabled(checked));
        startBtn.setOnClickListener(v -> startServers());
        stopBtn.setOnClickListener(v -> stopServers());
        webuiBtn.setOnClickListener(v -> openWebUi());
        importBtn.setOnClickListener(v -> importNow());
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
        btSwitch = view.findViewById(R.id.kismet_bt_switch);
        btNote = view.findViewById(R.id.kismet_bt_note);
        gpsSwitch = view.findViewById(R.id.kismet_gps_switch);
        gpsNote = view.findViewById(R.id.kismet_gps_note);
        startBtn = view.findViewById(R.id.kismet_start_btn);
        stopBtn = view.findViewById(R.id.kismet_stop_btn);
        webuiBtn = view.findViewById(R.id.kismet_open_webui_btn);
        importBtn = view.findViewById(R.id.kismet_import_btn);
        autoImportSwitch = view.findViewById(R.id.kismet_import_auto_switch);
    }

    private void startServers() {
        if (!busy.compareAndSet(false, true)) return;
        setButtonsEnabled(false);
        runBg(() -> {
            try {
                manager.start();
                // Bridge alat best-effort: gagalnya TIDAK
                // menggagalkan Start Kismet — alat mentah hanya
                // add-on WebUI, Kismet tetap jalan tanpanya.
                try {
                    toolsManager.start();
                } catch (IOException toolsErr) {
                    toast(toolsFailedText);
                }
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
                // Tools bridge DULU (watchdog-nya men-CONT Kismet
                // bila beku), baru Kismet stop — urutan ini wajib.
                toolsManager.stop();
                manager.stop();
                KismetManager.Status s = manager.status();
                if ("managed".equals(s.wifiType) && s.wifiChannel == null) {
                    // Managed tapi belum terasosiasi: UP harus
                    // lewat toggle WiFi Quick Settings (bukan ip up).
                    toast(wifiQsText);
                }
                // Auto-import (bila toggle ON) jalan SESUDAH Stop
                // selesai — restore WiFi sudah jalan di stop(),
                // jadi import tidak pernah menunda restore.
                // importNow memakai thread + guard sendiri, jadi
                // finally di bawah langsung melepas busy.
                if (manager.isAutoImportEnabled()) {
                    importNow();
                }
            } finally {
                busy.set(false);
                refreshStatus();
            }
        });
    }

    /**
     * Import manual (tombol) maupun auto-import (Stop): satu
     * jalur yang sama, guard importing yang sama. Berjalan di
     * thread latar sendiri (lookup jaringan bisa lama di
     * jaringan lambat); toast awal = progres, toast akhir =
     * summary (skip terlihat, bukan hilang). Tombol import
     * di-disable selama import, tombol lain tidak ikut.
     */
    private void importNow() {
        if (!importing.compareAndSet(false, true)) return;
        runUi(() -> importBtn.setEnabled(false));
        toast(importStartText);
        runBg(() -> {
            try {
                KismetImporter.Result r = manager.importNewest();
                if (r.error != null) {
                    if (r.noCapture) {
                        toast(importNoneText);
                    } else {
                        toast(String.format(Locale.US, importFailedFormat, r.error));
                    }
                } else {
                    toast(String.format(Locale.US, importDoneFormat,
                            r.added, r.updated, r.skipped));
                }
            } finally {
                importing.set(false);
                runUi(() -> importBtn.setEnabled(true));
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
                // Bridge alat best-effort: gagalnya TIDAK
                // menggagalkan buka WebUI (WebUI tanpa mod tetap
                // jalan normal). Token diteruskan ke JS mod
                // lewat cookie: cookie host 127.0.0.1 berlaku
                // semua port, jadi JS di :2501 bisa membaca
                // token untuk header ke bridge :18082.
                final String toolsToken;
                try {
                    toolsManager.start();
                    toolsToken = toolsManager.sessionToken();
                } catch (IOException toolsErr) {
                    toast(toolsFailedText);
                    toolsToken = null;
                }
                runUi(() -> {
                    if (!uiReady()) return;
                    if (toolsToken != null) {
                        CookieManager.getInstance().setCookie(
                                "http://127.0.0.1:2501/",
                                "astro_tools_token=" + toolsToken);
                    }
                    WebOverlayWindows.show(context, "Kismet",
                            "http://127.0.0.1:2501/",
                            () -> {
                                // ✕: tools dulu (latar), baru
                                // closeHook Kismet — urutan ini
                                // menjaga Kismet tidak beku saat
                                // di-stop.
                                runBg(() -> {
                                    toolsManager.stop();
                                    manager.closeHook().run();
                                });
                            });
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
        // Toggle BT DISABLED selama server ON (ganti source butuh
        // restart; state berlaku pada Start berikutnya). Keterangan
        // BT TIDAK PERNAH mengklaim server asing: btActive sudah
        // diAND-kan dengan owned di manager; server asing = "—".
        btSwitch.setEnabled(!b && !s.kismetRunning);
        if (!s.kismetRunning) {
            btNote.setText("—");
            btNote.setTextColor(0xFF9E9E9E);
        } else if (s.btActive) {
            btNote.setText(R.string.kismet_bt_active);
            btNote.setTextColor(0xFF43A047);
        } else if (s.owned) {
            btNote.setText(R.string.kismet_bt_inactive);
            btNote.setTextColor(0xFF9E9E9E);
        } else {
            btNote.setText("—");
            btNote.setTextColor(0xFF9E9E9E);
        }
        // Toggle GPS: aturan yang sama persis dengan BT (disabled
        // selama server ON/busy). Toggle auto-import SENGAJA
        // tidak disentuh di sini (boleh diubah saat server ON).
        gpsSwitch.setEnabled(!b && !s.kismetRunning);
        if (!s.kismetRunning) {
            gpsNote.setText("—");
            gpsNote.setTextColor(0xFF9E9E9E);
        } else if (s.gpsActive) {
            gpsNote.setText(R.string.kismet_gps_active);
            gpsNote.setTextColor(0xFF43A047);
        } else if (s.owned) {
            gpsNote.setText(R.string.kismet_gps_inactive);
            gpsNote.setTextColor(0xFF9E9E9E);
        } else {
            gpsNote.setText("—");
            gpsNote.setTextColor(0xFF9E9E9E);
        }
        importBtn.setEnabled(!b && !importing.get());
    }

    private void setButtonsEnabled(boolean enabled) {
        runUi(() -> {
            startBtn.setEnabled(enabled);
            stopBtn.setEnabled(enabled);
            webuiBtn.setEnabled(enabled);
            btSwitch.setEnabled(enabled);
            gpsSwitch.setEnabled(enabled);
            importBtn.setEnabled(enabled);
        });
    }
}
