package com.click;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.click.ui.onboarding.PermissionReadyView;

/** Welcomes the user before practicing on the home screen with Clue. */
public final class TutorialReadyActivity extends AppCompatActivity {
    private com.click.designpreview.TutorialNarrator narrator;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!com.click.core.permission.PermissionStatus.areRequiredPermissionsGranted(this)) {
            startActivity(new Intent(this, RequiredPermissionsActivity.class)
                    .putExtra(RequiredPermissionsActivity.EXTRA_DESTINATION, "tutorial"));
            finish();
            return;
        }
        narrator = new com.click.designpreview.TutorialNarrator(this);
        narrator.speak(getString(R.string.tutorial_ready_title) + " " + getString(R.string.tutorial_ready_description));
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        PermissionReadyView welcome = new PermissionReadyView(this);
        welcome.bind(getString(R.string.tutorial_ready_title), getString(R.string.tutorial_ready_description),
                getString(R.string.tutorial_ready_start), view -> {
                    view.setEnabled(false);
                    startActivity(new Intent(this, TutorialActivity.class)
                            .putExtra(TutorialActivity.EXTRA_FROM_SIGN_UP, true));
                    finish();
                });
        setContentView(welcome);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                startActivity(new Intent(TutorialReadyActivity.this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        if (!com.click.core.permission.PermissionStatus.areRequiredPermissionsGranted(this)) {
            startActivity(new Intent(this, RequiredPermissionsActivity.class)
                    .putExtra(RequiredPermissionsActivity.EXTRA_DESTINATION, "tutorial"));
            finish(); return;
        }
        com.click.data.local.AccessibilityOnboardingState onboarding =
                new com.click.data.local.AccessibilityOnboardingState(this);
        if (onboarding.isPending() && onboarding.opensTutorial()) onboarding.markTutorialLaunched();
        narrator.resume();
    }
    @Override protected void onPause() { if (narrator != null) narrator.pause(); super.onPause(); }
    @Override protected void onDestroy() { if (narrator != null) narrator.close(); super.onDestroy(); }
}
