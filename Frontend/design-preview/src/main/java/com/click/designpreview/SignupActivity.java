package com.click.designpreview;

import android.app.Activity;
import android.app.Dialog;
import android.widget.Toast;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/** Two short, high-legibility signup steps for the independent design preview. */
public final class SignupActivity extends Activity {
    private static final String PAGE_STATE = "signup_page";
    private static final String PHONE_STATE = "signup_email";
    private static final String NAME_STATE = "signup_name";

    private int page;
    private EditText phone;
    private EditText password;
    private EditText passwordConfirm;
    private EditText name;
    private String confirmedPhone;
    private boolean submitting;
    private ImageButton passwordToggle;
    private ImageButton passwordConfirmToggle;
    private boolean passwordVisible;
    private boolean passwordConfirmVisible;
    private boolean loadingPasswordRules;
    private boolean passwordRulesReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(R.layout.activity_sign_up);
        View root = findViewById(R.id.signup_screen);
        root.setBackground(new ClickBackgroundDrawable(getResources()));
        int side = dp(20);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(side + insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(), side + insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        findViewById(R.id.signup_scroll).addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        view.setClipBounds(new Rect(0, 0, right - left, bottom - top)));

        phone = findViewById(R.id.signup_email);
        password = findViewById(R.id.signup_password);
        passwordConfirm = findViewById(R.id.signup_password_confirm);
        name = findViewById(R.id.signup_name);
        passwordToggle = findViewById(R.id.signup_password_toggle);
        passwordConfirmToggle = findViewById(R.id.signup_password_confirm_toggle);
        password.setSaveEnabled(false);
        passwordConfirm.setSaveEnabled(false);
        setupPasswordToggle(password, passwordToggle, false);
        setupPasswordToggle(passwordConfirm, passwordConfirmToggle, false);
        setLineHeight(R.id.signup_account_title, 1.28f);
        setLineHeight(R.id.signup_account_description, 1.5f);
        setLineHeight(R.id.signup_profile_title, 1.28f);
        setLineHeight(R.id.signup_profile_description, 1.5f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            findViewById(R.id.signup_account_title).setAccessibilityHeading(true);
            findViewById(R.id.signup_profile_title).setAccessibilityHeading(true);
        }

        View action = findViewById(R.id.signup_action);
        action.setBackground(OnboardingDrawables.startButton(getResources()));
        addShadow(action, 2);
        action.setOnClickListener(view -> onAction());
        findViewById(R.id.signup_back).setOnClickListener(view -> goBack());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }

        if (savedInstanceState != null) {
            phone.setText(savedInstanceState.getString(PHONE_STATE, ""));
            name.setText(savedInstanceState.getString(NAME_STATE, ""));
            // Passwords are never written into saved state; re-enter the account step after recreation.
            page = 0;
        }
        showPage(page, false);
        loadPasswordRules();
    }

    private String phoneNumber() {
        return SignupInputRules.normalizePhone(phone.getText().toString());
    }

    private void loadPasswordRules() {
        if (loadingPasswordRules) return;
        loadingPasswordRules = true;
        passwordRulesReady = false;
        password.setHint(R.string.signup_password_hint);
        TextView helper = findViewById(R.id.signup_password_hint_text);
        helper.setText(R.string.signup_password_rules_loading);
        updateAction();
        ClickHost.from(this).loadRegistrationRules(this, new ClickHost.PasswordRulesCallback() {
            @Override public void complete(int minimum, int maximum) {
                if (isDestroyed()) return;
                loadingPasswordRules = false;
                passwordRulesReady = true;
                password.setHint(getString(R.string.signup_password_hint));
                helper.setText(getString(R.string.signup_password_helper));
                updateAction();
            }

            @Override public void failed() {
                if (isDestroyed()) return;
                loadingPasswordRules = false;
                helper.setText(R.string.signup_password_rules_failed);
                helper.announceForAccessibility(helper.getText());
                updateAction();
            }
        });
    }

    private void updateAction() {
        View action = findViewById(R.id.signup_action);
        action.setEnabled(!loadingPasswordRules && !submitting);
        int label = loadingPasswordRules ? R.string.signup_password_rules_loading
                : !passwordRulesReady ? R.string.signup_retry
                : R.string.signup_next;
        ((TextView) findViewById(R.id.signup_action_label)).setText(label);
        action.setContentDescription(getString(label));
    }

    private void onAction() {
        if (submitting) return;
        if (!passwordRulesReady) { loadPasswordRules(); return; }
        if (page == 0) {
            if (!validateAccount()) return;
            String address = phoneNumber();
            showPhoneConfirmation(address);
            return;
        }
        if (!validateAccount() || !phoneNumber().equals(confirmedPhone)) {
            showPage(0, true); return;
        }
        clearErrors(R.id.signup_name_error);
        if (!SignupInputRules.validName(name.getText().toString())) {
            showError(R.id.signup_name_error, R.string.signup_error_name, name);
            return;
        }
        submitting = true;
        findViewById(R.id.signup_action).setEnabled(false);
        ((TextView) findViewById(R.id.signup_action_label)).setText("가입하는 중이에요…");
        String address = confirmedPhone;
        ClickHost.from(this).register(this, name.getText().toString().trim(), address,
                password.getText().toString(),
                (success, duplicate, message) -> {
                    submitting = false;
                    findViewById(R.id.signup_action).setEnabled(true);
                    ((TextView) findViewById(R.id.signup_action_label)).setText(R.string.signup_next);
                    if (success) {
                        password.setText(""); passwordConfirm.setText("");
                        startActivity(new Intent(this, PermissionGuideActivity.class)); finish();
                    } else if (duplicate) {
                        showExistingAccount(address);
                    } else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
    }

    private void showPhoneConfirmation(String address) {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(phone.getWindowToken(), 0);
        View focused = getCurrentFocus();
        if (focused != null) focused.clearFocus();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_email_confirmation);
        dialog.findViewById(R.id.email_confirmation_scroll).setClipToOutline(true);
        dialog.setCanceledOnTouchOutside(true);
        ((TextView) dialog.findViewById(R.id.email_confirmation_address)).setText(address);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dialog.findViewById(R.id.email_confirmation_title).setAccessibilityHeading(true);
        }
        View confirm = dialog.findViewById(R.id.email_confirmation_continue);
        confirm.setBackground(OnboardingDrawables.startButton(getResources()));
        confirm.setOnClickListener(v -> { dialog.dismiss(); confirmedPhone = address; showPage(1, true); });
        dialog.findViewById(R.id.email_confirmation_edit).setOnClickListener(v -> {
            dialog.dismiss(); phone.requestFocus();
            if (keyboard != null) keyboard.showSoftInput(phone, InputMethodManager.SHOW_IMPLICIT); });
        dialog.findViewById(R.id.email_confirmation_close).setOnClickListener(v -> dialog.dismiss());

        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.38f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(400)),
                WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.CENTER);
    }

    private void showExistingAccount(String address) {
        AccountExistsDialog.show(this, address,
                () -> { showPage(0, true); phone.requestFocus(); },
                () -> {
                    startActivity(new Intent(this, LoginActivity.class).putExtra("phone_number", address));
                    finish();
                });
    }

    private boolean validateAccount() {
        if (!passwordRulesReady) return false;
        clearErrors(R.id.signup_email_error, R.id.signup_password_error,
                R.id.signup_password_confirm_error);
        String phoneValue = phoneNumber();
        String passwordValue = password.getText().toString();
        if (!SignupInputRules.validPhone(phoneValue)) {
            showError(R.id.signup_email_error, R.string.signup_error_email, phone);
            return false;
        }
        if (!SignupInputRules.validPin(passwordValue)) {
            showError(R.id.signup_password_error, R.string.signup_error_pin, password);
            return false;
        }
        if (!passwordValue.equals(passwordConfirm.getText().toString())) {
            showError(R.id.signup_password_confirm_error, R.string.signup_error_password_confirm,
                    passwordConfirm);
            return false;
        }
        return true;
    }

    private void showPage(int requestedPage, boolean changedByUser) {
        page = Math.max(0, Math.min(1, requestedPage));
        boolean accountPage = page == 0;
        findViewById(R.id.signup_account_content).setVisibility(accountPage ? View.VISIBLE : View.GONE);
        findViewById(R.id.signup_profile_content).setVisibility(accountPage ? View.GONE : View.VISIBLE);
        findViewById(R.id.signup_step_one).setBackgroundResource(
                R.drawable.onboarding_progress_active);
        findViewById(R.id.signup_step_two).setBackgroundResource(accountPage
                ? R.drawable.onboarding_progress_inactive : R.drawable.onboarding_progress_active);
        findViewById(R.id.signup_progress).setContentDescription(getString(
                R.string.signup_progress, page + 1));
        updateAction();
        if (!changedByUser) return;

        ScrollView scroll = findViewById(R.id.signup_scroll);
        scroll.scrollTo(0, 0);
        scroll.post(() -> scroll.scrollTo(0, 0));
        View body = findViewById(R.id.signup_body);
        body.animate().cancel();
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            body.setAlpha(.35f);
            body.setTranslationY(dp(6));
            body.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            body.setAlpha(1f);
            body.setTranslationY(0f);
        }
        TextView title = findViewById(accountPage ? R.id.signup_account_title : R.id.signup_profile_title);
        title.announceForAccessibility(title.getText());
    }

    private void setupPasswordToggle(EditText input, ImageButton toggle, boolean visible) {
        updatePasswordVisibility(input, toggle, visible);
        toggle.setOnClickListener(view -> {
            boolean next = input == password ? !passwordVisible : !passwordConfirmVisible;
            updatePasswordVisibility(input, toggle, next);
        });
    }

    private void updatePasswordVisibility(EditText input, ImageButton toggle, boolean visible) {
        int selection = input.getSelectionEnd();
        input.setTransformationMethod(visible ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        if (selection >= 0) input.setSelection(selection);
        if (input == password) passwordVisible = visible;
        else passwordConfirmVisible = visible;
        toggle.setImageResource(visible ? R.drawable.signup_visibility_off
                : R.drawable.signup_visibility_on);
        toggle.setContentDescription(getString(visible ? R.string.signup_hide_password
                : R.string.signup_show_password));
    }

    private void clearErrors(int... errorIds) {
        for (int errorId : errorIds) findViewById(errorId).setVisibility(View.GONE);
    }

    private void showError(int errorId, int messageId, EditText target) {
        TextView error = findViewById(errorId);
        error.setText(messageId);
        error.setVisibility(View.VISIBLE);
        target.requestFocus();
    }

    private void goBack() {
        if (page > 0) showPage(0, true);
        else finish();
    }

    @Override public void onBackPressed() {
        goBack();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused instanceof EditText) {
                Rect bounds = new Rect();
                focused.getGlobalVisibleRect(bounds);
                if (!bounds.contains((int) event.getRawX(), (int) event.getRawY())) {
                    focused.clearFocus();
                    InputMethodManager keyboard = (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE);
                    if (keyboard != null) {
                        keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(PAGE_STATE, page);
        outState.putString(PHONE_STATE, phone.getText().toString());
        outState.putString(NAME_STATE, name.getText().toString());
        super.onSaveInstanceState(outState);
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

    private void addShadow(View view, int elevation) {
        view.setElevation(dp(elevation));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setOutlineAmbientShadowColor(0x553E71C5);
            view.setOutlineSpotShadowColor(0x553E71C5);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
