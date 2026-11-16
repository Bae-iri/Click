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
    private Typeface regular;
    private AccessibilityConnectionWatcher connectionWatcher;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        connectionWatcher = new AccessibilityConnectionWatcher(this);
        medium = getResources().getFont(R.font.pretendard_medium);
        semibold = getResources().getFont(R.font.pretendard_semibold);
        regular = getResources().getFont(R.font.pretendard_regular);
        configureWindow();
    }

    @Override protected void onResume() {
        super.onResume();
        render(ClickHost.from(this).accessibilityConnected());
        connectionWatcher.start(() -> render(true), () -> {});
    }

    @Override protected void onPause() {
        connectionWatcher.stop();
        super.onPause();
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
        ClickBrandView logo = new ClickBrandView(this, null);
        row.addView(logo, new LinearLayout.LayoutParams(dp(112), dp(38)));
        return row;
    }

    private void addRequiredContent(LinearLayout body) {
        boolean reconnect = ClickHost.from(this).accessibilityEnabled();
        TextView title = text(reconnect ? "화면 읽기 연결을\n다시 확인해 주세요"
                : "아직은 보고 계신 화면을\n확인할 수 없어요", 31, INK, semibold);
        title.setLetterSpacing(-.025f);
        title.setLineSpacing(dp(2), 1.04f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = text(reconnect
                ? "접근성은 켜져 있지만\n화면 읽기 연결이 끊겼어요.\n\n접근성 안내를 따라 클릭 화면 안내를\n껐다가 다시 켜주세요."
                : "지금은 접근성 허용이 되지 않아\n클릭이 도와드릴 수 없어요.\n\n접근성 설정에서 클릭을 허용해 주시면\n바로 도와드릴게요.", 17, SECONDARY, regular);
        description.setLineSpacing(dp(4), 1.16f);
        add(body, description, 18);

        add(body, characterIllustration(), 28, 260);
    }

    private void addCompleteContent(LinearLayout body) {
        TextView title = text("접근성 설정이\n완료됐어요", 31, INK, semibold);
        title.setLetterSpacing(-.025f);
        title.setLineSpacing(dp(2), 1.04f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = text("이제 보고 계신 화면을 확인하고\n필요한 버튼과 순서를 안내할 수 있어요.", 17, SECONDARY, regular);
        description.setLineSpacing(dp(4), 1.16f);
        add(body, description, 18);
        add(body, characterIllustration(), 28, 260);
    }

    private ImageView characterIllustration() {
        ImageView character = new ImageView(this);
        character.setImageResource(R.drawable.welcome);
        character.setScaleType(ImageView.ScaleType.FIT_CENTER);
        character.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return character;
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
        TextView label = text(enabled ? "클릭 시작하기" : "접근성 설정하러 가기",
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
            else startActivity(new android.content.Intent(this, PermissionGuideActivity.class)
                    .putExtra(PermissionGuideActivity.EXTRA_RECONNECT, ClickHost.from(this).accessibilityEnabled()));
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
