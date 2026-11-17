package com.click.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import com.click.designpreview.R;

/** App presentation only. Observation, speech and automation deadlines stay independent. */
public final class ClickUiSpeed {
    private static final String PREFS = "click_display_settings";
    private static final String LEVEL = "ui_speed_level";
    private static final float[] DURATION_FACTORS = {2f, 1f, .5f};
    private static final int[] LABELS = {R.string.ui_speed_slow, R.string.ui_speed_normal, R.string.ui_speed_fast};

    private ClickUiSpeed() {}

    public static int level(Context context) {
        return clamp(preferences(context).getInt(LEVEL, 1));
    }

    public static void saveLevel(Context context, int level) {
        preferences(context).edit().putInt(LEVEL, clamp(level)).apply();
    }

    public static String label(Context context) { return context.getString(LABELS[level(context)]); }

    public static long duration(Context context, long normalMs) {
        return ValueAnimator.areAnimatorsEnabled()
                ? Math.max(0L, Math.round(normalMs * (double) DURATION_FACTORS[level(context)])) : 0L;
    }

    /** Fade without moving controls or changing the screen's actionable geometry. */
    public static void reveal(View view) {
        if (view == null) return;
        finishReveal(view);
        long duration = duration(view.getContext(), 240L);
        if (duration == 0L) return;
        view.setAlpha(.35f);
        view.animate().alpha(1f).setStartDelay(0L).setDuration(duration)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    public static void finishReveal(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(1f);
    }

    private static int clamp(int level) { return Math.max(0, Math.min(DURATION_FACTORS.length - 1, level)); }
    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
