package com.click.designpreview;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explains why Click needs accessibility, or confirms that permission is ready. */
public final class AccessibilityHelpActivity extends Activity {
    private static final int BLUE = 0xFF3478F6;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF7182A4;
    private Typeface medium;
    private Typeface semibold;
    private Typeface bold;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        medium = getResources().getFont(R.font.pretendard_medium);
        semibold = getResources().getFont(R.font.pretendard_semibold);
        bold = getResources().getFont(R.font.pretendard_bold);
        configureWindow();
    }

    @Override protected void onResume() {
        super.onResume();
        render(ClickHost.from(this).accessibilityEnabled());
    }

    private void render(boolean enabled) {
        FrameLayout canvas = new FrameLayout(this);
        canvas.setBackgroundColor(Color.WHITE);
        canvas.addView(new BrandBackgroundView(), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = vertical();
        int horizontal = dp(22);
        int maxWidth = Math.min(getResources().getDisplayMetrics().widthPixels, dp(500));
        canvas.addView(column, new FrameLayout.LayoutParams(maxWidth, -1, Gravity.CENTER_HORIZONTAL));
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(horizontal + insets.getSystemWindowInsetLeft(),
                    dp(12) + insets.getSystemWindowInsetTop(),
                    horizontal + insets.getSystemWindowInsetRight(),
                    dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        column.addView(brandHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = vertical();
        body.setPadding(dp(2), dp(18), dp(2), dp(18));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (enabled) addCompleteContent(body);
        else addRequiredContent(body);

        column.addView(footer(enabled), new LinearLayout.LayoutParams(-1, -2));
        setContentView(canvas);
        column.requestApplyInsets();
    }

    private View brandHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setMinimumHeight(dp(58));
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_click_cursor);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView wordmark = text("click", 29, 0xFF07183F, bold);
        wordmark.setLetterSpacing(-.04f);
        LinearLayout.LayoutParams wordmarkParams = new LinearLayout.LayoutParams(-2, -2);
        wordmarkParams.setMarginStart(dp(4));
        row.addView(wordmark, wordmarkParams);
        return row;
    }

    private void addRequiredContent(LinearLayout body) {
        TextView title = text("아직은 보고 계신 화면을\n확인할 수 없어요", 31, INK, bold);
        title.setLetterSpacing(-.025f);
        title.setLineSpacing(dp(2), 1.04f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = text("그래서 지금은 Click이 도와드릴 수 없어요.\n접근성 설정에서 Click을 허용해 주시면\n바로 도와드릴 수 있어요!", 17, SECONDARY, medium);
        description.setLineSpacing(dp(4), 1.16f);
        add(body, description, 18);

        add(body, heroCard(false), 28, 235);

        LinearLayout permission = new LinearLayout(this);
        permission.setGravity(Gravity.CENTER_VERTICAL);
        permission.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable permissionFill = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFF2F7FF, 0xFFEAF3FF, 0xFFF7FAFF});
        permissionFill.setCornerRadius(dp(22));
        permission.setBackground(permissionFill);

        FrameLayout iconCircle = new FrameLayout(this);
        iconCircle.setBackground(round(0xFFDCEBFF, 28));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_click_cursor);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        iconCircle.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        permission.addView(iconCircle, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout copy = vertical();
        copy.addView(text("접근성에서 Click 허용하기", 18, INK, semibold),
                new LinearLayout.LayoutParams(-1, -2));
        TextView subheading = text("화면을 확인하고 필요한 순서를 안내해요", 14, SECONDARY, medium);
        LinearLayout.LayoutParams subheadingParams = new LinearLayout.LayoutParams(-1, -2);
        subheadingParams.topMargin = dp(5);
        copy.addView(subheading, subheadingParams);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.setMarginStart(dp(14));
        permission.addView(copy, copyParams);
        add(body, permission, 16);
    }

    private void addCompleteContent(LinearLayout body) {
        TextView title = text("접근성 설정이\n완료됐어요", 31, INK, bold);
        title.setLetterSpacing(-.025f);
        title.setLineSpacing(dp(2), 1.04f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = text("이제 보고 계신 화면을 확인하고\n필요한 버튼과 순서를 안내할 수 있어요.", 17, SECONDARY, medium);
        description.setLineSpacing(dp(4), 1.16f);
        add(body, description, 18);
        add(body, heroCard(true), 28, 260);

        LinearLayout ready = vertical();
        ready.setPadding(dp(18), dp(16), dp(18), dp(16));
        ready.setBackground(round(0xFFF1F7FF, 22));
        ready.addView(text("Click이 준비됐어요", 19, INK, semibold),
                new LinearLayout.LayoutParams(-1, -2));
        TextView readyCopy = text("홈에서 말하거나 원하는 도움을 눌러 시작해 보세요.",
                15, SECONDARY, medium);
        LinearLayout.LayoutParams readyCopyParams = new LinearLayout.LayoutParams(-1, -2);
        readyCopyParams.topMargin = dp(6);
        ready.addView(readyCopy, readyCopyParams);
        add(body, ready, 16);
    }

    private FrameLayout heroCard(boolean enabled) {
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFF5F9FF, 0xFFEAF3FF});
        fill.setCornerRadius(dp(30));
        hero.setBackground(fill);

        ImageView character = new ImageView(this);
        character.setImageResource(R.drawable.welcome);
        character.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        character.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams characterParams = new FrameLayout.LayoutParams(
                enabled ? dp(220) : dp(195), enabled ? dp(225) : dp(205),
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        characterParams.bottomMargin = enabled ? dp(5) : 0;
        hero.addView(character, characterParams);

        TextView badge = text(enabled ? "✓" : "!", 20, Color.WHITE, bold);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(BLUE, 26));
        badge.setContentDescription(enabled ? "접근성 허용 완료" : "접근성 설정 필요");
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(50), dp(50),
                Gravity.END | Gravity.BOTTOM);
        badgeParams.setMargins(0, 0, dp(24), dp(22));
        hero.addView(badge, badgeParams);
        return hero;
    }

    private View footer(boolean enabled) {
        LinearLayout footer = vertical();
        footer.setPadding(0, dp(10), 0, dp(6));
        OnboardingStartButton action = new OnboardingStartButton(this, null);
        action.setId(R.id.accessibility_help_action);
        action.setGravity(Gravity.CENTER);
        action.setMinimumHeight(dp(64));
        action.setPadding(dp(18), dp(15), dp(18), dp(15));
        action.setBackground(OnboardingDrawables.startButton(getResources()));
        action.setElevation(dp(6));
        TextView label = text(enabled ? "Click 시작하기" : "접근성 설정하러 가기",
                20, Color.WHITE, semibold);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.addView(label, new LinearLayout.LayoutParams(-2, -2));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.onboarding_arrow);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrowParams.setMarginStart(dp(10));
        action.addView(arrow, arrowParams);
        action.setContentDescription(label.getText());
        action.setOnClickListener(view -> {
            if (enabled) ClickHost.from(this).openHome(this);
            else ClickHost.from(this).openAccessibilityGuide(this);
        });
        footer.addView(action, new LinearLayout.LayoutParams(-1, -2));

        TextView later = text(enabled ? "나중에 시작하기" : "지금은 앱 닫기",
                15, BLUE, medium);
        later.setGravity(Gravity.CENTER);
        later.setMinHeight(dp(46));
        later.setPadding(dp(12), dp(12), dp(12), dp(12));
        later.setBackground(new RippleDrawable(ColorStateList.valueOf(0x183478F6), null,
                round(Color.WHITE, 20)));
        later.setOnClickListener(view -> finishAffinity());
        LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(-1, -2);
        laterParams.topMargin = dp(3);
        footer.addView(later, laterParams);
        return footer;
    }

    private TextView text(String value, int size, int color, Typeface typeface) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(typeface);
        text.setIncludeFontPadding(false);
        return text;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private void add(LinearLayout parent, View child, int topMargin, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private void configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class BrandBackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BrandBackgroundView() { super(AccessibilityHelpActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setColor(0x416DA8FF);
            canvas.drawCircle(w * 1.02f, h * .12f, w * .28f, paint);
            paint.setColor(0x246DA8FF);
            canvas.drawCircle(w * .88f, h * .19f, w * .27f, paint);
            paint.setColor(0x227BB5FF);
            canvas.drawCircle(w * -.06f, h * .96f, w * .31f, paint);
        }
    }
}
