package com.click.designpreview;

import android.animation.ValueAnimator;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import static com.click.designpreview.PermissionIconView.Kind;

/** A three-page explanation shown before the user chooses whether to open system settings. */
public final class PermissionGuideActivity extends Activity {
    public static final String EXTRA_RECONNECT = "accessibility_reconnect";
    private static final int PAGE_COUNT = 3;
    private static final String PAGE_STATE = "permission_guide_page_v2";
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
    private TextView counter;
    private View[] progressBars;
    private TextView actionLabel;
    private OnboardingStartButton action;
    private Button replay;
    private Typeface medium;
    private Typeface semibold;
    private Typeface regular;
    private boolean settingsOpened;
    private boolean tutorialOpened;
    private PermissionEnableAnimationView enableAnimation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        FrameLayout header = new FrameLayout(this);
        header.setBackgroundColor(Color.WHITE);
        LinearLayout progress = new LinearLayout(this);
        progress.setId(R.id.guide_progress_bars);
        progress.setOrientation(LinearLayout.HORIZONTAL);
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        progressParams.topMargin = dp(4);
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

        ImageButton back = new ImageButton(this);
        backButton = back;
        back.setId(R.id.guide_back);
        back.setImageResource(R.drawable.onboarding_back);
        back.setContentDescription(getString(R.string.onboarding_previous));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x161673FF), null,
                rounded(Color.WHITE, 24)));
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setOnClickListener(view -> goBack());
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.BOTTOM | Gravity.START);
        header.addView(back, backParams);

        // Keep the old counter value available to existing accessibility instrumentation;
        // the visible page indicator is the three-bar onboarding treatment above.
        counter = text(0, 14, BLUE, semibold);
        counter.setId(R.id.guide_step_counter);
        counter.setVisibility(View.GONE);
        counter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(counter, new FrameLayout.LayoutParams(1, 1, Gravity.END));
        column.addView(header, new LinearLayout.LayoutParams(-1, dp(60)));

        scroll = new ScrollView(this);
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
        action.setElevation(dp(5));
        if (Build.VERSION.SDK_INT >= 28) {
            action.setOutlineAmbientShadowColor(0xFF76ADFF);
            action.setOutlineSpotShadowColor(0xFF76ADFF);
        }
        actionLabel = text(0, 17, Color.WHITE, semibold);
        actionLabel.setGravity(Gravity.CENTER);
        actionLabel.setMaxWidth(Math.max(dp(80), width - dp(118)));
        actionLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.addView(actionLabel, new LinearLayout.LayoutParams(-2, -2));
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
        replay = new Button(this); replay.setText("안내 다시 듣기"); replay.setAllCaps(false);
        replay.setTag("permission_replay");
        replay.setTypeface(medium); replay.setTextSize(13); replay.setTextColor(BLUE);
        replay.setSingleLine(true);
        replay.setMinWidth(0); replay.setMinimumWidth(0);
        replay.setMinHeight(dp(48)); replay.setMinimumHeight(dp(48));
        replay.setPadding(dp(12), dp(8), dp(12), dp(8));
        replay.setBackground(new RippleDrawable(ColorStateList.valueOf(0x183478F6),
                rounded(0xFFF1F6FF, 16), null));
        replay.setStateListAnimator(null); replay.setElevation(0);
        header.addView(replay, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM));
        replay.setOnClickListener(v -> { if (narrator.isSpeaking()) narrator.stop(); else narrator.replay(spokenPage); });
        narrator.setListener((speaking, unavailable) -> replay.setText(speaking ? "음성 멈춤" : "안내 다시 듣기"));
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

    private void showPage(int requestedPage, boolean animate) {
        page = Math.max(0, Math.min(PAGE_COUNT - 1, requestedPage));
        if (enableAnimation != null) enableAnimation.setPlaying(false);
        enableAnimation = null;
        body.animate().cancel();
        body.removeAllViews();
        counter.setText(getString(R.string.guide_progress, page + 1, PAGE_COUNT));
        String description = getString(R.string.guide_progress_description, page + 1, PAGE_COUNT);
        View progress = findViewById(R.id.guide_progress_bars);
        progress.setContentDescription(description);
        backButton.setVisibility(page == 0 ? View.INVISIBLE : View.VISIBLE);
        for (int i = 0; i < progressBars.length; i++) {
            progressBars[i].setBackgroundResource(i <= page
                    ? R.drawable.onboarding_progress_active : R.drawable.onboarding_progress_inactive);
        }
        int actionText = page == PAGE_COUNT - 1 ? R.string.guide_settings_now : R.string.onboarding_next;
        actionLabel.setText(actionText);
        action.setContentDescription(getString(actionText));
        switch (page) {
            case 0: reason(); break;
            case 1: allowStep(); break;
            case 2: consentStep(); break;
        }
        String[] cues = {
            "클릭은 접근성 기능으로 화면의 정보를 이해하고 다음에 누를 곳을 안내해요. 권한은 직접 선택하고 설정에서 끌 수 있어요.",
            "접근성 설정에서 설치된 앱을 누르고, 클릭 화면 안내를 선택해 주세요. 사용 안 함을 사용함으로 켜주세요. 다음 페이지에서 마지막 확인창을 보여드릴게요.",
            getString(R.string.guide_consent_cue)
        };
        spokenPage = page == 1 && getIntent().getBooleanExtra(EXTRA_RECONNECT, false)
                ? getString(R.string.guide_reconnect_description) : cues[page];
        narrator.speak(spokenPage);
        scroll.scrollTo(0, 0);
        scroll.post(() -> scroll.scrollTo(0, 0));
        body.setAlpha(1f);
        body.setTranslationY(0);
        if (animate) {
            if (ValueAnimator.areAnimatorsEnabled()) {
                body.setAlpha(.2f);
                body.setTranslationY(dp(8));
                body.animate().alpha(1f).translationY(0).setDuration(200).start();
            }
            progress.announceForAccessibility(description);
        }
    }

    private void addTitle(int resource, int size) {
        add(body, title(resource, size), 0);
    }

    private void reason() {
        addTitle(R.string.guide_reason_title, 24);
        TextView accent = title(R.string.guide_reason_accent, 23);
        accent.setTextColor(BLUE);
        add(body, accent, 4);
        add(body, text(R.string.guide_reason_description, 15, SECONDARY, regular), 20);
        FrameLayout scene = new FrameLayout(this);
        scene.setPadding(dp(8), dp(14), dp(8), dp(16));
        scene.setBackground(rounded(0xFFF1F7FF, 140));
        View phone = phone(false, true);
        phone.setRotation(-6);
        FrameLayout.LayoutParams phoneParams = new FrameLayout.LayoutParams(-1, -2);
        phoneParams.setMargins(dp(28), 0, dp(28), 0);
        scene.addView(phone, phoneParams);
        LinearLayout highlight = phoneRow(R.string.guide_accessibility, Kind.ACCESSIBILITY,
                BLUE, true, false);
        highlight.setElevation(dp(8));
        FrameLayout.LayoutParams highlightParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        scene.addView(highlight, highlightParams);
        add(body, scene, 24);
    }

    private void allowStep() {
        addTitle(R.string.guide_allow_title, 24);
        add(body, text(getIntent().getBooleanExtra(EXTRA_RECONNECT, false)
                ? R.string.guide_reconnect_description : R.string.guide_enable_description, 15, SECONDARY, regular), 18);
        enableAnimation = new PermissionEnableAnimationView(this);
        enableAnimation.setId(R.id.guide_enable_animation);
        add(body, enableAnimation, 24);
        enableAnimation.setPlaying(true);
        TextView deviceNote = text(R.string.guide_device_note, 13, SECONDARY, regular);
        deviceNote.setGravity(Gravity.CENTER);
        add(body, deviceNote, 14);
    }

    private void consentStep() {
        addTitle(R.string.guide_consent_title, 24);
        add(body, text(R.string.guide_consent_description, 15, SECONDARY, regular), 18);

        LinearLayout example = vertical();
        example.setId(R.id.guide_consent_example);
        example.setPadding(dp(16), dp(16), dp(16), dp(16));
        example.setBackground(rounded(0xFFF0F5FF, 24));
        TextView caption = text(R.string.guide_enable_example, 12, SECONDARY, regular);
        add(example, caption, 0);
        LinearLayout dialog = vertical();
        dialog.setPadding(dp(16), dp(18), dp(16), dp(12));
        dialog.setBackground(rounded(Color.WHITE, 20));
        add(dialog, text(R.string.guide_consent_dialog_title, 17, INK, semibold), 0);
        add(dialog, text(R.string.guide_consent_dialog_body, 14, SECONDARY, regular), 12);
        LinearLayout choices = new LinearLayout(this);
        choices.setGravity(Gravity.CENTER_VERTICAL);
        TextView cancel = text(R.string.guide_consent_cancel, 16, SECONDARY, regular);
        cancel.setGravity(Gravity.CENTER); cancel.setMinHeight(dp(44));
        choices.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView allow = text(R.string.guide_consent_allow, 16, BLUE, semibold);
        allow.setGravity(Gravity.CENTER); allow.setMinHeight(dp(44));
        allow.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable highlight = rounded(0xFFEAF2FF, 14);
        highlight.setStroke(dp(2), BLUE);
        allow.setBackground(highlight);
        choices.addView(allow, new LinearLayout.LayoutParams(0, -2, 1));
        add(dialog, choices, 16);
        add(example, dialog, 10);
        // This is an example; only the system's own confirmation grants permission.
        example.setContentDescription(getString(R.string.guide_enable_example) + ". "
                + getString(R.string.guide_consent_dialog_title) + ". "
                + getString(R.string.guide_consent_dialog_body) + " 허용을 눌러주세요.");
        example.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dialog.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        add(body, example, 22);
        add(body, text(R.string.guide_consent_check, 15, BLUE, semibold), 12);
        add(body, text(R.string.guide_consent_revoke, 14, SECONDARY, regular), 12);
    }

    private View phone(boolean accessList, boolean muted) {
        return new PermissionPhoneView(this, accessList, muted);
    }

    private LinearLayout phoneRow(int label, Kind icon, int color, boolean selected, boolean toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setMinimumHeight(dp(53));
        GradientDrawable background = rounded(Color.WHITE, 14);
        if (selected) background.setStroke(dp(1), BLUE);
        row.setBackground(background);
        row.addView(new PermissionIconView(this, icon, color),
                new LinearLayout.LayoutParams(dp(35), dp(35)));
        TextView text = text(label, selected ? 17 : 13, INK, selected ? semibold : medium);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(8));
        row.addView(text, labelParams);
        if (toggle) {
            FrameLayout track = new FrameLayout(this);
            track.setBackground(rounded(BLUE, 16));
            View thumb = new View(this);
            thumb.setBackground(rounded(Color.WHITE, 13));
            FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(dp(22), dp(22),
                    Gravity.END | Gravity.CENTER_VERTICAL);
            thumbParams.rightMargin = dp(3);
            track.addView(thumb, thumbParams);
            row.addView(track, new LinearLayout.LayoutParams(dp(47), dp(28)));
        } else if (selected) {
            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.onboarding_back);
            chevron.setImageTintList(ColorStateList.valueOf(BLUE));
            chevron.setRotation(180);
            row.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        return row;
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
        if (enableAnimation != null) enableAnimation.setPlaying(true);
        if (settingsOpened && !tutorialOpened && ClickHost.from(this).accessibilityConnected()) {
            if (ClickHost.from(this).postPermissionTutorialWasLaunched()) {
                finish();
                return;
            }
            tutorialOpened = true;
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
        result.setTextSize(size);
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
        else finish();
    }

    @Override public void onBackPressed() { goBack(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(PAGE_STATE, page);
        state.putBoolean(SETTINGS_OPENED_STATE, settingsOpened);
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() {
        if (enableAnimation != null) enableAnimation.setPlaying(false);
        narrator.pause();
        super.onPause();
    }
    @Override protected void onDestroy() { narrator.close(); super.onDestroy(); }

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
