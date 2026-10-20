package id.hawx.astro.osint;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import id.hawx.astro.R;
import id.hawx.astro.utils.Core;
import java.util.ArrayList;
import java.util.List;

public class OsintFragment extends Fragment {
    private Core core;
    private TextView output;
    private TextView statusText;
    private android.widget.ProgressBar progressBar;
    private Button cancelBtn;
    private RecyclerView categoryList;
    private OsintCategoryAdapter adapter;
    private List<OsintCategory> categories = new ArrayList<>();
    private volatile Thread currentThread;
    private volatile boolean cancelled;

    public static class OsintTool {
        String name, desc, inputHint, flagHint, cmdTemplate;
        OsintTool(String name, String desc, String inputHint, String flagHint, String cmdTemplate) {
            this.name = name; this.desc = desc;
            this.inputHint = inputHint; this.flagHint = flagHint;
            this.cmdTemplate = cmdTemplate;
        }
    }

    public static class OsintCategory {
        String icon, title, desc;
        List<OsintTool> tools;
        OsintCategory(String icon, String title, String desc, List<OsintTool> tools) {
            this.icon = icon; this.title = title; this.desc = desc; this.tools = tools;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_osint, container, false);
        core = new Core(requireContext());
        output = v.findViewById(R.id.osint_output);
        statusText = v.findViewById(R.id.osint_status);
        progressBar = v.findViewById(R.id.osint_progress);
        cancelBtn = v.findViewById(R.id.osint_cancel);
        categoryList = v.findViewById(R.id.osint_category_list);
        categoryList.setLayoutManager(new LinearLayoutManager(requireContext()));

        buildCategories();
        adapter = new OsintCategoryAdapter(categories, this::onToolTap);
        categoryList.setAdapter(adapter);
        cancelBtn.setOnClickListener(x -> cancelRun());
        setIdle();
        return v;
    }

    private void buildCategories() {
        List<OsintTool> emailTools = new ArrayList<>();
        emailTools.add(new OsintTool("theHarvester",
            "Email, subdomain & nama dari sumber publik",
            "domain (contoh: example.com)",
            "flag tambahan (opsional, contoh: -b crtsh,otx)",
            "/theHarvester/.venv/bin/theHarvester -d {input} {flags} -l 50"));
        emailTools.add(new OsintTool("holehe",
            "Cek email terdaftar di 120+ situs",
            "email (contoh: test@example.com)",
            "flag tambahan (opsional)",
            "export PATH=$HOME/.local/bin:$PATH && holehe {input} {flags}"));
        categories.add(new OsintCategory("📧", "EMAIL INTEL",
            "Harvest email & cek keberadaan akun", emailTools));

        List<OsintTool> userTools = new ArrayList<>();
        userTools.add(new OsintTool("sherlock",
            "Cari username di ratusan situs & sosmed",
            "username (contoh: johndoe)",
            "flag tambahan (opsional, contoh: --timeout 10)",
            "export PATH=$HOME/.local/bin:$PATH && sherlock {input} {flags}"));
        categories.add(new OsintCategory("👤", "USERNAME",
            "Lacak jejak username di internet", userTools));

        List<OsintTool> phoneTools = new ArrayList<>();
        phoneTools.add(new OsintTool("phoneinfoga",
            "Intel nomor HP: carrier, lokasi, VOIP",
            "nomor (contoh: +6281234567890)",
            "flag tambahan (opsional)",
            "phoneinfoga scan -n {input} {flags}"));
        categories.add(new OsintCategory("📱", "PHONE INTEL",
            "Analisis nomor telepon", phoneTools));

        List<OsintTool> subTools = new ArrayList<>();
        subTools.add(new OsintTool("subfinder",
            "Enumerasi subdomain pasif super cepat",
            "domain (contoh: example.com)",
            "flag tambahan (opsional, contoh: -all -v)",
            "subfinder -d {input} -silent {flags}"));
        categories.add(new OsintCategory("🌐", "SUBDOMAIN",
            "Petakan attack surface domain", subTools));

        List<OsintTool> ipTools = new ArrayList<>();
        ipTools.add(new OsintTool("whois",
            "Info registrasi IP: ASN, org, negara",
            "IP (contoh: 8.8.8.8)",
            "flag tambahan (opsional)",
            "whois {input} {flags}"));
        ipTools.add(new OsintTool("dig",
            "Reverse DNS lookup",
            "IP (contoh: 8.8.8.8)",
            "flag tambahan (opsional, contoh: +short)",
            "dig -x {input} {flags}"));
        categories.add(new OsintCategory("🔍", "IP INTEL",
            "Investigasi alamat IP", ipTools));
    }

    private void onToolTap(OsintTool tool) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 16, 48, 0);

        final android.widget.EditText inputTarget = new android.widget.EditText(requireContext());
        inputTarget.setHint(tool.inputHint);
        inputTarget.setSingleLine(true);
        layout.addView(inputTarget);

        final android.widget.EditText inputFlags = new android.widget.EditText(requireContext());
        inputFlags.setHint(tool.flagHint);
        inputFlags.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        inputFlags.setLayoutParams(lp);
        layout.addView(inputFlags);

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚡ " + tool.name)
            .setMessage(tool.desc)
            .setView(layout)
            .setPositiveButton("RUN", (dlg, w) -> {
                String target = inputTarget.getText().toString().trim();
                if (target.isEmpty()) return;
                String flags = inputFlags.getText().toString().trim();
                String cmd = tool.cmdTemplate.replace("{input}", target).replace("{flags}", flags);
                runCmd(tool.name, cmd);
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    private void setIdle() {
        progressBar.setVisibility(View.GONE);
        cancelBtn.setVisibility(View.GONE);
        statusText.setText("● IDLE — pilih tool di atas");
        statusText.setTextColor(0xFF8890A0);
    }

    private void setRunning(String toolName) {
        cancelled = false;
        progressBar.setVisibility(View.VISIBLE);
        cancelBtn.setVisibility(View.VISIBLE);
        statusText.setText("◉ RUNNING: " + toolName + "...");
        statusText.setTextColor(0xFF7EE787);
    }

    private void setDone(String toolName) {
        progressBar.setVisibility(View.GONE);
        cancelBtn.setVisibility(View.GONE);
        statusText.setText("✓ DONE: " + toolName);
        statusText.setTextColor(0xFF48B050);
    }

    private void setCancelled() {
        progressBar.setVisibility(View.GONE);
        cancelBtn.setVisibility(View.GONE);
        statusText.setText("■ CANCELLED");
        statusText.setTextColor(0xFFF0B000);
    }

    private void cancelRun() {
        cancelled = true;
        if (currentThread != null) currentThread.interrupt();
        requireActivity().runOnUiThread(() -> {
            output.append("\n■ Cancelled by user.\n");
            setCancelled();
        });
    }

    private void runCmd(String toolName, String cmd) {
        setRunning(toolName);
        output.setText("$ " + cmd + "\n");
        currentThread = new Thread(() -> {
            try {
                ArrayList<String> out = core.customChrootCommand(cmd, true);
                if (cancelled) return;
                StringBuilder sb = new StringBuilder("$ " + cmd + "\n");
                sb.append("────────────────────────────────\n");
                for (String l : out) {
                    if (cancelled) return;
                    // Wrap long lines neatly: break at 80 chars
                    String line = l;
                    while (line.length() > 80) {
                        sb.append(line.substring(0, 80)).append("\n");
                        line = line.substring(80);
                    }
                    sb.append(line).append("\n");
                }
                sb.append("────────────────────────────────\n");
                sb.append("✓ ").append(out.size()).append(" baris output\n");
                String result = sb.toString();
                requireActivity().runOnUiThread(() -> {
                    if (cancelled) return;
                    output.setText(result);
                    setDone(toolName);
                });
            } catch (Exception e) {
                if (cancelled) return;
                requireActivity().runOnUiThread(() -> {
                    output.setText("✗ Error: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                    cancelBtn.setVisibility(View.GONE);
                    statusText.setText("✗ ERROR");
                    statusText.setTextColor(0xFFE84040);
                });
            }
        }, "osint-cmd");
        currentThread.start();
    }
}
