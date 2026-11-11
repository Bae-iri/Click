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
    @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    public void settingsEntryReturnsToSettingsAfterLateConnectionEvenAfterRecreation() {
        ClickHost host = mock(ClickHost.class);
        when(host.accessibilityEnabled()).thenReturn(true);
        android.content.Intent intent = new android.content.Intent(
                org.robolectric.RuntimeEnvironment.getApplication(), PermissionGuideActivity.class)
                .putExtra(PermissionGuideActivity.EXTRA_RETURN_TO_SETTINGS, true);
        try (MockedStatic<ClickHost> bridge = mockStatic(ClickHost.class);
             ActivityController<PermissionGuideActivity> controller = Robolectric.buildActivity(PermissionGuideActivity.class, intent)) {
            bridge.when(() -> ClickHost.from(any(PermissionGuideActivity.class))).thenReturn(host);
            controller.setup();
            for (int i = 0; i < 5; i++) controller.get().findViewById(com.click.designpreview.R.id.guide_action).performClick();
            controller.recreate();
            assertFalse(controller.get().isFinishing());
            when(host.accessibilityConnected()).thenReturn(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));
            assertTrue(controller.get().isFinishing());
            verify(host).returnToSettingsAfterAccessibility(controller.get());
            verify(host, never()).openPostPermissionTutorial(any());
        }
    }
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
    @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    public void lateConnectionAdvancesWithoutLeavingAndResumingGuideAgain() {
        ClickHost host = mock(ClickHost.class);
        when(host.accessibilityEnabled()).thenReturn(true);
        try (MockedStatic<ClickHost> bridge = mockStatic(ClickHost.class);
             ActivityController<PermissionGuideActivity> controller = Robolectric.buildActivity(PermissionGuideActivity.class)) {
            PermissionGuideActivity activity = controller.get();
            bridge.when(() -> ClickHost.from(activity)).thenReturn(host);
            controller.setup();
            for (int i = 0; i < 5; i++) activity.findViewById(com.click.designpreview.R.id.guide_action).performClick();
            controller.pause().resume();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));
            assertFalse(activity.isFinishing());
            when(host.accessibilityConnected()).thenReturn(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));
            assertTrue(activity.isFinishing());
            verify(host).openPostPermissionTutorial(activity);
        }
    }

}
