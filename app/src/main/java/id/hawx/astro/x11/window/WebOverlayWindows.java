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
import java.util.LinkedHashMap;
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

    // LinkedHashMap (bukan HashMap): urutan sisip = urutan tampil di
    // dock, stabil antar rebuild (HashMap bisa mengacak urutan tiap
    // rehash — taskbar yang loncat-loncat membingungkan).
    private static final Map<String, WebOverlayWindows> sByPath = new LinkedHashMap<>();

    // Overlay terdepan terakhir (tap-to-front, 2026-10-31): raise
    // hanya re-add bila bukan ini — tap pada window yang sudah di
    // depan tidak boleh mengusik gesture/keyboard dengan
    // remove+add sia-sia. Di-set di attach()/raise(), dibersihkan
    // di hide() (close() lewat hide()).
    private static WebOverlayWindows sFront;

    // Listener perubahan overlay (ronde dock webview, 2026-10-25):
    // Dashboard mendaftar di onResume, melepas di onPause; tiap
    // transisi tampil/sembunyi/tutup memicu rebuild dock — tanpa
    // polling. Dipanggil sinkron dari thread transisi (praktiknya
    // thread UI: tap chrome/sel/menu); Dashboard sendiri sudah
    // menjaga isAdded/getView di refreshDisplays().
    public interface OverlayListener {
        void onOverlaysChanged();
    }

    private static final java.util.List<OverlayListener> sListeners =
            new java.util.ArrayList<>();

    public static void addOverlayListener(OverlayListener l) {
        if (l != null && !sListeners.contains(l)) sListeners.add(l);
    }

    public static void removeOverlayListener(OverlayListener l) {
        sListeners.remove(l);
    }

    private static void notifyOverlayListeners() {
        java.util.List<OverlayListener> copy =
                new java.util.ArrayList<>(sListeners);
        for (OverlayListener l : copy) {
            try {
                l.onOverlaysChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Suntikan auto keyboard (ronde auto keyboard, 2026-10-31):
     * listener focusin level dokumen. focusin bubble, jadi satu
     * listener di document menutupi input yang dibuat dinamis
     * sesudah load (SPA seperti UI bettercap) tanpa suntik ulang
     * per rute; navigasi dokumen penuh memicu onPageFinished lagi
     * (guard __astroAutoKb cegah duplikat dalam satu dokumen).
     * Non-intrusif: tidak mengubah DOM, tidak menyentuh skrip
     * halaman; semua dibungkus try/catch. Tipe input non-teks
     * (tombol, checkbox, radio, hidden, file, dst) tidak memanggil
     * keyboard — hanya field teks, textarea, dan contenteditable.
     */
    private static final String AUTO_KEYBOARD_JS =
            "(function(){try{"
            + "if(window.__astroAutoKb)return;"
            + "window.__astroAutoKb=true;"
            + "document.addEventListener('focusin',function(e){"
            + "var t=e.target;if(!t)return;"
            + "var tag=t.tagName;"
            + "if(tag==='TEXTAREA'||t.isContentEditable){"
            + "if(window.AstroBridge)window.AstroBridge.onInputFocused();"
            + "return;}"
            + "if(tag!=='INPUT')return;"
            + "var ty=(t.type||'text').toLowerCase();"
            + "if(ty==='hidden'||ty==='button'||ty==='submit'||ty==='reset'"
            + "||ty==='checkbox'||ty==='radio'||ty==='file'||ty==='image'"
            + "||ty==='range'||ty==='color')return;"
            + "if(window.AstroBridge)window.AstroBridge.onInputFocused();"
            + "},true);"
            + "}catch(err){}})();";

    /** Potret satu overlay untuk dock (sumber kebenaran = registry
     *  sByPath; visible = attached nyata, bukan pref/mode baru). */
    public static class OverlayInfo {
        public final String path;
        public final String title;
        public final String dockLabel;
        public final boolean visible;

        OverlayInfo(String path, String title, String dockLabel, boolean visible) {
            this.path = path;
            this.title = title;
            this.dockLabel = dockLabel;
            this.visible = visible;
        }
    }

    /** Potret semua overlay yang ada (terbuka MAUPUN disembunyikan —
     *  hide() tidak menghapus dari registry, hanya close()).
     *  Urutan = urutan dibuat (LinkedHashMap). */
    public static java.util.List<OverlayInfo> listOverlays() {
        java.util.List<OverlayInfo> out = new java.util.ArrayList<>();
        for (WebOverlayWindows o : sByPath.values()) {
            String dl = o.dockLabel;
            if (dl == null || dl.isEmpty()) dl = dockLabelFor("", o.title);
            out.add(new OverlayInfo(o.path, o.title, dl, o.attached));
        }
        return out;
    }

    /** Label dock dari ikon entry launcher: emoji/teks pendek dipakai
     *  langsung; ikon file: atau kosong -> huruf pertama judul. */
    public static String dockLabelFor(String icon, String title) {
        if (icon != null && !icon.trim().isEmpty() && !icon.startsWith("file:")) {
            return icon.trim();
        }
        if (title != null && !title.isEmpty()) {
            return title.substring(0, 1).toUpperCase();
        }
        return "?";
    }

    /** Set label dock overlay yang sudah ada (dipanggil call site
     *  show() sesudah tampil — termasuk jalur raise, supaya ikon
     *  yang diubah di editor ikut segar). No-op bila tak ada overlay
     *  untuk path itu. Memberi tahu listener (label di-set SESUDAH
     *  notify attach/raise, jadi rebuild sesudahnya yang memakainya). */
    public static void setDockLabelForPath(String path, String label) {
        WebOverlayWindows o = sByPath.get(path);
        if (o == null) return;
        o.dockLabel = label;
        notifyOverlayListeners();
    }

    private final Activity activity;
    private final WindowManager wm;
    private final String path;
    /** Judul window (disimpan — sebelumnya cuma param ctor untuk
     *  titleView; dock butuh judul untuk label fallback + menu). */
    private final String title;
    /** Label dock (ikon entry); null/kosong -> fallback huruf judul. */
    private String dockLabel;
    /**
     * Hook tutup (mode webui): dijalankan di close() saja — ✕ dan
     * closeByPath. hide() (—) TIDAK menjalankannya (server webui
     * tetap hidup saat window cuma disembunyikan). null = window
     * webview biasa, tanpa efek samping. Di-set SETELAH konstruksi
     * (show 4-arg), bukan dibaca lambda saat ctor berjalan.
     */
    private Runnable onClose;
    private final FrameLayout container;
    private final FrameLayout win;
    private final WebView webView;
    private TextView dotView;
    // Tombol kembali web (ronde back webview, 2026-10-25): ← di
    // chrome, sebelum ⌨. State (redup bila tak ada history)
    // di-update di onPageFinished — field, bukan lokal, supaya
    // callback WebViewClient yang dibuat sebelum chrome bisa
    // membacanya (pola yang sama dengan dotView).
    private TextView backBtn;
    private final WindowManager.LayoutParams wlp;
    private boolean attached;
    private boolean maximized;
    private int prevX, prevY, prevW, prevH;
    // Anchor maximize ke frame kelihatan (atas keyboard) — di-assign
    // di akhir ctor (bukan di inisialisasi field), supaya tidak ada
    // lambda yang membaca field selagi ctor berjalan.
    private ViewTreeObserver.OnGlobalLayoutListener frameListener;
    private boolean frameListening;
    // Toggle fokus input (ronde beralih ketik, 2026-10-25): default
    // NOT_FOCUSABLE supaya app di bawah pegang IME; mode focusable
    // hanya selama ketik di webview (masuk via ⌨, pulang via
    // tap-luar / keyboard-hide / hide). Boolean primitif, default
    // false — tidak ada self-reference.
    private boolean focusInputMode;
    private boolean kbSeenOpen;

    // ---------------- statis ----------------

    /** @return false bila tak bisa tampil (izin/foreground) — pemanggil jatuh ke GhostViz. */
    public static boolean show(Context ctx, String title, String path) {
        return show(ctx, title, path, null);
    }

    /**
     * Varian dengan hook tutup (mode webui: onClose mematikan server
     * saat window ditutup — bukan saat disembunyikan).
     * @return false bila tak bisa tampil (izin/foreground) — pemanggil jatuh ke GhostViz.
     */
    public static boolean show(Context ctx, String title, String path, Runnable onClose) {
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
        WebOverlayWindows w = new WebOverlayWindows(act, title, path);
        w.onClose = onClose;
        w.attach();
        return true;
    }

    public static boolean closeByPath(String path) {
        WebOverlayWindows o = sByPath.get(path);
        if (o == null) return false;
        o.close();
        return true;
    }

    /** Apakah overlay untuk path/URL ini sudah ada (terbuka atau disembunyikan). */
    public static boolean isShowing(String path) {
        return sByPath.containsKey(path);
    }

    /** Muat ulang halaman overlay yang ada (dipakai webui: server baru
     *  di-start, window lama masih menampilkan halaman basi). No-op
     *  bila tidak ada overlay untuk path itu. */
    public static void reloadByPath(String path) {
        WebOverlayWindows o = sByPath.get(path);
        if (o != null) o.webView.reload();
    }

    public static void hideForPath(String path) {
        WebOverlayWindows o = sByPath.get(path);
        if (o != null) o.hide();
    }

    // ---------------- instance ----------------

    private WebOverlayWindows(Activity act, String title, String path) {
        this.activity = act;
        this.title = title;
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
                // Default NOT_FOCUSABLE (ronde beralih ketik): window
                // overlay yang focusable merebut/menahan fokus window
                // sejak ditambahkan — cukup dengan keberadaannya,
                // app di bawah (WA) jadi tidak bisa memunculkan
                // keyboard (terbukti perangkat 2026-10-25, bahkan
                // tanpa overlay pernah disentuh). NOT_FOCUSABLE =
                // app bawah pegang fokus + IME normal; sentuhan
                // webview tetap jalan (tombol tidak butuh fokus).
                // WATCH_OUTSIDE_TOUCH = pemicu pulang: tap di luar
                // overlay tertangkap sebagai ACTION_OUTSIDE tanpa
                // di-consume (sentuhan tetap sampai ke app bawah).
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
                // State tombol ←: redup (bukan hilang) bila tak ada
                // history; goBack() selalu memicu page finish, jadi
                // state ini selalu segar tanpa listener baru.
                if (backBtn != null) {
                    boolean canBack = v.canGoBack();
                    backBtn.setAlpha(canBack ? 1.0f : 0.35f);
                    backBtn.setEnabled(canBack);
                }
                // Auto keyboard: suntik listener focusin (idempotent
                // per dokumen; SPA tidak reload dokumen, jadi
                // listener bertahan lintas rute tanpa suntik ulang).
                try {
                    v.evaluateJavascript(AUTO_KEYBOARD_JS, null);
                } catch (Throwable ignored) {
                }
            }
            // Guard navigasi (hardening 2026-10-25): hanya origin lokal /
            // loopback boleh dimuat di webview ber-bridge; link ke luar
            // daftar dibuka di browser eksternal. onPageFinished di atas
            // tidak diubah; helper ini tidak menyentuh field chrome
            // (dibuat sebelum chrome) maupun logika fokus/maksimal.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return shouldOverride(view, request.getUrl().toString());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return shouldOverride(view, url);
            }
            private boolean shouldOverride(WebView view, String url) {
                if (id.hawx.astro.goose.ToolsBridge.isAllowedNavigationUrl(url)) return false;
                id.hawx.astro.goose.ToolsBridge.openExternal(view.getContext(), url);
                return true;
            }
        });
        // Bridge dibuat lokal dulu (bukan inline) supaya listener
        // auto keyboard bisa dipasang sebelum attach — listener
        // hanya dipanggil sesudah konstruksi selesai (saat halaman
        // memanggil onInputFocused), jadi tidak ada pembacaan field
        // selagi ctor berjalan.
        id.hawx.astro.goose.ToolsBridge bridge =
                new id.hawx.astro.goose.ToolsBridge(
                        new id.hawx.astro.utils.Core(act), webView);
        bridge.setInputFocusListener(() -> onWebInputFocused());
        webView.addJavascriptInterface(bridge, "AstroBridge");

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

        // Tombol kembali history webview: SEBELUM ⌨ (urutan baru
        // ← ⌨ — ▢ ✕). WebView menyimpan history navigasi (termasuk
        // SPA seperti WebSec0), tapi overlay tak punya back dispatch
        // sistem — tanpa tombol ini pengguna kejebak sesudah maju
        // (scan -> laporan) karena satu-satunya jalan keluar adalah
        // ✕ (yang mematikan server webui). goBack() adalah panggilan
        // programatik — tak butuh fokus window, jadi jalan di kedua
        // mode fokus tanpa menyentuh toggle fokus. Guard canGoBack
        // di handler (tombol redup + disabled saat tak ada history
        // tetap dijaga onPageFinished di atas).
        backBtn = chromeBtn("←", v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        backBtn.setAlpha(0.35f);
        backBtn.setEnabled(false);
        bar.addView(backBtn);
        // Fallback manual mode ketik — jalur auto (focusin halaman)
        // memanggil enterInputMode() yang sama di bawah.
        bar.addView(chromeBtn("⌨", v -> enterInputMode()));
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

    /**
     * Dipanggil bridge saat input teks halaman mendapat fokus (auto
     * keyboard): urutan persis tombol ⌨, tapi dipicu SETELAH input
     * benar-benar fokus — jadi tidak ada balapan seperti tap ⌨ yang
     * kadang gagal (window belum focusable / input belum fokus saat
     * showSoftInput dipanggil). JavascriptInterface jalan di thread
     * WebView, jadi semua sentuhan view/window dipost ke UI thread.
     * No-op bila window sudah tidak attached (hide/close).
     * Idempotent dengan ⌨: setFocusableInputMode(true) sudah no-op
     * bila sudah focusable, dan requestFocus/showSoftInput yang
     * berulang tidak merusak state.
     */
    private void onWebInputFocused() {
        activity.runOnUiThread(this::enterInputMode);
    }

    /**
     * Urutan masuk mode ketik (dipakai tombol ⌨ DAN jalur auto):
     * window HARUS focusable dulu, baru requestFocus/showSoftInput
     * bisa jalan (urutan ini penting — keduanya gagal kalau window
     * masih NOT_FOCUSABLE). kbSeenOpen di-reset supaya deteksi
     * keyboard-hide (frame listener) mulai dari siklus segar.
     */
    private void enterInputMode() {
        if (!attached) return;
        setFocusableInputMode(true);
        kbSeenOpen = false;
        webView.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(webView, 0);
    }

    /** Toggle fokus input webview. true = window bisa jadi target
     *  IME (mode ketik webview); false = NOT_FOCUSABLE (app bawah
     *  pegang fokus + IME). HANYA mengubah bit NOT_FOCUSABLE —
     *  x/y/w/h, maximize, anchor tidak disentuh. No-op bila flag
     *  sudah sesuai (tidak memicu layout sia-sia). updateViewLayout
     *  hanya bila attached (sesudah removeView itu ilegal).
     *  Sengaja TANPA hideSoftInput eksplisit: saat fokus pindah,
     *  sistem yang mengikat ulang/menyembunyikan IME — hide
     *  eksplisit berisiko balapan dengan keyboard app bawah yang
     *  mau tampil (kasus pulang ke WA). */
    private void setFocusableInputMode(boolean on) {
        boolean wantNotFocusable = !on;
        boolean hasNotFocusable =
                (wlp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0;
        focusInputMode = on;
        if (!on) kbSeenOpen = false;
        if (hasNotFocusable == wantNotFocusable) return;
        if (wantNotFocusable) {
            wlp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            wlp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (attached) wm.updateViewLayout(container, wlp);
    }

    /** Anchor maximize ke frame kelihatan = OTORITAS TUNGGAL tinggi
     *  maximize: bawah frame = atas keyboard saat keyboard buka,
     *  = bawah layar saat tutup (termasuk restore ke full).
     *  Hanya jalan selagi maximize (layout lain tidak di-spam log).
     *  Ditambah deteksi keyboard-hide khusus mode ketik webview
     *  (pemicu pulang cadangan, di luar tap-luar): frame kembali
     *  penuh SETELAH sempat menyusut = keyboard sudah nutup ->
     *  pulang ke NOT_FOCUSABLE. Sengaja TIDAK memakai listener
     *  insets sebagai detektor: log 36 membuktikan imeH selalu 0
     *  di window overlay ini, jadi insets tak bisa diandalkan
     *  untuk hide. Listener frame ini sudah jalan selama attached
     *  (daftar di attach/raise), jadi tidak ada listener baru.
     *  Arah gagal aman: frame yang tak pernah kembali penuh (mis.
     *  nav bar buram) berarti tidak ada restore lewat jalur ini —
     *  tap-luar tetap menutupi pulangnya, ketik webview tak rusak. */
    private void onVisibleFrame() {
        if (focusInputMode && attached) {
            Rect r = new Rect();
            container.getWindowVisibleDisplayFrame(r);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            if (r.bottom < dm.heightPixels) {
                kbSeenOpen = true;
            } else if (kbSeenOpen) {
                setFocusableInputMode(false);
            }
        }
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
        sFront = this;
        startFrameListener();
        notifyOverlayListeners();
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
            sFront = this;
            notifyOverlayListeners();
        } else {
            raiseToFront();
        }
    }

    /**
     * Naikkan overlay ke depan (tap-to-front + raise dock):
     * reorder WindowManager remove+add — WebView + halaman
     * dipertahankan (tanpa reload). No-op bila belum attached,
     * sudah di depan, atau sudah keluar registry. Bila window
     * sedang mode ketik, mode itu dimasuki ulang sesudah re-add
     * (token window baru; tanpa ini keyboard jatuh).
     */
    private void raiseToFront() {
        if (!attached || sFront == this || sByPath.get(path) != this) return;
        boolean wasInput = focusInputMode;
        stopFrameListener();
        try {
            wm.removeView(container);
        } catch (Throwable ignored) {
        }
        attached = false;
        wm.addView(container, wlp);
        attached = true;
        startFrameListener();
        sFront = this;
        notifyOverlayListeners();
        if (wasInput) enterInputMode();
    }

    private void hide() {
        if (attached) {
            // Pulang dari mode ketik bila sedang aktif: flag harus
            // sudah NOT_FOCUSABLE sebelum removeView (removeView
            // membuang params; updateViewLayout sesudahnya ilegal).
            // No-op bila sudah NOT_FOCUSABLE.
            setFocusableInputMode(false);
            // Lepas listener SEBELUM removeView: observer view yang
            // sudah dilepas mati, dan pendaftaran lamanya tak bisa
            // dihapus dari observer baru.
            stopFrameListener();
            wm.removeView(container);
            attached = false;
            if (sFront == this) sFront = null;
            notifyOverlayListeners();
        }
    }

    private void close() {
        hide();
        // Hook tutup HANYA di sini (✕ / closeByPath) — hide() di atas
        // tidak menjalankannya, jadi server webui tetap hidup saat
        // window cuma disembunyikan (—). Gagal hook tidak boleh
        // menggagalkan hancurnya WebView.
        if (onClose != null) {
            try {
                onClose.run();
            } catch (Throwable ignored) {
            }
        }
        try {
            webView.destroy();
        } catch (Throwable ignored) {
        }
        sByPath.remove(path);
        notifyOverlayListeners();
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
        public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            // Pemicu pulang UTAMA: tap di luar overlay (area app
            // bawah) tertangkap sebagai ACTION_OUTSIDE berkat
            // FLAG_WATCH_OUTSIDE_TOUCH. Kembalikan NOT_FOCUSABLE
            // supaya app bawah merebut fokus window + IME.
            // TIDAK di-consume: super tetap jalan, sentuhan itu
            // sendiri tetap sampai ke app bawah. No-op bila sudah
            // NOT_FOCUSABLE (mode default).
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_OUTSIDE) {
                WebOverlayWindows.this.setFocusableInputMode(false);
            }
            boolean r = super.dispatchTouchEvent(e);
            // Tap-to-front: UP pada window yang tertimbun
            // menaikkannya ke depan. Sengaja sesudah dispatch
            // (bukan DOWN): klik/scroll halaman selesai dulu,
            // baru reorder — raise di DOWN berisiko memutus
            // gesture (token window baru). No-op bila window
            // sudah di depan, atau tertutup oleh klik itu
            // sendiri (✕/—).
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                WebOverlayWindows.this.raiseToFront();
            }
            return r;
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
