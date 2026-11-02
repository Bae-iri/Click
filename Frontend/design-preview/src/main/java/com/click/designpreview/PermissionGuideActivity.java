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

/** A five-page explanation shown before the user chooses whether to open system settings. */
public final class PermissionGuideActivity extends Activity {
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
    private Typeface bold;
    private boolean settingsOpened;
    private boolean tutorialOpened;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        narrator = new TutorialNarrator(this);
        settingsOpened = savedInstanceState != null
                && savedInstanceState.getBoolean(SETTINGS_OPENED_STATE, false);
        medium = getResources().getFont(R.font.pretendard_medium);
        semibold = getResources().getFont(R.font.pretendard_semibold);
        bold = getResources().getFont(R.font.pretendard_bold);
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
        TextView label = text(R.string.guide_name, 14, SECONDARY, medium);
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
            if (page < 4) showPage(page + 1, true);
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
        page = Math.max(0, Math.min(4, requestedPage));
        body.animate().cancel();
        body.removeAllViews();
        counter.setText(getString(R.string.guide_progress, page + 1));
        counter.setContentDescription(getString(R.string.guide_progress_description, page + 1));
        int actionText = page == 0 ? R.string.onboarding_start
                : page == 4 ? R.string.guide_settings_now : R.string.onboarding_next;
        actionLabel.setText(actionText);
        action.setContentDescription(getString(actionText));
        later.setVisibility(page == 1 || page == 4 ? View.VISIBLE : View.GONE);
        switch (page) {
            case 0: intro(); break;
            case 1: reason(); break;
            case 2: features(); break;
            case 3: settingsSteps(); break;
            case 4: allowStep(); break;
        }
        String[] cues = {
            "어려운 스마트폰 사용, 이제 클릭 한 번으로. 다음을 눌러 권한 안내를 확인해 주세요.",
            "click은 접근성 기능으로 화면의 정보를 이해하고 다음에 누를 곳을 안내해요. 권한은 직접 선택하고 설정에서 끌 수 있어요.",
            "화면에서 필요한 버튼을 찾고, 확인이 필요한 순간을 안내해요. 다음을 눌러 설정 방법을 확인해 주세요.",
            "접근성 설정 열기를 누르면 휴대폰 설정으로 이동해요. 설치된 앱에서 click 화면 안내를 찾아주세요.",
            "설치된 앱, click 화면 안내, 허용하기 순서로 눌러주세요. 지금 설정하기 버튼으로 이동할 수 있어요."
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
        add(body, text(R.string.guide_reason_description, 19, SECONDARY, medium), 20);
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

    private void features() {
        add(body, title(R.string.guide_features_title, 30), 0);
        add(body, text(R.string.guide_features_description, 18, SECONDARY, medium), 18);
        add(body, feature(Kind.SEARCH, R.string.guide_find_title, R.string.guide_find_description), 22);
        add(body, feature(Kind.ALERT, R.string.guide_alert_title, R.string.guide_alert_description), 12);
        add(body, feature(Kind.CONTROL, R.string.guide_control_title, R.string.guide_control_description), 12);
    }

    private void settingsSteps() {
        add(body, title(R.string.guide_settings_title, 28), 0);
        add(body, step(1, R.string.guide_step_one, R.string.guide_step_one_description), 26);
        LinearLayout settings = vertical();
        settings.setId(R.id.guide_settings_shortcut);
        settings.setGravity(Gravity.CENTER_HORIZONTAL);
        settings.setPadding(dp(18), dp(6), dp(18), dp(6));
        settings.setBackground(new RippleDrawable(ColorStateList.valueOf(0x161673FF), null,
                rounded(Color.WHITE, 20)));
        settings.setContentDescription(getString(R.string.guide_open_settings_description));
        settings.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        settings.setFocusable(true);
        settings.setOnClickListener(view -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        PermissionIconView gear = new PermissionIconView(this, Kind.SETTINGS, 0xFF4B607E);
        settings.addView(gear, new LinearLayout.LayoutParams(dp(84), dp(84)));
        TextView settingsLabel = text(R.string.guide_accessibility_shortcut, 18, BLUE, semibold);
        settingsLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        add(settings, settingsLabel, 4);
        settingsLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-2, -2);
        settingsParams.gravity = Gravity.CENTER_HORIZONTAL;
        settingsParams.topMargin = dp(12);
        body.addView(settings, settingsParams);
        TextView destination = text(R.string.guide_settings_destination, 16, SECONDARY, medium);
        destination.setGravity(Gravity.CENTER);
        add(body, destination, 12);
        add(body, step(2, R.string.guide_step_two, R.string.guide_step_two_description), 22);
        add(body, phone(false, false), 18);
    }

    private void allowStep() {
        add(body, title(R.string.guide_allow_title, 30), 0);
        add(body, step(3, R.string.guide_step_three, R.string.guide_step_three_description), 24);
        add(body, phone(true, false), 24);
        LinearLayout ready = vertical();
        ready.setGravity(Gravity.CENTER_HORIZONTAL);
        ready.setPadding(dp(16), dp(12), dp(16), dp(16));
        ready.setBackground(rounded(0xFFF1F7FF, 22));
        ready.addView(new PermissionIconView(this, Kind.CHECK, BLUE),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView note = text(R.string.guide_ready, 17, SECONDARY, medium);
        note.setGravity(Gravity.CENTER);
        add(ready, note, 6);
        add(body, ready, 18);
        TextView deviceNote = text(R.string.guide_device_note, 13, SECONDARY, medium);
        deviceNote.setGravity(Gravity.CENTER);
        add(body, deviceNote, 12);
    }

    private View feature(Kind icon, int label, int example) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(17), dp(14), dp(17));
        card.setMinimumHeight(dp(94));
        card.setBackground(rounded(0xFFF1F7FF, 24));
        card.addView(new PermissionIconView(this, icon, BLUE),
                new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout copy = vertical();
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.setMarginStart(dp(14));
        card.addView(copy, copyParams);
        add(copy, text(label, 17, INK, semibold), 0);
        add(copy, text(example, 14, SECONDARY, medium), 6);
        return card;
    }

    private View step(int number, int label, int description) {
        LinearLayout row = new LinearLayout(this);
        TextView badge = text(0, 21, Color.WHITE, semibold);
        badge.setText(getString(R.string.guide_step_number, number));
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(BLUE, 22));
        int badgeSize = Math.max(dp(40), badge.getLineHeight() + dp(8));
        row.addView(badge, new LinearLayout.LayoutParams(badgeSize, badgeSize));
        LinearLayout copy = vertical();
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.setMarginStart(dp(13));
        row.addView(copy, copyParams);
        add(copy, text(label, 19, INK, semibold), 2);
        add(copy, text(description, 14, SECONDARY, medium), 7);
        return row;
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
        TextView result = text(string, size, INK, bold);
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

    @Override protected void onPause() { narrator.pause(); super.onPause(); }
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
