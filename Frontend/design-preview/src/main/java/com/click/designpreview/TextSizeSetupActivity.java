package com.click.designpreview;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import com.click.ui.common.ClickTextScale;

/** Choose a readable app text size before the first onboarding page. */
public final class TextSizeSetupActivity extends Activity {
    private boolean openingOnboarding;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(getColor(R.color.account_surface));
        getWindow().setNavigationBarColor(getColor(R.color.account_surface));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(R.layout.activity_text_size_setup);
        View root = findViewById(R.id.text_size_setup_screen);
        root.setBackground(new ClickBackgroundDrawable(getResources()));
        int side = Math.round(24 * getResources().getDisplayMetrics().density);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(side + insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    side + insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        TextSizeControlView control = findViewById(R.id.text_size_setup_control);
        control.setOnSizeChanged(() -> ClickTextScale.applyTree(root));
        ClickTextScale.applyTree(root);
        findViewById(R.id.text_size_setup_back).setOnClickListener(view -> finish());
        View next = findViewById(R.id.text_size_setup_continue);
        next.setBackground(OnboardingDrawables.startButton(getResources()));
        next.setOnClickListener(view -> {
            if (openingOnboarding) return;
            openingOnboarding = true;
            next.setEnabled(false);
            startActivity(new Intent(this, OnboardingActivity.class));
        });
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
    }

    @Override protected void onResume() {
        super.onResume();
        openingOnboarding = false;
        findViewById(R.id.text_size_setup_continue).setEnabled(true);
    }
}
