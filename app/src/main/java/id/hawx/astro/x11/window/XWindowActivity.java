package id.hawx.astro.x11.window;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.termux.x11.LorieView;

import id.hawx.astro.x11.X11DebugLog;

/**
 * EKSPERIMEN PoC 1b: host yang sama persis (XWindowHost) tapi view-nya
 * tinggal di window Activity sendiri — misahin variabel "nempel di
 * hierarchy MainActivity" dari wiring host.
 */
public class XWindowActivity extends Activity {
    public static final String EXTRA_DISPLAY = "astro_xwin_display";

    private XWindowHost host;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int display = getIntent().getIntExtra(EXTRA_DISPLAY, 1);
        X11DebugLog.log("FLOAT", "XWindowActivity onCreate d=" + display);
        FrameLayout root = new FrameLayout(this);
        setContentView(root);
        host = new XWindowHost(this, display);
        LorieView view = host.view();
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Wiring sentuh meniru MainActivity upstream: listener di PARENT,
        // diteruskan ke TouchInputHandler host (tanpa ini kursor beku).
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN
                    && android.os.Build.VERSION.SDK_INT >= 29)
                v.requestUnbufferedDispatch(e);
            return host.handleTouch(v, e);
        });
        root.setOnHoverListener((v, e) -> host.handleTouch(v, e));
        root.setOnGenericMotionListener((v, e) -> host.handleTouch(v, e));
        if (android.os.Build.VERSION.SDK_INT >= 26)
            view.setOnCapturedPointerListener((v, e) -> host.handleTouch(view, e));
        host.attach();
    }

    @Override protected void onDestroy() {
        if (host != null) host.detach();
        super.onDestroy();
    }

    public static void launch(Activity from, int display) {
        Intent i = new Intent(from, XWindowActivity.class);
        i.putExtra(EXTRA_DISPLAY, display);
        from.startActivity(i);
    }
}
