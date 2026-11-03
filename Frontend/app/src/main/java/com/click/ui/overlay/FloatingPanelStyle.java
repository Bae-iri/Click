package com.click.ui.overlay;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/** Shared surfaces for the input, review and guidance panels. */
final class FloatingPanelStyle {
    private FloatingPanelStyle() {}

    static GradientDrawable surface(float density) {
        return gradient(density, 28, 0xFFFEFEFF, 0xFFF5F7FD);
    }

    static GradientDrawable summary(float density) {
        return gradient(density, 18, 0xFFEAF1FF, 0xFFDCE7FF);
    }

    static Drawable primary(float density) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                gradient(density, 16, 0xFF3987FF, 0xFF1954F5),
                gradient(density, 16, Color.WHITE, Color.WHITE));
    }

    private static GradientDrawable gradient(float density, int radius, int start, int end) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{start, end});
        drawable.setCornerRadius(radius * density);
        return drawable;
    }
}
