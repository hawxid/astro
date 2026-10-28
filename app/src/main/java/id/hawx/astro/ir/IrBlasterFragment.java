package id.hawx.astro.ir;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import id.hawx.astro.R;

/**
 * IR Blaster v1: TV remote over the phone's IR emitter.
 *
 * No root, no chroot: ConsumerIrManager is a normal Android API.
 * v1 scope: LG full pad (verified NEC table) + Samsung power (verified)
 * + raw hex sender + power-code wizard. AC / STB / more brands = next rounds.
 */
public class IrBlasterFragment extends Fragment {

    private static final String PREFS = "astro_ir";

    private ConsumerIrManager ir;
    private boolean hasEmitter;
    private TextView status;
    private TextView brandNote;
    private TextView wizardText;
    private EditText hexInput;
    private String brand = "LG";
    private int wizardIdx;

    /** Pad buttons that only exist in the LG table (disabled for Samsung). */
    private final List<View> lgOnlyButtons = new ArrayList<>();
    private final List<View> allSendButtons = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_irblaster, container, false);

        status = v.findViewById(R.id.ir_status);
        brandNote = v.findViewById(R.id.ir_brand_note);
        wizardText = v.findViewById(R.id.ir_txt_wizard);
        hexInput = v.findViewById(R.id.ir_edit_hex);

        ir = (ConsumerIrManager) requireContext().getSystemService(Context.CONSUMER_IR_SERVICE);
        hasEmitter = ir != null && ir.hasIrEmitter();

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        brand = prefs.getString("brand", "LG");
        wizardIdx = 0;

        bind(v, R.id.ir_btn_lg, () -> setBrand("LG"));
        bind(v, R.id.ir_btn_samsung, () -> setBrand("Samsung"));

        // Pad: button ids follow the table keys (ir_btn_<key>).
        Map<String, IrCodes.Entry> table = IrCodes.forBrand("LG");
        for (Map.Entry<String, IrCodes.Entry> e : table.entrySet()) {
            int id = getResources().getIdentifier("ir_btn_" + e.getKey(), "id",
                    requireContext().getPackageName());
            if (id == 0) continue;
            View btn = v.findViewById(id);
            if (btn == null) continue;
            final IrCodes.Entry entry = e.getValue();
            btn.setOnClickListener(view -> send(entry));
            allSendButtons.add(btn);
            if (!"power".equals(e.getKey())) lgOnlyButtons.add(btn);
        }

        bind(v, R.id.ir_btn_wizard_next, () -> {
            IrCodes.Entry cand = IrCodes.POWER_WIZARD[wizardIdx % IrCodes.POWER_WIZARD.length];
            wizardIdx++;
            wizardText.setText("Kandidat: " + cand.name + " (0x"
                    + Integer.toHexString(cand.code).toUpperCase()
                    + ") — terkirim, lihat TV bereaksi?");
            send(cand);
        });
        bind(v, R.id.ir_btn_wizard_save, () -> {
            IrCodes.Entry cand = IrCodes.POWER_WIZARD[
                    (wizardIdx - 1 + IrCodes.POWER_WIZARD.length) % IrCodes.POWER_WIZARD.length];
            prefs.edit()
                    .putString("saved_power_brand", cand.name)
                    .putString("saved_power_hex", Integer.toHexString(cand.code).toUpperCase())
                    .apply();
            setBrand(cand.name);
            setStatus("Tersimpan: TV " + cand.name + " (power 0x"
                    + Integer.toHexString(cand.code).toUpperCase() + ")");
        });
        bind(v, R.id.ir_btn_send_nec, () -> sendHex(IrCodes.PROTOCOL_NEC));
        bind(v, R.id.ir_btn_send_samsung, () -> sendHex(IrCodes.PROTOCOL_SAMSUNG));

        applyBrand();
        if (!hasEmitter) {
            setStatus("Perangkat ini tidak punya IR blaster — semua tombol kirim nonaktif.");
            for (View b : allSendButtons) b.setEnabled(false);
            v.findViewById(R.id.ir_btn_wizard_next).setEnabled(false);
            v.findViewById(R.id.ir_btn_wizard_save).setEnabled(false);
            v.findViewById(R.id.ir_btn_send_nec).setEnabled(false);
            v.findViewById(R.id.ir_btn_send_samsung).setEnabled(false);
        } else {
            setStatus("IR blaster siap — arahkan HP ke TV, lalu pencet tombol.");
        }
        String saved = prefs.getString("saved_power_brand", null);
        if (saved != null) {
            wizardText.setText("Tersimpan: TV " + saved + " (power "
                    + prefs.getString("saved_power_hex", "") + ")");
        }
        return v;
    }

    private void bind(View root, int id, Runnable action) {
        View btn = root.findViewById(id);
        if (btn != null) btn.setOnClickListener(v -> action.run());
    }

    private void setBrand(String next) {
        brand = next;
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("brand", next).apply();
        applyBrand();
    }

    private void applyBrand() {
        boolean fullPad = "LG".equals(brand);
        for (View b : lgOnlyButtons) b.setEnabled(fullPad);
        brandNote.setText(fullPad
                ? "Brand: LG — pad lengkap (tabel NEC terverifikasi)."
                : "Brand: Samsung — baru Power yang terverifikasi; tombol lain "
                        + "nonaktif sampai tabel lengkap. Pakai Kirim Kode Hex untuk tombol lain.");
    }

    private void send(IrCodes.Entry entry) {
        if (!hasEmitter || ir == null) {
            setStatus("Tidak ada IR blaster di perangkat ini.");
            return;
        }
        try {
            int[] pattern = entry.protocol == IrCodes.PROTOCOL_SAMSUNG
                    ? NecEncoder.samsung(entry.code) : NecEncoder.nec(entry.code);
            ir.transmit(NecEncoder.FREQUENCY_HZ, pattern);
            setStatus("Terkirim: " + entry.name + " (0x"
                    + Integer.toHexString(entry.code).toUpperCase() + ")");
        } catch (Exception e) {
            setStatus("Gagal kirim: " + e.getMessage());
        }
    }

    private void sendHex(int protocol) {
        String raw = hexInput.getText().toString().trim()
                .replace("0x", "").replace("0X", "").replace(" ", "");
        if (raw.isEmpty()) {
            setStatus("Isi dulu kode hex-nya (mis. 20DF10EF).");
            return;
        }
        try {
            long value = Long.parseLong(raw, 16);
            if (value < 0 || value > 0xFFFFFFFFL) throw new NumberFormatException("di luar 32-bit");
            send(new IrCodes.Entry("Hex 0x" + raw.toUpperCase(), (int) value, protocol));
        } catch (NumberFormatException e) {
            setStatus("Hex tidak valid: " + raw);
        }
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
    }
}
