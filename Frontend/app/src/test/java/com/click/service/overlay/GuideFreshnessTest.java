package com.click.service.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.Arrays;
import org.junit.Test;

public class GuideFreshnessTest {
    private static final int[] DISPLAY = {0, 400, 1080, 520};

    private static AccessibilityNodeSnapshot node(String id, String text, int[] bounds, Float progress) {
        return new AccessibilityNodeSnapshot(id, "node_0", true, "com.android.settings", text, null,
                "android:id/title", "android.widget.TextView", null, bounds, true, true, false, false, false,
                progress, 5);
    }
    private static ScreenSnapshot screen(String pkg, int width, Integer window, AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot(pkg, "2026-10-27T00:00:00Z", width, 2400, Arrays.asList(nodes), window);
    }
    private static ScreenSnapshot settings(AccessibilityNodeSnapshot... nodes) {
        return screen("com.android.settings", 1080, 5, nodes);
    }
    private static boolean valid(ScreenSnapshot requested, ScreenSnapshot latest) {
        return GuideFreshness.stillValid(requested, latest, "node_0_3", DISPLAY, false);
    }

    @Test public void newCaptureOfSameScreenKeepsGuide() {
        ScreenSnapshot requested = settings(node("node_0_3", "디스플레이", DISPLAY, null));
        assertTrue(valid(requested, settings(node("node_0_3", "디스플레이", DISPLAY, null))));
        assertTrue(valid(requested, requested));
    }
    @Test public void unrelatedChangeKeepsGuide() {
        ScreenSnapshot requested = settings(node("node_0_3", "디스플레이", DISPLAY, null),
                node("node_0_4", "배터리 80%", new int[]{0, 600, 1080, 700}, null));
        assertTrue(valid(requested, settings(node("node_0_3", "디스플레이", DISPLAY, null),
                node("node_0_4", "배터리 79%", new int[]{0, 600, 1080, 700}, null))));
    }
    @Test public void sliderValueChangeKeepsGuide() {
        assertTrue(valid(settings(node("node_0_3", "글자 크기", DISPLAY, 2f)),
                settings(node("node_0_3", "글자 크기", DISPLAY, 3f))));
    }
    @Test public void scrolledTargetIsStale() {
        assertFalse(valid(settings(node("node_0_3", "디스플레이", DISPLAY, null)),
                settings(node("node_0_3", "디스플레이", new int[]{0, 200, 1080, 320}, null))));
    }
    @Test public void sameNodeIdWithOtherMenuIsStale() {
        assertFalse(valid(settings(node("node_0_3", "디스플레이", DISPLAY, null)),
                settings(node("node_0_3", "잠금화면", DISPLAY, null))));
    }
    @Test public void missingTargetAppSwitchRotationOrWindowChangeIsStale() {
        ScreenSnapshot requested = settings(node("node_0_3", "디스플레이", DISPLAY, null));
        assertFalse(valid(requested, settings(node("node_0_9", "배터리", DISPLAY, null))));
        assertFalse(valid(requested, screen("com.sec.android.app.launcher", 1080, 5)));
        assertFalse(valid(requested, screen("com.android.settings", 2400, 5, node("node_0_3", "디스플레이", DISPLAY, null))));
        assertFalse(valid(requested, screen("com.android.settings", 1080, 6, node("node_0_3", "디스플레이", DISPLAY, null))));
    }
    @Test public void guidesWithoutTargetOrSnapshotsAreNotBlocked() {
        ScreenSnapshot requested = settings(node("node_0_3", "디스플레이", DISPLAY, null));
        ScreenSnapshot moved = settings(node("node_0_3", "디스플레이", new int[]{0, 0, 10, 10}, null));
        assertTrue(GuideFreshness.stillValid(requested, moved, null, null, false));
        assertTrue(GuideFreshness.stillValid(null, moved, "node_0_3", DISPLAY, false));
        assertTrue(GuideFreshness.stillValid(requested, null, "node_0_3", DISPLAY, false));
    }
    @Test public void visionTargetOnlyNeedsSameScreenContext() {
        ScreenSnapshot requested = settings(node("node_0_3", "디스플레이", DISPLAY, null));
        assertTrue(GuideFreshness.stillValid(requested, settings(), "vision_1", DISPLAY, true));
        assertFalse(GuideFreshness.stillValid(requested, screen("com.other", 1080, 5), "vision_1", DISPLAY, true));
    }
}
