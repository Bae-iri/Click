package com.click.ui.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextView;

/** App-only text sizing, kept separate from the phone's accessibility setting. */
public final class ClickTextScale {
    private static final String PREFS = "click_display_settings";
    private static final String LEVEL = "text_size_level";
    private static final float[] SCALES = {0.9f, 1f, 1.15f, 1.3f};

    private ClickTextScale() {}

    public static int level(Context context) {
        return Math.max(0, Math.min(SCALES.length - 1,
                preferences(context).getInt(LEVEL, 1)));
    }

    public static float scale(Context context) { return SCALES[level(context)]; }

    public static void saveLevel(Context context, int level) {
        preferences(context).edit().putInt(LEVEL,
                Math.max(0, Math.min(SCALES.length - 1, level))).apply();
    }

    public static float size(Context context, float baseSp) { return baseSp * scale(context); }

    public static void apply(TextView view, float baseSp) {
        view.setTextSize(size(view.getContext(), baseSp));
    }

    public static String label(Context context) {
        switch (level(context)) {
            case 0: return "작게";
            case 2: return "크게";
            case 3: return "아주 크게";
            default: return "기본";
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
