package com.click.service.overlay;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        if (before == null) return false;
        AccessibilityNodeSnapshot now = find(latest, targetNodeId);
        if (now == null || !sameTarget(before, now)) return false;
        // RecyclerView reuses unnamed clickable rows at the same path and bounds.
        // The title inside the row is part of its identity, too.
        Map<String, AccessibilityNodeSnapshot> previousChildren = subtree(requested, targetNodeId);
        Map<String, AccessibilityNodeSnapshot> currentChildren = subtree(latest, targetNodeId);
        if (!previousChildren.keySet().equals(currentChildren.keySet())) return false;
        for (String id : previousChildren.keySet()) {
            if (!sameTarget(previousChildren.get(id), currentChildren.get(id))) return false;
        }
        return true;
    }

    private static boolean sameTarget(AccessibilityNodeSnapshot before, AccessibilityNodeSnapshot now) {
        return before.visibleToUser == now.visibleToUser && before.enabled == now.enabled
                && before.clickable == now.clickable && Arrays.equals(before.bounds, now.bounds)
                && before.windowId == now.windowId
                && Objects.equals(before.parentId, now.parentId)
                && Objects.equals(before.packageName, now.packageName)
                && Objects.equals(before.text, now.text)
                && (Objects.equals(before.contentDescription, now.contentDescription)
                    || numericSliderValue(before) && numericSliderValue(now)
                        && valueLabel(before.contentDescription).equals(valueLabel(now.contentDescription)))
                && Objects.equals(before.resourceId, now.resourceId)
                && Objects.equals(before.className, now.className);
    }

    private static boolean numericSliderValue(AccessibilityNodeSnapshot node) {
        // Samsung uses the current step as the SeekBar description ("3" -> "4").
        // This is a value change on the same control, not a renamed/recycled target.
        return "slider".equals(node.role) && node.progress != null && Float.isFinite(node.progress)
                && node.contentDescription != null
                && node.contentDescription.matches(".*\\d.*");
    }

    private static String valueLabel(String label) {
        return label.replaceAll("[+-]?(?:\\d+(?:[.,]\\d+)?|[.,]\\d+)\\s*%?", "#");
    }

    private static Map<String, AccessibilityNodeSnapshot> subtree(ScreenSnapshot screen, String root) {
        Map<String, List<AccessibilityNodeSnapshot>> children = new HashMap<>();
        for (AccessibilityNodeSnapshot node : screen.nodes)
            children.computeIfAbsent(node.parentId, key -> new ArrayList<>()).add(node);
        Map<String, AccessibilityNodeSnapshot> result = new HashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            List<AccessibilityNodeSnapshot> next = children.get(pending.removeFirst());
            if (next == null) continue;
            for (AccessibilityNodeSnapshot node : next) {
                if (root.equals(node.nodeId) || result.putIfAbsent(node.nodeId, node) != null) continue;
                pending.add(node.nodeId);
            }
        }
        return result;
    }

    private static AccessibilityNodeSnapshot find(ScreenSnapshot snapshot, String nodeId) {
        if (nodeId == null) return null;
        for (AccessibilityNodeSnapshot node : snapshot.nodes) if (nodeId.equals(node.nodeId)) return node;
        return null;
    }
}
