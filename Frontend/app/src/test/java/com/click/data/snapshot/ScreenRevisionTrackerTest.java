package com.click.data.snapshot;

import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenRevisionTrackerTest {
    private ScreenSnapshot screen(ScreenRevisionTracker tracker) {
        return new ScreenSnapshot("com.android.settings", "2026-10-28T00:00:00Z",
                1080, 2400, Collections.emptyList(), 42).withScreenRevision(tracker.revision());
    }

    @Test public void emptyTreeChangesAfterScrollContentNavigationAndClick() {
        ScreenRevisionTracker tracker = new ScreenRevisionTracker("com.click");
        for (int type : new int[]{AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_SELECTED}) {
            ScreenSnapshot before = screen(tracker);
            tracker.onEvent(type, "com.android.settings");
            assertNotEquals(before.fingerprint(), screen(tracker).fingerprint());
        }
    }

    @Test public void ownOverlaysAndRepeatedReadsDoNotInvalidateConsent() {
        ScreenRevisionTracker tracker = new ScreenRevisionTracker("com.click");
        ScreenSnapshot before = screen(tracker);
        tracker.onEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.click");
        tracker.onEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null);
        tracker.onEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED, "com.android.settings",
                AccessibilityEvent.WINDOWS_CHANGE_LAYER | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED);
        tracker.onEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, "com.android.settings");
        assertEquals(before.fingerprint(), screen(tracker).fingerprint());
    }

    @Test public void windowBoundsChangeInvalidatesEmptyScreen() {
        ScreenRevisionTracker tracker = new ScreenRevisionTracker("com.click");
        ScreenSnapshot before = screen(tracker);
        tracker.onEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED, "com.android.settings",
                AccessibilityEvent.WINDOWS_CHANGE_BOUNDS);
        assertNotEquals(before.fingerprint(), screen(tracker).fingerprint());
    }

    @Test public void serviceRestartHasDifferentRevisionAndPayloadCarriesRevision() throws Exception {
        ScreenSnapshot before = screen(new ScreenRevisionTracker("com.click"));
        ScreenSnapshot after = screen(new ScreenRevisionTracker("com.click"));
        assertNotEquals(before.fingerprint(), after.fingerprint());
        assertEquals(before.screenRevision, new org.json.JSONObject(before.toJson()).getString("screen_revision"));
        assertEquals(before.screenRevision, AgentVisionSnapshot.fromOcr(before, true,
                Collections.emptyList(), 1L, 1L).getString("screen_revision"));
    }
}
