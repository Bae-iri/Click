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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.click.ui.common.ClickTextScale;

/** Display preferences for text rendered inside Click. */
public final class DisplaySettingsActivity extends AppCompatActivity {
    private static final int BLUE = 0xFF3478F6;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF7182A4;

    private Typeface regular;
    private Typeface medium;
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
        medium = getResources().getFont(com.click.designpreview.R.font.pretendard_medium);
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
        add(page, text("클릭 화면과 안내 글자의 크기를 조절할 수 있어요.", 14,
                SECONDARY, regular), 20);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(26);
        page.addView(sizeCard(), cardParams);

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
        TextView title = text("클릭 글자 크기", 22, INK, semibold);
        title.setPadding(dp(12), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View sizeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        card.setBackground(cardBackground());

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("글자 크기", 16, INK, medium),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView current = text(ClickTextScale.label(this), 13, BLUE, medium);
        heading.addView(current, new LinearLayout.LayoutParams(-2, -2));
        card.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView smaller = text("가", 15, SECONDARY, regular);
        TextView preview = text("가나다라마바사", 17, INK, medium);
        ClickTextScale.apply(preview, 17);
        TextView bigger = text("가", 23, BLUE, semibold);
        previewRow.addView(smaller, new LinearLayout.LayoutParams(-2, dp(48)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        previewParams.setMarginStart(dp(12));
        previewParams.setMarginEnd(dp(12));
        previewRow.addView(preview, previewParams);
        previewRow.addView(bigger, new LinearLayout.LayoutParams(-2, dp(48)));
        LinearLayout.LayoutParams previewRowParams = new LinearLayout.LayoutParams(-1, -2);
        previewRowParams.topMargin = dp(14);
        card.addView(previewRow, previewRowParams);

        SeekBar scale = new SeekBar(this);
        scale.setMax(3);
        scale.setProgress(ClickTextScale.level(this));
        scale.setContentDescription("클릭 글자 크기 조절");
        scale.setMinHeight(dp(48));
        scale.setPadding(dp(4), 0, dp(4), 0);
        scale.setProgressTintList(ColorStateList.valueOf(BLUE));
        scale.setThumbTintList(ColorStateList.valueOf(BLUE));
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                ClickTextScale.saveLevel(DisplaySettingsActivity.this, progress);
                current.setText(ClickTextScale.label(DisplaySettingsActivity.this));
                ClickTextScale.apply(preview, 17);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout.LayoutParams scaleParams = new LinearLayout.LayoutParams(-1, -2);
        scaleParams.topMargin = dp(8);
        card.addView(scale, scaleParams);

        LinearLayout labels = new LinearLayout(this);
        TextView min = text("작게", 12, SECONDARY, regular);
        TextView max = text("아주 크게", 12, SECONDARY, regular);
        labels.addView(min, new LinearLayout.LayoutParams(0, -2, 1));
        max.setGravity(Gravity.END);
        labels.addView(max, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(labels, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), 0xFFF0F3F8);
        return background;
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
        view.setTextSize(ClickTextScale.size(this, size));
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
