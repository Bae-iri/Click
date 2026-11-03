package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
public class ForegroundWindowReaderTest {
    private AccessibilityService service;
    @Before public void setup() {
        service = mock(AccessibilityService.class);
        when(service.getPackageName()).thenReturn("com.click");
    }
    private AccessibilityNodeInfo root(String pkg, int id) {
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        when(root.getPackageName()).thenReturn(pkg);
        when(root.getWindowId()).thenReturn(id);
        return root;
    }
    private AccessibilityWindowInfo window(int type, int layer, AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = mock(AccessibilityWindowInfo.class);
        when(window.getType()).thenReturn(type);
        when(window.getLayer()).thenReturn(layer);
        int id = root.getWindowId();
        when(window.getId()).thenReturn(id);
        when(window.getRoot()).thenReturn(root);
        return window;
    }
    @SafeVarargs private final void setWindows(List<AccessibilityWindowInfo>... states) {
        when(service.getWindows()).thenReturn(states[0], java.util.Arrays.copyOfRange(states, 1, states.length));
    }
    @Test public void focusedClickGuideReadsLauncherUnderneath() {
        AccessibilityNodeInfo guide = root("com.click", 4023), home = root("com.sec.android.app.launcher", 3953);
        when(service.getRootInActiveWindow()).thenReturn(guide);
        setWindows(List.of(
                window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, 4, guide),
                window(AccessibilityWindowInfo.TYPE_SYSTEM, 3, root("com.android.systemui", 3957)),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, home)));
        assertSame(home, ForegroundWindowReader.readRoot(service));
        verify(guide).recycle();
        verify(home, never()).recycle();
    }
    @Test public void repeatedReadsFollowNewApplicationInsteadOfCachedLauncher() {
        AccessibilityNodeInfo guide = root("com.click", 20), home = root("com.sec.android.app.launcher", 2);
        AccessibilityNodeInfo settings = root("com.android.settings", 3);
        AccessibilityWindowInfo overlay = window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, 5, guide);
        when(service.getRootInActiveWindow()).thenReturn(guide);
        setWindows(
                List.of(overlay, window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, home)),
                List.of(overlay, window(AccessibilityWindowInfo.TYPE_APPLICATION, 1, settings)));
        assertSame(home, ForegroundWindowReader.readRoot(service));
        assertSame(settings, ForegroundWindowReader.readRoot(service));
    }
    @Test public void clickActivityIsNotMistakenForFloatingGuide() {
        AccessibilityNodeInfo app = root("com.click", 4), home = root("com.sec.android.app.launcher", 2);
        when(service.getRootInActiveWindow()).thenReturn(app);
        setWindows(List.of(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, 4, app),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, home)));
        assertSame(app, ForegroundWindowReader.readRoot(service));
    }
    @Test public void activeSettingsAndSystemDialogsRemainAuthoritative() {
        for (int type : new int[]{AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_SYSTEM}) {
            AccessibilityNodeInfo current = root(type == AccessibilityWindowInfo.TYPE_SYSTEM ? "com.android.systemui" : "com.android.settings", 3);
            when(service.getRootInActiveWindow()).thenReturn(current);
            setWindows(List.of(window(type, 4, current)));
            assertSame(current, ForegroundWindowReader.readRoot(service));
        }
    }
    @Test public void keyboardContentIsNotReadAsTaskScreen() {
        AccessibilityNodeInfo keyboard = root("com.samsung.android.honeyboard", 7), settings = root("com.android.settings", 2);
        when(service.getRootInActiveWindow()).thenReturn(keyboard);
        setWindows(List.of(
                window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, 4, keyboard),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, settings)));
        assertSame(settings, ForegroundWindowReader.readRoot(service));
    }
    @Test public void missingActiveRootReadsHighestApplicationRegardlessOfListOrder() {
        AccessibilityNodeInfo home = root("com.sec.android.app.launcher", 2), settings = root("com.android.settings", 3);
        setWindows(List.of(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, home),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, 1, settings)));
        assertSame(settings, ForegroundWindowReader.readRoot(service));
    }
    @Test public void missingTopApplicationRootNeverFallsBackToBackground() {
        AccessibilityWindowInfo top = window(AccessibilityWindowInfo.TYPE_APPLICATION, 2, root("com.android.settings", 3));
        when(top.getRoot()).thenReturn(null);
        setWindows(List.of(top,
                window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, root("com.sec.android.app.launcher", 2))));
        assertNull(ForegroundWindowReader.readRoot(service));
    }
    @Test public void pictureInPictureDoesNotReplaceUnderlyingTaskScreen() {
        AccessibilityNodeInfo settings = root("com.android.settings", 3);
        AccessibilityWindowInfo video = window(AccessibilityWindowInfo.TYPE_APPLICATION, 3, root("video", 6));
        when(video.isInPictureInPictureMode()).thenReturn(true);
        setWindows(List.of(video, window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, settings)));
        assertSame(settings, ForegroundWindowReader.readRoot(service));
    }
    @Test public void newlyFocusedOverlayCanBeMissingFromWindowList() {
        AccessibilityNodeInfo guide = root("com.click", 9), settings = root("com.android.settings", 3);
        AccessibilityWindowInfo overlay = window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, 4, guide);
        when(guide.getWindow()).thenReturn(overlay);
        when(service.getRootInActiveWindow()).thenReturn(guide);
        setWindows(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, settings)));
        assertSame(settings, ForegroundWindowReader.readRoot(service));
    }
    @Test public void omittedOwnOverlayUsesTopApplicationOnly() {
        AccessibilityNodeInfo guide = root("com.click", 9), settings = root("com.android.settings", 3);
        when(service.getRootInActiveWindow()).thenReturn(guide);
        setWindows(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, settings)));
        assertSame(settings, ForegroundWindowReader.readRoot(service));
    }
    @Test public void omittedOwnWindowNeverSkipsTopClickActivity() {
        AccessibilityNodeInfo guide = root("com.click", 9), ownActivity = root("com.click", 3);
        when(service.getRootInActiveWindow()).thenReturn(guide);
        setWindows(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, 1, ownActivity),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, 0, root("com.android.settings", 2))));
        assertSame(ownActivity, ForegroundWindowReader.readRoot(service));
    }
    @Test public void unknownOwnWindowIsNotSilentlyBypassed() {
        AccessibilityNodeInfo own = root("com.click", 5);
        when(service.getRootInActiveWindow()).thenReturn(own);
        setWindows(List.of());
        assertSame(own, ForegroundWindowReader.readRoot(service));
    }
}
