package com.click.service.accessibility;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.service.overlay.ClickOverlayService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ManualSliderEventTest {
    @Test public void nativeRangeEventsReachGuideTrackerWithoutTouchExplorationEvents() {
        checkNativeRangeEvents("android.widget.SeekBar");
    }
    @Test public void genericSetProgressControlAlsoReportsNativeRangeEvents() {
        checkNativeRangeEvents("android.view.View");
    }
    private void checkNativeRangeEvents(String className) {
        ClickAccessibilityService service = spy(Robolectric.buildService(ClickAccessibilityService.class).create().get());
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo slider = mock(AccessibilityNodeInfo.class);
        doReturn(root).when(service).getRootInActiveWindow();
        when(root.getChildCount()).thenReturn(1);
        when(root.getChild(0)).thenReturn(slider);
        when(slider.getPackageName()).thenReturn("com.android.settings");
        when(slider.getClassName()).thenReturn(className);
        when(slider.getActionList()).thenReturn(java.util.Collections.singletonList(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS));
        when(slider.getViewIdResourceName()).thenReturn("settings:id/font");
        when(slider.getWindowId()).thenReturn(5);
        when(slider.getRangeInfo()).thenReturn(AccessibilityNodeInfo.RangeInfo.obtain(0, 0, 7, 3));
        doAnswer(call -> { ((Rect) call.getArgument(0)).set(135, 2009, 945, 2071); return null; })
                .when(slider).getBoundsInScreen(any());
        try (MockedStatic<ClickOverlayService> overlay = mockStatic(ClickOverlayService.class)) {
            for (int type : new int[]{AccessibilityEvent.TYPE_VIEW_SELECTED,
                    AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED}) {
                AccessibilityEvent event = mock(AccessibilityEvent.class);
                when(event.getEventType()).thenReturn(type);
                when(event.getPackageName()).thenReturn("com.android.settings");
                when(event.getEventTime()).thenReturn(SystemClock.uptimeMillis());
                when(event.getSource()).thenReturn(slider);
                service.onAccessibilityEvent(event);
            }
            overlay.verify(() -> ClickOverlayService.onGuidedRangeChanged(eq("com.android.settings"),
                    eq(5), eq("node_0_0"), eq("settings:id/font"), aryEq(new int[]{135, 2009, 945, 2071}),
                    anyLong(), eq(3f), eq(0f), eq(7f)), times(3));
        }
    }
}
