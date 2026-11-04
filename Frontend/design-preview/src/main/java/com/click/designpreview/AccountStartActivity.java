package com.click.designpreview;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/** A single entry point for both Kakao registration and returning-user login. */
public final class AccountStartActivity extends Activity {
    private boolean submitting;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(getColor(R.color.account_surface));
        getWindow().setNavigationBarColor(getColor(R.color.account_surface));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(R.layout.activity_account_start);
        findViewById(R.id.account_start_screen).setBackground(new ClickBackgroundDrawable(getResources()));
        findViewById(R.id.account_start_email).setBackground(OnboardingDrawables.startButton(getResources()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            findViewById(R.id.account_start_title).setAccessibilityHeading(true);
        }
        findViewById(R.id.account_start_back).setOnClickListener(view -> finish());
        findViewById(R.id.account_start_kakao).setOnClickListener(view -> loginWithKakao());
        findViewById(R.id.account_start_email).setOnClickListener(view -> {
            if (!submitting) startActivity(new Intent(this, SignupActivity.class));
        });
        findViewById(R.id.account_start_login).setOnClickListener(view -> {
            if (!submitting) startActivity(new Intent(this, LoginActivity.class));
        });
    }

    private void loginWithKakao() {
        if (submitting) return;
        setSubmitting(true);
        TextView error = findViewById(R.id.account_start_error);
        error.setVisibility(View.GONE);
        ClickHost.from(this).loginWithKakao(this, (success, duplicate, message) -> {
            if (isFinishing() || isDestroyed()) return;
            setSubmitting(false);
            if (!success) {
                error.setText(message);
                error.setVisibility(View.VISIBLE);
                error.announceForAccessibility(message);
                return;
            }
            if (ClickHost.from(this).accessibilityEnabled()) ClickHost.from(this).openHome(this);
            else {
                startActivity(new Intent(this, PermissionGuideActivity.class));
                finish();
            }
        });
    }

    private void setSubmitting(boolean value) {
        submitting = value;
        for (int id : new int[]{R.id.account_start_kakao,
                R.id.account_start_email, R.id.account_start_login}) {
            findViewById(id).setEnabled(!value);
            findViewById(id).setAlpha(value ? .6f : 1f);
        }
        findViewById(R.id.account_start_progress).setVisibility(value ? View.VISIBLE : View.GONE);
    }
}
