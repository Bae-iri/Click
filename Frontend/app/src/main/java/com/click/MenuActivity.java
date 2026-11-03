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
import android.graphics.RadialGradient;
import android.graphics.Shader;
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
    private static final int DANGER = 0xFFE94467;
    private static final int GRANTED = 0xFF1E9E55;

    private Typeface regular;
    private Typeface medium;
    private Typeface semibold;
    private PersonalizationEventGateway personalizationEvents;
    private boolean timingConsented;
    private androidx.appcompat.widget.SwitchCompat automationToggle;
    private TouchAutomationSettings automationSettings;
    private boolean syncingAutomationToggle;
    private AccountActionsGateway accountActions;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        regular = getResources().getFont(com.click.designpreview.R.font.pretendard_regular);
        medium = getResources().getFont(com.click.designpreview.R.font.pretendard_medium);
        semibold = getResources().getFont(com.click.designpreview.R.font.pretendard_semibold);
        personalizationEvents = new PersonalizationEventGateway(this);
        automationSettings = new TouchAutomationSettings(this);
        accountActions = new AccountActionsGateway(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.addView(new MenuBackgroundView(), new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = vertical();
        page.setClipChildren(false);
        page.setClipToPadding(false);
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

        section(page, "도움 설정");
        LinearLayout settings = group();
        addRow(settings, MenuIconView.Kind.SHIELD, "앱 권한 확인", "안내에 필요한 권한을 확인해요", false,
                view -> showPermissionStatus());
        divider(settings);
        addRow(settings, MenuIconView.Kind.PERSONALIZE, "나에게 맞는 안내", "사용 기록을 활용할지 선택해요", false,
                view -> showTimingConsentDialog());
        divider(settings);
        addRow(settings, MenuIconView.Kind.AUTOMATION, "자동으로 도와주기", "화면 이동과 설정 조절을 도와드려요", false,
                view -> showAutomationSettings());
        add(page, settings, 10);

        section(page, "이용 안내");
        LinearLayout tutorial = group();
        addRow(tutorial, MenuIconView.Kind.BOOK, "클릭 사용법", "직접 따라 하며 익혀보세요", false,
                view -> startActivity(new Intent(this, TutorialActivity.class)));
        add(page, tutorial, 10);

        LinearLayout policies = group();
        addRow(policies, MenuIconView.Kind.DOCUMENT, "서비스 이용약관", null, false,
                view -> showPolicy("서비스 이용약관", R.array.click_terms_sections));
        divider(policies);
        addRow(policies, MenuIconView.Kind.LOCK, "개인정보 처리방침", null, false,
                view -> showPolicy("개인정보 처리방침", R.array.click_privacy_sections));
        add(page, policies, 12);

        section(page, "계정 관리");
        LinearLayout accountActions = group();
        addRow(accountActions, MenuIconView.Kind.LOGOUT, "로그아웃", null, false,
                view -> confirmLogout());
        divider(accountActions);
        addRow(accountActions, MenuIconView.Kind.TRASH, "회원 탈퇴", null, true,
                view -> confirmDelete());
        add(page, accountActions, 10);

        setContentView(root);
        root.requestApplyInsets();
    }

    private void showAutomationSettings() {
        boolean enabled = automationSettings.isEnabled();
        LinearLayout content = vertical();
        add(content, dialogNotice(MenuIconView.Kind.AUTOMATION,
                enabled ? "지금은 자동 모드가 켜져 있어요" : "지금은 자동 모드가 꺼져 있어요"), 0);
        add(content, dialogSection("자동 모드를 켜면",
                "요청을 확인하고 시작하면 지원되는 앱 열기, 메뉴 이동, 스크롤과 설정 조절을 도와드려요.",
                "직접 조작이 필요한 단계는 따로 안내해요."), 22);
        add(content, dialogSection("자동 모드를 끄면",
                "눌러야 할 곳을 안내해 드려요. 안내에 따라 직접 조작해 주세요."), 22);
        add(content, dialogSection("기억해 주세요",
                "진행 중에도 설정 화면 위의 스위치로 끌 수 있어요.",
                "결제·전화 걸기·메시지 보내기는 자동으로 실행하지 않아요."), 22);
        ClickDialog.show(this, "자동으로 도와주기", content,
                enabled ? "자동 모드 끄기" : "자동 모드 켜기",
                () -> automationToggle.setChecked(!enabled), "닫기");
    }

    private void showAutomationSummary() {
        LinearLayout content = vertical();
        add(content, dialogSection("이렇게 도와드려요",
                "앱 열기·화면 이동·스크롤·설정 조절을 자동으로 도와드려요.",
                "언제든 자동 모드를 끄거나 작업을 멈출 수 있어요."), 0);
        add(content, dialogNotice(MenuIconView.Kind.AUTOMATION,
                "결제·전화 걸기·메시지 보내기는 자동으로 실행하지 않아요."), 16);
        ClickDialog.show(this, "자동 모드를 켰어요", content, "알겠어요", null, null);
    }

    /** Short sections leave breathing room without putting all copy in one box. */
    private View dialogSection(String title, String... paragraphs) {
        LinearLayout section = vertical();
        section.setPadding(dp(2), 0, dp(2), 0);
        TextView heading = text(title, 15, INK, semibold);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        section.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        for (String paragraph : paragraphs) {
            TextView copy = text(paragraph, 14, 0xFF65738C, regular);
            copy.setLineSpacing(dp(3), 1.15f);
            add(section, copy, 9);
        }
        return section;
    }

    private View dialogNotice(MenuIconView.Kind kind, String message) {
        LinearLayout note = new LinearLayout(this);
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.setPadding(dp(12), dp(12), dp(14), dp(12));
        note.setBackground(ripple(0xFFF5F8FE, 18));
        note.addView(new MenuIconView(kind, kind == MenuIconView.Kind.TRASH),
                new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView copy = text(message, 14, INK, medium);
        copy.setLineSpacing(dp(2), 1.1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(dp(10));
        note.addView(copy, params);
        return note;
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        View back = new MenuIconView(MenuIconView.Kind.BACK, false);
        back.setContentDescription("홈으로 돌아가기");
        back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        back.setClickable(true);
        back.setFocusable(true);
        back.setBackground(ripple(Color.WHITE, 24));
        back.setOnClickListener(view -> closeMenu());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("설정", 24, INK, semibold);
        title.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        title.setPadding(dp(12), 0, dp(8), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        automationToggle = new androidx.appcompat.widget.SwitchCompat(this);
        automationToggle.setText("자동 모드");
        automationToggle.setTextSize(13);
        automationToggle.setTypeface(medium);
        automationToggle.setTextColor(BLUE);
        automationToggle.setShowText(false);
        automationToggle.setTextOn("켜짐");
        automationToggle.setTextOff("꺼짐");
        automationToggle.setSwitchMinWidth(dp(48));
        automationToggle.setSwitchPadding(dp(6));
        automationToggle.setMinHeight(dp(48));
        automationToggle.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF2469EA, 0xFF566885}));
        automationToggle.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFBFD7FF, 0xFFE5EDF8}));
        automationToggle.setChecked(automationSettings.isEnabled());
        automationToggle.setContentDescription("자동 모드");
        automationToggle.setOnCheckedChangeListener((button, enabled) -> {
            if (syncingAutomationToggle) return;
            automationToggle.setText("자동 모드");
            automationSettings.setEnabled(enabled);
            ClickOverlayService.onAutomationSettingChanged();
            if (enabled) showAutomationSummary();
        });
        row.addView(automationToggle, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private LinearLayout group() {
        LinearLayout group = vertical();
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(dp(24));
        card.setStroke(dp(1), 0xFFF0F3F8);

        group.setBackground(card);
        group.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        group.setClipToOutline(true);
        group.setElevation(dp(1));
        if (Build.VERSION.SDK_INT >= 28) {
            group.setOutlineAmbientShadowColor(0x183D5D8F);
            group.setOutlineSpotShadowColor(0x143D5D8F);
        }
        group.setPadding(dp(8), dp(5), dp(8), dp(5));
        return group;
    }

    private void section(LinearLayout page, String title) {
        TextView heading = text(title, 14, SECONDARY, medium);
        heading.setPadding(dp(4), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        add(page, heading, 24);
    }

    private void addRow(LinearLayout group, MenuIconView.Kind kind, String label, String description, boolean danger,
                        View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(13), dp(8), dp(13));
        row.setMinimumHeight(dp(72));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(description == null ? label : label + ", " + description);
        row.setBackground(ripple(Color.TRANSPARENT, 18));
        row.setOnClickListener(listener);
        row.addView(new MenuIconView(kind, danger), new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout copy = vertical();
        TextView title = text(label, 16, danger ? DANGER : INK, medium);
        copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (description != null) {
            TextView detail = text(description, 12, SECONDARY, regular);
            detail.setLineSpacing(dp(2), 1f);
            add(copy, detail, 5);
        }
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(dp(12));
        row.addView(copy, titleParams);
        row.addView(new MenuIconView(MenuIconView.Kind.NEXT, false),
                new LinearLayout.LayoutParams(dp(24), dp(32)));
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void divider(LinearLayout group) {
        View line = new View(this);
        line.setBackgroundColor(0xFFF0F4FA);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMarginStart(dp(64));
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
        add(statuses, permissionRow("다른 앱 위 표시", "다른 앱에서도 클릭 안내를 볼 수 있어요.", overlay, () -> {
            if (dialog[0] != null) dialog[0].dismiss();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }), 10);
        add(statuses, permissionRow("마이크", "도움이 필요한 내용을 말로 입력해요.", microphone, () -> {
            if (dialog[0] != null) dialog[0].dismiss();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }), 10);
        dialog[0] = ClickDialog.show(this, "앱 권한 확인", statuses, "확인했어요", null, null);
    }

    /** Status stays beside the heading; the explanation gets the full card width. */
    private View permissionRow(String label, String purpose, boolean granted, Runnable openSettings) {
        LinearLayout row = vertical();
        row.setPadding(dp(16), dp(16), dp(16), dp(16));
        row.setBackground(ripple(granted ? 0xFFF5F8FD : 0xFFFFF4F7, 18));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text(label, 16, INK, semibold), new LinearLayout.LayoutParams(0, -2, 1));
        TextView chip = text(granted ? "허용됨" : "허용하기 ›", 13,
                granted ? GRANTED : DANGER, medium);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable chipBackground = new GradientDrawable();
        chipBackground.setColor(granted ? 0xFFE6F6EC : 0xFFFFE3EA);
        chipBackground.setCornerRadius(dp(12));
        chip.setBackground(chipBackground);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
        chipParams.setMarginStart(dp(10));
        header.addView(chip, chipParams);
        row.addView(header, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = text(purpose, 14, SECONDARY, regular);
        detail.setLineSpacing(dp(3), 1.15f);
        add(row, detail, 10);
        row.setContentDescription(label + ", " + purpose + ", "
                + (granted ? "허용됨" : "허용 필요, 눌러서 설정 열기"));
        if (!granted) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> openSettings.run());
        }
        return row;
    }

    private void showTimingConsentDialog() {
        final boolean consent = !timingConsented;
        LinearLayout content = vertical();
        add(content, dialogNotice(MenuIconView.Kind.PERSONALIZE,
                timingConsented ? "사용 기록으로 안내를 맞추고 있어요" : "나에게 맞는 안내를 위한 선택이에요"), 0);
        if (timingConsented) {
            add(content, dialogSection("동의를 철회하면",
                    "저장된 맞춤 안내 행동 기록이 삭제돼요.",
                    "안내 수준도 처음 상태로 돌아가요."), 22);
            add(content, dialogSection("기본 안내는 그대로",
                    "동의를 철회해도 기본 안내는 계속 이용할 수 있어요."), 22);
        } else {
            add(content, dialogSection("어떤 기록을 사용하나요?",
                    "안내가 나온 뒤 처음 누르기까지 걸린 시간과 단계 진행 정보를 저장해요."), 22);
            add(content, dialogSection("어떻게 활용하나요?",
                    "사용 기록을 바탕으로 더 쉬운 안내를 만드는 데 사용해요."), 22);
            add(content, dialogSection("편하게 선택해 주세요",
                    "이 기록에는 화면 글자나 비밀번호를 저장하지 않아요.",
                    "동의하지 않아도 기본 안내는 이용할 수 있어요."), 22);
        }
        ClickDialog.show(this, "나에게 맞는 안내", content,
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
        String[] sections = getResources().getStringArray(contentId);
        for (int index = 0; index < sections.length; index += 2) {
            if (index > 0) {
                View line = new View(this);
                line.setBackgroundColor(0xFFEAF0F8);
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
                lineParams.topMargin = dp(24);
                content.addView(line, lineParams);
            }
            add(content, dialogSection(sections[index], sections[index + 1].split("\\n+")), index == 0 ? 0 : 24);
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
        add(content, dialogNotice(delete ? MenuIconView.Kind.TRASH : MenuIconView.Kind.LOGOUT,
                delete ? "탈퇴 전에 꼭 확인해 주세요" : "잠시 이용을 마치시나요?"), 0);
        if (delete) {
            add(content, dialogSection("삭제되는 정보",
                    "서버에 저장된 계정과 연결된 이용 기록을 삭제해요.",
                    "이 기기에 저장된 회원 정보도 함께 삭제해요."), 22);
            add(content, dialogSection("되돌릴 수 없어요",
                    "탈퇴 후에는 삭제한 정보를 복구할 수 없어요. 계속 진행하실지 확인해 주세요."), 22);
        } else {
            add(content, dialogSection("진행 중인 안내가 종료돼요",
                    "현재 안내를 종료하고 로그아웃해요."), 22);
            add(content, dialogSection("언제든 다시 이용할 수 있어요",
                    "다시 로그인하면 클릭을 이용할 수 있어요."), 22);
        }
        TextView errorMessage = text("", 14, DANGER, medium);
        errorMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorMessage.setVisibility(View.GONE);
        add(content, errorMessage, 12);
        Dialog dialog = ClickDialog.show(this, delete ? "회원 탈퇴를 진행할까요?" : "로그아웃할까요?",
                content, delete ? "회원 탈퇴" : "로그아웃", null, "취소");
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
        syncingAutomationToggle = true;
        automationToggle.setChecked(automationSettings.isEnabled());
        syncingAutomationToggle = false;
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
        MenuBackgroundView() {
            super(MenuActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            paint.setShader(new RadialGradient(w + dp(4), dp(175), dp(230),
                    new int[]{0xFFDFF1FF, 0xFFECF7FF, 0xFFFCFDFF},
                    new float[]{0, .67f, 1}, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }

    /** Rounded, consistently weighted pictograms paired with the home card palette. */
    private final class MenuIconView extends View {
        enum Kind { SHIELD, PERSONALIZE, AUTOMATION, BOOK, DOCUMENT, LOCK, LOGOUT, TRASH, BACK, NEXT }
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
        private void stroke(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.1f);
        }
        @Override protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            canvas.save();
            canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
            canvas.scale(size / 48f, size / 48f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.FILL);
            if (kind == Kind.BACK || kind == Kind.NEXT) {
                stroke(kind == Kind.BACK ? INK : 0xFF9BAAC0);
                paint.setStrokeWidth(kind == Kind.BACK ? 2.2f : 2.8f);
                path.reset();
                if (kind == Kind.BACK) {
                    path.moveTo(27, 17); path.lineTo(20, 24); path.lineTo(27, 31);
                } else {
                    path.moveTo(20, 16); path.lineTo(28, 24); path.lineTo(20, 32);
                }
                canvas.drawPath(path, paint);
                canvas.restore(); return;
            }
            int accent = danger ? DANGER : BLUE;
            int tint = 0xFFEAF2FF;
            if (kind == Kind.PERSONALIZE) { accent = 0xFF8970D7; tint = 0xFFF1ECFF; }
            if (kind == Kind.AUTOMATION) { accent = 0xFFE8A028; tint = 0xFFFFF4DD; }
            if (kind == Kind.BOOK) { accent = 0xFF39A78C; tint = 0xFFE6F7F0; }
            if (kind == Kind.LOGOUT) { accent = 0xFF8391A8; tint = 0xFFF0F3F8; }
            if (danger) tint = 0xFFFFEBEF;
            paint.setColor(tint);
            canvas.drawRoundRect(1, 1, 47, 47, 15, 15, paint);
            stroke(accent);
            switch (kind) {
                case SHIELD:
                    path.reset(); path.moveTo(24, 11); path.lineTo(35, 15);
                    path.lineTo(34, 25); path.quadTo(33, 32, 24, 37);
                    path.quadTo(15, 32, 14, 25); path.lineTo(13, 15); path.close();
                    canvas.drawPath(path, paint);
                    path.reset(); path.moveTo(19, 24); path.lineTo(23, 28); path.lineTo(30, 20);
                    canvas.drawPath(path, paint); break;
                case PERSONALIZE:
                    canvas.drawCircle(22, 19, 5, paint);
                    canvas.drawArc(12, 27, 32, 41, 190, 160, false, paint);
                    canvas.drawLine(35, 14, 35, 22, paint);
                    canvas.drawLine(31, 18, 39, 18, paint); break;
                case AUTOMATION:
                    path.reset(); path.moveTo(25, 10); path.lineTo(15, 26); path.lineTo(23, 26);
                    path.lineTo(21, 38); path.lineTo(34, 21); path.lineTo(26, 21); path.close();
                    paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint); break;
                case BOOK:
                    path.reset(); path.moveTo(24, 16); path.quadTo(18, 12, 11, 14);
                    path.lineTo(11, 33); path.quadTo(18, 31, 24, 35);
                    path.quadTo(30, 31, 37, 33); path.lineTo(37, 14);
                    path.quadTo(30, 12, 24, 16); path.lineTo(24, 35);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(16, 20, 20, 21, paint);
                    canvas.drawLine(28, 21, 32, 20, paint); break;
                case DOCUMENT:
                    canvas.drawRoundRect(14, 11, 34, 37, 4, 4, paint);
                    canvas.drawLine(19, 19, 29, 19, paint);
                    canvas.drawLine(19, 24, 29, 24, paint);
                    canvas.drawLine(19, 29, 25, 29, paint); break;
                case LOCK:
                    canvas.drawRoundRect(14, 21, 34, 36, 5, 5, paint);
                    canvas.drawArc(18, 11, 30, 29, 180, 180, false, paint);
                    canvas.drawLine(24, 27, 24, 30, paint); break;
                case LOGOUT:
                    path.reset(); path.moveTo(22, 13); path.lineTo(15, 13);
                    path.quadTo(12, 13, 12, 16); path.lineTo(12, 32);
                    path.quadTo(12, 35, 15, 35); path.lineTo(22, 35);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(21, 24, 37, 24, paint);
                    path.reset(); path.moveTo(31, 18); path.lineTo(37, 24); path.lineTo(31, 30);
                    canvas.drawPath(path, paint); break;
                case TRASH:
                    canvas.drawRoundRect(16, 18, 32, 36, 3, 3, paint);
                    canvas.drawLine(12, 16, 36, 16, paint);
                    canvas.drawRoundRect(20, 11, 28, 16, 2, 2, paint);
                    canvas.drawLine(21, 23, 21, 30, paint);
                    canvas.drawLine(27, 23, 27, 30, paint); break;
                default: break;
            }
            canvas.restore();
        }
    }
}
