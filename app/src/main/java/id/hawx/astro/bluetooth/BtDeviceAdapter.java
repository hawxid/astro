package id.hawx.astro.bluetooth;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import id.hawx.astro.R;
import java.util.List;

public class BtDeviceAdapter extends RecyclerView.Adapter<BtDeviceAdapter.VH> {
    public interface OnTap { void onTap(BluetoothFragment.BtDevice d); }
    private final List<BluetoothFragment.BtDevice> devices;
    private final OnTap listener;

    public BtDeviceAdapter(List<BluetoothFragment.BtDevice> devices, OnTap listener) {
        this.devices = devices; this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(p.getContext()).inflate(android.R.layout.simple_list_item_2, p, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        BluetoothFragment.BtDevice d = devices.get(pos);
        h.t1.setText(d.name);
        h.t2.setText(d.mac);
        h.itemView.setOnClickListener(x -> listener.onTap(d));
    }

    @Override public int getItemCount() { return devices.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView t1, t2;
        VH(View v) { super(v); t1 = v.findViewById(android.R.id.text1); t2 = v.findViewById(android.R.id.text2); }
    }
}
