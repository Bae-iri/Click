package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.OverlayActions;
import com.click.service.overlay.ClickOverlayService;

import java.lang.ref.WeakReference;

public final class ClickAccessibilityService extends AccessibilityService {
    private static final String TAG = "ClickSnapshot";
    private static final long SNAPSHOT_DEBOUNCE_MS = 300L;
    private final AccessibilitySnapshotCollector collector = new AccessibilitySnapshotCollector();
    private final Runnable captureRunnable = this::captureCurrentScreen;
    private Handler mainHandler;
    private static WeakReference<ClickAccessibilityService> connectedService = new WeakReference<>(null);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connectedService = new WeakReference<>(this);
        mainHandler = new Handler(getMainLooper());
        Log.i(TAG, "Click accessibility service connected");
        ClickOverlayService.onAccessibilityOverlayAvailable();
        mainHandler.postDelayed(captureRunnable, SNAPSHOT_DEBOUNCE_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (isSystemNavigationEvent(event, packageName == null ? "" : packageName.toString())) {
            ClickOverlayService.collapseIfRunning();
            Intent collapse = new Intent(OverlayActions.ACTION_COLLAPSE).setPackage(getPackageName());
            sendBroadcast(collapse);
            return;
        }
        if (packageName == null || packageName.toString().equals(getPackageName())) return;
        if (mainHandler == null) mainHandler = new Handler(getMainLooper());
        mainHandler.removeCallbacks(captureRunnable);
        mainHandler.postDelayed(captureRunnable, SNAPSHOT_DEBOUNCE_MS);
    }

    @Override
    public void onInterrupt() {
        if (mainHandler != null) mainHandler.removeCallbacks(captureRunnable);
    }

    @Override
    public void onDestroy() {
        if (connectedService.get() == this) {
            connectedService.clear();
            ClickOverlayService.onAccessibilityOverlayLost();
        }
        super.onDestroy();
    }

    public static WindowManager accessibilityWindowManager() {
        ClickAccessibilityService service = connectedService.get();
        return service == null ? null : service.getSystemService(WindowManager.class);
    }

    public static boolean swipeHomePage(boolean towardNextPage) {
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo pager = findScrollableNode(root);
        if (pager != null) {
            int action = towardNextPage
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            boolean performed = pager.performAction(action);
            Log.i(TAG, "Home page accessibility scroll direction="
                    + (towardNextPage ? "forward" : "backward") + " performed=" + performed);
            if (performed) {
                if (service.mainHandler != null) {
                    service.mainHandler.postDelayed(service.captureRunnable, 650L);
                }
                return true;
            }
        }
        int width = service.getResources().getDisplayMetrics().widthPixels;
        int height = service.getResources().getDisplayMetrics().heightPixels;
        float startX = towardNextPage ? width * 0.82f : width * 0.18f;
        float endX = towardNextPage ? width * 0.18f : width * 0.82f;
        Path path = new Path();
        path.moveTo(startX, height * 0.52f);
        path.lineTo(endX, height * 0.52f);
        android.accessibilityservice.GestureDescription gesture =
                new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                                path, 0L, 420L))
                        .build();
        boolean dispatched = service.dispatchGesture(gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(android.accessibilityservice.GestureDescription gestureDescription) {
                        if (service.mainHandler != null) {
                            service.mainHandler.postDelayed(service.captureRunnable, 450L);
                        }
                    }
                }, service.mainHandler);
        Log.i(TAG, "Home page gesture fallback direction="
                + (towardNextPage ? "forward" : "backward") + " dispatched=" + dispatched);
        return dispatched;
    }

    /** Executes a scroll on the current screen's scrollable container ({@code action_type: scroll},
     * whose {@code execution_owner} is the frontend accessibility service). */
    public static boolean performScroll(boolean forward) {
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return false;
        AccessibilityNodeInfo pager = findScrollableNode(service.getRootInActiveWindow());
        if (pager == null) return false;
        int action = forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean performed = pager.performAction(action);
        Log.i(TAG, "Active window scroll forward=" + forward + " performed=" + performed);
        if (performed && service.mainHandler != null) {
            service.mainHandler.postDelayed(service.captureRunnable, 550L);
        }
        return performed;
    }

    private static AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo result = findScrollableNode(node.getChild(index));
            if (result != null) return result;
        }
        return null;
    }

    private void captureCurrentScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;
        String packageName = root.getPackageName().toString();
        if (packageName.equals(getPackageName())) return;
        WindowManager windowManager = getSystemService(WindowManager.class);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            width = windowManager.getCurrentWindowMetrics().getBounds().width();
            height = windowManager.getCurrentWindowMetrics().getBounds().height();
        }
        ScreenSnapshot snapshot = collector.collect(root, packageName, width, height);
        LatestScreenSnapshot.set(snapshot);
        sendBroadcast(new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED).setPackage(getPackageName()));
        logSnapshot(snapshot.toJson());
    }

    private void logSnapshot(String json) {
        int chunkSize = 3000;
        int total = Math.max(1, (json.length() + chunkSize - 1) / chunkSize);
        for (int index = 0; index < total; index++) {
            int start = index * chunkSize;
            int end = Math.min(json.length(), start + chunkSize);
            Log.i(TAG, "snapshot " + (index + 1) + "/" + total + " " + json.substring(start, end));
        }
    }

    private boolean isSettingsPackage(String packageName) {
        return packageName.equals("com.android.settings")
                || packageName.equals("com.samsung.android.settings")
                || packageName.endsWith(".settings");
    }

    private boolean isSystemNavigationEvent(AccessibilityEvent event, String packageName) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return false;

        boolean systemNavigationPackage = packageName.equals("com.android.systemui")
                || packageName.equals("com.sec.android.app.launcher")
                || packageName.equals("com.android.launcher3")
                || packageName.contains("launcher");
        if (!systemNavigationPackage) return false;

        StringBuilder description = new StringBuilder();
        if (event.getClassName() != null) description.append(event.getClassName()).append(' ');
        if (event.getContentDescription() != null) description.append(event.getContentDescription()).append(' ');
        for (CharSequence text : event.getText()) description.append(text).append(' ');
        String value = description.toString().toLowerCase(java.util.Locale.ROOT);
        return value.contains("recent")
                || value.contains("recents")
                || value.contains("overview")
                || value.contains("taskview")
                || value.contains("taskstack")
                || value.contains("최근 앱")
                || value.contains("최근 사용");
    }
}
