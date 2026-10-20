package id.hawx.astro.osint;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import id.hawx.astro.R;
import java.util.List;

public class OsintCategoryAdapter extends RecyclerView.Adapter<OsintCategoryAdapter.VH> {
    public interface OnToolTap { void onTap(OsintFragment.OsintTool tool); }
    private final List<OsintFragment.OsintCategory> cats;
    private final OnToolTap listener;

    public OsintCategoryAdapter(List<OsintFragment.OsintCategory> cats, OnToolTap listener) {
        this.cats = cats; this.listener = listener;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView icon, title, desc, toolList;
        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.osint_cat_icon);
            title = v.findViewById(R.id.osint_cat_title);
            desc = v.findViewById(R.id.osint_cat_desc);
            toolList = v.findViewById(R.id.osint_cat_tools);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.osint_category_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        OsintFragment.OsintCategory c = cats.get(pos);
        h.icon.setText(c.icon);
        h.title.setText(c.title);
        h.desc.setText(c.desc);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.tools.size(); i++) {
            if (i > 0) sb.append("   ");
            sb.append("▸ ").append(c.tools.get(i).name);
        }
        h.toolList.setText(sb.toString());
        h.itemView.setOnClickListener(v -> {
            String[] names = new String[c.tools.size()];
            for (int i = 0; i < c.tools.size(); i++)
                names[i] = c.tools.get(i).name;
            new MaterialAlertDialogBuilder(v.getContext())
                .setTitle(c.icon + " " + c.title)
                .setItems(names, (d, w) -> listener.onTap(c.tools.get(w)))
                .show();
        });
    }

    @Override
    public int getItemCount() { return cats.size(); }
}
