package com.click.designpreview;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.Toast;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Patterns;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/** Two short, high-legibility signup steps for the independent design preview. */
public final class SignupActivity extends Activity {
    private static final String PAGE_STATE = "signup_page";
    private static final String EMAIL_STATE = "signup_email";
    private static final String EMAIL_DOMAIN_STATE = "signup_email_domain";
    private static final String EMAIL_CUSTOM_DOMAIN_STATE = "signup_email_custom_domain";
    private static final String NAME_STATE = "signup_name";
    private static final String AGE_STATE = "signup_age";
    private static final String OCCUPATION_STATE = "signup_occupation";

    private int page;
    private EditText email;
    private Button emailDomain;
    private EditText emailCustomDomain;
    private int emailDomainIndex = -1;
    private EditText password;
    private EditText passwordConfirm;
    private EditText name;
    private EditText age;
    private TextView occupation;
    private RadioGroup occupationOptions;
    private boolean occupationExpanded;
    private ValueAnimator occupationAnimator;
    private int occupationIndex;
    private String confirmedEmail;
    private boolean submitting;
    private ImageButton passwordToggle;
    private ImageButton passwordConfirmToggle;
    private boolean passwordVisible;
    private boolean passwordConfirmVisible;
    private int minimumPasswordLength;
    private int maximumPasswordLength;
    private boolean loadingPasswordRules;
    private boolean passwordRulesReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(R.layout.activity_sign_up);
        View root = findViewById(R.id.signup_screen);
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

        email = findViewById(R.id.signup_email);
        emailDomain = findViewById(R.id.signup_email_domain);
        emailCustomDomain = findViewById(R.id.signup_email_custom_domain);
        setupEmailDomain();
        password = findViewById(R.id.signup_password);
        passwordConfirm = findViewById(R.id.signup_password_confirm);
        name = findViewById(R.id.signup_name);
        age = findViewById(R.id.signup_age);
        occupation = findViewById(R.id.signup_occupation);
        occupationOptions = findViewById(R.id.signup_occupation_options);
        passwordToggle = findViewById(R.id.signup_password_toggle);
        passwordConfirmToggle = findViewById(R.id.signup_password_confirm_toggle);
        setupOccupations();
        occupation.setOnClickListener(v -> setOccupationExpanded(!occupationExpanded, true));
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
        addShadow(action, 8);
        action.setOnClickListener(view -> onAction());
        findViewById(R.id.signup_back).setOnClickListener(view -> goBack());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }

        if (savedInstanceState != null) {
            email.setText(savedInstanceState.getString(EMAIL_STATE, ""));
            emailDomainIndex = savedInstanceState.getInt(EMAIL_DOMAIN_STATE, -1);
            emailCustomDomain.setText(savedInstanceState.getString(EMAIL_CUSTOM_DOMAIN_STATE, ""));
            refreshEmailDomain();
            name.setText(savedInstanceState.getString(NAME_STATE, ""));
            age.setText(savedInstanceState.getString(AGE_STATE, ""));
            occupationIndex = savedInstanceState.getInt(OCCUPATION_STATE, 0);
            refreshOccupation();
            // Passwords are never written into saved state; re-enter the account step after recreation.
            page = 0;
        }
        showPage(page, false);
        loadPasswordRules();
    }

    private void setupEmailDomain() {
        refreshEmailDomain();
        emailDomain.setOnClickListener(view -> {
            PopupMenu menu = new PopupMenu(this, emailDomain);
            String[] domains = getResources().getStringArray(R.array.signup_email_domains);
            for (int i = 0; i < domains.length; i++) menu.getMenu().add(0, i, i, domains[i]);
            menu.setOnMenuItemClickListener(item -> {
                emailDomainIndex = item.getItemId();
                refreshEmailDomain();
                clearErrors(R.id.signup_email_error);
                if (isCustomEmailDomain()) focusCustomEmailDomain();
                return true;
            });
            menu.show();
        });
        // Keep full-address paste and email autofill working with the split fields.
        email.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                String address = text.toString().trim();
                int separator = address.indexOf('@');
                if (separator < 0) return;
                String domain = address.substring(separator + 1);
                String[] domains = getResources().getStringArray(R.array.signup_email_domains);
                emailDomainIndex = domains.length - 1;
                for (int i = 0; i < domains.length - 1; i++) {
                    if (domains[i].equalsIgnoreCase(domain)) { emailDomainIndex = i; break; }
                }
                emailCustomDomain.setText(isCustomEmailDomain() ? domain : "");
                refreshEmailDomain();
                text.replace(0, text.length(), address.substring(0, separator));
                email.setSelection(email.length());
                if (domain.isEmpty() && email.hasFocus()) focusCustomEmailDomain();
            }
        });
    }

    private boolean isCustomEmailDomain() {
        return emailDomainIndex == getResources().getStringArray(R.array.signup_email_domains).length - 1;
    }

    private void refreshEmailDomain() {
        String[] domains = getResources().getStringArray(R.array.signup_email_domains);
        if (emailDomainIndex < -1 || emailDomainIndex >= domains.length) emailDomainIndex = -1;
        String label = emailDomainIndex < 0 ? getString(R.string.signup_email_domain_select)
                : domains[emailDomainIndex];
        emailDomain.setText(label);
        emailDomain.setContentDescription(getString(R.string.signup_email_domain_accessibility, label));
        emailCustomDomain.setVisibility(isCustomEmailDomain() ? View.VISIBLE : View.GONE);
        email.setNextFocusForwardId(isCustomEmailDomain() ? R.id.signup_email_custom_domain
                : emailDomainIndex < 0 ? R.id.signup_email_domain : R.id.signup_password);
    }

    private void focusCustomEmailDomain() {
        emailCustomDomain.requestFocus();
        emailCustomDomain.setSelection(emailCustomDomain.length());
        emailCustomDomain.post(() -> {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.showSoftInput(emailCustomDomain, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private String emailAddress() {
        String domain = emailDomainIndex < 0 ? "" : isCustomEmailDomain()
                ? emailCustomDomain.getText().toString().trim()
                : getResources().getStringArray(R.array.signup_email_domains)[emailDomainIndex];
        return email.getText().toString().trim() + "@" + domain;
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
                minimumPasswordLength = minimum;
                maximumPasswordLength = maximum;
                password.setHint(getString(R.string.signup_password_hint_length, minimum));
                helper.setText(getString(R.string.signup_password_helper_length, minimum));
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
                : page == 0 ? R.string.signup_next : R.string.signup_review;
        ((TextView) findViewById(R.id.signup_action_label)).setText(label);
        action.setContentDescription(getString(label));
    }

    private void onAction() {
        if (submitting) return;
        if (!passwordRulesReady) { loadPasswordRules(); return; }
        if (page == 0) {
            if (!validateAccount()) return;
            String address = emailAddress();
            showEmailConfirmation(address);
            return;
        }
        if (!validateAccount() || !emailAddress().equals(confirmedEmail)) {
            showPage(0, true); return;
        }
        if (!validateProfile()) return;
        submitting = true;
        findViewById(R.id.signup_action).setEnabled(false);
        ((TextView) findViewById(R.id.signup_action_label)).setText("가입하는 중이에요…");
        String address = confirmedEmail;
        String ageValue = age.getText().toString().trim();
        Integer selectedAge = ageValue.isEmpty() ? null : Integer.parseInt(ageValue);
        String job = occupationIndex == 0 ? null : getResources().getStringArray(R.array.signup_occupations)[occupationIndex];
        ClickHost.from(this).register(this, name.getText().toString().trim(), address,
                password.getText().toString(),
                selectedAge, job, (success, duplicate, message) -> {
                    submitting = false;
                    findViewById(R.id.signup_action).setEnabled(true);
                    ((TextView) findViewById(R.id.signup_action_label)).setText(R.string.signup_review);
                    if (success) {
                        password.setText(""); passwordConfirm.setText("");
                        startActivity(new Intent(this, PermissionGuideActivity.class)); finish();
                    } else if (duplicate) {
                        showExistingAccount(address);
                    } else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
    }

    private void showEmailConfirmation(String address) {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(email.getWindowToken(), 0);
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
        confirm.setOnClickListener(v -> { dialog.dismiss(); confirmedEmail = address; showPage(1, true); });
        dialog.findViewById(R.id.email_confirmation_edit).setOnClickListener(v -> {
            dialog.dismiss(); email.requestFocus();
            if (keyboard != null) keyboard.showSoftInput(email, InputMethodManager.SHOW_IMPLICIT); });
        dialog.findViewById(R.id.email_confirmation_close).setOnClickListener(v -> dialog.dismiss());

        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.38f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(400)),
                WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.CENTER);
    }

    private void showExistingAccount(String address) {
        AccountExistsDialog.show(this, address,
                () -> { showPage(0, true); email.requestFocus(); },
                () -> {
                    startActivity(new Intent(this, LoginActivity.class).putExtra("email", address));
                    finish();
                });
    }

    private void refreshOccupation() {
        String[] values = getResources().getStringArray(R.array.signup_occupations);
        occupationIndex = Math.max(0, Math.min(values.length - 1, occupationIndex));
        occupation.setText(occupationIndex == 0 ? getString(R.string.signup_occupation_hint)
                : values[occupationIndex]);
        occupation.setActivated(occupationExpanded);
        occupation.setContentDescription(getString(occupationExpanded
                ? R.string.signup_occupation_collapse : R.string.signup_occupation_expand,
                values[occupationIndex]));
        occupationOptions.check(occupationOptions.getChildAt(occupationIndex).getId());
    }

    private void setupOccupations() {
        String[] values = getResources().getStringArray(R.array.signup_occupations);
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setSaveEnabled(false);
            option.setText(values[i]); option.setTextSize(19); option.setTextColor(AccountUi.INK);
            option.setTypeface(getResources().getFont(R.font.pretendard_medium));
            option.setIncludeFontPadding(false);
            option.setLetterSpacing(-.01f);
            option.setButtonDrawable(new android.graphics.drawable.InsetDrawable(
                    new AccountSelectionMark(getResources().getDisplayMetrics().density), dp(12), 0, dp(12), 0));
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(0, dp(14), dp(12), dp(14)); option.setMinHeight(dp(60));
            option.setBackgroundResource(R.drawable.signup_occupation_option);
            option.setOnCheckedChangeListener((button, checked) -> button.setTypeface(
                    getResources().getFont(checked ? R.font.pretendard_semibold : R.font.pretendard_medium)));
            option.setOnClickListener(view -> {
                occupationIndex = index;
                setOccupationExpanded(false, true);
                occupation.requestFocus();
                occupation.announceForAccessibility(values[index]);
            });
            occupationOptions.addView(option, new RadioGroup.LayoutParams(-1, -2));
        }
        refreshOccupation();
    }

    private void setOccupationExpanded(boolean expanded, boolean animate) {
        ViewGroup profile = findViewById(R.id.signup_profile_content);
        if (occupationAnimator != null) {
            occupationAnimator.removeAllListeners();
            occupationAnimator.cancel();
            occupationAnimator = null;
        }
        int startHeight = occupationOptions.getVisibility() == View.VISIBLE ? occupationOptions.getHeight() : 0;
        occupationExpanded = expanded;
        refreshOccupation();
        if (expanded) {
            View focused = getCurrentFocus();
            if (focused != null && focused != occupation) focused.clearFocus();
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(occupation.getWindowToken(), 0);
            occupation.requestFocus();
        }
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || profile.getWidth() == 0) {
            occupationOptions.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            occupationOptions.setVisibility(expanded ? View.VISIBLE : View.GONE);
            occupationOptions.requestLayout();
            if (expanded) occupationOptions.post(this::scrollToOccupation);
            return;
        }
        occupationOptions.measure(View.MeasureSpec.makeMeasureSpec(profile.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int endHeight = expanded ? occupationOptions.getMeasuredHeight() : 0;
        occupationOptions.getLayoutParams().height = startHeight;
        occupationOptions.setVisibility(View.VISIBLE);
        occupationAnimator = ValueAnimator.ofInt(startHeight, endHeight);
        occupationAnimator.setDuration(200);
        occupationAnimator.addUpdateListener(animation -> {
            occupationOptions.getLayoutParams().height = (Integer) animation.getAnimatedValue();
            occupationOptions.requestLayout();
        });
        occupationAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                occupationOptions.setVisibility(occupationExpanded ? View.VISIBLE : View.GONE);
                occupationOptions.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                occupationOptions.requestLayout();
                occupationAnimator = null;
                if (occupationExpanded) occupationOptions.post(SignupActivity.this::scrollToOccupation);
            }
        });
        occupationAnimator.start();
    }

    private void scrollToOccupation() {
        if (!occupationExpanded) return;
        View profile = findViewById(R.id.signup_profile_content);
        View label = findViewById(R.id.signup_occupation_label);
        ScrollView scroll = findViewById(R.id.signup_scroll);
        scroll.smoothScrollTo(0, profile.getTop() + label.getTop() - dp(12));
    }

    private boolean validateAccount() {
        if (!passwordRulesReady) return false;
        clearErrors(R.id.signup_email_error, R.id.signup_password_error,
                R.id.signup_password_confirm_error);
        String emailValue = emailAddress();
        String passwordValue = password.getText().toString();
        if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
            showError(R.id.signup_email_error, R.string.signup_error_email, email);
            if (!email.getText().toString().trim().isEmpty()) {
                if (emailDomainIndex < 0) emailDomain.requestFocus();
                else if (isCustomEmailDomain()) emailCustomDomain.requestFocus();
            }
            return false;
        }
        int passwordLength = passwordValue.codePointCount(0, passwordValue.length());
        if (passwordLength < minimumPasswordLength || passwordLength > maximumPasswordLength) {
            TextView error = findViewById(R.id.signup_password_error);
            error.setText(getString(passwordLength < minimumPasswordLength
                    ? R.string.signup_error_password_minimum : R.string.signup_error_password_maximum,
                    passwordLength < minimumPasswordLength ? minimumPasswordLength : maximumPasswordLength));
            error.setVisibility(View.VISIBLE);
            password.requestFocus();
            return false;
        }
        if (!passwordValue.equals(passwordConfirm.getText().toString())) {
            showError(R.id.signup_password_confirm_error, R.string.signup_error_password_confirm,
                    passwordConfirm);
            return false;
        }
        return true;
    }

    private boolean validateProfile() {
        clearErrors(R.id.signup_name_error, R.id.signup_age_error);
        if (name.getText().toString().trim().isEmpty()) {
            showError(R.id.signup_name_error, R.string.signup_error_name, name);
            return false;
        }
        String ageValue = age.getText().toString().trim();
        if (ageValue.isEmpty()) return true;
        try {
            int numericAge = Integer.parseInt(ageValue);
            if (numericAge < 1 || numericAge > 120) throw new NumberFormatException();
        } catch (NumberFormatException ignored) {
            showError(R.id.signup_age_error, R.string.signup_error_age, age);
            return false;
        }
        return true;
    }

    private void showPage(int requestedPage, boolean changedByUser) {
        page = Math.max(0, Math.min(1, requestedPage));
        boolean accountPage = page == 0;
        if (accountPage && occupationExpanded) setOccupationExpanded(false, false);
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
        if (occupationExpanded) {
            setOccupationExpanded(false, true);
            occupation.requestFocus();
        } else if (page > 0) showPage(0, true);
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
        outState.putString(EMAIL_STATE, email.getText().toString());
        outState.putInt(EMAIL_DOMAIN_STATE, emailDomainIndex);
        outState.putString(EMAIL_CUSTOM_DOMAIN_STATE, emailCustomDomain.getText().toString());
        outState.putString(NAME_STATE, name.getText().toString());
        outState.putString(AGE_STATE, age.getText().toString());
        outState.putInt(OCCUPATION_STATE, occupationIndex);
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
