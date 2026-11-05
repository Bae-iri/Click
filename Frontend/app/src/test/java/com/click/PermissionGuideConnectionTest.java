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
    @Test public void screenshotIsStaticWithoutOpeningDialogOrChangingStep() {
        try (ActivityController<PermissionGuideActivity> controller = Robolectric.buildActivity(PermissionGuideActivity.class).setup()) {
            PermissionGuideActivity activity = controller.get();
            android.view.View screenshot = activity.findViewById(com.click.designpreview.R.id.guide_screenshot);
            assertFalse(screenshot.isClickable());
            assertFalse(screenshot.performClick());
            android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(dialog == null || !dialog.isShowing());
            assertNull(activity.findViewById(com.click.designpreview.R.id.guide_step_counter));
            assertEquals("접근성 안내, 5단계 중 1단계", activity.findViewById(
                    com.click.designpreview.R.id.guide_progress_bars).getContentDescription());
            assertFalse(activity.isFinishing());
        }
    }
    @Test public void enabledSettingDoesNotFinishGuideUntilServiceConnects() {
        ClickHost host = mock(ClickHost.class);
        when(host.accessibilityEnabled()).thenReturn(true);
        try (MockedStatic<ClickHost> bridge = mockStatic(ClickHost.class);
             ActivityController<PermissionGuideActivity> controller = Robolectric.buildActivity(PermissionGuideActivity.class)) {
            PermissionGuideActivity activity = controller.get();
            bridge.when(() -> ClickHost.from(activity)).thenReturn(host);
            controller.setup();
            for (int i = 0; i < 5; i++) activity.findViewById(com.click.designpreview.R.id.guide_action).performClick();
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
