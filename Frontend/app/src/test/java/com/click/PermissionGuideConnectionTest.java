package com.click;

import android.app.Application;
import com.click.designpreview.ClickHost;
import com.click.designpreview.PermissionGuideActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class PermissionGuideConnectionTest {
    @Test public void enabledSettingDoesNotFinishGuideUntilServiceConnects() {
        ClickHost host = mock(ClickHost.class);
        when(host.accessibilityEnabled()).thenReturn(true);
        try (MockedStatic<ClickHost> bridge = mockStatic(ClickHost.class);
             ActivityController<PermissionGuideActivity> controller = Robolectric.buildActivity(PermissionGuideActivity.class)) {
            PermissionGuideActivity activity = controller.get();
            bridge.when(() -> ClickHost.from(activity)).thenReturn(host);
            controller.setup();
            for (int i = 0; i < 3; i++) activity.findViewById(com.click.designpreview.R.id.guide_action).performClick();
            verify(host).openAccessibilitySettings(activity);
            controller.pause().resume();
            assertFalse(activity.isFinishing());
            verify(host, never()).openPostPermissionTutorial(any());
            when(host.accessibilityConnected()).thenReturn(true);
            controller.pause().resume();
            assertTrue(activity.isFinishing());
            verify(host).openPostPermissionTutorial(activity);
        }
    }
}
