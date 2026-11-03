package com.click.designpreview;

import android.app.Activity;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/** Welcome, two feature introductions, and a ready screen in the independent design preview. */
public final class OnboardingActivity extends Activity {
    private static final String PAGE_STATE = "onboarding_page";
    private static final int[] TITLES = {
            R.string.onboarding_title, R.string.onboarding_question_title,
            R.string.onboarding_task_title, R.string.onboarding_ready_title
    };
    private static final int[] DESCRIPTIONS = {
            R.string.onboarding_description, R.string.onboarding_question_description,
            0, R.string.onboarding_ready_description
    };
    private int page;
    private final OnboardingDrawables.Check[] checks = new OnboardingDrawables.Check[3];
    private final Runnable startChecks = this::startChecklistIfVisible;
    private AnimatorSet checkAnimation;
    private boolean checklistPending;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(R.layout.activity_onboarding);
        View root = findViewById(R.id.onboarding_screen);
        int side = dp(20);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(side + insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(), side + insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        // Decorative shadows may extend outside a scene, but never over the fixed controls.
        findViewById(R.id.onboarding_scroll).addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        view.setClipBounds(new Rect(0, 0, right - left, bottom - top)));

        setLineHeight(R.id.onboarding_title, 1.28f);
        setLineHeight(R.id.onboarding_description, 1.5f);
        setLineHeight(R.id.onboarding_bubble, 1.35f);
        setLineHeight(R.id.onboarding_feature_bubble, 1.4f);
        setLineHeight(R.id.onboarding_quote, 1.45f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            findViewById(R.id.onboarding_title).setAccessibilityHeading(true);
        }
        View bubble = findViewById(R.id.onboarding_bubble);
        bubble.setBackground(new OnboardingDrawables.Bubble(getResources()));
        addShadow(bubble, 4);
        findViewById(R.id.onboarding_quote).setBackground(new OnboardingDrawables.Bubble(
                getResources(), getColor(R.color.onboarding_blue_light), .78f, 28f));
        ((OnboardingArtworkView) findViewById(R.id.onboarding_ready_logo)).setScene(3);
        int[] checkLabels = {R.id.onboarding_check_explain, R.id.onboarding_check_find,
                R.id.onboarding_check_prevent};
        for (int i = 0; i < checks.length; i++) {
            checks[i] = new OnboardingDrawables.Check(getResources());
            ((TextView) findViewById(checkLabels[i])).setCompoundDrawablesRelativeWithIntrinsicBounds(
                    checks[i], null, null, null);
        }
        findViewById(R.id.onboarding_checklist).addOnLayoutChangeListener(
                (view, l, t, r, b, oldL, oldT, oldR, oldB) -> startChecklistIfVisible());
        findViewById(R.id.onboarding_scroll).getViewTreeObserver()
                .addOnScrollChangedListener(this::startChecklistIfVisible);
        View start = findViewById(R.id.onboarding_start);
        start.setBackground(OnboardingDrawables.startButton(getResources()));
        addShadow(start, 8);
        start.setOnClickListener(view -> {
            if (page < 2) showPage(page + 1, true);
            else startActivity(new Intent(this, AccountStartActivity.class));
        });
        findViewById(R.id.onboarding_skip).setOnClickListener(view -> showPage(2, true));
        findViewById(R.id.guide_entry).setOnClickListener(view ->
                startActivity(new Intent(this, AccountStartActivity.class)));
        findViewById(R.id.onboarding_back).setOnClickListener(view -> goBack());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
        showPage(savedInstanceState == null ? 0 : savedInstanceState.getInt(PAGE_STATE, 0), false);
    }

    private void showPage(int requestedPage, boolean changedByUser) {
        finishChecklistAnimation();
        page = Math.max(0, Math.min(2, requestedPage));
        boolean welcome = page == 0;
        boolean feature = page == 1 || page == 2;
        TextView title = findViewById(R.id.onboarding_title);
        TextView description = findViewById(R.id.onboarding_description);
        title.setText(TITLES[page]);
        title.setGravity(welcome ? Gravity.START : Gravity.CENTER);
        description.setGravity(welcome ? Gravity.START : Gravity.CENTER);
        int textInset = welcome ? dp(16) : 0;
        title.setPadding(textInset, 0, textInset, 0);
        description.setPadding(textInset, 0, textInset, 0);
        description.setVisibility(DESCRIPTIONS[page] == 0 ? View.GONE : View.VISIBLE);
        if (DESCRIPTIONS[page] != 0) description.setText(DESCRIPTIONS[page]);
        visible(R.id.onboarding_quote, page == 2);
        visible(R.id.onboarding_welcome_scene, welcome);
        visible(R.id.onboarding_feature_scene, feature);
        visible(R.id.onboarding_ready_scene, false);
        visible(R.id.onboarding_skip, welcome);
        visible(R.id.onboarding_back, !welcome);
        visible(R.id.onboarding_start_arrow, page != 2);
        visible(R.id.guide_entry, page == 2);
        if (feature) {
            ((OnboardingFeatureLayout) findViewById(R.id.onboarding_feature_scene)).setTask(page == 2);
            TextView bubble = findViewById(R.id.onboarding_feature_bubble);
            bubble.setText(page == 1 ? R.string.onboarding_question_bubble : R.string.onboarding_task_bubble);
            bubble.setTextColor(page == 1 ? getColor(R.color.onboarding_bubble_text) : Color.WHITE);
            bubble.setBackground(new OnboardingDrawables.Bubble(getResources(),
                    page == 1 ? Color.WHITE : getColor(R.color.onboarding_blue), .48f, 32f));
            addShadow(bubble, page == 1 ? 4 : 6);
        }
        int[] bars = {R.id.onboarding_step_one, R.id.onboarding_step_two, R.id.onboarding_step_three};
        int completed = page + 1;
        for (int i = 0; i < bars.length; i++) {
            findViewById(bars[i]).setBackgroundResource(i < completed
                    ? R.drawable.onboarding_progress_active : R.drawable.onboarding_progress_inactive);
        }
        findViewById(R.id.onboarding_progress).setContentDescription(welcome
                ? getString(R.string.onboarding_welcome_progress)
                : getString(R.string.onboarding_step_progress, page + 1));
        int actionText = page == 2 ? R.string.onboarding_signup : welcome ? R.string.onboarding_start : R.string.onboarding_next;
        ((TextView) findViewById(R.id.onboarding_start_label)).setText(actionText);
        View start = findViewById(R.id.onboarding_start);
        start.setContentDescription(getString(actionText));
        if (changedByUser) {
            ScrollView scroll = findViewById(R.id.onboarding_scroll);
            scroll.scrollTo(0, 0);
            scroll.post(() -> scroll.scrollTo(0, 0));
            View body = findViewById(R.id.onboarding_body);
            body.animate().cancel();
            if (ValueAnimator.areAnimatorsEnabled()) {
                body.setAlpha(.35f);
                body.setTranslationY(dp(6));
                body.animate().alpha(1f).translationY(0f).setDuration(180).start();
            } else {
                body.setAlpha(1f);
                body.setTranslationY(0f);
            }
            title.announceForAccessibility(title.getText());
        }
        if (page == 3) prepareChecklistAnimation();
    }

    private void prepareChecklistAnimation() {
        finishChecklistAnimation();
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        checklistPending = true;
        for (OnboardingDrawables.Check check : checks) check.setProgress(0f);
        findViewById(R.id.onboarding_checklist).post(startChecks);
    }

    private void startChecklistIfVisible() {
        if (!checklistPending || !resumed || !hasWindowFocus() || page != 3) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishChecklistAnimation();
            return;
        }
        View first = findViewById(R.id.onboarding_check_explain);
        Rect visible = new Rect();
        // On short screens, wait until the user scrolls the checklist into view.
        if (!first.getGlobalVisibleRect(visible) || visible.height() < first.getHeight() / 2) return;
        checklistPending = false;
        ValueAnimator[] sequence = new ValueAnimator[checks.length];
        for (int i = 0; i < checks.length; i++) {
            OnboardingDrawables.Check check = checks[i];
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(480L);
            animator.setStartDelay(i == 0 ? 0L : 220L);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(value -> check.setProgress((Float) value.getAnimatedValue()));
            sequence[i] = animator;
        }
        checkAnimation = new AnimatorSet();
        checkAnimation.playSequentially(sequence);
        // Let the page settle before drawing checks so the sequence is easy to notice.
        checkAnimation.setStartDelay(450L);
        checkAnimation.start();
    }

    private void finishChecklistAnimation() {
        checklistPending = false;
        findViewById(R.id.onboarding_checklist).removeCallbacks(startChecks);
        if (checkAnimation != null) {
            checkAnimation.cancel();
            checkAnimation = null;
        }
        for (OnboardingDrawables.Check check : checks) {
            if (check != null) check.setProgress(1f);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (page == 3) prepareChecklistAnimation();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (page != 3) return;
        if (hasFocus) {
            findViewById(R.id.onboarding_checklist).post(startChecks);
        } else if (resumed) {
            // If another window covers the page mid-animation, replay when it is visible again.
            prepareChecklistAnimation();
        }
    }

    @Override protected void onPause() {
        resumed = false;
        finishChecklistAnimation();
        super.onPause();
    }

    @Override protected void onDestroy() {
        finishChecklistAnimation();
        super.onDestroy();
    }

    private void goBack() {
        if (page > 0) showPage(page - 1, true);
        else finish();
    }

    @Override public void onBackPressed() {
        goBack();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(PAGE_STATE, page);
        super.onSaveInstanceState(outState);
    }

    private void visible(int id, boolean show) {
        findViewById(id).setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void addShadow(View view, int elevation) {
        view.setElevation(dp(elevation));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setOutlineAmbientShadowColor(0x553E71C5);
            view.setOutlineSpotShadowColor(0x553E71C5);
        }
    }

    private void configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getWindow().setNavigationBarContrastEnforced(false);
    }

    private void setLineHeight(int id, float multiplier) {
        TextView text = findViewById(id);
        text.setLineSpacing(Math.max(0f,
                text.getTextSize() * multiplier - text.getPaint().getFontSpacing()), 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
