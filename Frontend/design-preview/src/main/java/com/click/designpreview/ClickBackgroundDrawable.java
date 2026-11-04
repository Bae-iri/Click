package com.click.designpreview;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** The original home background, shared with login and signup. */
public final class ClickBackgroundDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public ClickBackgroundDrawable(Resources resources) { density = resources.getDisplayMetrics().density; }

    @Override protected void onBoundsChange(Rect bounds) {
        paint.setShader(new RadialGradient(bounds.right + 4 * density, bounds.top + 215 * density,
                210 * density, new int[]{0xFFDFF1FF, 0xFFECF7FF, 0xFFFCFDFF},
                new float[]{0, .67f, 1}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) { canvas.drawRect(getBounds(), paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
