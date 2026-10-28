package id.hawx.astro.bluetooth;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.Button;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import id.hawx.astro.R;
import id.hawx.astro.utils.Core;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BluetoothFragment extends Fragment {
    private Core core;
    private BluebinderController bluebinder;
    private TextView output;
    private RecyclerView deviceList;
    private BtDeviceAdapter adapter;
    private List<BtDevice> devices = new ArrayList<>();

    public static class BtDevice {
        String mac, name;
        BtDevice(String mac, String name) { this.mac = mac; this.name = name; }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_bluetooth, container, false);
        core = new Core(requireContext());
        bluebinder = new BluebinderController(requireContext());
        output = v.findViewById(R.id.bt_output);
        deviceList = v.findViewById(R.id.bt_device_list);
        deviceList.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new BtDeviceAdapter(devices, this::onDeviceTap);
        deviceList.setAdapter(adapter);

        refreshBluebinderBtn(v);
        v.findViewById(R.id.bt_btn_bluebinder).setOnClickListener(x -> toggleBluebinder(v));
        v.findViewById(R.id.bt_btn_up).setOnClickListener(x -> runCmd("hciconfig hci0 up && hciconfig", false));
        v.findViewById(R.id.bt_btn_interfaces).setOnClickListener(x -> runCmd("hciconfig", false));
        v.findViewById(R.id.bt_btn_scan).setOnClickListener(x -> runCmd("hcitool scan", true));
        return v;
    }

    private void onDeviceTap(BtDevice d) {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(d.name + "\n" + d.mac)
            .setItems(new String[]{"L2Ping", "L2Ping Custom...", "Browse Services (SDP)", "Get Info"}, (dialog, which) -> {
                if (which == 0) runCmd("l2ping -c 4 " + d.mac, false);
                else if (which == 1) showL2pingCustom(d);
                else if (which == 2) runCmd("sdptool browse " + d.mac, false);
                else runCmd("hcitool info " + d.mac, false);
            })
            .show();
    }

    private void showL2pingCustom(BtDevice d) {
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("contoh: -c 10 -s 100 -f");
        input.setText("-c 4");
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("L2Ping " + d.mac)
            .setMessage("Custom args (kosongkan = default):\n-f = flood, -s = size, -i = interval")
            .setView(input)
            .setPositiveButton("Run", (dlg, w) -> {
                String args = input.getText().toString().trim();
                runCmd("l2ping " + args + " " + d.mac, false);
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    private void refreshBluebinderBtn(View v) {
        android.widget.Button b = v.findViewById(R.id.bt_btn_bluebinder);
        bluebinder.checkStatus((running, hciUp, detail) ->
                b.setText(running ? "Stop Bluebinder" : "Start Bluebinder"));
    }

    private void toggleBluebinder(View v) {
        android.widget.Button b = v.findViewById(R.id.bt_btn_bluebinder);
        b.setEnabled(false);
        output.setText("...");
        bluebinder.checkStatus((running, hciUp, detail) -> {
            if (running) {
                bluebinder.stop((ok, stopDetail) -> {
                    output.setText("$ bluebinder stop\n" + stopDetail);
                    b.setEnabled(true);
                    refreshBluebinderBtn(v);
                });
            } else {
                bluebinder.start((ok, startDetail) -> {
                    output.setText("$ bluebinder start\n" + startDetail);
                    b.setEnabled(true);
                    refreshBluebinderBtn(v);
                });
            }
        });
    }

    private void runCmd(String cmd, boolean isScan) {
        output.setText("$ " + cmd + "\n...");
        new Thread(() -> {
            try {
                ArrayList<String> out = core.customChrootCommand(cmd, true);
                StringBuilder sb = new StringBuilder("$ " + cmd + "\n");
                for (String l : out) sb.append(l).append("\n");
                String result = sb.toString();
                requireActivity().runOnUiThread(() -> {
                    output.setText(result);
                    if (isScan) parseScanResults(out);
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> output.setText("Error: " + e.getMessage()));
            }
        }, "bt-cmd").start();
    }

    private void parseScanResults(List<String> lines) {
        devices.clear();
        Pattern p = Pattern.compile("([0-9A-Fa-f:]{17})\\s+(.+)");
        for (String line : lines) {
            Matcher m = p.matcher(line.trim());
            if (m.find()) devices.add(new BtDevice(m.group(1), m.group(2).trim()));
        }
        adapter.notifyDataSetChanged();
    }
}
