package com.click;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.click.core.permission.PermissionStatus;
import com.click.data.local.AuthTokenStore;
import com.click.data.local.LocalAccountStore;
import com.click.data.remote.PersonalizationEventGateway;
import com.click.service.overlay.ClickOverlayService;

/** Account and settings menu using the same soft-blue visual system as the Click home screen. */
public final class MenuActivity extends AppCompatActivity {
    private static final int BLUE = 0xFF3478F6;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF7182A4;
    private static final int BORDER = 0xFFE5EDF8;
    private static final int DANGER = 0xFFE94467;
    private static final int GRANTED = 0xFF1E9E55;
    private static final String[] JOBS = {
            "직업을 선택해주세요", "IT/소프트웨어", "제조업", "서비스업", "사무/관리",
            "자영업", "교육/연구", "의료/보건", "공무원", "주부", "학생", "무직", "기타"
    };
    private static final String[] GENDERS = {"성별을 선택해주세요", "남성", "여성"};

    private Typeface medium;
    private Typeface semibold;
    private Typeface bold;
    private TextView profileName;
    private LocalAccountStore account;
    private PersonalizationEventGateway personalizationEvents;
    private boolean timingConsented;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        medium = getResources().getFont(com.click.designpreview.R.font.pretendard_medium);
        semibold = getResources().getFont(com.click.designpreview.R.font.pretendard_semibold);
        bold = getResources().getFont(com.click.designpreview.R.font.pretendard_bold);
        account = new LocalAccountStore(this);
        personalizationEvents = new PersonalizationEventGateway(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.addView(new MenuBackgroundView(), new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = vertical();
        int horizontal = dp(22);
        page.setPadding(horizontal, dp(14), horizontal, dp(34));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(520)), -1,
                Gravity.CENTER_HORIZONTAL));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            page.setPadding(horizontal, dp(14) + insets.getSystemWindowInsetTop(), horizontal,
                    dp(34) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        page.addView(header(), new LinearLayout.LayoutParams(-1, dp(58)));
        add(page, profileHeader(), 20);

        LinearLayout settings = group();
        addRow(settings, MenuIconView.Kind.SHIELD, "권한 상태 확인", false,
                view -> showPermissionStatus());
        divider(settings);
        addRow(settings, MenuIconView.Kind.SLIDERS, "맞춤 안내 기록 설정", false,
                view -> showTimingConsentDialog());
        divider(settings);
        settings.addView(touchAutomationRow());
        add(page, settings, 22);

        LinearLayout tutorial = group();
        addRow(tutorial, MenuIconView.Kind.BOOK, "튜토리얼", false,
                view -> startActivity(new Intent(this, TutorialActivity.class)));
        add(page, tutorial, 14);

        LinearLayout policies = group();
        addRow(policies, MenuIconView.Kind.DOCUMENT, "서비스 이용약관", false,
                view -> showPolicy("서비스 이용약관"));
        divider(policies);
        addRow(policies, MenuIconView.Kind.SHIELD, "개인정보 처리방침", false,
                view -> showPolicy("개인정보 처리방침"));
        add(page, policies, 14);

        LinearLayout accountActions = group();
        addRow(accountActions, MenuIconView.Kind.LOGOUT, "로그아웃", false,
                view -> confirmLogout());
        divider(accountActions);
        addRow(accountActions, MenuIconView.Kind.TRASH, "회원탈퇴", true,
                view -> confirmDelete());
        add(page, accountActions, 14);

        setContentView(root);
        root.requestApplyInsets();
        refreshProfile();
    }

    private View touchAutomationRow() {
        LinearLayout row = vertical();
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        com.click.data.local.TouchAutomationSettings preferences = new com.click.data.local.TouchAutomationSettings(this);
        androidx.appcompat.widget.SwitchCompat toggle = new androidx.appcompat.widget.SwitchCompat(this);
        toggle.setText("버튼 자동 터치");
        toggle.setTextSize(18);
        toggle.setTextColor(INK);
        toggle.setMinHeight(dp(48));
        toggle.setChecked(preferences.isEnabled());
        toggle.setOnCheckedChangeListener((button, enabled) -> preferences.setEnabled(enabled));
        row.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        TextView description = text("ON: 지원하는 설정 메뉴 이동과 글자 크기 조절을 자동으로 해요.\nOFF: 위치만 안내해요.\n결제·전화 걸기·메시지 보내기는 자동 실행하지 않아요.", 14, SECONDARY, medium);
        description.setLineSpacing(dp(4), 1f);
        add(row, description, 8);
        return row;
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("메뉴", 31, INK, bold);
        title.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        TextView close = text("×", 38, INK, medium);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("메뉴 닫기");
        close.setClickable(true);
        close.setFocusable(true);
        close.setBackground(ripple(Color.TRANSPARENT, 25));
        close.setOnClickListener(view -> closeMenu());
        row.addView(close, new LinearLayout.LayoutParams(dp(50), dp(50)));
        return row;
    }

    private View profileHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));

        MenuIconView avatar = new MenuIconView(MenuIconView.Kind.USER, false);
        row.addView(avatar, new LinearLayout.LayoutParams(dp(74), dp(74)));

        LinearLayout copy = vertical();
        profileName = text("", 21, INK, semibold);
        copy.addView(profileName, new LinearLayout.LayoutParams(-1, -2));
        TextView welcome = text("Click과 함께해요.", 15, SECONDARY, medium);
        LinearLayout.LayoutParams welcomeParams = new LinearLayout.LayoutParams(-1, -2);
        welcomeParams.topMargin = dp(6);
        copy.addView(welcome, welcomeParams);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.setMarginStart(dp(16));
        row.addView(copy, copyParams);

        TextView edit = text("수정하기", 15, BLUE, semibold);
        edit.setGravity(Gravity.CENTER);
        edit.setMinWidth(dp(94));
        edit.setMinHeight(dp(48));
        edit.setPadding(dp(16), dp(11), dp(16), dp(11));
        edit.setBackground(ripple(0xFFEAF3FF, 22));
        edit.setClickable(true);
        edit.setFocusable(true);
        edit.setOnClickListener(view -> showEditProfile());
        row.addView(edit, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private LinearLayout group() {
        LinearLayout group = vertical();
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xF9FFFFFF);
        card.setCornerRadius(dp(24));
        card.setStroke(dp(1), BORDER);
        group.setBackground(card);
        group.setElevation(dp(2));
        group.setPadding(dp(8), dp(5), dp(8), dp(5));
        return group;
    }

    private void addRow(LinearLayout group, MenuIconView.Kind kind, String label, boolean danger,
                        View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        row.setMinimumHeight(dp(72));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(label);
        row.setBackground(ripple(Color.TRANSPARENT, 18));
        row.setOnClickListener(listener);
        row.addView(new MenuIconView(kind, danger), new LinearLayout.LayoutParams(dp(54), dp(54)));

        TextView title = text(label, 18, danger ? DANGER : INK, semibold);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(dp(15));
        row.addView(title, titleParams);
        TextView arrow = text("›", 32, 0xFF7890B8, medium);
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(42)));
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void divider(LinearLayout group) {
        View line = new View(this);
        line.setBackgroundColor(0xFFE9EFF8);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMarginStart(dp(70));
        params.setMarginEnd(dp(12));
        group.addView(line, params);
    }

    private void showPermissionStatus() {
        boolean accessibility = PermissionStatus.isAccessibilityEnabled(this);
        boolean overlay = Settings.canDrawOverlays(this);
        boolean microphone = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        int missing = (accessibility ? 0 : 1) + (overlay ? 0 : 1) + (microphone ? 0 : 1);

        LinearLayout card = vertical();
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(28));
        card.setBackground(background);
        card.setPadding(dp(22), dp(26), dp(22), dp(20));

        TextView title = text("권한 상태", 23, INK, bold);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView summary = text(missing == 0 ? "모든 권한이 허용되어 있어요."
                : missing + "개 권한이 필요해요. 눌러서 허용할 수 있어요.", 15, SECONDARY, medium);
        summary.setLineSpacing(dp(3), 1f);
        add(card, summary, 10);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(card).create();
        add(card, permissionRow("접근성", "화면을 읽고 안내를 표시해요", accessibility, () -> {
            dialog.dismiss();
            startActivity(new Intent(this, com.click.designpreview.AccessibilityHelpActivity.class));
        }), 20);
        add(card, permissionRow("다른 앱 위 표시", "Click 버튼을 다른 앱 위에 띄워요", overlay, () -> {
            dialog.dismiss();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }), 10);
        add(card, permissionRow("마이크", "말로 요청할 때 사용해요", microphone, () -> {
            dialog.dismiss();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }), 10);

        TextView confirm = text("확인", 17, Color.WHITE, semibold);
        confirm.setGravity(Gravity.CENTER);
        confirm.setBackground(ripple(BLUE, 18));
        confirm.setClickable(true);
        confirm.setFocusable(true);
        confirm.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(-1, dp(56));
        confirmParams.topMargin = dp(22);
        card.addView(confirm, confirmParams);

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** One permission line: name and purpose on the left, a colored status chip on the right. */
    private View permissionRow(String label, String purpose, boolean granted, Runnable openSettings) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setBackground(ripple(granted ? 0xFFF5F8FD : 0xFFFFF4F7, 18));

        LinearLayout copy = vertical();
        copy.addView(text(label, 17, INK, semibold), new LinearLayout.LayoutParams(-1, -2));
        TextView detail = text(purpose, 13, SECONDARY, medium);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(6);
        copy.addView(detail, detailParams);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        TextView chip = text(granted ? "허용됨" : "허용하기 ›", 14,
                granted ? GRANTED : DANGER, semibold);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable chipBackground = new GradientDrawable();
        chipBackground.setColor(granted ? 0xFFE6F6EC : 0xFFFFE3EA);
        chipBackground.setCornerRadius(dp(14));
        chip.setBackground(chipBackground);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
        chipParams.setMarginStart(dp(10));
        row.addView(chip, chipParams);

        row.setContentDescription(label + ", " + (granted ? "허용됨" : "허용 필요, 눌러서 설정 열기"));
        if (!granted) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> openSettings.run());
        }
        return row;
    }

    private void showEditProfile() {
        LinearLayout form = vertical();
        form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText name = input("이름", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        name.setText(account.name());
        EditText email = input("이메일", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(account.email());
        EditText age = input("나이 (선택)", InputType.TYPE_CLASS_NUMBER);
        age.setText(account.age());
        Spinner job = spinner(JOBS, account.job());
        Spinner gender = spinner(GENDERS, account.gender());
        form.addView(name, fieldParams(0));
        form.addView(email, fieldParams(10));
        form.addView(age, fieldParams(10));
        form.addView(job, fieldParams(10));
        form.addView(gender, fieldParams(10));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("내 정보 수정")
                .setView(form)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String nameValue = name.getText().toString().trim();
                    String emailValue = email.getText().toString().trim();
                    if (nameValue.isEmpty() || emailValue.isEmpty()) {
                        Toast.makeText(this, "이름과 이메일을 입력해 주세요.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    account.saveProfile(nameValue, emailValue, age.getText().toString().trim(),
                            job.getSelectedItemPosition() == 0 ? "" : job.getSelectedItem().toString(),
                            gender.getSelectedItemPosition() == 0 ? "" : gender.getSelectedItem().toString());
                    refreshProfile();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private EditText input(String hint, int type) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(type);
        input.setTextSize(17);
        input.setTextColor(INK);
        input.setHintTextColor(SECONDARY);
        input.setTypeface(medium);
        input.setSingleLine(true);
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackground(fieldBackground());
        return input;
    }

    private LinearLayout.LayoutParams fieldParams(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58));
        params.topMargin = dp(top);
        return params;
    }

    private Spinner spinner(String[] values, String selected) {
        Spinner spinner = new Spinner(this);
        spinner.setBackground(fieldBackground());
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(selected)) spinner.setSelection(index);
        }
        return spinner;
    }

    private void showTimingConsentDialog() {
        new AlertDialog.Builder(this)
                .setTitle(timingConsented ? "기록 동의를 철회할까요?" : "맞춤 안내 기록에 동의할까요?")
                .setMessage(timingConsented
                        ? "동의를 철회하면 저장된 개인화 행동 기록과 분석 이력이 삭제되고 맞춤 안내가 중단됩니다."
                        : "안내가 표시된 뒤 첫 탭까지 걸린 시간과 단계 진행 정보를 저장해 더 쉬운 안내를 만드는 데 사용해요. 화면 글자나 비밀번호는 저장하지 않아요.")
                .setNegativeButton("취소", null)
                .setPositiveButton(timingConsented ? "동의 철회" : "동의", (dialog, which) ->
                        personalizationEvents.setConsent(!timingConsented,
                                new PersonalizationEventGateway.ConsentCallback() {
                                    @Override public void onResult(boolean consented) {
                                        timingConsented = consented;
                                        Toast.makeText(MenuActivity.this,
                                                consented ? "맞춤 안내 기록에 동의했어요." : "기록 동의를 철회했어요.",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                    @Override public void onFailure() {
                                        Toast.makeText(MenuActivity.this, "변경하지 못했어요. 다시 시도해 주세요.",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                })).show();
    }

    private void showPolicy(String title) {
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage("정식 정책 문서를 준비하고 있어요.")
                .setPositiveButton("확인", null).show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this).setTitle("로그아웃할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("로그아웃", (dialog, which) -> returnToLogin()).show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle("회원탈퇴를 진행할까요?")
                .setMessage("저장된 회원 정보가 삭제되며 되돌릴 수 없습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("탈퇴", (dialog, which) -> {
                    stopService(new Intent(this, ClickOverlayService.class));
                    account.deleteAccount();
                    returnToLogin();
                }).show();
    }

    private void refreshProfile() {
        String name = account.name().trim();
        if (name.isEmpty()) name = "사용자";
        profileName.setText(name + "님");
    }

    @Override protected void onResume() {
        super.onResume();
        refreshProfile();
        personalizationEvents.readConsent(new PersonalizationEventGateway.ConsentCallback() {
            @Override public void onResult(boolean consented) { timingConsented = consented; }
            @Override public void onFailure() { }
        });
    }

    @Override protected void onDestroy() {
        if (personalizationEvents != null) personalizationEvents.close();
        super.onDestroy();
    }

    private void closeMenu() {
        finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override public void onBackPressed() { closeMenu(); }

    private void returnToLogin() {
        new AuthTokenStore(this).clear();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private TextView text(String value, int size, int color, Typeface typeface) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(typeface);
        text.setIncludeFontPadding(false);
        return text;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private RippleDrawable ripple(int color, int radius) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(radius));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radius));
        return new RippleDrawable(ColorStateList.valueOf(0x1A3478F6), fill, mask);
    }

    private GradientDrawable fieldBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFF8FAFD);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0xFFD9E2F2);
        return background;
    }

    private void configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class MenuBackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        MenuBackgroundView() { super(MenuActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setColor(0x2B74A9FF);
            canvas.drawCircle(w * .98f, h * .05f, w * .27f, paint);
            paint.setColor(0x1B74A9FF);
            canvas.drawCircle(w * .88f, h * .13f, w * .21f, paint);
            paint.setColor(0x1574A9FF);
            canvas.drawCircle(w * .05f, h * .98f, w * .30f, paint);
        }
    }

    private final class MenuIconView extends View {
        enum Kind { USER, SHIELD, SLIDERS, BOOK, DOCUMENT, LOGOUT, TRASH }
        private final Kind kind;
        private final boolean danger;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        MenuIconView(Kind kind, boolean danger) {
            super(MenuActivity.this);
            this.kind = kind;
            this.danger = danger;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(danger ? 0xFFFFEAF0 : 0xFFEAF3FF);
            canvas.drawCircle(cx, cy, Math.min(w, h) * .48f, paint);
            paint.setColor(danger ? DANGER : BLUE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if (kind == Kind.USER) {
                paint.setColor(0xFF74A7FF);
                canvas.drawCircle(cx, cy - dp(8), dp(9), paint);
                RectF body = new RectF(cx - dp(15), cy + dp(3), cx + dp(15), cy + dp(20));
                canvas.drawRoundRect(body, dp(10), dp(10), paint);
            } else if (kind == Kind.SHIELD) {
                path.reset(); path.moveTo(cx, cy - dp(16)); path.lineTo(cx + dp(14), cy - dp(10));
                path.lineTo(cx + dp(12), cy + dp(9)); path.quadTo(cx, cy + dp(19), cx, cy + dp(19));
                path.quadTo(cx - dp(12), cy + dp(9), cx - dp(12), cy + dp(9));
                path.lineTo(cx - dp(14), cy - dp(10)); path.close(); canvas.drawPath(path, paint);
                paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
                canvas.drawLine(cx - dp(5), cy + dp(1), cx - dp(1), cy + dp(5), paint);
                canvas.drawLine(cx - dp(1), cy + dp(5), cx + dp(7), cy - dp(4), paint);
            } else if (kind == Kind.SLIDERS) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * dp(10); canvas.drawLine(cx - dp(15), y, cx + dp(15), y, paint);
                    float knob = cx + (i == -1 ? dp(7) : i == 0 ? -dp(6) : dp(3));
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(knob, y, dp(4), paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
            } else if (kind == Kind.BOOK) {
                RectF left = new RectF(cx - dp(15), cy - dp(14), cx - dp(1), cy + dp(15));
                RectF right = new RectF(cx + dp(1), cy - dp(14), cx + dp(15), cy + dp(15));
                canvas.drawRoundRect(left, dp(3), dp(3), paint); canvas.drawRoundRect(right, dp(3), dp(3), paint);
            } else if (kind == Kind.DOCUMENT) {
                canvas.drawRoundRect(new RectF(cx - dp(12), cy - dp(16), cx + dp(12), cy + dp(16)), dp(4), dp(4), paint);
                paint.setColor(Color.WHITE); paint.setStrokeWidth(dp(2));
                for (int i = -1; i <= 1; i++) canvas.drawLine(cx - dp(6), cy + i * dp(7), cx + dp(6), cy + i * dp(7), paint);
            } else if (kind == Kind.LOGOUT) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3));
                canvas.drawRoundRect(new RectF(cx - dp(14), cy - dp(15), cx + dp(2), cy + dp(15)), dp(3), dp(3), paint);
                canvas.drawLine(cx - dp(1), cy, cx + dp(16), cy, paint);
                canvas.drawLine(cx + dp(16), cy, cx + dp(10), cy - dp(6), paint);
                canvas.drawLine(cx + dp(16), cy, cx + dp(10), cy + dp(6), paint);
            } else {
                canvas.drawRoundRect(new RectF(cx - dp(10), cy - dp(10), cx + dp(10), cy + dp(15)), dp(3), dp(3), paint);
                canvas.drawRoundRect(new RectF(cx - dp(14), cy - dp(16), cx + dp(14), cy - dp(10)), dp(3), dp(3), paint);
                paint.setColor(danger ? 0xFFFFEAF0 : 0xFFEAF3FF); paint.setStrokeWidth(dp(2));
                canvas.drawLine(cx - dp(4), cy - dp(5), cx - dp(4), cy + dp(9), paint);
                canvas.drawLine(cx + dp(4), cy - dp(5), cx + dp(4), cy + dp(9), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
