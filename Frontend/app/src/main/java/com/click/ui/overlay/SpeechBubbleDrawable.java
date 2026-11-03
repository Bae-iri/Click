package com.click.ui.overlay;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Scalable conversation bubble; the tail stays inside its measured bounds. */
final class SpeechBubbleDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private final boolean outgoing;

    SpeechBubbleDrawable(int color, float density, boolean outgoing) {
        paint.setColor(color);
        this.density = density;
        this.outgoing = outgoing;
    }

    @Override public void draw(Canvas canvas) {
        float left = getBounds().left, right = getBounds().right;
        float top = getBounds().top, bottom = getBounds().bottom;
        float tail = 6 * density;
        float edge = outgoing ? right : left;
        float direction = outgoing ? -1 : 1;
        float bodyBottom = bottom - tail;
        path.reset();
        path.moveTo(edge + direction * 18 * density, bodyBottom - 9 * density);
        path.quadTo(edge + direction * 10 * density, bodyBottom, edge, bottom);
        path.quadTo(edge + direction * 8 * density, bodyBottom - 6 * density,
                edge + direction * 5 * density, bodyBottom - 18 * density);
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawRoundRect(new RectF(left, top, right, bodyBottom),
                18 * density, 18 * density, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
