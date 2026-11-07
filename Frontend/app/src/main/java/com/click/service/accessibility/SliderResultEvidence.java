package com.click.service.accessibility;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.Objects;

/** Reidentify one native slider after a redraw without changing its original action binding. */
public final class SliderResultEvidence {
    private SliderResultEvidence() {}

    public static boolean changed(ScreenSnapshot before, ScreenSnapshot after,
                                  AccessibilityNodeSnapshot original, int direction, long actionAt) {
        if (before == null || after == null || original == null || !after.privacyMaskingReady
                || !Objects.equals(before.packageName, after.packageName)
                || after.activeWindowId == null || !valid(original)) return false;
        String setting = SettingsSliderTarget.identify(before, original);
        if (setting == null) return false;
        try {
            if (java.time.Instant.parse(after.capturedAt).toEpochMilli() <= actionAt) return false;
        } catch (RuntimeException invalidTime) { return false; }
        boolean sameWindow = Objects.equals(before.activeWindowId, after.activeWindowId);
        boolean fontRedraw = original.resourceId != null
                && (original.resourceId.endsWith(":id/seekBarForFontSize") || original.resourceId.endsWith(":id/font_size"))
                && finiteScale(before.appliedFontScale) && finiteScale(after.appliedFontScale)
                && SliderAdjustment.moved(before.appliedFontScale, after.appliedFontScale, direction);
        if (!sameWindow && !fontRedraw) return false;
        AccessibilityNodeSnapshot found = null;
        for (AccessibilityNodeSnapshot node : after.nodes) {
            boolean identity = original.resourceId != null
                    ? original.resourceId.equals(node.resourceId) : sameWindow && original.nodeId.equals(node.nodeId)
                        && java.util.Arrays.equals(original.bounds, node.bounds);
            if (!identity || node.windowId != after.activeWindowId || !Objects.equals(node.packageName, original.packageName)
                    || !Objects.equals(node.className, original.className) || !valid(node)
                    || !SettingsSliderTarget.matches(after, node, setting)
                    || !Objects.equals(original.rangeMin, node.rangeMin) || !Objects.equals(original.rangeMax, node.rangeMax)) continue;
            if (found != null) return false;
            found = node;
        }
        return found != null && SliderAdjustment.moved(original.progress, found.progress, direction);
    }

    private static boolean valid(AccessibilityNodeSnapshot node) {
        return node.enabled && node.visibleToUser && !node.sensitiveInput && "slider".equals(node.role)
                && node.progress != null && node.rangeMin != null && node.rangeMax != null
                && Float.isFinite(node.progress) && Float.isFinite(node.rangeMin) && Float.isFinite(node.rangeMax)
                && node.rangeMin < node.rangeMax && node.progress >= node.rangeMin && node.progress <= node.rangeMax;
    }

    private static boolean finiteScale(Float value) { return value != null && Float.isFinite(value) && value > 0 && value <= 10; }
}
