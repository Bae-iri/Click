package com.click.service.accessibility;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class AccessibilitySnapshotCollector {
    ScreenSnapshot collect(AccessibilityNodeInfo root, String packageName, int width, int height) {
        List<AccessibilityNodeSnapshot> nodes = new ArrayList<>();
        visit(root, "node_0", nodes);
        return new ScreenSnapshot(packageName, Instant.now().toString(), width, height, nodes);
    }

    private void visit(AccessibilityNodeInfo node, String nodeId, List<AccessibilityNodeSnapshot> output) {
        if (node == null) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String className = string(node.getClassName());
        output.add(new AccessibilityNodeSnapshot(
                nodeId,
                string(node.getText()),
                string(node.getContentDescription()),
                node.getViewIdResourceName(),
                className,
                inferRole(className, node),
                new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom},
                node.isClickable(),
                node.isEnabled(),
                node.isSelected(),
                node.isScrollable(),
                node.isCheckable()
        ));
        for (int index = 0; index < node.getChildCount(); index++) {
            visit(node.getChild(index), nodeId + "_" + index, output);
        }
    }

    private String inferRole(String className, AccessibilityNodeInfo node) {
        if (className == null) return null;
        String lower = className.toLowerCase();
        if (lower.contains("seekbar")) return "slider";
        if (lower.contains("button")) return "button";
        if (lower.contains("switch")) return "switch";
        if (lower.contains("checkbox")) return "checkbox";
        if (lower.contains("radiobutton")) return "radio_button";
        if (lower.contains("edittext")) return "text_field";
        if (lower.contains("list") || node.isScrollable()) return "list";
        if (node.isClickable()) return "menu_item";
        if (lower.contains("textview")) return "text";
        return "container";
    }

    private static String string(CharSequence value) {
        return value == null || value.length() == 0 ? null : value.toString();
    }
}
