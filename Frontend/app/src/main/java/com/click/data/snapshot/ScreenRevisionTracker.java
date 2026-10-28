package com.click.data.snapshot;

import android.view.accessibility.AccessibilityEvent;
import java.util.UUID;

/** Invalidate even an empty AX observation when the underlying app reports a change. */
public final class ScreenRevisionTracker {
    private final String ownPackage;
    private final String serviceEpoch = UUID.randomUUID().toString();
    private long changes;

    public ScreenRevisionTracker(String ownPackage) {
        this.ownPackage = ownPackage;
    }

    public void onEvent(int type, String packageName) {
        onEvent(type, packageName, 0);
    }

    public void onEvent(int type, String packageName, int windowChanges) {
        // Our consent/guide overlays must not invalidate the screen they describe.
        // Package-less windows events also accompany overlay add/remove operations.
        if (packageName == null || packageName.equals(ownPackage)) return;
        // Adding a Click overlay may change the settings window's layer/focus.
        // Those changes alone do not mean its pixels or menu position changed.
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED && (windowChanges
                & (AccessibilityEvent.WINDOWS_CHANGE_BOUNDS | AccessibilityEvent.WINDOWS_CHANGE_TITLE)) == 0) return;
        switch (type) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
            case AccessibilityEvent.TYPE_VIEW_SELECTED:
                changes++;
                break;
            default:
                break;
        }
    }

    public String revision() {
        return serviceEpoch + ":" + changes;
    }
}
