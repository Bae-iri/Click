package com.click;

import android.app.Activity;
import android.os.Looper;
import android.view.WindowManager;
import com.click.core.permission.PermissionStatus;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = com.click.testing.ClickTestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppVisibilityTest {
    @Test public void appExitDoesNotRestartGuidanceStoppedOnEntry() {
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        try (MockedStatic<PermissionStatus> permissions = mockStatic(PermissionStatus.class);
             MockedStatic<ClickAccessibilityService> accessibility = mockStatic(ClickAccessibilityService.class);
             MockedStatic<ClickOverlayService> overlays = mockStatic(ClickOverlayService.class)) {
            var activity = Robolectric.buildActivity(Activity.class).create().start().resume();
            assertTrue(app.isAppForeground());
            permissions.when(() -> PermissionStatus.isAccessibilityEnabled(app)).thenReturn(true);
            accessibility.when(ClickAccessibilityService::accessibilityWindowManager).thenReturn(mock(WindowManager.class));
            activity.pause().stop();
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(app.isAppForeground());
            assertNull(shadowOf(app).getNextStartedService());
            overlays.verify(() -> ClickOverlayService.onAppVisibilityChanged(false));
            activity.destroy();
        }
    }
    @Test public void switchingInternalActivitiesNeverShowsOverlay() {
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        try (MockedStatic<ClickOverlayService> overlays = mockStatic(ClickOverlayService.class)) {
            var first = Robolectric.buildActivity(Activity.class).create().start().resume();
            first.pause().stop();
            var second = Robolectric.buildActivity(Activity.class).create().start().resume();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(app.isAppForeground());
            overlays.verify(() -> ClickOverlayService.onAppVisibilityChanged(false), never());
            second.pause().stop().destroy();
            first.destroy();
        }
    }
    @Test public void recreationKeepsOverlayHidden() {
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        try (MockedStatic<ClickOverlayService> overlays = mockStatic(ClickOverlayService.class)) {
            var activity = Robolectric.buildActivity(Activity.class).create().start().resume().visible();
            activity.recreate();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(app.isAppForeground());
            overlays.verify(() -> ClickOverlayService.onAppVisibilityChanged(false), never());
            activity.pause().stop().destroy();
        }
    }
    @Test public void missingPermissionDoesNotStartOverlayOnExit() {
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        var activity = Robolectric.buildActivity(Activity.class).create().start().resume();
        activity.pause().stop();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(app).getNextStartedService());
        activity.destroy();
    }
}
