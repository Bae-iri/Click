package com.click.service.accessibility;

import android.app.Application;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class FreshScreenObservationTest {
    @Test public void missingRootNeverUsesTheLastWindowEventAsForegroundEvidence() throws Exception {
        ClickAccessibilityService service = Robolectric.buildService(ClickAccessibilityService.class).get();
        Field tracker = ClickAccessibilityService.class.getDeclaredField("screenRevisions");
        tracker.setAccessible(true); tracker.set(service, new com.click.data.snapshot.ScreenRevisionTracker("com.click"));
        Field previous = ClickAccessibilityService.class.getDeclaredField("lastForegroundPackage");
        previous.setAccessible(true);
        Method read = ClickAccessibilityService.class.getDeclaredMethod("readCurrentScreen");
        read.setAccessible(true);
        try (MockedStatic<ForegroundWindowReader> window = mockStatic(ForegroundWindowReader.class)) {
            window.when(() -> ForegroundWindowReader.readRoot(service)).thenReturn(null);
            for (String stale : new String[]{"com.android.systemui", "com.android.settings", "com.sec.android.app.launcher"}) {
                previous.set(service, stale);
                assertNull("A missing root cannot establish the current app: " + stale, read.invoke(service));
            }
        }
    }

    @Test public void rootWithoutPackageCannotBorrowAnOldPackage() throws Exception {
        ClickAccessibilityService service = Robolectric.buildService(ClickAccessibilityService.class).get();
        Field tracker = ClickAccessibilityService.class.getDeclaredField("screenRevisions");
        tracker.setAccessible(true); tracker.set(service, new com.click.data.snapshot.ScreenRevisionTracker("com.click"));
        Field previous = ClickAccessibilityService.class.getDeclaredField("lastForegroundPackage");
        previous.setAccessible(true); previous.set(service, "com.android.systemui");
        Method read = ClickAccessibilityService.class.getDeclaredMethod("readCurrentScreen");
        read.setAccessible(true);
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        try (MockedStatic<ForegroundWindowReader> window = mockStatic(ForegroundWindowReader.class)) {
            window.when(() -> ForegroundWindowReader.readRoot(service)).thenReturn(root);
            assertNull(read.invoke(service));
            verify(root).recycle();
        }
    }
}
