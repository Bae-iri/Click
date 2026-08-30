package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.OverlayActions;
import com.click.service.overlay.ClickOverlayService;

public final class ClickAccessibilityService extends AccessibilityService {
    private static final String TAG = "ClickSnapshot";
    private static final long SNAPSHOT_DEBOUNCE_MS = 300L;
    private final AccessibilitySnapshotCollector collector = new AccessibilitySnapshotCollector();
    private final Runnable captureRunnable = this::captureCurrentScreen;
    private Handler mainHandler;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mainHandler = new Handler(getMainLooper());
        Log.i(TAG, "Click accessibility service connected");
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
        if (packageName == null || !isSettingsPackage(packageName.toString())) return;
        if (mainHandler == null) mainHandler = new Handler(getMainLooper());
        mainHandler.removeCallbacks(captureRunnable);
        mainHandler.postDelayed(captureRunnable, SNAPSHOT_DEBOUNCE_MS);
    }

    @Override
    public void onInterrupt() {
        if (mainHandler != null) mainHandler.removeCallbacks(captureRunnable);
    }

    private void captureCurrentScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;
        String packageName = root.getPackageName().toString();
        if (!isSettingsPackage(packageName)) return;
        WindowManager windowManager = getSystemService(WindowManager.class);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            width = windowManager.getCurrentWindowMetrics().getBounds().width();
            height = windowManager.getCurrentWindowMetrics().getBounds().height();
        }
        ScreenSnapshot snapshot = collector.collect(root, packageName, width, height);
        LatestScreenSnapshot.set(snapshot);
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
