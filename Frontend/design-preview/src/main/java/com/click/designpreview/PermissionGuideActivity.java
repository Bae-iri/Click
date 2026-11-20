package com.click.designpreview;

import android.graphics.RectF;
import android.view.MotionEvent;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

/** Five static, reversible cards starting at the installed-apps list for every device. */
public final class PermissionGuideActivity extends Activity {
    public static final String EXTRA_RECONNECT = "accessibility_reconnect";
    public static final String EXTRA_RETURN_TO_SETTINGS = "accessibility_return_to_settings";
    public static final String EXTRA_ONBOARDING = "accessibility_from_onboarding";
    private static final int PAGE_COUNT = 5;
    private static final String PAGE_STATE = "permission_guide_cards_v5";
    private static final String SETTINGS_OPENED_STATE = "permission_settings_opened";
    private static final int BLUE = 0xFF3478F6;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF6F7F9E;
    private int page;
    private TutorialNarrator narrator;
    private String spokenPage;
    private LinearLayout body;
    private ScrollView scroll;
    private View backButton;
    private View[] progressBars;
    private TextView actionLabel;
    private OnboardingStartButton action;
    private TextView replay;
    private Typeface medium;
    private Typeface semibold;
    private Typeface regular;
    private boolean settingsOpened;
    private boolean tutorialOpened;
    private AccessibilityConnectionWatcher connectionWatcher;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null && getApplication() instanceof ClickHost) {
            ((ClickHost) getApplication()).cancelAccessibilityReturn();
        }
        connectionWatcher = new AccessibilityConnectionWatcher(this);
        narrator = new TutorialNarrator(this);
        settingsOpened = savedInstanceState != null
                && savedInstanceState.getBoolean(SETTINGS_OPENED_STATE, false);
        medium = getResources().getFont(R.font.pretendard_medium);
        semibold = getResources().getFont(R.font.pretendard_semibold);
        regular = getResources().getFont(R.font.pretendard_regular);
        configureWindow();

        FrameLayout canvas = new FrameLayout(this);
        canvas.setBackgroundColor(Color.WHITE);
        LinearLayout column = vertical();
        column.setId(R.id.guide_screen);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(480));
        canvas.addView(column, new FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL));
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(22) + insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    dp(22) + insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header = vertical();
        header.setBackgroundColor(Color.WHITE);
        header.setPadding(0, dp(8), 0, dp(16));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout progress = new LinearLayout(this);
        progress.setId(R.id.guide_progress_bars);
        progress.setOrientation(LinearLayout.HORIZONTAL);
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-2, -2);
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(12);
        header.addView(progress, progressParams);
        progressBars = new View[PAGE_COUNT];
        for (int i = 0; i < PAGE_COUNT; i++) {
            View bar = new View(this);
            bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(46), dp(6));
            if (i > 0) barParams.leftMargin = dp(6);
            progress.addView(bar, barParams);
            progressBars[i] = bar;
        }

        column.addView(header, new LinearLayout.LayoutParams(-1, -2));

        scroll = new CardScrollView();
        scroll.setId(R.id.guide_scroll);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        body = vertical();
        body.setPadding(dp(2), dp(8), dp(2), dp(14));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = vertical();
        footer.setBackgroundColor(Color.WHITE);
        footer.setPadding(0, dp(12), 0, dp(14));
        action = new OnboardingStartButton(this, null);
        action.setId(R.id.guide_action);
        action.setGravity(Gravity.CENTER);
        action.setOrientation(LinearLayout.HORIZONTAL);
        action.setMinimumHeight(dp(52));
        action.setPadding(dp(20), dp(14), dp(20), dp(14));
        action.setClickable(true);
        action.setFocusable(true);
        GradientDrawable buttonFill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF247AFF, 0xFF3987FF});
        buttonFill.setCornerRadius(dp(22));
        action.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), buttonFill,
                rounded(Color.WHITE, 22)));
        action.setElevation(0);
        if (Build.VERSION.SDK_INT >= 28) {
            action.setOutlineAmbientShadowColor(0xFF76ADFF);
            action.setOutlineSpotShadowColor(0xFF76ADFF);
        }
        actionLabel = text(0, 17, Color.WHITE, semibold);
        actionLabel.setGravity(Gravity.CENTER);
        actionLabel.setMaxWidth(Math.max(dp(80), width - dp(118)));
        actionLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.addView(actionLabel, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.onboarding_arrow);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrowParams.setMarginStart(dp(10));
        action.addView(arrow, arrowParams);
        action.setOnClickListener(view -> {
            if (page < PAGE_COUNT - 1) showPage(page + 1, true);
            else openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        });
        replay = new TextView(this); replay.setText("안내 다시 듣기");
        replay.setTag("permission_replay");
        replay.setTypeface(medium); replay.setTextSize(com.click.ui.common.ClickTextScale.size(this, 13)); replay.setTextColor(BLUE);
        replay.setSingleLine(true);
        replay.setGravity(Gravity.CENTER);
        replay.setFocusable(true);
        replay.setMinWidth(0); replay.setMinimumWidth(0);
        replay.setMinHeight(dp(48)); replay.setMinimumHeight(dp(48));
        replay.setPadding(dp(12), dp(8), dp(12), dp(8));
        replay.setBackground(null);
        replay.setStateListAnimator(null); replay.setElevation(0);
        replay.setOnClickListener(v -> { if (narrator.isSpeaking()) narrator.stop(); else narrator.replay(spokenPage); });
        narrator.setListener((speaking, unavailable) -> replay.setText(speaking ? "음성 멈춤" : "안내 다시 듣기"));
        TextView previous = new TextView(this);
        backButton = previous;
        previous.setId(R.id.guide_back);
        previous.setText(R.string.guide_card_previous);
        previous.setAllCaps(false);
        previous.setTypeface(medium);
        previous.setTextSize(com.click.ui.common.ClickTextScale.size(this, 13));
        previous.setTextColor(BLUE);
        previous.setSingleLine(true);
        previous.setGravity(Gravity.CENTER);
        previous.setFocusable(true);
        previous.setMinHeight(dp(48)); previous.setMinimumHeight(dp(48));
        previous.setMinimumWidth(0); previous.setMinWidth(0);
        previous.setPadding(dp(12), dp(8), dp(12), dp(8));
        previous.setStateListAnimator(null); previous.setElevation(0);
        previous.setBackground(null);
        previous.setOnClickListener(view -> { if (page > 0) showPage(page - 1, true); });
        controls.addView(previous, new LinearLayout.LayoutParams(-2, -2));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        controls.addView(replay, new LinearLayout.LayoutParams(-2, -2));
        footer.addView(action, new LinearLayout.LayoutParams(-1, -2));
        column.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(canvas);
        column.requestApplyInsets();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
        showPage(savedInstanceState == null ? 0 : savedInstanceState.getInt(PAGE_STATE, 0), false);
    }

    private static final int[] IMAGES = {
        R.drawable.permission_01_accessibility, R.drawable.permission_02_installed_apps,
        R.drawable.permission_03_service_off, R.drawable.permission_04_consent,
        R.drawable.permission_05_service_on
    };
    private static final int[] TITLES = {
        R.string.guide_card_apps_title, R.string.guide_card_click_title,
        R.string.guide_card_switch_title, R.string.guide_card_consent_title,
        R.string.guide_card_done_title
    };
    private static final int[] DESCRIPTIONS = {
        R.string.guide_card_apps_body, R.string.guide_card_click_body,
        R.string.guide_card_switch_body, R.string.guide_card_consent_body,
        R.string.guide_card_done_body
    };
    // Display the relevant part of each screenshot at readable size.
    private static final RectF[] CROPS = {
        new RectF(0, 1280, 1080, 2160), new RectF(0, 650, 1080, 1450),
        new RectF(0, 160, 1080, 960), new RectF(0, 720, 1080, 2250),
        new RectF(0, 160, 1080, 960)
    };
    private static final RectF[] TARGETS = {
        new RectF(24, 1680, 1056, 1890), new RectF(24, 1040, 1056, 1270),
        new RectF(870, 335, 1032, 455), new RectF(795, 2055, 990, 2190),
        new RectF(24, 328, 1056, 470)
    };

    private void showPage(int requestedPage, boolean announce) {
        page = Math.max(0, Math.min(PAGE_COUNT - 1, requestedPage));
        narrator.stop();
        body.removeAllViews();
        String description = getString(R.string.guide_progress_description, page + 1, PAGE_COUNT);
        View progress = findViewById(R.id.guide_progress_bars);
        progress.setContentDescription(description);
        backButton.setEnabled(page > 0);
        backButton.setAlpha(page > 0 ? 1f : .4f);
        for (int i = 0; i < progressBars.length; i++) {
            progressBars[i].setBackgroundResource(i <= page
                    ? R.drawable.onboarding_progress_active : R.drawable.onboarding_progress_inactive);
        }
        int actionText = page == PAGE_COUNT - 1 ? R.string.guide_settings_now : R.string.guide_card_next;
        actionLabel.setText(actionText);
        action.setContentDescription(getString(actionText));
        TextView title = title(TITLES[page], 21);
        title.setId(R.id.guide_card_title);
        add(body, title, 0);
        int bodyText = page == 2 && getIntent().getBooleanExtra(EXTRA_RECONNECT, false)
                ? R.string.guide_card_reconnect_body : DESCRIPTIONS[page];
        add(body, text(bodyText, 14, SECONDARY, regular), 10);

        LinearLayout card = vertical();
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable cardFill = rounded(0xFFF6F8FC, 20);
        cardFill.setStroke(dp(1), 0xFFE1E9F5);
        card.setBackground(cardFill);
        card.setClipToOutline(true);
        PermissionScreenshotView screenshot = new PermissionScreenshotView(
                this, IMAGES[page], CROPS[page], TARGETS[page]);
        screenshot.setId(R.id.guide_screenshot);
        screenshot.setContentDescription(getString(TITLES[page]));
        card.addView(screenshot, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout imageRegion = new FrameLayout(this);
        int photoWidth = Math.max(dp(120), Math.min(getResources().getDisplayMetrics().widthPixels, dp(480)) - dp(60));
        imageRegion.setMinimumHeight(Math.round(photoWidth * CROPS[page].height() / CROPS[page].width()) + dp(12));
        imageRegion.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, 0, 1);
        imageParams.topMargin = dp(24);
        body.addView(imageRegion, imageParams);
        spokenPage = getString(TITLES[page]) + ". " + getString(bodyText);
        narrator.speak(spokenPage);
        scroll.scrollTo(0, 0);
        scroll.post(() -> scroll.scrollTo(0, 0));
        if (announce) progress.announceForAccessibility(description);
    }

    /** Horizontal gestures turn cards; vertical gestures keep normal text/image scrolling. */
    private final class CardScrollView extends ScrollView {
        private float startX, startY;
        private boolean horizontal;
        CardScrollView() { super(PermissionGuideActivity.this); }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startX = event.getX(); startY = event.getY(); horizontal = false;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(event.getX() - startX), dy = Math.abs(event.getY() - startY);
                if (dx > dp(24) && dx > dy * 1.5f) { horizontal = true; return true; }
            }
            return super.onInterceptTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startX = event.getX(); startY = event.getY(); horizontal = false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && Math.abs(event.getX() - startX) > dp(24)
                    && Math.abs(event.getX() - startX) > Math.abs(event.getY() - startY) * 1.5f) horizontal = true;
            if (horizontal) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    float dx = event.getX() - startX;
                    if (Math.abs(dx) > dp(48)) {
                        int next = page + (dx < 0 ? 1 : -1);
                        if (next >= 0 && next < PAGE_COUNT) showPage(next, true);
                    }
                    horizontal = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) horizontal = false;
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private void openSettings(String settingAction) {
        try {
            if (Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(settingAction)) {
                settingsOpened = true;
                ClickHost.from(this).openAccessibilitySettings(this);
            } else startActivity(new Intent(settingAction));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.guide_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        narrator.resume();
        if (settingsOpened && !tutorialOpened) {
            connectionWatcher.start(this::onConnectionReady, () -> {});
        }
    }

    private void onConnectionReady() {
        if (settingsOpened && !tutorialOpened && ClickHost.from(this).accessibilityConnected()) {
            tutorialOpened = true;
            if (getIntent().getBooleanExtra(EXTRA_RETURN_TO_SETTINGS, false)) {
                ClickHost.from(this).returnToSettingsAfterAccessibility(this);
                finish();
                return;
            }
            if (!getIntent().getBooleanExtra(EXTRA_ONBOARDING, false)) {
                ClickHost.from(this).returnToHomeAfterAccessibility(this);
                finish();
                return;
            }
            ClickHost.from(this).openPostPermissionTutorial(this);
            finish();
        }
    }

    private TextView title(int string, int size) {
        TextView result = text(string, size, INK, semibold);
        result.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) result.setAccessibilityHeading(true);
        return result;
    }

    private TextView text(int string, int size, int color, Typeface typeface) {
        TextView result = new TextView(this);
        if (string != 0) result.setText(string);
        result.setTextSize(com.click.ui.common.ClickTextScale.size(this, size));
        result.setTextColor(color);
        result.setTypeface(typeface);
        result.setIncludeFontPadding(false);
        result.setLineSpacing(dp(3), 1.12f);
        return result;
    }

    private LinearLayout vertical() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void goBack() {
        if (page > 0) showPage(page - 1, true);
        else {
            if (getApplication() instanceof ClickHost) ((ClickHost) getApplication()).cancelAccessibilityReturn();
            finish();
        }
    }

    @Override public void onBackPressed() { goBack(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(PAGE_STATE, page);
        state.putBoolean(SETTINGS_OPENED_STATE, settingsOpened);
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() {
        connectionWatcher.stop();
        narrator.pause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        narrator.close(); super.onDestroy();
    }

    private void configureWindow() {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
    }
}
