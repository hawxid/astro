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

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import id.hawx.astro.R;
import id.hawx.astro.engine.Apt;
import id.hawx.astro.utils.Core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mode Anonim Batch 1: toggle + WireGuard saja.
 * VPN standar dan Tor tampil sebagai chip nonaktif ("segera") — bukan
 * fungsi palsu, keduanya menyusul di batch berikutnya.
 *
 * Backend WG = kernel wg0 lewat wireguard-tools di chroot (root).
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
    private MaterialButton saveBtn;
    private MaterialButton importBtn;
    private MaterialTextView toolsText;
    private MaterialButton installToolsBtn;

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
        chipWg.setChecked(true);
        chipWg.setEnabled(true);
        chipVpn.setEnabled(false);
        chipTor.setEnabled(false);

        toggleBtn.setOnClickListener(v -> onToggle());
        refreshBtn.setOnClickListener(v -> refreshStatus());
        repairBtn.setOnClickListener(v -> onRepair());
        genkeyBtn.setOnClickListener(v -> onGenkey());
        saveBtn.setOnClickListener(v -> onSave());
        importBtn.setOnClickListener(v -> onImport());
        installToolsBtn.setOnClickListener(v -> onInstallTools());

        fillFields(WgConfig.load(core));
        refreshTools();
        refreshStatus();
    }

    @Override
    public void onDestroyView() {
        alive.set(false);
        super.onDestroyView();
    }

    private void bindViews(View view) {
        statusText = view.findViewById(R.id.anon_status_text);
        toggleBtn = view.findViewById(R.id.anon_toggle_btn);
        refreshBtn = view.findViewById(R.id.anon_refresh_btn);
        repairBtn = view.findViewById(R.id.anon_repair_btn);
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
        saveBtn = view.findViewById(R.id.anon_save_btn);
        importBtn = view.findViewById(R.id.anon_import_btn);
        toolsText = view.findViewById(R.id.anon_tools_text);
        installToolsBtn = view.findViewById(R.id.anon_install_tools_btn);
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

    private void refreshStatus() {
        runBg(() -> {
            WgEngine.Status s = WgEngine.status(core);
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
            });
        });
    }

    private void refreshTools() {
        runBg(() -> {
            boolean ok = WgEngine.toolsOk(core);
            runUi(() -> {
                toolsText.setText(ok ? R.string.anon_tools_ok : R.string.anon_tools_missing);
                installToolsBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void onToggle() {
        if (busy.getAndSet(true)) return;
        runBg(() -> {
            try {
                WgEngine.Status s = WgEngine.status(core);
                if (s.up) {
                    WgEngine.Result r = WgEngine.down(core);
                    toast(r.ok ? getString(R.string.anon_toast_down_ok) : r.message);
                } else {
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
            } finally {
                busy.set(false);
            }
            refreshStatus();
        });
    }

    private void onRepair() {
        if (busy.getAndSet(true)) return;
        runBg(() -> {
            try {
                WgEngine.down(core);
                WgConfig c = WgConfig.load(core);
                if (!c.isComplete()) {
                    toast(getString(R.string.anon_toast_need_config));
                    return;
                }
                WgEngine.Result r = WgEngine.up(core, c);
                toast(r.ok ? getString(R.string.anon_toast_up_ok)
                        : getString(R.string.anon_toast_up_fail) + " " + r.message);
            } finally {
                busy.set(false);
            }
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
                    fillFields(c);
                    c.save(core);
                    toast(getString(R.string.anon_toast_saved));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onInstallTools() {
        runBg(() -> {
            String cmd = TextUtils.join("; ", Apt.env()) + "; " + Apt.install("wireguard-tools");
            core.customChrootCommand(cmd, true);
            boolean ok = WgEngine.toolsOk(core);
            toast(ok ? getString(R.string.anon_tools_ok) : getString(R.string.anon_toast_up_fail));
            refreshTools();
        });
    }
}
