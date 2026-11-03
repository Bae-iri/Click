package com.click.service.overlay;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuidedActionTrackerTest {
    private final int[] bounds = {20, 100, 900, 180};
    private GuidedActionTracker tracker() {
        AccessibilityNodeSnapshot target = new AccessibilityNodeSnapshot("slider", null, true,
                "com.android.settings", "Font size", null, "settings:id/font", "android.widget.SeekBar",
                "slider", bounds, true, true, false, false, false, 2f, 5);
        ScreenSnapshot snapshot = new ScreenSnapshot("com.android.settings", "2026-10-09T00:00:00Z",
                1080, 2400, Collections.singletonList(target), 5);
        GuidedActionTracker tracker = new GuidedActionTracker();
        tracker.bind("session", "episode", "guide", "drag", "slider", bounds, snapshot, 1000);
        return tracker;
    }
    private JSONObject action(GuidedActionTracker tracker, long at) {
        return tracker.record("drag_completed", "com.android.settings", 5,
                "slider", "settings:id/font", bounds, at);
    }
    @Test public void actualActionCarriesSessionEpisodeGuideAndTarget() throws Exception {
        JSONObject event = action(tracker(), 1200);
        assertEquals("session", event.getString("session_id"));
        assertEquals("episode", event.getString("episode_id"));
        assertEquals("guide", event.getString("guide_command_id"));
        assertEquals("slider", event.getString("target_node_id"));
        assertEquals(1200L, event.getLong("occurred_at_ms"));
        assertTrue(event.has("event_id"));
    }
    @Test public void otherSliderWindowBoundsOrOldActionAreNotCredited() {
        GuidedActionTracker t = tracker();
        assertNull(t.record("drag_completed", "com.android.settings", 5, "brightness", "settings:id/font", bounds, 1200));
        assertNull(t.record("drag_completed", "com.android.settings", 6, "slider", "settings:id/font", bounds, 1200));
        assertNull(t.record("drag_completed", "com.android.settings", 5, "slider", "settings:id/brightness", bounds, 1200));
        assertNull(t.record("drag_completed", "com.android.settings", 5, "slider", "settings:id/font", new int[]{0,0,5,5}, 1200));
        assertNull(action(t, 999));
        assertNotNull(action(t, 1200));
        assertNull(action(t, 1300));
    }
    @Test public void cancelClearsBindingAndLateEventsCannotResumeGuide() {
        GuidedActionTracker t = tracker(); t.clear(); assertNull(action(t, 1200));
    }
    @Test public void spokenClaimIsNotAnAccessibilityAction() {
        assertNull(tracker().record("observe_only", "com.android.settings", 5, "slider", "settings:id/font", bounds, 1200));
    }

    @Test public void childTextTapBindsToOriginalRowUsingRealAncestry() throws Exception {
        AccessibilityNodeSnapshot row = menu("row", "root", bounds, true);
        int[] small = {40, 110, 200, 150};
        AccessibilityNodeSnapshot title = menu("unrelated-id", "row", small, false);
        AccessibilityNodeSnapshot sibling = menu("row_fake_child", "root", small, false);
        ScreenSnapshot screen = new ScreenSnapshot("com.android.settings", "2026-10-09T00:00:00Z",
                1080, 2400, java.util.Arrays.asList(row, title, sibling), 5);
        GuidedActionTracker t = new GuidedActionTracker();
        t.bind("s", "e", "g", "tap", "row", bounds, screen, 1000);
        assertFalse(t.isTargetTap("com.android.settings", 5, sibling.nodeId, small));
        assertNull(t.record("tap_completed", "com.android.settings", 5, sibling.nodeId, null, small, 1200));
        JSONObject event = t.record("tap_completed", "com.android.settings", 5, title.nodeId, null, small, 1200);
        assertEquals("row", event.getString("target_node_id"));
        assertEquals(20, event.getJSONArray("target_bounds").getInt(0));
    }

    private AccessibilityNodeSnapshot menu(String id, String parent, int[] box, boolean clickable) {
        return new AccessibilityNodeSnapshot(id, parent, true, "com.android.settings", "디스플레이", null,
                null, "android.widget.TextView", "menu_item", box, clickable, true, false, false, false, null, 5);
    }
}
