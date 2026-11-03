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
    @Test public void samsungNumericSliderDescriptionIsAValueNotAnotherControl() {
        ScreenSnapshot before = settings(samsungSlider("3", 2f));
        assertTrue(valid(before, settings(samsungSlider("4", 3f))));
        assertFalse(valid(before, settings(samsungSlider("밝기", 3f))));
    }
    @Test public void brightnessPercentageChangeKeepsIdentityButDifferentSliderLabelDoesNot() {
        ScreenSnapshot before = settings(samsungSlider("밝기, 40%", 2f));
        assertTrue(valid(before, settings(samsungSlider("밝기, 50%", 3f))));
        assertFalse(valid(before, settings(samsungSlider("음량, 50%", 3f))));
    }
    @Test public void replacedResourceCannotHideBehindAnEqualScreenFingerprint() {
        AccessibilityNodeSnapshot original = samsungSlider("3", 2f);
        AccessibilityNodeSnapshot replacement = new AccessibilityNodeSnapshot(original.nodeId, original.parentId, true,
                original.packageName, original.text, original.contentDescription, "settings:id/brightness", original.className,
                original.role, original.bounds, original.clickable, true, false, false, false, 2f, 5, false, 0f, 7f);
        assertFalse(valid(settings(original), settings(replacement)));
    }
    private static AccessibilityNodeSnapshot samsungSlider(String description, float value) {
        return new AccessibilityNodeSnapshot("node_0_3", "node_0", true, "com.android.settings",
                null, description, "com.android.settings:id/seekBarForFontSize", "android.widget.SeekBar", "slider",
                DISPLAY, false, true, false, false, false, value, 5, false, 0f, 7f);
    }
    @Test public void scrolledTargetIsStale() {
        assertFalse(valid(settings(node("node_0_3", "디스플레이", DISPLAY, null)),
                settings(node("node_0_3", "디스플레이", new int[]{0, 200, 1080, 320}, null))));
    }
    @Test public void sameNodeIdWithOtherMenuIsStale() {
        assertFalse(valid(settings(node("node_0_3", "디스플레이", DISPLAY, null)),
                settings(node("node_0_3", "잠금화면", DISPLAY, null))));
    }
    @Test public void recycledUnnamedRowCannotKeepDisplayGuideOverAccessibility() {
        AccessibilityNodeSnapshot row = new AccessibilityNodeSnapshot("node_0_3", "node_0", true,
                "com.android.settings", null, null, "android:id/row", "android.widget.LinearLayout", null,
                DISPLAY, true, true, false, false, false, null, 5);
        AccessibilityNodeSnapshot display = rowTitle("디스플레이");
        AccessibilityNodeSnapshot accessibility = rowTitle("접근성");
        assertTrue(valid(settings(row, display), settings(row, rowTitle("디스플레이"))));
        assertFalse(valid(settings(row, display), settings(row, accessibility)));
        assertFalse(valid(settings(row, display), settings(row)));
    }
    private static AccessibilityNodeSnapshot rowTitle(String title) {
        return new AccessibilityNodeSnapshot("node_0_3_0", "node_0_3", true, "com.android.settings",
                title, null, "android:id/title", "android.widget.TextView", null,
                new int[]{30, 420, 900, 480}, false, true, false, false, false, null, 5);
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
    @Test public void visionTargetNeedsSameScreenContents() {
        ScreenSnapshot requested = settings(node("node_0_3", "디스플레이", DISPLAY, null));
        assertTrue(GuideFreshness.stillValid(requested,
                settings(node("node_0_3", "디스플레이", DISPLAY, null)), "vision_1", DISPLAY, true));
        assertFalse(GuideFreshness.stillValid(requested, settings(), "vision_1", DISPLAY, true));
        assertFalse(GuideFreshness.stillValid(requested, screen("com.other", 1080, 5), "vision_1", DISPLAY, true));
    }
    @Test public void emptyTreeVisionGuideExpiresOnReportedScreenChange() {
        ScreenSnapshot requested = settings().withScreenRevision("epoch:1");
        assertTrue(GuideFreshness.stillValid(requested, settings().withScreenRevision("epoch:1"),
                "vision_1", DISPLAY, true));
        assertFalse(GuideFreshness.stillValid(requested, settings().withScreenRevision("epoch:2"),
                "vision_1", DISPLAY, true));
    }
}
