package com.click.designpreview;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;

/** A home-style white card around the shared Click logo. */
public final class SplashBrandView extends ClickBrandView {
    public SplashBrandView(Context context, AttributeSet attrs) {
        super(context, attrs);
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
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float scale = Math.min(width / 272f, height / 136f);
        int horizontal = Math.round(24 * scale), vertical = Math.round(32 * scale);
        setPadding(horizontal, vertical, horizontal, vertical);
    }
}
