package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Executes native scrolling; session budgets and direction selection belong to the overlay. */
final class NativeScrollExecutor {
    private static final String TAG = "ClickSnapshot";
    private final AccessibilityService service;
    private final Handler handler;
    private final Runnable captureSnapshot;

    NativeScrollExecutor(AccessibilityService service, Handler handler, Runnable captureSnapshot) {
        this.service = service;
        this.handler = handler;
        this.captureSnapshot = captureSnapshot;
    }

    boolean swipeHomePage(boolean towardNextPage, String expectedPackage, Integer expectedWindow) {
        AccessibilityNodeInfo root = ForegroundWindowReader.readRoot(service);
        if (root == null) return false;
        try {
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (!(pkg.contains("launcher") || pkg.equals("com.miui.home"))
                    || expectedPackage != null && !expectedPackage.equals(pkg)
                    || expectedWindow != null && root.getWindowId() != expectedWindow) return false;
            int action = towardNextPage ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            // Native containers determine their own axis: home pages move sideways,
            // while an app drawer's RecyclerView moves vertically.
            if (scrollVisibleList(root, action)) {
                if (handler != null) handler.postDelayed(captureSnapshot, 650L);
                return true;
            }
        } finally { root.recycle(); }
        int width = service.getResources().getDisplayMetrics().widthPixels;
        int height = service.getResources().getDisplayMetrics().heightPixels;
        float y = uncoveredSwipeY(height);
        if (y < 0) return false;
        Path path = new Path();
        path.moveTo(towardNextPage ? width * .82f : width * .18f, y);
        path.lineTo(towardNextPage ? width * .18f : width * .82f, y);
        android.accessibilityservice.GestureDescription gesture =
                new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 420L))
                        .build();
        return service.dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(android.accessibilityservice.GestureDescription description) {
                if (handler != null) handler.postDelayed(captureSnapshot, 450L);
            }
            @Override public void onCancelled(android.accessibilityservice.GestureDescription description) {
                if (handler != null) handler.post(captureSnapshot);
            }
        }, handler);
    }

    boolean performScroll(boolean forward) {
        AccessibilityNodeInfo pager = findScrollableNode(ForegroundWindowReader.readRoot(service));
        if (pager == null) return false;
        int action = forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        try {
            boolean performed = pager.performAction(action);
            Log.i(TAG, "Active window scroll forward=" + forward + " performed=" + performed);
            if (performed && handler != null) {
                handler.postDelayed(captureSnapshot, 550L);
            }
            return performed;
        } finally {
            pager.recycle();
        }
    }

    boolean performSettingsScroll(boolean forward, String expectedPackage, int expectedWindow) {
        if (!"com.android.settings".equals(expectedPackage)
                && !"com.samsung.android.settings".equals(expectedPackage)) return false;
        AccessibilityNodeInfo root = ForegroundWindowReader.readRoot(service);
        if (root == null) return false;
        try {
            if (root.getWindowId() != expectedWindow || root.getPackageName() == null
                    || !expectedPackage.contentEquals(root.getPackageName())) return false;
            return scrollVisibleList(root, forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        } finally { root.recycle(); }
    }

    private float uncoveredSwipeY(int height) {
        java.util.List<Rect> covered = new java.util.ArrayList<>();
        for (AccessibilityWindowInfo window : service.getWindows()) {
            try {
                if (window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (!root.isVisibleToUser() || root.getPackageName() == null
                            || !service.getPackageName().contentEquals(root.getPackageName())) continue;
                    Rect bounds = new Rect(); window.getBoundsInScreen(bounds); covered.add(bounds);
                } finally { root.recycle(); }
            } finally { window.recycle(); }
        }
        for (float fraction : new float[]{.52f, .68f, .36f, .78f}) {
            float y = height * fraction;
            boolean clear = true;
            for (Rect bounds : covered) if (y >= bounds.top - 24 && y <= bounds.bottom + 24) { clear = false; break; }
            if (clear) return y;
        }
        return -1;
    }

    private static boolean scrollVisibleList(AccessibilityNodeInfo node, int action) {
        if (!node.isVisibleToUser() || !node.isEnabled()) return false;
        // SeekBars also expose scroll actions; those change settings, not list position.
        if (node.isScrollable() && node.getRangeInfo() == null
                && !String.valueOf(node.getClassName()).toLowerCase(java.util.Locale.ROOT).contains("seekbar")
                && node.performAction(action)) return true;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { if (scrollVisibleList(child, action)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private static AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        // Ownership of the selected node passes to performScroll; release the rest here.
        if (node.isScrollable()) return node;
        try {
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo result = findScrollableNode(node.getChild(index));
                if (result != null) return result;
            }
            return null;
        } finally {
            node.recycle();
        }
    }
}
