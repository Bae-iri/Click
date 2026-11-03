package com.click.designpreview;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
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

/** A four-page explanation shown before the user chooses whether to open system settings. */
public final class PermissionGuideActivity extends Activity {
    private static final int PAGE_COUNT = 4;
    private static final String PAGE_STATE = "permission_guide_page";
    private static final String SETTINGS_OPENED_STATE = "permission_settings_opened";
    private static final int BLUE = 0xFF3478F6;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF6F7F9E;
    private int page;
    private TutorialNarrator narrator;
    private String spokenPage;
    private LinearLayout body;
    private ScrollView scroll;
    private TextView counter;
    private TextView actionLabel;
    private OnboardingStartButton action;
    private Button later;
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
        ImageButton back = new ImageButton(this);
        back.setId(R.id.guide_back);
        back.setImageResource(R.drawable.onboarding_back);
        back.setContentDescription(getString(R.string.onboarding_previous));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x161673FF), null,
                rounded(Color.WHITE, 24)));
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setOnClickListener(view -> goBack());
        header.addView(back, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START));
        TextView label = text(R.string.guide_name, 14, SECONDARY, regular);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(-1, -1);
        nameParams.setMargins(dp(54), 0, dp(66), 0);
        header.addView(label, nameParams);
        counter = text(0, 14, BLUE, semibold);
        counter.setId(R.id.guide_step_counter);
        counter.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        header.addView(counter, new FrameLayout.LayoutParams(dp(62), -1, Gravity.END));
        column.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));

        scroll = new ScrollView(this);
        scroll.setId(R.id.guide_scroll);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        body = vertical();
        body.setPadding(dp(2), dp(22), dp(2), dp(14));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = vertical();
        footer.setBackgroundColor(Color.WHITE);
        footer.setPadding(0, dp(12), 0, dp(14));
        action = new OnboardingStartButton(this, null);
        action.setId(R.id.guide_action);
        action.setGravity(Gravity.CENTER);
        action.setOrientation(LinearLayout.HORIZONTAL);
        action.setMinimumHeight(dp(66));
        action.setPadding(dp(20), dp(16), dp(20), dp(16));
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
        actionLabel = text(0, 22, Color.WHITE, semibold);
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
        Button replay = new Button(this); replay.setText("안내 다시 듣기"); replay.setAllCaps(false);
        replay.setTypeface(medium); replay.setTextSize(15); replay.setTextColor(BLUE);
        replay.setBackgroundColor(Color.TRANSPARENT); replay.setMinimumHeight(dp(48));
        replay.setOnClickListener(v -> { if (narrator.isSpeaking()) narrator.stop(); else narrator.replay(spokenPage); });
        narrator.setListener((speaking, unavailable) -> replay.setText(speaking ? "음성 멈춤" : "안내 다시 듣기"));
        footer.addView(replay, new LinearLayout.LayoutParams(-1, -2));
        footer.addView(action, new LinearLayout.LayoutParams(-1, -2));
        later = new Button(this);
        later.setId(R.id.guide_later_action);
        later.setText(R.string.guide_later);
        later.setTextColor(BLUE);
        later.setTypeface(medium);
        later.setTextSize(16);
        later.setAllCaps(false);
        later.setBackgroundColor(Color.TRANSPARENT);
        later.setPaintFlags(later.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        later.setMinimumHeight(dp(48));
        later.setOnClickListener(view -> startActivity(new Intent(this, AccessibilityHelpActivity.class)));
        LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(-1, -2);
        laterParams.topMargin = dp(4);
        footer.addView(later, laterParams);
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
        counter.setContentDescription(getString(R.string.guide_progress_description, page + 1, PAGE_COUNT));
        int actionText = page == 0 ? R.string.onboarding_start
                : page == PAGE_COUNT - 1 ? R.string.guide_settings_now : R.string.onboarding_next;
        actionLabel.setText(actionText);
        action.setContentDescription(getString(actionText));
        later.setVisibility(page == 1 || page == PAGE_COUNT - 1 ? View.VISIBLE : View.GONE);
        switch (page) {
            case 0: intro(); break;
            case 1: reason(); break;
            case 2: allowStep(); break;
            case 3: consentStep(); break;
        }
        String[] cues = {
            "어려운 스마트폰 사용, 이제 클릭 한 번으로. 다음을 눌러 권한 안내를 확인해 주세요.",
            "클릭은 접근성 기능으로 화면의 정보를 이해하고 다음에 누를 곳을 안내해요. 권한은 직접 선택하고 설정에서 끌 수 있어요.",
            "접근성 설정에서 설치된 앱을 누르고, 클릭 화면 안내를 선택해 주세요. 사용 안 함을 사용함으로 켜주세요. 다음 페이지에서 마지막 확인창을 보여드릴게요.",
            getString(R.string.guide_consent_cue)
        };
        spokenPage = cues[page]; narrator.speak(spokenPage);
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
            counter.announceForAccessibility(counter.getContentDescription());
        }
    }

    private void intro() {
        ClickBrandView logo = new ClickBrandView(this, null);
        logo.setContentDescription(getString(R.string.splash_logo_description));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                Math.min(dp(222), getResources().getDisplayMetrics().widthPixels - dp(64)), dp(72));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.topMargin = dp(10);
        body.addView(logo, logoParams);
        TextView title = title(R.string.guide_intro, 23);
        title.setGravity(Gravity.CENTER);
        add(body, title, 18);
        PermissionTouchArtworkView art = new PermissionTouchArtworkView(this);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(-1, dp(290));
        artParams.topMargin = dp(20);
        artParams.weight = 1;
        body.addView(art, artParams);
    }

    private void reason() {
        add(body, title(R.string.guide_reason_title, 29), 0);
        TextView accent = title(R.string.guide_reason_accent, 28);
        accent.setTextColor(BLUE);
        add(body, accent, 4);
        add(body, text(R.string.guide_reason_description, 19, SECONDARY, regular), 20);
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
        add(body, scene, 34);
    }

    private void allowStep() {
        add(body, title(R.string.guide_allow_title, 30), 0);
        add(body, text(R.string.guide_enable_description, 18, SECONDARY, regular), 18);
        enableAnimation = new PermissionEnableAnimationView(this);
        enableAnimation.setId(R.id.guide_enable_animation);
        add(body, enableAnimation, 24);
        enableAnimation.setPlaying(true);
        TextView deviceNote = text(R.string.guide_device_note, 13, SECONDARY, regular);
        deviceNote.setGravity(Gravity.CENTER);
        add(body, deviceNote, 14);
    }

    private void consentStep() {
        add(body, title(R.string.guide_consent_title, 29), 0);
        add(body, text(R.string.guide_consent_description, 17, SECONDARY, regular), 18);

        LinearLayout example = vertical();
        example.setId(R.id.guide_consent_example);
        example.setPadding(dp(16), dp(16), dp(16), dp(16));
        example.setBackground(rounded(0xFFF0F5FF, 24));
        TextView caption = text(R.string.guide_enable_example, 12, SECONDARY, regular);
        add(example, caption, 0);
        LinearLayout dialog = vertical();
        dialog.setPadding(dp(16), dp(18), dp(16), dp(12));
        dialog.setBackground(rounded(Color.WHITE, 20));
        add(dialog, text(R.string.guide_consent_dialog_title, 19, INK, semibold), 0);
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
        add(body, text(R.string.guide_consent_purpose, 16, INK, medium), 20);
        add(body, text(R.string.guide_consent_check, 16, BLUE, semibold), 12);
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
        if (settingsOpened && !tutorialOpened && ClickHost.from(this).accessibilityEnabled()) {
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
