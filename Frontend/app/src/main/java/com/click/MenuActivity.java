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
import com.click.data.remote.GraphImprovementConsentGateway;
import com.click.data.remote.AccountActionsGateway;
import com.click.data.remote.AuthGateway;
import com.click.data.local.TouchAutomationSettings;
import com.click.service.overlay.ClickOverlayService;
import com.click.ui.common.ClickDialog;
import com.click.ui.common.ClickTextScale;

/** Account and settings menu using the same soft-blue visual system as the Click home screen. */
public final class MenuActivity extends AppCompatActivity {
    static final String EXTRA_TOUR = "com.click.extra.TOUR_SETTINGS";
    private com.click.ui.tutorial.ClueCoachView coach;
    private View automationRow, displayRow;
    private Dialog tourNotice;
    private boolean readTourNotice;
    private static final int BLUE = 0xFF3478F6;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF7182A4;
    private static final int DANGER = 0xFFE94467;
    private static final int GRANTED = 0xFF1E9E55;

    private Typeface regular;
    private Typeface medium;
    private Typeface semibold;
    private androidx.appcompat.widget.SwitchCompat automationToggle;
    private TouchAutomationSettings automationSettings;
    private boolean syncingAutomationToggle;
    private AccountActionsGateway accountActions;
    private GraphImprovementConsentGateway graphConsent;
    private boolean graphConsented;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        regular = getResources().getFont(com.click.designpreview.R.font.pretendard_regular);
        medium = getResources().getFont(com.click.designpreview.R.font.pretendard_medium);
        semibold = getResources().getFont(com.click.designpreview.R.font.pretendard_semibold);
        automationSettings = new TouchAutomationSettings(this);
        accountActions = new AccountActionsGateway(this);
        graphConsent = new GraphImprovementConsentGateway(this);

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
        automationRow = addRow(settings, MenuIconView.Kind.AUTOMATION, "자동으로 도와주기", "화면 이동과 설정 조절을 도와드려요", false,
                view -> showAutomationSettings());
        divider(settings);
        addRow(settings, MenuIconView.Kind.PERSONALIZE, "클릭 개선 참여", "사용 결과를 바탕으로 클릭을 더 편리하게 만들어요", false,
                view -> showGraphConsent());
        add(page, settings, 10);

        section(page, "클릭 설정");
        LinearLayout displaySettings = group();
        displayRow = addRow(displaySettings, MenuIconView.Kind.SETTINGS, "글자 크기", "클릭 앱 글자 크기를 조절해요", false,
                view -> startActivity(new Intent(this, DisplaySettingsActivity.class)));
        divider(displaySettings);
        addRow(displaySettings, MenuIconView.Kind.CLUE, "클루에 대해서", "클릭과 함께하는 펭귄 친구를 소개해요", false,
                view -> startActivity(new Intent(this, AboutClueActivity.class)));
        add(page, displaySettings, 10);

        section(page, "이용 안내");
        LinearLayout tutorial = group();
        addRow(tutorial, MenuIconView.Kind.BOOK, "클릭 사용법", "직접 따라 하며 익혀보세요", false,
                view -> startActivity(HomeActivity.tutorialIntent(this)));
        if (com.click.data.local.CandidateDiagnosticLog.enabled()) {
            divider(tutorial);
            addRow(tutorial, MenuIconView.Kind.SETTINGS, "테스트 진단 자료 공유", "안내가 멈췄다면 문제 확인 자료를 공유해 주세요", false,
                    view -> shareCandidateDiagnostics());
        }
        divider(tutorial);
        addRow(tutorial, MenuIconView.Kind.ACCESSIBILITY, "접근성 설정", "접근성 설정 방법을 확인해요", false,
                view -> startActivity(new Intent(this, com.click.designpreview.PermissionGuideActivity.class)
                        .putExtra(com.click.designpreview.PermissionGuideActivity.EXTRA_RETURN_TO_SETTINGS, true)
                        .putExtra(com.click.designpreview.PermissionGuideActivity.EXTRA_RECONNECT,
                                PermissionStatus.isAccessibilityEnabled(this)
                                        && com.click.service.accessibility.ClickAccessibilityService.accessibilityWindowManager() == null)));
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
        if (getIntent().getBooleanExtra(EXTRA_TOUR, false)) {
            coach = new com.click.ui.tutorial.ClueCoachView(root);
            getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() { previousTourStep(); }
            });
            showTourStep();
            if (state != null && state.getBoolean("tour_notice")) {
                showAutomationSettings();
                ScrollView notice = tourNotice.getWindow().getDecorView().findViewWithTag("click_dialog_body_scroll");
                notice.post(() -> notice.scrollTo(0, state.getBoolean("tour_notice_read")
                        ? notice.getChildAt(0).getHeight() : state.getInt("tour_notice_y")));
            }
        }
        root.requestApplyInsets();
    }

    private void showAutomationSettings() {
        if (tourNotice != null) return;
        boolean enabled = automationSettings.isEnabled();
        boolean teaching = coach != null;
        LinearLayout content = vertical();
        int intro = enabled ? R.string.tour_auto_read_enabled : R.string.tour_auto_read;
        int done = enabled ? R.string.tour_auto_ready_enabled : R.string.tour_auto_ready;
        add(content, dialogNotice(MenuIconView.Kind.AUTOMATION,
                enabled ? "지금은 자동 모드가 켜져 있어요" : "지금은 자동 모드가 꺼져 있어요"), 0);
        if (!teaching && !enabled) add(content, text("안내를 끝까지 읽고 자동 모드를 켜주세요.", 13, SECONDARY, regular), 14);
        add(content, dialogSection("자동 모드를 켜면",
                "요청을 확인하고 시작하면 지원되는 앱 열기, 메뉴 이동, 스크롤과 설정 조절을 도와드려요.",
                "직접 조작이 필요한 단계는 따로 안내해요."), 22);
        add(content, dialogSection("자동 모드를 끄면",
                "눌러야 할 곳을 안내해 드려요. 안내에 따라 직접 조작해 주세요."), 22);
        add(content, dialogSection("개인정보를 보호해요",
                "화면 속 비밀번호·인증번호 같은 민감한 입력 내용은 서버로 보내지 않아요.",
                "화면을 확인할 때는 작업 안내에 필요한 버튼·메뉴 이름과 위치 등을 사용해요.",
                "말하거나 쓰는 내용에도 비밀번호·인증번호·결제정보를 넣지 말아주세요."), 22);
        add(content, dialogSection("위험한 작업 앞에서는 멈춰요",
                "결제·송금·삭제·초기화처럼 중요한 작업은 자동으로 누르지 않아요.",
                "개인정보 입력이 필요하거나 안전하게 진행할 수 없는 화면에서는 자동 조작을 멈추고 알려드려요."), 22);
        add(content, dialogSection("기억해 주세요",
                "진행 중에도 설정 화면 위의 스위치로 끌 수 있어요.",
                "결제·전화 걸기·메시지 보내기는 자동으로 실행하지 않아요."), 22);
        if (!teaching) {
            ClickDialog.show(this, "자동으로 도와주기", content,
                    enabled ? "자동 모드 끄기" : "자동 모드 켜기",
                    () -> applyAutomationSetting(!enabled), "닫기", !enabled);
            return;
        }
        TextView readingStatus = text(getString(intro), 15, INK, medium);
        readingStatus.setId(R.id.tutorial_title);
        readTourNotice = false;
        coach.setVisibility(View.INVISIBLE);
        coach.status(getString(intro), true);
        tourNotice = ClickDialog.show(this, "자동으로 도와주기", content,
                enabled ? "계속" : "자동 모드 켜기", enabled ? null : () -> applyAutomationSetting(true), "닫기", true, clueNotice(readingStatus), () -> {
                    readTourNotice = true;
                    readingStatus.setText(done);
                    coach.status(getString(done), true);
                });
        tourNotice.setOnDismissListener(dialog -> {
            tourNotice = null;
            if (isFinishing() || isChangingConfigurations()) return;
            if (readTourNotice) { setResult(RESULT_OK); finish(); }
            else showTourStep();
        });
    }

    private View clueNotice(TextView bubble) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView penguin = new android.widget.ImageView(this);
        penguin.setImageResource(com.click.designpreview.R.drawable.clue3_tutorial);
        penguin.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(penguin, new LinearLayout.LayoutParams(dp(48), dp(62)));
        bubble.setBackground(new com.click.ui.overlay.SpeechBubbleDrawable(0xFFEEF5FF,
                getResources().getDisplayMetrics().density, false));
        bubble.setPadding(dp(24), dp(12), dp(10), dp(12));
        row.addView(bubble, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void applyAutomationSetting(boolean enabled) {
        boolean changed = automationSettings.isEnabled() != enabled;
        automationSettings.setEnabled(enabled);
        syncAutomationToggle();
        if (changed) ClickOverlayService.onAutomationSettingChanged();
    }

    private void syncAutomationToggle() {
        syncingAutomationToggle = true;
        automationToggle.setChecked(automationSettings.isEnabled());
        syncingAutomationToggle = false;
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

    private void shareCandidateDiagnostics() {
        try {
            java.io.File file = new com.click.data.local.CandidateDiagnosticLog(this).export();
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".diagnostic-files", file);
            Intent share = new Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(android.content.ClipData.newRawUri("진단 자료", uri));
            startActivity(Intent.createChooser(share, "테스트 진단 자료 공유"));
        } catch (java.io.FileNotFoundException empty) {
            Toast.makeText(this, "아직 진단 자료가 없어요. 문제가 발생한 안내를 다시 실행해 주세요.", Toast.LENGTH_LONG).show();
        } catch (Exception unavailable) {
            Toast.makeText(this, "진단 자료를 공유하지 못했어요. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_LONG).show();
        }
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        TextView title = text("설정", 24, INK, semibold);
        title.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        title.setPadding(0, 0, dp(8), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        automationToggle = new androidx.appcompat.widget.SwitchCompat(this);
        automationToggle.setId(R.id.menu_automation_toggle);
        automationToggle.setText("자동 모드");
        automationToggle.setTextSize(ClickTextScale.size(this, 13));
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
            if (coach != null || enabled) {
                syncAutomationToggle();
                showAutomationSettings();
            } else applyAutomationSetting(false);
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

    private View addRow(LinearLayout group, MenuIconView.Kind kind, String label, String description, boolean danger,
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
        return row;
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
        boolean accessibility = PermissionStatus.isAccessibilityEnabled(this)
                && com.click.service.accessibility.ClickAccessibilityService.accessibilityWindowManager() != null;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean microphone = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        LinearLayout statuses = vertical();
        final android.app.Dialog[] dialog = {null};
        add(statuses, permissionRow("접근성", "화면을 읽고 눌러야 할 곳을 안내해요.", accessibility, () -> {
            if (dialog[0] != null) dialog[0].dismiss();
            startActivity(new Intent(this, com.click.designpreview.PermissionGuideActivity.class)
                    .putExtra(com.click.designpreview.PermissionGuideActivity.EXTRA_RETURN_TO_SETTINGS, true)
                    .putExtra(com.click.designpreview.PermissionGuideActivity.EXTRA_RECONNECT,
                            !accessibility && PermissionStatus.isAccessibilityEnabled(this)));
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

    private void showGraphConsent() {
        LinearLayout content = vertical();
        add(content, dialogSection("더 편리한 클릭을 함께 만들어요",
                "동의하면 앞으로 안내받는 앱 종류, 작업 단계와 조작 결과를 기억해 다음 안내를 개선해요.",
                "경험 기록에는 음성·검색어·비밀번호 원문과 화면 이미지를 저장하지 않아요. 버튼과 화면은 원문 대신 구조 표시로 비교해요.",
                "동의하지 않아도 안내를 이용할 수 있어요. 철회하면 서버의 경험 분석 결과를 삭제하고 새 기록 전송을 멈춰요."), 0);
        ClickDialog.show(this, "클릭 개선 참여", content,
                graphConsented ? "참여 동의 철회" : "동의하고 참여하기", () ->
                        graphConsent.set(!graphConsented, new GraphImprovementConsentGateway.Callback() {
                            @Override public void onResult(boolean consented) {
                                if (isFinishing() || isDestroyed()) return;
                                graphConsented = consented;
                                Toast.makeText(MenuActivity.this, consented ? "클릭 개선에 참여했어요." : "참여 동의를 철회했어요.", Toast.LENGTH_SHORT).show();
                            }
                            @Override public void onFailure() {
                                if (!isFinishing() && !isDestroyed()) Toast.makeText(MenuActivity.this,
                                        "변경하지 못했어요. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show();
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
        com.click.designpreview.ClueArtworkView clue = new com.click.designpreview.ClueArtworkView(this);
        clue.setImageResource(com.click.designpreview.R.drawable.clue6_goodbye);
        clue.setTag("account_goodbye_clue");
        content.addView(clue, new LinearLayout.LayoutParams(-1, dp(184)));

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
                content, delete ? "회원 탈퇴" : "로그아웃", null, "취소", delete);
        TextView confirm = dialog.findViewById(android.R.id.button1);
        View cancel = dialog.findViewById(android.R.id.button2);
        View close = dialog.getWindow().getDecorView().findViewWithTag("click_dialog_close");
        confirm.setOnClickListener(view -> {
            if (!confirm.isEnabled()) return;
            confirm.setEnabled(false);
            cancel.setEnabled(false);
            close.setEnabled(false);
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
                    close.setEnabled(true);
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
        if (coach != null) { coach.resume(); return; }
        com.click.data.local.AccessibilityOnboardingState onboarding =
                new com.click.data.local.AccessibilityOnboardingState(this);
        if (onboarding.isPending() && onboarding.returnsToSettings()
                && com.click.service.accessibility.ClickAccessibilityService.accessibilityWindowManager() != null) {
            onboarding.markSettingsReturned();
        }
        syncAutomationToggle();
        graphConsent.read(new GraphImprovementConsentGateway.Callback() {
            @Override public void onResult(boolean consented) { graphConsented = consented; }
            @Override public void onFailure() { }
        });
    }

    private void showTourStep() {
        coach.setVisibility(View.VISIBLE);
        coach.show(getString(R.string.tour_automation), com.click.designpreview.R.drawable.clue3_tutorial,
                automationToggle, automationToggle);
    }

    private void previousTourStep() { finish(); }

    @Override protected void onPause() {
        if (coach != null) coach.pause();
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (tourNotice != null) {
            out.putBoolean("tour_notice", true);
            out.putBoolean("tour_notice_read", readTourNotice);
            ScrollView notice = tourNotice.getWindow().getDecorView().findViewWithTag("click_dialog_body_scroll");
            out.putInt("tour_notice_y", notice.getScrollY());
        }
    }
    @Override protected void onDestroy() {
        if (tourNotice != null) {
            tourNotice.setOnDismissListener(null);
            tourNotice.dismiss();
            tourNotice = null;
        }
        if (coach != null) coach.close();
        if (graphConsent != null) graphConsent.close();
        if (accountActions != null) accountActions.close();
        super.onDestroy();
    }

    private void closeMenu() {
        finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    private void returnToLogin() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private TextView text(String value, int size, int color, Typeface typeface) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(ClickTextScale.size(this, size));
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
        enum Kind { CLUE, SETTINGS, SHIELD, PERSONALIZE, AUTOMATION, BOOK, ACCESSIBILITY, DOCUMENT, LOCK, LOGOUT, TRASH, BACK, NEXT }
        private final Kind kind;
        private final boolean danger;
        private final android.graphics.drawable.Drawable artwork;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        MenuIconView(Kind kind, boolean danger) {
            super(MenuActivity.this);
            this.kind = kind;
            this.danger = danger;
            int resource = 0;
            switch (kind) {
                case CLUE: resource = com.click.designpreview.R.drawable.clue2_signup; break;
                case LOGOUT: resource = com.click.designpreview.R.drawable.icon_logout; break;
                case SETTINGS: resource = com.click.designpreview.R.drawable.icon_setting; break;
                case SHIELD: resource = com.click.designpreview.R.drawable.icon_shield; break;
                case PERSONALIZE: resource = com.click.designpreview.R.drawable.icon_light; break;
                case AUTOMATION: resource = com.click.designpreview.R.drawable.icon_lightning; break;
                case BOOK: resource = com.click.designpreview.R.drawable.icon_map; break;
                case ACCESSIBILITY: resource = com.click.designpreview.R.drawable.icon_accessibility; break;
                case DOCUMENT: resource = com.click.designpreview.R.drawable.icon_checklist; break;
                case LOCK: resource = com.click.designpreview.R.drawable.icon_lock; break;
                case TRASH: resource = com.click.designpreview.R.drawable.icon_trashcan_red; break;
                default: break;
            }
            artwork = resource == 0 ? null : getDrawable(resource);

            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private void stroke(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.1f);
        }
        @Override protected void onDraw(Canvas canvas) {
            if (artwork != null) {
                // The logout PNG has more transparent padding than the other menu artwork.
                int side = Math.round(Math.min(getWidth(), getHeight()) * (kind == Kind.LOGOUT ? 1.35f : 1f));
                int left = (getWidth() - side) / 2, top = (getHeight() - side) / 2;
                artwork.setBounds(left, top, left + side, top + side);
                artwork.draw(canvas);
                return;
            }
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
