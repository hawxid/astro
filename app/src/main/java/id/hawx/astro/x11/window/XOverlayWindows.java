package id.hawx.astro.x11.window;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.stryker.terminal.frontend.session.view.extrakey.XKeyRouter;

import java.util.HashMap;
import java.util.Map;

import id.hawx.astro.AstroApp;

/**
 * Rumah jendela melayang kelahiran terminal (xrun) — METODE SAMA
 * PERSIS dengan desktop (gas Hawx 2026-10-24): layer View di dalam
 * window activity sendiri, BUKAN lagi WindowManager overlay sistem.
 * Dulu overlay sistem (TYPE_APPLICATION_OVERLAY) gagal diikat di
 * atas softkey justru karena beda layer/window dengan terminal:
 * dua window = dua otoritas layout yang tarik-menarik (jitter).
 * Kini jendela ini anak decor NeoTermActivity: satu window, satu
 * otoritas layout — keyboard buka, layout activity bergerak, dan
 * jendela dikunci di atas softkey lewat margin di window yang
 * sama. Konsekuensi yang disetujui Hawx: jendela terminal TAK
 * bisa dibawa keluar activity terminal (keluar terminal =
 * jendela ikut tersembunyi; sesi X tetap hidup, balik ke
 * terminal muncul lagi). Izin "tampil di atas aplikasi lain"
 * tidak lagi dibutuhkan buat floating terminal.
 *
 * Yang manggil terakhir jadi tuan rumah: show() menutup jendela
 * layer desktop display yang sama, dan XWindowLayer.openWindow
 * menyembunyikan jendela terminal display yang sama.
 *
 * Strip softkey TIDAK diduplikasi: bar bawaan terminal melayani
 * jendela ini lewat XKeyRouter (modul terminal). Fokus IME pun
 * native satu window: ketuk LorieView = fokus ke xterm, ketuk
 * terminal = fokus ke terminal (tak ada lagi flip
 * NOT_FOCUSABLE / broadcast lepas-fokus antar window).
 */
public class XOverlayWindows implements XFloatingWindow.Listener, XWindowHost.Listener {

    private static final Map<Integer, XOverlayWindows> sByDisplay = new HashMap<>();
    private static XOverlayWindows sActive;

    private final Activity activity;
    private final int display;
    private final XWindowHost host;
    private final XFloatingWindow win;
    private final FrameLayout container;
    private boolean attached;
    // Rect penuh yang diinginkan selama episode fit anchor
    // (top + height); -1 = tidak ada episode. Disimpan SEKALI
    // saat enforceAnchor pertama kali mengubah rect karena anchor
    // (jangan timpa dengan rect hasil fit). Rect manual user
    // (drag/resize) menghapus simpanan ini via onUserRectChanged.
    private int anchorSavedTop = -1;
    private int anchorSavedH = -1;
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    // ---------------- statis ----------------

    public static boolean isShowing(int display) {
        XOverlayWindows o = sByDisplay.get(display);
        return o != null && o.attached
                && o.win.getVisibility() == android.view.View.VISIBLE;
    }

    /**
     * Lepas input dari jendela terminal (dipanggil tombol keyboard
     * bar terminal). Dulu membalik flag NOT_FOCUSABLE window
     * overlay; kini satu window dengan terminal, jadi cukup
     * bersihkan router — fokus IME pindah native lewat
     * ketuk/showSoftInput.
     */
    public static void releaseFocus() {
        XKeyRouter.clear();
    }

    // ---- Patokan TUNGGAL pengunci di atas softkey ----
    // Patokannya: tepi bawah TerminalView (= tepi atas bar EK,
    // karena TerminalView layout_above extra_keys), dalam
    // koordinat decor. Penegaknya listener layout TerminalView
    // yang jalan TIAP event TANPA debounce (ronde debug 3,
    // kunci relatif ke EK): selama animasi keyboard, rect
    // TerminalView bergerak frame-by-frame dan jendela ikut
    // dihitung ulang tiap frame, sehingga jendela meluncur
    // bersama EK — bukan lompat sekali setelah settle seperti
    // di ronde debounce 150 md. Ini aman dari loop: rect
    // TerminalView TIDAK bergantung pada margin jendela
    // (jendela ada di container overlay terpisah, tidak
    // memengaruhi layout tab), dan fit() idempotent — bila
    // fitted == rect saat ini, tidak ada setLayoutParams,
    // jadi tidak ada layout baru (paling banyak satu pass
    // no-op per perubahan). Pelanggaran < 1dp diabaikan.
    // Watcher 400 md tinggal bertugas menempelkan ulang
    // listener kalau view TerminalView-nya berganti (ganti
    // tab/dll), TANPA menggerakkan jendela.
    private static android.view.View sAnchorView;
    private static android.view.ViewTreeObserver.OnGlobalLayoutListener sAnchorListener;

    private static void enforceAnchor() {
        if (sAnchorView == null || sByDisplay.isEmpty()) return;
        int[] r = terminalRectInDecor();
        if (r == null) return;
        int anchorT = r[1];
        int anchorB = r[3];
        float density = sAnchorView.getResources().getDisplayMetrics().density;
        int threshPx = Math.round(1 * density);
        for (XOverlayWindows o : sByDisplay.values()) {
            if (!o.attached
                    || o.win.getVisibility() != android.view.View.VISIBLE) continue;
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) o.win.getLayoutParams();
            int anchorH = anchorB - anchorT;
            if (o.win.isMaximized()) {
                // Maximize ikut anchor (aturan sama kaya floating):
                // set LANGSUNG ke rect maximize (margin 6dp), TANPA
                // lewat logika simpanan/fit — simpanan anchor tidak
                // disentuh dan tidak boleh mengecilkan window
                // maximize. ATAS = bawah status bar (sumber yang
                // sama dengan supplier maximize di ctor, lewat
                // maximizeTopPx); bawah tetap anchorB. Keyboard
                // buka -> anchorB naik -> window ngalah tiap frame
                // lewat jalur debug 3 ini (tanpa debounce);
                // keyboard tutup -> window tumbuh balik. Toolbar
                // Stryker ketutup saat maximize, balik lewat ❐.
                // Pengaman 200dp dihitung dari tinggi maximize
                // sebenarnya (anchorB - atasBaru), bukan anchorH:
                // dengan atas naik ke status bar, window LEBIH
                // tinggi dari anchor, jadi guard anchorH akan
                // menahan kasus yang sebenarnya masih muat.
                int maxBaseT = maximizeTopPx(anchorT);
                int maxH0 = anchorB - maxBaseT;
                if (maxH0 < Math.round(200 * density)) continue;
                int m = Math.round(6 * density);
                int anchorL = r[0];
                int anchorR = r[2];
                if (anchorR - anchorL <= 2 * m || maxH0 <= 2 * m) continue;
                int maxL = anchorL + m;
                int maxT = maxBaseT + m;
                int maxW = anchorR - anchorL - 2 * m;
                int maxH = maxH0 - 2 * m;
                if (Math.abs(maxL - lp.leftMargin) < threshPx) maxL = lp.leftMargin;
                if (Math.abs(maxT - lp.topMargin) < threshPx) maxT = lp.topMargin;
                if (Math.abs(maxW - lp.width) < threshPx) maxW = lp.width;
                if (Math.abs(maxH - lp.height) < threshPx) maxH = lp.height;
                if (maxL == lp.leftMargin && maxT == lp.topMargin
                        && maxW == lp.width && maxH == lp.height) continue;
                lp.leftMargin = maxL;
                lp.topMargin = maxT;
                lp.width = maxW;
                lp.height = maxH;
                o.win.setLayoutParams(lp);
                continue;
            }
            int curTop = lp.topMargin;
            int curH = lp.height;
            // MEKANISME FINAL (persetujuan user): rect yang diinginkan
            // vs rect yang ditampilkan, satu mekanisme — bukan dua
            // restore terpisah. desired = simpanan bila ada, bila
            // tidak rect saat ini. fitted = fit(desired, anchor):
            // tinggi > anchorH -> (anchorT, anchorH) — dorong sampai
            // mentok lalu potong; muat -> top = max(anchorT,
            // min(desTop, anchorB - desH)), tinggi tetap. fitted
            // selalu memenuhi kedua tepi sekaligus, jadi tidak ada
            // osilasi. Window maximize TIDAK di-skip lagi: cabang
            // maximize di atas set langsung ke rect anchor (tanpa
            // simpanan/fit), jadi maximize ikut aturan anchor yang sama.
            boolean adaSimpanan = o.anchorSavedH > 0;
            int desTop = adaSimpanan ? o.anchorSavedTop : curTop;
            int desH = adaSimpanan ? o.anchorSavedH : curH;
            int fitTop;
            int fitH;
            if (desH > anchorH) {
                // Pengaman: anchor lebih pendek dari 200dp — biarkan
                // jendela apa adanya, catat saja (tanpa simpan).
                if (anchorH < Math.round(200 * density)) {
                    continue;
                }
                fitTop = anchorT;
                fitH = anchorH;
            } else {
                fitTop = Math.max(anchorT, Math.min(desTop, anchorB - desH));
                fitH = desH;
            }
            // Kembali ke simpanan? fitted == saved (rect asli muat
            // penuh di anchor baru) -> tampilkan saved, hapus
            // simpanan. Perilaku yang user minta: saat keyboard
            // nutup, jendela TURUN sedikit demi sedikit mengikuti
            // anchor (fitted.top = anchorB - height membesar seiring
            // anchorB), lalu top DAN tinggi balik persis ke semula.
            boolean kembaliKeSaved = adaSimpanan
                    && fitTop == desTop && fitH == desH;
            if (kembaliKeSaved) {
                o.anchorSavedH = -1;
                o.anchorSavedTop = -1;
            }
            // Threshold 1dp per dimensi: perubahan kecil diabaikan
            // (tidak gerak, tidak log).
            int newTop = fitTop;
            int newH = fitH;
            if (Math.abs(newTop - curTop) < threshPx) newTop = curTop;
            if (Math.abs(newH - curH) < threshPx) newH = curH;
            if (newTop == curTop && newH == curH) continue;
            // Simpan SEKALI saat pertama kali mengubah rect karena
            // anchor (jangan timpa simpanan dengan rect hasil fit).
            // Rect maximize disimpan terpisah di XFloatingWindow dan
            // tidak mencampuri simpanan ini: un-maximize
            // mengembalikan rect pra-maximize — bila itu rect hasil
            // fit, episode tetap utuh; bila berbeda (manual),
            // onUserRectChanged yang membersihkannya.
            if (!adaSimpanan) {
                o.anchorSavedTop = curTop;
                o.anchorSavedH = curH;
            }
            lp.topMargin = newTop;
            lp.height = newH;
            o.win.setLayoutParams(lp);
            // Log ANCHOR temporer sampai verdict jitter lulus: satu
            // baris per perubahan yang benar-benar dieksekusi.
            // top berubah = dorong, tinggi berubah = potong,
            // kembali ke saved = balik.
            String kind;
            if (kembaliKeSaved) {
                kind = "balik";
            } else if (newTop != curTop && newH != curH) {
                kind = "dorong+potong";
            } else if (newTop != curTop) {
                kind = "dorong";
            } else {
                kind = "potong";
            }
        }
    }

    private static void attachAnchorListener(android.view.View tv) {
        if (tv == sAnchorView && sAnchorListener != null) return;
        detachAnchorListener();
        sAnchorView = tv;
        // Tanpa debounce: tiap layout event langsung menegakkan
        // anchor (tracking per-frame ke batas EK).
        sAnchorListener = () -> enforceAnchor();
        tv.getViewTreeObserver().addOnGlobalLayoutListener(sAnchorListener);
    }

    private static void detachAnchorListener() {
        if (sAnchorView != null && sAnchorListener != null) {
            try {
                sAnchorView.getViewTreeObserver()
                        .removeOnGlobalLayoutListener(sAnchorListener);
            } catch (Throwable ignored) {
            }
        }
        sAnchorView = null;
        sAnchorListener = null;
    }

    private static final android.os.Handler sWatch =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean sWatchRunning;
    private static final Runnable sWatchTask = () -> {
        sWatchRunning = false;
        if (sByDisplay.isEmpty()) {
            detachAnchorListener();
            return;
        }
        Activity fg = AstroApp.foregroundActivity();
        if (fg != null
                && fg.getClass().getName().contains("NeoTermActivity")) {
            android.view.View tv = findTerminalView(fg.getWindow().getDecorView());
            if (tv != null && tv.getWidth() > 0 && tv.getHeight() > 0) {
                attachAnchorListener(tv);
            }
        }
        startAnchorWatch();
    };

    private static void startAnchorWatch() {
        if (!sWatchRunning && !sByDisplay.isEmpty()) {
            sWatchRunning = true;
            sWatch.postDelayed(sWatchTask, 400);
        }
    }

    /** Rect TerminalView dalam koordinat decor (null = tak ada). */
    private static int[] terminalRectInDecor() {
        Activity fg = AstroApp.foregroundActivity();
        if (fg == null
                || !fg.getClass().getName().contains("NeoTermActivity")) return null;
        android.view.View decorView = fg.getWindow().getDecorView();
        android.view.View tv = findTerminalView(decorView);
        if (tv == null || tv.getWidth() <= 0 || tv.getHeight() <= 0) return null;
        int[] dl = new int[2];
        decorView.getLocationOnScreen(dl);
        int[] tl = new int[2];
        tv.getLocationOnScreen(tl);
        return new int[]{
                tl[0] - dl[0], tl[1] - dl[1],
                tl[0] - dl[0] + tv.getWidth(), tl[1] - dl[1] + tv.getHeight()};
    }

    /** Tinggi status bar px (0 = tak bisa di-resolve). Sumber sama
     *  dengan desktopUsableRect layer (resource sistem android). */
    private static int statusBarHeightPx() {
        if (sAnchorView == null) return 0;
        android.content.res.Resources res = sAnchorView.getResources();
        int id = res.getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? res.getDimensionPixelSize(id) : 0;
    }

    /** Atas maximize decor: bawah status bar; fallback atas anchor
     *  bila tinggi status bar tak ketemu (tak pernah crash, tak
     *  pernah negatif). SATU sumber dipakai supplier maximize DAN
     *  cabang maximize enforceAnchor — dua konsumen, satu nilai. */
    private static int maximizeTopPx(int anchorT) {
        int sb = statusBarHeightPx();
        return sb > 0 ? sb : anchorT;
    }

    /** Sembunyikan jendela terminal display ini (server tetap hidup). */
    public static void hideForDisplay(int display) {
        XOverlayWindows o = sByDisplay.get(display);
        if (o != null) o.hide();
    }

    /** Tutup total jendela terminal display ini (dipakai watcher lifecycle R1). */
    public static void closeForDisplay(int display) {
        XOverlayWindows o = sByDisplay.get(display);
        if (o == null) return;
        o.close();
        if (sActive == o) {
            sActive = null;
            XKeyRouter.clear();
        }
        o.refocusTerminalSoon();
    }

    /**
     * Tampilkan jendela terminal buat display ini (layer decor).
     *
     * @return false bila foreground bukan NeoTermActivity —
     *         pemanggil silakan jatuh ke jalur desktop.
     */
    public static boolean show(Context ctx, int display, String title) {
        Activity act = AstroApp.foregroundActivity();
        if (act == null
                || !act.getClass().getName().contains("NeoTermActivity")) {
            return false;
        }
        XOverlayWindows existing = sByDisplay.get(display);
        if (existing != null) {
            if (existing.container.getParent() == null) {
                // Decor lama mati (activity recreate) — buang
                // zombie, buat baru (sesi X selamat, posisi reset).
                existing.host.detach();
                sByDisplay.remove(display);
            } else if (!existing.host.connected()) {
                // Pisah sebagian (log 35): instance lama tapi host
                // putus / server berganti generasi — raise diam-diam
                // HANYA mengulang jebakan setengah-terbuka. Buang
                // instance lama, jatuh ke jalur instance baru di
                // bawah (attach ulang + knock baru).
                existing.close();
            } else {
                existing.raise();
                return true;
            }
        }
        XWindowLayer.closeForDisplay(display); // rumah lama melepas
        new XOverlayWindows(act, display, title).attach();
        return true;
    }

    // ---------------- instance ----------------

    private XOverlayWindows(Activity act, int display, String title) {
        this.activity = act;
        this.display = display;

        host = new XWindowHost(act, display);
        host.setListener(this);
        win = new XFloatingWindow(act, host, title);
        win.setListener(this);
        // Drag jendela dikunci ke rect TerminalView hidup (satu
        // patokan dengan enforceAnchor, dalam koordinat decor).
        win.setAnchorSupplier(() -> terminalRectInDecor());
        // Maximize: sisi + bawah = rect anchor hidup, ATAS = bawah
        // status bar (bukan atas anchor — atas anchor = bawah
        // toolbar, gap-nya ketinggian menurut verdict device).
        // Supplier terpisah biar drag clamp tak berubah.
        win.setMaximizeBoundsSupplier(() -> {
            int[] r = terminalRectInDecor();
            if (r == null) return null;
            return new int[]{r[0], maximizeTopPx(r[1]), r[2], r[3]};
        });

        // Kontainer selayar di decor: transparan + tak menghalangi
        // sentuhan di luar jendela (jatuh ke terminal di bawahnya).
        container = new FrameLayout(act);
        container.setClickable(false);
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getRealMetrics(m);
        int w = (int) (m.widthPixels * 0.44f);
        int h = (int) (m.heightPixels * 0.275f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = dp(14) + (display - 1) * dp(26);
        lp.topMargin = dp(70) + (display - 1) * dp(30);
        win.setLayoutParams(lp);
        container.addView(win);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void attach() {
        sByDisplay.put(display, this);
        android.view.ViewGroup decor =
                (android.view.ViewGroup) activity.getWindow().getDecorView();
        decor.addView(container, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        attached = true;
        host.attach();
        setActive();
        startAnchorWatch();
    }

    private void raise() {
        if (container.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) container.getParent())
                    .bringChildToFront(container);
        }
        win.setVisibility(android.view.View.VISIBLE);
        setActive();
    }

    /** — : sembunyikan jendela saja; sesi X tetap hidup. */
    public void hide() {
        win.setVisibility(android.view.View.GONE);
        if (sActive == this) {
            sActive = null;
            XKeyRouter.clear();
        }
        refocusTerminalSoon();
    }

    /**
     * Setelah jendela turun, kembalikan fokus + keyboard ke
     * TerminalView foreground (satu window, jadi ini sekadar
     * memastikan IME pulang — tanpa drama antar window).
     */
    private void refocusTerminalSoon() {
        ui.postDelayed(() -> {
            Activity act = AstroApp.foregroundActivity();
            if (act == null
                    || !act.getClass().getName().contains("NeoTermActivity")) return;
            android.view.View term =
                    findTerminalView(act.getWindow().getDecorView());
            if (term == null) return;
            term.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(term, 0);
        }, 150);
    }

    private static android.view.View findTerminalView(android.view.View v) {
        if (v.getClass().getName().endsWith("TerminalView")) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View f = findTerminalView(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** ✕ / host minta tutup: lepas total (server diurus pemanggil). */
    private void close() {
        win.setVisibility(android.view.View.GONE);
        if (container.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) container.getParent())
                    .removeView(container);
        }
        attached = false;
        host.detach();
        sByDisplay.remove(display);
        id.hawx.astro.x11.XTerminalManager.get(activity).unwatchXrunDisplay(display);
        if (sByDisplay.isEmpty()) detachAnchorListener();
    }

    private void setActive() {
        boolean changed = sActive != this;
        sActive = this;
        registerRouter();
        if (changed) {
            // Permukaan SurfaceView menumpuk ikut urutan lahir —
            // lahirkan ulang permukaan jendela aktif biar paling atas.
            host.view().setVisibility(android.view.View.INVISIBLE);
            host.view().post(() -> host.view().setVisibility(android.view.View.VISIBLE));
        }
    }

    /** Bar terminal melayani jendela ini selama dia aktif.
     *  Jalur = LorieView.sendKeyEvent(scanCode, keyCode, down) —
     *  kanal hardware-key yang DIPAKAI EK Lorie sendiri. Versi
     *  pertama router pakai dispatchKeyEvent generik: event-nya
     *  tidak sampai ke server X (temuan Hawx: softkey ga ngaruh). */
    private void registerRouter() {
        final XWindowHost h = host;
        XKeyRouter.keySink = code -> {
            h.view().sendKeyEvent(0, code, true);
            h.view().sendKeyEvent(0, code, false);
        };
        XKeyRouter.textSink = text -> h.view().dispatchKeyEvent(new KeyEvent(
                SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
        XKeyRouter.modifierSink = (name, down) -> {
            int code = "Ctrl".equals(name) ? KeyEvent.KEYCODE_CTRL_LEFT
                    : "Alt".equals(name) ? KeyEvent.KEYCODE_ALT_LEFT
                    : KeyEvent.KEYCODE_SHIFT_LEFT;
            h.view().sendKeyEvent(0, code, down);
        };
    }

    // ---------------- XFloatingWindow.Listener ----------------

    @Override
    public void onFocusGained(XFloatingWindow w) {
        host.view().requestFocus();
        setActive();
    }

    @Override
    public void onContentTapped(XFloatingWindow w) {
        // Aturan input overlay (Hawx): mode floating — ketuk isi
        // memunculkan keyboard, sama kaya terminal di belakangnya;
        // mode maximize — ketuk CUMA fokus, keyboard hanya dari
        // tombol keyboard (biar ga tiap ketuk layar penuh keyboard
        // muncul). Satu window: requestFocus saja sudah memindah
        // target IME secara native.
        host.view().requestFocus();
        setActive();
        if (!w.isMaximized()) {
            host.view().setKeyboardVisible(true);
        }
    }

    @Override
    public void onMinimizeClicked(XFloatingWindow w) {
        hide();
    }

    @Override
    public void onCloseClicked(XFloatingWindow w) {
        close();
        id.hawx.astro.x11.XTerminalManager.get(activity).releaseDisplay(display);
    }

    @Override
    public void onUserRectChanged(XFloatingWindow w) {
        // Rect manual user = keinginan baru: simpanan rect anchor
        // dihapus supaya enforceAnchor berikutnya tidak menimpa
        // posisi manual dengan fitted(saved). Sekali per episode
        // (log hanya bila simpanan memang ada).
        if (anchorSavedH > 0) {
            anchorSavedH = -1;
            anchorSavedTop = -1;
        }
    }

    // ---------------- XWindowHost.Listener ----------------

    @Override
    public void onCloseRequested(XWindowHost h) {
        close();
    }

    @Override
    public void onConnectionChanged(XWindowHost h, boolean connected) {
        win.setConnectedDot(connected);
    }

    @Override
    public void onStripVisibilityRequested(XWindowHost h, boolean visible) {
        // Layer terminal TANPA strip: tombol khususnya bar bawaan
        // terminal via XKeyRouter.
    }
}
