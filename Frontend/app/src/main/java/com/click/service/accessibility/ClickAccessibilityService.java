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
import com.click.data.snapshot.VisionPrivacy;
import com.click.data.snapshot.ScreenRevisionTracker;
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
    private VisionPrivacy visionPrivacy;
    private ScreenRevisionTracker screenRevisions;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        screenRevisions = new ScreenRevisionTracker(getPackageName());
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
        if (screenRevisions == null) screenRevisions = new ScreenRevisionTracker(getPackageName());
        screenRevisions.onEvent(event.getEventType(),
                event.getPackageName() == null ? null : event.getPackageName().toString(),
                android.os.Build.VERSION.SDK_INT >= 28 ? event.getWindowChanges() : 0);
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
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                String navigationId = source.getViewIdResourceName();
                if ("com.android.systemui".contentEquals(packageName) && navigationId != null) {
                    if (navigationId.endsWith("/back") || navigationId.endsWith("/back_button"))
                        ClickOverlayService.onSystemNavigationRequested(true);
                    else if (navigationId.endsWith("/home") || navigationId.endsWith("/home_button"))
                        ClickOverlayService.onSystemNavigationRequested(false);
                }
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

    public static void captureVision(ScreenSnapshot expected, String vocabularyKey, OnDeviceVision.Callback callback) {
        captureVision(expected, vocabularyKey, "full_frame", null, callback);
    }

    public static void captureVision(ScreenSnapshot expected, String vocabularyKey, String strategy,
                                     int[] focus, OnDeviceVision.Callback callback) {
        ClickAccessibilityService service = connectedService.get();
        ScreenSnapshot fresh = service == null ? null : service.readCurrentScreen();
        if (fresh == null || !fresh.fingerprint().equals(expected.fingerprint())) {
            callback.onFailure("screen_changed", "화면이 바뀌었어요. 새 화면에서 다시 허용해 주세요.");
            return;
        }
        final VisionOcrEngine engine;
        try {
            if (service.visionPrivacy == null) {
                try (java.io.InputStream source = service.getAssets().open("vision_vocabulary.json")) {
                    service.visionPrivacy = VisionPrivacy.load(source);
                }
            }
            if (!service.visionPrivacy.supports(vocabularyKey)) {
                callback.onFailure("unsupported", "이 작업의 화면 분석 어휘가 준비되지 않았어요.");
                return;
            }
            if (service.visionOcrEngine == null) service.visionOcrEngine = new VisionOcrEngine();
            engine = service.visionOcrEngine;
        } catch (java.io.IOException | org.json.JSONException | RuntimeException error) {
            callback.onFailure("ocr_failed", "화면 분석을 시작하지 못했어요.");
            return;
        }
        OnDeviceVision.capture(service, engine, service.visionPrivacy, vocabularyKey, strategy, focus, fresh, callback);
    }

    /** Revalidate the exact live node. Failure leaves the manual guide available. */
    public static boolean performGuidedTap(ScreenSnapshot expected, String id, int[] bounds) {
        ClickAccessibilityService service = connectedService.get();
        if (service == null || !new com.click.data.local.TouchAutomationSettings(service).isEnabled()) return false;
        ScreenSnapshot latest = freshSnapshot();
        if (!TouchAutomationPolicy.sameTarget(expected, latest, id, bounds)) return false;
        com.click.data.snapshot.AccessibilityNodeSnapshot target = TouchAutomationPolicy.clickableTarget(latest, id, bounds);
        if (target == null) return false;
        AccessibilityNodeInfo node = service.getRootInActiveWindow();
        if (node == null) return false;
        try {
            if (!target.nodeId.matches("node_0(_[0-9]+)*")) return false;
            String[] path = target.nodeId.split("_");
            for (int i = 2; i < path.length; i++) {
                AccessibilityNodeInfo child = node.getChild(Integer.parseInt(path[i]));
                node.recycle(); node = child;
                if (node == null) return false;
            }
            Rect actual = new Rect(); node.getBoundsInScreen(actual);
            actual.intersect(0, 0, latest.screenWidth, latest.screenHeight);
            String label = node.getText() != null ? node.getText().toString() : node.getContentDescription() == null ? "" : node.getContentDescription().toString();
            if (!target.packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                    || node.getWindowId() != latest.activeWindowId || !node.isVisibleToUser() || !node.isEnabled()
                    || node.isCheckable() || node.isEditable() || node.isPassword()
                    || !label.equals(target.text != null ? target.text : target.contentDescription == null ? "" : target.contentDescription)
                    || !java.util.Objects.equals(node.getViewIdResourceName(), target.resourceId)
                    || !java.util.Objects.equals(node.getClassName() == null ? null : node.getClassName().toString(), target.className)
                    || !actual.equals(new Rect(target.bounds[0],target.bounds[1],target.bounds[2],target.bounds[3]))) return false;
            boolean performed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            final long actionAt = System.currentTimeMillis();
            if (performed) {
                service.verifyTapChange(latest, id, actionAt, 0);
                return true;
            }
            ScreenSnapshot current = freshSnapshot();
            if (!TouchAutomationPolicy.sameTarget(latest, current, id, bounds)
                    || !new com.click.data.local.TouchAutomationSettings(service).isEnabled()) return false;
            Path tap = new Path(); tap.moveTo((bounds[0] + bounds[2]) / 2f, (bounds[1] + bounds[3]) / 2f);
            android.accessibilityservice.GestureDescription gesture = new android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(tap, 0, 80L)).build();
            return service.dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(android.accessibilityservice.GestureDescription description) {
                    service.verifyTapChange(latest, id, actionAt, 0);
                }
                @Override public void onCancelled(android.accessibilityservice.GestureDescription description) {
                    requestFreshSnapshot();
                }
            }, service.mainHandler);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException error) { return false; }
        finally { if (node != null) node.recycle(); }
    }

    /** One native slider increment/decrement; never drag arbitrary screen coordinates. */
    public static boolean performGuidedSlider(ScreenSnapshot expected, String id, int[] bounds, int direction) {
        ClickAccessibilityService service = connectedService.get();
        if (service == null || !new com.click.data.local.TouchAutomationSettings(service).isEnabled()
                || direction != -1 && direction != 1) return false;
        ScreenSnapshot latest = freshSnapshot();
        if (!TouchAutomationPolicy.sameTarget(expected, latest, id, bounds)
                || !TouchAutomationPolicy.settingsPackage(latest.packageName) || !latest.privacyMaskingReady
                || latest.activeWindowId == null || id == null || !id.matches("node_0(_[0-9]+)*")
                || bounds == null || bounds.length != 4) return false;
        com.click.data.snapshot.AccessibilityNodeSnapshot target = null;
        for (com.click.data.snapshot.AccessibilityNodeSnapshot n : latest.nodes) {
            if (id.equals(n.nodeId) && java.util.Arrays.equals(bounds, n.bounds)) target = n;
        }
        if (target == null || target.sensitiveInput || !target.enabled || !target.visibleToUser
                || target.progress == null || target.rangeMin == null || target.rangeMax == null
                || !("slider".equals(target.role) || target.className != null
                    && (target.className.toLowerCase(java.util.Locale.ROOT).contains("seekbar")
                        || target.className.toLowerCase(java.util.Locale.ROOT).contains("slider")))) return false;
        AccessibilityNodeInfo node = service.getRootInActiveWindow();
        if (node == null) return false;
        try {
            String[] path = id.split("_");
            for (int i = 2; i < path.length; i++) {
                AccessibilityNodeInfo child = node.getChild(Integer.parseInt(path[i]));
                node.recycle(); node = child;
                if (node == null) return false;
            }
            Rect actual = new Rect(); node.getBoundsInScreen(actual);
            AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
            if (range == null || !node.isEnabled() || !node.isVisibleToUser() || node.isPassword()
                    || node.isEditable() || node.isCheckable()
                    || !latest.packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                    || !java.util.Objects.equals(target.className, node.getClassName() == null ? null : node.getClassName().toString())
                    || node.getWindowId() != latest.activeWindowId
                    || !actual.equals(new Rect(bounds[0], bounds[1], bounds[2], bounds[3]))
                    || Float.compare(range.getCurrent(), target.progress) != 0
                    || Float.compare(range.getMin(), target.rangeMin) != 0
                    || Float.compare(range.getMax(), target.rangeMax) != 0
                    || !SliderAdjustment.canMove(range.getCurrent(), range.getMin(), range.getMax(), direction)) return false;
            float before = range.getCurrent();
            boolean performed = false;
            // SET_PROGRESS is exposed by Samsung/custom controls even when native scroll is absent.
            if (node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)) {
                float next = SliderAdjustment.nextValue(before, range.getMin(), range.getMax(), direction,
                        range.getType() == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT);
                if (Float.isFinite(next) && next != before) {
                    android.os.Bundle arguments = new android.os.Bundle();
                    arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, next);
                    performed = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments);
                }
            }
            if (!performed) {
                int action = direction > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                AccessibilityNodeInfo.AccessibilityAction nativeAction = direction > 0
                        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD;
                if (node.getActionList().contains(nativeAction)) performed = node.performAction(action);
            }
            final long actionAt = System.currentTimeMillis();
            if (!performed) {
                ScreenSnapshot current = freshSnapshot();
                if (!TouchAutomationPolicy.sameTarget(latest, current, id, bounds)
                        || !new com.click.data.local.TouchAutomationSettings(service).isEnabled()) return false;
                float next = SliderAdjustment.nextValue(before, range.getMin(), range.getMax(), direction,
                        range.getType() == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT);
                float[] points = SliderAdjustment.dragPoints(bounds, before, next, range.getMin(), range.getMax());
                if (points == null) return false;
                Path drag = new Path(); drag.moveTo(points[0], points[1]); drag.lineTo(points[2], points[3]);
                android.accessibilityservice.GestureDescription gesture = new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(drag, 0, 350L)).build();
                final com.click.data.snapshot.AccessibilityNodeSnapshot original = target;
                return service.dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(android.accessibilityservice.GestureDescription description) {
                        service.verifySliderChange(latest, original, direction, before, actionAt, 0);
                    }
                    @Override public void onCancelled(android.accessibilityservice.GestureDescription description) {
                        requestFreshSnapshot();
                    }
                }, service.mainHandler);
            }
            service.verifySliderChange(latest, target, direction, before, actionAt, 0);
            return true;
        } catch (IllegalArgumentException | IllegalStateException | SecurityException error) { return false; }
        finally { if (node != null) node.recycle(); }
    }

    private void verifyTapChange(ScreenSnapshot before, String id, long actionAt, int attempt) {
        if (mainHandler == null) mainHandler = new Handler(getMainLooper());
        mainHandler.postDelayed(() -> {
            ScreenSnapshot after = freshSnapshot();
            if (after != null && !before.fingerprint().equals(after.fingerprint())) {
                for (com.click.data.snapshot.AccessibilityNodeSnapshot original : before.nodes) {
                    if (id.equals(original.nodeId)) {
                        ClickOverlayService.onVerifiedAutomaticAction("tap_completed", original.packageName,
                                original.windowId, id, original.resourceId, original.bounds, actionAt);
                        requestFreshSnapshot(); return;
                    }
                }
            }
            if (attempt < 3) verifyTapChange(before, id, actionAt, attempt + 1);
            else requestFreshSnapshot();
        }, attempt == 0 ? 150L : attempt == 1 ? 250L : attempt == 2 ? 600L : 1000L);
    }

    private void verifySliderChange(ScreenSnapshot baseline, com.click.data.snapshot.AccessibilityNodeSnapshot original,
                                    int direction, float before, long actionAt, int attempt) {
        if (mainHandler == null) mainHandler = new Handler(getMainLooper());
        mainHandler.postDelayed(() -> {
            ScreenSnapshot after = freshSnapshot();
            if (after != null && java.util.Objects.equals(after.packageName, baseline.packageName)
                    && java.util.Objects.equals(after.activeWindowId, baseline.activeWindowId)) {
                for (com.click.data.snapshot.AccessibilityNodeSnapshot changed : after.nodes) {
                    if (original.nodeId.equals(changed.nodeId) && changed.progress != null
                            && changed.visibleToUser && !changed.sensitiveInput
                            && java.util.Objects.equals(changed.resourceId, original.resourceId)
                            && java.util.Objects.equals(changed.className, original.className)
                            && java.util.Arrays.equals(changed.bounds, original.bounds)
                            && SliderAdjustment.moved(before, changed.progress, direction)) {
                        ClickOverlayService.onVerifiedAutomaticAction("drag_completed", changed.packageName, changed.windowId,
                                original.nodeId, original.resourceId, original.bounds, actionAt);
                        requestFreshSnapshot();
                        return;
                    }
                }
            }
            if (attempt < 3 && after != null && java.util.Objects.equals(after.packageName, baseline.packageName)
                    && java.util.Objects.equals(after.activeWindowId, baseline.activeWindowId))
                verifySliderChange(baseline, original, direction, before, actionAt, attempt + 1);
            else requestFreshSnapshot();
        }, attempt == 0 ? 150L : attempt == 1 ? 250L : attempt == 2 ? 600L : 1000L);
    }

    /** Recovery is always user-triggered, independent of the automation preference. */
    public static boolean performRecoveryNavigation(boolean back) {
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return false;
        boolean accepted = service.performGlobalAction(back ? GLOBAL_ACTION_BACK : GLOBAL_ACTION_HOME);
        requestFreshSnapshot();
        return accepted;
    }

    /** Only highlight a real observed system navigation control; never guess gesture coordinates. */
    public static int[] navigationButtonBounds(boolean back) {
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return null;
        for (AccessibilityWindowInfo window : service.getWindows()) {
            try {
                if (window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    int[] found = findNavigationBounds(root, back);
                    if (found != null) return found;
                } finally { root.recycle(); }
            } finally { window.recycle(); }
        }
        return null;
    }

    private static int[] findNavigationBounds(AccessibilityNodeInfo node, boolean back) {
        String id = node.getViewIdResourceName();
        String pkg = node.getPackageName() == null ? "" : node.getPackageName().toString();
        if ("com.android.systemui".equals(pkg) && node.isVisibleToUser() && node.isEnabled()
                && id != null && (id.endsWith(back ? "/back" : "/home")
                    || id.endsWith(back ? "/back_button" : "/home_button"))) {
            Rect box = new Rect(); node.getBoundsInScreen(box);
            if (!box.isEmpty()) return new int[]{box.left, box.top, box.right, box.bottom};
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { int[] found = findNavigationBounds(child, back); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
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
                width, height, java.util.Collections.emptyList(), lastForegroundWindow)
                .withScreenRevision(screenRevisions.revision());
        Float appliedScale = null;
        try {
            float scale = android.provider.Settings.System.getFloat(getContentResolver(),
                    android.provider.Settings.System.FONT_SCALE);
            if (Float.isFinite(scale) && scale > 0) appliedScale = scale;
        } catch (android.provider.Settings.SettingNotFoundException | SecurityException ignored) {
            // Missing/read-denied means unverifiable, never assume a default scale.
        }
        return collector.collect(root, packageName, width, height, appliedScale)
                .withScreenRevision(screenRevisions.revision());
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
