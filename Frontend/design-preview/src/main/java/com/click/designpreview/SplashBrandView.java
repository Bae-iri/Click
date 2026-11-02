package com.click.designpreview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/** Uses the same cursor and cropped wordmark as the home header. */
public final class SplashBrandView extends View {
    private final Drawable cursor;
    private final Drawable wordmark;

    public SplashBrandView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cursor = context.getDrawable(R.drawable.ic_click_cursor).mutate();
        wordmark = context.getDrawable(R.drawable.click).mutate();
        if (wordmark instanceof BitmapDrawable) ((BitmapDrawable) wordmark).setFilterBitmap(true);
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(32 * density);
        setBackground(card);
        setElevation(4 * density);
        if (Build.VERSION.SDK_INT >= 28) {
            setOutlineAmbientShadowColor(0x243D5D8F);
            setOutlineSpotShadowColor(0x303D5D8F);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 272f, getHeight() / 136f);
        canvas.save();
        canvas.translate((getWidth() - 272 * scale) / 2, (getHeight() - 136 * scale) / 2);
        canvas.scale(scale, scale);
        cursor.setBounds(21, 32, 93, 104);
        cursor.draw(canvas);
        // The supplied wordmark includes transparent margins; crop exactly as on home.
        canvas.translate(99, 43);
        canvas.scale(144f / 1741f, 50f / 611f);
        canvas.clipRect(0, 0, 1741, 611);
        canvas.translate(-211, -71);
        wordmark.setBounds(0, 0, 2172, 724);
        wordmark.draw(canvas);
        canvas.restore();
    }
}
