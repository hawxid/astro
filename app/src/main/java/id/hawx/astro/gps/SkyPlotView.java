package id.hawx.astro.gps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * Plot langit satelit (ala GPS Locker): lingkaran horizon + lingkaran
 * 45 derajat + garis silang, titik satelit dari azimuth/elevasi GSV.
 * Radius titik = (90 - elevasi) / 90 * radius plot; azimuth 0 = utara
 * (atas), searah jarum jam. Warna: used = hijau neon (live OSIRIS),
 * in-view bersinyal = emas, in-view tanpa SNR = abu-abu.
 */
public class SkyPlotView extends View {

    private static final int COLOR_GOLD = 0xFFD4AF37;
    private static final int COLOR_LIVE = 0xFF00FF88;
    private static final int COLOR_GREY = 0xFF8A8A95;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<GpsLockService.Sat> sats = Collections.emptyList();

    public SkyPlotView(Context context) {
        super(context);
        init();
    }

    public SkyPlotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f);
        ringPaint.setColor(COLOR_GREY);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void setSats(List<GpsLockService.Sat> sats) {
        this.sats = sats != null ? sats : Collections.emptyList();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 8f;
        if (radius <= 0) return;
        // Lingkaran horizon + 45 derajat + garis silang.
        canvas.drawCircle(cx, cy, radius, ringPaint);
        canvas.drawCircle(cx, cy, radius / 2f, ringPaint);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, ringPaint);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, ringPaint);
        float dotR = 10f;
        for (GpsLockService.Sat s : sats) {
            if (s.elevation < 0 || s.azimuth < 0) continue;
            float frac = (90f - Math.min(s.elevation, 90)) / 90f;
            double rad = Math.toRadians(s.azimuth);
            float x = (float) (cx + radius * frac * Math.sin(rad));
            float y = (float) (cy - radius * frac * Math.cos(rad));
            if (s.used) {
                dotPaint.setColor(COLOR_LIVE);
            } else if (s.snr >= 0) {
                dotPaint.setColor(COLOR_GOLD);
            } else {
                dotPaint.setColor(COLOR_GREY);
            }
            canvas.drawCircle(x, y, dotR, dotPaint);
        }
    }
}
