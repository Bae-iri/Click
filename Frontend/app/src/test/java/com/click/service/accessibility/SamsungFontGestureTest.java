package com.click.service.accessibility;

import android.accessibilityservice.GestureDescription;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.local.TouchAutomationSettings;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SamsungFontGestureTest {
    private ClickAccessibilityService service;
    private AccessibilityNodeInfo slider;
    private ScreenSnapshot screen;
    private MockedStatic<ClickAccessibilityService> calls;
    private final int[] bounds = {135, 2009, 945, 2071};
    private static final String RESOURCE = "com.android.settings:id/seekBarForFontSize";
    private void field(String name, Object value) throws Exception {
        Field f = ClickAccessibilityService.class.getDeclaredField(name);
        f.setAccessible(true); f.set(service, value);
    }
    @Before public void setup() throws Exception {
        service = spy(Robolectric.buildService(ClickAccessibilityService.class).create().get());
        field("connectedService", new WeakReference<>(service));
        field("mainHandler", new Handler(Looper.getMainLooper()));
        new TouchAutomationSettings(service).setEnabled(true);
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        slider = mock(AccessibilityNodeInfo.class);
        doReturn(root).when(service).getRootInActiveWindow();
        when(root.getChild(0)).thenReturn(slider);
        when(slider.getPackageName()).thenReturn("com.android.settings");
        when(slider.getClassName()).thenReturn("android.widget.SeekBar");
        when(slider.getViewIdResourceName()).thenReturn(RESOURCE);
        when(slider.getWindowId()).thenReturn(5);
        when(slider.isEnabled()).thenReturn(true); when(slider.isVisibleToUser()).thenReturn(true);
        when(slider.getRangeInfo()).thenReturn(AccessibilityNodeInfo.RangeInfo.obtain(0, 0, 7, 3));
        doAnswer(call -> { ((Rect) call.getArgument(0)).set(135, 2009, 945, 2071); return null; })
                .when(slider).getBoundsInScreen(any());
        doReturn(true).when(service).dispatchGesture(any(), any(), any());
        AccessibilityNodeSnapshot node = new AccessibilityNodeSnapshot("node_0_0", "node_0", true,
                "com.android.settings", null, "4", RESOURCE, "android.widget.SeekBar", "slider", bounds,
                false, true, false, false, false, 3f, 5, false, 0f, 7f);
        screen = new ScreenSnapshot("com.android.settings", "2026-10-30T00:00:00Z", 1080, 2400,
                Collections.singletonList(node), 5, true, 1.1f);
        calls = mockStatic(ClickAccessibilityService.class, CALLS_REAL_METHODS);
        calls.when(ClickAccessibilityService::freshSnapshot).thenReturn(screen);
    }
    @After public void close() throws Exception {
        calls.close(); field("connectedService", new WeakReference<>(null));
    }
    @Test public void samsungFontUsesTouchCompletionInsteadOfPreviewOnlyScrollAction() {
        assertTrue(ClickAccessibilityService.performGuidedSlider(screen, "node_0_0", bounds, 1));
        verify(service).dispatchGesture(any(GestureDescription.class), any(), any());
        verify(slider, never()).performAction(anyInt());
    }
    @Test public void replacedControlOrDisabledAutomationNeverTouchesScreen() {
        when(slider.getViewIdResourceName()).thenReturn("settings:id/brightness");
        assertFalse(ClickAccessibilityService.performGuidedSlider(screen, "node_0_0", bounds, 1));
        when(slider.getViewIdResourceName()).thenReturn(RESOURCE);
        new TouchAutomationSettings(service).setEnabled(false);
        assertFalse(ClickAccessibilityService.performGuidedSlider(screen, "node_0_0", bounds, 1));
        verify(service, never()).dispatchGesture(any(), any(), any());
    }
}
