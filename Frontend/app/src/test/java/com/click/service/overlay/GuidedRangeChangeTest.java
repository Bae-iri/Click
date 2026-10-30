package com.click.service.overlay;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuidedRangeChangeTest {
    private GuidedActionTracker thumbTracker(boolean includeTrack) {
        AccessibilityNodeSnapshot thumb = new AccessibilityNodeSnapshot("thumb", "track", true,
                "com.android.settings", null, "Media volume,", null, "android.widget.SeekBar",
                "slider", new int[]{302,691,428,817}, false, true, false, false, false, 5f, 42, false, 0f, 15f);
        AccessibilityNodeSnapshot track = new AccessibilityNodeSnapshot("track", "row", true,
                "com.android.settings", null, "Media volume", "com.android.settings:id/slider", "android.widget.SeekBar",
                "slider", new int[]{84,691,996,817}, false, true, false, false, false, null, 42);
        ScreenSnapshot screen = new ScreenSnapshot("com.android.settings", "2026-10-30T00:00:00Z",
                1080,2400, includeTrack ? java.util.Arrays.asList(track, thumb) : Collections.singletonList(thumb),42);
        GuidedActionTracker tracker = new GuidedActionTracker();
        tracker.bind("session","episode","guide","drag","thumb",thumb.bounds,screen,1000);
        return tracker;
    }

    @Test public void nativeThumbMovementInsideItsOwnTrackKeepsOriginalGuideBinding() throws Exception {
        JSONObject event = thumbTracker(true).recordRangeChange("com.android.settings",42,"thumb",null,
                new int[]{350,691,476,817},1200,6,0,15);
        assertNotNull(event);
        assertEquals(302,event.getJSONArray("target_bounds").getInt(0));
        assertEquals("thumb",event.getString("target_node_id"));
    }

    @Test public void movingThumbStillRejectsScrollOtherControlOutsideTrackAndMissingParent() {
        GuidedActionTracker tracker = thumbTracker(true);
        assertNull(tracker.recordRangeChange("com.android.settings",42,"thumb",null,new int[]{350,701,476,827},1200,6,0,15));
        assertNull(tracker.recordRangeChange("com.android.settings",42,"thumb",null,new int[]{950,691,1076,817},1200,6,0,15));
        assertNull(tracker.recordRangeChange("com.android.settings",42,"call",null,new int[]{350,691,476,817},1200,6,0,15));
        assertNull(thumbTracker(false).recordRangeChange("com.android.settings",42,"thumb",null,new int[]{350,691,476,817},1200,6,0,15));
    }
    private final int[] bounds = {135, 2009, 945, 2071};
    private GuidedActionTracker tracker() {
        AccessibilityNodeSnapshot node = new AccessibilityNodeSnapshot("slider", null, true,
                "com.android.settings", null, "3", "settings:id/font", "android.widget.SeekBar",
                "slider", bounds, false, true, false, false, false, 2f, 5, false, 0f, 7f);
        ScreenSnapshot screen = new ScreenSnapshot("com.android.settings", "2026-10-30T00:00:00Z",
                1080, 2400, Collections.singletonList(node), 5, true, 1f);
        GuidedActionTracker tracker = new GuidedActionTracker();
        tracker.bind("session", "episode", "guide", "drag", "slider", bounds, screen, 1000);
        return tracker;
    }
    private JSONObject changed(GuidedActionTracker tracker, float value) {
        return tracker.recordRangeChange("com.android.settings", 5, "slider", "settings:id/font",
                bounds, 1200, value, 0, 7);
    }
    @Test public void nativeRangeChangeWorksWithoutTouchStartOrEndAndIsCorrelatedOnce() throws Exception {
        GuidedActionTracker tracker = tracker();
        JSONObject event = changed(tracker, 3);
        assertNotNull(event);
        assertEquals("drag_completed", event.getString("action_type"));
        assertEquals("guide", event.getString("guide_command_id"));
        assertEquals("episode", event.getString("episode_id"));
        assertEquals(1200, event.getLong("occurred_at_ms"));
        assertNull(changed(tracker, 4));
    }
    @Test public void unchangedOrInvalidValueNeverConsumesTheGuide() {
        GuidedActionTracker tracker = tracker();
        assertNull(changed(tracker, 2));
        assertNull(changed(tracker, Float.NaN));
        assertNull(changed(tracker, 8));
        assertNotNull(changed(tracker, 3));
    }
    @Test public void differentControlWindowRangeOrOldEventIsNotCredited() {
        GuidedActionTracker tracker = tracker();
        assertNull(tracker.recordRangeChange("com.android.settings", 5, "brightness", "settings:id/font", bounds, 1200, 3, 0, 7));
        assertNull(tracker.recordRangeChange("com.android.settings", 6, "slider", "settings:id/font", bounds, 1200, 3, 0, 7));
        assertNull(tracker.recordRangeChange("com.android.settings", 5, "slider", "settings:id/brightness", bounds, 1200, 3, 0, 7));
        assertNull(tracker.recordRangeChange("com.android.settings", 5, "slider", "settings:id/font", bounds, 1200, 3, 0, 10));
        assertNull(tracker.recordRangeChange("com.android.settings", 5, "slider", "settings:id/font", bounds, 999, 3, 0, 7));
        assertNotNull(changed(tracker, 3));
    }
    @Test public void cancelledGuideCannotCreditLateValueEvents() {
        GuidedActionTracker tracker = tracker();
        tracker.clear();
        assertNull(changed(tracker, 3));
    }
}
