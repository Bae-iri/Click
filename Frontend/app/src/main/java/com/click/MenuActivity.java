package com.click;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.click.core.permission.PermissionStatus;
import com.click.data.remote.PersonalizationEventGateway;
import com.click.data.remote.AccountActionsGateway;
import com.click.data.remote.AuthGateway;
import com.click.data.local.TouchAutomationSettings;
import com.click.service.overlay.ClickOverlayService;
import com.click.ui.common.ClickDialog;

/** Account and settings menu using the same soft-blue visual system as the Click home screen. */
public final class MenuActivity extends AppCompatActivity {
    private static final int BLUE = 0xFF3478F6;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF7182A4;
    private static final int BORDER = 0xFFE5EDF8;
    private static final int DANGER = 0xFFE94467;
    private static final int GRANTED = 0xFF1E9E55;

    private Typeface medium;
    private Typeface semibold;
    private Typeface bold;
    private PersonalizationEventGateway personalizationEvents;
    private boolean timingConsented;
    private androidx.appcompat.widget.SwitchCompat automationToggle;
    private TouchAutomationSettings automationSettings;
    private AccountActionsGateway accountActions;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        medium = getResources().getFont(com.click.designpreview.R.font.pretendard_medium);
        semibold = getResources().getFont(com.click.designpreview.R.font.pretendard_semibold);
        bold = getResources().getFont(com.click.designpreview.R.font.pretendard_bold);
        personalizationEvents = new PersonalizationEventGateway(this);
        automationSettings = new TouchAutomationSettings(this);
        accountActions = new AccountActionsGateway(this);

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

        page.addView(header(), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout settings = group();
        addRow(settings, MenuIconView.Kind.SHIELD, "권한 상태 확인", false,
                view -> showPermissionStatus());
        divider(settings);
        addRow(settings, MenuIconView.Kind.SLIDERS, "맞춤 안내 기록 설정", false,
                view -> showTimingConsentDialog());
        divider(settings);
        addRow(settings, MenuIconView.Kind.SLIDERS, "지원 작업 전체 자동화", false,
                view -> showAutomationSettings());
        add(page, settings, 18);

        LinearLayout tutorial = group();
        addRow(tutorial, MenuIconView.Kind.BOOK, "튜토리얼", false,
                view -> startActivity(new Intent(this, TutorialActivity.class)));
        add(page, tutorial, 14);

        LinearLayout policies = group();
        addRow(policies, MenuIconView.Kind.DOCUMENT, "서비스 이용약관", false,
                view -> showPolicy("서비스 이용약관", R.array.click_terms_sections));
        divider(policies);
        addRow(policies, MenuIconView.Kind.SHIELD, "개인정보 처리방침", false,
                view -> showPolicy("개인정보 처리방침", R.array.click_privacy_sections));
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
    }

    private void showAutomationSettings() {
        boolean enabled = automationSettings.isEnabled();
        ClickDialog.show(this, "지원 작업 전체 자동화", ClickDialog.body(this,
                (enabled ? "자동화가 켜져 있어요.\n\n" : "자동화가 꺼져 있어요.\n\n")
                        + "ON · 요청을 확인하고 시작하면 지원되는 앱 열기, 메뉴 이동, 스크롤과 설정 조절을 자동으로 진행해요. 직접 조작이 필요한 단계는 따로 안내해요.\n\n"
                        + "OFF · 눌러야 할 곳을 안내하고 사용자가 직접 조작해요.\n\n"
                        + "진행 중에도 상단 스위치로 끌 수 있어요. 결제·전화 걸기·메시지 보내기는 자동 실행하지 않아요."),
                enabled ? "자동화 끄기" : "자동화 켜기",
                () -> automationToggle.setChecked(!enabled), "닫기");
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        TextView back = text("‹", 32, BLUE, medium);
        back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 36);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("메뉴 닫고 돌아가기");
        back.setClickable(true);
        back.setFocusable(true);
        back.setBackground(ripple(0xFFEAF3FF, 24));
        back.setOnClickListener(view -> closeMenu());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("메뉴", 28, INK, bold);
        title.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        title.setPadding(dp(10), 0, dp(8), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        automationToggle = new androidx.appcompat.widget.SwitchCompat(this);
        automationToggle.setText(automationSettings.isEnabled() ? "자동 ON" : "자동 OFF");
        automationToggle.setTextSize(15);
        automationToggle.setTypeface(semibold);
        automationToggle.setTextColor(BLUE);
        automationToggle.setShowText(false);
        automationToggle.setTextOn("ON");
        automationToggle.setTextOff("OFF");
        automationToggle.setSwitchMinWidth(dp(62));
        automationToggle.setSwitchPadding(dp(6));
        automationToggle.setMinHeight(dp(48));
        automationToggle.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF2469EA, 0xFF566885}));
        automationToggle.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFBFD7FF, 0xFFE5EDF8}));
        automationToggle.setChecked(automationSettings.isEnabled());
        automationToggle.setContentDescription("지원 작업 전체 자동화");
        automationToggle.setOnCheckedChangeListener((button, enabled) -> {
            automationToggle.setText(enabled ? "자동 ON" : "자동 OFF");
            automationSettings.setEnabled(enabled);
            ClickOverlayService.onAutomationSettingChanged();
        });
        row.addView(automationToggle, new LinearLayout.LayoutParams(-2, -2));
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

        String displayLabel = getResources().getConfiguration().fontScale > 1.3f
                && "지원 작업 전체 자동화".equals(label) ? "지원 작업\n전체 자동화" : label;
        TextView title = text(displayLabel, 18, danger ? DANGER : INK, semibold);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(dp(15));
        row.addView(title, titleParams);
        TextView arrow = text("›", 32, 0xFF7890B8, medium);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 28);
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
        LinearLayout statuses = vertical();
        final android.app.Dialog[] dialog = {null};
        add(statuses, permissionRow("접근성", "화면을 읽고 눌러야 할 곳을 안내해요.", accessibility, () -> {
            if (dialog[0] != null) dialog[0].dismiss();
            startActivity(new Intent(this, com.click.designpreview.AccessibilityHelpActivity.class));
        }), 0);
        add(statuses, permissionRow("다른 앱 위 표시", "다른 앱에서도 click 안내를 볼 수 있어요.", overlay, () -> {
            if (dialog[0] != null) dialog[0].dismiss();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }), 10);
        add(statuses, permissionRow("마이크", "도움이 필요한 내용을 말로 입력해요.", microphone, () -> {
            if (dialog[0] != null) dialog[0].dismiss();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }), 10);
        dialog[0] = ClickDialog.show(this, "권한 상태 확인", statuses, "확인했어요", null, null);
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

    private void showTimingConsentDialog() {
        final boolean consent = !timingConsented;
        ClickDialog.show(this, "맞춤 안내 기록 설정", ClickDialog.body(this, timingConsented
                        ? "동의를 철회하면 저장된 맞춤 안내 행동 기록이 삭제되고 안내 수준이 초기화돼요. 기본 안내는 계속 이용할 수 있어요."
                        : "안내가 표시된 뒤 첫 탭까지 걸린 시간과 단계 진행 정보를 저장해 더 쉬운 안내를 만드는 데 사용해요. 화면 글자나 비밀번호는 저장하지 않아요."),
                timingConsented ? "기록 동의 철회" : "동의하고 사용하기", () ->
                        personalizationEvents.setConsent(consent,
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
                                }), "취소");
    }

    private void showPolicy(String title, int contentId) {
        LinearLayout content = vertical();
        add(content, ClickDialog.body(this, getString(R.string.click_policy_draft_notice)), 0);
        String[] sections = getResources().getStringArray(contentId);
        for (int index = 0; index < sections.length; index += 2) {
            TextView heading = text(sections[index], 17, INK, semibold);
            if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            add(content, heading, 22);
            TextView paragraph = text(sections[index + 1], 15, INK, medium);
            paragraph.setLineSpacing(dp(3), 1.15f);
            add(content, paragraph, 8);
        }
        ClickDialog.show(this, title, content, "확인했어요", null, null);
    }

    private void confirmLogout() {
        confirmAccountAction(false);
    }

    private void confirmDelete() {
        confirmAccountAction(true);
    }

    private void confirmAccountAction(boolean delete) {
        LinearLayout content = vertical();
        content.addView(ClickDialog.body(this, delete
                ? "서버의 계정과 연결된 이용 기록, 이 기기의 회원 정보를 삭제해요. 삭제한 정보는 되돌릴 수 없어요."
                : "진행 중인 안내를 종료하고 로그아웃해요. 다시 로그인하면 Click을 이용할 수 있어요."));
        TextView errorMessage = text("", 14, DANGER, medium);
        errorMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorMessage.setVisibility(View.GONE);
        add(content, errorMessage, 12);
        Dialog dialog = ClickDialog.show(this, delete ? "회원탈퇴를 진행할까요?" : "로그아웃할까요?",
                content, delete ? "회원탈퇴" : "로그아웃", null, "취소");
        TextView confirm = dialog.findViewById(android.R.id.button1);
        View cancel = dialog.findViewById(android.R.id.button2);
        confirm.setOnClickListener(view -> {
            if (!confirm.isEnabled()) return;
            confirm.setEnabled(false);
            cancel.setEnabled(false);
            confirm.setText("처리 중이에요…");
            errorMessage.setVisibility(View.GONE);
            dialog.setCancelable(false);
            AuthGateway.Callback callback = new AuthGateway.Callback() {
                @Override public void onSuccess() {
                    dialog.dismiss();
                    returnToLogin();
                }
                @Override public void onFailure(Throwable error) {
                    if (isFinishing() || isDestroyed()) return;
                    confirm.setEnabled(true);
                    cancel.setEnabled(true);
                    confirm.setText(delete ? "다시 탈퇴하기" : "다시 로그아웃하기");
                    dialog.setCancelable(true);
                    errorMessage.setText(delete
                            ? "서버에서 탈퇴 완료를 확인하지 못했어요. 인터넷 연결을 확인한 뒤 다시 시도해 주세요."
                            : "로그아웃하지 못했어요. 인터넷 연결을 확인한 뒤 다시 시도해 주세요.");
                    errorMessage.setVisibility(View.VISIBLE);
                }
            };
            if (delete) accountActions.deleteAccount(callback);
            else accountActions.logout(callback);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        automationToggle.setChecked(automationSettings.isEnabled());
        personalizationEvents.readConsent(new PersonalizationEventGateway.ConsentCallback() {
            @Override public void onResult(boolean consented) { timingConsented = consented; }
            @Override public void onFailure() { }
        });
    }

    @Override protected void onDestroy() {
        if (personalizationEvents != null) personalizationEvents.close();
        if (accountActions != null) accountActions.close();
        super.onDestroy();
    }

    private void closeMenu() {
        finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override public void onBackPressed() { closeMenu(); }

    private void returnToLogin() {
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
