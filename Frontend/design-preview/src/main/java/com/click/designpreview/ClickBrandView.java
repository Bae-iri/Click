package com.click.designpreview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

/** The home-style cursor and original wordmark, kept in a single horizontal lockup. */
public class ClickBrandView extends View {
    private final Drawable cursor;
    private final Drawable wordmark;

    public ClickBrandView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cursor = context.getDrawable(R.drawable.ic_click_cursor).mutate();
        wordmark = context.getDrawable(R.drawable.click_wordmark).mutate();
        if (wordmark instanceof BitmapDrawable) ((BitmapDrawable) wordmark).setFilterBitmap(true);
        setContentDescription(context.getString(R.string.splash_logo_description));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        float height = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        float scale = Math.min(width / 222f, height / 72f);
        canvas.save();
        canvas.translate(getPaddingLeft() + (width - 222 * scale) / 2,
                getPaddingTop() + (height - 72 * scale) / 2);
        canvas.scale(scale, scale);
        cursor.setBounds(0, 0, 72, 72);
        cursor.draw(canvas);
        // Match the home header's crop of the source artwork's transparent margins.
        canvas.translate(78, 11);
        canvas.scale(144f / 1741f, 50f / 611f);
        canvas.clipRect(0, 0, 1741, 611);
        canvas.translate(-211, -71);
        wordmark.setBounds(0, 0, 2172, 724);
        wordmark.draw(canvas);
        canvas.restore();
    }
}
