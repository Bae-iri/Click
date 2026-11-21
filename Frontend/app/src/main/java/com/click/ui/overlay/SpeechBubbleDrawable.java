package com.click.ui.overlay;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** A side tail points toward the speaker and stays inside the measured bounds. */
public final class SpeechBubbleDrawable extends Drawable {
    static final int TAIL_DP = 14;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private final boolean outgoing;
    private final int fill, border;

    public SpeechBubbleDrawable(int color, float density, boolean outgoing) {
        this(color, density, outgoing, Color.TRANSPARENT);
    }

    public SpeechBubbleDrawable(int color, float density, boolean outgoing, int border) {
        this.fill = color;
        this.border = border;
        this.density = density;
        this.outgoing = outgoing;
    }

    @Override public void draw(Canvas canvas) {
        float inset = border == Color.TRANSPARENT ? 0 : density / 2f;
        float right = getBounds().width() - inset, bottom = getBounds().height() - inset;
        float left = Math.min(TAIL_DP * density, right / 3f);
        float r = Math.min(16 * density, Math.min((right - left) / 2f, (bottom - inset) / 3f));
        if (r <= 0) return;
        float middle = (inset + bottom) / 2f;
        if (border != Color.TRANSPARENT) middle = Math.max(middle, bottom - 40 * density);
        float tail = Math.min(7 * density, Math.min(middle - inset - r, bottom - r - middle));
        canvas.save();
        canvas.translate(outgoing ? getBounds().right : getBounds().left, getBounds().top);
        if (outgoing) canvas.scale(-1, 1);
        path.rewind();
        path.moveTo(left + r, inset);
        path.lineTo(right - r, inset);
        path.quadTo(right, inset, right, inset + r);
        path.lineTo(right, bottom - r);
        path.quadTo(right, bottom, right - r, bottom);
        path.lineTo(left + r, bottom);
        path.quadTo(left, bottom, left, bottom - r);
        path.lineTo(left, middle + tail);
        path.quadTo(left, middle + tail * .7f, inset, middle + tail * .45f);
        path.quadTo(left, middle - tail * .4f, left, middle - tail);
        path.lineTo(left, inset + r);
        path.quadTo(left, inset, left + r, inset);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        paint.setAlpha(Color.alpha(fill) * alpha / 255);
        canvas.drawPath(path, paint);
        if (border != Color.TRANSPARENT) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(density);
            paint.setColor(border);
            paint.setAlpha(Color.alpha(border) * alpha / 255);
            canvas.drawPath(path, paint);
        }
        canvas.restore();
    }

    private int alpha = 255;
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
