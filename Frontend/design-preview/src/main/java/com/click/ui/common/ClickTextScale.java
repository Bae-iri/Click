package com.click.ui.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.click.designpreview.R;

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

    /** Scale inflated account screens without multiplying again on repeated updates. */
    public static void applyTree(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Object saved = text.getTag(R.id.click_text_base_size);
            float base = saved instanceof Float ? (Float) saved : text.getTextSize();
            text.setTag(R.id.click_text_base_size, base);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * scale(view.getContext()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i));
        }
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
