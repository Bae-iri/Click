package com.click.service.accessibility;

import android.app.Application;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.service.overlay.ClickOverlayService;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SnapshotSchedulingTest {
    private ServiceController<ClickAccessibilityService> controller;
    private ClickAccessibilityService service;
    private MockedStatic<ForegroundWindowReader> windows;
    private MockedStatic<ClickOverlayService> overlays;

    @Before public void connect() throws Exception {
        windows = mockStatic(ForegroundWindowReader.class);
        overlays = mockStatic(ClickOverlayService.class);
        controller = Robolectric.buildService(ClickAccessibilityService.class).create();
        service = controller.get();
        var connected = ClickAccessibilityService.class.getDeclaredMethod("onServiceConnected");
        connected.setAccessible(true);
        connected.invoke(service);
        service.onInterrupt();
    }

    @After public void disconnect() {
        controller.destroy();
        overlays.close();
        windows.close();
    }

    private void event(String packageName) {
        AccessibilityEvent event = mock(AccessibilityEvent.class);
        when(event.getEventType()).thenReturn(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        when(event.getPackageName()).thenReturn(packageName);
        service.onAccessibilityEvent(event);
    }

    private void advance(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private void reads(int count) {
        windows.verify(() -> ForegroundWindowReader.readRoot(service), times(count));
    }

    @Test public void shortBurstIsCoalescedAfter120MillisecondsOfQuiet() {
        event("com.android.settings");
        advance(60);
        event("com.android.settings");
        advance(119);
        reads(0);
        advance(1);
        reads(1);
        advance(500);
        reads(1);
    }

    @Test public void continuousEventsCannotPostponeCaptureBeyond300Milliseconds() {
        for (int i = 0; i < 6; i++) {
            event("com.android.settings");
            advance(50);
        }
        reads(1);
    }

    @Test public void explicitRefreshesShareOneReadAndCannotBeDelayedByAnEvent() {
        event("com.android.settings");
        ClickAccessibilityService.requestFreshSnapshot();
        ClickAccessibilityService.requestFreshSnapshot();
        ClickAccessibilityService.requestFreshSnapshot();
        event("com.android.settings");
        shadowOf(Looper.getMainLooper()).idle();
        reads(1);
        advance(500);
        reads(1);
    }

    @Test public void ownOverlayEventsDoNotDelayTheUnderlyingScreen() {
        event("com.android.settings");
        advance(100);
        event(service.getPackageName());
        advance(20);
        reads(1);
    }

    @Test public void interruptionCancelsTheReadAndResetsTheBurstDeadline() {
        for (int i = 0; i < 5; i++) {
            event("com.android.settings");
            advance(50);
        }
        service.onInterrupt();
        advance(100);
        reads(0);
        event("com.android.settings");
        advance(119);
        reads(0);
        advance(1);
        reads(1);
    }

    @Test public void aRootExcludedFromCollectionIsStillReleased() {
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        when(root.getPackageName()).thenReturn(service.getPackageName());
        windows.when(() -> ForegroundWindowReader.readRoot(service)).thenReturn(root);
        assertNull(ClickAccessibilityService.freshSnapshot());
        verify(root).recycle();
    }

    @Test public void aRootIsReleasedWhenCollectionFails() {
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        when(root.getPackageName()).thenThrow(new IllegalStateException("detached"));
        windows.when(() -> ForegroundWindowReader.readRoot(service)).thenReturn(root);
        assertThrows(IllegalStateException.class, ClickAccessibilityService::freshSnapshot);
        verify(root).recycle();
    }
}
