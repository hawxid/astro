package id.hawx.astro.geomac.layers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/** Small round dot markers for Atlas layers (flights, quakes). Size in dp. */
public final class LayerMarkers {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(32);

    private LayerMarkers() {}

    public static Drawable dot(Context context, int color, int sizeDp) {
        String key = color + "|" + sizeDp;
        Bitmap bmp = CACHE.get(key);
        if (bmp == null) {
            float density = context.getResources().getDisplayMetrics().density;
            int size = Math.round(sizeDp * density);
            if (size < 2) size = 2;
            bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setColor(Color.WHITE);
            ring.setStyle(Paint.Style.FILL);
            Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
            body.setColor(color);
            body.setStyle(Paint.Style.FILL);
            float cx = size / 2f;
            c.drawCircle(cx, cx, cx, ring);
            c.drawCircle(cx, cx, cx * 0.72f, body);
            CACHE.put(key, bmp);
        }
        return new BitmapDrawable(context.getResources(), bmp);
    }
}
