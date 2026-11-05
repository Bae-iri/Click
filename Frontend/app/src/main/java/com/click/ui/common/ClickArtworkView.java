package com.click.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Draws only the visible part of the original brand artwork, keeping its proportions. */
public final class ClickArtworkView extends View {
    public enum Kind { WORDMARK, CURSOR, HAND }
    private final Drawable artwork;
    private final int width, height;
    private final float left, top, right, bottom;

    public ClickArtworkView(Context context, Kind kind) {
        super(context);
        if (kind == Kind.WORDMARK) {
            artwork = context.getDrawable(com.click.designpreview.R.drawable.click_wordmark);
            width = 2172; height = 724; left = 211; top = 71; right = 1952; bottom = 682;
        } else if (kind == Kind.CURSOR) {
            artwork = context.getDrawable(com.click.designpreview.R.drawable.ic_click_cursor);
            width = height = 108; left = 12; top = 8; right = 96; bottom = 96;
        } else {
            artwork = context.getDrawable(com.click.designpreview.R.drawable.permission_touch_hand);
            width = height = 1254; left = 216; top = 138; right = 1120; bottom = 1141;
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / (right - left), getHeight() / (bottom - top));
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        canvas.translate((getWidth() - (right - left) * scale) / 2,
                (getHeight() - (bottom - top) * scale) / 2);
        canvas.scale(scale, scale);
        canvas.translate(-left, -top);
        artwork.setBounds(0, 0, width, height);
        artwork.draw(canvas);
        canvas.restore();
    }
}
