package com.click.designpreview;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.Toast;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Patterns;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/** Two short, high-legibility signup steps for the independent design preview. */
public final class SignupActivity extends Activity {
    private static final String PAGE_STATE = "signup_page";
    private static final String EMAIL_STATE = "signup_email";
    private static final String NAME_STATE = "signup_name";
    private static final String AGE_STATE = "signup_age";
    private static final String OCCUPATION_STATE = "signup_occupation";

    private int page;
    private EditText email;
    private EditText password;
    private EditText passwordConfirm;
    private EditText name;
    private EditText age;
    private TextView occupation;
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
        password = findViewById(R.id.signup_password);
        passwordConfirm = findViewById(R.id.signup_password_confirm);
        name = findViewById(R.id.signup_name);
        age = findViewById(R.id.signup_age);
        occupation = findViewById(R.id.signup_occupation);
        passwordToggle = findViewById(R.id.signup_password_toggle);
        passwordConfirmToggle = findViewById(R.id.signup_password_confirm_toggle);
        refreshOccupation();
        occupation.setOnClickListener(v -> showOccupations());
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
            String address = email.getText().toString().trim();
            new AlertDialog.Builder(this)
                    .setTitle("이메일을 한 번 더 확인해 주세요")
                    .setMessage(address + "\n\n휴대폰을 바꾸거나 다시 로그인할 때 사용할 이메일이에요. Click 계정 안내를 받을 수 있도록, 자주 사용하는 주소인지 확인해 주세요.")
                    .setNegativeButton("수정할게요", (dialog, which) -> email.requestFocus())
                    .setPositiveButton("네, 이 이메일이 맞아요", (dialog, which) -> {
                        confirmedEmail = address;
                        showPage(1, true);
                    }).show();
            return;
        }
        if (!validateAccount() || !email.getText().toString().trim().equals(confirmedEmail)) {
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
        occupation.setText((occupationIndex == 0 ? "선택하지 않아도 괜찮아요" : values[occupationIndex]) + "   ⌄");
        occupation.setContentDescription("직업: " + values[occupationIndex] + ", 눌러서 선택");
    }

    private void showOccupations() {
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = new LinearLayout(this); sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(24), dp(26), dp(24), dp(20));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(28));
        sheet.setBackground(background);

        View handle = new View(this);
        GradientDrawable handleBackground = new GradientDrawable();
        handleBackground.setColor(0xFFD9E2F2); handleBackground.setCornerRadius(dp(3));
        handle.setBackground(handleBackground);
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL; handleParams.bottomMargin = dp(22);
        sheet.addView(handle, handleParams);

        TextView title = AccountUi.text(this, getString(R.string.signup_occupation_title), 25);
        title.setTypeface(getResources().getFont(R.font.pretendard_semibold));
        title.setIncludeFontPadding(false);
        title.setLetterSpacing(-.025f);
        title.setLineSpacing(0f, 1.22f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) title.setAccessibilityHeading(true);
        AccountUi.add(this, sheet, title, 0);

        TextView description = AccountUi.text(this,
                getString(R.string.signup_occupation_description), 17);
        description.setTypeface(getResources().getFont(R.font.pretendard_medium));
        description.setIncludeFontPadding(false);
        description.setLetterSpacing(-.012f);
        description.setTextColor(0xFF647594);
        description.setLineSpacing(dp(3), 1.18f);
        AccountUi.add(this, sheet, description, 12);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(14), 0, dp(6));
        RadioGroup choices = new RadioGroup(this); choices.setOrientation(RadioGroup.VERTICAL);
        choices.setPadding(dp(4), dp(4), dp(4), dp(2));
        String[] values = getResources().getStringArray(R.array.signup_occupations);
        for (int i = 0; i < values.length; i++) {
            RadioButton option = new RadioButton(this); option.setId(View.generateViewId()); option.setTag(i);
            option.setText(values[i]); option.setTextSize(19); option.setTextColor(AccountUi.INK);
            option.setTypeface(getResources().getFont(R.font.pretendard_medium));
            option.setIncludeFontPadding(false);
            option.setLetterSpacing(-.01f);
            option.setButtonTintList(new ColorStateList(new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked}},
                    new int[]{AccountUi.BLUE, 0xFF8FA1BC}));
            // Keep the selection circle away from the sheet edge and give the label breathing room.
            option.setPadding(dp(24), dp(13), dp(18), dp(13)); option.setMinHeight(dp(64));
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(-1, -2);
            params.topMargin = dp(9); params.leftMargin = dp(2); params.rightMargin = dp(2);
            choices.addView(option, params);
            option.setOnCheckedChangeListener((button, checked) -> {
                GradientDrawable card = new GradientDrawable(); card.setCornerRadius(dp(16));
                card.setColor(checked ? 0xFFEEF4FF : 0xFFF7F9FC);
                card.setStroke(dp(checked ? 2 : 1), checked ? AccountUi.BLUE : 0xFFE2E8F0);
                button.setTypeface(getResources().getFont(checked
                        ? R.font.pretendard_semibold : R.font.pretendard_medium));
                button.setBackground(card);
            });
            GradientDrawable card = new GradientDrawable(); card.setCornerRadius(dp(16)); card.setColor(0xFFF7F9FC);
            option.setBackground(card); if (i == occupationIndex) choices.check(option.getId());
        }
        scroll.addView(choices); sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button done = AccountUi.button(this, "선택 완료", true);
        done.setOnClickListener(v -> { View selected = choices.findViewById(choices.getCheckedRadioButtonId());
            if (selected != null) occupationIndex = (Integer) selected.getTag(); refreshOccupation(); dialog.dismiss(); });
        AccountUi.add(this, sheet, done, 16);
        Button cancel = AccountUi.button(this, "돌아가기", false); cancel.setOnClickListener(v -> dialog.dismiss());
        AccountUi.add(this, sheet, cancel, 8);
        dialog.setContentView(sheet); dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setGravity(Gravity.BOTTOM); dialog.show();
        dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(480)),
                (int) (getResources().getDisplayMetrics().heightPixels * .80));
    }

    private boolean validateAccount() {
        if (!passwordRulesReady) return false;
        clearErrors(R.id.signup_email_error, R.id.signup_password_error,
                R.id.signup_password_confirm_error);
        String emailValue = email.getText().toString().trim();
        String passwordValue = password.getText().toString();
        if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
            showError(R.id.signup_email_error, R.string.signup_error_email, email);
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
        outState.putString(EMAIL_STATE, email.getText().toString());
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
