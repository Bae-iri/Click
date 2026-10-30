package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.click.data.snapshot.ScreenSnapshot;
import com.click.TutorialReadyActivity;
import com.click.data.local.AccessibilityOnboardingState;
import com.click.service.overlay.OverlayActions;
import com.click.service.overlay.ClickOverlayService;

import java.lang.ref.WeakReference;

public final class ClickAccessibilityService extends AccessibilityService {
    private static final String TAG = "ClickSnapshot";
    private static final long SNAPSHOT_DEBOUNCE_MS = 300L;
    private final AccessibilitySnapshotCollector collector = new AccessibilitySnapshotCollector();
    private final Runnable captureRunnable = this::captureCurrentScreen;
    private Handler mainHandler;
    private String lastForegroundPackage;
    private Integer lastForegroundWindow;
    private boolean touchInteraction;
    private AccessibilityNodeInfo draggedSource;
    private static WeakReference<ClickAccessibilityService> connectedService = new WeakReference<>(null);
    // Reused for the service lifetime so repeated fallbacks avoid reloading the OCR model.
    private VisionOcrEngine visionOcrEngine;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connectedService = new WeakReference<>(this);
        mainHandler = new Handler(getMainLooper());
        Log.i(TAG, "Click accessibility service connected");
        ClickOverlayService.onAccessibilityOverlayAvailable();
        AccessibilityOnboardingState onboarding = new AccessibilityOnboardingState(this);
        if (onboarding.isPending()) {
            onboarding.markTutorialLaunched();
            Intent tutorial = new Intent(this, TutorialReadyActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(tutorial);
        }
        mainHandler.postDelayed(captureRunnable, SNAPSHOT_DEBOUNCE_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Keyboard events can come from Click itself or have no package name.
            ClickOverlayService.onKeyboardWindowChanged();
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            touchInteraction = true;
            if (draggedSource != null) draggedSource.recycle();
            draggedSource = null;
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            if (touchInteraction && draggedSource != null) {
                emitAction(draggedSource, "drag_completed", event.getEventTime());
                draggedSource.recycle();
                draggedSource = null;
            }
            touchInteraction = false;
            requestFreshSnapshot();
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (isSystemNavigationEvent(event, packageName == null ? "" : packageName.toString())) {
            ClickOverlayService.collapseIfRunning();
            Intent collapse = new Intent(OverlayActions.ACTION_COLLAPSE).setPackage(getPackageName());
            sendBroadcast(collapse);
            return;
        }
        if (packageName == null || packageName.toString().equals(getPackageName())) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            ClickOverlayService.onGuidedViewClicked(packageName.toString(), event.getEventTime());
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                emitAction(source, "tap_completed", event.getEventTime());
                source.recycle();
            }
        }
        if (touchInteraction && (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED)) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && source.getRangeInfo() != null) {
                if (draggedSource != null) draggedSource.recycle();
                draggedSource = source;
            } else if (source != null) source.recycle();
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastForegroundPackage = packageName.toString();
            lastForegroundWindow = event.getWindowId();
        }
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
        if (visionOcrEngine != null) {
            visionOcrEngine.close();
            visionOcrEngine = null;
        }
        super.onDestroy();
    }

    /** Collect a new observation; resending LatestScreenSnapshot is not a fresh read. */
    public static void requestFreshSnapshot() {
        ClickAccessibilityService service = connectedService.get();
        if (service != null && service.mainHandler != null) {
            service.mainHandler.post(service.captureRunnable);
        }
    }

    public static ScreenSnapshot freshSnapshot() {
        ClickAccessibilityService service = connectedService.get();
        ScreenSnapshot fresh = service == null ? null : service.readCurrentScreen();
        if (fresh != null) LatestScreenSnapshot.set(fresh);
        return fresh;
    }

    public static void captureVision(ScreenSnapshot expected, OnDeviceVision.Callback callback) {
        ClickAccessibilityService service = connectedService.get();
        ScreenSnapshot fresh = service == null ? null : service.readCurrentScreen();
        if (fresh == null || !fresh.fingerprint().equals(expected.fingerprint())) {
            callback.onFailure("screen_changed", "화면이 바뀌었어요. 새 화면에서 다시 허용해 주세요.");
            return;
        }
        final VisionOcrEngine engine;
        try {
            if (service.visionOcrEngine == null) service.visionOcrEngine = new VisionOcrEngine();
            engine = service.visionOcrEngine;
        } catch (RuntimeException error) {
            callback.onFailure("ocr_failed", "화면 분석을 시작하지 못했어요.");
            return;
        }
        OnDeviceVision.capture(service, engine, fresh, callback);
    }

    public static WindowManager accessibilityWindowManager() {
        ClickAccessibilityService service = connectedService.get();
        return service == null ? null : service.getSystemService(WindowManager.class);
    }

    /** Only window geometry is read; keyboard text and keys are never collected here. */
    public static Rect keyboardBounds() {
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return null;
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo window : service.getWindows()) {
            try {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect keyboard = new Rect();
                    window.getBoundsInScreen(keyboard);
                    bounds.union(keyboard);
                }
            } finally {
                window.recycle();
            }
        }
        return bounds.isEmpty() ? null : bounds;
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

    /** Scroll only the settings window that the user just asked to search. */
    public static boolean performSettingsScroll(boolean forward, String expectedPackage, int expectedWindow) {
        if (!"com.android.settings".equals(expectedPackage)
                && !"com.samsung.android.settings".equals(expectedPackage)) return false;
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        try {
            if (root.getWindowId() != expectedWindow || root.getPackageName() == null
                    || !expectedPackage.contentEquals(root.getPackageName())) return false;
            return scrollVisibleList(root, forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        } finally { root.recycle(); }
    }

    /** Return only while the same Settings window is still in front. */
    public static boolean performSettingsBack(String expectedPackage, int expectedWindow) {
        if (!"com.android.settings".equals(expectedPackage)
                && !"com.samsung.android.settings".equals(expectedPackage)) return false;
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        try {
            if (root.getWindowId() != expectedWindow || root.getPackageName() == null
                    || !expectedPackage.contentEquals(root.getPackageName())) return false;
            return service.performGlobalAction(GLOBAL_ACTION_BACK);
        } finally { root.recycle(); }
    }

    private static boolean scrollVisibleList(AccessibilityNodeInfo node, int action) {
        if (!node.isVisibleToUser() || !node.isEnabled()) return false;
        if (node.isScrollable() && node.performAction(action)) return true;
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
        if (node.isScrollable()) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo result = findScrollableNode(node.getChild(index));
            if (result != null) return result;
        }
        return null;
    }

    private void captureCurrentScreen() {
        ScreenSnapshot snapshot = readCurrentScreen();
        if (snapshot == null) return;
        LatestScreenSnapshot.set(snapshot);
        sendBroadcast(new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED).setPackage(getPackageName()));
    }

    private ScreenSnapshot readCurrentScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String packageName = root == null || root.getPackageName() == null
                ? lastForegroundPackage : root.getPackageName().toString();
        if (packageName == null || packageName.equals(getPackageName())) return null;
        WindowManager windowManager = getSystemService(WindowManager.class);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            width = windowManager.getCurrentWindowMetrics().getBounds().width();
            height = windowManager.getCurrentWindowMetrics().getBounds().height();
        }
        if (root == null) return new ScreenSnapshot(packageName, java.time.Instant.now().toString(),
                width, height, java.util.Collections.emptyList(), lastForegroundWindow);
        Float appliedScale = null;
        try {
            float scale = android.provider.Settings.System.getFloat(getContentResolver(),
                    android.provider.Settings.System.FONT_SCALE);
            if (Float.isFinite(scale) && scale > 0) appliedScale = scale;
        } catch (android.provider.Settings.SettingNotFoundException | SecurityException ignored) {
            // Missing/read-denied means unverifiable, never assume a default scale.
        }
        return collector.collect(root, packageName, width, height, appliedScale);
    }

    private void emitAction(AccessibilityNodeInfo source, String action, long eventUptime) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String nodeId = findNodeId(root, source, "node_0");
        if (root != null) root.recycle();
        if (nodeId == null || source.getPackageName() == null) return;
        android.graphics.Rect rect = new android.graphics.Rect();
        source.getBoundsInScreen(rect);
        long wallTime = System.currentTimeMillis() - (android.os.SystemClock.uptimeMillis() - eventUptime);
        ClickOverlayService.onGuidedAction(action, source.getPackageName().toString(), source.getWindowId(),
                nodeId, source.getViewIdResourceName(), new int[]{rect.left, rect.top, rect.right, rect.bottom}, wallTime);
    }

    private static String findNodeId(AccessibilityNodeInfo node, AccessibilityNodeInfo target, String id) {
        if (node == null) return null;
        if (node.equals(target)) return id;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            String found = findNodeId(child, target, id + "_" + index);
            if (child != null) child.recycle();
            if (found != null) return found;
        }
        return null;
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
