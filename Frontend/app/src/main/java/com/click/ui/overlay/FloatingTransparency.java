package com.click.ui.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Opacity of the floating cursor and bubble surfaces, independent of their text and touch targets. */
public final class FloatingTransparency {
    public static final int MAX_PERCENT = 60;
    private static final String KEY = "transparency_percent";

    private FloatingTransparency() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("click_floating_appearance", Context.MODE_PRIVATE);
    }

    public static int percent(Context context) {
        return clamp(preferences(context).getInt(KEY, 0));
    }

    public static void save(Context context, int percent) {
        preferences(context).edit().putInt(KEY, clamp(percent)).apply();
    }

    private static int clamp(int percent) {
        return Math.max(0, Math.min(MAX_PERCENT, percent));
    }

    public static void bind(View owner, Drawable... surfaces) {
        Context context = owner.getContext();
        SharedPreferences prefs = preferences(context);
        Runnable refresh = () -> {
            int alpha = Math.round(255 * (100 - percent(context)) / 100f);
            for (Drawable surface : surfaces) surface.setAlpha(alpha);
            owner.invalidate();
        };
        SharedPreferences.OnSharedPreferenceChangeListener listener = (store, key) -> {
            if (key == null || KEY.equals(key)) refresh.run();
        };
        owner.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                prefs.registerOnSharedPreferenceChangeListener(listener);
                refresh.run();
            }

            @Override public void onViewDetachedFromWindow(View view) {
                prefs.unregisterOnSharedPreferenceChangeListener(listener);
            }
        });
        if (owner.isAttachedToWindow()) prefs.registerOnSharedPreferenceChangeListener(listener);
        refresh.run();
    }
}
