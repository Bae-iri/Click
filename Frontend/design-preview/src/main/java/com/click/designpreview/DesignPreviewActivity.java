package com.click.designpreview;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.DecelerateInterpolator;

public final class DesignPreviewActivity extends Activity {
    private static final String REMAINING_TIME = "splash_remaining_time";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ForegroundCountdown countdown;
    private View splash;
    private boolean resumed;
    private boolean navigated;
    private final Runnable openOnboarding = () -> {
        if (!resumed || navigated || isFinishing() || isDestroyed()) return;
        navigated = true;
        if (ClickHost.from(this).signedIn()) {
            if (ClickHost.from(this).accessibilityEnabled()) ClickHost.from(this).openHome(this);
            else startActivity(new Intent(this, AccessibilityHelpActivity.class));
        } else startActivity(new Intent(this, OnboardingActivity.class));
        finish();
    };
    private final Runnable startCountdown = () -> {
        if (!resumed || navigated) return;
        long now = SystemClock.uptimeMillis();
        countdown.resume(now);
        handler.removeCallbacks(openOnboarding);
        handler.postDelayed(openOnboarding, countdown.remaining(now));
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        countdown = new ForegroundCountdown(savedInstanceState == null ? 2000L
                : savedInstanceState.getLong(REMAINING_TIME, 2000L));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(getColor(R.color.design_canvas_background));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        setContentView(R.layout.activity_design_preview);
        splash = findViewById(R.id.splash_screen);
        splash.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        splash.requestApplyInsets();

        if (savedInstanceState == null && ValueAnimator.areAnimatorsEnabled()) {
            View logo = findViewById(R.id.splash_logo);
            reveal(logo, 0L).start();
            reveal(findViewById(R.id.splash_tagline), 100L).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Posting to the attached view starts the timer after the initial layout.
        splash.post(startCountdown);
    }

    @Override
    protected void onPause() {
        resumed = false;
        splash.removeCallbacks(startCountdown);
        handler.removeCallbacks(openOnboarding);
        countdown.pause(SystemClock.uptimeMillis());
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putLong(REMAINING_TIME, countdown.remaining(SystemClock.uptimeMillis()));
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        splash.removeCallbacks(startCountdown);
        handler.removeCallbacks(openOnboarding);
        super.onDestroy();
    }

    private ViewPropertyAnimator reveal(View view, long delay) {
        view.setAlpha(0f);
        view.setTranslationY(8f * getResources().getDisplayMetrics().density);
        return view.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(480L)
                .setInterpolator(new DecelerateInterpolator());
    }
}
