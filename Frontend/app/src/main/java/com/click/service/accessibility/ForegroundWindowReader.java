package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Reads the application behind Click's focused guide, without using a cached screen. */
final class ForegroundWindowReader {
    private ForegroundWindowReader() {}

    static AccessibilityNodeInfo readRoot(AccessibilityService service) {
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        List<AccessibilityWindowInfo> windows = service.getWindows();
        AccessibilityWindowInfo newlyActiveWindow = null;
        try {
            AccessibilityWindowInfo activeWindow = null;
            AccessibilityWindowInfo application = null;
            for (AccessibilityWindowInfo window : windows) {
                if (active != null && window.getId() == active.getWindowId()) activeWindow = window;
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                        && !window.isInPictureInPictureMode()
                        && (application == null || window.getLayer() > application.getLayer())) application = window;
            }
            if (active != null) {
                // The active root can change before Android updates its cached window list.
                // Resolve its own window directly so a just-focused guide is still recognized.
                if (activeWindow == null) {
                    newlyActiveWindow = active.getWindow();
                    activeWindow = newlyActiveWindow;
                }
                boolean ownRoot = service.getPackageName().contentEquals(
                        active.getPackageName() == null ? "" : active.getPackageName());
                // Android may omit this service's own overlay from the window list
                // and from getWindow(). In that case the top application is the
                // content window; it may still be a Click activity, never a lower app.
                boolean ownOverlay = ownRoot && (activeWindow != null
                        && activeWindow.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        || activeWindow == null && application != null);
                boolean keyboard = activeWindow != null
                        && activeWindow.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                // An actual Click activity or system dialog is still the foreground screen.
                // Only our overlay and the keyboard may be skipped, never another app's dialog.
                if (!ownOverlay && !keyboard) return active;
                active.recycle();
            }
            // Use only the top application. If its root is temporarily missing, do not
            // substitute an older/background app or another launcher page.
            return application == null ? null : application.getRoot();
        } finally {
            if (newlyActiveWindow != null) newlyActiveWindow.recycle();
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }
}
