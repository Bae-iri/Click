package com.click.core.permission;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import com.click.service.accessibility.ClickAccessibilityService;

public final class PermissionStatus {
    private PermissionStatus() {}

    public static boolean areRequiredPermissionsGranted(Context context) {
        return isAccessibilityEnabled(context)
                && ClickAccessibilityService.accessibilityWindowManager() != null
                && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                && Settings.canDrawOverlays(context);
    }

    public static boolean isAccessibilityEnabled(Context context) {
        ComponentName expected = new ComponentName(context, ClickAccessibilityService.class);
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(enabled)) return true;
        }
        return false;
    }
}
