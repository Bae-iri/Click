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
import com.click.data.local.AccessibilityOnboardingState;
import com.click.service.overlay.OverlayActions;
import com.click.service.overlay.ClickOverlayService;

import java.lang.ref.WeakReference;

public final class ClickAccessibilityService extends AccessibilityService {
    private static final String TAG = "ClickSnapshot";
    private static final long SNAPSHOT_DEBOUNCE_MS = 120L;
    private static final long SNAPSHOT_MAX_WAIT_MS = 300L;
    private final AccessibilitySnapshotCollector collector = new AccessibilitySnapshotCollector();
    private final Runnable captureRunnable = this::captureCurrentScreen;
    private Handler mainHandler;
    private int onboardingReturnAttempts;
    private final Runnable onboardingReturn = this::returnFromAccessibilitySettings;
    private long snapshotBurstStartedAt = -1L;
    private long snapshotScheduledAt = -1L;
    private String lastForegroundPackage;
    private String lastForegroundClass;
    private Integer lastForegroundWindow;
    private boolean touchInteraction;
    private AccessibilityNodeInfo draggedSource;
    private static WeakReference<ClickAccessibilityService> connectedService = new WeakReference<>(null);
    // Reused for the service lifetime so repeated fallbacks avoid reloading the OCR model.
    private VisionOcrEngine visionOcrEngine;
    private VisionPrivacy visionPrivacy;
    private ScreenRevisionTracker screenRevisions;

    private DisplayTutorialGuide displayTutorial;

    public static void refreshDisplayTutorial() {
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return;
        String pkg = com.click.data.local.LiveTutorialStore.activeDisplayPackage(service);
        if (service.displayTutorial != null) {
            if (service.displayTutorial.settingsPackage.equals(pkg)) return;
            service.displayTutorial.close();
            service.displayTutorial = null;
        }
        if (pkg != null) {
            service.displayTutorial = new DisplayTutorialGuide(service, pkg);
            service.scheduleSnapshot(true);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        screenRevisions = new ScreenRevisionTracker(getPackageName());
        connectedService = new WeakReference<>(this);
        mainHandler = new Handler(getMainLooper());
        Log.i(TAG, "Click accessibility service connected");
        ClickOverlayService.onAccessibilityOverlayAvailable();
        refreshDisplayTutorial();
        mainHandler.removeCallbacks(onboardingReturn);
        onboardingReturnAttempts = 0;
        returnFromAccessibilitySettings();
        scheduleSnapshot(false);
    }

    private void returnFromAccessibilitySettings() {
        AccessibilityOnboardingState onboarding = new AccessibilityOnboardingState(this);
        if (!onboarding.isPending() || onboardingReturnAttempts >= 4) return;
        onboardingReturnAttempts++;
        Class<?> screen = com.click.RequiredPermissionsActivity.class;
        Intent destination = new Intent(this, screen)
                .putExtra(com.click.RequiredPermissionsActivity.EXTRA_DESTINATION, onboarding.opensTutorial() ? "tutorial"
                        : onboarding.returnsToSettings() ? "settings" : "home")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(destination);
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            Log.w(TAG, "Accessibility return screen not opened", unavailable);
        }
        // A launch request may race Settings' permission dialog. The destination
        // acknowledges success when resumed; retry briefly until then.
        mainHandler.postDelayed(onboardingReturn, 1000L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED){
            if(ClickOverlayService.onAppInputEdited(event))scheduleSnapshot(false);
            return;
        }
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
        if (displayTutorial != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && (packageName == null || !displayTutorial.settingsPackage.contentEquals(packageName))) {
            scheduleSnapshot(true);
        }
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
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                AccessibilityNodeInfo.RangeInfo range = source.getRangeInfo();
                String sourceClass = source.getClassName() == null ? ""
                        : source.getClassName().toString().toLowerCase(java.util.Locale.ROOT);
                if (range != null && (sourceClass.contains("seekbar") || sourceClass.contains("slider")
                        || source.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS))) {
                    // Record the native value event before a changed-screen observation is sent.
                    // Ordinary touch mode need not produce TOUCH_INTERACTION_START/END.
                    emitAction(source, "drag_completed", event.getEventTime(), range);
                }
                if (touchInteraction && range != null) {
                    if (draggedSource != null) draggedSource.recycle();
                    draggedSource = source;
                } else source.recycle();
            }
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastForegroundClass=event.getClassName()==null?null:event.getClassName().toString();
            lastForegroundPackage = packageName.toString();
            lastForegroundWindow = event.getWindowId();
        }
        scheduleSnapshot(false);
    }

    @Override
    public void onInterrupt() {
        if (mainHandler != null) mainHandler.removeCallbacks(captureRunnable);
        snapshotBurstStartedAt = -1L;
        snapshotScheduledAt = -1L;
    }

    @Override
    public void onDestroy() {
        disconnect();
        if (visionOcrEngine != null) {
            visionOcrEngine.close();
            visionOcrEngine = null;
        }
        super.onDestroy();
    }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    private void disconnect() {
        if (displayTutorial != null) {
            displayTutorial.close();
            displayTutorial = null;
        }
        if (mainHandler != null) mainHandler.removeCallbacks(onboardingReturn);
        if (mainHandler != null) mainHandler.removeCallbacks(captureRunnable);
        snapshotBurstStartedAt = -1L;
        snapshotScheduledAt = -1L;
        if (connectedService.get() == this) {
            connectedService.clear();
            ClickOverlayService.onAccessibilityOverlayLost();
        }
    }

    /** Collect a new observation; resending LatestScreenSnapshot is not a fresh read. */
    public static void requestFreshSnapshot() {
        ClickAccessibilityService service = connectedService.get();
        if (service != null && service.mainHandler != null) {
            service.scheduleSnapshot(true);
        }
    }

    private void scheduleSnapshot(boolean immediate) {
        if (mainHandler == null) mainHandler = new Handler(getMainLooper());
        long now = android.os.SystemClock.uptimeMillis();
        // A fresh read that is already due must survive later queued AX events.
        if (snapshotScheduledAt >= 0 && snapshotScheduledAt <= now) return;
        if (snapshotBurstStartedAt < 0) snapshotBurstStartedAt = now;
        long captureAt = immediate ? now : Math.min(now + SNAPSHOT_DEBOUNCE_MS,
                snapshotBurstStartedAt + SNAPSHOT_MAX_WAIT_MS);
        mainHandler.removeCallbacks(captureRunnable);
        snapshotScheduledAt = captureAt;
        mainHandler.postAtTime(captureRunnable, captureAt);
    }

    public static ScreenSnapshot freshSnapshot() {
        ClickAccessibilityService service = connectedService.get();
        ScreenSnapshot fresh = service == null ? null : service.readCurrentScreen();
        LatestScreenSnapshot.set(fresh);
        return fresh;
    }
    public static boolean performAppTask(ScreenSnapshot expected,org.json.JSONObject observation,org.json.JSONObject action,String input,String selectedPackage){
        return AppTaskExecutor.execute(connectedService.get(),expected,freshSnapshot(),observation,action,input,selectedPackage);
    }
    public static boolean appTaskInputMatches(String targetId,String expectedHash){return AppTaskExecutor.inputMatches(connectedService.get(),LatestScreenSnapshot.get(),targetId,expectedHash);}
    public static void captureAppTaskVision(AppTaskVision.Callback callback){AppTaskVision.capture(connectedService.get(),freshSnapshot(),callback);}

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
        return performNavigationTap(expected,id,bounds,null,null);
    }
    public static boolean performGuidedNavigationTap(ScreenSnapshot expected,String id,int[] bounds,String goal,String state) {
        return performNavigationTap(expected,id,bounds,goal,state);
    }
    private static boolean performNavigationTap(ScreenSnapshot expected,String id,int[] bounds,String goal,String state) {
        ClickAccessibilityService service = connectedService.get();
        if (service == null || !new com.click.data.local.TouchAutomationSettings(service).isEnabled()) return false;
        ScreenSnapshot latest = freshSnapshot();
        if (!TouchAutomationPolicy.sameTarget(expected, latest, id, bounds)) return false;
        com.click.data.snapshot.AccessibilityNodeSnapshot target = goal==null ? TouchAutomationPolicy.clickableTarget(latest,id,bounds)
                : TouchAutomationPolicy.clickableTarget(latest,id,bounds,goal,state);
        if (target == null) return false;
        boolean titleOnly=goal!=null && TouchAutomationPolicy.clickableTarget(latest,id,bounds)==null;
        int[] tapBounds=titleOnly?target.bounds:bounds;
        AccessibilityNodeInfo node = ForegroundWindowReader.readRoot(service);
        if (node == null) return false;
        try {
            if (!target.nodeId.matches("node_0(_[0-9]+)*")) return false;
            String[] path = target.nodeId.split("_");
            for (int i = 2; i < path.length; i++) {
                AccessibilityNodeInfo child = NodePath.child(node, Integer.parseInt(path[i]));
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
            boolean performed = !titleOnly && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if(com.click.data.local.CandidateDiagnosticLog.enabled())android.util.Log.i("ClickAutomation",
                "tap_dispatch method="+(titleOnly?"title_gesture":performed?"accessibility":"gesture_fallback")
                    +" native_clickable="+node.isClickable()+" bounds="+java.util.Arrays.toString(tapBounds)
                    +" "+ClickOverlayService.automaticTapOverlayGeometry());
            final long actionAt = System.currentTimeMillis();
            if (performed) {
                service.verifyTapChange(latest, id, actionAt, 0);
                return true;
            }
            ScreenSnapshot current = freshSnapshot();
            if (!TouchAutomationPolicy.sameTarget(latest, current, id, bounds)
                    || !new com.click.data.local.TouchAutomationSettings(service).isEnabled()) return false;
            if (ClickOverlayService.automaticTapPointCovered(tapBounds)) return false;
            Path tap = new Path(); tap.moveTo((tapBounds[0] + tapBounds[2]) / 2f, (tapBounds[1] + tapBounds[3]) / 2f);
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

    /** One increment/decrement of the freshly validated, explicitly guided slider. */
    public static boolean performGuidedSlider(ScreenSnapshot expected, String id, int[] bounds, int direction) {
        com.click.data.snapshot.AccessibilityNodeSnapshot selected = null;
        if (expected != null) for (com.click.data.snapshot.AccessibilityNodeSnapshot n : expected.nodes)
            if (java.util.Objects.equals(id, n.nodeId)) selected = n;
        return performGuidedSlider(expected, id, bounds, direction, SettingsSliderTarget.identify(expected, selected));
    }

    public static boolean performGuidedSlider(ScreenSnapshot expected, String id, int[] bounds, int direction, String setting) {
        return performGuidedSlider(expected,id,bounds,direction,setting,1f);
    }
    public static boolean performGuidedSlider(ScreenSnapshot expected,String id,int[] bounds,int direction,String setting,float maxFraction) {
        if(!Float.isFinite(maxFraction) || maxFraction<=0 || maxFraction>1)return false;
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
        if (target == null || !SettingsSliderTarget.matches(latest, target, setting)
                || target.sensitiveInput || !target.enabled || !target.visibleToUser
                || target.progress == null || target.rangeMin == null || target.rangeMax == null
                || !("slider".equals(target.role) || target.className != null
                    && (target.className.toLowerCase(java.util.Locale.ROOT).contains("seekbar")
                        || target.className.toLowerCase(java.util.Locale.ROOT).contains("slider")))) return false;
        AccessibilityNodeInfo node = ForegroundWindowReader.readRoot(service);
        if (node == null) return false;
        try {
            String[] path = id.split("_");
            for (int i = 2; i < path.length; i++) {
                AccessibilityNodeInfo child = NodePath.child(node, Integer.parseInt(path[i]));
                node.recycle(); node = child;
                if (node == null) return false;
            }
            Rect actual = new Rect(); node.getBoundsInScreen(actual);
            AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
            if (range == null || !node.isEnabled() || !node.isVisibleToUser() || node.isPassword()
                    || node.isEditable() || node.isCheckable()
                    || !latest.packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                    || !java.util.Objects.equals(target.className, node.getClassName() == null ? null : node.getClassName().toString())
                    || !java.util.Objects.equals(target.resourceId, node.getViewIdResourceName())
                    || node.getWindowId() != latest.activeWindowId
                    || !actual.equals(new Rect(bounds[0], bounds[1], bounds[2], bounds[3]))
                    || Float.compare(range.getCurrent(), target.progress) != 0
                    || Float.compare(range.getMin(), target.rangeMin) != 0
                    || Float.compare(range.getMax(), target.rangeMax) != 0
                    || !SliderAdjustment.canMove(range.getCurrent(), range.getMin(), range.getMax(), direction)) return false;
            if ("com.android.settings:id/seekBarForFontSize".equals(target.resourceId)) {
                // Samsung updates only the preview for ACTION_SCROLL_FORWARD/BACKWARD.
                // A real down/up on this validated control also commits FONT_SCALE.
                if (!target.resourceId.equals(node.getViewIdResourceName())
                        || range.getType() != AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT
                        || actual.width() <= actual.height()) return false;
                float next = Math.max(range.getMin(), Math.min(range.getMax(), range.getCurrent() + direction));
                if(Math.abs((double)next-range.getCurrent())/((double)range.getMax()-range.getMin())>maxFraction)return false;
                float fraction = (next - range.getMin()) / (range.getMax() - range.getMin());
                float x = Math.max(actual.left + 1, Math.min(actual.right - 1,
                        actual.left + actual.width() * fraction));
                Path touch = new Path();
                touch.moveTo(x, actual.exactCenterY());
                android.accessibilityservice.GestureDescription gesture =
                        new android.accessibilityservice.GestureDescription.Builder().addStroke(
                                new android.accessibilityservice.GestureDescription.StrokeDescription(touch, 0, 120)).build();
                final long actionAt = System.currentTimeMillis();
                final com.click.data.snapshot.AccessibilityNodeSnapshot original = target;
                return service.dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(android.accessibilityservice.GestureDescription completed) {
                        ClickOverlayService.onVerifiedAutomaticAction("drag_completed", original.packageName,
                                original.windowId, original.nodeId, original.resourceId, original.bounds, actionAt);
                        requestFreshSnapshot();
                    }
                    @Override public void onCancelled(android.accessibilityservice.GestureDescription cancelled) {
                        requestFreshSnapshot();
                    }
                }, service.mainHandler);
            }
            float before = range.getCurrent();
            // Samsung's brightness SET_PROGRESS/SCROLL actions update its preview only.
            // A bounded drag also commits the persisted device brightness.
            boolean commitByTouch = "com.android.settings:id/brightness_seekbar".equals(target.resourceId);
            boolean discreteRange = range.getType() == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT && !commitByTouch;
            float next = SliderAdjustment.nextValue(before, range.getMin(), range.getMax(), direction, discreteRange);
            if ("volume".equals(setting)) {
                android.media.AudioManager audio = service.getSystemService(android.media.AudioManager.class);
                if (audio == null) return false;
                int stream = android.media.AudioManager.STREAM_MUSIC;
                int minimum = android.os.Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(stream) : 0;
                next = SliderAdjustment.nextMediaValue(before, range.getMin(), range.getMax(), direction,
                        audio.getStreamVolume(stream), minimum, audio.getStreamMaxVolume(stream));
            }
            if (!Float.isFinite(next) || Math.abs((double)next-before)/((double)range.getMax()-range.getMin())>maxFraction) return false;
            boolean performed = false;
            // SET_PROGRESS is exposed by Samsung/custom controls even when native scroll is absent.
            if (!commitByTouch && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)) {
                if (Float.isFinite(next) && next != before) {
                    android.os.Bundle arguments = new android.os.Bundle();
                    arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, next);
                    performed = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments);
                }
            }
            if (!performed && !commitByTouch && maxFraction>=1f) {
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
            if (NavigationTapEvidence.nativeContentChanged(before,after)) {
                for (com.click.data.snapshot.AccessibilityNodeSnapshot original : before.nodes) {
                    if (id.equals(original.nodeId)) {
                        ClickOverlayService.onVerifiedAutomaticAction("tap_completed", original.packageName,
                                original.windowId, id, original.resourceId, original.bounds, actionAt);
                        requestFreshSnapshot(); return;
                    }
                }
            }
            if (attempt < 3) verifyTapChange(before, id, actionAt, attempt + 1);
            else {
                if(com.click.data.local.CandidateDiagnosticLog.enabled())android.util.Log.i("ClickAutomation","tap_effect unchanged=true checks="+(attempt+1));
                requestFreshSnapshot();
            }
        }, attempt == 0 ? 150L : attempt == 1 ? 250L : attempt == 2 ? 600L : 1000L);
    }

    private void verifySliderChange(ScreenSnapshot baseline, com.click.data.snapshot.AccessibilityNodeSnapshot original,
                                    int direction, float before, long actionAt, int attempt) {
        if (mainHandler == null) mainHandler = new Handler(getMainLooper());
        mainHandler.postDelayed(() -> {
            ScreenSnapshot after = freshSnapshot();
            if (SliderResultEvidence.changed(baseline, after, original, direction, actionAt)) {
                ClickOverlayService.onVerifiedAutomaticAction("drag_completed", original.packageName, original.windowId,
                        original.nodeId, original.resourceId, original.bounds, actionAt);
                requestFreshSnapshot();
                return;
            }
            if (attempt < 5 && (after == null || after.packageName == null
                    || java.util.Objects.equals(after.packageName, baseline.packageName)))
                verifySliderChange(baseline, original, direction, before, actionAt, attempt + 1);
            else requestFreshSnapshot();
        }, attempt == 0 ? 150L : attempt == 1 ? 250L : attempt == 2 ? 600L : attempt == 3 ? 1000L : 1500L);
    }

    /** Performs system navigation and requests evidence of the screen reached. */
    public static boolean performRecoveryNavigation(boolean back) {
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return false;
        boolean accepted = service.performGlobalAction(back ? GLOBAL_ACTION_BACK : GLOBAL_ACTION_HOME);
        android.util.Log.i("ClickNavigation","system_navigation action="+(back?"back":"home")+" accepted="+accepted);
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
        try{return findNavigationBounds(node,back,new SnapshotTraversalBudget(),0);}
        catch(SnapshotTraversalBudget.Incomplete incomplete){return null;}
    }
    private static int[] findNavigationBounds(AccessibilityNodeInfo node,boolean back,SnapshotTraversalBudget budget,int depth) {
        budget.visit(depth);
        String id = node.getViewIdResourceName();
        String pkg = node.getPackageName() == null ? "" : node.getPackageName().toString();
        if ("com.android.systemui".equals(pkg) && node.isVisibleToUser() && node.isEnabled()
                && id != null && (id.endsWith(back ? "/back" : "/home")
                    || id.endsWith(back ? "/back_button" : "/home_button"))) {
            Rect box = new Rect(); node.getBoundsInScreen(box);
            if (!box.isEmpty()) return new int[]{box.left, box.top, box.right, box.bottom};
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            budget.child();
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { int[] found = findNavigationBounds(child, back,budget,depth+1); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }

    public static Gov24LoginEntry.Target gov24LoginEntry() {
        ClickAccessibilityService service=connectedService.get();if(service==null)return null;
        AccessibilityNodeInfo root=ForegroundWindowReader.readRoot(service);
        try{return Gov24LoginEntry.inspect(root);}finally{if(root!=null)root.recycle();}
    }
    public static boolean revealGov24LoginEntry() {
        ClickAccessibilityService service=connectedService.get();if(service==null)return false;
        AccessibilityNodeInfo root=ForegroundWindowReader.readRoot(service);
        try{return Gov24LoginEntry.reveal(root);}finally{if(root!=null)root.recycle();}
    }
    public static boolean revealGov24Service(String goalType) {
        ClickAccessibilityService service=connectedService.get();if(service==null)return false;
        AccessibilityNodeInfo root=ForegroundWindowReader.readRoot(service);
        try {
            if(root==null)return false;
            var screen=service.collector.collect(root,com.click.data.snapshot.Gov24LoginPrivacy.PACKAGE,service,null);
            return screen!=null && Gov24PublicNavigation.reveal(root,screen,goalType);
        }finally{if(root!=null)root.recycle();}
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
        return swipeHomePage(towardNextPage, null, null);
    }

    public static boolean swipeHomePage(boolean towardNextPage, String expectedPackage, Integer expectedWindow) {
        ClickAccessibilityService service = connectedService.get();
        return service != null && new NativeScrollExecutor(service, service.mainHandler, service.captureRunnable)
                .swipeHomePage(towardNextPage, expectedPackage, expectedWindow);
    }

    /** Re-observe and validate each local launcher action immediately before dispatch. */
    public static boolean performLauncherSearch(com.click.data.snapshot.ScreenSnapshot expected,
            com.click.service.overlay.LauncherAppSearch.Step step, java.util.Collection<String> appNames) {
        ClickAccessibilityService service=connectedService.get();
        if(service==null || expected==null || step==null)return false;
        AccessibilityNodeInfo root=ForegroundWindowReader.readRoot(service);if(root==null)return false;
        try {
            return LauncherSearchExecutor.execute(service,service.mainHandler,service.captureRunnable,
                    root,expected,service.readCurrentScreen(root),step,appNames);
        } finally {root.recycle();}
    }

    /** Executes a scroll on the current screen's scrollable container ({@code action_type: scroll},
     * whose {@code execution_owner} is the frontend accessibility service). */
    public static boolean performScroll(boolean forward) {
        ClickAccessibilityService service = connectedService.get();
        return service != null && new NativeScrollExecutor(service, service.mainHandler, service.captureRunnable)
                .performScroll(forward);
    }

    /** Scroll only the settings window that the user just asked to search. */
    public static boolean performSettingsScroll(boolean forward, String expectedPackage, int expectedWindow) {
        ClickAccessibilityService service = connectedService.get();
        return service != null && new NativeScrollExecutor(service, service.mainHandler, service.captureRunnable)
                .performSettingsScroll(forward, expectedPackage, expectedWindow);
    }

    public static boolean revealSettingsTarget(String nodeId, int[] bounds, String expectedPackage, int expectedWindow) {
        ClickAccessibilityService service=connectedService.get();
        return service!=null && new NativeScrollExecutor(service,service.mainHandler,service.captureRunnable)
                .revealSettingsTarget(nodeId,bounds,expectedPackage,expectedWindow);
    }

    /** Return only while the same Settings window is still in front. */
    public static boolean performSettingsBack(String expectedPackage, int expectedWindow) {
        if (!TouchAutomationPolicy.settingsPackage(expectedPackage)) return false;
        ClickAccessibilityService service = connectedService.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = ForegroundWindowReader.readRoot(service);
        if (root == null) return false;
        try {
            if (root.getWindowId() != expectedWindow || root.getPackageName() == null
                    || !expectedPackage.contentEquals(root.getPackageName())) return false;
            return service.performGlobalAction(GLOBAL_ACTION_BACK);
        } finally { root.recycle(); }
    }

    private void captureCurrentScreen() {
        // Explicit refreshes and scroll callbacks may already have queued the same read.
        if (mainHandler != null) mainHandler.removeCallbacks(captureRunnable);
        snapshotBurstStartedAt = -1L;
        snapshotScheduledAt = -1L;
        ScreenSnapshot snapshot = readCurrentScreen();
        if (displayTutorial != null) {
            boolean displayActivity = snapshot != null && snapshot.packageName.equals(lastForegroundPackage)
                    && java.util.Objects.equals(snapshot.activeWindowId, lastForegroundWindow)
                    && lastForegroundClass != null && lastForegroundClass.endsWith("DisplaySettingsActivity");
            displayTutorial.update(snapshot, displayActivity);
        }
        if (snapshot == null) return;
        LatestScreenSnapshot.set(snapshot);
        sendBroadcast(new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED).setPackage(getPackageName()));
    }

    private ScreenSnapshot readCurrentScreen() {
        AccessibilityNodeInfo root = ForegroundWindowReader.readRoot(this);
        try {
            return readCurrentScreen(root);
        } finally {
            if (root != null) root.recycle();
        }
    }

    private ScreenSnapshot readCurrentScreen(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null || getPackageName().contentEquals(root.getPackageName())) return null;
        String packageName = root.getPackageName().toString();
        Float appliedScale = null;
        try {
            float scale = android.provider.Settings.System.getFloat(getContentResolver(),
                    android.provider.Settings.System.FONT_SCALE);
            if (Float.isFinite(scale) && scale > 0) appliedScale = scale;
        } catch (android.provider.Settings.SettingNotFoundException | SecurityException ignored) {
            // Missing/read-denied means unverifiable, never assume a default scale.
        }
        Integer brightness = null, mediaVolume = null;
        try {
            brightness = android.provider.Settings.System.getInt(getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS);
        } catch (android.provider.Settings.SettingNotFoundException | SecurityException ignored) { }
        try {
            android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
            if (audio != null) mediaVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        } catch (SecurityException ignored) { }
        Integer wifiState = null;
        try {
            android.net.wifi.WifiManager wifi = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
            if (wifi != null) wifiState = wifi.getWifiState();
        } catch (SecurityException ignored) { }
        Integer ringerMode = null;
        try {
            android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
            if (audio != null) ringerMode = audio.getRingerMode();
        } catch (SecurityException ignored) { }
        Integer nightMode = null;
        try {
            android.app.UiModeManager mode = getSystemService(android.app.UiModeManager.class);
            if (mode != null) nightMode = mode.getNightMode();
        } catch (SecurityException ignored) { }
        Integer displayDensity = null;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.accessibility.AccessibilityWindowInfo nativeWindow = root.getWindow();
            if (nativeWindow != null) {
                try {
                    if (nativeWindow.getDisplayId() == android.view.Display.DEFAULT_DISPLAY)
                        displayDensity = getResources().getConfiguration().densityDpi;
                } finally { nativeWindow.recycle(); }
            }
        }
        com.click.data.snapshot.NativeWindowContext windowContext=NativeWindowContextReader.read(
                this,root,lastForegroundPackage,lastForegroundWindow,lastForegroundClass);
        ScreenSnapshot captured=collector.collect(root, packageName, this, appliedScale);
        return captured==null?null:captured
                .withWindowContext(windowContext)
                .withAdjustmentValues(brightness, mediaVolume)
                .withWifiState(wifiState)
                .withNightMode(nightMode)
                .withRingerMode(ringerMode)
                .withDisplayDensity(displayDensity)
                .withScreenRevision(screenRevisions.revision());
    }

    private void emitAction(AccessibilityNodeInfo source, String action, long eventUptime) {
        emitAction(source, action, eventUptime, null);
    }

    private void emitAction(AccessibilityNodeInfo source, String action, long eventUptime,
                            AccessibilityNodeInfo.RangeInfo range) {
        AccessibilityNodeInfo root = ForegroundWindowReader.readRoot(this);
        String nodeId = findNodeId(root, source, "node_0");
        if (root != null) root.recycle();
        if (source.getPackageName() == null) return;
        if (nodeId == null && range == null) return;
        if (range != null && nodeId == null)
            Log.i("ClickSlider", "native_range_source_detached_from_current_root");
        android.graphics.Rect rect = new android.graphics.Rect();
        source.getBoundsInScreen(rect);
        long wallTime = System.currentTimeMillis() - (android.os.SystemClock.uptimeMillis() - eventUptime);
        int[] bounds = new int[]{rect.left, rect.top, rect.right, rect.bottom};
        if (range != null) {
            ClickOverlayService.onGuidedRangeChanged(source.getPackageName().toString(), source.getWindowId(),
                    nodeId, source.getViewIdResourceName(), bounds, wallTime,
                    range.getCurrent(), range.getMin(), range.getMax());
        } else {
            ClickOverlayService.onGuidedAction(action, source.getPackageName().toString(), source.getWindowId(),
                    nodeId, source.getViewIdResourceName(), bounds, wallTime);
        }
    }

    private static String findNodeId(AccessibilityNodeInfo node, AccessibilityNodeInfo target, String id) {
        try{return findNodeId(node,target,id,new SnapshotTraversalBudget(),0);}
        catch(SnapshotTraversalBudget.Incomplete incomplete){return null;}
    }
    private static String findNodeId(AccessibilityNodeInfo node,AccessibilityNodeInfo target,String id,SnapshotTraversalBudget budget,int depth) {
        if(node==null)return null;
        budget.visit(depth);
        if (node.equals(target)) return id;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            try {
                budget.child();
                String found=findNodeId(child,target,id+"_"+index,budget,depth+1);
                if(found!=null)return found;
            } finally {if(child!=null)child.recycle();}
        }
        return null;
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
