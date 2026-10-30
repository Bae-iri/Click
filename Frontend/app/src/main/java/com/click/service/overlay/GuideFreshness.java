package com.click.service.overlay;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;

import java.util.Arrays;
import java.util.Objects;

/**
 * Whether a guide computed from the submitted observation may still be drawn on the latest screen.
 * A new capture of the same screen stays valid; a moved, replaced or missing target does not, and
 * the guide is never re-bound to new bounds because the server matches actions to the original ones.
 */
final class GuideFreshness {
    private GuideFreshness() {}

    static boolean stillValid(ScreenSnapshot requested, ScreenSnapshot latest, String targetNodeId,
                              int[] targetBounds, boolean visionDerived) {
        if (targetBounds == null || requested == null || latest == null || requested == latest) return true;
        if (!Objects.equals(requested.packageName, latest.packageName)
                || !Objects.equals(requested.activeWindowId, latest.activeWindowId)
                || requested.screenWidth != latest.screenWidth
                || requested.screenHeight != latest.screenHeight) return false;
        // OCR has no real target node. A content/scroll/navigation event invalidates
        // its coordinates even when the AX tree is empty and the window is unchanged.
        if (visionDerived) return Objects.equals(requested.screenRevision, latest.screenRevision)
                && requested.fingerprint().equals(latest.fingerprint());
        if (requested.fingerprint().equals(latest.fingerprint())) return true;
        AccessibilityNodeSnapshot before = find(requested, targetNodeId);
        if (before == null) return true;
        AccessibilityNodeSnapshot now = find(latest, targetNodeId);
        return now != null && now.visibleToUser && Arrays.equals(before.bounds, now.bounds)
                && before.windowId == now.windowId
                && Objects.equals(before.packageName, now.packageName)
                && Objects.equals(before.text, now.text)
                && Objects.equals(before.contentDescription, now.contentDescription)
                && Objects.equals(before.resourceId, now.resourceId)
                && Objects.equals(before.className, now.className);
    }

    private static AccessibilityNodeSnapshot find(ScreenSnapshot snapshot, String nodeId) {
        if (nodeId == null) return null;
        for (AccessibilityNodeSnapshot node : snapshot.nodes) if (nodeId.equals(node.nodeId)) return node;
        return null;
    }
}
