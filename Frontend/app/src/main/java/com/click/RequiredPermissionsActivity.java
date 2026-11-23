package com.click;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.click.core.permission.PermissionStatus;
import com.click.data.local.AccessibilityOnboardingState;
import com.click.designpreview.ClickHost;
import com.click.designpreview.PermissionGuideActivity;
import com.click.designpreview.TutorialNarrator;
import com.click.ui.onboarding.RequiredPermissionView;

/** Continues accessibility setup with microphone and overlay; readiness uses live OS grants. */
public final class RequiredPermissionsActivity extends AppCompatActivity {
    public static final String EXTRA_DESTINATION = "required_permissions_destination";
    private static final int MICROPHONE_REQUEST = 41;
    private TutorialNarrator narrator;
    private RequiredPermissionView screen;
    private String spoken;
    private boolean navigating;
    private com.click.designpreview.AccessibilityConnectionWatcher connectionWatcher;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        narrator = new TutorialNarrator(this);
        connectionWatcher = new com.click.designpreview.AccessibilityConnectionWatcher(this);
        screen = new RequiredPermissionView(this, v -> advance());
        setContentView(screen);
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
            body = "클루가 화면에서 누를 곳을 찾아드려요.\n‘설치된 앱’에서 ‘클릭 화면 안내’를 켜주세요.";
            label = "접근성 설정하기";
        } else if (!microphoneGranted()) {
            step = "필수 권한 2 / 3";
            heading = "마이크를 허용해 주세요";
            body = "도움이 필요할 때 말로 물어볼 수 있어요.\n‘앱 사용 중에만 허용’을 선택해 주세요.";
            label = "마이크 허용하기";
            if (getPreferences(MODE_PRIVATE).getBoolean("microphone_requested", false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                body = "앱 설정에서 ‘권한’과 ‘마이크’를 차례로 눌러요.\n‘앱 사용 중에만 허용’을 선택하고 돌아와 주세요.";
                label = "마이크 설정 열기";
            }
        } else if (!Settings.canDrawOverlays(this)) {
            step = "필수 권한 3 / 3";
            heading = "다른 앱 위에 표시해 주세요";
            body = "다른 앱을 쓸 때도 클루의 안내를 볼 수 있어요.\n설정에서 ‘클릭’을 켜고 돌아와 주세요.";
            label = "표시 권한 설정하기";
        } else {
            if (!"settings".equals(destination())) { openDestination(); return; }
            step = null;
            heading = "이제 클릭을 사용할 수 있어요!";
            body = "접근성, 마이크, 다른 앱 위에 표시가 모두 허용되었어요.";
            label = "클릭 시작하기";
        }
        screen.bind(step, heading, body, label);
        narrate(heading, body);
    }

    private void narrate(String heading, String body) {
        String next = heading + ". " + body;
        if (!next.equals(spoken)) { spoken = next; narrator.speak(next); }
    }

    private String destination() { return getIntent().getStringExtra(EXTRA_DESTINATION); }

    private void advance() {
        if (navigating) return;
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
        openDestination();
    }

    private void openDestination() {
        navigating = true;
        Intent next;
        if ("tutorial".equals(destination())) {
            next = HomeActivity.tutorialIntent(this);
            new AccessibilityOnboardingState(this).markTutorialLaunched();
        } else next = new Intent(this, "settings".equals(destination()) ? MenuActivity.class : HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
            screen.showSettingsError();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE_REQUEST) render();
    }
    @Override protected void onPause() { connectionWatcher.stop(); narrator.pause(); super.onPause(); }
    @Override protected void onDestroy() { narrator.close(); super.onDestroy(); }
}
