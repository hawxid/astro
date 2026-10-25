package id.hawx.astro.x11.window;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import id.hawx.astro.AstroApp;

/**
 * Bentuk MELAYANG jendela webview (keputusan Hawx: webview selama
 * ini cuma layar penuh GhostVizActivity). Satu overlay sistem per
 * berkas: chrome (judul + seret + grip resize + —/✕) + WebView
 * dengan ToolsBridge yang sama (AstroBridge). Dipilih lewat
 * checkbox "floating" entri launcher; tanpa itu tetap GhostViz.
 * — = lepas dari WindowManager saja (WebView hidup, bisa dibuka
 * lagi); ✕ = hancurkan WebView + tutup.
 */
public class WebOverlayWindows {

    private static final Map<String, WebOverlayWindows> sByPath = new HashMap<>();

    private final Activity activity;
    private final WindowManager wm;
    private final String path;
    private final FrameLayout container;
    private final FrameLayout win;
    private final WebView webView;
    private TextView dotView;
    private final WindowManager.LayoutParams wlp;
    private boolean attached;
    private boolean maximized;
    private int prevX, prevY, prevW, prevH;
    // Anchor maximize ke frame kelihatan (atas keyboard) — di-assign
    // di akhir ctor (bukan di inisialisasi field), supaya tidak ada
    // lambda yang membaca field selagi ctor berjalan.
    private ViewTreeObserver.OnGlobalLayoutListener frameListener;
    private boolean frameListening;

    // ---------------- statis ----------------

    /** @return false bila tak bisa tampil (izin/foreground) — pemanggil jatuh ke GhostViz. */
    public static boolean show(Context ctx, String title, String path) {
        if (!Settings.canDrawOverlays(ctx)) {
            Activity act = AstroApp.foregroundActivity();
            if (act != null) {
                Toast.makeText(act, "Aktifkan izin \"Tampil di atas aplikasi lain\" "
                        + "buat Astro, lalu ulangi", Toast.LENGTH_LONG).show();
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()));
                act.startActivity(i);
            }
            return false;
        }
        Activity act = AstroApp.foregroundActivity();
        if (act == null) return false;
        WebOverlayWindows ex = sByPath.get(path);
        if (ex != null) {
            ex.raise();
            return true;
        }
        new WebOverlayWindows(act, title, path).attach();
        return true;
    }

    public static boolean closeByPath(String path) {
        WebOverlayWindows o = sByPath.get(path);
        if (o == null) return false;
        o.close();
        return true;
    }

    public static void hideForPath(String path) {
        WebOverlayWindows o = sByPath.get(path);
        if (o != null) o.hide();
    }

    // ---------------- instance ----------------

    private WebOverlayWindows(Activity act, String title, String path) {
        this.activity = act;
        this.path = path;
        wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);

        // wlp HARUS di-assign sebelum bar/grip dibuat: lambda
        // tombol maximize membaca wlp, dan javac menolak lambda
        // yang membaca field final sebelum assignment-nya
        // (ini yang meruntuhkan 3 build terakhir).
        int w = (int) (m.widthPixels * 0.88f);
        int h = (int) (m.heightPixels * 0.55f);
        wlp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        int n = sByPath.size();
        wlp.x = dp(14) + n * dp(26);
        wlp.y = dp(70) + n * dp(30);

        container = new WebContainer(act);

        win = new FrameLayout(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21A2138);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFF3D4A7A);
        win.setBackground(bg);
        win.setClipToOutline(true);
        win.setElevation(dp(10));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        win.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(act);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (dotView != null) dotView.setTextColor(0xFF3DDC84);
            }
        });
        webView.addJavascriptInterface(
                new id.hawx.astro.goose.ToolsBridge(
                        new id.hawx.astro.utils.Core(act), webView),
                "AstroBridge");

        // ---- chrome bar: SALIN PERSIS dari XFloatingWindow ----
        // (temuan Hawx: tombol Button bawaan tema merenggang
        // berjauhan — X pakai TextView tanpa minWidth bawaan,
        // jadi tombol merapat. Urutan + ukuran + warna disamakan.)
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), 0, dp(4), 0);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xFF232C4E);
        bar.setBackground(barBg);
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        dotView = new TextView(act);
        dotView.setText("●");
        dotView.setTextSize(9);
        dotView.setTextColor(0xFF8B93B8);
        bar.addView(dotView);

        TextView titleView = new TextView(act);
        titleView.setText(title);
        titleView.setTextColor(0xFFE8EBFA);
        titleView.setTextSize(12);
        titleView.setSingleLine(true);
        titleView.setPadding(dp(6), 0, 0, 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        bar.addView(chromeBtn("⌨", v -> {
            webView.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(webView, 0);
        }));
        bar.addView(chromeBtn("—", v -> hide()));
        // JANGAN pasang listener di dalam inisialisasi maxBtn:
        // lambda-nya membaca maxBtn sendiri, padahal assignment
        // baru selesai setelah chromeBtn() kembali — javac
        // menolak ("variable maxBtn might not have been
        // initialized", kesalahan yang sama dengan wlp kemarin,
        // dan 2 build berikutnya ikut gagal karena file ini
        // tak berubah). Jadi: buat dulu, listener belakangan.
        TextView maxBtn = chromeBtn("▢", null);
        maxBtn.setOnClickListener(v -> {
            if (maximized) {
                wlp.x = prevX;
                wlp.y = prevY;
                wlp.width = prevW;
                wlp.height = prevH;
                maximized = false;
                maxBtn.setText("▢");
            } else {
                prevX = wlp.x;
                prevY = wlp.y;
                prevW = wlp.width;
                prevH = wlp.height;
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                // Maximize = TEPAT frame kelihatan, bukan mentah
                // realMetrics: rect mentah mengabaikan status bar —
                // window yang diminta setinggi layar penuh nyatanya
                // dipotong WM di frame konten, dan selisihnya yang
                // bikin bawah halaman melar dikit (footer kepotong
                // setengah baris di gulir mentok, tanpa keyboard).
                // Frame kelihatan = ground truth yang sama dengan
                // anchor keyboard; kalau frame tak valid, fallback
                // ke rect lama (tak pernah nol/negatif).
                Rect fr = new Rect();
                container.getWindowVisibleDisplayFrame(fr);
                // Guard: frame harus seukuran layar (bukan seukuran
                // window floating) — kalau API ternyata mengembalikan
                // frame window sendiri, fallback ke rect lama (tak ada
                // regresi; anchor frame sesudahnya tetap mengoreksi).
                if (fr.bottom > fr.top && fr.right > fr.left
                        && fr.right - fr.left >= dm.widthPixels * 0.9f
                        && fr.bottom - fr.top >= dm.heightPixels * 0.9f) {
                    wlp.x = fr.left;
                    wlp.y = fr.top;
                    wlp.width = fr.right - fr.left;
                    wlp.height = fr.bottom - fr.top;
                } else {
                    wlp.x = 0;
                    wlp.y = 0;
                    wlp.width = dm.widthPixels;
                    wlp.height = dm.heightPixels;
                }
                maximized = true;
                maxBtn.setText("❐");
            }
            wm.updateViewLayout(container, wlp);
            // Keyboard mungkin SUDAH buka saat maximize ditekan —
            // tak ada dispatch insets baru, jadi terapkan inset
            // terakhir yang tercatat (bila ada; imeH 0 diabaikan
            // oleh applyImeResize) + evaluasi frame di bawah.
            if (maximized && android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsets in = container.getRootWindowInsets();
                if (in != null) applyImeResize(in);
            }
            // Evaluasi anchor frame juga di sini (kasus maximize
            // dengan keyboard sudah buka: tak ada layout baru yang
            // memicu listener, tapi frame kelihatan saat ini valid).
            if (maximized) onVisibleFrame();
        });
        bar.addView(maxBtn);
        bar.addView(chromeBtn("✕", v -> close()));

        col.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        View grip = new View(act);
        grip.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(dp(30), dp(30));
        gripLp.gravity = Gravity.BOTTOM | Gravity.END;
        win.addView(grip, gripLp);
        grip.setOnTouchListener(new ResizeListener());

        // PENTING: win HARUS match-parent kontainer, bukan ukuran
        // fixed — kalau fixed, resize cuma mengubah kontainer dan
        // isi (WebView) tetap di ukuran awal lalu terpotong
        // (temuan Hawx: "pas diperkecil sisanya kepotong").
        win.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(win);

        // Maximize + keyboard: viewport harus MENGECIL di atas
        // keyboard (bukan cuma window digeser) — kalau tidak,
        // halaman satu layar penuh tak punya sisa scroll dan
        // field bawah mentok di balik keyboard. Mekanisme:
        // listener insets (pola yang sama dengan XWindowActivity,
        // API 30+; dipasang di akhir ctor, setelah semua field
        // final ter-assign) — SEKARANG HANYA FALLBACK yang boleh
        // menyempitkan (imeH > 0); otoritas penuh ada di anchor
        // frame di bawah (log 36: inset vis=true imeH=0 + loop).
        // Overlay TYPE_APPLICATION_OVERLAY tidak mengandalkan
        // softInputMode ADJUST_RESIZE sistem (efeknya pada window
        // overlay tak bisa diverifikasi dari kode).
        // Non-maximize tidak disentuh.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            container.setOnApplyWindowInsetsListener((v, insets) -> {
                applyImeResize(insets);
                return insets;
            });
        }

        // Anchor frame kelihatan = OTORITAS TUNGGAL tinggi maximize
        // (log 36: frame ngikutin keyboard 2340 -> 1414 dengan benar,
        // sementara inset baca vis=true imeH=0 terus dan re-dispatch
        // tiap ganti tinggi -> osilasi). Mekanisme ini tidak tergantung
        // dispatch WindowInsets: tiap layout global, frame kelihatan
        // pembungkus diukur sendiri — bawah frame = atas keyboard
        // saat keyboard buka. Didaftar di attach()/raise(), dilepas
        // di hide() (sebelum removeView, selagi observer masih hidup).
        frameListener = () -> onVisibleFrame();

    }

    /** Salinan persis chromeButton XFloatingWindow: TextView
     *  (bukan Button — Button tema bawa minWidth bawaan yang
     *  bikin tombol merenggang berjauhan). */
    private TextView chromeBtn(String label, View.OnClickListener onClick) {
        TextView b = new TextView(activity);
        b.setText(label);
        b.setTextColor(0xFFD6DBF5);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(7), dp(2), dp(7), dp(2));
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * Fallback inset IME (API 30+) — BUKAN otoritas tinggi maximize.
     * Log 36 membuktikan dua hal: (1) inset sampai (vis=true) tapi
     * imeH selalu 0 di window overlay ini; (2) ganti tinggi memicu
     * layout -> re-dispatch insets -> loop tarik-menarik dengan
     * anchor frame tiap ~10-30ms. Karena itu evaluasi inset HANYA
     * boleh MENYEMPITKAN (imeH > 0, tinggi nyata); imeH == 0
     * DIABAIKAN total (tidak pernah menulis) — mengembalikan ke
     * full adalah tugas anchor frame (onVisibleFrame) saja.
     * Dipanggil dari listener insets + cabang maximize. Tak pernah
     * menyentuh prevH (restore tetap ke rect floating semula).
     */
    private void applyImeResize(android.view.WindowInsets insets) {
        if (insets == null) return;
        int imeH = 0;
        boolean vis = insets.isVisible(android.view.WindowInsets.Type.ime());
        if (vis) imeH = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        // Tinggi = (bawah layar - imeH) - wlp.y: bawah window harus
        // mendarat tepat di atas keyboard, bukan setinggi itu dari
        // atas window (maximize kini ber-y = atas frame, bukan 0).
        int wantH = dm.heightPixels - imeH - wlp.y;
        // SKIP = imeH 0 diabaikan total (tidak pernah menulis).
        boolean skip = imeH <= 0;
        if (skip) return;
        setMaxHeight(wantH);
    }

    /** Satu-satunya penulis tinggi maximize: guard idempotensi +
     *  prevH tidak pernah disentuh (restore tetap ke rect floating). */
    private void setMaxHeight(int wantH) {
        if (!maximized || !attached || wantH <= 0 || wlp.height == wantH) return;
        wlp.height = wantH;
        wm.updateViewLayout(container, wlp);
    }

    /** Anchor maximize ke frame kelihatan = OTORITAS TUNGGAL tinggi
     *  maximize: bawah frame = atas keyboard saat keyboard buka,
     *  = bawah layar saat tutup (termasuk restore ke full).
     *  Hanya jalan selagi maximize (layout lain tidak di-spam log). */
    private void onVisibleFrame() {
        if (!maximized || !attached) return;
        Rect r = new Rect();
        container.getWindowVisibleDisplayFrame(r);
        int wantH = r.bottom - wlp.y;
        setMaxHeight(wantH);
    }

    private void startFrameListener() {
        if (frameListening || frameListener == null) return;
        container.getViewTreeObserver().addOnGlobalLayoutListener(frameListener);
        frameListening = true;
    }

    private void stopFrameListener() {
        if (!frameListening) return;
        container.getViewTreeObserver().removeOnGlobalLayoutListener(frameListener);
        frameListening = false;
    }

    private void attach() {
        sByPath.put(path, this);
        wm.addView(container, wlp);
        attached = true;
        startFrameListener();
        File f = new File(path);
        if (f.exists()) {
            webView.loadUrl("file://" + f.getAbsolutePath());
        } else if (path.startsWith("http://") || path.startsWith("https://")) {
            webView.loadUrl(path);
        } else {
            webView.loadData("<h3>Berkas tidak ditemukan</h3><p>" + path + "</p>",
                    "text/html", "UTF-8");
        }
    }

    private void raise() {
        if (!attached) {
            wm.addView(container, wlp);
            attached = true;
            startFrameListener();
        }
    }

    private void hide() {
        if (attached) {
            // Lepas listener SEBELUM removeView: observer view yang
            // sudah dilepas mati, dan pendaftaran lamanya tak bisa
            // dihapus dari observer baru.
            stopFrameListener();
            wm.removeView(container);
            attached = false;
        }
    }

    private void close() {
        hide();
        try {
            webView.destroy();
        } catch (Throwable ignored) {
        }
        sByPath.remove(path);
    }

    /** Seret lewat bar judul — gerakkan parameter WindowManager langsung. */
    private class WebContainer extends FrameLayout {
        private boolean dragArmed;
        private float dragDownX, dragDownY;
        private int dragStartX, dragStartY;

        WebContainer(Context ctx) {
            super(ctx);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            int action = e.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                if (e.getY() >= win.getTop() && e.getY() <= win.getTop() + dp(32)
                        && e.getX() >= win.getLeft() && e.getX() <= win.getRight()) {
                    dragArmed = true;
                    dragDownX = e.getRawX();
                    dragDownY = e.getRawY();
                    dragStartX = wlp.x;
                    dragStartY = wlp.y;
                }
                return false;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE && dragArmed) {
                if (Math.abs(e.getRawX() - dragDownX) > dp(10)
                        || Math.abs(e.getRawY() - dragDownY) > dp(10)) {
                    return true;
                }
            }
            if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                dragArmed = false;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            int action = e.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_MOVE && dragArmed) {
                DisplayMetrics m = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(m);
                int nx = dragStartX + Math.round(e.getRawX() - dragDownX);
                int ny = dragStartY + Math.round(e.getRawY() - dragDownY);
                nx = Math.max(-(wlp.width - dp(80)),
                        Math.min(nx, m.widthPixels - dp(80)));
                ny = Math.max(0, Math.min(ny, m.heightPixels - dp(40)));
                if (nx != wlp.x || ny != wlp.y) {
                    wlp.x = nx;
                    wlp.y = ny;
                    wm.updateViewLayout(container, wlp);
                }
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                dragArmed = false;
                return true;
            }
            return false;
        }
    }

    /** Resize lewat grip pojok — ubah parameter WindowManager langsung. */
    private class ResizeListener implements View.OnTouchListener {
        private float downX, downY;
        private int startW, startH;

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startW = wlp.width;
                    startH = wlp.height;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    wlp.width = Math.max(dp(280),
                            startW + Math.round(e.getRawX() - downX));
                    wlp.height = Math.max(dp(200),
                            startH + Math.round(e.getRawY() - downY));
                    wm.updateViewLayout(container, wlp);
                    return true;
            }
            return false;
        }
    }
}
