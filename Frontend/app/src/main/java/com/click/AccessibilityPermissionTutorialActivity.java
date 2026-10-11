package com.click;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Compatibility entry point kept for older intents.
 *
 * Every route now uses the same accessibility guide as sign-up. This forwarder prevents the old
 * screenshot walkthrough from resurfacing from a stale task or shortcut.
 */
public final class AccessibilityPermissionTutorialActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, com.click.designpreview.PermissionGuideActivity.class));
        finish();
    }
}
