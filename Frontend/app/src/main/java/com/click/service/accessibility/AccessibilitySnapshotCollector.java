package com.click.service.accessibility;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class AccessibilitySnapshotCollector {
    /** Observation and execution must clip native bounds to the same window. */
    ScreenSnapshot collect(AccessibilityNodeInfo root, String packageName, android.content.Context context, Float fontScale) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = metrics.widthPixels, height = metrics.heightPixels;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Rect window = context.getSystemService(android.view.WindowManager.class)
                    .getCurrentWindowMetrics().getBounds();
            width = window.width();
            height = window.height();
        }
        return collect(root, packageName, width, height, fontScale);
    }

    ScreenSnapshot collect(AccessibilityNodeInfo root, String packageName, int width, int height, Float fontScale) {
        List<AccessibilityNodeSnapshot> nodes = new ArrayList<>();
        boolean[] maskReady = {true};
        if(root==null)return null;
        SnapshotTraversalBudget budget=new SnapshotTraversalBudget();
        try {
            visit(root, "node_0", null, packageName, width, height, nodes, maskReady,budget,0);
            var navigation=NativeNavigationReader.read(root,budget);
            budget.check();
            var screen=new ScreenSnapshot(packageName, Instant.now().toString(), width, height, nodes, root.getWindowId(), maskReady[0], fontScale).withNavigation(navigation);
            var drawerNavigation=NativeNavigationReader.compactDrawerNavigation(root,screen);
            return drawerNavigation==navigation?screen:screen.withNavigation(drawerNavigation);
        } catch(SnapshotTraversalBudget.Incomplete incomplete) {
            // Never expose a prefix that could omit a private field or the real target.
            return null;
        }
    }

    private void visit(AccessibilityNodeInfo node, String nodeId, String parentId, String packageName,
                       int screenWidth, int screenHeight,
                       List<AccessibilityNodeSnapshot> output, boolean[] maskReady,SnapshotTraversalBudget budget,int depth) {
        if (node == null) return;
        budget.visit(depth);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String className = string(node.getClassName());
        String role = inferRole(className, node);
        if (node.isVisibleToUser() && (node.isPassword() || node.isEditable()
                || "text_field".equals(role)) && bounds.isEmpty()) maskReady[0] = false;
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        Float progress = range == null ? null : range.getCurrent();
        boolean intersectsScreen = bounds.right > 0 && bounds.bottom > 0
                && bounds.left < screenWidth && bounds.top < screenHeight;
        if (intersectsScreen && bounds.right > bounds.left && bounds.bottom > bounds.top) {
            int left = Math.max(0, Math.min(bounds.left, screenWidth));
            int top = Math.max(0, Math.min(bounds.top, screenHeight));
            int right = Math.max(left, Math.min(bounds.right, screenWidth));
            int bottom = Math.max(top, Math.min(bounds.bottom, screenHeight));
            output.add(new AccessibilityNodeSnapshot(
                    nodeId,
                    parentId,
                    node.isVisibleToUser(),
                    string(node.getPackageName()) == null ? packageName : string(node.getPackageName()),
                    node.isShowingHintText() ? null : string(node.getText()),
                    node.isShowingHintText() && (node.isEditable() || "text_field".equals(role))
                            ? null : string(node.getContentDescription()),
                    node.getViewIdResourceName(),
                    className,
                    role,
                    new int[]{left, top, right, bottom},
                    node.isClickable(),
                    node.isEnabled(),
                    node.isSelected(),
                    node.isScrollable(),
                    node.isCheckable(),
                    progress,
                    node.getWindowId(),
                    node.isPassword() || node.isEditable() || "text_field".equals(role),
                    range == null ? null : range.getMin(), range == null ? null : range.getMax(), node.isPassword(),
                    node.isCheckable() ? node.isChecked() : null,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(node).isHeading(),
                    searchHint(node), range != null && range.getType() == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT
            ));
        }
        int childCount = node.getChildCount();
        for (int index = 0; index < childCount; index++) {
            budget.child();
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                visit(child, nodeId + "_" + index, nodeId, packageName,
                        screenWidth, screenHeight, output, maskReady,budget,depth+1);
            } finally {
                child.recycle();
            }
        }
    }

    private static boolean searchHint(AccessibilityNodeInfo node) {
        if (!(node.isEditable() || String.valueOf(node.getClassName()).contains("EditText")) || node.isPassword()) return false;
        CharSequence hint = node.getHintText();
        if (publicSearchHint(hint, node)) return true;
        // WebView placeholders may be exposed as hint text or an accessibility
        // description instead of getHintText(). Never inspect a filled value.
        return com.click.data.snapshot.Gov24LoginPrivacy.PACKAGE.contentEquals(String.valueOf(node.getPackageName()))
                && (publicSearchHint(node.getContentDescription(), node)
                    || node.isShowingHintText() && publicSearchHint(node.getText(), node));
    }

    private static boolean publicSearchHint(CharSequence hint, AccessibilityNodeInfo node) {
        if (hint == null) return false;
        String text = hint.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return "검색".equals(text) || "search".equals(text)
                || com.click.data.snapshot.Gov24LoginPrivacy.PACKAGE.contentEquals(String.valueOf(node.getPackageName()))
                    && com.click.data.snapshot.Gov24LoginPrivacy.isPublicSearchHint(text);
    }

    private String inferRole(String className, AccessibilityNodeInfo node) {
        if (node.getRangeInfo() != null
                && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)) return "slider";
        if (className == null) return null;
        String lower = className.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("seekbar") || lower.contains("slider")) return "slider";
        if (lower.contains("radiobutton")) return "radio_button";
        if (lower.contains("button")) return "button";
        if (lower.contains("switch")) return "switch";
        if (lower.contains("checkbox")) return "checkbox";
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
