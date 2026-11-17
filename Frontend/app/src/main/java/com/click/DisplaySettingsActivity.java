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
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.click.ui.common.ClickTextScale;

/** Shared app display preferences, also available before onboarding. */
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
        add(page, text("글자 크기와 화면 표시 속도를 편하게 맞춰주세요.", 14,
                SECONDARY, regular), 20);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(26);
        var sizeControl = new com.click.designpreview.TextSizeControlView(this);
        sizeControl.setOnSizeChanged(() -> ClickTextScale.applyTree(root));
        page.addView(sizeControl, cardParams);
        add(page, new com.click.designpreview.UiSpeedControlView(this), 20);

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
        TextView title = text("화면 설정", 22, INK, semibold);
        title.setPadding(dp(12), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
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
