package com.click;

import android.Manifest;
import android.app.Application;
import android.content.ComponentName;
import android.provider.Settings;
import com.click.core.permission.PermissionStatus;
import com.click.service.accessibility.ClickAccessibilityService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
public class RequiredPermissionStatusTest {
    @Test public void onlyAllThreeLiveGrantsAndAConnectedServiceAreReady() {
        var app=RuntimeEnvironment.getApplication();
        String service=new ComponentName(app,ClickAccessibilityService.class).flattenToString();
        try(var connection=mockStatic(ClickAccessibilityService.class)) {
            connection.when(ClickAccessibilityService::accessibilityWindowManager)
                .thenReturn(mock(android.view.WindowManager.class));
            for(int mask=0;mask<8;mask++) {
                Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (mask&1)!=0?service:"");
                if((mask&2)!=0)shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
                else shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);
                ShadowSettings.setCanDrawOverlays((mask&4)!=0);
                assertEquals("permission combination "+mask,mask==7,PermissionStatus.areRequiredPermissionsGranted(app));
            }
            connection.when(ClickAccessibilityService::accessibilityWindowManager).thenReturn(null);
            assertFalse(PermissionStatus.areRequiredPermissionsGranted(app));
        }
    }
}
