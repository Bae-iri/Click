package com.click.designpreview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/** Login screen using the same typography, fields, and actions as signup. */
public final class LoginActivity extends Activity {
    private static final String PHONE_STATE = "login_email";
    private EditText phone;
    private EditText password;
    private ImageButton passwordToggle;
    private View action;
    private boolean passwordVisible;
    private boolean submitting;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        setContentView(R.layout.activity_login);

        View root = findViewById(R.id.login_screen);
        root.setBackground(new ClickBackgroundDrawable(getResources()));
        int side = dp(20);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(side + insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(), side + insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        findViewById(R.id.login_scroll).addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        view.setClipBounds(new Rect(0, 0, right - left, bottom - top)));

        phone = findViewById(R.id.login_email);
        password = findViewById(R.id.login_password);
        passwordToggle = findViewById(R.id.login_password_toggle);
        action = findViewById(R.id.login_action);
        password.setSaveEnabled(false);
        updatePasswordVisibility(false);

        String suppliedPhone = getIntent().getStringExtra("phone_number");
        phone.setText(state == null ? suppliedPhone : state.getString(PHONE_STATE, suppliedPhone));
        setLineHeight(R.id.login_title, 1.28f);
        setLineHeight(R.id.login_description, 1.5f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            findViewById(R.id.login_title).setAccessibilityHeading(true);
        }

        action.setBackground(OnboardingDrawables.startButton(getResources()));
        action.setElevation(dp(2));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            action.setOutlineAmbientShadowColor(0x243D5D8F);
            action.setOutlineSpotShadowColor(0x303D5D8F);
        }
        action.setContentDescription(getString(R.string.login_action));
        action.setOnClickListener(view -> login());
        findViewById(R.id.login_kakao).setOnClickListener(view -> loginWithKakao());
        password.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            login(); return true;
        });
        passwordToggle.setOnClickListener(view -> updatePasswordVisibility(!passwordVisible));
        findViewById(R.id.login_back).setOnClickListener(view -> finish());
        findViewById(R.id.login_signup).setBackgroundResource(R.drawable.account_email_button_background);
        ((TextView) findViewById(R.id.login_signup)).setTextColor(getColor(R.color.account_blue));
        findViewById(R.id.login_signup).setOnClickListener(view -> {
            if (!submitting) startActivity(new Intent(this, SignupActivity.class));
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
        }
    }

    private void login() {
        if (submitting) return;
        clearError(R.id.login_email_error); clearError(R.id.login_password_error);
        clearError(R.id.login_api_error);
        String address = SignupInputRules.normalizePhone(phone.getText().toString());
        String secret = password.getText().toString();
        if (!SignupInputRules.validPhone(address)) {
            showError(R.id.login_email_error, getString(R.string.signup_error_email), phone); return;
        }
        if (!SignupInputRules.validPin(secret)) {
            showError(R.id.login_password_error, getString(R.string.login_error_password_required), password); return;
        }
        setSubmitting(true);
        ((TextView) findViewById(R.id.login_action_label)).setText("로그인하는 중이에요…");
        ClickHost.from(this).login(this, address, secret, (success, duplicate, message) -> {
            completeLogin(success, message);
        });
    }

    private void loginWithKakao() {
        if (submitting) return;
        clearError(R.id.login_api_error);
        setSubmitting(true);
        ((TextView) findViewById(R.id.login_kakao_label)).setText(R.string.login_kakao_progress);
        ClickHost.from(this).loginWithKakao(this,
                (success, duplicate, message) -> completeLogin(success, message));
    }

    private void completeLogin(boolean success, String message) {
        if (isFinishing() || isDestroyed()) return;
        setSubmitting(false);
        if (!success) { showError(R.id.login_api_error, message, null); return; }
        password.setText("");
        if (ClickHost.from(this).accessibilityEnabled()) ClickHost.from(this).openHome(this);
        else { startActivity(new Intent(this, PermissionGuideActivity.class)); finish(); }
    }

    // Both login methods share one in-flight request; switching cannot create duplicate sessions.
    private void setSubmitting(boolean value) {
        submitting = value;
        for (int id : new int[]{R.id.login_action, R.id.login_kakao, R.id.login_signup}) {
            View button = findViewById(id);
            button.setEnabled(!value);
            button.setAlpha(value ? .62f : 1f);
        }
        if (!value) {
            ((TextView) findViewById(R.id.login_action_label)).setText(R.string.login_action);
            ((TextView) findViewById(R.id.login_kakao_label)).setText(R.string.login_kakao);
        }
    }

    private void updatePasswordVisibility(boolean visible) {
        int selection = password.getSelectionEnd();
        password.setTransformationMethod(visible ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        if (selection >= 0) password.setSelection(selection);
        passwordVisible = visible;
        passwordToggle.setImageResource(visible ? R.drawable.signup_visibility_off
                : R.drawable.signup_visibility_on);
        passwordToggle.setContentDescription(getString(visible ? R.string.signup_hide_password
                : R.string.signup_show_password));
    }

    private void clearError(int id) { findViewById(id).setVisibility(View.GONE); }
    private void showError(int id, String message, EditText target) {
        TextView error = findViewById(id); error.setText(message); error.setVisibility(View.VISIBLE);
        error.announceForAccessibility(message);
        if (target != null) target.requestFocus();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused instanceof EditText) {
                Rect bounds = new Rect(); focused.getGlobalVisibleRect(bounds);
                if (!bounds.contains((int) event.getRawX(), (int) event.getRawY())) {
                    focused.clearFocus();
                    InputMethodManager keyboard = (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE);
                    if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(PHONE_STATE, phone.getText().toString());
        super.onSaveInstanceState(state);
    }

    private void setLineHeight(int id, float multiplier) {
        TextView text = findViewById(id);
        text.setLineSpacing(Math.max(0f,
                text.getTextSize() * multiplier - text.getPaint().getFontSpacing()), 1f);
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
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
