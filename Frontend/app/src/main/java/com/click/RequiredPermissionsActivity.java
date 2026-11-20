package com.click;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.click.core.permission.PermissionStatus;
import com.click.data.local.AccessibilityOnboardingState;
import com.click.designpreview.ClickHost;
import com.click.designpreview.PermissionGuideActivity;
import com.click.designpreview.TutorialNarrator;
import com.click.ui.common.ClickUi;

/** Continues accessibility setup with microphone and overlay; readiness uses live OS grants. */
public final class RequiredPermissionsActivity extends AppCompatActivity {
    public static final String EXTRA_DESTINATION = "required_permissions_destination";
    private static final int MICROPHONE_REQUEST = 41;
    private TutorialNarrator narrator;
    private TextView title, description, progress;
    private Button action;
    private String spoken;
    private boolean navigating;
    private com.click.designpreview.AccessibilityConnectionWatcher connectionWatcher;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        narrator = new TutorialNarrator(this);
        connectionWatcher = new com.click.designpreview.AccessibilityConnectionWatcher(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new com.click.designpreview.ClickBackgroundDrawable(getResources()));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int padding = ClickUi.dp(this, 28);
        column.setPadding(padding, padding, padding, padding);
        scroll.addView(column);
        progress = ClickUi.body(this, "");
        progress.setGravity(Gravity.CENTER);
        column.addView(progress, ClickUi.wrap(16));
        title = ClickUi.title(this, "");
        title.setGravity(Gravity.CENTER);
        column.addView(title, ClickUi.wrap(20));
        description = ClickUi.body(this, "");
        description.setGravity(Gravity.CENTER);
        column.addView(description, ClickUi.wrap(24));
        action = ClickUi.primaryButton(this, "허용하기");
        column.addView(action, ClickUi.wrap(24));
        action.setOnClickListener(v -> advance());
        setContentView(scroll);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                // Leave setup without granting anything or bouncing through the finished guide.
                moveTaskToBack(true);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        // Acknowledge the service return before a permission dialog pauses this activity.
        new AccessibilityOnboardingState(this).cancelPending();
        narrator.resume();
        render();
        if (PermissionStatus.isAccessibilityEnabled(this) && !ClickHost.from(this).accessibilityConnected()) {
            connectionWatcher.start(this::render, () -> {});
        }
    }

    private boolean microphoneGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean accessibilityReady() {
        return PermissionStatus.isAccessibilityEnabled(this) && ClickHost.from(this).accessibilityConnected();
    }

    private void render() {
        if (navigating) return;
        String heading, body, label, step;
        if (!accessibilityReady()) {
            step = "필수 권한 1 / 3";
            heading = "접근성을 허용해 주세요";
            body = "화면의 버튼을 찾고 안내하려면 접근성 연결이 필요해요.\n설치된 앱에서 ‘클릭 화면 안내’를 허용해 주세요.";
            label = "접근성 설정하기";
        } else if (!microphoneGranted()) {
            step = "필수 권한 2 / 3";
            heading = "마이크를 허용해 주세요";
            body = "도움이 필요한 내용을 말로 전달하기 위한 권한이에요.\n허용하기를 누른 뒤 ‘앱 사용 중에만 허용’을 선택해 주세요.";
            label = "마이크 허용하기";
            if (getPreferences(MODE_PRIVATE).getBoolean("microphone_requested", false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                body = "권한 요청 창이 더 이상 나오지 않으면 앱 설정에서 허용할 수 있어요.\n권한 → 마이크 → ‘앱 사용 중에만 허용’을 선택하고 돌아와 주세요.";
                label = "마이크 설정 열기";
            }
        } else if (!Settings.canDrawOverlays(this)) {
            step = "필수 권한 3 / 3";
            heading = "다른 앱 위에 표시를 허용해 주세요";
            body = "다른 앱에서도 클릭의 안내를 보여드리기 위한 권한이에요.\n설정 화면에서 클릭을 선택하고 표시 허용 스위치를 켜주세요.\n설정 후 뒤로 돌아와 주세요.";
            label = "표시 권한 설정하기";
        } else {
            step = "필수 권한 3개 확인 완료";
            heading = "이제 클릭을 사용할 수 있어요!";
            body = "접근성, 마이크, 다른 앱 위에 표시가 모두 허용되었어요.";
            label = "tutorial".equals(destination()) ? "사용법 보기" : "클릭 시작하기";
        }
        title.setText(heading); description.setText(body); progress.setText(step); action.setText(label);
        String next = heading + ". " + body;
        if (!next.equals(spoken)) { spoken = next; narrator.speak(next); }
    }

    private String destination() { return getIntent().getStringExtra(EXTRA_DESTINATION); }

    private void advance() {
        if (!accessibilityReady()) {
            Intent guide = new Intent(this, PermissionGuideActivity.class);
            guide.putExtra(PermissionGuideActivity.EXTRA_ONBOARDING, "tutorial".equals(destination()));
            guide.putExtra(PermissionGuideActivity.EXTRA_RETURN_TO_SETTINGS, "settings".equals(destination()));
            startActivity(guide);
            return;
        }
        if (!microphoneGranted()) {
            boolean requested = getPreferences(MODE_PRIVATE).getBoolean("microphone_requested", false);
            if (requested && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            } else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("microphone_requested", true).apply();
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
            }
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            return;
        }
        navigating = true;
        Class<?> target = "tutorial".equals(destination()) ? TutorialActivity.class
                : "settings".equals(destination()) ? MenuActivity.class : HomeActivity.class;
        Intent next = new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (target == TutorialActivity.class) {
            next.putExtra(TutorialActivity.EXTRA_FROM_SIGN_UP, true);
            new AccessibilityOnboardingState(this).markTutorialLaunched();
        }
        startActivity(next);
        finish();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        navigating = false;
        spoken = null;
        render();
    }

    private void openSettings(String settingsAction) {
        try {
            startActivity(new Intent(settingsAction, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            description.setText("설정 화면을 열지 못했어요. 휴대폰 설정의 앱 목록에서 클릭의 권한을 확인한 뒤 돌아와 주세요.");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE_REQUEST) render();
    }
    @Override protected void onPause() { connectionWatcher.stop(); narrator.pause(); super.onPause(); }
    @Override protected void onDestroy() { narrator.close(); super.onDestroy(); }
}
