package com.click.designpreview;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A static example of Click guidance. No control here changes device settings. */
public final class OnboardingSettingsPhoneView extends LinearLayout {
    private static final int BLUE = 0xFF3F78F7, INK = 0xFF111B32, MUTED = 0xFF7383A2;
    private final Typeface regular, medium;

    public OnboardingSettingsPhoneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        regular = getResources().getFont(R.font.pretendard_regular);
        medium = getResources().getFont(R.font.pretendard_semibold);
        setOrientation(VERTICAL);
        setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable shell = rounded(0xFFE5EDFA, 30);
        shell.setStroke(dp(1), 0xFFCBD9EF);
        setBackground(shell);

        LinearLayout screen = column();
        screen.setPadding(dp(12), dp(12), dp(12), dp(12));
        screen.setBackground(rounded(Color.WHITE, 24));
        screen.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        addView(screen, new LayoutParams(-1, 0, 1));

        FrameLayout status = new FrameLayout(context);
        TextView clock = text("9:41", 10, MUTED, false);
        status.addView(clock, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        View camera = new View(context);
        camera.setBackground(rounded(0xFFCBD7E8, 4));
        status.addView(camera, new FrameLayout.LayoutParams(dp(5), dp(5), Gravity.CENTER));
        screen.addView(status, new LayoutParams(-1, dp(18)));

        var configuration = getResources().getConfiguration();
        boolean compact = configuration.fontScale >= 1.3f || configuration.screenHeightDp < 700
                || configuration.screenWidthDp < 420;
        TextView title = text(getString(R.string.onboarding_phone_display), compact ? 17 : 21, INK, true);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.onboarding_back, 0, 0, 0);
        title.setCompoundDrawablePadding(dp(7));
        add(screen, title, 15);
        // The example's target stays near the top on short screens or with larger text.
        if (!compact) {
            add(screen, text(getString(R.string.onboarding_phone_brightness), 13, INK, false), 20);
            LinearLayout slider = new LinearLayout(context);
            slider.setBackground(rounded(0xFFE7EDF8, 3));
            View progress = new View(context); progress.setBackground(rounded(BLUE, 3));
            slider.addView(progress, new LayoutParams(0, -1, .62f));
            slider.addView(new View(context), new LayoutParams(0, -1, .38f));
            LayoutParams sliderParams = new LayoutParams(-1, dp(5)); sliderParams.topMargin = dp(13);
            screen.addView(slider, sliderParams);

            LinearLayout adaptive = new LinearLayout(context);
            adaptive.setGravity(Gravity.CENTER_VERTICAL);
            adaptive.addView(text(getString(R.string.onboarding_phone_adaptive), 13, INK, false), new LayoutParams(0, -2, 1));
            FrameLayout toggle = new FrameLayout(context); toggle.setBackground(rounded(BLUE, 12));
            View thumb = new View(context); thumb.setBackground(rounded(Color.WHITE, 9));
            FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.END | Gravity.CENTER_VERTICAL);
            thumbParams.rightMargin = dp(3); toggle.addView(thumb, thumbParams);
            adaptive.addView(toggle, new LayoutParams(dp(36), dp(22)));
            add(screen, adaptive, 20);

            LinearLayout mode = column();
            mode.addView(text(getString(R.string.onboarding_phone_mode), 13, INK, false));
            add(mode, text(getString(R.string.onboarding_phone_mode_value), 11, MUTED, false), 4);
            add(screen, mode, 22);
        }

        LinearLayout highlight = new LinearLayout(context);
        highlight.setTag("onboarding_font_size_highlight");
        highlight.setGravity(Gravity.CENTER_VERTICAL);
        highlight.setPadding(dp(10), dp(13), dp(8), dp(13));
        GradientDrawable selected = rounded(0xFFEEF4FF, 12); selected.setStroke(dp(2), BLUE);
        highlight.setBackground(selected);
        LinearLayout label = column();
        label.addView(text(getString(R.string.onboarding_phone_font), 14, INK, true));
        add(label, text(getString(R.string.onboarding_phone_font_hint), 11, BLUE, false), 6);
        highlight.addView(label, new LayoutParams(0, -2, 1));
        add(screen, highlight, 20);
        add(screen, text(getString(R.string.onboarding_phone_zoom), 13, INK, false), 20);
        screen.addView(new View(context), new LayoutParams(-1, 0, 1));
        View gesture = new View(context); gesture.setBackground(rounded(0xFFB7C7DE, 3));
        LayoutParams gestureParams = new LayoutParams(dp(48), dp(3));
        gestureParams.gravity = Gravity.CENTER_HORIZONTAL; gestureParams.topMargin = dp(24);
        screen.addView(gesture, gestureParams);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Keep a handset silhouette when the compact example has fewer rows.
        setMinimumHeight(Math.round(MeasureSpec.getSize(widthSpec) * 1.5f));
        super.onMeasure(widthSpec, heightSpec);
    }

    private String getString(int resource) { return getResources().getString(resource); }
    private LinearLayout column() {
        LinearLayout result = new LinearLayout(getContext()); result.setOrientation(VERTICAL); return result;
    }
    private TextView text(String value, int size, int color, boolean emphasis) {
        TextView result = new TextView(getContext());
        // These labels belong to the static handset illustration. Its single accessibility
        // description exposes the example while surrounding onboarding copy scales normally.
        result.setText(value); result.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size); result.setTextColor(color);
        result.setTypeface(emphasis ? medium : regular); result.setIncludeFontPadding(false);
        result.setLineSpacing(dp(2), 1.1f);
        return result;
    }
    private void add(LinearLayout parent, View child, int top) {
        LayoutParams params = new LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(child, params);
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
