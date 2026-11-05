package com.click.ui.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Notebook paper with a left binding; rows remain ordinary accessible Android views. */
public final class LibraryNotebookBackground extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    public LibraryNotebookBackground(float density) { this.density = density; }
    @Override public void draw(Canvas canvas) {
        RectF paper = new RectF(getBounds());
        paper.left += 9 * density; paper.top += density; paper.right -= density; paper.bottom -= density;
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFFFFEFC);
        canvas.drawRoundRect(paper, 20 * density, 20 * density, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density); paint.setColor(0xFFDCE6F3);
        canvas.drawRoundRect(paper, 20 * density, 20 * density, paint);
        for (float y = paper.top + 34 * density; y < paper.bottom - 18 * density; y += 40 * density) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFDCE6F3);
            canvas.drawCircle(paper.left + 14 * density, y, 4 * density, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3 * density); paint.setColor(0xFF9EB5D5);
            canvas.drawRoundRect(paper.left - 5 * density, y - 4 * density,
                    paper.left + 14 * density, y + 4 * density, 4 * density, 4 * density, paint);
        }
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
