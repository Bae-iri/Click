package com.click;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.FloatingTransparency;
import com.click.ui.overlay.SpeechBubbleDrawable;

/** Display preferences for Click text and floating guidance. */
public final class DisplaySettingsActivity extends AppCompatActivity {
    private static final int BLUE = 0xFF3478F6;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF7182A4;

    private Typeface regular;
    private Typeface semibold;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        regular = getResources().getFont(com.click.designpreview.R.font.pretendard_regular);
        semibold = getResources().getFont(com.click.designpreview.R.font.pretendard_semibold);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(22);
        page.setPadding(horizontal, dp(14), horizontal, dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            page.setPadding(horizontal, dp(14) + insets.getSystemWindowInsetTop(), horizontal,
                    dp(32) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        page.addView(header(), new LinearLayout.LayoutParams(-1, -2));
        var sizeControl = new com.click.designpreview.TextSizeControlView(this);
        sizeControl.setOnSizeChanged(() -> ClickTextScale.applyTree(root));
        add(page, sizeControl, 18);
        add(page, transparencyControl(), 16);

        setContentView(root);
        root.requestApplyInsets();
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        TextView back = text("‹", 36, INK, regular);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("설정으로 돌아가기");
        back.setClickable(true);
        back.setFocusable(true);
        back.setBackground(ripple(Color.WHITE, 24));
        back.setOnClickListener(view -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("글자 크기와 투명도", 22, INK, semibold);
        title.setPadding(dp(12), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View transparencyControl() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        card.setBackground(ClickUi.outlined(Color.WHITE, 0xFFE3EAF6, dp(24), dp(1)));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("투명도", 16, INK, semibold);
        label.setLabelFor(R.id.floating_transparency_scale);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text("", 13, BLUE, semibold);
        value.setId(R.id.floating_transparency_value);
        heading.addView(value);
        card.addView(heading);
        add(card, text("클릭 버튼과 안내 말풍선이 비치는 정도예요.", 14, SECONDARY, regular), 6);
        add(card, floatingPreview(), 14);

        SeekBar slider = new SeekBar(this);
        slider.setId(R.id.floating_transparency_scale);
        slider.setMax(FloatingTransparency.MAX_PERCENT);
        slider.setKeyProgressIncrement(5);
        slider.setMinHeight(dp(48));
        slider.setPadding(dp(12), 0, dp(12), 0);
        slider.setProgressTintList(ColorStateList.valueOf(BLUE));
        slider.setThumbTintList(ColorStateList.valueOf(BLUE));
        slider.setContentDescription("클릭 버튼과 안내 말풍선 투명도");
        slider.setProgress(FloatingTransparency.percent(this));
        showPercent(value, slider, slider.getProgress());
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                FloatingTransparency.save(DisplaySettingsActivity.this, progress);
                showPercent(value, bar, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        add(card, slider, 4);

        LinearLayout ends = new LinearLayout(this);
        ends.addView(text("선명하게", 12, SECONDARY, regular), new LinearLayout.LayoutParams(0, -2, 1));
        ends.addView(text("투명하게", 12, SECONDARY, regular));
        card.addView(ends);
        return card;
    }

    private View floatingPreview() {
        LinearLayout sample = new LinearLayout(this);
        sample.setGravity(Gravity.CENTER);
        sample.setPadding(dp(12), dp(12), dp(12), dp(12));
        sample.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFDEE9FE, 0xFFA5C4EC});
        backdrop.setCornerRadius(dp(14));
        sample.setBackground(backdrop);
        ImageView cursor = new ImageView(this);
        cursor.setImageDrawable(getDrawable(com.click.designpreview.R.drawable.icon_floating_cursor).mutate());
        sample.addView(cursor, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView bubble = text("안내 말풍선", 15, INK, semibold);
        bubble.setId(R.id.floating_transparency_preview);
        bubble.setBackground(new SpeechBubbleDrawable(Color.WHITE,
                getResources().getDisplayMetrics().density, false, 0xFFB9C6D8));
        bubble.setPadding(dp(26), dp(10), dp(14), dp(10));
        sample.addView(bubble);
        FloatingTransparency.bind(sample, cursor.getDrawable(), bubble.getBackground());
        return sample;
    }

    private void showPercent(TextView value, SeekBar slider, int percent) {
        String formatted = getString(R.string.floating_transparency_percent, percent);
        value.setText(formatted);
        if (Build.VERSION.SDK_INT >= 30) slider.setStateDescription(formatted);
    }

    private android.graphics.drawable.Drawable ripple(int color, int radius) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(radius));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radius));
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x1A3478F6), fill, mask);
    }

    private TextView text(String value, float size, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        ClickTextScale.applyTree(view);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setIncludeFontPadding(false);
        return view;
    }

    private void add(LinearLayout parent, View child, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMarginDp);
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
