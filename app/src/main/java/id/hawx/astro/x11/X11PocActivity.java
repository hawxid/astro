package id.hawx.astro.x11;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import id.hawx.astro.utils.Core;

/**
 * PoC fondasi X11 (milestone 1) — BUKAN fitur final.
 *
 * Membuktikan: server X Lorie (modul :lorie, snapshot termux-x11) bisa hidup
 * on-demand dari shell root Astro, socket lahir di tmp chroot, klien X dari
 * chroot (xterm) tampil di MainActivity bawaan lorie, dan server mati bersih.
 * Prinsip user: socket X hanya dibuat saat launcher ke tool GUI dipicu.
 */
public class X11PocActivity extends AppCompatActivity {

    private static final String CHROOT = Core.CHROOT_ROOT; // rootfs asli: /data/local/astro/release
    private static final String LOG = CHROOT + "/tmp/x11-server.log";

    private Core core;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        core = new Core(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("X11 PoC — Fondasi");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Syarat di chroot: apt install -y xterm xkb-data\n"
                + "Alur tes: 1) Start Server  2) Buka Layar X  3) Jalankan xterm");
        hint.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(hint);

        root.addView(makeButton("1 · Start Server X", v -> runRoot(startScript(), true)));
        root.addView(makeButton("2 · Buka Layar X", v -> openDisplay()));
        root.addView(makeButton("3 · Jalankan xterm (chroot)", v -> runXterm()));
        root.addView(makeButton("Stop Server", v -> runRoot(stopScript(), true)));
        root.addView(makeButton("Segarkan Status", v -> runRoot(statusScript(), true)));

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(12);
        status.setPadding(0, pad, 0, 0);
        status.setText("Menunggu…");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll);

        setContentView(root);
        runRoot(statusScript(), true);
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        return b;
    }

    private String startScript() {
        return "mkdir -p " + CHROOT + "/tmp; "
                + "if pgrep -f termux-x11 >/dev/null; then echo '== server SUDAH jalan =='; "
                + "else TMPDIR=" + CHROOT + "/tmp "
                + "XKB_CONFIG_ROOT=" + CHROOT + "/usr/share/X11/xkb "
                + "CLASSPATH=$(pm path id.hawx.astro | cut -d: -f2) "
                + "setsid /system/bin/app_process / --nice-name=termux-x11 "
                + "com.termux.x11.CmdEntryPoint :0 >" + LOG + " 2>&1 </dev/null & "
                + "echo '== server dipicu =='; fi; "
                + "sleep 2; echo '--- proses ---'; pgrep -af termux-x11; "
                + "echo '--- socket ---'; ls -la " + CHROOT + "/tmp/.X11-unix/ 2>/dev/null || echo '(socket belum lahir)'; "
                + "echo '--- log server ---'; tail -15 " + LOG + " 2>/dev/null";
    }

    private String stopScript() {
        return "pkill -f termux-x11; sleep 1; "
                + "if pgrep -f termux-x11 >/dev/null; then echo '== server MASIH jalan =='; "
                + "else echo '== server mati bersih =='; fi; "
                + "ls " + CHROOT + "/tmp/.X11-unix/ 2>/dev/null || echo '(direktori socket bersih)'";
    }

    private String statusScript() {
        return "echo '--- proses ---'; pgrep -af termux-x11 || echo '(server tidak jalan)'; "
                + "echo '--- socket ---'; ls -la " + CHROOT + "/tmp/.X11-unix/ 2>/dev/null || echo '(tidak ada socket)'; "
                + "echo '--- xkb data ---'; ls " + CHROOT + "/usr/share/X11/xkb >/dev/null 2>&1 && echo 'xkb-data ADA' || echo 'xkb-data BELUM ADA (apt install -y xkb-data)'; "
                + "echo '--- xterm ---'; ls " + CHROOT + "/usr/bin/xterm >/dev/null 2>&1 && echo 'xterm ADA' || echo 'xterm BELUM ADA (apt install -y xterm)'; "
                + "echo '--- log server (ekor) ---'; tail -8 " + LOG + " 2>/dev/null || echo '(belum ada log)'";
    }

    private void runRoot(String script, boolean showInStatus) {
        if (showInStatus) status.setText("Memproses…");
        new Thread(() -> {
            String out;
            try {
                out = core.executeCommand(script);
            } catch (Exception e) {
                out = "ERROR: " + e;
            }
            String result = out;
            runOnUiThread(() -> status.setText(result));
        }).start();
    }

    private void runXterm() {
        status.setText("Mengirim perintah xterm ke chroot…");
        core.threadChrootCommand("export DISPLAY=:0; "
                + "(xterm >/tmp/xterm.log 2>&1 &) ; sleep 1; "
                + "echo '--- xterm.log ---'; cat /tmp/xterm.log 2>/dev/null; "
                + "echo '--- proses xterm ---'; pgrep -af xterm || echo '(xterm tidak jalan)'");
        // threadChrootCommand tidak mengembalikan output; status dicek manual
        new Thread(() -> {
            try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            runOnUiThread(() -> runRoot(statusScript(), true));
        }).start();
        Toast.makeText(this, "xterm dipicu — cek Layar X", Toast.LENGTH_SHORT).show();
    }

    private void openDisplay() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("id.hawx.astro", "com.termux.x11.MainActivity"));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal buka Layar X: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
