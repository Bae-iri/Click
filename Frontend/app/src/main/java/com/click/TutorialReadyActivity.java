package com.click;

import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import com.click.core.permission.PermissionStatus;
import com.click.data.local.AccessibilityOnboardingState;

/** Handles a previously saved onboarding destination after an app update. */
public final class TutorialReadyActivity extends AppCompatActivity {
    @Override protected void onResume() {
        super.onResume();
        if (PermissionStatus.areRequiredPermissionsGranted(this)) {
            new AccessibilityOnboardingState(this).markTutorialLaunched();
            startActivity(HomeActivity.tutorialIntent(this));
        } else {
            startActivity(new Intent(this, RequiredPermissionsActivity.class)
                    .putExtra(RequiredPermissionsActivity.EXTRA_DESTINATION, "tutorial"));
        }
        finish();
    }
}
