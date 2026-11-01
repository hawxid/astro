package id.hawx.astro.anon;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import id.hawx.astro.R;
import id.hawx.astro.engine.Apt;
import id.hawx.astro.utils.Core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mode Anonim: toggle + WireGuard + Tor (satu mode aktif).
 * VPN standar masih chip nonaktif ("segera") — bukan fungsi palsu.
 *
 * Backend WG = kernel wg0 lewat wireguard-tools di chroot (root).
 * Backend Tor = daemon chroot sebagai debian-tor + transparent proxy
 * iptables dari su sisi-Android (pola kill switch WG).
 * Semua perintah chroot jalan di thread latar; kunci privat tidak pernah
 * ditulis ke log (engine memakai nolog=true).
 */
public class AnonFragment extends Fragment {

    private Activity activity;
    private Context context;
    private Core core;

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private MaterialTextView statusText;
    private MaterialButton toggleBtn;
    private MaterialButton refreshBtn;
    private MaterialButton repairBtn;
    private SwitchMaterial killSwitch;
    private MaterialTextView warnText;
    private Chip chipWg;
    private Chip chipVpn;
    private Chip chipTor;
    private TextInputEditText inEndpoint;
    private TextInputEditText inAddress;
    private TextInputEditText inAllowed;
    private TextInputEditText inKeepalive;
    private TextInputEditText inPeer;
    private TextInputEditText inPrivkey;
    private MaterialButton genkeyBtn;
    private MaterialButton pubkeyBtn;
    private MaterialButton saveBtn;
    private MaterialButton importBtn;
    private MaterialTextView toolsText;
    private MaterialButton installToolsBtn;

    private String mode = TorEngine.MODE_WG;
    private View wgCard;
    private View torCard;
    private View torConnecting;
    private ProgressBar torProgress;
    private MaterialTextView torPercentText;
    private MaterialTextView torStageText;
    private MaterialTextView torLogText;
    private MaterialButton torLogBtn;
    private View torLogBox;
    private MaterialTextView torLogFullText;
    private MaterialButton torExitBtn;
    private final AtomicBoolean torPolling = new AtomicBoolean(false);
    private final AtomicBoolean torStarting = new AtomicBoolean(false);
    private int torLastPercent = -1;
    private long torLastProgressMs = 0;

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
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.fragment_anon, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle saved) {
        alive.set(true);
        bindViews(view);
        chipWg.setEnabled(true);
        chipVpn.setEnabled(false);
        chipTor.setEnabled(true);
        chipWg.setOnClickListener(v -> selectMode(TorEngine.MODE_WG));
        chipTor.setOnClickListener(v -> selectMode(TorEngine.MODE_TOR));

        toggleBtn.setOnClickListener(v -> onToggle());
        refreshBtn.setOnClickListener(v -> refreshStatus());
        repairBtn.setOnClickListener(v -> onRepair());
        killSwitch.setChecked(WgConfig.load(core).killswitch);
        // Sakelar menyimpan langsung (hanya pref kill switch, bukan field
        // form) supaya Perbaiki rute pun menghormatinya tanpa Simpan dulu.
        killSwitch.setOnCheckedChangeListener((btn, checked) ->
                core.putBoolean(WgConfig.P_KILL, checked));
        genkeyBtn.setOnClickListener(v -> onGenkey());
        pubkeyBtn.setOnClickListener(v -> onShowPubkey());
        saveBtn.setOnClickListener(v -> onSave());
        importBtn.setOnClickListener(v -> onImport());
        installToolsBtn.setOnClickListener(v -> onInstallTools());
        torLogBtn.setOnClickListener(v -> toggleTorLog());
        torExitBtn.setOnClickListener(v -> onShowTorExit());

        fillFields(WgConfig.load(core));
        String savedMode = core.getString(TorEngine.P_MODE);
        selectMode(TorEngine.MODE_TOR.equals(savedMode)
                ? TorEngine.MODE_TOR : TorEngine.MODE_WG);
    }

    @Override
    public void onDestroyView() {
        alive.set(false);
        stopTorPolling();
        super.onDestroyView();
    }

    private void bindViews(View view) {
        statusText = view.findViewById(R.id.anon_status_text);
        toggleBtn = view.findViewById(R.id.anon_toggle_btn);
        refreshBtn = view.findViewById(R.id.anon_refresh_btn);
        repairBtn = view.findViewById(R.id.anon_repair_btn);
        killSwitch = view.findViewById(R.id.anon_killswitch);
        warnText = view.findViewById(R.id.anon_warn_text);
        chipWg = view.findViewById(R.id.anon_chip_wg);
        chipVpn = view.findViewById(R.id.anon_chip_vpn);
        chipTor = view.findViewById(R.id.anon_chip_tor);
        inEndpoint = view.findViewById(R.id.anon_in_endpoint);
        inAddress = view.findViewById(R.id.anon_in_address);
        inAllowed = view.findViewById(R.id.anon_in_allowed);
        inKeepalive = view.findViewById(R.id.anon_in_keepalive);
        inPeer = view.findViewById(R.id.anon_in_peer);
        inPrivkey = view.findViewById(R.id.anon_in_privkey);
        genkeyBtn = view.findViewById(R.id.anon_genkey_btn);
        pubkeyBtn = view.findViewById(R.id.anon_pubkey_btn);
        saveBtn = view.findViewById(R.id.anon_save_btn);
        importBtn = view.findViewById(R.id.anon_import_btn);
        toolsText = view.findViewById(R.id.anon_tools_text);
        installToolsBtn = view.findViewById(R.id.anon_install_tools_btn);
        wgCard = view.findViewById(R.id.anon_wg_card);
        torCard = view.findViewById(R.id.anon_tor_card);
        torConnecting = view.findViewById(R.id.anon_tor_connecting);
        torProgress = view.findViewById(R.id.anon_tor_progress);
        torPercentText = view.findViewById(R.id.anon_tor_percent);
        torStageText = view.findViewById(R.id.anon_tor_stage);
        torLogText = view.findViewById(R.id.anon_tor_log);
        torLogBtn = view.findViewById(R.id.anon_tor_log_btn);
        torLogBox = view.findViewById(R.id.anon_tor_log_box);
        torLogFullText = view.findViewById(R.id.anon_tor_log_full);
        torExitBtn = view.findViewById(R.id.anon_tor_exit_btn);
    }

    private String textOf(TextInputEditText in) {
        if (in == null || in.getText() == null) return "";
        return in.getText().toString().trim();
    }

    private void setText(TextInputEditText in, String val) {
        if (in != null) in.setText(val == null ? "" : val);
    }

    private WgConfig readFields() {
        WgConfig c = new WgConfig();
        c.endpoint = textOf(inEndpoint);
        c.address = textOf(inAddress);
        c.allowedIps = textOf(inAllowed);
        c.peerPublicKey = textOf(inPeer);
        c.privateKey = textOf(inPrivkey);
        c.killswitch = killSwitch != null && killSwitch.isChecked();
        try {
            c.keepalive = Integer.parseInt(textOf(inKeepalive));
        } catch (NumberFormatException ignored) {
            c.keepalive = 25;
        }
        return c;
    }

    private void fillFields(WgConfig c) {
        setText(inEndpoint, c.endpoint);
        setText(inAddress, c.address);
        setText(inAllowed, c.allowedIps);
        setText(inKeepalive, String.valueOf(c.keepalive));
        setText(inPeer, c.peerPublicKey);
        setText(inPrivkey, c.privateKey);
    }

    /** Ganti mode tampilan (WG vs Tor): kartu + sakelar kill switch ikut. */
    private void selectMode(String m) {
        mode = TorEngine.MODE_TOR.equals(m) ? TorEngine.MODE_TOR : TorEngine.MODE_WG;
        core.putString(TorEngine.P_MODE, mode);
        boolean tor = TorEngine.MODE_TOR.equals(mode);
        wgCard.setVisibility(tor ? View.GONE : View.VISIBLE);
        torCard.setVisibility(tor ? View.VISIBLE : View.GONE);
        killSwitch.setVisibility(tor ? View.GONE : View.VISIBLE);
        chipWg.setChecked(!tor);
        chipTor.setChecked(tor);
        stopTorPolling();
        refreshStatus();
        refreshTools();
    }

    private void stopTorPolling() {
        torPolling.set(false);
    }

    /** Polling bootstrap Tor tiap 2 detik selama CONNECTING. */
    private void startTorPolling() {
        if (torPolling.getAndSet(true)) return;
        runBg(() -> {
            try {
                while (alive.get() && torPolling.get()
                        && TorEngine.MODE_TOR.equals(mode)) {
                    TorEngine.Status s = TorEngine.status(core);
                    runUi(() -> renderTorStatus(s));
                    if (s.state != TorEngine.State.CONNECTING) break;
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            } finally {
                torPolling.set(false);
            }
        });
    }

    private String torStageLabel(String tag) {
        if (tag == null) return getString(R.string.tor_stage_unknown);
        switch (tag) {
            case "starting":
                return getString(R.string.tor_stage_start);
            case "conn":
            case "conn_done":
                return getString(R.string.tor_stage_conn);
            case "handshake":
            case "handshake_done":
                return getString(R.string.tor_stage_handshake);
            case "requesting_status":
            case "loading_status":
            case "loading_keys":
                return getString(R.string.tor_stage_status);
            case "requesting_descriptors":
            case "loading_descriptors":
                return getString(R.string.tor_stage_descriptors);
            case "enough_dirinfo":
            case "ap_handshake_done":
            case "circuit_create":
                return getString(R.string.tor_stage_circuit);
            case "done":
                return getString(R.string.tor_stage_done);
            default:
                return getString(R.string.tor_stage_unknown);
        }
    }

    private void renderTorStatus(TorEngine.Status s) {
        if (!uiReady()) return;
        // Selama up() masih jalan, kartu optimistis dipertahankan: status
        // yang dibaca di tengah jalan (daemon hidup tapi aturan belum
        // terpasang) akan terlihat BROKEN padahal itu keadaan transien.
        if (torStarting.get() && s.state != TorEngine.State.ON) return;
        switch (s.state) {
            case ON:
                statusText.setText(R.string.tor_status_on);
                toggleBtn.setText(R.string.tor_btn_off);
                torConnecting.setVisibility(View.GONE);
                torLogBtn.setVisibility(View.GONE);
                torLogBox.setVisibility(View.GONE);
                warnText.setText(R.string.tor_warn_on);
                stopTorPolling();
                break;
            case CONNECTING:
                statusText.setText(R.string.tor_status_connecting);
                toggleBtn.setText(R.string.tor_btn_cancel);
                torConnecting.setVisibility(View.VISIBLE);
                torLogBtn.setVisibility(View.VISIBLE);
                torProgress.setProgress(s.percent);
                torPercentText.setText(s.percent + "%");
                String stage = torStageLabel(s.stageTag);
                if (s.percent != torLastPercent) {
                    torLastPercent = s.percent;
                    torLastProgressMs = System.currentTimeMillis();
                } else if (torLastProgressMs > 0
                        && System.currentTimeMillis() - torLastProgressMs > 180000) {
                    stage += getString(R.string.tor_stage_stuck);
                }
                torStageText.setText(stage);
                if (s.lastLog != null && !s.lastLog.isEmpty()) {
                    torLogText.setText(s.lastLog);
                }
                warnText.setText(R.string.tor_warn);
                startTorPolling();
                break;
            case BROKEN:
                statusText.setText(R.string.tor_status_broken);
                toggleBtn.setText(R.string.tor_btn_off);
                torConnecting.setVisibility(View.GONE);
                torLogBtn.setVisibility(View.VISIBLE);
                warnText.setText(R.string.tor_warn_broken);
                stopTorPolling();
                break;
            default:
                statusText.setText(R.string.tor_status_off);
                toggleBtn.setText(R.string.tor_btn_on);
                torConnecting.setVisibility(View.GONE);
                torLogBtn.setVisibility(View.GONE);
                torLogBox.setVisibility(View.GONE);
                warnText.setText(R.string.tor_warn);
                stopTorPolling();
                break;
        }
        // Umpan balik tombol dipulihkan di sini (bukan di pemanggil) agar
        // tidak ada jendela tombol aktif dengan teks "Memproses…".
        toggleBtn.setEnabled(true);
        repairBtn.setEnabled(true);
        repairBtn.setText(R.string.anon_btn_repair);
    }

    /** Kartu connecting optimistis: tampil sejak tap, sebelum up() kembali. */
    private void showTorOptimistic() {
        if (!uiReady()) return;
        statusText.setText(R.string.tor_status_connecting);
        torConnecting.setVisibility(View.VISIBLE);
        torLogBtn.setVisibility(View.VISIBLE);
        torProgress.setProgress(0);
        torPercentText.setText("0%");
        torStageText.setText(R.string.tor_stage_start);
        torLogText.setText("");
        warnText.setText(R.string.tor_warn);
    }

    private void refreshTorStatus() {
        runBg(() -> {
            TorEngine.Status s = TorEngine.status(core);
            runUi(() -> renderTorStatus(s));
        });
    }

    private void toggleTorLog() {
        if (torLogBox.getVisibility() == View.VISIBLE) {
            torLogBox.setVisibility(View.GONE);
            return;
        }
        runBg(() -> {
            String tail = TorEngine.logTail(core, 30);
            runUi(() -> {
                torLogFullText.setText(tail == null || tail.isEmpty()
                        ? getString(R.string.tor_log_empty) : tail);
                torLogBox.setVisibility(View.VISIBLE);
            });
        });
    }

    private void refreshStatus() {
        if (TorEngine.MODE_TOR.equals(mode)) {
            refreshTorStatus();
            return;
        }
        runBg(() -> {
            WgEngine.Status s = WgEngine.status(core);
            boolean ksActive = WgEngine.killSwitchActive(core);
            boolean ksPref = WgConfig.load(core).killswitch;
            runUi(() -> {
                if (s.up) {
                    String hs = s.handshake.isEmpty()
                            ? "" : "\nHandshake terakhir: " + s.handshake;
                    statusText.setText(getString(R.string.anon_status_on) + hs);
                    toggleBtn.setText(R.string.anon_btn_off);
                } else {
                    statusText.setText(R.string.anon_status_off);
                    toggleBtn.setText(R.string.anon_btn_on);
                }
                // Peringatan mengikuti VERIFIKASI LIVE aturan, bukan hanya
                // setelan: sakelar menyala tapi aturan hilang = rusak.
                if (warnText != null) {
                    if (s.up && ksActive) {
                        warnText.setText(R.string.anon_warn_ks_on);
                    } else if (s.up && ksPref) {
                        warnText.setText(R.string.anon_warn_ks_broken);
                    } else if (s.up) {
                        warnText.setText(R.string.anon_warn_ks_off);
                    } else {
                        warnText.setText(R.string.anon_warn);
                    }
                }
                toggleBtn.setEnabled(true);
                repairBtn.setEnabled(true);
                repairBtn.setText(R.string.anon_btn_repair);
            });
        });
    }

    private void refreshTools() {
        if (TorEngine.MODE_TOR.equals(mode)) {
            runBg(() -> {
                boolean ok = TorEngine.toolsOk(core);
                runUi(() -> {
                    toolsText.setText(ok ? R.string.tor_tools_ok : R.string.tor_tools_missing);
                    installToolsBtn.setText(R.string.tor_btn_install_tools);
                    installToolsBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
                });
            });
            return;
        }
        runBg(() -> {
            boolean ok = WgEngine.toolsOk(core);
            runUi(() -> {
                toolsText.setText(ok ? R.string.anon_tools_ok : R.string.anon_tools_missing);
                installToolsBtn.setText(R.string.anon_btn_install_tools);
                installToolsBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void onToggle() {
        if (busy.getAndSet(true)) return;
        runUi(() -> {
            toggleBtn.setEnabled(false);
            repairBtn.setEnabled(false);
            toggleBtn.setText(R.string.anon_btn_processing);
        });
        runBg(() -> {
            try {
                if (TorEngine.MODE_TOR.equals(mode)) {
                    onToggleTor();
                } else {
                    onToggleWg();
                }
            } finally {
                busy.set(false);
            }
            refreshStatus();
        });
    }

    private void onToggleTor() {
        TorEngine.Status s = TorEngine.status(core);
        if (s.state == TorEngine.State.ON || s.state == TorEngine.State.CONNECTING) {
            TorEngine.Result r = TorEngine.down(core);
            toast(r.ok ? getString(R.string.tor_toast_down_ok) : r.message);
            stopTorPolling();
            return;
        }
        // Pindah mode: matikan WG dulu bila sedang jalan.
        WgEngine.Status ws = WgEngine.status(core);
        if (ws.up) {
            WgEngine.down(core);
            toast(getString(R.string.tor_toast_wg_down));
        }
        if (!TorEngine.toolsOk(core)) {
            toast(getString(R.string.tor_toast_tools_first));
            return;
        }
        torLastPercent = -1;
        torLastProgressMs = System.currentTimeMillis();
        torStarting.set(true);
        runUi(() -> showTorOptimistic());
        TorEngine.Result r = TorEngine.up(core);
        torStarting.set(false);
        if (r.ok) {
            toast(getString(R.string.tor_toast_up_start));
        } else {
            toast(getString(R.string.tor_toast_up_fail) + " " + r.message);
        }
    }

    private void onToggleWg() {
        WgEngine.Status s = WgEngine.status(core);
        if (s.up) {
            WgEngine.Result r = WgEngine.down(core);
            toast(r.ok ? getString(R.string.anon_toast_down_ok) : r.message);
        } else {
            // Pindah mode: matikan Tor dulu bila sedang jalan.
            TorEngine.Status ts = TorEngine.status(core);
            if (ts.state != TorEngine.State.OFF) {
                TorEngine.down(core);
                toast(getString(R.string.tor_toast_tor_down));
            }
            WgConfig c = readFields();
            if (!c.isComplete()) {
                toast(getString(R.string.anon_toast_need_config));
                return;
            }
            c.save(core);
            WgEngine.Result r = WgEngine.up(core, c);
            toast(r.ok ? getString(R.string.anon_toast_up_ok)
                    : getString(R.string.anon_toast_up_fail) + " " + r.message);
        }
    }

    private void onRepair() {
        if (busy.getAndSet(true)) return;
        runUi(() -> {
            toggleBtn.setEnabled(false);
            repairBtn.setEnabled(false);
            repairBtn.setText(R.string.anon_btn_processing);
        });
        runBg(() -> {
            try {
                if (TorEngine.MODE_TOR.equals(mode)) {
                    TorEngine.down(core);
                    if (!TorEngine.toolsOk(core)) {
                        toast(getString(R.string.tor_toast_tools_first));
                    } else {
                        torLastPercent = -1;
                        torLastProgressMs = System.currentTimeMillis();
                        torStarting.set(true);
                        runUi(() -> showTorOptimistic());
                        TorEngine.Result r = TorEngine.up(core);
                        torStarting.set(false);
                        toast(r.ok ? getString(R.string.tor_toast_up_start)
                                : getString(R.string.tor_toast_up_fail) + " " + r.message);
                    }
                } else {
                    WgEngine.down(core);
                    WgConfig c = WgConfig.load(core);
                    if (!c.isComplete()) {
                        toast(getString(R.string.anon_toast_need_config));
                    } else {
                        WgEngine.Result r = WgEngine.up(core, c);
                        toast(r.ok ? getString(R.string.anon_toast_up_ok)
                                : getString(R.string.anon_toast_up_fail) + " " + r.message);
                    }
                }
            } finally {
                busy.set(false);
            }
            // WAJIB selalu jalan: return dini di dalam try akan melewati
            // baris ini (bug: UI beku di kartu optimistis + tombol
            // "Memproses…" setelah Perbaiki Tor, padahal engine sudah 100%).
            refreshStatus();
        });
    }

    private void onGenkey() {
        runBg(() -> {
            if (!WgEngine.toolsOk(core)) {
                toast(getString(R.string.anon_toast_tools_first));
                return;
            }
            String key = WgEngine.genKey(core);
            if (key == null) {
                toast(getString(R.string.anon_toast_up_fail));
                return;
            }
            runUi(() -> setText(inPrivkey, key));
        });
    }

    private void onShowPubkey() {
        runBg(() -> {
            String priv = textOf(inPrivkey);
            if (priv.length() != 44) {
                toast(getString(R.string.anon_toast_need_privkey));
                return;
            }
            if (!WgEngine.toolsOk(core)) {
                toast(getString(R.string.anon_toast_tools_first));
                return;
            }
            String pub = WgEngine.pubKey(core, priv);
            if (pub == null) {
                toast(getString(R.string.anon_toast_pubkey_fail));
                return;
            }
            runUi(() -> new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.anon_pubkey_title)
                    .setMessage(pub)
                    .setPositiveButton(R.string.anon_btn_copy, (d, w) -> {
                        try {
                            ClipboardManager cm = (ClipboardManager)
                                    context.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("wg-pubkey", pub));
                            }
                            toast(getString(R.string.anon_toast_copied));
                        } catch (Exception ignored) {
                            // Clipboard gagal: kunci tetap terlihat di dialog.
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        });
    }

    private void onSave() {
        WgConfig c = readFields();
        c.save(core);
        toast(getString(R.string.anon_toast_saved));
    }

    private void onImport() {
        TextInputEditText input = new TextInputEditText(context);
        input.setMinLines(8);
        // Multiline eksplisit: tanpa ini, tempelan .conf berisiko runtuh
        // jadi satu baris dan parser tidak menemukan section apa pun.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint(R.string.anon_import_hint);
        // Bila clipboard sudah berisi .conf, tampilkan langsung: tombol
        // "tempel" harus benar-benar menempel, bukan kotak kosong lagi.
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence clipText = clip.getItemAt(0).getText();
                    if (clipText != null) {
                        String s = clipText.toString();
                        if (s.contains("[Interface]") || s.contains("PrivateKey")) {
                            input.setText(s);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Clipboard tidak bisa dibaca: user tetap bisa paste manual.
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.anon_import_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String text = input.getText() == null ? "" : input.getText().toString();
                    WgConfig c = WgConfig.parseConf(text);
                    // Jangan pernah menimpa config tersimpan dengan hasil
                    // parse kosong/gagal: itu yang dulu bikin impor terasa
                    // "tidak masuk" sambil diam-diam menghapus isian.
                    if (!c.isComplete()) {
                        toast(getString(R.string.anon_toast_import_fail));
                        return;
                    }
                    // Impor tidak boleh menimpa setelan kill switch.
                    c.killswitch = killSwitch != null && killSwitch.isChecked();
                    fillFields(c);
                    c.save(core);
                    toast(getString(R.string.anon_toast_saved));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onInstallTools() {
        runBg(() -> {
            if (TorEngine.MODE_TOR.equals(mode)) {
                String cmd = TextUtils.join("; ", Apt.env()) + "; " + Apt.install("tor");
                core.customChrootCommand(cmd, true);
                boolean ok = TorEngine.toolsOk(core);
                toast(ok ? getString(R.string.tor_tools_ok) : getString(R.string.tor_toast_up_fail));
                refreshTools();
                return;
            }
            String cmd = TextUtils.join("; ", Apt.env()) + "; " + Apt.install("wireguard-tools");
            core.customChrootCommand(cmd, true);
            boolean ok = WgEngine.toolsOk(core);
            toast(ok ? getString(R.string.anon_tools_ok) : getString(R.string.anon_toast_up_fail));
            refreshTools();
        });
    }

    private void onShowTorExit() {
        runBg(() -> {
            TorEngine.Status s = TorEngine.status(core);
            if (s.state != TorEngine.State.ON) {
                toast(getString(R.string.tor_toast_not_on));
                return;
            }
            String ip = TorEngine.exitIp(core);
            if (ip == null) {
                toast(getString(R.string.tor_toast_exit_fail));
                return;
            }
            runUi(() -> new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.tor_exit_title)
                    .setMessage(ip)
                    .setPositiveButton(R.string.anon_btn_copy, (d, w) -> {
                        try {
                            ClipboardManager cm = (ClipboardManager)
                                    context.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("tor-exit-ip", ip));
                            }
                            toast(getString(R.string.tor_toast_exit_copied));
                        } catch (Exception ignored) {
                            // Clipboard gagal: IP tetap terlihat di dialog.
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        });
    }
}
