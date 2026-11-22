package com.click.service.accessibility;

import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Replays a snapshot node id ("node_0_3_1") against a live tree.
 *
 * The id was recorded from an earlier traversal; the window can drop children before the
 * replay. AccessibilityNodeInfo.getChild then throws IndexOutOfBounds instead of returning
 * null, which crashed the whole app process on a Galaxy Note20 while Settings redrew.
 * A stale path is "target not found", never a crash.
 */
final class NodePath {
    private NodePath() {}

    /** One child, or null when the index no longer exists or the node went stale. */
    static AccessibilityNodeInfo child(AccessibilityNodeInfo node, int index) {
        if (node == null || index < 0) return null;
        try {
            if (index >= node.getChildCount()) return null;
            return node.getChild(index);
        } catch (IndexOutOfBoundsException | IllegalStateException stale) {
            return null;
        }
    }

    /**
     * Walks from root along the id. Recycles intermediates (never root) and returns null on any
     * stale step. The caller owns the result and must not recycle it when it is root.
     */
    static AccessibilityNodeInfo resolve(AccessibilityNodeInfo root, String id) {
        if (root == null || id == null) return null;
        String[] path = id.split("_");
        AccessibilityNodeInfo node = root;
        for (int i = 2; i < path.length; i++) {
            int index;
            try { index = Integer.parseInt(path[i]); }
            catch (NumberFormatException invalid) { if (node != root) node.recycle(); return null; }
            AccessibilityNodeInfo next = child(node, index);
            if (node != root) node.recycle();
            node = next;
            if (node == null) return null;
        }
        return node;
    }
}
