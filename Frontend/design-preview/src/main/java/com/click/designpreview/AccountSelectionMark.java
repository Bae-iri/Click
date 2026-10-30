package com.click.designpreview;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** A centered, consistently sized single-selection indicator. */
public final class AccountSelectionMark extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean checked;
    public AccountSelectionMark(float density) { this.density = density; }
    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] states) {
        boolean next = false;
        for (int state : states) if (state == android.R.attr.state_checked) next = true;
        if (next == checked) return false;
        checked = next; invalidateSelf(); return true;
    }
    @Override public void draw(Canvas canvas) {
        float x = getBounds().exactCenterX(), y = getBounds().exactCenterY();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density);
        paint.setColor(checked ? AccountUi.BLUE : 0xFF9AAAC0);
        canvas.drawCircle(x, y, 9 * density, paint);
        if (checked) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, 5 * density, paint); }
    }
    @Override public int getIntrinsicWidth() { return Math.round(24 * density); }
    @Override public int getIntrinsicHeight() { return Math.round(24 * density); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
