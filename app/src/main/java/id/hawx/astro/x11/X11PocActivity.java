package id.hawx.astro.x11;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import id.hawx.astro.utils.Core;

/**
 * Milestone 2 — layar X11: pengelola sesi X + daftar launcher tool GUI.
 * (Berevolusi dari X11PocActivity milestone 1; nama kelas dipertahankan agar
 * entri manifest + ikon launcher tidak berubah.)
 *
 * Berisi: kartu sesi (start/stop/buka layar, status, chip watchdog idle
 * 15 menit yang dicek malas saat onResume, penggeser skala Xft.dpi),
 * daftar launcher (tap = jalankan/fokus, Stop per entri, Edit),
 * ekspor/impor JSON, dan panel status diagnostik warisan PoC.
 */
public class X11PocActivity extends AppCompatActivity {

    private Core core;
    private XSessionManager mgr;
    private LauncherStore store;

    private TextView sessionStatus;
    private TextView statusPane;
    private LinearLayout idleChip;
    private LinearLayout rowsBox;
    private TextView dpiLabel;
    private SeekBar dpiSeek;
    private boolean showHidden = false;
    private boolean busy = false;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        core = new Core(this);
        mgr = XSessionManager.get(this);
        store = new LauncherStore(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("X11 — Sesi & Launcher");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Server X hidup malas: hanya saat ada tool GUI yang dibuka.\n"
                + "Syarat chroot: apt install -y xterm xkb-data openbox x11-xserver-utils xfonts-base fonts-dejavu-core");
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);

        // ---- kartu sesi ----
        sessionStatus = new TextView(this);
        sessionStatus.setTypeface(Typeface.MONOSPACE);
        sessionStatus.setTextSize(12);
        sessionStatus.setText("Server: …");
        root.addView(sessionStatus);

        LinearLayout sessRow = new LinearLayout(this);
        sessRow.setOrientation(LinearLayout.HORIZONTAL);
        sessRow.addView(actionButton("Start Server", v -> doStart()));
        sessRow.addView(actionButton("Stop Server", v -> doStop()));
        sessRow.addView(actionButton("Buka Layar X", v -> mgr.openViewer()));
        root.addView(sessRow);

        // ---- PoC multi-display (2026-10-22): display :1 = server terpisah ----
        TextView pocTitle = new TextView(this);
        pocTitle.setText("PoC Multi-Display — display :1 (server terpisah, tanpa WM)");
        pocTitle.setTextSize(13);
        pocTitle.setTypeface(null, Typeface.BOLD);
        pocTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(pocTitle);
        TextView pocHint = new TextView(this);
        pocHint.setText("Spawn :1 + XTerm membuktikan dua server X independen jalan bareng. "
                + "Hasil Probe menampilkan RSS tiap server — dasar plafon jumlah jendela.");
        pocHint.setTextSize(11);
        root.addView(pocHint);
        LinearLayout pocRow = new LinearLayout(this);
        pocRow.setOrientation(LinearLayout.HORIZONTAL);
        pocRow.addView(actionButton("Spawn :1 + XTerm", v -> doPocSpawn()));
        pocRow.addView(actionButton("Jendela Floating :1 (PoC 1)", v -> doPocFloating()));
        pocRow.addView(actionButton("Probe :0 + :1", v -> doPocProbe()));
        pocRow.addView(actionButton("Matikan :1", v -> doPocStop()));
        root.addView(pocRow);

        // ---- chip watchdog idle (bundle: cek malas saat onResume, tanpa polling) ----
        idleChip = new LinearLayout(this);
        idleChip.setOrientation(LinearLayout.HORIZONTAL);
        idleChip.setGravity(Gravity.CENTER_VERTICAL);
        idleChip.setPadding(0, dp(6), 0, 0);
        TextView idleText = new TextView(this);
        idleText.setText("⚠ Server X nganggur > 15 menit");
        idleText.setTextColor(Color.rgb(230, 160, 60));
        idleChip.addView(idleText);
        idleChip.addView(actionButton("Matikan", v -> doStop()));
        idleChip.setVisibility(View.GONE);
        root.addView(idleChip);

        // ---- skala DPI ----
        LinearLayout dpiRow = new LinearLayout(this);
        dpiRow.setOrientation(LinearLayout.HORIZONTAL);
        dpiRow.setGravity(Gravity.CENTER_VERTICAL);
        dpiRow.setPadding(0, dp(10), 0, 0);
        dpiLabel = new TextView(this);
        dpiSeek = new SeekBar(this);
        dpiSeek.setMax(8); // 96..288 langkah 24
        dpiSeek.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        dpiSeek.setProgress((mgr.getDpi() - 96) / 24);
        dpiRow.addView(dpiLabel);
        dpiRow.addView(dpiSeek);
        dpiRow.addView(actionButton("Terapkan", v -> {
            mgr.setDpi(96 + dpiSeek.getProgress() * 24);
            mgr.applyDpiLive((ok, msg) -> toast(msg));
        }));
        root.addView(dpiRow);
        updateDpiLabel();
        dpiSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) { updateDpiLabel(); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                mgr.setDpi(96 + sb.getProgress() * 24);
            }
        });

        // ---- header launcher ----
        LinearLayout lh = new LinearLayout(this);
        lh.setOrientation(LinearLayout.HORIZONTAL);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        lh.setPadding(0, dp(14), 0, dp(4));
        TextView lt = new TextView(this);
        lt.setText("Launcher");
        lt.setTextSize(17);
        lt.setTypeface(null, Typeface.BOLD);
        lt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        lh.addView(lt);
        lh.addView(actionButton("+ Baru", v -> {
            Intent i = new Intent(this, LauncherEditActivity.class);
            startActivity(i);
        }));
        lh.addView(actionButton("Tersembunyi", v -> {
            showHidden = !showHidden;
            rebuildRows();
        }));
        root.addView(lh);

        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(rowsBox);

        // ---- ekspor / impor ----
        LinearLayout ioRow = new LinearLayout(this);
        ioRow.setOrientation(LinearLayout.HORIZONTAL);
        ioRow.setPadding(0, dp(10), 0, 0);
        ioRow.addView(actionButton("Ekspor ke /sdcard", v ->
                new Thread(() -> toast(store.exportToSdcard())).start()));
        ioRow.addView(actionButton("Impor dari /sdcard", v ->
                new Thread(() -> {
                    String msg = store.importFromSdcard();
                    runOnUiThread(() -> { toast(msg); rebuildRows(); });
                }).start()));
        root.addView(ioRow);

        // ---- panel status diagnostik ----
        LinearLayout stHead = new LinearLayout(this);
        stHead.setOrientation(LinearLayout.HORIZONTAL);
        stHead.setGravity(Gravity.CENTER_VERTICAL);
        stHead.setPadding(0, dp(14), 0, 0);
        TextView stt = new TextView(this);
        stt.setText("Status");
        stt.setTypeface(null, Typeface.BOLD);
        stt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        stHead.addView(stt);
        stHead.addView(actionButton("Segarkan", v -> refreshAll()));
        stHead.addView(actionButton("Log Debug", v -> {
            // tampilkan ekor log debug manajer di panel yang sama
            new Thread(() -> {
                String t = X11DebugLog.tail(80);
                runOnUiThread(() -> statusPane.setText("--- x11-debug.log (/sdcard/Astro) ---\n" + t));
            }).start();
        }));
        stHead.addView(actionButton("Hapus Log", v -> {
            X11DebugLog.clear();
            toast("Log debug dibersihkan");
        }));
        root.addView(stHead);

        statusPane = new TextView(this);
        statusPane.setTypeface(Typeface.MONOSPACE);
        statusPane.setTextSize(11);
        statusPane.setText("Menunggu…");
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190)));
        scroll.addView(statusPane);
        root.addView(scroll);

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);

        if (store.lastLoadWarning != null) toast(store.lastLoadWarning);
    }

    @Override
    protected void onResume() {
        super.onResume();
        store = new LauncherStore(this); // baca segar — alasan sama seperti Dashboard
        refreshAll(); // sekalian cek watchdog idle (malas, tanpa polling)
    }

    // ------------------------------------------------------------ actions

    private Button actionButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        return b;
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private LauncherEntry pocEntry() {
        LauncherEntry e = new LauncherEntry();
        e.id = "poc-d1";
        e.name = "XTerm (PoC :1)";
        e.command = "xterm";
        e.type = "gui";
        return e;
    }

    private void doPocSpawn() {
        if (busy) return;
        busy = true;
        sessionStatus.setText("⏳ PoC: spawn display :1 + xterm…");
        mgr.launchOnDisplay(pocEntry(), 1, (ok, msg) -> {
            busy = false;
            toast(msg);
            refreshAll();
        });
    }

    /** PoC 1: spawn :1 lalu buka jendela floating tertanam di desktop Astro. */
    private void doPocFloating() {
        if (busy) return;
        busy = true;
        toast("Menyiapkan display :1 + jendela floating…");
        mgr.launchOnDisplay(pocEntry(), 1, (ok, msg) -> {
            busy = false;
            id.hawx.astro.x11.window.XWindowActivity.launch(this, 1);
        }, false);
    }

    private void doPocProbe() {
        mgr.probe((st0, det0) -> mgr.probeDisplay(1, (st1, det1) ->
                sessionStatus.setText("Probe :0 → " + st0 + " (" + det0 + ")\n"
                        + "Probe :1 → " + st1 + " (" + det1 + ")")));
    }

    private void doPocStop() {
        if (busy) return;
        busy = true;
        sessionStatus.setText("⏳ PoC: mematikan display :1…");
        mgr.stopEntry(pocEntry(), (ok, msg) ->
                mgr.stopDisplay(1, (ok2, msg2) -> {
                    busy = false;
                    toast(msg2);
                    refreshAll();
                }));
    }

    private void doStart() {
        if (busy) return;
        busy = true;
        sessionStatus.setText("⏳ Menyiapkan server X…");
        mgr.start((ok, msg) -> {
            busy = false;
            toast(msg);
            refreshAll();
        });
    }

    private void doStop() {
        if (busy) return;
        busy = true;
        sessionStatus.setText("⏳ Menghentikan sesi X…");
        mgr.stopSession((ok, msg) -> {
            busy = false;
            toast(msg);
            refreshAll();
        });
    }

    private void doLaunch(LauncherEntry e) {
        mgr.launch(e, (ok, msg) -> {
            toast(msg);
            refreshAll();
        });
    }

    // ------------------------------------------------------------ render

    private void updateDpiLabel() {
        int dpi = 96 + dpiSeek.getProgress() * 24;
        dpiLabel.setText("Skala teks GTK (DPI): " + dpi + "  ");
    }

    private void refreshAll() {
        mgr.probe((state, detail) -> {
            if (!busy) {
                sessionStatus.setText("Server: " + (state == XSessionManager.State.RUNNING ? "JALAN" : "BERHENTI")
                        + (detail.isEmpty() ? "" : " • " + detail));
            }
            boolean idle = state == XSessionManager.State.RUNNING
                    && mgr.idleMillis() >= XSessionManager.IDLE_LIMIT_MS;
            idleChip.setVisibility(idle ? View.VISIBLE : View.GONE);
        });
        rebuildRows();
        refreshStatusPane();
    }

    private void rebuildRows() {
        new Thread(() -> {
            List<LauncherEntry> list = store.list();
            Map<String, Boolean> running = new HashMap<>();
            for (LauncherEntry e : list) {
                if ("gui".equals(e.type)) {
                    running.put(e.id, mgr.entryRunningSync(e.id, e.firstWord()));
                }
            }
            runOnUiThread(() -> {
                rowsBox.removeAllViews();
                for (LauncherEntry e : list) {
                    if (e.hidden && !showHidden) continue;
                    rowsBox.addView(buildRow(e, Boolean.TRUE.equals(running.get(e.id))));
                }
                if (rowsBox.getChildCount() == 0) {
                    TextView empty = new TextView(this);
                    empty.setText("(belum ada launcher — ketuk + Baru)");
                    rowsBox.addView(empty);
                }
            });
        }).start();
    }

    private View iconView(LauncherEntry e) {
        if (e.icon != null && e.icon.startsWith("file:")) {
            File f = new File("/sdcard/Astro/icons", e.icon.substring(5));
            if (f.exists()) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
                iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
                return iv;
            }
        }
        TextView tv = new TextView(this);
        String label = (e.icon != null && !e.icon.trim().isEmpty() && !e.icon.startsWith("file:"))
                ? e.icon.trim()
                : (e.name == null || e.name.isEmpty() ? "?" : e.name.substring(0, 1).toUpperCase());
        tv.setText(label);
        tv.setTextSize(22);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return tv;
    }

    private View buildRow(LauncherEntry e, boolean running) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        row.addView(iconView(e));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(e.name + (e.hidden ? " (tersembunyi)" : ""));
        name.setTypeface(null, Typeface.BOLD);
        mid.addView(name);
        TextView sub = new TextView(this);
        sub.setText(("gui".equals(e.type) ? "GUI" : "CLI") + " • " + e.command);
        sub.setTextSize(11);
        sub.setSingleLine(true);
        mid.addView(sub);
        if ("gui".equals(e.type)) {
            TextView dot = new TextView(this);
            dot.setText(running ? "● berjalan" : "○ berhenti");
            dot.setTextSize(11);
            dot.setTextColor(running ? Color.rgb(60, 200, 120) : Color.GRAY);
            mid.addView(dot);
        }
        row.addView(mid);

        if (running) {
            row.addView(actionButton("Stop", v -> mgr.stopEntry(e, (ok, msg) -> {
                toast(msg);
                refreshAll();
            })));
        }
        row.addView(actionButton("Edit", v -> {
            Intent i = new Intent(this, LauncherEditActivity.class);
            i.putExtra("entry_id", e.id);
            startActivity(i);
        }));

        row.setOnClickListener(v -> doLaunch(e));
        return row;
    }

    // ------------------------------------------------------------ panel status

    private void refreshStatusPane() {
        new Thread(() -> {
            String out;
            try {
                out = core.executeCommand(statusScript());
            } catch (Exception e) {
                out = "ERROR: " + e;
            }
            String result = out;
            runOnUiThread(() -> statusPane.setText(result));
        }).start();
    }

    private String statusScript() {
        String root = Core.CHROOT_ROOT;
        // Kebenaran server = listener :0 di /proc/net/unix (selaras probe
        // manajer); file PID kini berisi "PID starttime" — ambil kolom 1.
        return "echo '--- server ---'; "
                + "set -- $(cat " + root + "/tmp/astro-x11-server.pid 2>/dev/null); P=$1; "
                + "INO=$(awk -v s='" + root + "/tmp/.X11-unix/X0' -v a='@" + root + "/tmp/.X11-unix/X0' '$8==s || $8==a {print $7; exit}' /proc/net/unix 2>/dev/null); "
                + "if [ -n \"$INO\" ]; then echo \"jalan — listener :0 aktif (pid tercatat: ${P:-?})\"; [ -n \"$P\" ] && grep VmRSS /proc/$P/status 2>/dev/null; "
                + "elif [ -n \"$P\" ] && [ -d /proc/$P ]; then echo \"proses $P hidup tapi tidak mendengarkan (zombie)\"; "
                + "else echo '(tidak jalan)'; fi; "
                + "echo '--- socket ---'; ls " + root + "/tmp/.X11-unix/ 2>/dev/null || echo '(tidak ada socket)'; "
                + "echo '--- openbox ---'; pgrep -x openbox >/dev/null 2>&1 && echo 'openbox jalan' || echo '(openbox tidak jalan)'; "
                + "echo '--- xkb data ---'; ls " + root + "/usr/share/X11/xkb >/dev/null 2>&1 && echo 'xkb-data ADA' || echo 'xkb-data BELUM ADA'; "
                + "echo '--- font x11 ---'; ls " + root + "/usr/share/fonts/X11/misc/fonts.dir >/dev/null 2>&1 && echo 'fonts.dir ADA (font core X siap didaftarkan)' || echo 'fonts.dir BELUM ADA (xset fp+ akan ditolak server)'; "
                + "echo '--- log sesi (font/openbox) ---'; tail -4 " + root + "/tmp/astro-x11-session.log 2>/dev/null || echo '(belum ada)'; "
                + "echo '--- log server (ekor) ---'; tail -6 " + root + "/tmp/x11-server.log 2>/dev/null || echo '(belum ada log)'";
    }
}
