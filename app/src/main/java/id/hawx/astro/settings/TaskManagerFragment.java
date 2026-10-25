package id.hawx.astro.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import id.hawx.astro.MainActivity;
import id.hawx.astro.utils.Core;
import id.hawx.astro.x11.LauncherEntry;
import id.hawx.astro.x11.LauncherStore;
import id.hawx.astro.x11.WebUiLauncher;
import id.hawx.astro.x11.XServerOps;
import id.hawx.astro.x11.XSessionManager;
import id.hawx.astro.x11.XTerminalManager;
import id.hawx.astro.x11.window.WebOverlayWindows;
import id.hawx.astro.x11.window.XOverlayWindows;
import id.hawx.astro.x11.window.XWindowLayer;

/**
 * Task Manager — kategori System (di bawah Desktop), ronde 2026-10-25.
 * Prinsip Hawx: semua service yang ngumpet harus KELIHATAN dan bisa
 * dieksekusi dari sini (Buka / Matikan / Kill). Baris hanya info,
 * ringkas, tanpa tombol inline — semua aksi lewat PopupMenu long-press
 * (pola menu long-press dock/desktop). Tap baris = tidak ada aksi.
 *
 * Timing scan (aturan clean Hawx): scan HANYA saat fragment ini dibuka
 * (onResume) + tombol Refresh manual. Tanpa polling, tanpa watcher,
 * tanpa listener, tanpa scan dari layar lain, tanpa scan sesudah pause.
 * Aksi Kill/Matikan TIDAK me-refresh otomatis — toast mengarahkan ke
 * tombol Refresh, supaya trigger scan tetap dua itu saja.
 */
public class TaskManagerFragment extends Fragment {

    // ---------- perintah scan (chroot, sekali jalan per refresh) ----------

    /** Proses chroot saja: /proc/PID/root yang menunjuk root chroot.
     *  SELF=$$ mengusir shell pemindai sendiri dari daftar. */
    private static final String PS_CMD =
            "echo SELF=$$;"
            + "PIDS=$(ls -l /proc/[0-9]*/root 2>/dev/null | awk '$NF==\"/data/local/astro/release\"{split($9,a,\"/\"); print a[3]}' | paste -sd, -);"
            + "[ -n \"$PIDS\" ] && ps -o pid=,user=,pcpu=,pmem=,args= -p \"$PIDS\" 2>/dev/null";

    /** Baris "WEBUI port pid alive" per PID file webui yang ada. */
    private static final String WEBUI_CMD =
            "for f in /tmp/astro-webui-*.pid; do [ -e \"$f\" ] || continue;"
            + " port=${f#/tmp/astro-webui-}; port=${port%.pid};"
            + " pid=$(head -1 \"$f\" 2>/dev/null | tr -d ' \\t\\r\\n');"
            + " alive=0; [ -n \"$pid\" ] && [ -d /proc/$pid ] && alive=1;"
            + " echo \"WEBUI $port $pid $alive\"; done";

    private static final String MEM_CMD =
            "grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo";

    // ---------- data ----------

    private static final class ProcRow {
        final int pid;
        final String user;
        final String cpu;
        final String mem;
        final float memF;
        final String cmd;

        ProcRow(int pid, String user, String cpu, String mem, float memF, String cmd) {
            this.pid = pid;
            this.user = user;
            this.cpu = cpu;
            this.mem = mem;
            this.memF = memF;
            this.cmd = cmd;
        }
    }

    private static final class WebScan {
        final int port;
        final String pid;
        final boolean pidAlive;
        final boolean listening;

        WebScan(int port, String pid, boolean pidAlive, boolean listening) {
            this.port = port;
            this.pid = pid;
            this.pidAlive = pidAlive;
            this.listening = listening;
        }
    }

    // ---------- state ----------

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int gen = 0;

    private LinearLayout procBox;
    private LinearLayout xBox;
    private LinearLayout webBox;
    private TextView ramText;
    private TextView statusText;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------- UI ----------

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setBackgroundColor(0xFF0A0F1E);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(requireContext());
        title.setText("Task Manager");
        title.setTextColor(0xFFE8EBFA);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(requireContext());
        sub.setText("Proses, display, dan server yang jalan — termasuk yang ngumpet. "
                + "Tahan baris untuk aksi (Buka / Matikan / Kill). Tap baris tidak ngapa-ngapain.");
        sub.setTextColor(0xFF8B93B8);
        sub.setTextSize(12);
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        Button refreshBtn = new Button(requireContext());
        refreshBtn.setText("Refresh");
        refreshBtn.setTextSize(12);
        root.addView(refreshBtn);
        refreshBtn.setOnClickListener(v -> refresh());

        statusText = new TextView(requireContext());
        statusText.setTextColor(0xFF8B93B8);
        statusText.setTextSize(12);
        statusText.setPadding(0, dp(6), 0, dp(6));
        root.addView(statusText);

        TextView ramHdr = new TextView(requireContext());
        ramHdr.setText("RAM");
        ramHdr.setTextColor(0xFFE8EBFA);
        ramHdr.setTextSize(15);
        ramHdr.setTypeface(null, android.graphics.Typeface.BOLD);
        ramHdr.setPadding(0, dp(18), 0, dp(6));
        root.addView(ramHdr);
        ramText = new TextView(requireContext());
        ramText.setTextColor(0xFFE8EBFA);
        ramText.setTextSize(14);
        root.addView(ramText);

        procBox = section(root, "Proses chroot");
        xBox = section(root, "Display & server X");
        webBox = section(root, "Server webui");
        return scroll;
    }

    private LinearLayout section(LinearLayout root, String title) {
        TextView hdr = new TextView(requireContext());
        hdr.setText(title);
        hdr.setTextColor(0xFFE8EBFA);
        hdr.setTextSize(15);
        hdr.setTypeface(null, android.graphics.Typeface.BOLD);
        hdr.setPadding(0, dp(18), 0, dp(6));
        root.addView(hdr);
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);
        return box;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onPause() {
        gen++;
        super.onPause();
    }

    private void setPlaceholder(LinearLayout box, String text) {
        box.removeAllViews();
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextColor(0xFF8B93B8);
        t.setTextSize(12);
        box.addView(t);
    }

    /** Baris info saja: judul + satu sub ringkas. TANPA tombol, TANPA aksi tap. */
    private LinearLayout infoRow(String title, String sub) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setTextColor(0xFFE8EBFA);
        t.setTextSize(14);
        row.addView(t);
        TextView s = new TextView(requireContext());
        s.setText(sub);
        s.setTextColor(0xFF8B93B8);
        s.setTextSize(12);
        s.setMaxLines(2);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(s);
        return row;
    }

    private static String fmtKb(long kb) {
        if (kb >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f GB", kb / 1048576.0);
        }
        return String.format(java.util.Locale.US, "%.0f MB", kb / 1024.0);
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        if (s.length() <= n) return s;
        return s.substring(0, n) + "…";
    }

    // ---------- scan: HANYA onResume + tombol Refresh ----------

    private void refresh() {
        if (!isAdded()) return;
        final int g = ++gen;
        statusText.setText("Memindai…");
        ramText.setText("…");
        setPlaceholder(procBox, "Memindai…");
        setPlaceholder(xBox, "Memindai…");
        setPlaceholder(webBox, "Memindai…");
        final Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            Core core = new Core(appCtx);
            if (!core.isMounted()) {
                mainHandler.post(() -> {
                    if (g != gen || !isAdded()) return;
                    statusText.setText("Chroot belum terpasang — Task Manager butuh chroot.");
                    ramText.setText("—");
                    setPlaceholder(procBox, "Chroot belum terpasang.");
                    setPlaceholder(xBox, "Chroot belum terpasang.");
                    setPlaceholder(webBox, "Chroot belum terpasang.");
                });
                return;
            }
            final Map<String, Long> mem = readMeminfo(core);
            final List<ProcRow> procs = readProcs(core);
            final List<WebScan> webs = readWebui(core);
            final List<LauncherEntry> entries = new LauncherStore(appCtx).list();
            mainHandler.post(() -> {
                if (g != gen || !isAdded()) return;
                renderRam(mem);
                renderProcs(procs);
                renderWebui(webs, entries);
                statusText.setText("Siap — tekan Refresh untuk pindai ulang.");
                requestX(g);
            });
        }).start();
    }

    private static Map<String, Long> readMeminfo(Core core) {
        Map<String, Long> out = new HashMap<>();
        try {
            for (String line : core.customChrootCommand(MEM_CMD)) {
                String t = line == null ? "" : line.trim();
                if (t.isEmpty()) continue;
                String[] p = t.split("\\s+");
                if (p.length < 2) continue;
                String key = p[0].replace(":", "");
                if (!key.equals("MemTotal") && !key.equals("MemAvailable")
                        && !key.equals("SwapTotal") && !key.equals("SwapFree")) continue;
                try {
                    out.put(key, Long.parseLong(p[1]));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<ProcRow> readProcs(Core core) {
        List<ProcRow> out = new ArrayList<>();
        int selfPid = -1;
        try {
            for (String line : core.customChrootCommand(PS_CMD)) {
                String t = line == null ? "" : line.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("SELF=")) {
                    try {
                        selfPid = Integer.parseInt(t.substring(5).trim());
                    } catch (NumberFormatException ignored) {
                    }
                    continue;
                }
                String[] p = t.split("\\s+", 5);
                if (p.length < 5) continue;
                int pid;
                try {
                    pid = Integer.parseInt(p[0]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid == 1 || pid == selfPid) continue;
                if (p[4].startsWith("[")) continue;
                float memF;
                try {
                    memF = Float.parseFloat(p[3]);
                } catch (NumberFormatException e) {
                    memF = 0f;
                }
                out.add(new ProcRow(pid, p[1], p[2], p[3], memF, p[4]));
            }
        } catch (Throwable ignored) {
        }
        // Paling rakus RAM dulu — itu yang dicari saat HP sesak.
        Collections.sort(out, (a, b) -> Float.compare(b.memF, a.memF));
        return out;
    }

    private static List<WebScan> readWebui(Core core) {
        List<WebScan> out = new ArrayList<>();
        try {
            for (String line : core.customChrootCommand(WEBUI_CMD)) {
                String t = line == null ? "" : line.trim();
                if (!t.startsWith("WEBUI ")) continue;
                String[] p = t.split("\\s+");
                if (p.length < 4) continue;
                int port;
                try {
                    port = Integer.parseInt(p[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                String pid = p[2];
                boolean numeric = !pid.isEmpty();
                for (int i = 0; i < pid.length(); i++) {
                    if (!Character.isDigit(pid.charAt(i))) {
                        numeric = false;
                        break;
                    }
                }
                boolean pidAlive = numeric && "1".equals(p[3]);
                boolean listening = WebUiLauncher.isListening(port);
                if (pidAlive || listening) {
                    out.add(new WebScan(port, numeric ? pid : "?", pidAlive, listening));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ---------- render ----------

    private void renderRam(Map<String, Long> mem) {
        Long total = mem.get("MemTotal");
        Long avail = mem.get("MemAvailable");
        Long swapT = mem.get("SwapTotal");
        Long swapF = mem.get("SwapFree");
        if (total == null || avail == null) {
            ramText.setText("RAM tidak terbaca.");
            return;
        }
        String s = "Bebas " + fmtKb(avail) + " / Total " + fmtKb(total);
        if (swapT != null && swapF != null) {
            s += " · Swap bebas " + fmtKb(swapF) + " / " + fmtKb(swapT);
        }
        ramText.setText(s);
    }

    private void renderProcs(List<ProcRow> procs) {
        procBox.removeAllViews();
        if (procs.isEmpty()) {
            setPlaceholder(procBox, "Tidak ada proses chroot (atau ps tidak tersedia).");
            return;
        }
        for (ProcRow p : procs) {
            final ProcRow row = p;
            LinearLayout view = infoRow(trunc(p.cmd, 64),
                    "PID " + p.pid + " · " + p.user + " · CPU " + p.cpu + "% · MEM " + p.mem + "%");
            view.setOnLongClickListener(v -> {
                showProcMenu(v, row);
                return true;
            });
            procBox.addView(view);
        }
    }

    private void showProcMenu(View anchor, ProcRow p) {
        PopupMenu pm = new PopupMenu(requireContext(), anchor);
        pm.getMenu().add(0, 1, 0, "Kill " + p.pid);
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != 1) return false;
            final Context appCtx = requireContext().getApplicationContext();
            new AlertDialog.Builder(requireContext())
                    .setTitle("Kill proses " + p.pid + "?")
                    .setMessage(p.cmd + "\n\nProses akan dihentikan (SIGTERM).")
                    .setPositiveButton("Kill", (dlog, which) -> {
                        new Core(appCtx).threadChrootCommand("kill " + p.pid);
                        Toast.makeText(appCtx, "Kill dikirim ke PID " + p.pid
                                + " — tekan Refresh untuk cek.", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Batal", null)
                    .show();
            return true;
        });
        pm.show();
    }

    private void renderWebui(List<WebScan> webs, List<LauncherEntry> entries) {
        webBox.removeAllViews();
        if (webs.isEmpty()) {
            setPlaceholder(webBox, "Tidak ada server webui yang jalan.");
            return;
        }
        Map<Integer, LauncherEntry> byPort = new HashMap<>();
        for (LauncherEntry e : entries) {
            if (e != null && "webui".equals(e.type) && e.port > 0 && !byPort.containsKey(e.port)) {
                byPort.put(e.port, e);
            }
        }
        Map<Integer, Boolean> overlayVisible = new HashMap<>();
        for (WebOverlayWindows.OverlayInfo o : WebOverlayWindows.listOverlays()) {
            String path = o.path == null ? "" : o.path;
            String prefix = "http://127.0.0.1:";
            if (path.startsWith(prefix)) {
                String rest = path.substring(prefix.length());
                int slash = rest.indexOf('/');
                String portStr = slash >= 0 ? rest.substring(0, slash) : rest;
                try {
                    overlayVisible.put(Integer.parseInt(portStr), o.visible);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (WebScan w : webs) {
            final WebScan scan = w;
            final LauncherEntry entry = byPort.get(w.port);
            String title;
            if (entry != null) {
                title = entry.name;
            } else {
                title = "WebUI :" + w.port + " (orphan)";
            }
            String ov;
            if (!overlayVisible.containsKey(w.port)) {
                ov = "tanpa overlay";
            } else if (overlayVisible.get(w.port)) {
                ov = "overlay tampil";
            } else {
                ov = "overlay tersembunyi";
            }
            String sub = "port " + w.port + " · PID " + w.pid
                    + " · port: " + (w.listening ? "listening" : "tidak listening")
                    + " · " + ov;
            LinearLayout view = infoRow(title, sub);
            view.setOnLongClickListener(v -> {
                showWebMenu(v, scan, entry);
                return true;
            });
            webBox.addView(view);
        }
    }

    private void showWebMenu(View anchor, WebScan w, LauncherEntry stored) {
        PopupMenu pm = new PopupMenu(requireContext(), anchor);
        pm.getMenu().add(0, 1, 0, "Buka");
        pm.getMenu().add(0, 2, 0, "Matikan");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                if (!w.listening) {
                    Toast.makeText(requireContext(), "Server port " + w.port
                            + " belum listening — Task Manager tidak men-start server.",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                LauncherEntry e = stored;
                if (e == null) {
                    e = new LauncherEntry();
                    e.id = "taskmgr-orphan-" + w.port;
                    e.name = "WebUI :" + w.port;
                    e.type = "webui";
                    e.port = w.port;
                    e.urlPath = "/";
                    e.floating = true;
                }
                WebUiLauncher.launch(requireContext(), e);
                return true;
            }
            if (item.getItemId() == 2) {
                WebUiLauncher.killServer(requireContext(), w.port, killed -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Server port " + w.port
                            + " dimatikan — tekan Refresh untuk cek.", Toast.LENGTH_SHORT).show();
                });
                return true;
            }
            return false;
        });
        pm.show();
    }

    // ---------- display X: probe + listing (callback main thread) ----------

    private void requestX(final int g) {
        XSessionManager mgr;
        try {
            mgr = XSessionManager.get(requireContext());
        } catch (Exception e) {
            return;
        }
        final XSessionManager mgrF = mgr;
        final Map<Integer, XServerOps.State> states = new HashMap<>();
        final Map<Integer, String> details = new HashMap<>();
        final List<XSessionManager.DisplayInfo> dispList = new ArrayList<>();
        final int[] done = new int[1];
        for (int d = 0; d <= 4; d++) {
            final int dd = d;
            mgrF.probeDisplay(dd, (state, detail) -> {
                if (g != gen || !isAdded()) return;
                states.put(dd, state);
                details.put(dd, detail);
                done[0]++;
                maybeRenderX(g, mgrF, states, details, dispList, done);
            });
        }
        mgrF.listDisplays(list -> {
            if (g != gen || !isAdded()) return;
            dispList.addAll(list);
            done[0]++;
            maybeRenderX(g, mgrF, states, details, dispList, done);
        });
    }

    private void maybeRenderX(int g, XSessionManager mgr,
                              Map<Integer, XServerOps.State> states,
                              Map<Integer, String> details,
                              List<XSessionManager.DisplayInfo> dispList,
                              int[] done) {
        if (done[0] < 6) return;
        xBox.removeAllViews();
        for (int d = 0; d <= 4; d++) {
            boolean running = states.get(d) == XServerOps.State.RUNNING;
            XSessionManager.DisplayInfo di = null;
            for (XSessionManager.DisplayInfo c : dispList) {
                if (c.display == d) {
                    di = c;
                    break;
                }
            }
            String label;
            if (d == 0) {
                label = "Desktop :0";
            } else if (d <= 2) {
                label = "Terminal :" + d;
            } else if (di != null && di.name != null) {
                label = di.name;
            } else {
                label = "Desktop :" + d;
            }
            List<String> homes = new ArrayList<>();
            if (mgr.hasFullscreenViewer(d)) homes.add("fullscreen");
            if (XWindowLayer.isWindowOpen(d) || XWindowLayer.isWindowMinimized(d)) {
                homes.add("layer");
            }
            if (XOverlayWindows.isShowing(d)) homes.add("decor");
            String viewer = homes.isEmpty() ? "tanpa viewer"
                    : android.text.TextUtils.join(" + ", homes);
            String sub;
            if (running) {
                sub = "server: hidup · " + details.get(d) + " · viewer: " + viewer;
                if (di != null) {
                    sub += " · app: " + (di.appRunning ? "jalan" : "mati");
                }
            } else {
                sub = "server: mati · viewer: " + viewer;
            }
            final int dd = d;
            final String labelF = label;
            final boolean runningF = running;
            LinearLayout view = infoRow(label, sub);
            view.setOnLongClickListener(v -> {
                if (!runningF) {
                    Toast.makeText(requireContext(), "Server :" + dd + " tidak jalan.",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                showXMenu(v, mgr, dd, labelF);
                return true;
            });
            xBox.addView(view);
        }
    }

    private void showXMenu(View anchor, XSessionManager mgr, int d, String label) {
        PopupMenu pm = new PopupMenu(requireContext(), anchor);
        pm.getMenu().add(0, 1, 0, "Buka");
        pm.getMenu().add(0, 2, 0, "Matikan");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                if (d == 0) {
                    mgr.openViewerForDisplay(0);
                } else {
                    MainActivity.openXWindowFrom(requireContext(), d, label);
                }
                return true;
            }
            if (item.getItemId() == 2) {
                XSessionManager.Callback cb = (ok, msg) -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), msg + " — tekan Refresh untuk cek.",
                            Toast.LENGTH_SHORT).show();
                };
                if (XTerminalManager.isTerminalDisplay(d)) {
                    XTerminalManager.get(requireContext()).releaseDisplay(d, cb);
                } else {
                    mgr.stopDisplay(d, cb);
                }
                return true;
            }
            return false;
        });
        pm.show();
    }
}
