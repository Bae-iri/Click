package com.click.service.accessibility;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
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
public class LauncherScrollTest {
    private ClickAccessibilityService service;
    private AccessibilityNodeInfo root;
    private MockedStatic<ForegroundWindowReader> reader;

    private void field(String name, Object value) throws Exception {
        Field field = ClickAccessibilityService.class.getDeclaredField(name);
        field.setAccessible(true); field.set(service, value);
    }
    @Before public void setup() throws Exception {
        service = spy(Robolectric.buildService(ClickAccessibilityService.class).get());
        field("connectedService", new WeakReference<>(service));
        field("mainHandler", new Handler(Looper.getMainLooper()));
        root = mock(AccessibilityNodeInfo.class);
        when(root.getPackageName()).thenReturn("com.sec.android.app.launcher");
        when(root.getWindowId()).thenReturn(43);
        when(root.isVisibleToUser()).thenReturn(true);
        when(root.isEnabled()).thenReturn(true);
        reader = mockStatic(ForegroundWindowReader.class);
        reader.when(() -> ForegroundWindowReader.readRoot(service)).thenReturn(root);
        doReturn(List.of()).when(service).getWindows();
    }
    @After public void cleanup() throws Exception {
        reader.close(); field("connectedService", new WeakReference<>(null));
    }
    @Test public void changedWindowOrPackageNeverMoves() {
        assertFalse(ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 44));
        assertFalse(ClickAccessibilityService.swipeHomePage(true, "other.launcher", 43));
        when(root.getPackageName()).thenReturn("com.android.settings");
        assertFalse(ClickAccessibilityService.swipeHomePage(true));
        verify(root, never()).performAction(anyInt());
        verify(service, never()).dispatchGesture(any(), any(), any());
    }
    @Test public void usesVisibleNativeContainerInBothDirections() {
        AccessibilityNodeInfo hidden = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo visible = mock(AccessibilityNodeInfo.class);
        when(root.getChildCount()).thenReturn(2);
        when(root.getChild(0)).thenReturn(hidden); when(root.getChild(1)).thenReturn(visible);
        when(hidden.isScrollable()).thenReturn(true); when(hidden.isEnabled()).thenReturn(true);
        when(visible.isScrollable()).thenReturn(true); when(visible.isEnabled()).thenReturn(true);
        when(visible.isVisibleToUser()).thenReturn(true); when(visible.performAction(anyInt())).thenReturn(true);
        assertTrue(ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43));
        assertTrue(ClickAccessibilityService.swipeHomePage(false, "com.sec.android.app.launcher", 43));
        verify(visible).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        verify(visible).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        verify(hidden, never()).performAction(anyInt());
        verify(service, never()).dispatchGesture(any(), any(), any());
    }
    @Test public void doesNotSwipeThroughAnExpandedClickPanel() {
        AccessibilityWindowInfo window = mock(AccessibilityWindowInfo.class);
        AccessibilityNodeInfo overlay = mock(AccessibilityNodeInfo.class);
        when(window.getType()).thenReturn(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY);
        when(window.getRoot()).thenReturn(overlay);
        String packageName = service.getPackageName();
        when(overlay.getPackageName()).thenReturn(packageName);
        when(overlay.isVisibleToUser()).thenReturn(true);
        doAnswer(call -> { ((Rect) call.getArgument(0)).set(0, 0, 10000, 10000); return null; })
                .when(window).getBoundsInScreen(any());
        doReturn(List.of(window)).when(service).getWindows();
        assertFalse(ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43));
        verify(service, never()).dispatchGesture(any(), any(), any());
    }
}
