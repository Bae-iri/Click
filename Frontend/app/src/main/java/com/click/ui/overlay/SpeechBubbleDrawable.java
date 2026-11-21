package com.click.ui.overlay;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A side tail points toward the speaker and stays inside the measured bounds. */
public final class SpeechBubbleDrawable extends Drawable {
    static final int TAIL_DP = 14;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private final boolean outgoing;

    public SpeechBubbleDrawable(int color, float density, boolean outgoing) {
        paint.setColor(color);
        this.density = density;
        this.outgoing = outgoing;
    }

    @Override public void draw(Canvas canvas) {
        float left = getBounds().left, right = getBounds().right;
        float top = getBounds().top, bottom = getBounds().bottom;
        float tail = TAIL_DP * density;
        float edge = outgoing ? right : left;
        float direction = outgoing ? -1 : 1;
        float bodyEdge = edge + direction * tail;
        float middle = (top + bottom) / 2;
        canvas.drawRoundRect(new RectF(outgoing ? left : bodyEdge, top,
                outgoing ? bodyEdge : right, bottom), 18 * density, 18 * density, paint);
        path.reset();
        path.moveTo(bodyEdge + direction * 3 * density, middle - 11 * density);
        path.lineTo(edge, middle + 4 * density);
        path.lineTo(bodyEdge + direction * 3 * density, middle + 12 * density);
        path.close();
        canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
