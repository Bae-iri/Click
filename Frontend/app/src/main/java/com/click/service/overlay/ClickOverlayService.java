package com.click.service.overlay;

import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import com.click.designpreview.TutorialNarrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.click.data.remote.AgentActionEvents;
import com.click.data.local.QuickHelpSelectionStore;
import com.click.core.permission.PermissionStatus;
import com.click.data.remote.AgentTurnContract;
import com.click.data.remote.AgentTurnGateway;
import com.click.data.remote.AgentServerContract;
import com.click.domain.goal.LocalGoalResolver;
import com.click.domain.goal.RequirementReview;
import com.click.data.remote.ErrorDetails;
import com.click.data.remote.VoiceQueueGateway;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.service.accessibility.OnDeviceVision;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.QuickHelpTask;
import com.click.domain.goal.GoalCommandInterpreter;
import com.click.domain.goal.RepeatRequest;
import com.click.domain.goal.TaskFlowAnswers;
import com.click.ui.overlay.TaskFlowOverlayView;

import org.json.JSONObject;
import com.click.service.accessibility.LatestScreenSnapshot;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.FloatingPanelPosition;
import com.click.ui.overlay.ConfirmationOverlayView;
import com.click.ui.overlay.GuideDragDirection;
import com.click.ui.overlay.GuideOverlayView;
import com.click.ui.overlay.TrashDropView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

public final class ClickOverlayService extends Service implements ClickOverlayLayout.Callback {
    public static final String EXTRA_INITIAL_GOAL = "com.click.extra.INITIAL_GOAL";
    public static final String EXTRA_OPEN_VOICE = "com.click.extra.OPEN_VOICE";
    public static final String EXTRA_OPEN_MENU = "com.click.extra.OPEN_MENU";
    public static final String EXTRA_SELECTED_GOAL_TYPE = "com.click.extra.SELECTED_GOAL_TYPE";
    private static WeakReference<ClickOverlayService> runningService = new WeakReference<>(null);
    private boolean appForeground;
    private final java.util.Map<View, Integer> overlayVisibility = new java.util.WeakHashMap<>();

    public static void onAppVisibilityChanged(boolean foreground) {
        ClickOverlayService service = runningService.get();
        if (service == null || service.closing) return;
        service.appForeground = foreground;
        if (foreground) {
            service.cancelDragInteraction();
            service.stopListening();
            if (service.overlayView != null) service.overlayView.collapse();
            service.stopNarration();
        }
        for (java.util.Map.Entry<View, Integer> entry : service.overlayVisibility.entrySet()) {
            entry.getKey().setVisibility(foreground && entry.getKey() != service.feedbackView ? View.GONE : entry.getValue());
        }
    }

    private void setOverlayVisibility(View view, int visibility) {
        overlayVisibility.put(view, visibility);
        view.setVisibility(appForeground ? View.GONE : visibility);
    }

    private void addOverlayView(View view, WindowManager.LayoutParams params) {
        int desired = overlayVisibility.computeIfAbsent(view, key -> key.getVisibility());
        int actual = appForeground && view != feedbackView ? View.GONE : desired;
        if (view.getVisibility() != actual) view.setVisibility(actual);
        windowManager.addView(view, params);
    }

    private WindowManager windowManager;
    private int overlayWindowType;
    private WindowManager.LayoutParams windowParams;
    private ClickOverlayLayout overlayView;
    private int textRestingY;
    private final Runnable keyboardPlacement = this::fitTextAboveKeyboard;
    private TrashDropView trashDropView;
    private WindowManager.LayoutParams trashDropParams;
    private ValueAnimator snapAnimator;
    private boolean compactBubbleAbove = true;
    private SpeechInput speechInput;
    private Handler speechHandler;
    private boolean listening;
    private boolean acceptingVoiceCommand;
    private final TaskExecutionController execution = new TaskExecutionController();
    private int speechGeneration;
    private int queueGeneration;
    private boolean requestActive;
    private boolean awaitingReplacement;
    private com.click.data.local.DeviceTaskJournal deviceJournal;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    private final ReceiptReconciliationGate receiptSyncGate=new ReceiptReconciliationGate();
    private final AdjustmentDelegation adjustmentDelegation=new AdjustmentDelegation();
    private JSONObject pendingApprovalProvenance;
    private final AutomationRunGuard automationGuard = new AutomationRunGuard();
    private VoiceQueueGateway queueGateway;
    private QuickHelpSelectionStore quickHelpSelections;
    private String activeVoiceRequestId;
    private TutorialNarrator narrator;
    // Set only by a submitted request or explicit replay, never by simply opening Click.
    private boolean voiceReplyEnabled;
    private boolean ttsUnavailable;
    private String lastNarrationSuppression;
    private String lastSpokenMessage;
    private String lastNarratedGuideKey;
    private final GuidePresentationGate presentationGate = new GuidePresentationGate();
    private boolean panelInterruptedNarration;
    private boolean overTrashTarget;
    private AgentTurnGateway agentGateway;
    private final GuideStepTimer guideStepTimer = new GuideStepTimer();
    private final NavigationRecovery navigationRecovery = new NavigationRecovery();
    private boolean homeSearchRestart;
    private String lastAutomaticConfirmationId;
    private boolean automaticAdjustmentApprovalPending;
    private GoalCatalogClassifier goalClassifier;
    private GoalCatalogClassifier.Match activeGoalMatch;
    private JSONObject goalSelection;
    private RequirementReview requirementReview;
    private int taskFlowHeightLimit;
    private int taskFlowRestingGravity;
    private int appliedTaskFlowHeight, appliedTaskFlowFlags, appliedTaskFlowGravity;
    private String goalResolutionId;
    private com.click.domain.goal.GoalPreparationState goalPreparation;
    private boolean restoredStartedSession;
    private Runnable nextQueuedRequest;
    private boolean waitingForCompletedEnd,endingTask,advanceAfterUserEnd,nextTaskReview;
    private String sequenceOverview;
    private String lastCompletedVoiceRequestId, lastCompletedAgentSessionId;
    private JSONObject resultPrompt;
    private JSONObject followUp;
    private JSONObject pendingVerifiedAction;
    private final AdjustmentResultCheck adjustmentResultCheck = new AdjustmentResultCheck();
    private final GuidedActionTracker actionTracker = new GuidedActionTracker();
    private TaskFlowOverlayView taskFlowView;
    private boolean taskFlowCollapsed;
    private int targetHelpGeneration = -1;
    private int assistedRecoveryGeneration = -1, recoveryApologyGeneration = -1;
    private ScreenSnapshot targetHelpScreen;
    private boolean appIconGuide;
    private com.click.data.snapshot.CoupangScreenGuide coupangNativeGuide;
    private WindowManager.LayoutParams taskFlowParams;
    private Runnable resultTimeout;
    private String pendingReplacement;
    private String agentConfirmationId;
    private String initialGoal;
    private boolean awaitingExitConfirmation;
    private String lastSnapshotFingerprint;
    private final AutomaticScrollSearch homeScrollSearch = new AutomaticScrollSearch(AutomaticScrollSearch.Mode.LAUNCHER);
    private int homeSearchToken;
    private long adjustmentSettlingUntil;
    private String pendingActionType;
    private long pendingActionOccurredAtMs;
    private Runnable missingScreenRetry;
    private Runnable actionEvidenceWait;
    private int missingScreenAttempts;
    private boolean missingScreenRecovery;
    private String lastGuideCommandId;
    private String lastTargetNodeId;
    private int[] lastTargetBounds;
    private boolean visionConsentRequested;
    private boolean visionCaptureInFlight;
    private boolean visionImageCaptured;
    private boolean speechProcessing;
    private JSONObject pendingVisionSnapshot;
    private String pendingVisionFailure;
    private ScreenSnapshot pendingVisionScreen;
    private ScreenSnapshot lastSubmittedScreen;
    private ScreenSnapshot observedPageScreen, pendingPageScreen;
    private SettingsPageNavigation settingsPageNavigation;
    private String environmentWaitKey;
    private com.click.data.snapshot.ScreenEnvironment environmentWait;
    private ScreenSnapshot lastNavigableEnvironment;
    private ScreenSnapshot visionGuideScreen;
    private ScreenSnapshot nativeGuideScreen;
    private long confirmationExpiresAt;
    private int freshObservationRetries;
    private int staleGuideDrops;
    private Runnable guideResyncRetry;
    private int scrollSearchGeneration = -1;
    private String targetRevealScope;
    private int targetRevealAttempts;
    private String lastBackRecoveryScreen;
    private int settingsBackRecoveries;
    private final AutomaticScrollSearch automaticScrollSearch = new AutomaticScrollSearch();
    private GuideOverlayView guideOverlayView;
    private CoupangBrowseController coupangBrowse;
    private boolean gov24Guide;
    private boolean gov24Civil;
    private String gov24EntryPresentation;
    private int gov24EntryRevealCount;
    private long gov24EntryRevealAt;
    private int gov24TargetRefreshes;
    private WindowManager.LayoutParams guideOverlayParams;
    private ConfirmationOverlayView confirmationView;
    private WindowManager.LayoutParams confirmationParams;
    private boolean collapseReceiverRegistered;
    private final BroadcastReceiver collapseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (OverlayActions.ACTION_COLLAPSE.equals(intent.getAction()) && overlayView != null) {
                overlayView.collapse();
            } else if (OverlayActions.ACTION_SNAPSHOT_UPDATED.equals(intent.getAction())) {
                if(coupangBrowse!=null)coupangBrowse.screenChanged(LatestScreenSnapshot.get());
                if(gov24Guide&&coupangBrowse!=null&&!coupangBrowse.terminal()&&showGov24EntryGuide())return;
                if (showCoupangNativeGuide(LatestScreenSnapshot.get())) return;
                if (coupangBrowse != null) {
                    ScreenSnapshot current=LatestScreenSnapshot.get();
                    if (lastSubmittedScreen==null || current==null || !lastSubmittedScreen.contentFingerprint().equals(current.contentFingerprint())) hideGuideOverlay();
                    return;
                }
                if (handleEnvironment(LatestScreenSnapshot.get())) return;
                if (handleSettingsPageChange(LatestScreenSnapshot.get())) return;
                if (waitingForTargetHelp() && preserveTargetHelp(ClickAccessibilityService.freshSnapshot())) return;
                invalidateStaleGuide();
                if (!handleNavigationSnapshot(LatestScreenSnapshot.get()))
                    sendAgentTurn(null, execution.observationRequested());
            }
        }
    };
    private float dragTargetX;
    private float dragTargetY;
    private float dragWindowOffsetX;
    private float dragWindowOffsetY;
    private boolean dragFrameScheduled;
    private boolean dragInProgress;
    private boolean closing;
    private final Runnable dragFrame = () -> {
        dragFrameScheduled = false;
        if (!dragInProgress || closing || windowManager == null || windowParams == null || overlayView == null) return;
        windowParams.x = Math.round(dragTargetX);
        windowParams.y = Math.round(dragTargetY);
        windowManager.updateViewLayout(overlayView, windowParams);
        placeProgressBubble();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        runningService = new WeakReference<>(this);
        appForeground = getApplication() instanceof com.click.ClickApplication
                && ((com.click.ClickApplication) getApplication()).isAppForeground();
        WindowManager accessibilityWindows = ClickAccessibilityService.accessibilityWindowManager();
        if (!PermissionStatus.isAccessibilityEnabled(this) || accessibilityWindows == null) {
            startActivity(accessibilityGuideIntent());
            stopSelf();
            return;
        }

        if (accessibilityWindows != null) {
            windowManager = accessibilityWindows;
            overlayWindowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else {
            windowManager = getSystemService(WindowManager.class);
            overlayWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        overlayView = new ClickOverlayLayout(this);
        overlayView.setCallback(this);
        overlayView.setOnLibraryRequested(this::openLibrary);
        trashDropView = new TrashDropView(this);
        trashDropParams = new WindowManager.LayoutParams(
                dp(112), dp(112), overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        trashDropParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        trashDropParams.y = dp(TrashDropView.BOTTOM_MARGIN_DP);
        speechHandler = new Handler(getMainLooper());
        narrator = new TutorialNarrator(this, false);
        narrator.setListener((speaking, unavailable) -> {
            if (closing) return;
            ttsUnavailable = unavailable;
            if (taskFlowView != null) taskFlowView.setNarrationState(speaking, unavailable);
        });
        agentGateway = new AgentTurnGateway(this);
        deviceJournal=agentGateway.journal();
        registerReceiptConnectivity();
        queueGateway = new VoiceQueueGateway(this);
        interactionGateway = new com.click.data.remote.InteractionGateway(this);
        quickHelpSelections = new QuickHelpSelectionStore(this);
        goalClassifier = loadGoalCatalog();
        speechInput = new DeviceSpeechInput(this);
        IntentFilter overlayFilter = new IntentFilter(OverlayActions.ACTION_COLLAPSE);
        overlayFilter.addAction(OverlayActions.ACTION_SNAPSHOT_UPDATED);
        registerReceiver(collapseReceiver, overlayFilter, Context.RECEIVER_NOT_EXPORTED);
        collapseReceiverRegistered = true;
        windowParams = new WindowManager.LayoutParams(
                overlayView.getWindowWidth(ClickOverlayLayout.Mode.COLLAPSED, screenWidth()), dp(64), overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        windowParams.gravity = Gravity.START | Gravity.TOP;
        // Position the overlay from screen coordinates ourselves, without an additional IME pan.
        windowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        windowParams.x = screenWidth() - windowParams.width - dp(12);
        windowParams.y = screenHeight() - dp(64) - dp(48);
        // Windows of the same type stack in attachment order. Keep the visual
        // guide below Click's controls for their entire lifetime.
        attachGuideOverlay();
        attachTrashTarget();
        windowManager.addView(overlayView, windowParams);
        sendBroadcast(new Intent(OverlayActions.ACTION_VISIBILITY_CHANGED).setPackage(getPackageName()));
        // Restoring an overlay must not claim or speak a request left on the server.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (closing) return START_NOT_STICKY;
        if (intent != null && overlayView != null) {
            String initialRequest = intent.getStringExtra(EXTRA_INITIAL_GOAL);
            String selectedType = intent.getStringExtra(EXTRA_SELECTED_GOAL_TYPE);
            boolean openVoice = intent.getBooleanExtra(EXTRA_OPEN_VOICE, false);
            if (selectedType != null) {
                silenceVoiceReply();
                voiceReplyEnabled = true;
                QuickHelpTask selected = QuickHelpTask.fromGoalType(selectedType);
                if (selected == null || goalClassifier == null
                        || goalClassifier.executableGoal(selectedType) == null) {
                    speak("지금은 이 도움을 시작할 수 없어요. 잠시 후 다시 눌러 주세요.");
                } else {
                    overlayView.post(() -> {
                        if (closing) return;
                        // A home quick-help tap starts a fresh task panel. Do not let a
                        // previously collapsed input panel swallow the selected request.
                        initialGoal = selected.requestText;
                        taskFlowCollapsed = false;
                        overlayView.collapse();
                        enqueueNormalRequest(selected.requestText, selected.goalType);
                    });
                }
            } else if (initialRequest != null && !initialRequest.trim().isEmpty()) {
                silenceVoiceReply();
                voiceReplyEnabled = true;
                String request = initialRequest.trim();
                overlayView.post(() -> {
                    if (closing) return;
                    overlayView.collapse();
                    handleRecognizedCommand(request);
                });
            } else if (openVoice) {
                overlayView.post(() -> { if (!closing) overlayView.openVoice(); });
            } else if (intent.getBooleanExtra(EXTRA_OPEN_MENU, false)) {
                overlayView.post(() -> { if (!closing) overlayView.openMenu(); });
            }
        }
        return START_STICKY;
    }

    @Override
    public void onModeChanged(ClickOverlayLayout.Mode oldMode, ClickOverlayLayout.Mode newMode) {
        if (windowManager == null || windowParams == null) return;
        cancelDragInteraction();
        android.util.Log.i("ClickOverlay", "Input panel: " + oldMode + " -> " + newMode);
        // The task card and the input/menu panel share one visible interaction surface.
        updateFloatingVisibility();
        float anchorX = windowParams.x + overlayView.getClickCenterX(oldMode);
        float anchorY = windowParams.y + overlayView.getClickCenterY(oldMode);
        windowParams.width = overlayView.getWindowWidth(newMode, screenWidth());
        windowParams.height = overlayView.getWindowHeight(newMode);
        windowParams.x = Math.round(anchorX - overlayView.getClickCenterX(newMode));
        windowParams.y = Math.round(anchorY - overlayView.getClickCenterY(newMode));
        clampWindowPosition();
        if (newMode == ClickOverlayLayout.Mode.TEXT) textRestingY = windowParams.y;
        updateFocusableFlag(newMode == ClickOverlayLayout.Mode.TEXT);
        windowManager.updateViewLayout(overlayView, windowParams);
        if (newMode == ClickOverlayLayout.Mode.TEXT) overlayView.post(keyboardPlacement);
        placeProgressBubble();
        // Opening a panel never authorizes recording. Only the microphone button does.
        stopListening();
        if (newMode != ClickOverlayLayout.Mode.COLLAPSED) {
            if (oldMode == ClickOverlayLayout.Mode.COLLAPSED && narrator != null && narrator.isSpeaking())
                panelInterruptedNarration = true;
            stopNarration();
        }
        if (newMode == ClickOverlayLayout.Mode.COLLAPSED) {
            restorePanelNarration();
        }
    }

    @Override
    public void onDragStarted() {
        if (closing || windowManager == null || windowParams == null || overlayView == null
                || overlayView.getMode() != ClickOverlayLayout.Mode.COLLAPSED
                || overlayView.getVisibility() != View.VISIBLE) return;
        dragInProgress = true;
        if (snapAnimator != null) snapAnimator.cancel();
        int[] location = new int[2];
        overlayView.getLocationOnScreen(location);
        dragWindowOffsetX = location[0] - windowParams.x;
        dragWindowOffsetY = location[1] - windowParams.y;
        overlayView.collapse();
        dragTargetX = windowParams.x;
        dragTargetY = windowParams.y;
        showTrashTarget();
    }

    @Override
    public void onDragBy(float deltaX, float deltaY) {
        if (!dragInProgress || closing || windowManager == null || windowParams == null) return;
        int maxX = Math.max(0, screenWidth() - windowParams.width);
        int maxY = Math.max(0, screenHeight() - windowParams.height);
        dragTargetX = Math.max(0, Math.min(dragTargetX + deltaX, maxX));
        dragTargetY = Math.max(0, Math.min(dragTargetY + deltaY, maxY));
        if (!dragFrameScheduled) {
            dragFrameScheduled = true;
            overlayView.postOnAnimation(dragFrame);
        }
        updateTrashTargetState();
    }

    @Override
    public void onDragEnded() {
        if (!dragInProgress || closing || overlayView == null || windowManager == null || windowParams == null) return;
        dragInProgress = false;
        overlayView.removeCallbacks(dragFrame);
        dragFrameScheduled = false;
        windowParams.x = Math.round(dragTargetX);
        windowParams.y = Math.round(dragTargetY);
        windowManager.updateViewLayout(overlayView, windowParams);
        updateTrashTargetState();
        if (overTrashTarget) {
            android.util.Log.i("ClickOverlay", "Closing after CLICK was dropped on trash");
            hideTrashTarget();
            shutdownClick();
            return;
        }
        hideTrashTarget();
        snapToNearestEdge();
    }

    @Override public void onInteractionStarted() { if (snapAnimator != null) snapAnimator.cancel(); }

    @Override public void onDragCancelled() {
        cancelDragInteraction();
        snapToNearestEdge();
    }

    private void cancelDragInteraction() {
        dragInProgress = false;
        if (overlayView != null) overlayView.removeCallbacks(dragFrame);
        dragFrameScheduled = false;
        if (snapAnimator != null) snapAnimator.cancel();
        hideTrashTarget();
    }

    @Override
    public void onTextSubmitted(String text) {
        if (text == null || text.trim().isEmpty()) return;
        silenceVoiceReply();
        voiceReplyEnabled = true;
        Toast.makeText(this, "요청을 보내고 있습니다…", Toast.LENGTH_SHORT).show();
        if (overlayView != null) {
            overlayView.collapse();
        }
        handleRecognizedCommand(text);
    }

    @Override
    public void onCloseRequested() {
        shutdownClick();
    }

    @Override
    public void onDestroy() {
        android.util.Log.i("ClickOverlay", "Service destroyed; explicitClose=" + closing);
        closing = true;
        if(networkCallback!=null)try {getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
        networkCallback=null;
        clearMissingScreenWait();
        stopNarration();
        if (narrator != null) { narrator.close(); narrator = null; }
        if (overlayView != null) overlayView.removeCallbacks(keyboardPlacement);
        clearTaskFlow();
        execution.invalidate();
        automationGuard.interrupt();
        navigationRecovery.clear();
        homeSearchRestart = false;
        queueGeneration++;
        speechGeneration++;
        acceptingVoiceCommand = false;
        clearVisionState();
        if (snapAnimator != null) snapAnimator.cancel();
        if (speechHandler != null) speechHandler.removeCallbacksAndMessages(null);
        if (speechInput != null) speechInput.close();
        listening = false;
        if (agentGateway != null) agentGateway.close();
        if (queueGateway != null) queueGateway.close();
        if (interactionGateway != null) interactionGateway.close();
        clearFeedback();
        if (collapseReceiverRegistered) {
            unregisterReceiver(collapseReceiver);
            collapseReceiverRegistered = false;
        }
        detachGuideOverlay();
        hideConfirmation();
        hideTrashTarget();
        detachTrashTarget();
        if (windowManager != null && overlayView != null && overlayView.isAttachedToWindow()) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        trashDropView = null;
        windowManager = null;
        if (runningService.get() == this) runningService.clear();
        sendBroadcast(new Intent(OverlayActions.ACTION_VISIBILITY_CHANGED).setPackage(getPackageName()));
        super.onDestroy();
    }

    /** Activity lifecycle signal, independent of AX events from our own overlay windows. */
    public static void stopForAppEntry() {
        ClickOverlayService service = runningService.get();
        if (service != null) service.shutdownClick();
    }

    public static void collapseIfRunning() {
        ClickOverlayService service = runningService.get();
        if (service != null && service.overlayView != null) {
            service.overlayView.post(service.overlayView::collapse);
        }
    }

    public static boolean isRunning() {
        ClickOverlayService service = runningService.get();
        return service != null && !service.closing && service.overlayView != null;
    }

    /** Called only for a matched manual guide action; navigation and machine actions are excluded. */
    public static void onGuidedViewClicked(String packageName, long eventUptimeMs) {
        ClickOverlayService service = runningService.get();
        if (service == null || service.execution.paused() || service.execution.sessionId() == null) return;
        service.guideStepTimer.firstTap(packageName, eventUptimeMs);
    }

    public static void onGuidedAction(String type, String packageName, int windowId, String nodeId,
                                     String resourceId, int[] bounds, long occurredAt) {
        recordGuidedAction(type, packageName, windowId, nodeId, resourceId, bounds, occurredAt, false);
    }

    public static void onVerifiedAutomaticAction(String type, String packageName, int windowId, String nodeId,
                                                 String resourceId, int[] bounds, long occurredAt) {
        recordGuidedAction(type, packageName, windowId, nodeId, resourceId, bounds, occurredAt, true);
    }

    private static void recordGuidedAction(String type, String packageName, int windowId, String nodeId,
                                          String resourceId, int[] bounds, long occurredAt, boolean automatic) {
        ClickOverlayService service = runningService.get();
        if (service == null || service.execution.paused() || service.execution.pendingControl() != null) return;
        if (service.coupangBrowse != null) {
            if (!automatic && AgentActionEvents.TAP_COMPLETED.equals(type)) service.coupangBrowse.tap(packageName,windowId,nodeId,occurredAt);
            return;
        }
        JSONObject event = service.actionTracker.record(type, packageName, windowId, nodeId,
                resourceId, bounds, occurredAt);
        if (event == null) {
            if (!automatic && AgentActionEvents.TAP_COMPLETED.equals(type) && service.lastSubmittedScreen != null
                    && java.util.Objects.equals(packageName, service.lastSubmittedScreen.packageName)
                    && !service.actionTracker.isTargetTap(packageName, windowId, nodeId, bounds)) {
                service.navigationRecovery.unplannedTap();
                service.guideStepTimer.clear();
            }
            return;
        }
        if (!automatic && AgentActionEvents.TAP_COMPLETED.equals(type))
            onGuidedViewClicked(packageName, SystemClock.uptimeMillis() - (System.currentTimeMillis() - occurredAt));
        service.acceptGuidedAction(event);
    }

    public static void onGuidedRangeChanged(String packageName, int windowId, String nodeId,
                                           String resourceId, int[] bounds, long occurredAt,
                                           float value, float minimum, float maximum) {
        ClickOverlayService service = runningService.get();
        if (service == null || service.execution.paused() || service.execution.pendingControl() != null) return;
        JSONObject event = service.actionTracker.recordRangeChange(packageName, windowId, nodeId,
                resourceId, bounds, occurredAt, value, minimum, maximum);
        if (com.click.BuildConfig.DEBUG) android.util.Log.i("ClickSlider",
                "native_range_event value=" + value + " matched_guide=" + (event != null));
        service.acceptGuidedAction(event);
    }

    private void acceptGuidedAction(JSONObject event) {
        if (event == null) return;
        pendingVerifiedAction = event;
        final int generation = execution.generation();
        // Re-read through transient redraws, including unchanged screens, to settle or reject it.
        for (long delay : new long[]{350L, 1100L, 2200L, 5500L, 6500L}) {
            speechHandler.postDelayed(() -> {
                if (generation != execution.generation() || execution.paused()
                        || resultPrompt != null || execution.pendingControl() != null) return;
                execution.requestObservation();
                ClickAccessibilityService.requestFreshSnapshot();
            }, delay);
        }
    }

    private void updateFloatingVisibility() {
        if (overlayView == null || closing) return;
        overlayView.setTaskPhase(taskFlowView == null ? null : taskFlowView.phaseLabel());
        boolean bubble = taskFlowView != null && taskFlowCollapsed && taskFlowView.hasCompactContent();
        if (taskFlowView != null) taskFlowView.setMinimizedProgress(bubble);
        boolean showTask = taskFlowView != null && !taskFlowCollapsed
                && overlayView.getMode() == ClickOverlayLayout.Mode.COLLAPSED;
        boolean hideForCapture = visionCaptureInFlight && !visionImageCaptured;
        setOverlayVisibility(overlayView, hideForCapture || showTask ? View.GONE : View.VISIBLE);
        if (taskFlowView != null) setOverlayVisibility(taskFlowView, !hideForCapture
                && (showTask || bubble && overlayView.getMode() == ClickOverlayLayout.Mode.COLLAPSED)
                ? View.VISIBLE : View.GONE);
        if (bubble) placeProgressBubble();
    }

    private void collapseTaskFlow() {
        android.util.Log.i("ClickOverlay", "Task panel minimized; keeping CLICK visible");
        if (overlayView != null) overlayView.cancelPendingGesture();
        cancelDragInteraction();
        taskFlowCollapsed = true;
        stopListening();
        if (taskFlowView != null) {
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(taskFlowView.getWindowToken(), 0);
        }
        if (overlayView != null) overlayView.collapse();
        updateFloatingVisibility();
        if (taskFlowView != null && taskFlowView.hasOpenedTargetHelp()) {
            replayGuidanceMessage(taskFlowView.guidanceMessage());
            ClickAccessibilityService.requestFreshSnapshot();
        }
    }

    private void openLibrary() {
        if (closing) return;
        if (overlayView != null) overlayView.collapse();
        stopListening();
        stopNarration();
        startActivity(new Intent(this, com.click.HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(com.click.HomeActivity.EXTRA_OPEN_LIBRARY, true));
    }

    @Override public boolean onOpenRequested() {
        if (taskFlowView == null || !taskFlowCollapsed) return false;
        taskFlowCollapsed = false;
        updateFloatingVisibility();
        placeTaskFlow(null, true);
        if (taskFlowView.isTargetHelp()) replayGuidanceMessage(taskFlowView.guidanceMessage());
        else restorePanelNarration();
        return true;
    }

    private void ensureTaskFlow() {
        if (taskFlowView != null) { taskFlowView.setRequestSummary(initialGoal); return; }
        if (windowManager == null) return;
        // Hand off to guidance once, without closing a draft on later task updates.
        if (overlayView != null) overlayView.collapse();
        final TaskFlowOverlayView[] owner = {null};
        taskFlowView = new TaskFlowOverlayView(this, new TaskFlowOverlayView.Listener() {
            @Override public void collapse() { collapseTaskFlow(); }
            @Override public void expand() { onOpenRequested(); }
            @Override public void openInput() { if (overlayView != null) overlayView.openVoice(); }
            @Override public void chooseGoal(JSONObject goal) { chooseResolvedGoal(goal); }
            @Override public void clarify(String choice) { resolveGoal(initialGoal, choice); }
            @Override public void rephrase(String text) { voiceReplyEnabled = true; initialGoal = text; resolveGoal(text, null); }
            @Override public void replayGuidance(String message) { replayGuidanceMessage(message); }
            @Override public void stopGuidance() { panelInterruptedNarration = false; stopNarration(); }
            @Override public void requirementChoice(String choice) {
                if (requirementReview == null || execution.paused() || execution.busy()) return;
                voiceReplyEnabled = true;
                requirementReview.choose(choice); showRequirementReview();
            }
            @Override public void requirementAnswer(String answer) { updateRequirements(answer); }
            @Override public void confirmRequirements() { confirmRequirementReview(); }
            @Override public void answerGoalInput(String value) { ClickOverlayService.this.answerGoalInput(value); }
            @Override public void resetGoalInputs() {
                if (execution.busy() || execution.paused()) return;
                execution.setAcceptedGoal(null);
                execution.exitManual();
                resolveGoalOnServer(initialGoal, null, null, true);
            }
            @Override public void result(String answer) { sendResult(answer); }
            @Override public void adjust(String reason) { sendAdjustment(reason); }
            @Override public void control(String command) {
                if (closing || taskFlowView != owner[0]) return;
                if ("cancel_task".equals(command)) endCurrentTask();
                else sendAgentControl(command);
            }
        });
        owner[0] = taskFlowView;
        taskFlowView.setRequestSummary(initialGoal);
        taskFlowParams = new WindowManager.LayoutParams(taskFlowView.getWindowWidth(screenWidth()),
                WindowManager.LayoutParams.WRAP_CONTENT, overlayWindowType,
                // Waiting/guidance must not become the active accessibility window.
                // Editable preparation explicitly enables focus in placeTaskFlow.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        taskFlowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        taskFlowParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        taskFlowParams.y = dp(30);
        addOverlayView(taskFlowView, taskFlowParams);
        updateFloatingVisibility();
    }

    private void placeTaskFlow(int[] target, boolean expanded) {
        ensureTaskFlow();
        if (taskFlowView == null) return;
        if (taskFlowCollapsed && taskFlowView.hasCompactContent()) {
            taskFlowView.setMinimizedProgress(true);
            placeProgressBubble();
            return;
        }
        taskFlowParams.width = taskFlowView.getWindowWidth(screenWidth());
        taskFlowParams.x = 0;
        // This is a ceiling; WRAP_CONTENT keeps short questions small and lets longer
        // requests, choices and captions grow before the body needs to scroll.
        int height = (int) (screenHeight() * (expanded ? .78f : .55f));
        if (getResources().getConfiguration().fontScale > 1.3f)
            height = Math.max(height, (int) (screenHeight() * .82f));
        boolean atBottom = target != null && (target[1] + target[3]) / 2 < screenHeight() / 2;
        if (target != null) {
            int available = atBottom ? screenHeight() - target[3] - dp(32) : target[1] - dp(48);
            height = Math.max(dp(150), Math.min(height, available));
        }
        taskFlowParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        taskFlowParams.gravity = (atBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        taskFlowParams.y = dp(24);
        taskFlowParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | (goalSelection == null && goalPreparation == null && (requirementReview == null || requirementReview.confirmed())
                    && !(gov24Guide&&taskFlowView!=null&&taskFlowView.needsPublicGuideInput()) ? WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE : 0);
        taskFlowHeightLimit = height;
        taskFlowRestingGravity = taskFlowParams.gravity;
        appliedTaskFlowHeight = -1;
        fitTaskFlowAboveKeyboard();
    }

    private void placeProgressBubble() {
        if (taskFlowView == null || taskFlowParams == null || windowParams == null || windowManager == null
                || !taskFlowView.isAttachedToWindow() || !taskFlowView.isMinimizedProgress() || closing) return;
        int width = taskFlowView.getWindowWidth(screenWidth());
        int limit = Math.min(Math.round(screenHeight() * .30f), dp(260));
        taskFlowView.setAvailableHeight(limit);
        taskFlowView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
        int height = taskFlowView.getMeasuredHeight();
        int left = Math.max(dp(12), Math.min(windowParams.x + windowParams.width - width,
                screenWidth() - width - dp(12)));
        boolean canAbove = windowParams.y - height - dp(10) >= dp(24);
        boolean canBelow = windowParams.y + windowParams.height + dp(10) + height <= screenHeight() - dp(24);
        if (compactBubbleAbove && !canAbove && canBelow) compactBubbleAbove = false;
        else if (!compactBubbleAbove && !canBelow && canAbove) compactBubbleAbove = true;
        // Keep the caption beside the user's chosen icon position; highlights never move the icon.
        int top = compactBubbleAbove ? windowParams.y - height - dp(10)
                : windowParams.y + windowParams.height + dp(10);
        taskFlowView.setCompactAnchor(windowParams.x + windowParams.width / 2f - left, !compactBubbleAbove);
        taskFlowParams.width = width;
        taskFlowParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        taskFlowParams.gravity = Gravity.TOP | Gravity.START;
        taskFlowParams.x = left; taskFlowParams.y = top;
        taskFlowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | (windowParams.flags & (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS));
        windowManager.updateViewLayout(taskFlowView, taskFlowParams);
    }

    private void clearTaskFlow() {
        if (coupangBrowse != null) { coupangBrowse.close(); coupangBrowse=null; }
        gov24Guide=false;gov24Civil=false;gov24EntryPresentation=null;gov24EntryRevealCount=0;gov24EntryRevealAt=0;gov24TargetRefreshes=0;
        adjustmentDelegation.clear();pendingApprovalProvenance=null;
        environmentWaitKey=null;environmentWait=null;lastNavigableEnvironment=null;
        observedPageScreen=pendingPageScreen=null;
        clearTargetHelpWait();
        panelInterruptedNarration = false;
        if (resultTimeout != null) speechHandler.removeCallbacks(resultTimeout);
        resultTimeout = null;
        actionTracker.clear(); pendingVerifiedAction = null;
        adjustmentResultCheck.clear(); clearActionEvidenceWait();
        resultPrompt = followUp = goalSelection = null; execution.setAcceptedGoal(null);
        requirementReview = null;
        goalResolutionId = null; goalPreparation = null; restoredStartedSession = false; execution.clearControl();
        if (taskFlowView != null && taskFlowView.isAttachedToWindow()) windowManager.removeView(taskFlowView);
        taskFlowView = null; taskFlowCollapsed = false;
        updateFloatingVisibility();
        appliedTaskFlowHeight = 0;
    }

    private void resolveGoal(String text, String clarification) {
        if (execution.busy() || execution.sessionId() == null) return;
        new com.click.data.local.RequestLibraryStore(this).record(text, null);
        initialGoal = text;
        taskFlowCollapsed = false;
        goalPreparation = null;
        restoredStartedSession = false;
        goalResolutionId = null;
        String selectedType = quickHelpSelections == null || activeVoiceRequestId == null
                ? null : quickHelpSelections.get(activeVoiceRequestId);
        QuickHelpTask selectedCard = QuickHelpTask.fromGoalType(selectedType);
        GoalCatalogClassifier.Match selected = selectedCard == null || goalClassifier == null
                || !selectedCard.requestText.equals(text) ? null : goalClassifier.executableGoal(selectedType);
        requirementReview = RequirementReview.isFontRequest(text)
                ? RequirementReview.forContext(text, foregroundPackage()) : null;
        final boolean explicitCardSelection = selected != null && clarification == null;
        if (explicitCardSelection) {
            try { requirementReview = RequirementReview.forGoal(text, selected.toTypedGoalJson(), selected.displayName); }
            catch (org.json.JSONException error) { showQueueError(error); return; }
        }
        final String preparationSource = explicitCardSelection ? requirementReview.preparationSource() : text;
        ensureTaskFlow(); updateFloatingVisibility(); taskFlowView.waiting("사용자 요청을 처리하고 있어요");
        setTurnInFlight(true);
        final int generation = execution.generation();
        agentGateway.loadContract(new AgentTurnGateway.ContractCallback() {
            @Override public void onSuccess(AgentServerContract contract) {
                if (generation != execution.generation() || closing) return;
                setTurnInFlight(false);
                if (execution.pendingControl() != null) {
                    String control = execution.pendingControl(); execution.clearControl();
                    sendAgentControl(control); return;
                }
                if (execution.paused()) { taskFlowView.paused(); return; }
                if (contract.goalPreparation) {
                    // A favorite already selects a Settings task. Keep that explicit scope
                    // through server preparation; ordinary speech still needs a scope question.
                    if (!explicitCardSelection) {
                        requirementReview = RequirementReview.isFontRequest(text) ? new RequirementReview(text) : null;
                    }
                    resolveGoalOnServer(preparationSource, clarification); return;
                }
                if (LocalGoalResolver.needsServerResolution(goalClassifier, contract, text, foregroundPackage(), clarification)) {
                    resolveGoalOnServer(text, clarification); return;
                }
                // Resolve requirements before any runtime command, regardless of server version.
                if (requirementReview != null) { showRequirementReview(); return; }
                try {
                    goalSelection = LocalGoalResolver.resolve(goalClassifier, contract, text, foregroundPackage(), clarification);
                    initialGoal = text;
                    goalResolutionId = null;
                    updateRequestStatus(true, false);
                    org.json.JSONArray candidates = goalSelection.getJSONArray("candidates");
                    if (candidates.length() == 1) { chooseResolvedGoal(candidates.getJSONObject(0).getJSONObject("goal")); return; }
                    if (candidates.length() == 0) { showUnresolvedGoal(); return; }
                    taskFlowView.goals(goalSelection.getString("message"), goalSelection.getJSONArray("candidates"));
                    placeTaskFlow(null, true);
                    speak(goalSelection.getString("message"));
                } catch (org.json.JSONException error) { showQueueError(error); }
            }
            @Override public void onFailure(Throwable error) {
                if (generation != execution.generation() || closing) return;
                setTurnInFlight(false);
                showQueueError(error);
            }
        });
    }

    private void resolveGoalOnServer(String text, String clarification) {
        resolveGoalOnServer(text, clarification, null, false);
    }

    private void resolveGoalOnServer(String text, String clarification, JSONObject answer, boolean reset) {
        final int generation = execution.generation();
        final AgentTurnContract.Request request = AgentTurnContract.Request.resolve(execution.sessionId(), text, clarification)
                .withSessionState(execution.sessionVersion(), null, null);
        setTurnInFlight(true);
        boolean prepares = agentGateway.currentContract() != null && agentGateway.currentContract().goalPreparation;
        if (prepares) {
            request.preparationVersion = "goal-inputs.v1";
            request.clarificationAnswer = answer;
            request.goalResolutionId = answer != null && goalPreparation != null ? goalPreparation.id() : null;
            request.preparationReset = reset;
        } else goalResolutionId = null;
        stopNarration();
        ensureTaskFlow(); taskFlowView.waiting("요청에 필요한 내용을 확인하고 있어요…");
        updateRequestStatus(true, true);
        sendTracked(request, new AgentTurnGateway.Callback() {
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if (generation != execution.generation() || closing) return;
                if (!request.requestId.equals(response.requestId) || !request.sessionId.equals(response.sessionId)
                        || response.sessionVersion != execution.sessionVersion() || !"goal_resolution".equals(response.responseType)) {
                    onFailure(new java.io.IOException("INVALID_GOAL_RESOLUTION")); return;
                }
                setTurnInFlight(false);
                try {
                    if (prepares) {
                        if (response.preparation == null) throw new org.json.JSONException("Missing preparation response");
                        goalPreparation = new com.click.domain.goal.GoalPreparationState(
                                response.preparation, response.resolvedGoal, response.message);
                        goalResolutionId = goalPreparation.id();
                        initialGoal = response.preparation.getString("source_text");
                        restoredStartedSession = false;
                        requirementReview = null; goalSelection = null;
                    }
                } catch (org.json.JSONException error) { onFailure(error); return; }
                if (execution.pendingControl() != null) {
                    String control = execution.pendingControl(); execution.clearControl(); sendAgentControl(control); return;
                }
                if (execution.paused()) { taskFlowView.paused(); return; }
                if (prepares) { showGoalPreparation(); return; }
                try {
                    goalSelection = LocalGoalResolver.serverSelection(goalClassifier, agentGateway.currentContract(),
                            response.resolutionStatus, response.resolvedGoal, response.message);
                    org.json.JSONArray candidates = goalSelection.getJSONArray("candidates");
                    if (candidates.length() == 1) {
                        chooseResolvedGoal(candidates.getJSONObject(0).getJSONObject("goal")); return;
                    }
                    showUnresolvedGoal();
                } catch (org.json.JSONException error) { onFailure(error); }
            }
            @Override public void onFailure(Throwable error) {
                if (generation != execution.generation() || closing) return;
                setTurnInFlight(false);
                if (execution.pendingControl() != null) {
                    String control = execution.pendingControl(); execution.clearControl(); sendAgentControl(control); return;
                }
                if (execution.paused()) { taskFlowView.paused(); return; }
                // Do not log request text or an API response body.
                android.util.Log.w("ClickGoal", "resolve_goal failed: " + error.getClass().getSimpleName()
                        + (error instanceof com.click.data.remote.ApiException
                        ? " HTTP " + ((com.click.data.remote.ApiException) error).status : ""));
                if (prepares && error instanceof com.click.data.remote.ApiException
                        && "GOAL_PREPARATION_NOT_ALLOWED".equals(
                                ((com.click.data.remote.ApiException) error).conflictCode)) {
                    restoreQueuedSession(false); return;
                }
                if (prepares) {
                    // Never turn a failed preparation into a locally executable goal.
                    if (answer != null || reset) {
                        // An answer may already have committed before its response was lost.
                        resolveGoalOnServer(text, null, null, false); return;
                    }
                    goalPreparation = null; goalResolutionId = null;
                    String message = ErrorDetails.requestMessage(error);
                    if (requirementReview != null) showRequirementReview();
                    else showGoalResolutionFailure(text, message);
                    return;
                }
                showGoalResolutionFailure(text, ErrorDetails.requestMessage(error));
            }
        });
    }

    private void showGoalResolutionFailure(String text, String message) {
        ensureTaskFlow();
        final int generation = execution.generation();
        taskFlowView.resolutionUnavailable(text, message, () -> {
            if (generation != execution.generation() || closing || execution.paused() || execution.busy()
                    || execution.sessionId() == null || execution.pendingControl() != null) return;
            resolveGoalOnServer(text, null);
        });
        updateRequestStatus(true, false);
        placeTaskFlow(null, true);
        speak(message);
    }

    private void showGoalPreparation() {
        if (goalPreparation == null || closing) return;
        ensureTaskFlow();
        if (goalPreparation.browsing() || goalPreparation.publicGuide()) {
            taskFlowView.setPreparationMessage(null);taskFlowView.preparation(goalPreparation);
            taskFlowView.offerAdjustmentDelegation(false);
            updateRequestStatus(true,false);placeTaskFlow(null,true);speak(goalPreparation.guidance());return;
        }
        String review=nextTaskReview?TaskSequenceText.question(goalPreparation.goal==null?null:goalPreparation.goal.optString("goal_type")):sequenceOverview;
        taskFlowView.setPreparationMessage(review);
        taskFlowView.preparation(goalPreparation);
        if(goalPreparation.ready())taskFlowView.disruptiveTaskNotice(com.click.data.local.DeviceTaskJournal.disruptive(
                goalPreparation.goal.optJSONObject("slots")==null?null:goalPreparation.goal.optJSONObject("slots").optString("setting")));
        taskFlowView.offerAdjustmentDelegation(goalPreparation.ready() && SettingsAdjustment.from(goalPreparation.goal)!=null
                && automaticTouchEnabled() && agentGateway.currentContract()!=null && agentGateway.currentContract().approvalProvenance);
        updateRequestStatus(true, false); placeTaskFlow(null, true);
        speak(goalPreparation.ready() && review!=null?review:goalPreparation.guidance());
    }

    private void answerGoalInput(String value) {
        if (goalPreparation == null || execution.busy() || execution.paused() || execution.acceptedGoal() != null) return;
        try {
            JSONObject answer = goalPreparation.answer(value);
            // A rephrased request bypasses the queue, but still belongs in recent questions.
            if ("request".equals(goalPreparation.question().optString("field")))
                new com.click.data.local.RequestLibraryStore(this).record(answer.getString("value"), null);
            resolveGoalOnServer(initialGoal, null, answer, false);
        } catch (org.json.JSONException error) {
            speak("화면의 선택지에서 원하시는 내용을 골라 주시겠어요?");
        }
    }

    private void showUnresolvedGoal() {
        if (requirementReview != null) { showRequirementReview(); return; }
        try {
            String message = getString(com.click.R.string.unresolved_goal_guidance);
            goalSelection = new JSONObject().put("message", message).put("candidates", new org.json.JSONArray());
            updateRequestStatus(true, false);
            taskFlowView.unresolvedGoal(initialGoal);
            placeTaskFlow(null, true); speak(message);
        } catch (org.json.JSONException error) { showQueueError(error); }
    }

    private void chooseResolvedGoal(JSONObject goal) {
        if (execution.busy() || execution.paused() || goal == null || goalSelection == null) return;
        activeGoalMatch = goalClassifier == null ? null : goalClassifier.executableGoal(goal.optString("goal_type"));
        requirementReview = RequirementReview.forResolvedGoal(initialGoal, goal,
                activeGoalMatch == null ? "선택한 요청" : activeGoalMatch.displayName, requirementReview);
        goalSelection = null;
        showRequirementReview();
    }

    private void showRequirementReview() {
        if (requirementReview == null || closing) return;
        try {
            ensureTaskFlow();
            JSONObject proposed=requirementReview.proposedGoal(goalClassifier,agentGateway.currentContract());
            boolean executable=proposed!=null;
            String review=nextTaskReview?TaskSequenceText.question(proposed==null?null:proposed.optString("goal_type")):sequenceOverview;
            taskFlowView.setPreparationMessage(review);
            taskFlowView.requirements(requirementReview, executable);
            updateRequestStatus(true, false); placeTaskFlow(null, true);
            speak(executable && review!=null?review:requirementReview.prompt(executable));
        } catch (org.json.JSONException error) { showQueueError(error); }
    }

    private void updateRequirements(String answer) {
        if (requirementReview == null || execution.paused() || execution.busy()) return;
        voiceReplyEnabled = true;
        if (!requirementReview.isFont()) { resolveGoal(answer, null); return; }
        GoalCatalogClassifier.Classification changed = goalClassifier == null ? null : goalClassifier.classify(answer, null);
        if (changed != null && changed.matched() && !changed.match.goalType.startsWith("settings.font_size.")) {
            resolveGoal(answer, null); return;
        }
        requirementReview.update(answer);
        showRequirementReview();
    }

    private void confirmRequirementReview() {
        if (goalPreparation != null) {
            if (execution.paused() || execution.busy() || execution.acceptedGoal() != null || !goalPreparation.ready()) return;
            if (goalPreparation.publicGuide()) {
                gov24Guide=true;
                JSONObject publicGoal=goalPreparation.data.optJSONObject("public_guide_goal");
                gov24Civil=publicGoal!=null&&"gov24.resident_copy.open".equals(publicGoal.optString("goal_type"));
                startCoupangBrowse(goalPreparation.id(),false,0);return;
            }
            if (goalPreparation.browsing()) {gov24Guide=false;}
            if (goalPreparation.browsing()) { startCoupangBrowse(goalPreparation.id(),false,0);return; }
            GoalCatalogClassifier.Match match = goalClassifier == null ? null
                    : goalClassifier.executableGoal(goalPreparation.goal.optString("goal_type"));
            if (match == null) { speak("이 안내를 시작하려면 앱을 업데이트해 주세요."); return; }
            execution.setAcceptedGoal(goalPreparation.goal);
            activeGoalMatch = match;
            goalResolutionId = goalPreparation.id();
            if(taskFlowView.adjustmentDelegated())adjustmentDelegation.grant(execution.acceptedGoal().optString("goal_type"),System.currentTimeMillis());
            startSelectedTask();
            return;
        }
        if (requirementReview == null || execution.paused() || execution.busy() || execution.acceptedGoal() != null) return;
        voiceReplyEnabled = true;
        try {
            if (agentGateway.currentContract() != null && agentGateway.currentContract().goalPreparation) {
                // A local choice recovers the question UI, never a server preparation ID.
                if (requirementReview.proposedGoal(goalClassifier, agentGateway.currentContract()) == null) {
                    showRequirementReview(); return;
                }
                resolveGoalOnServer(requirementReview.preparationSource(), null);
                return;
            }
            JSONObject goal = requirementReview.confirm(goalClassifier, agentGateway.currentContract());
            if (goal == null) { showRequirementReview(); return; }
            activeGoalMatch = goalClassifier.executableGoal(goal.optString("goal_type"));
            execution.setAcceptedGoal(goal); goalSelection = null; goalResolutionId = null;
            initialGoal = requirementReview.request() + "\n사용자가 확인한 요청: " + requirementReview.summary();
            taskFlowView.setRequirements(requirementReview);
            startSelectedTask();
        } catch (org.json.JSONException error) { showQueueError(error); }
    }

    private void startSelectedTask() {
        if(deviceJournal!=null && execution.acceptedGoal()!=null)try {
            JSONObject slots=execution.acceptedGoal().optJSONObject("slots");
            if(slots!=null)deviceJournal.prepare(execution.sessionId(),execution.episodeId(),execution.acceptedGoal().optString("goal_type"),slots.optString("setting"),slots.optString("state"));
        }catch(java.io.IOException failure){automationNeedsHelp("기기 진행 기록을 저장하지 못했어요. 조작을 시작하지 않을게요.");return;}
        execution.retry();
        presentationGate.clear();
        clearMissingScreenWait();
        stopNarration();
        ensureTaskFlow(); taskFlowView.waiting("사용자 요청을 처리하고 있어요");
        placeTaskFlow(null, false);
        final int generation = execution.generation();
        speechHandler.postDelayed(() -> {
            if (generation == execution.generation() && !execution.paused()) sendAgentTurn(null, true);
        }, 250L);
    }

    private void startCoupangBrowse(String resolution,boolean restoring,int version) {
        try {
            if (coupangBrowse!=null)coupangBrowse.close();
            hideGuideOverlay();hideConfirmation();automationGuard.interrupt();actionTracker.clear();
            goalPreparation=null;goalResolutionId=null;requirementReview=null;goalSelection=null;
            var gateway=gov24Guide?new com.click.data.remote.CoupangBrowseGateway(this,"/api/v1/agent/public-guide/commands","public-guide.v1")
                :new com.click.data.remote.CoupangBrowseGateway(this);
            CoupangBrowseController.ScreenFilter privacy=gov24Guide?new com.click.data.snapshot.Gov24LoginPrivacy(gov24Civil)::filter
                :new com.click.data.snapshot.CoupangBrowsePrivacy(this)::filter;
            coupangBrowse=new CoupangBrowseController(gateway,privacy,gov24Guide?"kr.go.minwon.m":"com.coupang.mobile",
                gov24Guide?"public-guide.v1":"coupang-browse.v1",new CoupangBrowseController.Listener(){
                public void busy(boolean value){setTurnInFlight(value);}
                public boolean localGuide(ScreenSnapshot screen){return gov24Guide?showGov24EntryGuide():showCoupangNativeGuide(screen);}
                public boolean canObserve(){return !closing && !execution.paused() && execution.pendingControl()==null;}
                public void failed(Throwable error,Runnable retry){
                    if(closing || coupangBrowse==null || execution.paused())return;
                    hideGuideOverlay();ensureTaskFlow();
                    taskFlowView.resolutionUnavailable(initialGoal,gov24Guide?"정부24 안내에 연결하지 못했어요. 다시 확인해 주세요.":"쿠팡 화면 안내에 연결하지 못했어요. 다시 확인해 주세요.",retry);
                    placeTaskFlow(null,true);
                }
                public void present(JSONObject response,ScreenSnapshot submitted){
                    if(closing || coupangBrowse==null || execution.paused())return;
                    showCoupangBrowse(response,submitted);
                }
            });
            execution.setStarted(true);execution.clearObservation();
            if(restoring)coupangBrowse.restore(execution.sessionId(),version);
            else coupangBrowse.start(execution.sessionId(),resolution);
            if(gov24Guide&&!"kr.go.minwon.m".equals(foregroundPackage()))openCoupang();
            coupangBrowse.screenChanged(ClickAccessibilityService.freshSnapshot());
        }catch(Exception failure){showQueueError(failure);}
    }

    private void showCoupangBrowse(JSONObject response,ScreenSnapshot submitted) {
        if(gov24Guide && "IN_PROGRESS".equals(response.optString("status")) && showGov24EntryGuide())return;
        if(!gov24Guide && "IN_PROGRESS".equals(response.optString("status")) && showCoupangNativeGuide(ClickAccessibilityService.freshSnapshot()))return;
        if(!"IN_PROGRESS".equals(response.optString("status")) && coupangNativeGuide!=null)clearTargetHelpWait();
        hideGuideOverlay();ensureTaskFlow();
        boolean terminal=!"IN_PROGRESS".equals(response.optString("status"));
        String message=response.optString("message");
        JSONObject publicQuestion=response.optJSONObject("question");
        boolean nativeProviderSelection=gov24Guide&&!gov24Civil&&publicQuestion!=null
                &&"인증기관".equals(publicQuestion.optString("label"));
        if(nativeProviderSelection)message="원하는 민간 인증서를 선택해 주세요.";
        int[] publicTarget=null;
        if(gov24Guide) {
            gov24EntryPresentation=null;
            if(terminal) {
                stopListening();
                message="handoff".equals(response.optString("outcome"))
                    ?(message.contains("동의")?"동의를 확인해 주세요.":"직접 인증해 주세요.")
                    :"BLOCKED".equals(response.optString("status"))?"안내를 계속할 수 없어요.":"안내를 마쳤어요.";
            } else if(message.contains("현재 화면 확인"))message="kr.go.minwon.m".equals(foregroundPackage())
                ?"로그인 버튼을 찾고 있어요.":"정부24를 여는 중이에요.";
        }
        ScreenSnapshot current=ClickAccessibilityService.freshSnapshot();
        if(!terminal && !response.isNull("target_node_id") && submitted!=null && current!=null
                && submitted.contentFingerprint().equals(current.contentFingerprint())) {
            org.json.JSONArray bounds=response.optJSONArray("target_bounds");
            if(bounds!=null && bounds.length()==4) {
                gov24TargetRefreshes=0;
                publicTarget=new int[]{bounds.optInt(0),bounds.optInt(1),bounds.optInt(2),bounds.optInt(3)};
                lastSubmittedScreen=submitted;
                showGuideOverlay(new int[]{bounds.optInt(0),bounds.optInt(1),bounds.optInt(2),bounds.optInt(3)},
                    "scroll".equals(response.optString("action_kind"))?"scroll":"tap",GuideDragDirection.UNKNOWN);
            }
        } else if(!terminal && !response.isNull("target_node_id")) {
            message=gov24Guide?"화면을 다시 확인하고 있어요.":"화면이 바뀌었어요. 현재 화면 확인을 눌러 새 안내를 받아주세요.";
            if(gov24Guide&&gov24TargetRefreshes++<2) {
                CoupangBrowseController owner=coupangBrowse;
                speechHandler.postDelayed(()->{if(!closing&&coupangBrowse==owner&&owner!=null&&!owner.terminal())
                    owner.observe(ClickAccessibilityService.freshSnapshot());},350);
            }
        }
        if(gov24Guide) {
            taskFlowView.publicGuide(message,terminal,response.optJSONObject("question"),gov24Civil,
                (value,button)->{if(coupangBrowse!=null)coupangBrowse.answer(ClickAccessibilityService.freshSnapshot(),value,button);},
                ()->{if(coupangBrowse==null||endingTask)return;if(!terminal){coupangBrowse.finish();return;}
                    endingTask=true;clearTaskFlow();finishActiveQueueRequest("BLOCKED".equals(response.optString("status"))?"AGENT_ERROR":null);});
        } else taskFlowView.browsing(message,terminal,
            ()->{if(coupangBrowse!=null)coupangBrowse.observe(ClickAccessibilityService.freshSnapshot());},
            this::openCoupang,
            ()->{
                if(coupangBrowse==null || endingTask)return;
                if(!terminal){coupangBrowse.finish();return;}
                endingTask=true;
                String code="BLOCKED".equals(response.optString("status"))?"AGENT_ERROR":null;
                clearTaskFlow();finishActiveQueueRequest(code);
            });
        taskFlowView.setAutomaticProgress(false);taskFlowCollapsed=false;
        if(overlayView!=null)overlayView.collapse();updateFloatingVisibility();placeTaskFlow(gov24Guide?publicTarget:null,true);
        speak(message);
        if(nativeProviderSelection)collapseTaskFlow();
        if(gov24Guide&&terminal)voiceReplyEnabled=false;
    }

    private boolean showGov24EntryGuide() {
        if(!gov24Guide||gov24Civil||closing)return false;
        var target=ClickAccessibilityService.gov24LoginEntry();
        if(target==null){gov24EntryPresentation=null;return false;}
        boolean visible=target.visible&&target.bounds[0]>=0&&target.bounds[1]>=0
                &&target.bounds[2]<=screenWidth()&&target.bounds[3]<=screenHeight();
        if(!visible&&gov24EntryRevealCount<3&&SystemClock.uptimeMillis()-gov24EntryRevealAt>=500) {
            gov24EntryRevealAt=SystemClock.uptimeMillis();gov24EntryRevealCount++;
            ClickAccessibilityService.revealGov24LoginEntry();
            speechHandler.postDelayed(ClickAccessibilityService::requestFreshSnapshot,400);
        }
        String key=target.windowId+":"+target.nodeId+":"+java.util.Arrays.toString(target.bounds)+":"+visible;
        if(key.equals(gov24EntryPresentation)&&taskFlowView!=null)return true;
        gov24EntryPresentation=key;
        hideGuideOverlay();ensureTaskFlow();
        String message=visible?"로그인을 눌러주세요.":"로그인 버튼을 찾고 있어요.";
        taskFlowView.progress(null,null,message,false);taskFlowView.setAutomaticProgress(false);taskFlowCollapsed=false;
        if(overlayView!=null)overlayView.collapse();updateFloatingVisibility();placeTaskFlow(visible?target.bounds:null,false);
        if(visible)showGuideOverlay(target.bounds,"tap",GuideDragDirection.UNKNOWN);
        speak(message);return true;
    }

    private void openCoupang() {
        Intent launch=getPackageManager().getLaunchIntentForPackage(gov24Guide?"kr.go.minwon.m":"com.coupang.mobile");
        if(launch==null){speak(gov24Guide?"정부24 앱이 설치되어 있는지 확인해 주세요.":"기기에 쿠팡 앱이 설치되어 있는지 확인해 주세요.");return;}
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(launch);
    }

    private void sendResult(String answer) {
        if (resultPrompt == null || execution.busy() || execution.paused()) return;
        AgentTurnContract.Request request = AgentTurnContract.Request.control(execution.sessionId(),
                execution.sessionVersion(), execution.episodeId(), "respond_result", null);
        request.resultPromptId = resultPrompt.optString("id"); request.userResult = answer;
        sendFlowRequest(request);
    }

    private void sendAdjustment(String reason) {
        if (followUp == null || execution.busy() || execution.paused()) return;
        if ("other".equals(reason)) {
            processVipRequest("다른 도움이 필요해요", true);
            return;
        }
        AgentTurnContract.Request request = AgentTurnContract.Request.control(execution.sessionId(),
                execution.sessionVersion(), execution.episodeId(), "adjust_task", null);
        request.adjustmentReason = reason; sendFlowRequest(request);
    }

    private void contractFailure(){contractFailure(null);}

    private void contractFailure(Throwable error){
        adjustmentDelegation.clear();pendingApprovalProvenance=null;
        hideGuideOverlay();hideConfirmation();stopNarration();automationGuard.interrupt();actionTracker.clear();
        execution.stop();
        boolean upgrade=error instanceof com.click.data.remote.ExecutionState.ContractMismatch
            && com.click.data.remote.ExecutionState.ContractMismatch.SERVER_UPGRADE_REQUIRED.equals(((com.click.data.remote.ExecutionState.ContractMismatch)error).code);
        showStoppedTask(upgrade?error.getMessage():"서버와 앱의 작업 기록이 맞지 않아 안내를 멈췄어요. 앱과 서버를 같은 버전으로 업데이트해 주세요.");
    }

    private boolean acceptExecutionState(AgentTurnContract.Response response,String command,boolean snapshot) {
        if(execution.acceptResponse(response,command,snapshot)) {
            String goalType=response.executionState==null?null:response.executionState.goalType;
            String declaredPublicGoal=response.metadata==null?"":response.metadata.optString("public_guide_goal_type");
            boolean knownPublicGoal="gov24.login".equals(goalType)
                    || "gov24.resident_copy.open".equals(goalType) && goalType.equals(declaredPublicGoal);
            boolean publicLoginSnapshot=snapshot && knownPublicGoal
                    && (declaredPublicGoal.isEmpty() || declaredPublicGoal.equals(goalType))
                    && response.metadata!=null && response.metadata.optBoolean("public_guide");
            if(publicLoginSnapshot) {
                execution.setAcceptedGoal(null);
                return true;
            }
            if(goalType!=null && goalClassifier!=null) {
                GoalCatalogClassifier.Match goal=goalClassifier.executableGoal(goalType);
                if(goal==null)return false;
                try {execution.setAcceptedGoal(goal.toTypedGoalJson());}catch(org.json.JSONException invalid){return false;}
            }
            return true;
        }
        execution.recovery(TaskExecutionController.RecoveryCause.RESULT_UNKNOWN);
        adjustmentDelegation.clear();pendingApprovalProvenance=null;
        hideGuideOverlay();hideConfirmation();automationGuard.interrupt();actionTracker.clear();
        return false;
    }

    private void synchronizeTracked(String sessionId, AgentTurnGateway.Callback callback) {
        TaskExecutionController.RequestToken token=execution.beginRequest();
        agentGateway.synchronize(sessionId,new AgentTurnGateway.Callback(){
            @Override public void onSuccess(AgentTurnContract.Response response){if(execution.finishRequest(token)){if(acceptExecutionState(response,null,true))callback.onSuccess(response);else contractFailure();}}
            @Override public void onFailure(Throwable error){if(execution.finishRequest(token)){if(error instanceof com.click.data.remote.ExecutionState.ContractMismatch)contractFailure(error);else callback.onFailure(error);}}
        });
    }

    private void sendTracked(AgentTurnContract.Request request, AgentTurnGateway.Callback callback) {
        TaskExecutionController.RequestToken token=execution.beginRequest(request.commandType());
        agentGateway.send(request,new AgentTurnGateway.Callback(){
            @Override public void onSuccess(AgentTurnContract.Response response){if(execution.finishRequest(token)){if(acceptExecutionState(response,request.commandType(),false))callback.onSuccess(response);else contractFailure();}}
            @Override public void onFailure(Throwable error){if(execution.finishRequest(token)){if(error instanceof com.click.data.remote.ExecutionState.ContractMismatch)contractFailure(error);else callback.onFailure(error);}}
        });
    }

    private void sendFlowRequest(AgentTurnContract.Request request) {
        sendFlowRequest(request, true);
    }

    private void sendFlowRequest(AgentTurnContract.Request request, boolean mayRecover) {
        stopNarration();
        setTurnInFlight(true);
        updateRequestStatus(true, true);
        final int generation = execution.generation();
        sendTracked(request, new AgentTurnGateway.Callback() {
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if (generation != execution.generation() || !response.sessionId.equals(execution.sessionId())) return;
                setTurnInFlight(false);

                if(!recordDeviceVerification(request,response))return;
                if (request.accessibilityTree != null) {
                    lastSubmittedScreen = request.accessibilityTree;
                    lastSnapshotFingerprint = request.accessibilityTree.observationFingerprint();
                }
                if (response.metadata != null && response.metadata.optJSONObject("goal_selection") != null) {
                    goalSelection = response.metadata.optJSONObject("goal_selection");
                    goalResolutionId = goalSelection.optString("id", null);
                }
                if (execution.pendingControl() != null) {
                    String command = execution.pendingControl(); execution.clearControl(); sendAgentControl(command); return;
                }
                if (execution.resumeAfterPause() && "pause_task".equals(request.commandType())) {
                    execution.clearQueuedResume();
                    sendAgentControl("resume_task"); return;
                }
                applyTaskFlowResponse(response);
            }
            @Override public void onFailure(Throwable error) {
                if (generation != execution.generation()) return;
                setTurnInFlight(false);
                if (execution.pendingControl() != null) {
                    String command = execution.pendingControl(); execution.clearControl(); recoverControl(command); return;
                }
                if(deviceJournal!=null && deviceJournal.plan(execution.sessionId())!=null && java.util.Set.of("request_result","respond_result").contains(request.commandType()) && !execution.paused()) {showOfflineReceipt();return;}
                if (mayRecover && ("cancel_task".equals(request.commandType()) || "pause_task".equals(request.commandType())
                        || "resume_task".equals(request.commandType()))) {
                    recoverControl(request.commandType()); return;
                }
                if ("pause_task".equals(request.commandType()) || "resume_task".equals(request.commandType())) {
                    execution.clearQueuedResume();
                    haltLocalExecution(); showPausedTask(); return;
                }
                showQueueError(error);
            }
        });
    }

    private void recoverControl(String command) {
        final int generation = execution.generation();
        execution.pause();
        if ("resume_task".equals(command)) execution.requestResume();
        setTurnInFlight(true);
        synchronizeTracked(execution.sessionId(), new AgentTurnGateway.Callback() {
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if (generation != execution.generation()) return;
                setTurnInFlight(false);
                if ("CANCELLED".equals(response.taskStatus) || "EXPIRED".equals(response.taskStatus)
                        || "COMPLETED".equals(response.taskStatus) || "BLOCKED".equals(response.taskStatus)) {
                    applyTaskFlowResponse(response); return;
                }
                if ("pause_task".equals(command) && "PAUSED".equals(response.taskStatus)
                        || "resume_task".equals(command) && !"PAUSED".equals(response.taskStatus)) {
                    if (execution.resumeAfterPause() && "pause_task".equals(command)) {
                        execution.clearQueuedResume();
                        sendAgentControl("resume_task"); return;
                    }
                    applyTaskFlowResponse(response); return;
                }
                sendFlowRequest(AgentTurnContract.Request.control(execution.sessionId(), execution.sessionVersion(),
                        execution.episodeId(), command, null), false);
            }
            @Override public void onFailure(Throwable error) {
                if (generation != execution.generation()) return;
                setTurnInFlight(false);
                if (error instanceof com.click.data.remote.ApiException && ((com.click.data.remote.ApiException) error).status == 404
                        && "cancel_task".equals(command)) {
                    abortActiveRequest(); finishActiveQueueRequest("USER_ABORTED"); return;
                }
                if (error instanceof com.click.data.remote.ApiException
                        && ((com.click.data.remote.ApiException) error).status == 404) {
                    execution.resetCursor(); execution.setStarted(false);
                }
                execution.clearQueuedResume();
                haltLocalExecution(); showPausedTask();
                if (overlayView != null) overlayView.showInputFeedback(
                        "기기에서 멈췄어요. 연결을 확인한 뒤 이어서 진행해 주세요.");
            }
        });
    }

    private boolean waitingForDeclinedEnd;

    private void showUserDeclined(AgentTurnContract.Response response) {
        execution.invalidate();
        haltLocalExecution(); execution.stop(); execution.clearControl(); execution.clearQueuedResume();
        agentConfirmationId=null; confirmationExpiresAt=0;
        resultPrompt=followUp=null; pendingReplacement=null;
        setTurnInFlight(false); updateRequestStatus(false,false);
        waitingForDeclinedEnd=true; endingTask=false;
        JSONObject termination=response.metadata.optJSONObject("termination");
        String message=termination.optString("message","추가로 조절하지 않고 안내를 마칠게요.");
        ensureTaskFlow(); taskFlowView.userDeclined(message,this::finishDeclinedTask);
        taskFlowCollapsed=false;
        if(overlayView!=null)overlayView.collapse();
        updateFloatingVisibility(); placeTaskFlow(null,true); speak(message);
    }

    private void finishDeclinedTask() {
        if(closing || endingTask || !waitingForDeclinedEnd)return;
        endingTask=true; advanceAfterUserEnd=true;
        finishActiveQueueRequest("USER_DECLINED");
    }

    private void applyTaskFlowResponse(AgentTurnContract.Response response) {
        if (!execution.receiveStatus(response.taskStatus)) return;
        if(deviceJournal!=null && java.util.Set.of("COMPLETED","CANCELLED","EXPIRED","BLOCKED").contains(response.taskStatus))try {deviceJournal.finish(response.sessionId);}catch(java.io.IOException ignored){}
        if (("COMPLETED".equals(response.taskStatus) || response.guideCommand != null
                && "done".equals(response.guideCommand.commandType)) && !CompletionEvidence.accepted(response.metadata, execution.acceptedGoal() == null ? null : execution.acceptedGoal().optString("goal_type"))) {
            showStoppedTask("변경 결과를 확인하지 못했어요. 완료로 처리하지 않고 안내를 멈췄어요.");
            return;
        }
        JSONObject termination=response.metadata==null?null:response.metadata.optJSONObject("termination");
        if("CANCELLED".equals(response.taskStatus) && termination!=null
                && "USER_CHOICE".equals(termination.optString("category"))
                && "USER_DECLINED".equals(termination.optString("code"))) {
            showUserDeclined(response); return;
        }
        JSONObject prompt = response.metadata == null ? null : response.metadata.optJSONObject("result_prompt");
        if ("WAITING_RESULT".equals(response.taskStatus) && prompt != null
                && !presentationGate.accept(execution.episodeId(), "result", prompt.optString("id") + ":" + prompt.optString("message"))) return;
        if ("BLOCKED".equals(response.taskStatus) || response.guideCommand != null
                && "blocked".equals(response.guideCommand.commandType)) {
            response.logDebug("ClickAgent");
            showStoppedGuide(response);
            return;
        }
        if (!"IN_PROGRESS".equals(response.taskStatus)
                && !"WAITING_CONFIRMATION".equals(response.taskStatus)) clearTargetHelpWait();
        updateRequestStatus(true, false);
        ensureTaskFlow();
        if (resultTimeout != null) speechHandler.removeCallbacks(resultTimeout);
        if ("WAITING_GOAL".equals(response.taskStatus)) {
            goalSelection = response.metadata.optJSONObject("goal_selection");
            goalResolutionId = goalSelection.optString("id", null);
            if (execution.paused()) { taskFlowView.paused(); return; }
            JSONObject automatic = goalSelection.optJSONObject("automatic_goal");
            if (automatic != null) { chooseResolvedGoal(automatic); return; }
            taskFlowView.goals(response.message, goalSelection.optJSONArray("candidates"));
            placeTaskFlow(null, true);
            String choices = "";
            org.json.JSONArray candidates = goalSelection.optJSONArray("candidates");
            if (candidates != null) for (int i = 0; i < candidates.length(); i++)
                choices += " " + (i + 1) + "번, " + candidates.optJSONObject(i).optString("label") + ".";
            speak(response.message + choices + " 잘 모르겠어요, 다른 뜻이에요라고 답해도 돼요.");
            return;
        }
        goalSelection = null;
        if ("PAUSED".equals(response.taskStatus)) {
            haltLocalExecution(); showPausedTask(); speak(response.message); return;
        }
        resultPrompt = response.metadata == null ? null : response.metadata.optJSONObject("result_prompt");
        followUp = response.metadata == null ? null : response.metadata.optJSONObject("follow_up");
        if ("WAITING_RESULT".equals(response.taskStatus) && resultPrompt != null) {
            JSONObject outcome = response.metadata.optJSONObject("task_outcome");
            JSONObject technical = outcome == null ? null : outcome.optJSONObject("technical");
            if (technical != null) try {
                String status=technical.optString("status","UNVERIFIABLE");
                if (java.util.Set.of("CHANGED","ALREADY_SET","OPENED").contains(status)
                        && !com.click.data.snapshot.TaskSuccess.verified(technical,execution.acceptedGoal()==null?null:execution.acceptedGoal().optString("goal_type"))) {
                    status="UNVERIFIABLE";
                    resultPrompt.put("message","작업 결과를 직접 확인해 주세요. 원하신 결과를 얻었나요?");
                }
                resultPrompt.put("technical_status",status);
            }
            catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
            actionTracker.clear(); pendingVerifiedAction = null;
            adjustmentResultCheck.clear(); clearActionEvidenceWait();
            hideGuideOverlay(); hideConfirmation();
            taskFlowView.setChecklist(response.metadata.optJSONArray("checklist"));
            boolean fontSize = execution.acceptedGoal() != null && SettingsAdjustment.forGoal(
                    execution.acceptedGoal().optString("goal_type")) == SettingsAdjustment.FONT_SIZE;
            taskFlowView.result(resultPrompt, fontSize);
            taskFlowCollapsed = false;
            if (overlayView != null) overlayView.collapse();
            updateFloatingVisibility();
            placeTaskFlow(null, true);
            speak(TaskFlowOverlayView.resultNarration(resultPrompt, fontSize));
            final String promptId = resultPrompt.optString("id");
            final int generation = execution.generation();
            resultTimeout = () -> {
                if (generation == execution.generation() && resultPrompt != null && !execution.paused()
                        && promptId.equals(resultPrompt.optString("id"))) sendResult("no_response");
            };
            speechHandler.postDelayed(resultTimeout,
                    Math.max(1000L, resultPrompt.optLong("expires_at") * 1000L - System.currentTimeMillis() + 500L));
            return;
        }
        if ("WAITING_ADJUSTMENT".equals(response.taskStatus) && followUp != null) {
            taskFlowView.followUp(followUp); placeTaskFlow(null, true);
            speak(response.message == null ? followUp.optString("message") : response.message); return;
        }
        if ("CANCELLED".equals(response.taskStatus) || "COMPLETED".equals(response.taskStatus)
                || "EXPIRED".equals(response.taskStatus)) {
            String next = pendingReplacement; pendingReplacement = null;
            updateRequestStatus(false, false);
            boolean satisfied = "COMPLETED".equals(response.taskStatus);
            String message = response.message;
            hideGuideOverlay(); hideConfirmation();
            execution.invalidate();
        automationGuard.interrupt();
        navigationRecovery.clear();
        homeSearchRestart = false;
            if (satisfied && taskFlowView != null) {
                showCompletedTask(message,CompletionEvidence.verified(response.metadata,execution.acceptedGoal()==null?null:execution.acceptedGoal().optString("goal_type")));
                if (next != null) enqueueNormalRequest(next);
            } else {
                if (message != null) speak(message);
                clearTaskFlow();
                boolean expired = "EXPIRED".equals(response.taskStatus);
                if (expired) {
                    execution.pause();
                    ensureTaskFlow();
                    taskFlowView.expired(() -> {
                        clearTaskFlow();
                        if (overlayView != null) overlayView.openMenu();
                    });
                    taskFlowCollapsed = false;
                    if (overlayView != null) overlayView.collapse();
                    placeTaskFlow(null, true);
                    updateFloatingVisibility();
                }
                finishActiveQueueRequest("USER_ABORTED");
                if (next != null) enqueueNormalRequest(next);
            }
            return;
        }
        if (response.guideCommand != null) renderGuide(response);
        else { execution.setStarted(true); sendAgentTurn(null, true); }
    }

    private boolean handleTaskFlowAnswer(String text) {
        if (execution.sessionId() == null) return false;
        if (TaskFlowAnswers.stop(text)) { onStopRequested(); return true; }
        String normalized = GoalCatalogClassifier.normalize(text);
        if (normalized.equals("일시정지") || normalized.equals("잠깐만")) {
            sendAgentControl("pause_task"); return true;
        }
        if (normalized.equals("이어서진행") || normalized.equals("재개") || normalized.equals("계속해")) {
            sendAgentControl("resume_task"); return true;
        }
        if (resultPrompt != null) {
            String answer = TaskFlowAnswers.result(text);
            if (answer != null) sendResult(answer);
            else if (execution.acceptedGoal() != null && SettingsAdjustment.forGoal(
                    execution.acceptedGoal().optString("goal_type")) == SettingsAdjustment.FONT_SIZE)
                speak("네, 글씨가 잘 보여요 또는 더 커졌으면 좋겠어 중 하나로 답해 주세요.");
            else speak("좋아요 또는 조금 더 도와주세요 중 하나로 답해 주세요.");
            return true;
        }
        if (followUp != null) {
            String reason = TaskFlowAnswers.adjustment(text);
            if (reason != null) sendAdjustment(reason);
            else speak(followUp.optString("message"));
            return true;
        }
        if (goalPreparation != null && execution.acceptedGoal() == null) {
            if (execution.paused() || execution.busy()) return true;
            if (goalPreparation.ready() && normalized.matches("네|예|맞아|맞아요|이대로시작|이내용으로시작|시작해|시작해주세요"))
                confirmRequirementReview();
            else if (goalPreparation.needsInput()) {
                String value = goalPreparation.exactSpokenChoice(text);
                if (value != null) answerGoalInput(value);
                else speak("화면의 선택지에서 원하시는 내용을 골라 주시겠어요?");
            } else resolveGoal(text, null);
            return true;
        }
        if (requirementReview != null && !requirementReview.confirmed()) {
            if (normalized.matches("네|예|맞아|맞아요|이대로시작|이내용으로시작|시작해|시작해주세요")) confirmRequirementReview();
            else updateRequirements(text);
            return true;
        }
        if (goalSelection != null) {
            if (normalized.contains("모르겠")) resolveGoal(initialGoal, "unsure");
            else if (normalized.contains("다른뜻")) resolveGoal(initialGoal, "different");
            else {
                org.json.JSONArray candidates = goalSelection.optJSONArray("candidates");
                JSONObject selected = null;
                if (candidates != null) for (int i = 0; i < candidates.length(); i++) {
                    JSONObject candidate = candidates.optJSONObject(i);
                    JSONObject goal = candidate.optJSONObject("goal");
                    String direction = goal == null ? "" : goal.optJSONObject("slots").optString("direction");
                    boolean named = "increase".equals(direction) && (normalized.equals("크게") || normalized.equals("글자를크게"))
                            || "decrease".equals(direction) && (normalized.equals("작게") || normalized.equals("글자를작게"));
                    if (named || normalized.equals((i + 1) + "번") || normalized.equals(GoalCatalogClassifier.normalize(candidate.optString("label"))))
                        selected = goal;
                }
                if (selected != null) chooseResolvedGoal(selected);
                else resolveGoal(text, null);
            }
            return true;
        }
        return false;
    }

    /** Called when the accessibility service connects, so the overlay can move to an
     * accessibility overlay window that stays visible on top of the Settings app. */
    public static void onAccessibilityOverlayAvailable() {
        ClickOverlayService service = runningService.get();
        if (service != null) {
            service.rebindWindows(ClickAccessibilityService.accessibilityWindowManager(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        }
    }

    /** Called when the accessibility service disconnects, so the overlay falls back to a
     * regular application overlay window. */
    public static void onAccessibilityOverlayLost() {
        ClickOverlayService service = runningService.get();
        if (service != null) {
            service.rebindWindows(service.getSystemService(WindowManager.class),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
    }

    private void rebindWindows(WindowManager target, int windowType) {
        if (target == null || overlayView == null || windowParams == null
                || target == windowManager && overlayWindowType == windowType
                    && overlayView.isAttachedToWindow()
                    && (taskFlowView == null || taskFlowView.isAttachedToWindow())
                    && (feedbackView == null || feedbackView.isAttachedToWindow())) {
            return;
        }
        detachGuideOverlay();
        hideConfirmation();
        hideTrashTarget();
        detachTrashTarget();
        if (snapAnimator != null) snapAnimator.cancel();
        if (windowManager != null && overlayView.isAttachedToWindow()) {
            try {
                windowManager.removeViewImmediate(overlayView);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (windowManager != null && taskFlowView != null && taskFlowView.isAttachedToWindow()) {
            try { windowManager.removeViewImmediate(taskFlowView); }
            catch (IllegalArgumentException ignored) { }
        }
        if (windowManager != null && feedbackView != null && feedbackView.isAttachedToWindow()) {
            try { windowManager.removeViewImmediate(feedbackView); }
            catch (IllegalArgumentException ignored) { }
        }
        windowManager = target;
        overlayWindowType = windowType;
        windowParams.type = windowType;
        if (trashDropParams != null) trashDropParams.type = windowType;
        try {
            attachGuideOverlay();
            attachTrashTarget();
            windowManager.addView(overlayView, windowParams);
            if (taskFlowView != null && taskFlowParams != null) {
                taskFlowParams.type = windowType;
                addOverlayView(taskFlowView, taskFlowParams);
            }
            if (feedbackView != null && feedbackParams != null) {
                feedbackParams.type = windowType;
                addOverlayView(feedbackView, feedbackParams);
            }
            if (feedbackView != null && feedbackParams != null) {
                feedbackParams.type = windowType;
                windowManager.addView(feedbackView, feedbackParams);
            }
            updateFloatingVisibility();
        } catch (RuntimeException error) {
            android.util.Log.e("ClickOverlay", "Failed to rebind overlay window", error);
            // Do not leave a successfully restored logo hidden behind a missing task window.
            taskFlowCollapsed = true;
            if (overlayView.isAttachedToWindow()) setOverlayVisibility(overlayView, View.VISIBLE);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void snapToNearestEdge() {
        if (dragInProgress || closing || windowManager == null || windowParams == null) return;
        int maxX = Math.max(0, screenWidth() - windowParams.width);
        int maxY = Math.max(0, screenHeight() - windowParams.height);
        int left = windowParams.x;
        int right = maxX - windowParams.x;
        int top = windowParams.y;
        int bottom = maxY - windowParams.y;
        int nearest = Math.min(Math.min(left, right), Math.min(top, bottom));
        int targetX = windowParams.x;
        int targetY = windowParams.y;
        if (nearest == left) targetX = 0;
        else if (nearest == right) targetX = maxX;
        else if (nearest == top) targetY = 0;
        else targetY = maxY;
        animateWindowTo(targetX, targetY);
    }

    private void showTrashTarget() {
        if (windowManager == null || trashDropView == null) return;
        if (!trashDropView.isAttachedToWindow()) attachTrashTarget();
        overTrashTarget = false;
        trashDropView.setVisibility(View.VISIBLE);
        trashDropView.setActive(false);
        trashDropView.setAlpha(0f);
        trashDropView.setScaleX(.72f);
        trashDropView.setScaleY(.72f);
        trashDropView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180L).start();
    }

    private void hideTrashTarget() {
        overTrashTarget = false;
        if (windowManager != null && trashDropView != null && trashDropView.isAttachedToWindow()) {
            trashDropView.animate().cancel();
            trashDropView.setVisibility(View.GONE);
        }
    }

    private void attachTrashTarget() {
        if (windowManager == null || trashDropView == null || trashDropView.isAttachedToWindow()) return;
        trashDropView.setVisibility(View.GONE);
        windowManager.addView(trashDropView, trashDropParams);
    }

    private void detachTrashTarget() {
        if (windowManager != null && trashDropView != null && trashDropView.isAttachedToWindow())
            windowManager.removeViewImmediate(trashDropView);
    }

    private void updateTrashTargetState() {
        if (trashDropView == null || !trashDropView.isAttachedToWindow() || overlayView == null) return;
        int[] trashLocation = new int[2];
        trashDropView.getLocationOnScreen(trashLocation);
        float iconX = dragTargetX + dragWindowOffsetX
                + overlayView.getClickCenterX(overlayView.getMode());
        float iconY = dragTargetY + dragWindowOffsetY
                + overlayView.getClickCenterY(overlayView.getMode());
        float trashX = trashLocation[0] + trashDropView.getWidth() / 2f;
        float trashY = trashLocation[1] + trashDropView.getHeight() / 2f;
        overTrashTarget = Math.hypot(iconX - trashX, iconY - trashY) <= dp(68);
        trashDropView.setActive(overTrashTarget);
    }

    private void animateWindowTo(int targetX, int targetY) {
        final int startX = windowParams.x;
        final int startY = windowParams.y;
        snapAnimator = ValueAnimator.ofFloat(0f, 1f);
        snapAnimator.setDuration(240L);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(animation -> {
            if (windowManager == null || windowParams == null) return;
            float progress = (float) animation.getAnimatedValue();
            windowParams.x = Math.round(startX + (targetX - startX) * progress);
            windowParams.y = Math.round(startY + (targetY - startY) * progress);
            windowManager.updateViewLayout(overlayView, windowParams);
            placeProgressBubble();
        });
        snapAnimator.start();
    }

    private void clampWindowPosition() {
        windowParams.x = Math.max(0, Math.min(windowParams.x, screenWidth() - windowParams.width));
        windowParams.y = Math.max(0, Math.min(windowParams.y, screenHeight() - windowParams.height));
    }

    private void updateFocusableFlag(boolean focusable) {
        if (focusable) windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    }

    public static void onKeyboardWindowChanged() {
        ClickOverlayService service = runningService.get();
        if (service == null || service.overlayView == null) return;
        service.overlayView.removeCallbacks(service.keyboardPlacement);
        service.overlayView.post(service.keyboardPlacement);
        service.overlayView.post(service::fitTaskFlowAboveKeyboard);
    }

    private void fitTaskFlowAboveKeyboard() {
        if (taskFlowView == null || !taskFlowView.isAttachedToWindow() || taskFlowHeightLimit <= 0 || closing) return;
        if (taskFlowView.isMinimizedProgress()) { placeProgressBubble(); return; }
        Rect keyboard = ClickAccessibilityService.keyboardBounds();
        int height = taskFlowHeightLimit;
        taskFlowParams.gravity = taskFlowRestingGravity;
        // A floating IME/physical-keyboard toolbar must not collapse the entire card.
        boolean docked = keyboard != null && FloatingPanelPosition.isDockedKeyboard(
                screenHeight(), keyboard.top, keyboard.bottom, dp(64));
        if (docked) {
            android.view.WindowInsets insets = taskFlowView.getRootWindowInsets();
            int top = taskFlowParams.y + (insets == null ? dp(24) : insets.getSystemWindowInsetTop());
            if (taskFlowView.isLaidOut() && (appliedTaskFlowGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) {
                int[] location = new int[2]; taskFlowView.getLocationOnScreen(location);
                top = location[1];
            }
            height = Math.min(height, Math.max(0, keyboard.top - top - dp(12)));
            taskFlowParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        }
        if (appliedTaskFlowHeight == height && appliedTaskFlowFlags == taskFlowParams.flags
                && appliedTaskFlowGravity == taskFlowParams.gravity) return;
        appliedTaskFlowHeight = height; appliedTaskFlowFlags = taskFlowParams.flags;
        appliedTaskFlowGravity = taskFlowParams.gravity;
        taskFlowView.setAvailableHeight(height);
        windowManager.updateViewLayout(taskFlowView, taskFlowParams);
    }

    private void fitTextAboveKeyboard() {
        if (overlayView == null || windowManager == null || windowParams == null
                || !overlayView.isAttachedToWindow() || closing
                || overlayView.getMode() != ClickOverlayLayout.Mode.TEXT) return;
        Rect keyboard = ClickAccessibilityService.keyboardBounds();
        int bottom = screenHeight();
        if (keyboard != null) {
            int[] location = new int[2];
            overlayView.getLocationOnScreen(location);
            int windowOrigin = location[1] - windowParams.y;
            bottom = Math.min(bottom, keyboard.top - windowOrigin - dp(12));
        }
        FloatingPanelPosition placement = FloatingPanelPosition.fit(textRestingY,
                overlayView.getWindowHeight(ClickOverlayLayout.Mode.TEXT), 0, bottom);
        if (windowParams.y == placement.y && windowParams.height == placement.height) return;
        if (snapAnimator != null) snapAnimator.cancel();
        windowParams.y = placement.y;
        windowParams.height = placement.height;
        windowManager.updateViewLayout(overlayView, windowParams);
    }

    @Override public void onContentSizeChanged() {
        if (overlayView == null || windowManager == null || windowParams == null
                || !overlayView.isAttachedToWindow() || closing) return;
        if (overlayView.getMode() == ClickOverlayLayout.Mode.TEXT) { fitTextAboveKeyboard(); return; }
        int height = overlayView.getWindowHeight(overlayView.getMode());
        if (windowParams.height == height) return;
        windowParams.height = height;
        clampWindowPosition();
        windowManager.updateViewLayout(overlayView, windowParams);
    }

    @Override public void onMicrophoneClicked() {
        if (listening) onMicrophoneReleased();
        else onMicrophonePressed();
    }

    @Override public void onMicrophonePressed() {
        if (acceptingVoiceCommand || speechProcessing) return;
        if (closing || overlayView == null || overlayView.getMode() != ClickOverlayLayout.Mode.VOICE) return;
        stopNarration();
        acceptingVoiceCommand = true;
        startListening();
    }

    @Override public void onMicrophoneReleased() {
        if (!acceptingVoiceCommand || !listening || speechInput == null) return;
        listening = false;
        speechProcessing = true;
        overlayView.setVoiceInputActive(false);
        overlayView.setVoiceInputProcessing(true);
        overlayView.setRecognizedSpeech("말씀하신 내용을 확인하고 있어요…");
        refreshLoadingStatus();
        speechInput.finish();
    }

    @Override public void onMicrophoneCancelled() {
        if (!acceptingVoiceCommand) return;
        stopListening();
        overlayView.showVoiceInputError("음성 입력을 취소했어요. 마이크를 꾹 눌러 다시 말씀해 주세요.");
    }

    private boolean acceptsSpeech(int generation) {
        return generation == speechGeneration && acceptingVoiceCommand && !closing
                && overlayView != null && overlayView.getMode() == ClickOverlayLayout.Mode.VOICE;
    }

    private void startListening() {
        if (closing || !acceptingVoiceCommand || listening || overlayView == null
                || overlayView.getMode() != ClickOverlayLayout.Mode.VOICE) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopListening();
            overlayView.showVoiceInputError("말로 입력하시려면 마이크 사용을 허용해 주세요.");
            return;
        }
        final int generation = ++speechGeneration;
        overlayView.setVoiceInputActive(true);
        // Use the recognition service configured in Android; no Click audio upload.
        if (speechInput == null) {
            stopListening();
            overlayView.showVoiceInputError("지금은 음성 입력에 연결하기 어려워요. 글로 적어 주시면 이어서 도와드릴게요.");
            return;
        }
        listening = true;
        refreshLoadingStatus();
        overlayView.setRecognizedSpeech("마이크를 준비하고 있어요…");
        final TaskFlowOverlayView editOwner = taskFlowView;
        final java.util.function.Function<String, Boolean> editTarget = editOwner == null
                ? null : editOwner.requestEditSpeechTarget();
        speechInput.start(new SpeechInput.Callback() {
            @Override public void onReady() {
                if (acceptsSpeech(generation) && listening)
                    overlayView.setRecognizedSpeech("듣고 있어요. 말씀을 마치면 손을 떼세요.");
            }
            @Override public void onPartialText(String text) {
                if (acceptsSpeech(generation) && listening) overlayView.setRecognizedSpeech(text);
            }
            @Override public void onRecordingFinished() {
                if (!acceptsSpeech(generation)) return;
                listening = false;
                speechProcessing = true;
                overlayView.setVoiceInputActive(false);
                overlayView.setVoiceInputProcessing(true);
                refreshLoadingStatus();
                overlayView.setRecognizedSpeech("말씀하신 내용을 확인하고 있어요…");
            }
            @Override public void onSuccess(String text) {
                if (!acceptsSpeech(generation)) return;
                stopListening();
                submitRecognizedSpeech(text, editTarget == null ? null
                        : value -> taskFlowView == editOwner && editTarget.apply(value));
            }
            @Override public void onFailure(Throwable error) {
                if (!acceptsSpeech(generation)) return;
                stopListening();
                // A provider failure must not reopen the microphone without another tap.
                SpeechErrorMessages.log(error);
                overlayView.showVoiceInputError(SpeechErrorMessages.message(error));
            }
        });
    }

    private void submitRecognizedSpeech(String text, java.util.function.Function<String, Boolean> editTarget) {
        if (text == null || text.trim().isEmpty()) {
            overlayView.showVoiceInputError("마이크를 꾹 눌러 다시 말씀해 주세요.");
            return;
        }
        voiceReplyEnabled = true;
        overlayView.setRecognizedSpeech(text);
        overlayView.collapse();
        if (editTarget != null) {
            if (editTarget.apply(text)) {
                taskFlowCollapsed = false;
                updateFloatingVisibility(); placeTaskFlow(null, true);
            }
            return;
        }
        handleRecognizedCommand(text);
    }

    private void stopListening() {
        speechGeneration++;
        boolean wasAccepting = acceptingVoiceCommand;
        acceptingVoiceCommand = false;
        if (wasAccepting && overlayView != null)
            overlayView.setRecognizedSpeech("어떤 도움이 필요하세요?");
        if (speechInput != null) speechInput.cancel();
        listening = false;
        speechProcessing = false;
        refreshLoadingStatus();
        if (overlayView != null) {
            overlayView.setVoiceInputActive(false);
            overlayView.setVoiceInputProcessing(false);
        }
    }

    private void setTurnInFlight(boolean active) {
        execution.setBusy(active);
        refreshLoadingStatus();
    }

    private void refreshLoadingStatus() {
        String message = visionCaptureInFlight ? "화면을 분석하고 있어요…"
                : speechProcessing ? "음성을 인식하고 있어요…"
                : listening ? "듣고 있어요. 말씀해 주세요…"
                : execution.busy() ? "요청을 확인하고 다음 단계를 찾고 있어요…" : null;
        if (overlayView != null) overlayView.setLoadingStatus(message);
        if (taskFlowView != null) taskFlowView.setLoadingStatus(message);
    }

    private void updateRequestStatus(boolean active, boolean processing) {
        refreshLoadingStatus();
        requestActive = active;
        if (overlayView != null) overlayView.setRequestStatus(active
                ? processing ? "사용자 요청을 처리하고 있어요" : "요청을 진행하고 있어요" : null, active);
    }

    @Override public void onStopRequested() {
        if (closing) return;
        if (coupangBrowse!=null) {
            clearTaskFlow();hideGuideOverlay();setTurnInFlight(false);finishActiveQueueRequest("USER_ABORTED");return;
        }
        processVipRequest("멈춰");
    }

    private com.click.data.remote.InteractionGateway interactionGateway;
    private com.click.ui.overlay.FeedbackOverlayView feedbackView;
    private WindowManager.LayoutParams feedbackParams;
    private final java.util.ArrayDeque<com.click.data.remote.InteractionGateway.Response> pendingFeedback = new java.util.ArrayDeque<>();

    private void handleRecognizedCommand(String text) {
        String command = text == null ? "" : text.trim();
        if (command.isEmpty()) return;
        if(coupangBrowse!=null) {
            String normalized=GoalCatalogClassifier.normalize(command);
            if(normalized.matches("일시정지|잠깐만|기다려")){sendAgentControl("pause_task");return;}
            if(normalized.matches("계속해|재개|이어서진행")){sendAgentControl("resume_task");return;}
            if(TaskFlowAnswers.stop(command)){onStopRequested();return;}
            if(execution.paused())return;
            if(RepeatRequest.matches(command)){repeatLastGuide();return;}
            if(GoalCatalogClassifier.normalize(command).matches("확인|현재화면확인|다음|다음화면확인"))
                coupangBrowse.observe(ClickAccessibilityService.freshSnapshot());
            else if(gov24Guide&&coupangBrowse.awaitingAnswer())coupangBrowse.answer(ClickAccessibilityService.freshSnapshot(),command,false);
            else enqueueNormalRequest(command);
            return;
        }
        GoalCommandInterpreter.Result control = GoalCommandInterpreter.interpret(command,
                execution.sessionId() != null, execution.paused(), agentConfirmationId != null, null, foregroundPackage());
        boolean contextual = resultPrompt != null && TaskFlowAnswers.result(command) != null
                || followUp != null && TaskFlowAnswers.adjustment(command) != null
                || goalPreparation != null && goalPreparation.needsInput() && goalPreparation.exactSpokenChoice(command) != null
                || GoalCatalogClassifier.normalize(command).matches("네|예|맞아|맞아요|이대로시작|이내용으로시작|시작해|시작해주세요|다음요청시작|다음작업시작");
        boolean directControl = control.kind == GoalCommandInterpreter.Kind.EXIT_APPLICATION
                || control.kind == GoalCommandInterpreter.Kind.PAUSE_TASK || control.kind == GoalCommandInterpreter.Kind.RESUME_TASK
                || control.kind == GoalCommandInterpreter.Kind.CANCEL_TASK || control.kind == GoalCommandInterpreter.Kind.CONFIRM_ACCEPTED
                || control.kind == GoalCommandInterpreter.Kind.CONFIRM_REJECTED;
        if (interactionGateway == null || RepeatRequest.matches(command) || contextual
                || directControl || isInterruptCommand(command)
                || TaskFlowAnswers.stop(command)) {
            handleTaskCommand(command);
            return;
        }
        final int generation = queueGeneration;
        interactionGateway.classify(java.util.UUID.randomUUID().toString(), command, execution.sessionId(),
                new com.click.data.remote.InteractionGateway.Callback() {
            @Override public void onSuccess(com.click.data.remote.InteractionGateway.Response response) {
                if (closing || generation != queueGeneration) return;
                if (response.isFeedback()) showFeedback(response);
                else handleTaskCommand(command);
            }
            @Override public void onFailure(Throwable error) {
                if (closing || generation != queueGeneration) return;
                // Keep the entered text available; do not claim a save or silently
                // turn an unclassified complaint into an executable request.
                if (overlayView != null) {
                    overlayView.openText();
                    overlayView.setDraftText(command);
                    overlayView.showInputFeedback("말씀하신 내용을 확인하지 못했어요. 잠시 후 다시 보내주세요.");
                }
            }
        });
    }

    private void showFeedback(com.click.data.remote.InteractionGateway.Response response) {
        if (closing || windowManager == null) return;
        pendingFeedback.add(response);
        presentNextFeedback();
    }

    private void presentNextFeedback() {
        if (closing || feedbackView != null || pendingFeedback.isEmpty() || windowManager == null) return;
        com.click.data.remote.InteractionGateway.Response response = pendingFeedback.removeFirst();
        final com.click.ui.overlay.FeedbackOverlayView[] owner = {null};
        feedbackView = new com.click.ui.overlay.FeedbackOverlayView(this,
                "positive_feedback".equals(response.intent), response.message, response.detail,
                new com.click.ui.overlay.FeedbackOverlayView.Listener() {
            @Override public void dismiss() { if (feedbackView == owner[0]) dismissFeedback(); }
            @Override public void submit(String detail) {
                if (closing || feedbackView != owner[0] || interactionGateway == null) return;
                owner[0].setSaving(true);
                interactionGateway.submit(response.feedbackId, detail, new com.click.data.remote.InteractionGateway.SaveCallback() {
                    @Override public void onSaved() {
                        if (closing || feedbackView != owner[0]) return;
                        dismissFeedback();
                        Toast.makeText(ClickOverlayService.this, "피드백을 저장했어요. 알려주셔서 고마워요.", Toast.LENGTH_LONG).show();
                    }
                    @Override public void onFailure(Throwable error) {
                        if (!closing && feedbackView == owner[0])
                            owner[0].showError("아직 저장하지 못했어요. 적어주신 내용은 그대로 있으니 다시 보내주세요.");
                    }
                });
            }
        });
        owner[0] = feedbackView;
        feedbackParams = new WindowManager.LayoutParams(Math.min(dp(380), screenWidth() - dp(24)),
                WindowManager.LayoutParams.WRAP_CONTENT, overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        feedbackParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        feedbackParams.y = dp(20);
        feedbackParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        windowManager.addView(feedbackView, feedbackParams);
        speak(feedbackView.guidanceMessage());
    }

    private void dismissFeedback() {
        if (feedbackView != null) {
            android.view.inputmethod.InputMethodManager ime = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (ime != null) ime.hideSoftInputFromWindow(feedbackView.getWindowToken(), 0);
            if (feedbackView.isAttachedToWindow() && windowManager != null) windowManager.removeView(feedbackView);
        }
        feedbackView = null; feedbackParams = null;
        presentNextFeedback();
    }

    private void clearFeedback() { pendingFeedback.clear(); dismissFeedback(); }

    private void handleTaskCommand(String text) {
        String command = text == null ? "" : text.trim();
        if (command.isEmpty()) return;
        // Before contextual answers: "네?" / "응?" must not accept a confirmation or result prompt.
        if (RepeatRequest.matches(command)) { repeatLastGuide(); return; }
        if (nextQueuedRequest != null && GoalCatalogClassifier.normalize(command)
                .matches("다음요청시작|다음요청시작해|다음요청시작해주세요|다음작업시작")) {
            nextQueuedRequest.run(); return;
        }
        boolean explicitRequest = GoalCommandInterpreter.explicitTaskRequest(command, goalClassifier, foregroundPackage());
        boolean contextualAnswer = resultPrompt != null && TaskFlowAnswers.result(command) != null
                || followUp != null && !explicitRequest && TaskFlowAnswers.adjustment(command) != null
                || goalPreparation != null && goalPreparation.needsInput() && goalPreparation.exactSpokenChoice(command) != null;
        if (!contextualAnswer && explicitRequest) {
            enqueueNormalRequest(command);
            return;
        }
        if (handleTaskFlowAnswer(command)) return;
        if (handleExitConfirmation(command)) {
            return;
        }
        GoalCommandInterpreter.Result control = GoalCommandInterpreter.interpret(
                command, execution.sessionId() != null, execution.paused(), agentConfirmationId != null,
                null, foregroundPackage());
        if (control.kind == GoalCommandInterpreter.Kind.EXIT_APPLICATION) {
            shutdownClick();
        } else if (control.kind == GoalCommandInterpreter.Kind.PAUSE_TASK) {
            sendAgentControl("pause_task");
        } else if (control.kind == GoalCommandInterpreter.Kind.RESUME_TASK) {
            sendAgentControl("resume_task");
        } else if (control.kind == GoalCommandInterpreter.Kind.CANCEL_TASK) {
            onStopRequested();
        } else if (control.kind == GoalCommandInterpreter.Kind.CONFIRM_ACCEPTED) {
            sendAgentTurn("accepted", true);
        } else if (control.kind == GoalCommandInterpreter.Kind.CONFIRM_REJECTED) {
            sendAgentTurn("rejected", true);
        } else if (control.kind == GoalCommandInterpreter.Kind.REPLACE_TASK) {
            sendAgentReplacement(command);
        } else if (isInterruptCommand(command)) {
            processVipRequest(command);
        } else {
            enqueueNormalRequest(command);
        }
    }

    private void repeatLastGuide() {
        String repeated = lastSpokenMessage == null
                ? "아직 다시 말씀드릴 안내가 없어요." : lastSpokenMessage;
        if (overlayView != null) overlayView.setRecognizedSpeech(repeated);
        replayGuidanceMessage(repeated);
    }

    private void enqueueNormalRequest(String text) {
        enqueueNormalRequest(text, null);
    }

    private void enqueueNormalRequest(String text, String selectedGoalType) {
        if (closing || text == null || text.trim().isEmpty() || queueGateway == null) return;
        new com.click.data.local.RequestLibraryStore(this).record(text, selectedGoalType);
        if (execution.sessionId() == null && awaitingExitConfirmation) {
            awaitingExitConfirmation = false; execution.resume();
        }
        updateRequestStatus(true, true);
        if (execution.sessionId() == null && nextQueuedRequest==null) {
            ensureTaskFlow();
            taskFlowView.waiting("사용자 요청을 처리하고 있어요");
            placeTaskFlow(null, false);
        }
        final int queuedGeneration = queueGeneration;
        String requestId = java.util.UUID.randomUUID().toString();
        // Record before sending so an immediate claim can never lose the explicit selection.
        if (selectedGoalType != null) quickHelpSelections.save(requestId, selectedGoalType);
        queueGateway.enqueue(requestId, text.trim(), new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (queuedGeneration != queueGeneration) return;
                presentQueueSnapshot(response);
                awaitingReplacement = response.awaitingReplacement;
                if(gov24Guide&&coupangBrowse!=null&&coupangBrowse.terminal()) {
                    completePublicGuideAndContinue(coupangBrowse.status());return;
                }
                if (overlayView != null && execution.sessionId() != null) {
                    overlayView.setRecognizedSpeech("이 작업을 마친 뒤 이어서 도와드릴게요.");
                }
                if (execution.sessionId() == null && nextQueuedRequest==null && !execution.busy()) claimNextQueuedRequest();
            }

            @Override public void onFailure(Throwable error) {
                if (queuedGeneration != queueGeneration) return;
                if (execution.sessionId() == null) updateRequestStatus(false, false);
                showQueueError(error);
            }
        });
    }

    private void completePublicGuideAndContinue() {completePublicGuideAndContinue("COMPLETED");}
    private void completePublicGuideAndContinue(String status) {
        if(endingTask||activeVoiceRequestId==null||queueGateway==null)return;
        endingTask=true;setTurnInFlight(true);
        final int generation=execution.generation();final String finished=activeVoiceRequestId;
        VoiceQueueGateway.Callback completion=new VoiceQueueGateway.Callback(){
            public void onSuccess(VoiceQueueGateway.Response response) {
                if(generation!=execution.generation()||closing)return;
                if(quickHelpSelections!=null)quickHelpSelections.remove(finished);
                activeVoiceRequestId=null;execution.clearSession();clearTaskFlow();
                nextQueuedRequest=null;endingTask=false;setTurnInFlight(false);presentQueueSnapshot(response);
                if(response.queuedCount>0&&!response.awaitingReplacement)claimNextQueuedRequest();
                else shutdownClick();
            }
            public void onFailure(Throwable error) {
                if(generation!=execution.generation()||closing)return;
                endingTask=false;setTurnInFlight(false);showQueueError(error);
            }
        };
        if("COMPLETED".equals(status))queueGateway.complete(finished,completion);
        else if("CANCELLED".equals(status))queueGateway.cancel(finished,"USER_ABORTED",completion);
        else queueGateway.fail(finished,"AGENT_ERROR",completion);
    }

    private void processVipRequest(String command) {
        processVipRequest(command, false);
    }

    private void processVipRequest(String command, boolean openNewRequest) {
        queueGeneration++;
        waitingForCompletedEnd=false;endingTask=false;advanceAfterUserEnd=false;
        abortActiveRequest();
        if (overlayView != null) {
            overlayView.setRequestStatus("요청을 멈췄어요", false);
            overlayView.showInputFeedback("요청을 멈췄어요.");
            if (overlayView.getMode() == ClickOverlayLayout.Mode.COLLAPSED) overlayView.openMenu();
        }
        if (queueGateway == null) {
            activeVoiceRequestId = null;
            awaitingReplacement = false;
            return;
        }
        awaitingReplacement = true;
        final long generation = execution.generation();
        queueGateway.interrupt(command, new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (generation != execution.generation()) return;
                if (activeVoiceRequestId != null && quickHelpSelections != null) quickHelpSelections.remove(activeVoiceRequestId);
                awaitingReplacement = response.awaitingReplacement;
                activeVoiceRequestId = null;
                String message = openNewRequest ? "어떤 도움이 필요하세요? 새 요청을 입력해 주세요."
                        : "요청을 멈췄어요. 마이크를 꾹 눌러 새로 말해 주세요.";
                if (!requestActive && overlayView != null) {
                    overlayView.setRequestStatus("요청을 멈췄어요", false);
                    overlayView.setRecognizedSpeech(message);
                    if (openNewRequest) {
                        overlayView.setDraftText("");
                        overlayView.openMenu();
                    }
                    speak(message);
                }
            }

            @Override public void onFailure(Throwable error) {
                if (generation != execution.generation()) return;
                requestActive = true;
                if (overlayView != null) overlayView.setRequestStatus("기기에서 멈췄어요. 서버 중단을 다시 시도해 주세요.", true);
            }
        });
    }

    private void abortActiveRequest() {
        queueClaimToken++; queueClaimInFlight = false;
        clearGuideResyncRetry();
        nextQueuedRequest = null;
        silenceVoiceReply();
        execution.invalidate();
        haltLocalExecution();
        updateRequestStatus(false, false);
        clearTaskFlow();
        clearVisionState();
        execution.newTask();
        execution.clearSession();
        execution.resume();
        agentConfirmationId = null;
        automaticAdjustmentApprovalPending = false;
        initialGoal = null;
        activeGoalMatch = null;
        execution.setStarted(false);
        execution.resume();
        execution.clearQueuedResume();
        setTurnInFlight(false);
        pendingActionType = null;
        speechHandler.removeCallbacksAndMessages(null);
        stopNarration();
        hideGuideOverlay();
        hideConfirmation();
    }

    private boolean queueClaimInFlight;
    private long queueClaimToken;
    private long queueSnapshotVersion = -1;
    private java.util.List<String> queuedRequestTexts = java.util.Collections.emptyList();

    private void presentQueueSnapshot(VoiceQueueGateway.Response response) {
        if (response.queueVersion < queueSnapshotVersion) return;
        queueSnapshotVersion = response.queueVersion;
        java.util.List<String> text = new java.util.ArrayList<>();
        for (VoiceQueueGateway.Request request : response.queued)
            if (request.rawGoal != null) text.add(request.rawGoal);
        queuedRequestTexts = java.util.Collections.unmodifiableList(text);
    }

    private void claimNextQueuedRequest() { claimNextQueuedRequest(false); }

    private void claimNextQueuedRequest(boolean afterCompletion) {
        if (closing || queueGateway == null || execution.sessionId() != null || execution.busy() || awaitingReplacement
                || queueClaimInFlight || nextQueuedRequest!=null || execution.paused() && !afterCompletion) return;
        queueClaimInFlight = true;
        final long claimToken = ++queueClaimToken;
        final long generation = execution.generation();
        queueGateway.claim(new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (claimToken != queueClaimToken) return;
                queueClaimInFlight = false;
                if (generation != execution.generation() || execution.paused() && !afterCompletion) return;
                presentQueueSnapshot(response);
                awaitingReplacement = response.awaitingReplacement;
                if (response.running != null && response.running.agentSessionId != null
                        && response.running.rawGoal != null) {
                    if (isCompletedQueueRequest(response.running)) { askToExitAfterQueueCompleted(); return; }
                    if (afterCompletion) startNextQueuedRequest(response.running, response.claimed);
                    else {
                        activeVoiceRequestId = response.running.id;
                        beginAgentSession(response.running.rawGoal, response.running.agentSessionId, response.claimed);
                    }
                } else if (afterCompletion) askToExitAfterQueueCompleted();
            }

            @Override public void onFailure(Throwable error) { if (claimToken != queueClaimToken) return; queueClaimInFlight = false; if (generation == execution.generation()) showQueueError(error); }
        });
    }

    private boolean isCompletedQueueRequest(VoiceQueueGateway.Request request) {
        return request != null && (lastCompletedVoiceRequestId != null && lastCompletedVoiceRequestId.equals(request.id)
                || lastCompletedAgentSessionId != null && lastCompletedAgentSessionId.equals(request.agentSessionId));
    }

    /** Prepare the next task only after the previous task's End button; never approve it. */
    private void startNextQueuedRequest(VoiceQueueGateway.Request request, boolean newlyClaimed) {
        if (isCompletedQueueRequest(request)) { askToExitAfterQueueCompleted(); return; }
        nextQueuedRequest = null;
        activeVoiceRequestId = request.id;
        beginAgentSession(request.rawGoal, request.agentSessionId, newlyClaimed, true);
    }

    private void finishActiveQueueRequest(String failureCode) {
        updateRequestStatus(false, false);
        final boolean completedSuccessfully = failureCode == null;
        final boolean advanceOnEnd=completedSuccessfully || advanceAfterUserEnd;
        advanceAfterUserEnd=false;
        if (completedSuccessfully) {
            lastCompletedVoiceRequestId = activeVoiceRequestId;
            lastCompletedAgentSessionId = execution.sessionId();
            resultPrompt = followUp = null;
        }
        if (activeVoiceRequestId == null || queueGateway == null) {
            execution.clearSession();
            if (advanceOnEnd) shutdownClick();
            else claimNextQueuedRequest();
            return;
        }
        final long generation = execution.generation();
        String finishedId = activeVoiceRequestId;
        VoiceQueueGateway.Callback callback = new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (generation != execution.generation()) return;
                presentQueueSnapshot(response);
                quickHelpSelections.remove(finishedId);
                activeVoiceRequestId = null;
                execution.clearSession();
                setTurnInFlight(false);
                awaitingReplacement = response.awaitingReplacement;
                if (response.running != null && response.running.agentSessionId != null
                        && response.running.rawGoal != null) {
                    if (isCompletedQueueRequest(response.running)) { shutdownClick(); return; }
                    if (advanceOnEnd) startNextQueuedRequest(response.running, response.claimed);
                    else nextQueuedRequest=()->startNextQueuedRequest(response.running,response.claimed);
                } else if (advanceOnEnd && response.queuedCount == 0
                        && !response.awaitingReplacement) {
                    shutdownClick();
                } else if(advanceOnEnd) {
                    claimNextQueuedRequest(true);
                } else {
                    nextQueuedRequest=response.queuedCount>0 && !response.awaitingReplacement
                        ? ()->claimNextQueuedRequest(true):ClickOverlayService.this::shutdownClick;
                }
            }

            @Override public void onFailure(Throwable error) {
                if(generation!=execution.generation())return;
                endingTask=false;
                if(completedSuccessfully){waitingForCompletedEnd=true;taskFlowView.completed("다음 작업을 확인하지 못했어요. 끝내기를 눌러 다시 확인해 주세요.",false,ClickOverlayService.this::finishCompletedTask);}
                else showQueueError(error);
            }
        };
        if (failureCode == null) queueGateway.complete(finishedId, callback);
        else if("USER_DECLINED".equals(failureCode) || "USER_ABORTED".equals(failureCode))
            queueGateway.cancel(finishedId,failureCode,callback);
        else queueGateway.fail(finishedId, failureCode, callback);
    }

    private void showCompletedTask(String message,boolean verified){
        waitingForCompletedEnd=true;endingTask=false;
        execution.pause();actionTracker.clear();pendingVerifiedAction=null;
        taskFlowView.completed(message,verified,this::finishCompletedTask);
        taskFlowCollapsed=false;
        if(overlayView!=null)overlayView.collapse();
        placeTaskFlow(null,false);updateFloatingVisibility();
    }

    private void finishCompletedTask(){
        if(closing || !waitingForCompletedEnd || endingTask)return;
        waitingForCompletedEnd=false;endingTask=true;
        finishActiveQueueRequest(null);
    }

    private void endCurrentTask(){
        if(coupangBrowse!=null){onStopRequested();return;}
        if(nextQueuedRequest!=null){Runnable next=nextQueuedRequest;nextQueuedRequest=null;next.run();return;}
        if(waitingForCompletedEnd){finishCompletedTask();return;}
        if(waitingForDeclinedEnd){finishDeclinedTask();return;}
        if(closing || endingTask)return;
        endingTask=true;advanceAfterUserEnd=true;
        sendAgentControl("cancel_task");
    }

    private void failActiveQueueRequest(String failureCode) {
        if (activeVoiceRequestId != null) finishActiveQueueRequest(failureCode);
    }

    private void showQueueError(Throwable error) {
        android.util.Log.e("ClickQueue", ErrorDetails.describe("요청 처리", error), error);
        String message = ErrorDetails.requestMessage(error);
        if (overlayView != null) {
            overlayView.setRecognizedSpeech(message);
            overlayView.showInputFeedback(message);
        }
        if (taskFlowView != null) taskFlowView.failed(message);
        speak(message);
    }

    private boolean isInterruptCommand(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[\\s,.!?]", "");
        return normalized.contains("잠깐기다려")
                || normalized.contains("기다려봐")
                || normalized.contains("멈춰봐")
                || normalized.contains("멈춰")
                || normalized.contains("중단해")
                || normalized.equals("스톱")
                || normalized.equals("stop");
    }

    private void beginAgentSession(String goal, String sessionId, boolean newlyClaimed) {
        beginAgentSession(goal,sessionId,newlyClaimed,false);
    }

    private void beginAgentSession(String goal,String sessionId,boolean newlyClaimed,boolean followingTask) {
        nextTaskReview=followingTask;waitingForCompletedEnd=false;waitingForDeclinedEnd=false;endingTask=false;advanceAfterUserEnd=false;
        sequenceOverview=followingTask?null:TaskSequenceText.introduction(goal,queuedRequestTexts,goalClassifier);
        environmentWaitKey=null;environmentWait=null;lastNavigableEnvironment=null;
        observedPageScreen=pendingPageScreen=null;
        if (closing || goal == null || goal.trim().isEmpty()) return;
        ensureTaskFlow(); taskFlowView.setPreparationMessage(null);
        nextQueuedRequest = null;
        clearMissingScreenWait();
        if (narrator != null) narrator.reset();
        lastSpokenMessage = null;
        lastNarratedGuideKey = null;
        updateRequestStatus(true, true);
        execution.invalidate();
        automationGuard.interrupt();
        navigationRecovery.clear();
        homeSearchRestart = false;
        guideStepTimer.clear();
        execution.newTask();adjustmentDelegation.clear();pendingApprovalProvenance=null;
        execution.bindSession(sessionId);
        restoredStartedSession = false;
        agentConfirmationId = null;
        automaticAdjustmentApprovalPending = false;
        initialGoal = goal.trim();
        goalPreparation = null;
        awaitingExitConfirmation = false;
        activeGoalMatch = null;
        execution.setAcceptedGoal(null);
        execution.exitManual();
        goalSelection = resultPrompt = followUp = null;
        requirementReview = null;
        goalResolutionId = null;
        execution.resume();
        execution.clearControl();
        execution.clearQueuedResume();
        pendingVerifiedAction = null;
        actionTracker.clear();
        adjustmentResultCheck.clear(); clearActionEvidenceWait();
        execution.setStarted(false);
        lastSnapshotFingerprint = null;
        homeScrollSearch.clear();
        homeSearchToken++;
        adjustmentSettlingUntil = 0;
        pendingActionType = null;
        execution.clearObservation();
        lastGuideCommandId = null;
        lastTargetNodeId = null;
        lastTargetBounds = null;
        clearVisionState();
        freshObservationRetries = 0;
        staleGuideDrops = 0;
        scrollSearchGeneration = -1;
        automaticScrollSearch.clear();
        lastBackRecoveryScreen = null; settingsBackRecoveries = 0;
        if (overlayView != null) overlayView.setRecognizedSpeech("화면을 확인하고 있어요…");
        // A queue claim can return an existing episode after the service restarts.
        if (newlyClaimed) resolveGoal(initialGoal, null);
        else restoreQueuedSession(true);
    }

    /** Restore server state before deciding whether a queued request still needs preparation. */
    private void restoreQueuedSession(boolean allowPreparation) {
        if(deviceJournal!=null && deviceJournal.pending(execution.sessionId())!=null && !execution.paused()) {showOfflineReceipt();return;}
        final int generation = execution.generation();
        final String sessionId = execution.sessionId();
        setTurnInFlight(true);
        ensureTaskFlow();
        taskFlowView.waiting("진행하던 요청을 확인하고 있어요…");
        synchronizeTracked(sessionId, new AgentTurnGateway.Callback() {
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if (generation != execution.generation() || closing) return;
                if (!sessionId.equals(response.sessionId)) {
                    onFailure(new java.io.IOException("INVALID_SESSION_STATE")); return;
                }
                setTurnInFlight(false);
                if(response.metadata!=null&&response.metadata.optBoolean("public_guide")
                        &&java.util.Set.of("COMPLETED","CANCELLED","EXPIRED","BLOCKED").contains(response.taskStatus)) {
                    completePublicGuideAndContinue(response.taskStatus);return;
                }
                if(response.metadata!=null && (response.metadata.optBoolean("browsing")||response.metadata.optBoolean("public_guide"))) {
                    gov24Guide=response.metadata.optBoolean("public_guide");
                    gov24Civil="gov24.resident_copy.open".equals(response.metadata.optString("public_guide_goal_type"));
                    startCoupangBrowse(null,true,response.sessionVersion);return;
                }
                if ("COMPLETED".equals(response.taskStatus) || "CANCELLED".equals(response.taskStatus)
                        || "EXPIRED".equals(response.taskStatus)) {
                    applyTaskFlowResponse(response); return;
                }
                if (response.sessionVersion == 0 && response.episodeId == null && allowPreparation) {
                    if (execution.pendingControl() != null) {
                        String control = execution.pendingControl(); execution.clearControl();
                        sendAgentControl(control); return;
                    }
                    if (execution.paused()) { taskFlowView.paused(); return; }
                    resolveGoal(initialGoal, null); return;
                }
                // Use the server's exact graph ID, never reclassify the user's ambiguous wording.
                GoalCatalogClassifier.Match match = goalClassifier == null || response.metadata == null ? null
                        : goalClassifier.executableGraph(response.metadata.optString("task_id"));
                if (response.episodeId == null || match == null
                        || response.metadata.optBoolean("requires_goal", false)) {
                    onFailure(new java.io.IOException("SESSION_CANNOT_RESUME")); return;
                }
                try { execution.setAcceptedGoal(match.toTypedGoalJson()); }
                catch (org.json.JSONException error) { onFailure(error); return; }
                activeGoalMatch = match;
                goalPreparation = null; goalResolutionId = null; requirementReview = null;
                restoredStartedSession = true;
                execution.setStarted(true);
                if (execution.pendingControl() != null) {
                    String control = execution.pendingControl(); execution.clearControl();
                    sendAgentControl(control); return;
                }
                if (execution.paused()) { taskFlowView.paused(); return; }
                applyTaskFlowResponse(response);
            }

            @Override public void onFailure(Throwable error) {
                if (generation != execution.generation() || closing) return;
                setTurnInFlight(false);
                if (execution.pendingControl() != null) {
                    String control = execution.pendingControl(); execution.clearControl();
                    sendAgentControl(control); return;
                }
                if (execution.paused()) { taskFlowView.paused(); return; }
                if(deviceJournal!=null && deviceJournal.plan(sessionId)!=null){showOfflineReceipt();return;}
                taskFlowView.resolutionUnavailable(initialGoal, "진행하던 요청을 불러오지 못했어요. 다시 시도해 주세요.", () -> {
                    if (generation == execution.generation() && !closing && !execution.paused() && !execution.busy())
                        restoreQueuedSession(allowPreparation);
                });
                updateRequestStatus(true, false);
                placeTaskFlow(null, true);
            }
        });
    }

    private void sendAgentTurn(String confirmation, boolean force) {
        sendAgentTurn(confirmation, force, null);
    }

    private void sendAgentTurn(String confirmation, boolean force, ScreenSnapshot nativeConsentScreen) {
        if("rejected".equals(confirmation) && pendingVisionFailure==null) {
            if(closing || execution.busy() || execution.sessionId()==null || agentGateway==null || agentConfirmationId==null)return;
            AgentTurnContract.Request decline=new AgentTurnContract.Request(execution.sessionId(),null,null,"rejected")
                .withSessionState(execution.sessionVersion(),execution.episodeId(),agentConfirmationId);
            // Refusing an operation needs no screen, target, model, or surviving native action.
            haltLocalExecution(); execution.stop(); agentConfirmationId=null; confirmationExpiresAt=0;
            ensureTaskFlow();taskFlowView.waiting("선택을 반영하고 있어요.");
            sendFlowRequest(decline);return;
        }
        if(handleEnvironment(ClickAccessibilityService.freshSnapshot()))return;
        if(confirmation==null && handleSettingsPageChange(ClickAccessibilityService.freshSnapshot()))return;
        if (closing || !execution.canObserve() || execution.sessionId() == null || agentGateway == null
                || scrollSearchGeneration == execution.generation()
                || SystemClock.uptimeMillis() < adjustmentSettlingUntil
                || !hasConfirmedGoal() || resultPrompt != null || followUp != null || execution.pendingControl() != null) return;
        if (automaticAdjustmentApprovalPending && confirmation == null) {
            // Keep refresh events from consuming a one-use server approval challenge
            // while the matching automatic slider approval is queued.
            execution.requestObservation();
            return;
        }
        if (confirmation == null && (visionConsentRequested || visionCaptureInFlight)) return;
        // A launcher page can change before the debounced snapshot broadcast arrives.
        // Re-read native content before deduplication; force controls sending, not freshness.
        // Vision consent keeps the observation to which the permission was bound.
        ScreenSnapshot snapshot = pendingVisionScreen != null ? pendingVisionScreen
                : ClickAccessibilityService.freshSnapshot();
        if (showCoupangNativeGuide(snapshot)) return;
        if (execution.acceptedGoal() != null && "coupang".equals(execution.acceptedGoal().optString("target_app"))
                && snapshot != null && !"com.coupang.mobile".equals(snapshot.packageName)) {
            showCoupangEntryRecovery(); return;
        }
        if (preserveTargetHelp(snapshot)) return;
        force = force || execution.observationRequested();
        if (snapshot == null || (pendingVisionSnapshot == null && agentGateway.needsFreshObservation(snapshot))) {
            waitForMissingScreen(); return;
        }
        // A fresh read is not yet an accepted observation. Preserve the retry
        // budget until the boundary/server accepts it, so repeated rejections
        // cannot reset the three-attempt budget and poll forever.
        cancelMissingScreenRetry();
        if (staleGuideDrops >= 4) {
            // FONT_SCALE may commit after Settings has exhausted redraw retries. A real
            // change is new evidence, so send it once and let the normal result flow decide.
            // Unchanged/wrong-direction screens still require the existing manual retry.
            if (confirmation != null || execution.acceptedGoal() == null
                    || !adjustmentResultCheck.changed(execution.episodeId(),
                            execution.acceptedGoal().optString("goal_type"), snapshot)) return;
            staleGuideDrops = freshObservationRetries = 0;
            force = true;
            android.util.Log.i("ClickSlider", "resuming_result_check_after_persisted_change");
        }
        if (confirmation == null && pendingVerifiedAction != null
                && AgentActionEvents.DRAG_COMPLETED.equals(pendingVerifiedAction.optString("action_type"))
                && snapshot.nodes.isEmpty() && lastSubmittedScreen != null
                && java.util.Objects.equals(lastSubmittedScreen.packageName, snapshot.packageName)) {
            long age = System.currentTimeMillis() - pendingVerifiedAction.optLong("occurred_at_ms");
            if (age >= 0 && age < 6000L) {
                // Font-scale changes briefly remove the native root while Settings redraws.
                // Sending the action with that empty tree discards the server's pending attempt.
                // Keep its original timestamp and guide until a fresh native tree is available.
                execution.requestObservation();
                return;
            }
        }
        if (nativeConsentScreen != null) {
            // The server challenge binds the native screen revision, while the runtime
            // requires a newly captured observation. Retain the challenge revision only
            // after checking every native field, keeping the fresh timestamp and new ID.
            // Vision consent continues to use its separately captured, revision-bound payload.
            if (System.currentTimeMillis() >= confirmationExpiresAt * 1000L
                    || !nativeConsentScreen.sameNativeContent(snapshot)) {
                waitForFreshGuide();
                return;
            }
            snapshot = snapshot.withScreenRevision(nativeConsentScreen.screenRevision);
        }
        // A changed value can beat the accessibility action event during redraw.
        // Retain the original guide briefly instead of sending that value first.
        if (confirmation == null && awaitNativeActionEvidence(snapshot)) return;
        if (shouldScanHomeForSettings(snapshot)
                && !AgentActionEvents.HOME_NAVIGATED.equals(pendingActionType)) {
            if (startAutomaticHomeSearch(snapshot)) return;
            homeSearchRestart = false;
        } else if (hasSettingsTarget(snapshot) || !NavigationRecovery.isHomeScreen(snapshot)) {
            homeSearchRestart = false;
        }
        String fingerprint = snapshot.observationFingerprint();
        if (!force && pendingVerifiedAction == null && execution.started() && confirmation == null && fingerprint.equals(lastSnapshotFingerprint)) return;
        AgentTurnContract.Request request = new AgentTurnContract.Request(
                execution.sessionId(), execution.started() ? null : initialGoal, snapshot, confirmation,
                execution.started() ? null : activeGoalMatch)
                .withSessionState(execution.sessionVersion(), execution.episodeId(),
                        confirmation == null ? null : agentConfirmationId);
        SettingsAdjustment scope=execution.acceptedGoal()==null?null:SettingsAdjustment.forGoal(execution.acceptedGoal().optString("goal_type"));
        JSONObject goalSlots=execution.acceptedGoal()==null?null:execution.acceptedGoal().optJSONObject("slots");
        request.observationSetting=scope==null?(goalSlots==null?null:goalSlots.optString("setting",null)):scope.setting;
        if (execution.acceptedGoal() != null && "coupang".equals(execution.acceptedGoal().optString("target_app")))
            request.observationSetting = "commerce";
        if (!execution.started()) {
            request.acceptedGoal = execution.acceptedGoal();
            request.goalResolutionId = goalResolutionId;
            if (goalPreparation != null) request.preparationVersion = "goal-inputs.v1";
        }
        if (pendingVerifiedAction != null) request.withActionEvent(pendingVerifiedAction);
        else if (pendingActionType != null) {
            request.withActionEvent(AgentActionEvents.of(
                    pendingActionType, lastGuideCommandId, lastTargetNodeId, lastTargetBounds, pendingActionOccurredAtMs));
        }
        if (pendingVisionSnapshot != null && "accepted".equals(confirmation)) {
            request.withVisionSnapshot(pendingVisionSnapshot);
        }
        if ("accepted".equals(confirmation) && agentGateway.currentContract()!=null && agentGateway.currentContract().approvalProvenance) {
            try {request.approvalProvenance=pendingApprovalProvenance!=null?pendingApprovalProvenance:new JSONObject().put("source","user");}
            catch(org.json.JSONException invalid){showQueueError(invalid);return;}
        }
        if ("rejected".equals(confirmation)) request.visionFailureCode = pendingVisionFailure;
        pendingApprovalProvenance=null;
        setTurnInFlight(true);
        updateRequestStatus(true, true);
        lastSnapshotFingerprint = fingerprint;
        if(com.click.data.snapshot.ScreenIdentity.pageTitle(snapshot)!=null)observedPageScreen=snapshot;
        // Consume the fresh-read request only when an observation is actually sent.
        execution.clearObservation();
        pendingActionType = null;
        pendingVerifiedAction = null;
        pendingVisionSnapshot = null;
        pendingVisionFailure = null;
        pendingVisionScreen = null;
        sendTracked(request, new AgentTurnGateway.Callback() {
            private final int requestGeneration = execution.generation();
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if (requestGeneration != execution.generation()) return;
                setTurnInFlight(false);
                clearMissingScreenWait();
                execution.setStarted(true);


                JSONObject confirmationState = response.metadata == null
                        ? null : response.metadata.optJSONObject("confirmation");
                agentConfirmationId = confirmationState == null
                        ? null : confirmationState.optString("id", null);
                confirmationExpiresAt = confirmationState == null ? 0 : confirmationState.optLong("expires_at", 0);
                lastSubmittedScreen = request.accessibilityTree;
                if(!recordDeviceVerification(request,response))return;
                if (com.click.BuildConfig.DEBUG && execution.acceptedGoal() != null
                        && execution.acceptedGoal().optString("goal_type").startsWith("settings.font_size.")) {
                    JSONObject outcome = response.metadata == null ? null : response.metadata.optJSONObject("task_outcome");
                    android.util.Log.i("ClickSlider", "response status=" + response.taskStatus
                            + " font_scale=" + request.accessibilityTree.appliedFontScale
                            + " window=" + request.accessibilityTree.activeWindowId
                            + " state=" + response.stateId
                            + " action=" + (request.userActionEvent == null ? "none" : request.userActionEvent.optString("action_type"))
                            + " technical=" + (outcome == null ? null : outcome.optJSONObject("technical"))
                            + " verification=" + (response.metadata == null ? null : response.metadata.optJSONObject("verification")));
                }
                if (execution.pendingControl() != null) {
                    String control = execution.pendingControl(); execution.clearControl();
                    sendAgentControl(control); return;
                }
                if(environmentWait!=null && !java.util.Set.of("COMPLETED","WAITING_RESULT","CANCELLED","EXPIRED","BLOCKED").contains(response.taskStatus)){pendingPageScreen=null;return;}
                if (continueAfterPageChange(response)) return;
                applyTaskFlowResponse(response);
                if (homeSearchRestart) {
                    speechHandler.postDelayed(() -> {
                        if (requestGeneration != execution.generation() || execution.paused() || execution.busy()) return;
                        execution.requestObservation();
                        ClickAccessibilityService.requestFreshSnapshot();
                    }, 300L);
                }
                ScreenSnapshot latest = LatestScreenSnapshot.get();
                if (latest != null && (execution.observationRequested() || pendingVerifiedAction != null || !latest.observationFingerprint().equals(lastSnapshotFingerprint))) {
                    sendAgentTurn(null, execution.observationRequested());
                }
            }

            @Override public void onFailure(Throwable error) {
                if (requestGeneration != execution.generation()) return;
                setTurnInFlight(false);
                if(pendingPageScreen!=null) {
                    pendingPageScreen=null;
                    if(execution.paused() || execution.pendingControl()!=null)return;
                    // The old POST may have committed even if its reply was lost. Recover the
                    // version without replacing the local Back prompt or replaying its guide.
                    pendingPageScreen=observedPageScreen;
                    synchronizePageNavigation();return;
                }
                if (pendingVerifiedAction == null && request.userActionEvent != null)
                    pendingVerifiedAction = request.userActionEvent;
                if (execution.pendingControl() != null) {
                    String control = execution.pendingControl(); execution.clearControl();
                    recoverControl(control); return;
                }
                if (error instanceof com.click.data.snapshot.SettingsScreenPrivacy.Blocked) {
                    com.click.data.snapshot.SettingsScreenPrivacy.Blocked blocked =
                            (com.click.data.snapshot.SettingsScreenPrivacy.Blocked) error;
                    if (blocked.reason == com.click.data.snapshot.SettingsScreenPrivacy.Blocked.Reason.UNSUPPORTED_APP) {
                        waitForMissingScreen();
                    } else {
                    boolean sensitive = blocked.reason == com.click.data.snapshot.SettingsScreenPrivacy.Blocked.Reason.SENSITIVE_INPUT
                            || blocked.reason == com.click.data.snapshot.SettingsScreenPrivacy.Blocked.Reason.SENSITIVE_SCREEN;
                    showStoppedTask(goalAwarePrivacyMessage(ErrorDetails.requestMessage(error), sensitive),
                            sensitive && hasAssistedSettingsGoal());
                    }
                    return;
                }
                if(error instanceof com.click.data.remote.ApiException && "CONFIRMATION_INVALID".equals(((com.click.data.remote.ApiException)error).conflictCode)) {
                    adjustmentDelegation.clear();pendingApprovalProvenance=null;agentConfirmationId=null;
                    execution.recovery();hideGuideOverlay();hideConfirmation();
                    showObservationRecovery("확인 권한이 만료되었거나 화면이 바뀌었어요. 현재 내용을 다시 확인한 뒤 직접 승인해 주세요.",()->{
                        if(requestGeneration!=execution.generation() || execution.paused() || execution.busy())return;
                        execution.retry();restoreQueuedSession(true);
                    });
                    placeTaskFlow(null,true);return;
                }
                boolean recoverable = error instanceof com.click.data.remote.ApiException
                        ? ((com.click.data.remote.ApiException) error).status == 401
                            || ((com.click.data.remote.ApiException) error).status == 408
                            || ((com.click.data.remote.ApiException) error).status == 409
                            || ((com.click.data.remote.ApiException) error).status == 429
                            || ((com.click.data.remote.ApiException) error).status >= 500
                        : error instanceof java.io.IOException;
                if (recoverable) {
                    if(deviceJournal!=null && deviceJournal.plan(execution.sessionId())!=null) {showOfflineReceipt();return;}
                    automationGuard.interrupt();
                    execution.recovery(TaskExecutionController.RecoveryCause.NETWORK);
                    execution.clearObservation();
                    hideGuideOverlay(); hideConfirmation(); stopNarration();
                    String message = ErrorDetails.requestMessage(error)
                            + " 진행 상태를 다시 확인한 뒤 이어갈게요.";
                    final String session = execution.sessionId();
                    showTypedRecovery("NETWORK_UNAVAILABLE", () -> {
                        if (requestGeneration != execution.generation() || closing || execution.paused() || execution.busy()
                                || execution.pendingControl() != null || !java.util.Objects.equals(session, execution.sessionId())) return;
                        execution.retry();
                        restoreQueuedSession(true);
                    });
                    taskFlowCollapsed = false; updateFloatingVisibility(); placeTaskFlow(null, true);
                    speak(message);
                    return;
                }
                if (waitingForTargetHelp()) return;
                failActiveQueueRequest("AGENT_ERROR");
                android.util.Log.e("ClickAgent", "Agent turn failed", error);
                String userMessage = ErrorDetails.requestMessage(error);
                if (taskFlowView != null) taskFlowView.failed(userMessage);
                if (overlayView != null) overlayView.setRecognizedSpeech(userMessage);
                speak(userMessage);
                if (overlayView != null && overlayView.getMode() == ClickOverlayLayout.Mode.TEXT) {
                    Toast.makeText(ClickOverlayService.this,
                            userMessage,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private Intent accessibilityGuideIntent() {
        return new Intent(this, com.click.designpreview.PermissionGuideActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(com.click.designpreview.PermissionGuideActivity.EXTRA_RECONNECT,
                        PermissionStatus.isAccessibilityEnabled(this));
    }

    private void openAccessibilityGuide() {
        stopNarration();
        hideGuideOverlay();
        hideConfirmation();
        taskFlowCollapsed = true;
        if (overlayView != null) overlayView.collapse();
        updateFloatingVisibility();
        startActivity(accessibilityGuideIntent());
    }

    private void showObservationRecovery(String message, Runnable retry) {
        if (ClickAccessibilityService.accessibilityWindowManager() == null)
            showObservationRecovery(initialGoal, message, retry);
        else if (assistedRecoveryGeneration == execution.generation()) showRecoveryApology(assistedRetry(retry));
        else taskFlowView.observationRecovery(message, assistedRetry(retry));
    }

    private void showObservationRecovery(String goal, String message, Runnable retry) {
        if (ClickAccessibilityService.accessibilityWindowManager() == null) {
            taskFlowView.accessibilityRecovery(
                    "화면 읽기 연결이 끊겼어요. 접근성 안내를 따라 연결을 다시 설정해 주세요.",
                    this::openAccessibilityGuide, retry);
        } else if (assistedRecoveryGeneration == execution.generation()) showRecoveryApology(assistedRetry(retry));
        else taskFlowView.observationRecovery(goal, message, assistedRetry(retry));
    }

    private Runnable assistedRetry(Runnable retry) {
        final int generation = execution.generation();
        final String session = execution.sessionId();
        return () -> {
            if (generation != execution.generation() || closing || execution.paused() || execution.busy()
                    || execution.pendingControl() != null || !java.util.Objects.equals(session, execution.sessionId())) return;
            assistedRecoveryGeneration = generation;
            clearTargetHelpWait();
            retry.run();
        };
    }

    private void showRecoveryApology(Runnable retry) {
        clearMissingScreenWait();
        stopNarration();
        hideGuideOverlay(); hideConfirmation();
        guideStepTimer.clear(); actionTracker.clear();
        execution.clearObservation();
        targetHelpGeneration = recoveryApologyGeneration = execution.generation();
        targetHelpScreen = ClickAccessibilityService.freshSnapshot();
        taskFlowView.recoveryApology(retry);
        taskFlowCollapsed = false;
        updateFloatingVisibility();
    }

    private void cancelMissingScreenRetry() {
        if (missingScreenRetry != null && speechHandler != null) speechHandler.removeCallbacks(missingScreenRetry);
        missingScreenRetry = null;
    }

    private void clearMissingScreenWait() {
        cancelMissingScreenRetry();
        missingScreenAttempts = 0;
        missingScreenRecovery = false;
    }

    /** A missing native screen is not a server request: retry briefly, then give the user a way out. */
    private void waitForMissingScreen() {
        if (missingScreenRecovery || missingScreenRetry != null) return;
        final int generation = execution.generation();
        final String session = execution.sessionId();
        ensureTaskFlow();
        if (missingScreenAttempts < 3) {
            String message = "안내를 시작할 화면을 확인하고 있어요…";
            taskFlowView.waiting(message);
            if (overlayView != null) overlayView.setRecognizedSpeech(message);
            updateRequestStatus(true, false);
            missingScreenAttempts++;
            missingScreenRetry = new Runnable() {
                @Override public void run() {
                    if (missingScreenRetry != this) return;
                    missingScreenRetry = null;
                    if (generation != execution.generation() || !java.util.Objects.equals(session, execution.sessionId())
                            || closing || execution.paused() || execution.pendingControl() != null) return;
                    // Read again even if Android emits no new accessibility event.
                    // Never replay an earlier confirmation against a later screen.
                    sendAgentTurn(null, true);
                }
            };
            speechHandler.postDelayed(missingScreenRetry, 750L);
            return;
        }
        missingScreenRecovery = true;
        android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
        String message = ObservationRecovery.missingScreenMessage(
                ClickAccessibilityService.accessibilityWindowManager() != null,
                keyguard != null && keyguard.isDeviceLocked());
        hideGuideOverlay();
        showObservationRecovery(initialGoal, message, () -> {
            if (generation != execution.generation() || !java.util.Objects.equals(session, execution.sessionId())
                    || closing || execution.paused() || execution.busy() || execution.pendingControl() != null) return;
            clearMissingScreenWait();
            sendAgentTurn(null, true);
        });
        taskFlowCollapsed = false;
        updateRequestStatus(true, false);
        updateFloatingVisibility();
        placeTaskFlow(null, true);
        speak(taskFlowView.guidanceMessage());
    }

    private boolean hasConfirmedGoal() {
        if (execution.acceptedGoal() == null) return false;
        if (restoredStartedSession && execution.started() && execution.episodeId() != null) return true;
        // Server preparation replaces the local review, but still needs explicit user acceptance.
        if (goalPreparation != null) {
            return goalPreparation.ready() && !goalPreparation.id().isEmpty()
                    && goalPreparation.id().equals(goalResolutionId);
        }
        return requirementReview != null && requirementReview.confirmed();
    }

    private boolean shouldScanHomeForSettings(ScreenSnapshot snapshot) {
        if (initialGoal == null || !isSettingsGoal(initialGoal) || !NavigationRecovery.isHomeScreen(snapshot)) {
            return false;
        }
        return !hasSettingsTarget(snapshot);
    }

    private void shutdownClick() {
        clearFeedback();
        if (closing) return;
        closing = true;
        queueGeneration++;
        awaitingExitConfirmation = false;
        abortActiveRequest();
        acceptingVoiceCommand = false;
        stopListening();
        hideTrashTarget();
        if (snapAnimator != null) snapAnimator.cancel();
        if (overlayView != null) {
            overlayView.removeCallbacks(dragFrame);
            setOverlayVisibility(overlayView, android.view.View.GONE);
        }
        awaitingReplacement = true;
        final long generation = execution.generation();
        // Hiding is local and immediate, even when queue cancellation is offline or slow.
        speechHandler.postDelayed(this::stopSelf, 5000L);
        if (queueGateway == null) {
            abortActiveRequest();
            stopSelf();
            return;
        }
        queueGateway.interrupt("클릭 종료", true, new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (generation != execution.generation()) return;
                quickHelpSelections.clear();
                activeVoiceRequestId = null;
                stopSelf();
            }
            @Override public void onFailure(Throwable error) {
                if (generation == execution.generation()) stopSelf();
            }
        });
    }

    private boolean handleExitConfirmation(String text) {
        if (!awaitingExitConfirmation) return false;
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s,.!?]", "");
        if (normalized.equals("응") || normalized.equals("네") || normalized.equals("예")
                || normalized.equals("그래") || normalized.equals("종료")) {
            shutdownClick();
            return true;
        }
        if (normalized.equals("아니") || normalized.equals("아니오") || normalized.equals("계속")) {
            awaitingExitConfirmation = false;
            String message = "클릭을 계속 사용할게요.";
            if (overlayView != null) overlayView.setRecognizedSpeech(message);
            speak(message);
            return true;
        }
        String message = "클릭을 종료할지 예 또는 아니오로 말씀해주세요.";
        if (overlayView != null) overlayView.setRecognizedSpeech(message);
        speak(message);
        return true;
    }

    private void askToExitAfterQueueCompleted() {
        nextQueuedRequest = null;
        awaitingExitConfirmation = true;
        String message = "요청한 작업이 모두 끝났어요. 클릭을 종료할까요?";
        ensureTaskFlow();
        taskFlowView.completed("요청한 작업이 모두 끝났어요.", this::shutdownClick);
        taskFlowCollapsed = false;
        if (overlayView != null) {
            overlayView.collapse();
            overlayView.setRecognizedSpeech(message);
        }
        placeTaskFlow(null, true);
        updateFloatingVisibility();
        speak(message);
    }

    /** A button stops local speech, automation and retries before any network reply. */
    private void haltLocalExecution() {
        adjustmentDelegation.clear();pendingApprovalProvenance=null;
        execution.pause();
        clearMissingScreenWait(); clearGuideResyncRetry(); clearTargetHelpWait();
        automationGuard.interrupt(); navigationRecovery.clear(); guideStepTimer.clear();
        homeSearchRestart = false; homeSearchToken++;
        automaticAdjustmentApprovalPending = false;
        execution.clearObservation();
        pendingActionType = null; pendingVerifiedAction = null;
        stopListening(); stopNarration(); clearVisionState(); actionTracker.clear();
        hideGuideOverlay(); hideConfirmation();
        if (resultTimeout != null) speechHandler.removeCallbacks(resultTimeout);
        resultTimeout = null;
    }

    private com.click.data.snapshot.AssistedSettingsGuidance assistedSettingsGuidance;

    private String goalAwarePrivacyMessage(String message, boolean sensitive) {
        if (!sensitive || execution.acceptedGoal() == null) return message;
        if (assistedSettingsGuidance == null) {
            try (java.io.InputStream input = getAssets().open("manual_guidance.json")) {
                assistedSettingsGuidance = com.click.data.snapshot.AssistedSettingsGuidance.load(input);
            } catch (Exception unavailable) { return message; }
        }
        return assistedSettingsGuidance.privacyMessage(execution.acceptedGoal().optString("goal_type", null), message);
    }

    private String stoppedGuideMessage(AgentTurnContract.Response response) {
        org.json.JSONObject policy = response.guideCommand == null ? null : response.guideCommand.renderPolicy;
        String reason = policy == null ? "" : policy.optString("observation_reason");
        boolean sensitive = policy != null && policy.optBoolean("privacy_blocked")
                && java.util.Set.of("credential_input", "input_present", "protected_screen").contains(reason);
        return goalAwarePrivacyMessage(ErrorDetails.guideFailureMessage(response), sensitive);
    }

    private boolean hasAssistedSettingsGoal() {
        return assistedSettingsGuidance != null && execution.acceptedGoal() != null
                && assistedSettingsGuidance.supports(execution.acceptedGoal().optString("goal_type", null));
    }

    private void showStoppedGuide(AgentTurnContract.Response response) {
        String message = stoppedGuideMessage(response);
        org.json.JSONObject policy = response.guideCommand == null ? null : response.guideCommand.renderPolicy;
        String reason = policy == null ? "" : policy.optString("observation_reason");
        boolean sensitive = policy != null && policy.optBoolean("privacy_blocked")
                && java.util.Set.of("credential_input", "input_present", "protected_screen").contains(reason);
        showStoppedTask(message, sensitive && hasAssistedSettingsGoal());
    }

    private void showStoppedTask(String message) { showStoppedTask(message, false); }

    private void showStoppedTask(String message, boolean manualPrivateStep) {
        execution.invalidate();
        execution.clearControl(); execution.clearQueuedResume();
        haltLocalExecution();
        execution.stop();
        setTurnInFlight(false); updateRequestStatus(false, false);
        ensureTaskFlow();
        String explanation = message == null || message.trim().isEmpty()
                ? "작업이 중단됐어요. 원하시면 새로 요청해 주세요." : message;
        if (manualPrivateStep) taskFlowView.privateSettingsStep(explanation);
        else taskFlowView.failed(explanation);
        taskFlowCollapsed = false;
        if (overlayView != null) { overlayView.collapse(); overlayView.setRecognizedSpeech(explanation); }
        updateFloatingVisibility(); placeTaskFlow(null, true);
        speak(explanation);
    }

    private void showPausedTask() {
        ensureTaskFlow(); taskFlowView.paused();
        taskFlowCollapsed = false;
        if (overlayView != null) overlayView.collapse();
        updateRequestStatus(true, false);
        updateFloatingVisibility(); placeTaskFlow(null, true);
    }

    private void sendAgentControl(String commandType) {
        if(coupangBrowse!=null) {
            if("cancel_task".equals(commandType)){onStopRequested();return;}
            if("pause_task".equals(commandType)) {
                execution.pause();hideGuideOverlay();stopNarration();
                ensureTaskFlow();taskFlowView.paused();placeTaskFlow(null,true);
            } else if("resume_task".equals(commandType)) {
                execution.resume();coupangBrowse.observe(ClickAccessibilityService.freshSnapshot());
            }
            return;
        }
        if (closing) return;
        if("pause_task".equals(commandType) || "cancel_task".equals(commandType)){adjustmentDelegation.clear();pendingApprovalProvenance=null;}
        if (!"pause_task".equals(commandType)) stopNarration();
        if ("pause_task".equals(commandType)) {
            haltLocalExecution();
            showPausedTask();
            if (execution.sessionId() == null || agentGateway == null) return;
        } else if ("resume_task".equals(commandType) && execution.sessionId() == null) {
            execution.resume();
            updateRequestStatus(true, true);
            ensureTaskFlow(); taskFlowView.waiting("사용자 요청을 처리하고 있어요");
            claimNextQueuedRequest(); return;
        } else if (execution.sessionId() == null || agentGateway == null) {
            if ("cancel_task".equals(commandType)) onStopRequested();
            return;
        }
        if ("resume_task".equals(commandType)) {
            execution.requestResume();
            execution.retry();
            presentationGate.clear();
            lastNarratedGuideKey = null;
            if (narrator != null) narrator.reset();
        }
        if (execution.busy()) {
            if ("resume_task".equals(commandType) && "pause_task".equals(execution.pendingControl())) {
                execution.queueResumeAfterPause();
                return;
            }
            execution.queueControl(commandType);
            execution.pause();
            actionTracker.clear(); pendingVerifiedAction = null;
            hideGuideOverlay(); hideConfirmation();
            if (!"pause_task".equals(commandType)) {
                ensureTaskFlow(); taskFlowView.waiting("요청한 중단을 처리하고 있어요…");
            }
            return;
        }
        if ("resume_task".equals(commandType)) {
            ScreenSnapshot current=ClickAccessibilityService.freshSnapshot();
            android.app.KeyguardManager keyguard=getSystemService(android.app.KeyguardManager.class);
            var scene=com.click.data.snapshot.ScreenEnvironment.classify(current,
                    ClickAccessibilityService.accessibilityWindowManager()!=null,keyguard!=null && keyguard.isDeviceLocked(),getPackageName());
            var pages=pageNavigation();JSONObject slots=execution.acceptedGoal()==null?null:execution.acceptedGoal().optJSONObject("slots");
            boolean offRoute=scene.kind()==com.click.data.snapshot.ScreenEnvironment.Kind.SETTINGS_SUBPAGE && pages!=null
                    && pages.offRoute(current,slots==null?null:slots.optString("setting",null));
            if(execution.acceptedGoal()!=null && (!scene.navigable() || offRoute)
                    && scene.kind()!=com.click.data.snapshot.ScreenEnvironment.Kind.UNOBSERVABLE && scene.kind()!=com.click.data.snapshot.ScreenEnvironment.Kind.SELF) {
                execution.deferResumeForEnvironment();environmentWaitKey=null;
                handleEnvironment(current);return;
            }
        }
        if (execution.episodeId() == null) {
            if ("pause_task".equals(commandType)) {
                return;
            }
            if ("resume_task".equals(commandType)) {
                execution.resume();
                if (goalPreparation != null && execution.acceptedGoal() == null) showGoalPreparation();
                else if (requirementReview != null && !requirementReview.confirmed()) showRequirementReview();
                else if (goalSelection != null) {
                    taskFlowView.goals("원하는 목표를 골라주세요.", goalSelection.optJSONArray("candidates"));
                    placeTaskFlow(null, true);
                } else if (execution.acceptedGoal() == null) resolveGoal(initialGoal, null);
                else sendAgentTurn(null, true);
                return;
            }
            if ("cancel_task".equals(commandType)) {
                abortActiveRequest(); finishActiveQueueRequest("USER_ABORTED");
                return;
            }
            return;
        }
        ScreenSnapshot snapshot = "resume_task".equals(commandType) ? ClickAccessibilityService.freshSnapshot() : null;
        if ("resume_task".equals(commandType) && snapshot == null) {
            speak("현재 화면 정보를 기다리고 있어요. 다시 이어서 진행을 눌러 주세요."); return;
        }
        execution.invalidate();
        automationGuard.interrupt();
        navigationRecovery.clear();
        homeSearchRestart = false;
        if ("cancel_task".equals(commandType) || "pause_task".equals(commandType)) execution.pause();
        clearVisionState(); actionTracker.clear(); pendingVerifiedAction = null;
        hideConfirmation(); hideGuideOverlay();
        if (resultTimeout != null) speechHandler.removeCallbacks(resultTimeout);
        sendFlowRequest(AgentTurnContract.Request.control(execution.sessionId(),
                execution.sessionVersion(), execution.episodeId(), commandType, snapshot));
    }

    private void sendAgentReplacement(String sourceText) {
        pendingReplacement = sourceText;
        sendAgentControl("cancel_task");
    }

    private boolean hasSettingsTarget(ScreenSnapshot snapshot) {
        return settingsIconTarget(snapshot) != null;
    }

    private AccessibilityNodeSnapshot settingsIconTarget(ScreenSnapshot snapshot) {
        if (snapshot == null || !NavigationRecovery.isHomeScreen(snapshot)) return null;
        AccessibilityNodeSnapshot selected = null;
        for (AccessibilityNodeSnapshot node : snapshot.nodes) {
            if (!node.visibleToUser || !node.enabled || node.sensitiveInput
                    || !snapshot.packageName.equals(node.packageName)
                    || node.bounds == null || node.bounds.length != 4
                    || node.bounds[2] <= 0 || node.bounds[3] <= 0
                    || node.bounds[0] >= snapshot.screenWidth || node.bounds[1] >= snapshot.screenHeight
                    || node.bounds[0] >= node.bounds[2] || node.bounds[1] >= node.bounds[3]) continue;
            if (!(containsSettingsLabel(node.text) || containsSettingsLabel(node.contentDescription))) continue;
            AccessibilityNodeSnapshot target=com.click.service.accessibility.TouchAutomationPolicy.clickableTarget(snapshot,node.nodeId,node.bounds);
            if(target==null)continue;
            if(selected!=null && (!selected.nodeId.equals(target.nodeId)
                    || selected.windowId!=target.windowId || !java.util.Arrays.equals(selected.bounds,target.bounds)))return null;
            selected=target;
        }
        return selected;
    }

    /** Native, user-activated app navigation; not a fabricated Jev choice or auto tap. */
    private boolean showSettingsIconGuide(ScreenSnapshot screen) {
        if(!hasConfirmedGoal() || execution.acceptedGoal()==null || !"android_settings".equals(execution.acceptedGoal().optString("target_app")))return false;
        AccessibilityNodeSnapshot target=settingsIconTarget(screen);
        if(target==null)return false;
        showAppIconGuide(screen,target,"설정");
        return true;
    }

    private AccessibilityNodeSnapshot coupangIconTarget(ScreenSnapshot screen) {
        if(screen==null || !NavigationRecovery.isHomeScreen(screen) || screen.activeWindowId==null)return null;
        AccessibilityNodeSnapshot selected=null;
        for(AccessibilityNodeSnapshot node:screen.nodes) {
            if(!node.visibleToUser || !node.enabled || !node.clickable || node.sensitiveInput || node.checkable || node.scrollable
                    || !screen.packageName.equals(node.packageName) || node.windowId!=screen.activeWindowId
                    || node.bounds==null || node.bounds.length!=4 || node.bounds[0]<0 || node.bounds[1]<0
                    || node.bounds[2]>screen.screenWidth || node.bounds[3]>screen.screenHeight
                    || node.bounds[0]>=node.bounds[2] || node.bounds[1]>=node.bounds[3]
                    || node.bounds[2]-node.bounds[0]>screen.screenWidth/2
                    || node.bounds[3]-node.bounds[1]>screen.screenHeight/2)continue;
            if(!coupangAppLabel(node.text) && !coupangAppLabel(node.contentDescription))continue;
            if(selected!=null)return null;
            selected=node;
        }
        return selected;
    }

    private static boolean coupangAppLabel(String label) {
        if(label==null)return false;
        String name=label.trim().toLowerCase(java.util.Locale.ROOT);
        return name.equals("쿠팡") || name.equals("coupang") || name.startsWith("쿠팡,") || name.startsWith("coupang,");
    }

    private AccessibilityNodeSnapshot appIconTarget(ScreenSnapshot screen) {
        return execution.acceptedGoal()!=null && "coupang".equals(execution.acceptedGoal().optString("target_app"))
                ? coupangIconTarget(screen) : settingsIconTarget(screen);
    }

    private void showAppIconGuide(ScreenSnapshot screen,AccessibilityNodeSnapshot target,String app) {
        ensureTaskFlow();hideConfirmation();stopNarration();guideStepTimer.clear();actionTracker.clear();automationGuard.interrupt();
        clearTargetHelpWait();targetHelpGeneration=execution.generation();targetHelpScreen=screen;appIconGuide=true;
        clearMissingScreenWait();execution.clearObservation();execution.retry();
        String message="표시한 "+app+" 앱을 눌러주세요.";
        taskFlowView.progress(initialGoal,null,message,false);taskFlowView.setAutomaticProgress(false);
        showGuideOverlay(target.bounds,"tap",0);nativeGuideScreen=screen;
        placeTaskFlow(target.bounds,false);collapseTaskFlow();
        if(overlayView!=null)overlayView.setRecognizedSpeech(message);
        speak(message);
    }

    private boolean isSettingsGoal(String goal) {
        if (execution.acceptedGoal() != null) return "android_settings".equals(execution.acceptedGoal().optString("target_app"));
        if (activeGoalMatch != null) return "android_settings".equals(activeGoalMatch.targetApp);
        String normalized = goal.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return normalized.contains("설정") || normalized.contains("글자크기")
                || normalized.contains("글씨크기") || normalized.contains("폰트크기")
                || normalized.contains("fontsize") || normalized.contains("textsize");
    }

    private String foregroundPackage() {
        ScreenSnapshot snapshot = LatestScreenSnapshot.get();
        return snapshot == null ? null : snapshot.packageName;
    }

    private GoalCatalogClassifier loadGoalCatalog() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("mvp_goals.json"), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line).append('\n');
            GoalCatalogClassifier catalog = GoalCatalogClassifier.fromJson(json.toString());
            android.util.Log.i("ClickGoal", "Loaded goal catalog " + catalog.version()
                    + " with " + catalog.size() + " goals");
            return catalog;
        } catch (Exception error) {
            android.util.Log.e("ClickGoal", "Goal catalog could not be loaded", error);
            return null;
        }
    }

    private boolean containsSettingsLabel(String value) {
        return com.click.service.accessibility.TouchAutomationPolicy.isSettingsAppLabel(value);
    }

    private boolean canOfferSettingsEntry(AgentTurnContract.Response response, ScreenSnapshot screen) {
        if (screen == null || !NavigationRecovery.isHomeScreen(screen) || execution.acceptedGoal() == null
                || !"android_settings".equals(execution.acceptedGoal().optString("target_app"))
                || !"S0_UNKNOWN".equals(response.stateId) || response.guideCommand == null
                || response.guideCommand.targetBounds != null
                || !"observe_only".equals(response.guideCommand.actionType)) return false;
        JSONObject policy = response.guideCommand.renderPolicy;
        JSONObject recovery = policy == null ? null : policy.optJSONObject("recovery");
        if (recovery == null || !"user".equals(recovery.optString("waiting_for"))) return false;
        String reason = recovery.optString("reason_code");
        return "APP_NOT_OPEN".equals(reason) || "LOW_CONFIDENCE".equals(reason)
                || "MODEL_ABSTAINED".equals(reason) || "MODEL_WAIT_WITHOUT_ACTION".equals(reason)
                || "MODEL_UNAVAILABLE".equals(reason) || "TARGET_NOT_FOUND".equals(reason)
                || "STATE_UNKNOWN".equals(reason);
    }

    private long settingsEntryPresentation;

    private void showSettingsEntryRecovery() { showSettingsEntryRecovery(null); }

    private void showSettingsEntryRecovery(String failure) {
        ScreenSnapshot entryScreen = ClickAccessibilityService.freshSnapshot();
        if (recoverObservedSettingsSubpage(entryScreen)) return;
        if (ObservationRecovery.canReturnFromSettings(entryScreen)) {
            recoverSettingsBack(entryScreen);
            return;
        }
        guideStepTimer.clear(); actionTracker.clear(); automationGuard.interrupt();
        clearVisionState();agentConfirmationId=null;confirmationExpiresAt=0;
        automaticAdjustmentApprovalPending=false;
        adjustmentDelegation.clear();pendingApprovalProvenance=null;
        hideGuideOverlay(); hideConfirmation();
        ensureTaskFlow();
        final long presentation=++settingsEntryPresentation;
        final int generation=execution.generation();
        final String session=execution.sessionId();
        execution.recovery();
        taskFlowView.setAutomaticProgress(false);
        String message=failure==null ? "‘설정 열기’를 눌러주세요. 설정 화면에서 이어서 안내할게요." : failure;
        Runnable open=()->{
            if(presentation!=settingsEntryPresentation || generation!=execution.generation()
                    || !java.util.Objects.equals(session,execution.sessionId()) || closing
                    || execution.paused() || execution.pendingControl()!=null)return;
            ScreenSnapshot latest = ClickAccessibilityService.freshSnapshot();
            if (recoverObservedSettingsSubpage(latest)) return;
            if (ObservationRecovery.canReturnFromSettings(latest)) {
                execution.retry();
                recoverSettingsBack(latest);
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch(RuntimeException unavailable) {
                ++settingsEntryPresentation;
                String unavailableMessage="설정 앱을 열지 못했어요. 휴대폰에서 설정 앱을 직접 열어주세요.";
                taskFlowView.failureRecovery("설정 앱을 직접 열어주세요",unavailableMessage,null,null);
                speak(unavailableMessage);return;
            }
            ++settingsEntryPresentation; // This presentation cannot launch twice.
            final long opened=settingsEntryPresentation;
            navigationRecovery.clear();environmentWait=null;environmentWaitKey=null;
            clearGuideResyncRetry();clearMissingScreenWait();
            pendingVerifiedAction=null;agentConfirmationId=null;confirmationExpiresAt=0;
            adjustmentDelegation.clear();pendingApprovalProvenance=null;
            execution.retry();execution.requestObservation();
            pendingActionOccurredAtMs=System.currentTimeMillis();
            pendingActionType=AgentActionEvents.MANUAL_OVERRIDE;
            taskFlowView.waiting("설정 화면을 확인하고 있어요.");
            ClickAccessibilityService.requestFreshSnapshot();
            speechHandler.postDelayed(()->{
                if(opened!=settingsEntryPresentation || generation!=execution.generation()
                        || !java.util.Objects.equals(session,execution.sessionId()) || closing
                        || execution.paused() || execution.pendingControl()!=null)return;
                ScreenSnapshot current=ClickAccessibilityService.freshSnapshot();
                if(current!=null && com.click.data.snapshot.ScreenEnvironmentPolicy.contains("settings_packages",current.packageName)) {
                    sendAgentTurn(null,true);
                } else {
                    showSettingsEntryRecovery("설정 화면으로 이동한 것을 확인하지 못했어요. ‘설정 열기’를 다시 누르거나 설정 앱을 직접 열어주세요.");
                }
            },1200L);
        };
        if(failure==null)taskFlowView.settingsEntryRecovery(open);
        else taskFlowView.settingsEntryRecovery(message,open);
        taskFlowCollapsed=false;
        if(overlayView!=null){overlayView.collapse();overlayView.setRecognizedSpeech(message);}
        updateFloatingVisibility();placeTaskFlow(null,true);speak(message);
    }

    /** A delayed app-entry recovery must not reopen or leave a page already reached. */
    private boolean recoverObservedSettingsSubpage(ScreenSnapshot current) {
        if (current==null || execution.acceptedGoal()==null
                || !"android_settings".equals(execution.acceptedGoal().optString("target_app"))) return false;
        android.app.KeyguardManager keyguard=getSystemService(android.app.KeyguardManager.class);
        var scene=com.click.data.snapshot.ScreenEnvironment.classify(current,
                ClickAccessibilityService.accessibilityWindowManager()!=null,
                keyguard!=null && keyguard.isDeviceLocked(),getPackageName());
        var pages=pageNavigation();
        JSONObject slots=execution.acceptedGoal().optJSONObject("slots");
        if(scene.kind()!=com.click.data.snapshot.ScreenEnvironment.Kind.SETTINGS_SUBPAGE || pages==null
                || !pages.onRoute(current,slots==null?null:slots.optString("setting",null))
                || !com.click.data.snapshot.SettingsCapabilities.routePageTitle(
                    execution.acceptedGoal().optString("goal_type"),com.click.data.snapshot.ScreenIdentity.pageTitle(current))) return false;
        ++settingsEntryPresentation;
        navigationRecovery.clear();environmentWait=null;environmentWaitKey=null;
        clearTargetHelpWait();clearGuideResyncRetry();clearMissingScreenWait();
        hideGuideOverlay();hideConfirmation();stopNarration();
        agentConfirmationId=null;confirmationExpiresAt=0;
        automaticAdjustmentApprovalPending=false;
        adjustmentDelegation.clear();pendingApprovalProvenance=null;
        ensureTaskFlow();
        if(!com.click.data.snapshot.ScreenIdentity.samePage(lastSubmittedScreen,current) && execution.pageChanged()) {
            observedPageScreen=current;execution.requestObservation();
            taskFlowView.waitingProgress("현재 설정 화면에서 이어서 안내할게요.");
            if(execution.busy())pendingPageScreen=current;
            else sendAgentTurn(null,true);
        } else {
            execution.recovery();
            final int generation=execution.generation();
            showObservationRecovery("설정 화면에 있어요. 현재 화면을 다시 확인하고 안내를 이어갈게요.",
                    ()->retryObservation(generation,true));
        }
        return true;
    }

    private boolean stableManualSettingsPage(AgentTurnContract.GuideCommand command, ScreenSnapshot latest) {
        if (execution.acceptedGoal() == null || command.renderPolicy == null || !command.renderPolicy.optBoolean("manual_handoff")
                || !"observe_only".equals(command.actionType) || command.targetNodeId != null || command.targetBounds != null) return false;
        try {
            JSONObject spec = manualSettingsSpec();
            if (spec == null) return false;
            org.json.JSONArray pages = spec.getJSONArray("pages");
            java.util.Set<String> titles = new java.util.HashSet<>();
            for (int i=0;i<pages.length();i++) titles.add(pages.getString(i));
            return GuideFreshness.sameManualSettingsPage(lastSubmittedScreen, latest, titles);
        } catch (Exception unavailable) { return false; }
    }

    private JSONObject manualSettingsSpec() {
        if (execution.acceptedGoal() == null) return null;
        try (java.io.InputStream in = getAssets().open("settings_execution.json")) {
            JSONObject spec = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getJSONObject("goals").optJSONObject(execution.acceptedGoal().optString("goal_type"));
            return spec != null && "assisted".equals(spec.optString("mode")) ? spec : null;
        } catch (Exception unavailable) { return null; }
    }

    private void presentManualSettingsHandoff(AgentTurnContract.Response response) {
        AgentTurnContract.GuideCommand command=response.guideCommand;
        // Use the same bundled authored instructions as the runtime, including
        // empty Bluetooth discovery help, even with an older server response.
        JSONObject spec=manualSettingsSpec();
        String message=spec==null ? command.message : spec.optString("instruction",command.message)
                + " 직접 진행한 뒤 완료 확인을 눌러주세요.";
        actionTracker.clear();pendingVerifiedAction=null;guideStepTimer.clear();
        automationGuard.interrupt();clearVisionState();execution.clearObservation();
        hideGuideOverlay();hideConfirmation();
        try {
            if (presentationGate.accept(execution.episodeId(),"manual_settings",message)) {
                taskFlowView.progress(requirementReview!=null ? requirementReview.summary() : "선택한 작업",null,message,
                        command.renderPolicy.optBoolean("allow_result_check") && agentGateway.currentContract()!=null
                            && agentGateway.currentContract().supports("request_result"));
                taskFlowView.setAutomaticProgress(false);updateFloatingVisibility();placeTaskFlow(null,false);
                if(overlayView!=null)overlayView.setRecognizedSpeech(message);
                speak(message);
            }
            execution.manualPresented();
        } catch (RuntimeException error) {
            // Commit MANUAL only after its UI exists. A failed presentation has an explicit retry path.
            presentationGate.clear();execution.recovery();
            android.util.Log.e("ClickAgent","Manual instructions could not be presented",error);
            showObservationRecovery("안내를 표시하지 못했어요. 다시 시도해 주세요.", () -> {
                execution.retry();execution.requestObservation();ClickAccessibilityService.requestFreshSnapshot();
            });
            taskFlowCollapsed=false;updateFloatingVisibility();placeTaskFlow(null,true);
        }
    }

    private boolean showCoupangNativeGuide(ScreenSnapshot screen) {
        if(coupangBrowse!=null&&gov24Guide)return false;
        boolean goal=coupangBrowse!=null || execution.acceptedGoal()!=null
                && "coupang".equals(execution.acceptedGoal().optString("target_app"));
        if(!goal || closing || execution.sessionId()==null || execution.paused()
                || execution.pendingControl()!=null || resultPrompt!=null || followUp!=null)return false;
        if(coupangBrowse!=null && coupangBrowse.terminal())return false;
        if(coupangBrowse==null && !hasConfirmedGoal())return false;
        boolean wasLocal=coupangNativeGuide!=null;
        if(coupangNativeGuide!=null && waitingForTargetHelp() && preserveTargetHelp(screen))return true;
        var guide=com.click.data.snapshot.CoupangScreenGuide.identify(screen);
        if(guide==null) {
            if(wasLocal && coupangBrowse!=null) {
                taskFlowView.browsing("쿠팡 화면이 바뀌었어요. 현재 화면 확인을 눌러 이어서 안내받으세요.",false,
                    ()->{if(coupangBrowse!=null)coupangBrowse.observe(ClickAccessibilityService.freshSnapshot());},
                    this::openCoupang,()->{if(coupangBrowse!=null)coupangBrowse.finish();});
                taskFlowView.setAutomaticProgress(false);taskFlowCollapsed=false;placeTaskFlow(null,true);
                return true;
            }
            return false;
        }
        ensureTaskFlow();hideConfirmation();stopNarration();guideStepTimer.clear();actionTracker.clear();automationGuard.interrupt();
        clearTargetHelpWait();targetHelpGeneration=execution.generation();targetHelpScreen=screen;coupangNativeGuide=guide;
        clearMissingScreenWait();execution.clearObservation();execution.retry();
        taskFlowView.progress(initialGoal,null,guide.message,false);taskFlowView.setAutomaticProgress(false);
        if(guide.target!=null) {
            showGuideOverlay(guide.target.bounds,"tap",0);nativeGuideScreen=screen;
            placeTaskFlow(guide.target.bounds,false);collapseTaskFlow();
        } else {hideGuideOverlay();taskFlowCollapsed=false;placeTaskFlow(null,true);}
        if(overlayView!=null)overlayView.setRecognizedSpeech(guide.message);
        speak(guide.message);
        return true;
    }

    private void showCoupangEntryRecovery() {
        showCoupangEntryRecovery(true);
    }

    private void showCoupangEntryRecovery(boolean searchPages) {
        if (showCoupangNativeGuide(ClickAccessibilityService.freshSnapshot())) return;
        ScreenSnapshot entryScreen=ClickAccessibilityService.freshSnapshot();
        if(appIconGuide && preserveTargetHelp(entryScreen))return;
        AccessibilityNodeSnapshot icon=coupangIconTarget(entryScreen);
        if(icon!=null) {
            showAppIconGuide(entryScreen,icon,"쿠팡");
            return;
        }
        if (searchPages && hasConfirmedGoal() && NavigationRecovery.isHomeScreen(entryScreen)) {
            if (scrollSearchGeneration == execution.generation()) return;
            execution.retry();
            if (startAutomaticHomeSearch(entryScreen)) return;
        }
        clearTargetHelpWait();
        ensureTaskFlow();
        final int generation = execution.generation();
        final String session = execution.sessionId();
        execution.recovery();
        taskFlowView.setAutomaticProgress(false);
        hideConfirmation(); hideGuideOverlay();
        taskFlowView.appEntryRecovery("쿠팡", () -> {
            if (generation != execution.generation() || !java.util.Objects.equals(session, execution.sessionId())
                    || closing || execution.paused() || execution.busy() || execution.pendingControl() != null) return;
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.coupang.mobile");
            if (launch == null) {
                android.widget.Toast.makeText(this, "쿠팡 앱을 설치한 뒤 다시 시도해 주세요.", android.widget.Toast.LENGTH_LONG).show();
                showCoupangEntryRecovery(); return;
            }
            try { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (RuntimeException unavailable) { showCoupangEntryRecovery(); return; }
            execution.retry();
            pendingVerifiedAction = null;
            pendingActionType = AgentActionEvents.MANUAL_OVERRIDE;
            pendingActionOccurredAtMs = System.currentTimeMillis();
            speechHandler.postDelayed(() -> {
                if (generation == execution.generation() && java.util.Objects.equals(session, execution.sessionId())
                        && !closing && !execution.paused()) sendAgentTurn(null, true);
            }, 1200L);
        });
        taskFlowCollapsed = false;
        placeTaskFlow(null, true);
    }

    private void renderGuide(AgentTurnContract.Response response) {
        response.logDebug("ClickAgent");
        if (response.guideCommand == null) return;
        if ("blocked".equals(response.guideCommand.commandType) || "BLOCKED".equals(response.taskStatus)) {
            showStoppedGuide(response); return;
        }
        if (!"done".equals(response.guideCommand.commandType)
                && showCoupangNativeGuide(ClickAccessibilityService.freshSnapshot())) return;
        if ("done".equals(response.guideCommand.commandType) && coupangNativeGuide!=null)clearTargetHelpWait();
        if (waitingForTargetHelp() || execution.paused() || execution.retryRequired()) return;
        if (AgentActionEvents.BACK_NAVIGATED.equals(pendingActionType)
                || AgentActionEvents.HOME_NAVIGATED.equals(pendingActionType)) return;
        ensureTaskFlow();
        AgentTurnContract.GuideCommand command = response.guideCommand;
        // Preserve the device value from the observation that produced this slider guide,
        // including when the user moves it while the server response is in flight.
        adjustmentResultCheck.bind(execution.episodeId(), execution.acceptedGoal() == null ? null : execution.acceptedGoal().optString("goal_type"),
                command, lastSubmittedScreen);
        ScreenSnapshot displayedSnapshot = null;
        if (!"done".equals(command.commandType) && !"blocked".equals(command.commandType)) {
            // Read the actual screen before any message, overlay, confirmation, or action binding.
            // Redundant AX events do not invalidate identical native screen content.
            // OCR still checks its revision in GuideFreshness because pixels may
            // change without appearing in the accessibility tree.
            displayedSnapshot = ClickAccessibilityService.freshSnapshot();
            if(handleEnvironment(displayedSnapshot))return;
            JSONObject entry=command.recoveryAction();
            boolean settingsEntry=entry!=null && "open_android_settings_or_show_app_drawer".equals(entry.optString("type"));
            if((settingsEntry || canOfferSettingsEntry(response,displayedSnapshot))
                    && showSettingsIconGuide(displayedSnapshot))return;
            // A persisted setting change can outlive the SeekBar's window/node after a redraw.
            // Check the submitted value and a fresh value before retrying or reissuing a drag guide.
            if (requestChangedAdjustmentResult(displayedSnapshot)) return;
            if (lastSubmittedScreen == null || displayedSnapshot == null
                    || command.targetBounds == null
                        && !stableManualSettingsPage(command, displayedSnapshot)
                        && !(canSearchSettingsMenu(response,displayedSnapshot)
                            && ObservationRecovery.sameNavigationScreen(lastSubmittedScreen,displayedSnapshot))
                        && !lastSubmittedScreen.contentFingerprint().equals(displayedSnapshot.contentFingerprint())
                    || !GuideFreshness.stillValid(lastSubmittedScreen, displayedSnapshot,
                            command.targetNodeId, command.targetBounds,
                            command.renderPolicy != null && command.renderPolicy.optBoolean("vision_derived"))) {
                if (handleSettingsPageChange(displayedSnapshot)) return;
                waitForFreshGuide();
                return;
            }
        }
        if (revealClippedTarget(response, displayedSnapshot)) return;
        execution.guidePresented("confirmation".equals(command.commandType));
        // Do not suspend observations until the handoff has passed freshness
        // checks. A stale response must leave the refresh path usable.
        clearGuideResyncRetry();
        if (command.renderPolicy != null && command.renderPolicy.optBoolean("manual_handoff")) {
            // Personal choices remain local only after the instructions can be
            // presented. The result button submits no accessibility tree.
            presentManualSettingsHandoff(response);
            return;
        }
        // A HOME_NAVIGATED event must reach the server first. Continue page search from
        // its response as well; an unchanged launcher need not emit another AX event.
        if (displayedSnapshot != null && hasConfirmedGoal() && shouldScanHomeForSettings(displayedSnapshot)) {
            hideConfirmation(); hideGuideOverlay();
            if (startAutomaticHomeSearch(displayedSnapshot)) return;
        }
        boolean canSearchByScrolling = canSearchSettingsMenu(response,displayedSnapshot);
        if (command.targetBounds == null && displayedSnapshot != null) {
            JSONObject recovery = command.renderPolicy == null ? null : command.renderPolicy.optJSONObject("recovery");
            JSONObject fallback = command.fallbackAction();
            android.util.Log.i("ClickScroll", "search state=" + response.stateId + " action=" + command.actionType
                    + " reason=" + (recovery == null ? "none" : recovery.optString("reason_code"))
                    + " fallback=" + (fallback == null ? "none" : fallback.optString("reason", fallback.optString("reason_code")))
                    + " visibleLists=" + displayedSnapshot.nodes.stream()
                            .filter(n -> n.visibleToUser && n.enabled && n.scrollable).count()
                    + " eligible=" + canSearchByScrolling);
        }
        // An absent item on a recognized menu needs list search before back/home recovery.
        boolean targetSelectionHelp = ObservationRecovery.targetSelectionFailed(command.renderPolicy)
                && ObservationRecovery.targetLabel(response.stateId,
                        execution.acceptedGoal() == null ? null : execution.acceptedGoal().optString("goal_type")) != null;
        if (!canSearchByScrolling && !targetSelectionHelp
                && showNavigationRecoveryIfNeeded(response, displayedSnapshot)) return;
        if (displayedSnapshot != null && command.targetBounds != null
                && ("tap".equals(command.actionType) || "drag".equals(command.actionType))
                && !ObservationRecovery.awaitsUser(command.renderPolicy))
            navigationRecovery.onPath(displayedSnapshot);
        // Unsupported native confirmation must count toward the bounded refresh limit.
        if (!isAdjustmentConfirmation(command)) staleGuideDrops = 0;
        JSONObject fallbackAction = command.fallbackAction();
        // Observation retries are internal. Older servers may include diagnostic
        // text here; never put that text in the panel or its replay narration.
        boolean refreshingObservation = fallbackAction != null
                && "request_fresh_observation".equals(fallbackAction.optString("type"));
        JSONObject settingsRecovery = command.recoveryAction();
        boolean openingSettings = settingsRecovery != null && "open_android_settings_or_show_app_drawer"
                .equals(settingsRecovery.optString("type"));
        JSONObject recoveryPresentation = command.renderPolicy == null ? null : command.renderPolicy.optJSONObject("recovery");
        String presentation = response.stateId + ":" + command.commandType + ":" + command.actionType
                + ":" + java.util.Arrays.toString(command.targetBounds) + ":" + command.message
                + ":" + command.proposedAction + ":" + command.dragDirection()
                + (recoveryPresentation == null ? "" : ":" + recoveryPresentation.optString("reason_code")
                    + ":" + recoveryPresentation.optString("waiting_for") + ":" + recoveryPresentation.optString("next_action"));
        if (canSearchByScrolling && displayedSnapshot != null) presentation += ":" + ObservationRecovery.navigationFingerprint(displayedSnapshot);
        boolean present = presentationGate.accept(execution.episodeId(), "guide", presentation);
        if (present) {
            taskFlowView.progress(requirementReview != null ? requirementReview.summary() : "선택한 작업",
                    response.metadata == null ? null : response.metadata.optJSONArray("checklist"),
                    refreshingObservation ? "안내를 준비하고 있어요."
                            : openingSettings ? "설정 앱을 열어주세요." : command.message,
                    agentGateway.currentContract() != null
                            && agentGateway.currentContract().supports("request_result")
                            && !"confirmation".equals(command.commandType)
                            && (command.renderPolicy != null && command.renderPolicy.optBoolean("allow_result_check")
                                || execution.acceptedGoal() != null && adjustmentResultCheck.changed(execution.episodeId(),
                                    execution.acceptedGoal().optString("goal_type"), displayedSnapshot)
                                || command.renderPolicy != null && command.renderPolicy.optBoolean("adjustment_limit")
                                || SettingsAdjustment.forGoal(execution.acceptedGoal() == null ? null : execution.acceptedGoal().optString("goal_type")) == null
                                    && command.requiresUserAction && response.totalSteps > 0 && response.step == response.totalSteps));
            taskFlowView.setGuidanceProgress(response.step, response.totalSteps);
            if (displayedSnapshot != null && command.requiresUserAction
                    && "tap".equals(command.actionType) && command.targetNodeId != null) {
                for (AccessibilityNodeSnapshot node : displayedSnapshot.nodes) {
                    if (!command.targetNodeId.equals(node.nodeId) || !node.visibleToUser
                            || !node.enabled || node.sensitiveInput) continue;
                    taskFlowView.setGuidanceTarget(node.text != null && !node.text.trim().isEmpty()
                            ? node.text : node.contentDescription);
                    break;
                }
            }
            taskFlowView.setAutomaticProgress(automaticTouchEnabled()
                    && !"confirmation".equals(command.commandType) && !"blocked".equals(command.commandType));
            // A response refresh must not undo the user's explicit collapse, including confirmations.
            updateFloatingVisibility();
        }
        lastGuideCommandId = command.guideCommandId;
        lastTargetNodeId = command.targetNodeId;
        lastTargetBounds = command.targetBounds;
        if (command.requiresUserAction && !"confirmation".equals(command.commandType)) {
            actionTracker.bind(execution.sessionId(), execution.episodeId(), command.guideCommandId, command.actionType,
                    command.targetNodeId, command.targetBounds, displayedSnapshot, System.currentTimeMillis());
        } else if (!"observe_only".equals(command.actionType) || !actionTracker.isBoundToGuide(command.guideCommandId)) {
            actionTracker.clear();
        }
        if (present) placeTaskFlow(command.targetBounds, false);
        if (command.requiresUserAction && "tap".equals(command.actionType)
                && displayedSnapshot != null && displayedSnapshot.observationFingerprint().equals(lastSnapshotFingerprint)) {
            guideStepTimer.start(execution.sessionId(), response.stateId, response.step,
                    displayedSnapshot.packageName,
                    SystemClock.uptimeMillis());
        } else {
            guideStepTimer.clear();
        }

        if (tryAutomaticAdjustmentApproval(command, response.metadata, displayedSnapshot)) {
            staleGuideDrops = 0;
            return;
        }
        // BE-driven fallback / recovery policies are checked first: vision_fallback in
        // particular rides on a command_type of "confirmation".
        JSONObject recoveryAction = command.recoveryAction();
        JSONObject visionFallback = command.visionFallback();
        if (recoveryAction != null) {
            if (!present) return;
            if (recoverObservedSettingsSubpage(displayedSnapshot)) return;
            if("navigate_home".equals(recoveryAction.optString("type"))){navigationRecovery.forceHome();showSettingsEntryRecovery();return;}
            if("dismiss_interruption".equals(recoveryAction.optString("type"))){navigationRecovery.offPathPage(lastSubmittedScreen);showSettingsEntryRecovery();return;}
            hideConfirmation();
            hideGuideOverlay();
            if ("open_coupang".equals(recoveryAction.optString("type"))) {
                showCoupangEntryRecovery(); return;
            }
            if ("navigate_back".equals(recoveryAction.optString("type"))
                    || ObservationRecovery.canReturnFromSettings(displayedSnapshot)) {
                recoverSettingsBack(displayedSnapshot);
                return;
            }
            showSettingsEntryRecovery();
            return;
        }
        if (visionFallback != null) {
            if (!present) return;
            hideConfirmation();
            hideGuideOverlay();
            speak(command.message);
            handleVisionFallback(command, visionFallback);
            return;
        }
        // Older servers may omit open-settings recovery on a primary-model wait.
        // Offer the same user-activated OS entry after their bounded retries;
        // never turn a model abstention into an automatic icon tap.
        if (canOfferSettingsEntry(response, displayedSnapshot)) {
            if (present) {
                hideConfirmation(); hideGuideOverlay();
                showSettingsEntryRecovery();
            }
            return;
        }
        if (canSearchByScrolling || ObservationRecovery.awaitsUser(command.renderPolicy)) {
            if (!canSearchByScrolling) execution.recovery();
            if (!present) {
                if (canSearchByScrolling) startAutomaticSettingsSearch(response.stateId, displayedSnapshot);
                return;
            }
            hideConfirmation(); hideGuideOverlay();
            actionTracker.clear();
            final int generation = execution.generation();
            Runnable retry = () -> retryObservation(generation, true);
            String goal = requirementReview != null ? requirementReview.summary() : "선택한 작업";
            if (canSearchByScrolling) {
                final ScreenSnapshot searchScreen = displayedSnapshot;
                if (startAutomaticSettingsSearch(response.stateId, searchScreen)) return;
                showTargetHelp(response.stateId);
                return;
            } else {
                JSONObject recovery = command.renderPolicy.optJSONObject("recovery");
                JSONObject failure = recovery == null ? null : recovery.optJSONObject("failure");
                if (failure != null) {
                    taskFlowView.selectionRecovery(command.message, failure.optString("required_change"), retry);
                    speak(taskFlowView.guidanceMessage());
                    placeTaskFlow(null, true);
                    return;
                }
                if (targetSelectionHelp) {
                    showTargetHelp(response.stateId);
                    return;
                }
                showTypedRecovery(recovery==null?null:recovery.optString("reason_code"),retry);
                speak(taskFlowView.guidanceMessage());
            }
            placeTaskFlow(null, true);
            return;
        }
        if (fallbackAction != null
                && "request_fresh_observation".equals(fallbackAction.optString("type"))) {
            hideConfirmation();
            hideGuideOverlay();
            stopNarration();
            handleFreshObservationRequest(command, response.stateId);
            return;
        }

        if ("confirmation".equals(command.commandType)) {
            if (!present && !isAdjustmentConfirmation(command)) return;
            // Show what will be adjusted before asking for permission to continue.
            showGuideOverlay(command.targetBounds, "observe_only", 0);
            if (isAdjustmentConfirmation(command) && !AutomaticAdjustmentApproval.eligible(command,response.metadata,displayedSnapshot)) {
                waitForFreshGuide();
            } else {
                // A confirmed direction is not an implicit action approval. A bounded
                // delegation is optional; otherwise this is a direct user decision.
                showConfirmation(command.message);
                speakGuide(response);
            }
            return;
        }
        hideConfirmation();
        if ("done".equals(command.commandType) || "blocked".equals(command.commandType)
                || "COMPLETED".equals(response.taskStatus) || "BLOCKED".equals(response.taskStatus)) {
            hideGuideOverlay();
            boolean completed = "done".equals(command.commandType)
                    || "COMPLETED".equals(response.taskStatus);
            if(completed)showCompletedTask(command.message,CompletionEvidence.verified(response.metadata,execution.acceptedGoal()==null?null:execution.acceptedGoal().optString("goal_type")));
            else finishActiveQueueRequest("AGENT_ERROR");
            return;
        }

        if (!present) return;
        freshObservationRetries = 0;
        if (command.targetNodeId != null && command.requiresUserAction) {
            assistedRecoveryGeneration = -1;
            lastBackRecoveryScreen = null; settingsBackRecoveries = 0;
        }
        showGuideOverlay(command.targetBounds, command.actionType,
                GuideDragDirection.resolve(command.renderPolicy, response.metadata));
        if ("scroll".equals(command.actionType) && displayedSnapshot != null
                && com.click.service.accessibility.TouchAutomationPolicy.settingsPackage(displayedSnapshot.packageName)) {
            hideGuideOverlay(); stopNarration();
            taskFlowView.waitingProgress("필요한 항목을 찾고 있어요.");
            taskFlowView.setAutomaticProgress(true);
            collapseTaskFlow();
            executeScroll(command);
            return;
        }
        if (overlayView != null) overlayView.setRecognizedSpeech(command.message);
        speakGuide(response);
        visionGuideScreen = command.targetBounds != null && command.renderPolicy != null
                && command.renderPolicy.optBoolean("vision_derived") ? lastSubmittedScreen : null;
        executeAutomaticAction(command, response.metadata, displayedSnapshot, response.stateId);
        if ("scroll".equals(command.actionType)) {
            executeScroll(command);
        }
    }

    public static void onSystemNavigationRequested(boolean back) {
        ClickOverlayService service = runningService.get();
        if (service == null || service.execution.sessionId() == null || service.execution.paused()) return;
        service.guideStepTimer.clear();
        service.requestRecoveryNavigation(back ? NavigationRecovery.Action.BACK : NavigationRecovery.Action.HOME,
                LatestScreenSnapshot.get(), true);
        ClickAccessibilityService.requestFreshSnapshot();
    }

    public static void onAutomationSettingChanged() {
        ClickOverlayService service = runningService.get();
        if (service == null || service.speechHandler == null) return;
        service.speechHandler.post(() -> {
            service.guideStepTimer.clear();
            service.automationGuard.interrupt();
            service.staleGuideDrops = service.freshObservationRetries = 0;
            if (new com.click.data.local.TouchAutomationSettings(service).isEnabled()
                    && NavigationRecovery.isHomeScreen(LatestScreenSnapshot.get())) {
                service.homeSearchRestart = true;
                // Changing auto mode does not refund the active task's search budget.
            }
            service.execution.requestObservation();
            ClickAccessibilityService.requestFreshSnapshot();
        });
    }

    private boolean handleNavigationSnapshot(ScreenSnapshot screen) {
        if (screen == null || execution.sessionId() == null || !hasConfirmedGoal() || execution.paused()
                || resultPrompt != null || followUp != null) return false;
        if (lastSubmittedScreen != null && !java.util.Objects.equals(screen.packageName, lastSubmittedScreen.packageName))
            guideStepTimer.clear();
        NavigationRecovery.Action before = navigationRecovery.hint();
        NavigationRecovery.Action observed = navigationRecovery.observe(screen);
        if (observed != NavigationRecovery.Action.NONE) {
            execution.retry();
            presentationGate.clear();
            guideStepTimer.clear(); actionTracker.clear(); automationGuard.interrupt();
            hideGuideOverlay(); hideConfirmation(); clearVisionState();
            pendingVerifiedAction = null;
            refreshLoadingStatus();
            pendingActionType = observed == NavigationRecovery.Action.HOME
                    ? AgentActionEvents.HOME_NAVIGATED : AgentActionEvents.BACK_NAVIGATED;
            pendingActionOccurredAtMs = System.currentTimeMillis();
            agentConfirmationId = null; confirmationExpiresAt = 0;
            freshObservationRetries = staleGuideDrops = 0;
            scrollSearchGeneration = -1;
            if (observed == NavigationRecovery.Action.HOME) {
                adjustmentResultCheck.clear(); clearActionEvidenceWait();
                homeSearchRestart = true;
                homeSearchToken++;
                lastTargetBounds = null; lastTargetNodeId = lastGuideCommandId = null;
            }
            execution.requestObservation();
            return false;
        }
        if (navigationRecovery.hint() == NavigationRecovery.Action.BACK && observed == NavigationRecovery.Action.NONE
                && navigationRecovery.awaiting() == NavigationRecovery.Action.NONE)
            navigationRecovery.recommend(screen);
        if (navigationRecovery.hint() == NavigationRecovery.Action.HOME && before == NavigationRecovery.Action.BACK) {
            showSettingsEntryRecovery();
        }
        return navigationRecovery.hint() != NavigationRecovery.Action.NONE;
    }

    private boolean showNavigationRecoveryIfNeeded(AgentTurnContract.Response response, ScreenSnapshot screen) {
        AgentTurnContract.GuideCommand command = response.guideCommand;
        JSONObject action = command.recoveryAction();
        if (action != null && "open_android_settings_or_show_app_drawer".equals(action.optString("type")))
            return false;
        JSONObject recovery = command.renderPolicy == null ? null : command.renderPolicy.optJSONObject("recovery");
        if (screen == null || NavigationRecovery.isHome(screen.packageName) || recovery == null
                || !"user".equals(recovery.optString("waiting_for"))) return false;
        String reason = recovery.optString("reason_code");
        if (!("STATE_UNKNOWN".equals(reason) || "GRAPH_PATH_UNAVAILABLE".equals(reason)
                || "TARGET_NOT_FOUND".equals(reason) || "APP_NOT_OPEN".equals(reason))) return false;
        if (recoverObservedSettingsSubpage(screen)) return true;
        if (ObservationRecovery.canReturnFromSettings(screen)) {
            recoverSettingsBack(screen);
            return true;
        }
        showNavigationRecovery(navigationRecovery.recommend(screen));
        return true;
    }

    private void requestRecoveryNavigation(NavigationRecovery.Action action, ScreenSnapshot before, boolean manual) {
        if (manual) navigationRecovery.requestedByUser(action, before);
        else navigationRecovery.requested(action, before);
        guideStepTimer.clear();
        if (taskFlowView != null) taskFlowView.setLoadingStatus(null);
        final int generation = execution.generation();
        final String requestedEnvironment=environmentWaitKey;
        speechHandler.postDelayed(() -> {
            if (generation != execution.generation() || execution.paused() || navigationRecovery.awaiting() != action) return;
            ScreenSnapshot current = ClickAccessibilityService.freshSnapshot();
            if(requestedEnvironment!=null && handleEnvironment(current)) {
                if(current!=null && requestedEnvironment.equals(environmentWaitKey) && navigationRecovery.awaiting()==action) {
                    // Still outside Settings: retain the existing Settings action rather than
                    // escalating a physical Back press into another recovery button.
                    navigationRecovery.clear();
                }
                return;
            }
            if (!handleNavigationSnapshot(current)) { sendAgentTurn(null, true); return; }
            if (navigationRecovery.finishManualWait()) {
                // No correlated screen change: re-observe without fabricating BACK_NAVIGATED
                // or forcing the user home just because Settings recreated its window.
                execution.requestObservation();
                sendAgentTurn(null, true);
                return;
            }
            if (action == NavigationRecovery.Action.BACK && navigationRecovery.awaiting() == action) {
                navigationRecovery.forceHome();
                showSettingsEntryRecovery();
            } else if (action == NavigationRecovery.Action.HOME && navigationRecovery.awaiting() == action) {
                navigationRecovery.forceHome();
                showSettingsEntryRecovery();
                automationNeedsHelp("홈 화면을 아직 확인하지 못했어요. 휴대폰의 홈 버튼이나 제스처를 사용해 주세요.");
            }
        }, 2500L);
    }

    private void showNavigationRecovery(NavigationRecovery.Action action) {
        guideStepTimer.clear(); actionTracker.clear();
        hideGuideOverlay(); hideConfirmation();
        ensureTaskFlow();
        taskFlowCollapsed = false;
        if (overlayView != null) overlayView.collapse();
        updateFloatingVisibility();
        taskFlowView.setLoadingStatus(null);
        boolean back = action == NavigationRecovery.Action.BACK;
        String message = back ? "직전 작업 화면으로 돌아가 볼게요. 뒤로가기를 한 번 눌러주세요. 돌아온 화면을 확인한 뒤 이어서 안내할게요."
                : navigationRecovery.backAttempted()
                    ? "뒤로가기로 원래 화면을 확인하지 못했어요. 홈 버튼을 눌러주세요. 목표는 유지하고 처음부터 다시 안내할게요."
                    : "한 번의 뒤로가기로 돌아갈 근거가 부족해요. 홈 버튼을 눌러주세요. 목표는 유지하고 처음부터 다시 안내할게요.";
        final int navigationGeneration = execution.generation();
        final ScreenSnapshot recoveryScreen = ClickAccessibilityService.freshSnapshot();
        taskFlowView.navigationRecovery(back, message, () -> {
            if (navigationGeneration != execution.generation() || execution.paused() || navigationRecovery.hint() != action
                    || navigationRecovery.awaiting() != NavigationRecovery.Action.NONE) return;
            ScreenSnapshot current = ClickAccessibilityService.freshSnapshot();
            if (current == null || !java.util.Objects.equals(NavigationRecovery.key(recoveryScreen), NavigationRecovery.key(current))) {
                if (!handleNavigationSnapshot(current)) {
                    execution.requestObservation(); ClickAccessibilityService.requestFreshSnapshot();
                } else showNavigationRecovery(navigationRecovery.hint());
                return;
            }
            requestRecoveryNavigation(action, current, false);
            if (!ClickAccessibilityService.performRecoveryNavigation(back)) {
                automationNeedsHelp("이동하지 못했어요. 휴대폰의 뒤로가기·홈 버튼이나 제스처를 사용해 주세요.");
            }
        });
        placeTaskFlow(null, false);
        showGuideOverlay(ClickAccessibilityService.navigationButtonBounds(back), "tap", 0);
        if (overlayView != null) overlayView.setRecognizedSpeech(message);
        speak(message);
    }

    private boolean tryAutomaticAdjustmentApproval(AgentTurnContract.GuideCommand command, JSONObject metadata,
                                                 ScreenSnapshot screen) {
        if (!automaticTouchEnabled() || agentGateway.currentContract()==null || !agentGateway.currentContract().approvalProvenance
                || !adjustmentDelegation.permits(execution.acceptedGoal()==null?null:execution.acceptedGoal().optString("goal_type"),System.currentTimeMillis())
                || execution.paused() || execution.pendingControl() != null || !hasConfirmedGoal()
                || navigationRecovery.hint() != NavigationRecovery.Action.NONE
                || !isAdjustmentConfirmation(command)
                || !SettingsAdjustment.matches(execution.acceptedGoal(), metadata == null ? null : metadata.optJSONObject("typed_goal"))
                || agentConfirmationId == null || agentConfirmationId.equals(lastAutomaticConfirmationId)
                || System.currentTimeMillis() >= confirmationExpiresAt * 1000L
                || !AutomaticAdjustmentApproval.eligible(command, metadata, screen)) return false;
        lastAutomaticConfirmationId = agentConfirmationId;
        automaticAdjustmentApprovalPending = true;
        hideGuideOverlay(); hideConfirmation(); guideStepTimer.clear();
        String message = "요청하신 방향으로 조절할게요.";
        taskFlowView.waitingProgress(message);
        stopNarration();
        panelInterruptedNarration = false;
        lastSpokenMessage = TaskFlowOverlayView.AUTOMATIC_PROGRESS_MESSAGE;
        taskFlowView.setAutomaticProgress(automaticTouchEnabled());
        updateFloatingVisibility();
        final int generation = execution.generation();
        final String approval = agentConfirmationId;
        speechHandler.postDelayed(() -> {
            if (generation != execution.generation() || execution.paused() || execution.pendingControl() != null || execution.busy()
                    || !approval.equals(agentConfirmationId)) {
                automaticAdjustmentApprovalPending = false;
                return;
            }
            if(!automaticTouchEnabled()) {
                adjustmentDelegation.clear();automaticAdjustmentApprovalPending=false;
                execution.requestObservation();ClickAccessibilityService.requestFreshSnapshot();return;
            }
            ScreenSnapshot current = ClickAccessibilityService.freshSnapshot();
            if (!com.click.service.accessibility.TouchAutomationPolicy.sameTarget(screen, current,
                    command.targetNodeId, command.targetBounds)) {
                automaticAdjustmentApprovalPending = false;
                waitForFreshGuide(); return;
            }
            automaticAdjustmentApprovalPending = false;
            try {pendingApprovalProvenance=adjustmentDelegation.consume(execution.acceptedGoal().optString("goal_type"),System.currentTimeMillis());}
            catch(org.json.JSONException invalid){return;}
            if(pendingApprovalProvenance==null)return;
            sendAgentTurn("accepted", true, screen);
        }, 60L);
        return true;
    }

    private void recoverSettingsBack(ScreenSnapshot expected) {
        if (!ObservationRecovery.canReturnFromSettings(expected)) { waitForFreshGuide(); return; }
        execution.retry();
        showNavigationRecovery(navigationRecovery.recommendSettingsBack());
    }

    private void automationStatus(String message) {
        if (taskFlowView != null) taskFlowView.setAutomationStatus(message);
        if (overlayView != null && message != null) overlayView.setRecognizedSpeech(message);
    }

    private void automationNeedsHelp(String message) {
        if (taskFlowView == null || waitingForTargetHelp()) return;
        taskFlowView.actionRequired(message);
        taskFlowCollapsed = false;
        updateFloatingVisibility();
        placeTaskFlow(lastTargetBounds, true);
    }

    private void executeAutomaticAction(AgentTurnContract.GuideCommand command, JSONObject metadata,
                                        ScreenSnapshot screen, String stateId) {
        if (screen != null) automationGuard.observe(screen.contentFingerprint());
        if (!execution.canAct() || !execution.matchesGuide(command,lastSubmittedScreen) || navigationRecovery.hint() != NavigationRecovery.Action.NONE
                || !command.requiresUserAction
                || command.guideCommandId == null || screen == null
                || !new com.click.data.local.TouchAutomationSettings(this).isEnabled()
                || command.renderPolicy != null && command.renderPolicy.optBoolean("vision_derived")) return;
        if (!com.click.data.snapshot.SettingsCapabilities.compatible(metadata==null?null:metadata.optJSONObject("capability_contract"))) {
            automationNeedsHelp("이 단계는 표시된 항목을 직접 눌러주세요.");return;
        }
        boolean tap = "tap".equals(command.actionType);
        int direction = GuideDragDirection.resolve(command.renderPolicy, metadata);
        JSONObject typedGoal = metadata == null ? null : metadata.optJSONObject("typed_goal");
        boolean slider = "drag".equals(command.actionType) && SettingsAdjustment.matches(execution.acceptedGoal(), typedGoal)
                && direction != GuideDragDirection.UNKNOWN;
        if (!tap && !slider) return;
        if (tap && com.click.service.accessibility.TouchAutomationPolicy.clickableTarget(screen,
                command.targetNodeId, command.targetBounds,
                execution.acceptedGoal()==null?null:execution.acceptedGoal().optString("goal_type"),stateId) == null) {
            automationNeedsHelp("이 항목은 자동으로 누를 수 없어요. 표시된 곳을 직접 눌러주세요.");
            return;
        }
        AccessibilityNodeSnapshot operationTarget = screen.nodes.stream()
                .filter(n -> n.nodeId.equals(command.targetNodeId)).findFirst().orElse(null);
        String operation = command.actionType + ":" + direction + ":" + screen.packageName + ":" + screen.activeWindowId
                + ":" + java.util.Arrays.toString(command.targetBounds)
                + (operationTarget == null ? "" : ":" + operationTarget.resourceId + ":" + operationTarget.progress);
        AutomationRunGuard.Decision decision = automationGuard.begin(execution.sessionId(), execution.episodeId(),
                command.guideCommandId, screen.contentFingerprint(), operation);
        if (decision != AutomationRunGuard.Decision.ALLOW) {
            if (decision == AutomationRunGuard.Decision.DUPLICATE)
                automationNeedsHelp("이미 시도한 단계예요. 반복해서 누르지 않을게요. 필요하면 직접 조작해 주세요.");
            if (decision == AutomationRunGuard.Decision.WAITING_FOR_OBSERVATION)
                automationStatus("이전 조작의 화면 변화를 확인하고 있어요…");
            if (decision == AutomationRunGuard.Decision.LIMIT_REACHED)
                automationNeedsHelp("자동 조작 횟수에 도달했어요. 화면을 확인하고 직접 진행해 주세요.");
            return;
        }
        if(deviceJournal==null) {automationNeedsHelp("조작 기록을 준비하지 못했어요. 직접 진행해 주세요.");return;}
        try {
            if(!deviceJournal.begin(execution.sessionId(),execution.episodeId(),command.guideCommandId,operation,
                    command.renderPolicy==null?null:command.renderPolicy.optString("attempt_id",null),
                    lastSubmittedScreen==null?null:lastSubmittedScreen.observationId,screen.observationId)) {
                execution.recovery(TaskExecutionController.RecoveryCause.RESULT_UNKNOWN);
                hideGuideOverlay();hideConfirmation();automationNeedsHelp("이전 조작 결과가 아직 확인되지 않았어요. 같은 조작을 다시 실행하지 않을게요.");return;
            }
        }catch(java.io.IOException failure){automationNeedsHelp("조작 기록을 저장하지 못했어요. 직접 진행해 주세요.");return;}
        guideStepTimer.clear(); // Machine actions are never human response-time samples.
        execution.actionStarted();
        JSONObject approval=metadata==null?null:metadata.optJSONObject("approval_provenance");
        float fraction=approval!=null && "delegated_goal".equals(approval.optString("source"))?0.2f:1f;
        boolean performed = tap
                ? (com.click.service.accessibility.TouchAutomationPolicy.clickableTarget(screen,command.targetNodeId,command.targetBounds)!=null
                    ? ClickAccessibilityService.performGuidedTap(screen,command.targetNodeId,command.targetBounds)
                    : ClickAccessibilityService.performGuidedNavigationTap(screen,command.targetNodeId,command.targetBounds,
                        execution.acceptedGoal().optString("goal_type"),stateId))
                : fraction>=1f ? ClickAccessibilityService.performGuidedSlider(screen, command.targetNodeId, command.targetBounds, direction,
                        SettingsAdjustment.from(execution.acceptedGoal()).setting)
                : ClickAccessibilityService.performGuidedSlider(screen,command.targetNodeId,command.targetBounds,direction,
                        SettingsAdjustment.from(execution.acceptedGoal()).setting,fraction);
        execution.actionDispatched(performed);
        if (!performed) {
            automationGuard.finish(command.guideCommandId);
            automationNeedsHelp("자동 조작을 실행하지 못했어요. 표시된 곳을 직접 조작해 주세요.");
            return;
        }
        hideGuideOverlay();
        automationStatus(tap ? "버튼을 눌렀어요. 화면 변화를 확인하고 있어요…"
                : "조절한 결과를 확인하고 있어요…");
        final int generation = execution.generation();
        for (long delay : new long[]{1800L, 5500L}) {
            speechHandler.postDelayed(() -> {
                if (generation != execution.generation() || execution.paused() || execution.pendingControl() != null
                        || !automationGuard.awaiting(command.guideCommandId)) return;
                ScreenSnapshot after = ClickAccessibilityService.freshSnapshot();
                if (after != null && !after.nodes.isEmpty()) automationGuard.observe(after.contentFingerprint());
                if (!automationGuard.awaiting(command.guideCommandId)) {
                    execution.requestObservation();
                    ClickAccessibilityService.requestFreshSnapshot();
                } else if (delay == 5500L && !execution.busy()) {
                    automationGuard.finish(command.guideCommandId);
                    automationNeedsHelp("화면 변화를 확인하지 못했어요. 다시 누르지 않고 기다릴게요. 직접 조작해 주세요.");
                    if (after != null && screen.fingerprint().equals(after.fingerprint())
                            && java.util.Objects.equals(lastGuideCommandId, command.guideCommandId))
                        showGuideOverlay(command.targetBounds, command.actionType, direction);
                }
            }, delay);
        }
    }

    private void waitForFreshGuide() {
        if (waitingForTargetHelp()) return;
        // A redraw can precede a manual or automatic range event. Keep its exact
        // binding until the next validated guide or explicit cancel/navigation.
        if (!automationGuard.awaiting(lastGuideCommandId) && !actionTracker.hasSliderBinding()) actionTracker.clear();
        guideStepTimer.clear();
        lastGuideCommandId = lastTargetNodeId = null;
        lastTargetBounds = null;
        agentConfirmationId = null;
        confirmationExpiresAt = 0;
        clearVisionState();
        hideGuideOverlay();
        hideConfirmation();
        stopNarration();
        if (++staleGuideDrops >= 4) {
            if (requestChangedAdjustmentResult(ClickAccessibilityService.freshSnapshot())) return;
            execution.clearObservation();
            final int generation = execution.generation();
            String message = "조절 결과를 아직 확인하지 못했어요. 현재 화면을 다시 확인해 주세요.";
            showObservationRecovery(message, () -> retryObservation(generation, false));
            String guidance = taskFlowView.guidanceMessage();
            lastSpokenMessage = guidance;
            placeTaskFlow(null, true);
            if (overlayView != null) overlayView.setRecognizedSpeech(guidance);
            speak(guidance);
            return;
        }
        String message = "안내를 준비하고 있어요.";
        lastSpokenMessage = message;
        taskFlowView.waitingProgress(message);
        taskFlowView.setAutomaticProgress(automaticTouchEnabled());
        updateFloatingVisibility();
        placeTaskFlow(null, false);
        if (overlayView != null) overlayView.setRecognizedSpeech(message);
        execution.requestObservation();
        ClickAccessibilityService.requestFreshSnapshot();
    }

    private void handleVisionFallback(AgentTurnContract.GuideCommand command, JSONObject visionFallback) {
        // A blocked/no-signal response is terminal; it must never trigger a capture loop.
        if (!"confirmation".equals(command.commandType)
                || !visionFallback.optBoolean("requires_user_consent", true)) {
            finishActiveQueueRequest("AGENT_ERROR");
            return;
        }
        if (visionConsentRequested || visionCaptureInFlight) return;
        if (agentConfirmationId == null || lastSubmittedScreen == null) return;
        visionConsentRequested = true;
        showVisionConsent(command.message, visionFallback);
    }

    private void handleFreshObservationRequest(AgentTurnContract.GuideCommand command, String stateId) {
        ScreenSnapshot snapshot = lastSubmittedScreen;
        String recovery = com.click.service.overlay.ObservationRecovery.message(stateId,
                command.fallbackAction(), snapshot);
        if (recovery != null && startAutomaticSettingsSearch(stateId, snapshot)) return;
        if (recovery != null || ++freshObservationRetries > 4) {
            if (requestChangedAdjustmentResult(ClickAccessibilityService.freshSnapshot())) return;
            if (ObservationRecovery.targetLabel(stateId,
                    execution.acceptedGoal() == null ? null : execution.acceptedGoal().optString("goal_type")) != null) {
                showTargetHelp(stateId);
                return;
            }
            final int generation = execution.generation();
            String message = recovery != null ? recovery
                    : "현재 화면에서 다음 항목을 찾지 못했어요. 화면을 확인한 뒤 ‘다시 시도하기’를 눌러주세요.";
            showObservationRecovery(message, () -> retryObservation(generation, true));
            // Use the same concise guidance for the caption, replay, and initial speech.
            placeTaskFlow(null, true);
            String guidance = taskFlowView.guidanceMessage();
            if (overlayView != null) overlayView.setRecognizedSpeech(guidance);
            speak(guidance);
            return;
        }
        pendingActionOccurredAtMs = System.currentTimeMillis();
        pendingActionType = AgentActionEvents.OBSERVE_ONLY;
        final int generation = execution.generation();
        speechHandler.postDelayed(() -> {
            if (generation != execution.generation() || execution.paused() || execution.pendingControl() != null
                    || !java.util.Objects.equals(command.guideCommandId, lastGuideCommandId)) return;
            execution.requestObservation();
            ClickAccessibilityService.requestFreshSnapshot();
        }, 700L);
    }

    private void showTargetHelp(String stateId) {
        if (waitingForTargetHelp()) return;
        ensureTaskFlow();
        if (assistedRecoveryGeneration == execution.generation()) {
            final int generation = execution.generation();
            showRecoveryApology(assistedRetry(() -> retryObservation(generation, true)));
            placeTaskFlow(null, true);
            speak(taskFlowView.guidanceMessage());
            return;
        }
        String authoredTarget = ObservationRecovery.targetLabel(stateId,
                execution.acceptedGoal() == null ? null : execution.acceptedGoal().optString("goal_type"));
        final String target = authoredTarget == null ? "원하시는 항목" : authoredTarget;
        final int generation = execution.generation();
        final String session = execution.sessionId();
        final String guideId = lastGuideCommandId;
        targetHelpGeneration = generation;
        targetHelpScreen = ClickAccessibilityService.freshSnapshot();
        if (targetHelpScreen == null) targetHelpScreen = lastSubmittedScreen;
        clearMissingScreenWait();
        execution.clearObservation();
        staleGuideDrops = freshObservationRetries = 0;
        hideGuideOverlay(); hideConfirmation();
        guideStepTimer.clear(); actionTracker.clear();
        taskFlowView.targetHelp(target, () -> {
            if (generation != execution.generation() || !waitingForTargetHelp() || closing || execution.paused() || execution.pendingControl() != null
                    || !java.util.Objects.equals(session, execution.sessionId())
                    || !java.util.Objects.equals(guideId, lastGuideCommandId)
                    || navigationRecovery.awaiting() != NavigationRecovery.Action.NONE) return;
            if (recoveryApologyGeneration == generation) return;
            assistedRecoveryGeneration = generation;
            showRecoveryApology(assistedRetry(() -> retryObservation(generation, true)));
            placeTaskFlow(null, true);
            speak(taskFlowView.guidanceMessage());
        });
        collapseTaskFlow();
        if (overlayView != null) overlayView.setRecognizedSpeech("도움이 필요해요!");
        speak("도움이 필요해요!");
    }

    private boolean waitingForTargetHelp() {
        return targetHelpGeneration == execution.generation() && taskFlowView != null;
    }

    private void clearTargetHelpWait() {
        targetHelpGeneration = -1;
        recoveryApologyGeneration = -1;
        targetHelpScreen = null;
        appIconGuide = false;
        coupangNativeGuide = null;
    }

    private boolean handleEnvironment(ScreenSnapshot current) {
        if(closing || execution.sessionId()==null || !hasConfirmedGoal() || execution.acceptedGoal()==null
                || execution.paused() || execution.manual() || execution.pendingControl()!=null || resultPrompt!=null || followUp!=null)return false;
        android.app.KeyguardManager keyguard=getSystemService(android.app.KeyguardManager.class);
        var scene=com.click.data.snapshot.ScreenEnvironment.classify(current,
                ClickAccessibilityService.accessibilityWindowManager()!=null,
                keyguard!=null && keyguard.isDeviceLocked(),getPackageName());
        var kind=scene.kind();
        if(kind==com.click.data.snapshot.ScreenEnvironment.Kind.SELF)return false;
        if(kind==com.click.data.snapshot.ScreenEnvironment.Kind.UNOBSERVABLE)return environmentWait!=null;
        if((kind==com.click.data.snapshot.ScreenEnvironment.Kind.OTHER_APP
                || kind==com.click.data.snapshot.ScreenEnvironment.Kind.HOME
                || kind==com.click.data.snapshot.ScreenEnvironment.Kind.APP_DRAWER
                || kind==com.click.data.snapshot.ScreenEnvironment.Kind.LAUNCHER)
                && "coupang".equals(execution.acceptedGoal().optString("target_app"))
                && !"com.coupang.mobile".equals(current.packageName)) {
            showCoupangEntryRecovery();return true;
        }
        // A transient redraw during a real value change retains its original evidence.
        boolean valuePending=pendingVerifiedAction!=null && (AgentActionEvents.DRAG_COMPLETED.equals(pendingVerifiedAction.optString("action_type")) || actionTracker.hasSliderBinding());
        JSONObject activeSlots=execution.acceptedGoal().optJSONObject("slots");
        String activeSetting=activeSlots==null?null:activeSlots.optString("setting",null);
        if(kind==com.click.data.snapshot.ScreenEnvironment.Kind.OTHER_APP && execution.started() && execution.episodeId()!=null
                && com.click.data.snapshot.ScreenEnvironmentPolicy.manualApp(activeSetting,current.packageName)
                && manualSettingsSpec()!=null) {
            showExpectedExternalChoice(current,manualSettingsSpec());return true;
        }
        boolean offRoute=false;
        if(kind==com.click.data.snapshot.ScreenEnvironment.Kind.CONTROL_SURFACE){
            var surface=com.click.data.snapshot.NativeControlSurface.identify(current);
            offRoute=surface==null || !java.util.Objects.equals(activeSetting,surface.setting());
        }
        if(kind==com.click.data.snapshot.ScreenEnvironment.Kind.SETTINGS_SUBPAGE) {
            var pages=pageNavigation();JSONObject slots=execution.acceptedGoal().optJSONObject("slots");
            offRoute=pages!=null && pages.offRoute(current,slots==null?null:slots.optString("setting",null));
        }
        if(valuePending && scene.navigable() && !offRoute)return false;
        boolean expectedDialog=false;
        if(kind==com.click.data.snapshot.ScreenEnvironment.Kind.DIALOG){
            var pages=pageNavigation();JSONObject slots=execution.acceptedGoal().optJSONObject("slots");
            String title=com.click.data.snapshot.ScreenIdentity.pageTitle(current);
            expectedDialog=pages!=null && title!=null && !com.click.data.snapshot.ScreenEnvironmentPolicy.contains("settings_home_titles",title)
                    && pages.onRoute(current,slots==null?null:slots.optString("setting",null));
        }
        boolean expectedCommerce=kind==com.click.data.snapshot.ScreenEnvironment.Kind.OTHER_APP
                && "coupang".equals(execution.acceptedGoal().optString("target_app"))
                && "com.coupang.mobile".equals(current.packageName);
        boolean blocked=(!scene.navigable() && !expectedDialog && !expectedCommerce) || offRoute;
        if(!blocked) {
            lastNavigableEnvironment=current;
            if(environmentWait==null)return false;
            if(!execution.environmentReady())return true;
            environmentWait=null;environmentWaitKey=null;
            clearTargetHelpWait();clearGuideResyncRetry();clearMissingScreenWait();
            presentationGate.clear();navigationRecovery.clear();stopNarration();hideGuideOverlay();hideConfirmation();
            agentConfirmationId=null;confirmationExpiresAt=0;automaticAdjustmentApprovalPending=false;
            observedPageScreen=current;staleGuideDrops=freshObservationRetries=0;assistedRecoveryGeneration=-1;
            if(execution.started() && pendingVerifiedAction==null && !AgentActionEvents.BACK_NAVIGATED.equals(pendingActionType) && !AgentActionEvents.HOME_NAVIGATED.equals(pendingActionType)) {
                pendingActionType=AgentActionEvents.MANUAL_OVERRIDE;pendingActionOccurredAtMs=System.currentTimeMillis();
            }
            if(execution.busy())pendingPageScreen=current;
            ensureTaskFlow();taskFlowView.waitingProgress("현재 화면에서 이어서 안내할게요.");
            updateFloatingVisibility();placeTaskFlow(null,false);
            execution.requestObservation();
            if(execution.resumeAfterEnvironment()){
                execution.consumeEnvironmentResume();sendAgentControl("resume_task");return true;
            }
            return false;
        }
        String reason=offRoute?(kind==com.click.data.snapshot.ScreenEnvironment.Kind.CONTROL_SURFACE?"SYSTEM_PANEL_VISIBLE":"SETTINGS_OFF_PATH"):scene.reason();
        String key=reason+"|"+(current==null?"":current.packageName)+"|"+(current==null?"":current.activeWindowId);
        if(offRoute)key+="|"+com.click.data.snapshot.ScreenIdentity.pageTitle(current);
        if(key.equals(environmentWaitKey))return true;
        if(!execution.environmentBlocked())return true;
        environmentWait=scene;environmentWaitKey=key;
        if(execution.busy())pendingPageScreen=current!=null?current:observedPageScreen;
        clearTargetHelpWait();clearGuideResyncRetry();clearMissingScreenWait();
        stopNarration();guideStepTimer.clear();actionTracker.clear();automationGuard.interrupt();
        clearVisionState();hideGuideOverlay();hideConfirmation();
        agentConfirmationId=null;confirmationExpiresAt=0;automaticAdjustmentApprovalPending=false;
        pendingVerifiedAction=null;pendingActionType=null;lastGuideCommandId=lastTargetNodeId=null;lastTargetBounds=null;
        scrollSearchGeneration=-1;homeSearchToken++;
        ensureTaskFlow();
        JSONObject rule=com.click.data.snapshot.ScreenEnvironmentPolicy.recovery(reason);
        String message=rule.optString("message","현재 화면에서 직접 진행해 주세요.");
        switch(kind) {
            case DISCONNECTED:
                taskFlowView.accessibilityRecovery(message,this::openAccessibilityGuide,()->{environmentWaitKey=null;sendAgentTurn(null,true);});break;
            case LOCKED:
                taskFlowView.failureRecovery("잠금을 풀어주세요",message,null,null);break;
            case SYSTEM_DIALOG:
            case SENSITIVE:
                taskFlowView.failureRecovery("직접 진행해 주세요",message,null,null);break;
            case RECENTS:
                navigationRecovery.forceHome();showSettingsEntryRecovery();return true;
            case OTHER_APP:
                ScreenSnapshot anchor=lastNavigableEnvironment!=null?lastNavigableEnvironment:lastSubmittedScreen;
                if(anchor==null){showSettingsEntryRecovery();return true;}
                navigationRecovery.offPathPage(anchor);showSettingsEntryRecovery();return true;
            default:
                navigationRecovery.offPathPage(lastNavigableEnvironment!=null?lastNavigableEnvironment:lastSubmittedScreen);
                showSettingsEntryRecovery();return true;
        }
        taskFlowCollapsed=false;updateFloatingVisibility();placeTaskFlow(null,true);speak(message);return true;
    }

    private void showExpectedExternalChoice(ScreenSnapshot current,JSONObject spec) {
        String key="manual:"+current.packageName+":"+current.activeWindowId;
        if(key.equals(environmentWaitKey))return;
        environmentWaitKey=key;environmentWait=null;execution.environmentReady();
        clearTargetHelpWait();clearGuideResyncRetry();clearMissingScreenWait();
        stopNarration();hideGuideOverlay();hideConfirmation();clearVisionState();
        actionTracker.clear();guideStepTimer.clear();automationGuard.interrupt();
        agentConfirmationId=null;confirmationExpiresAt=0;pendingVerifiedAction=null;pendingActionType=null;
        String message=spec.optString("instruction")+" 직접 선택한 뒤 완료 확인을 눌러주세요.";
        ensureTaskFlow();
        try {
            taskFlowView.progress(requirementReview!=null?requirementReview.summary():"선택한 작업",null,message,
                    agentGateway.currentContract()!=null && agentGateway.currentContract().supports("request_result"));
            taskFlowView.setAutomaticProgress(false);taskFlowCollapsed=false;updateFloatingVisibility();placeTaskFlow(null,false);
            speak(message);execution.manualPresented();
        } catch(RuntimeException error) {
            execution.recovery();
            final int generation=execution.generation();final String session=execution.sessionId(),episode=execution.episodeId();
            taskFlowView.failureRecovery("직접 진행해 주세요",message,"안내 다시 보기",()->{
                if(generation!=execution.generation() || closing || execution.paused() || execution.busy() || execution.pendingControl()!=null
                        || !java.util.Objects.equals(session,execution.sessionId()) || !java.util.Objects.equals(episode,execution.episodeId()))return;
                environmentWaitKey=null;handleEnvironment(ClickAccessibilityService.freshSnapshot());
            });
            placeTaskFlow(null,true);
        }
    }

    private void showTypedRecovery(String reason,Runnable retry) {
        JSONObject rule=com.click.data.snapshot.ScreenEnvironmentPolicy.recovery(reason);
        if(rule.length()==0){showObservationRecovery(initialGoal,"",retry);return;}
        String action=rule.optString("action");
        String label="VERIFY".equals(action)?"결과 확인":"RECONCILE".equals(action)?"연결 다시 확인":"다시 안내받기";
        final int generation=execution.generation();final String session=execution.sessionId(),episode=execution.episodeId();
        Runnable next=()->{
            if(generation!=execution.generation() || closing || execution.paused() || execution.busy() || execution.pendingControl()!=null
                    || !java.util.Objects.equals(session,execution.sessionId()) || !java.util.Objects.equals(episode,execution.episodeId()))return;
            if("VERIFY".equals(action) && agentGateway.currentContract()!=null && agentGateway.currentContract().supports("request_result")) {
                sendFlowRequest(AgentTurnContract.Request.control(execution.sessionId(),execution.sessionVersion(),execution.episodeId(),"request_result",null));
            } else if("RECONCILE".equals(action)) {
                execution.retry();pendingActionType=AgentActionEvents.MANUAL_OVERRIDE;pendingActionOccurredAtMs=System.currentTimeMillis();restoreQueuedSession(true);
            } else retry.run();
        };
        taskFlowView.failureRecovery("안내를 이어갈게요",rule.optString("message"),label,next);
    }

    private SettingsPageNavigation pageNavigation() {
        if(settingsPageNavigation==null) {
            try(java.io.InputStream in=getAssets().open("vision_vocabulary.json")) {
                settingsPageNavigation=new SettingsPageNavigation(com.click.data.snapshot.VisionPrivacy.load(in));
            } catch(Exception unavailable){return null;}
        }
        return settingsPageNavigation;
    }

    /** A new native heading can retire a stale guide/recovery, never a pause or result question. */
    private boolean handleSettingsPageChange(ScreenSnapshot current) {
        if(closing || execution.sessionId()==null || !hasConfirmedGoal() || execution.acceptedGoal()==null
                || execution.paused() || execution.manual() || execution.pendingControl()!=null || resultPrompt!=null || followUp!=null)return false;
        if(pendingVerifiedAction!=null && (AgentActionEvents.DRAG_COMPLETED.equals(pendingVerifiedAction.optString("action_type"))
                || actionTracker.hasSliderBinding()))return false;
        SettingsPageNavigation pages=pageNavigation();
        if(pages==null)return false;
        JSONObject slots=execution.acceptedGoal().optJSONObject("slots");
        String setting=slots==null?null:slots.optString("setting",null);
        ScreenSnapshot before=observedPageScreen!=null?observedPageScreen:lastSubmittedScreen;
        SettingsPageNavigation.Change change=pages.changed(before,current,setting);
        if(change==SettingsPageNavigation.Change.NONE || !execution.pageChanged())return false;
        JSONObject navigationEvent=change==SettingsPageNavigation.Change.ON_ROUTE && pendingVerifiedAction!=null
                && AgentActionEvents.TAP_COMPLETED.equals(pendingVerifiedAction.optString("action_type"))
                    ? pendingVerifiedAction : null;
        observedPageScreen=current;
        clearTargetHelpWait();clearGuideResyncRetry();clearMissingScreenWait();
        presentationGate.clear();stopNarration();guideStepTimer.clear();actionTracker.clear();
        automationGuard.interrupt();clearVisionState();adjustmentResultCheck.clear();clearActionEvidenceWait();
        automaticAdjustmentApprovalPending=false;
        agentConfirmationId=null;confirmationExpiresAt=0;
        lastGuideCommandId=lastTargetNodeId=null;lastTargetBounds=null;
        staleGuideDrops=freshObservationRetries=0;assistedRecoveryGeneration=-1;
        scrollSearchGeneration=-1;homeSearchToken++;
        pendingVerifiedAction=navigationEvent;
        if(navigationEvent==null && !AgentActionEvents.BACK_NAVIGATED.equals(pendingActionType) && !AgentActionEvents.HOME_NAVIGATED.equals(pendingActionType)) {
            pendingActionType=AgentActionEvents.MANUAL_OVERRIDE;
            pendingActionOccurredAtMs=System.currentTimeMillis();
        }
        hideGuideOverlay();hideConfirmation();lastNarratedGuideKey=null;
        // Do not discard the old response's authoritative session version. Its callback will
        // suppress the stale guide and send the coalesced latest page once it has acknowledged it.
        if(execution.busy())pendingPageScreen=current;
        ensureTaskFlow();
        if(change==SettingsPageNavigation.Change.OFF_ROUTE) {
            execution.recovery();execution.clearObservation();
            navigationRecovery.offPathPage(before);
            showSettingsEntryRecovery();
            return true;
        }
        navigationRecovery.clear();
        taskFlowView.waitingProgress("현재 화면에서 이어서 안내할게요.");
        taskFlowView.setAutomaticProgress(automaticTouchEnabled());
        updateFloatingVisibility();placeTaskFlow(null,false);
        if(execution.busy())return true;
        if(pendingPageScreen!=null){pendingPageScreen=current;synchronizePageNavigation();return true;}
        sendAgentTurn(null,true);
        return true;
    }

    private void synchronizePageNavigation() {
        if(execution.busy() || execution.paused() || execution.sessionId()==null)return;
        final int generation=execution.generation();
        synchronizeTracked(execution.sessionId(),new AgentTurnGateway.Callback(){
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if(generation!=execution.generation() || !java.util.Objects.equals(execution.sessionId(),response.sessionId))return;

                if(execution.pendingControl()!=null){String command=execution.pendingControl();execution.clearControl();sendAgentControl(command);return;}
                if(!continueAfterPageChange(response))applyTaskFlowResponse(response);
            }
            @Override public void onFailure(Throwable error) {
                if(generation!=execution.generation())return;
                if(execution.pendingControl()!=null){String command=execution.pendingControl();execution.clearControl();recoverControl(command);return;}
                if(execution.paused() || execution.retryRequired())return;
                execution.recovery();
                showObservationRecovery("연결을 확인한 뒤 이어서 안내할게요.",()->{
                    execution.retry();synchronizePageNavigation();
                });
                placeTaskFlow(null,true);
            }
        });
    }

    private boolean continueAfterPageChange(AgentTurnContract.Response response) {
        if(pendingPageScreen==null)return false;
        pendingPageScreen=null;
        if(execution.paused() || execution.pendingControl()!=null)return true;
        // Authoritative outcome and terminal states still apply; obsolete active guides are discarded.
        if(java.util.Set.of("WAITING_RESULT","COMPLETED","CANCELLED","EXPIRED","BLOCKED").contains(response.taskStatus))return false;
        if(execution.retryRequired())return true; // A local Back prompt already explains the off-route page.
        execution.requestObservation();sendAgentTurn(null,true);return true;
    }

    private boolean preserveTargetHelp(ScreenSnapshot current) {
        if (!waitingForTargetHelp()) return false;
        if (execution.retryRequired()) return true;
        if (closing || execution.paused() || execution.pendingControl() != null) return true;
        if (recoveryApologyGeneration == execution.generation()) return true;
        // Ignore overlay redraws and incomplete reads, but keep observing the app
        // underneath even while the help card is expanded.
        if (current == null || current.nodes.isEmpty()
                || getPackageName().equals(current.packageName)) {
            if(appIconGuide || coupangNativeGuide!=null)hideGuideOverlay();
            return true;
        }
        if (targetHelpScreen == null) { targetHelpScreen = current; return true; }
        boolean wasAppIcon=appIconGuide;
        boolean wasCoupangNative=coupangNativeGuide!=null;
        if(wasCoupangNative) {
            var visible=com.click.data.snapshot.CoupangScreenGuide.identify(current);
            if(coupangNativeGuide.sameStep(visible)) {
                if(visible.target!=null) {
                    showGuideOverlay(visible.target.bounds,"tap",0);nativeGuideScreen=current;
                }
                return true;
            }
            hideGuideOverlay();
        } else if(wasAppIcon) {
            AccessibilityNodeSnapshot previous=appIconTarget(targetHelpScreen),visible=appIconTarget(current);
            if(previous!=null && visible!=null && java.util.Objects.equals(targetHelpScreen.activeWindowId,current.activeWindowId)
                    && previous.nodeId.equals(visible.nodeId) && java.util.Arrays.equals(previous.bounds,visible.bounds)) {
                showGuideOverlay(visible.bounds,"tap",0);nativeGuideScreen=current;
                return true;
            }
            hideGuideOverlay();
        } else if (targetHelpScreen.contentFingerprint().equals(current.contentFingerprint())) return true;
        // A real screen change resumes this request and immediately removes the old
        // target message, including while an older server response is still pending.
        if(!wasAppIcon && !wasCoupangNative)assistedRecoveryGeneration = execution.generation();
        clearTargetHelpWait();
        stopNarration();
        panelInterruptedNarration = false;
        lastNarratedGuideKey = null;
        String message = "다음 안내를 준비하고 있어요.";
        lastSpokenMessage = message;
        taskFlowView.waitingProgress(message);
        taskFlowView.setAutomaticProgress(automaticTouchEnabled());
        updateFloatingVisibility();
        placeTaskFlow(null, false);
        if (overlayView != null) overlayView.setRecognizedSpeech(message);
        if (pendingVerifiedAction == null && pendingActionType == null) {
            pendingActionType = AgentActionEvents.MANUAL_OVERRIDE;
            pendingActionOccurredAtMs = System.currentTimeMillis();
        }
        execution.requestObservation();
        return false;
    }

    private void retryObservation(int generation, boolean manualOverride) {
        if (generation != execution.generation() || closing || execution.paused() || execution.busy()
                || execution.sessionId() == null || execution.pendingControl() != null
                || resultPrompt != null || followUp != null
                || scrollSearchGeneration == execution.generation() || !hasConfirmedGoal()) return;
        // An automatic slider confirmation is a version-bound, one-use server challenge.
        // Do not replace its in-flight observation with a manual retry; the accepted
        // confirmation callback will issue the queued fresh observation if it is needed.
        if (automaticAdjustmentApprovalPending) {
            execution.requestObservation();
            return;
        }
        execution.retry();
        presentationGate.clear();
        if (manualOverride) automationGuard.retry();
        staleGuideDrops = freshObservationRetries = 0;
        stopNarration();
        if (manualOverride) {
            pendingActionType = AgentActionEvents.MANUAL_OVERRIDE;
            pendingActionOccurredAtMs = System.currentTimeMillis();
        }
        taskFlowView.waitingProgress("현재 화면을 다시 확인하고 있어요.");
        taskFlowView.setAutomaticProgress(automaticTouchEnabled());
        updateFloatingVisibility();
        placeTaskFlow(null, false);
        execution.requestObservation();
        // The button itself requests a fresh native read; an unchanged screen need not
        // emit another accessibility event for an explicit retry to reach the server.
        sendAgentTurn(null, true);
        if (!execution.busy() && execution.observationRequested()) {
            android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
            String message = ObservationRecovery.missingScreenMessage(
                    ClickAccessibilityService.accessibilityWindowManager() != null,
                    keyguard != null && keyguard.isDeviceLocked());
            showObservationRecovery(message, () -> retryObservation(generation, manualOverride));
            placeTaskFlow(null, true);
        }
    }

    private boolean recordDeviceVerification(AgentTurnContract.Request request,AgentTurnContract.Response response) {
        if(deviceJournal==null || response.metadata==null)return true;
        JSONObject verification=response.metadata.optJSONObject("verification");
        JSONObject result=verification==null?null:verification.optJSONObject("result");
        if(result==null || !"SUCCESS".equals(result.optString("status")))return true;
        try {
            String attempt=result.optString("attempt_id",null);
            deviceJournal.verifiedAttempt(request.sessionId,request.episodeId==null?response.episodeId:request.episodeId,attempt);
            if((attempt==null || attempt.isEmpty()) && request.userActionEvent!=null)
                deviceJournal.verified(request.sessionId,request.userActionEvent.optString("guide_command_id"));
            return true;
        }catch(java.io.IOException failed) {
            execution.recovery(TaskExecutionController.RecoveryCause.RESULT_UNKNOWN);hideGuideOverlay();hideConfirmation();
            automationNeedsHelp("조작 확인 기록을 저장하지 못했어요. 같은 조작을 다시 실행하지 않을게요.");return false;
        }
    }

    private void registerReceiptConnectivity() {
        var manager=getSystemService(android.net.ConnectivityManager.class);if(manager==null)return;
        networkCallback=new android.net.ConnectivityManager.NetworkCallback(){
            @Override public void onCapabilitiesChanged(android.net.Network network,android.net.NetworkCapabilities capabilities) {
                if(!capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))return;
                if(speechHandler!=null)speechHandler.post(()->{
                    if(!closing && execution.sessionId()!=null && deviceJournal!=null && !execution.paused()
                            && execution.pendingControl()==null && !execution.busy()) {
                        JSONObject pending=deviceJournal.pending(execution.sessionId());
                        if(pending!=null && receiptSyncGate.allow(pending.optString("request_id"),network.toString(),SystemClock.elapsedRealtime()))
                            reconcileOfflineReceipt(false);
                    }
                });
            }
        };
        try {manager.registerDefaultNetworkCallback(networkCallback);}catch(RuntimeException unavailable){networkCallback=null;}
    }

    private void showOfflineReceipt() {
        if(closing || execution.paused() || deviceJournal==null || execution.sessionId()==null)return;
        execution.recovery(TaskExecutionController.RecoveryCause.NETWORK);execution.clearObservation();
        adjustmentDelegation.clear();pendingApprovalProvenance=null;
        hideGuideOverlay();hideConfirmation();stopNarration();automationGuard.interrupt();actionTracker.clear();
        Integer wifi=null,airplane=null;
        try {var manager=getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);if(manager!=null)wifi=manager.getWifiState();}catch(RuntimeException ignored){}
        try {airplane=android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.AIRPLANE_MODE_ON);}catch(Exception ignored){}
        ensureTaskFlow();taskFlowView.offlineResult(LocalTaskReceipt.message(deviceJournal.plan(execution.sessionId()),deviceJournal.pending(execution.sessionId()),wifi,airplane),
                ()->reconcileOfflineReceipt(true));
        taskFlowCollapsed=false;updateFloatingVisibility();placeTaskFlow(null,true);
    }

    private void reconcileOfflineReceipt(boolean resumeRequested) {
        if(closing || execution.paused() || execution.busy() || execution.pendingControl()!=null || execution.sessionId()==null)return;
        String session=execution.sessionId();int generation=execution.generation();
        TaskExecutionController.RequestToken token=execution.beginRequest("reconcile_offline");
        agentGateway.reconcileOffline(session,new AgentTurnGateway.Callback(){
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if(!execution.finishRequest(token) || generation!=execution.generation() || !session.equals(execution.sessionId()))return;
                if(!acceptExecutionState(response,null,true)){contractFailure();return;}
                pendingVerifiedAction=null;pendingActionType=null;
                if(execution.pendingControl()!=null){String control=execution.pendingControl();execution.clearControl();sendAgentControl(control);return;}
                if(execution.paused())return;
                if(response.metadata!=null && response.metadata.optBoolean("offline_receipt_superseded")) {
                    execution.stop();hideGuideOverlay();hideConfirmation();
                    ensureTaskFlow();taskFlowView.failed("이전 작업이 교체되어 이 기기의 안내를 중단했어요. 새 작업을 확인해 주세요.");
                    taskFlowCollapsed=false;updateFloatingVisibility();placeTaskFlow(null,true);return;
                }
                if(java.util.Set.of("COMPLETED","CANCELLED","EXPIRED","BLOCKED","WAITING_RESULT").contains(response.taskStatus)) {
                    execution.retry();applyTaskFlowResponse(response);return;
                }
                // Synchronization confirms data only. A fresh explicit resume is needed.
                execution.recovery(TaskExecutionController.RecoveryCause.RESULT_UNKNOWN);
                ensureTaskFlow();taskFlowView.offlineResult("서버의 진행 기록을 확인했어요. 결과가 확정되지 않은 조작은 반복하지 않을게요.",()->{
                    if(generation!=execution.generation() || execution.paused() || execution.busy())return;
                    execution.retry();restoreQueuedSession(true);
                });
                taskFlowCollapsed=false;updateFloatingVisibility();placeTaskFlow(null,true);
            }
            @Override public void onFailure(Throwable error) {
                if(!execution.finishRequest(token) || generation!=execution.generation())return;
                if(execution.pendingControl()!=null){String control=execution.pendingControl();execution.clearControl();recoverControl(control);return;}
                if(error instanceof com.click.data.remote.ExecutionState.ContractMismatch){contractFailure(error);return;}
                if(!execution.paused())showOfflineReceipt();
            }
        });
    }

    private void clearActionEvidenceWait() {
        if (actionEvidenceWait != null && speechHandler != null) speechHandler.removeCallbacks(actionEvidenceWait);
        actionEvidenceWait = null;
    }

    private boolean awaitNativeActionEvidence(ScreenSnapshot screen) {
        if (pendingVerifiedAction != null || execution.acceptedGoal() == null || !actionTracker.hasSliderBinding()) {
            clearActionEvidenceWait(); return false;
        }
        long delay = adjustmentResultCheck.actionEvidenceDelay(execution.episodeId(),
                execution.acceptedGoal().optString("goal_type"), screen, SystemClock.uptimeMillis());
        if (delay <= 0) { clearActionEvidenceWait(); return false; }
        if (actionEvidenceWait != null) return true;
        final int generation = execution.generation();
        final String session = execution.sessionId();
        hideGuideOverlay(); hideConfirmation();
        ensureTaskFlow(); taskFlowView.waitingProgress("변경 결과를 확인하고 있어요.");
        placeTaskFlow(null, false);
        android.util.Log.i("ClickSlider", "waiting_native_action_evidence");
        actionEvidenceWait = new Runnable() {
            @Override public void run() {
                if (actionEvidenceWait != this) return;
                actionEvidenceWait = null;
                if (generation != execution.generation() || !java.util.Objects.equals(session, execution.sessionId())
                        || closing || execution.paused() || execution.pendingControl() != null || resultPrompt != null) return;
                sendAgentTurn(null, true);
            }
        };
        speechHandler.postDelayed(actionEvidenceWait, delay);
        return true;
    }

    private boolean requestChangedAdjustmentResult(ScreenSnapshot current) {
        if (closing || execution.paused() || execution.busy() || execution.pendingControl() != null || execution.acceptedGoal() == null
                || resultPrompt != null || followUp != null || agentGateway.currentContract() == null
                || !agentGateway.currentContract().supports("request_result")) return false;
        String goalType = execution.acceptedGoal().optString("goal_type");
        // Both the submitted observation and a fresh read must show a persisted change.
        // Keep the backend's technical outcome intact; only the user can report satisfaction.
        if (!adjustmentResultCheck.changed(execution.episodeId(), goalType, lastSubmittedScreen)
                || !adjustmentResultCheck.changed(execution.episodeId(), goalType, current)) return false;
        if (pendingVerifiedAction != null) {
            // An event may arrive while the prior observation is in flight.
            // Let the server consume that original event with a fresh observation
            // before a result request that intentionally contains no screen/action.
            execution.requestObservation();
            final int generation = execution.generation();
            speechHandler.post(() -> {
                if (generation == execution.generation() && !closing) sendAgentTurn(null, true);
            });
            return true;
        }
        hideGuideOverlay(); hideConfirmation();
        taskFlowView.waiting("조절한 결과를확인하고 있어요.");
        if (automaticTouchEnabled()) {
            taskFlowView.waitingProgress(TaskFlowOverlayView.AUTOMATIC_PROGRESS_MESSAGE);
            taskFlowView.setAutomaticProgress(true);
        }
        placeTaskFlow(null, true);
        SettingsAdjustment task = SettingsAdjustment.forGoal(goalType);
        if (task != null && !java.util.Objects.equals(task.value(lastSubmittedScreen), task.value(current))) {
            // The user may still be dragging. Wait for a settled device value without
            // treating that expected change as a stale screen or reissuing the action.
            final int generation = execution.generation();
            adjustmentSettlingUntil = SystemClock.uptimeMillis() + 450L;
            speechHandler.postDelayed(() -> {
                if (generation != execution.generation() || closing || execution.paused() || execution.busy()
                        || execution.pendingControl() != null || resultPrompt != null) return;
                adjustmentSettlingUntil = 0;
                execution.requestObservation();
                sendAgentTurn(null, true);
            }, 450L);
            return true;
        }
        sendFlowRequest(AgentTurnContract.Request.control(execution.sessionId(), execution.sessionVersion(),
                execution.episodeId(), "request_result", null));
        return true;
    }

    private com.click.data.snapshot.NavigationState navigationFrame(ScreenSnapshot screen) {
        if(screen.navigation!=null)return screen.navigation;
        return new com.click.data.snapshot.NavigationState("unknown",com.click.data.snapshot.NavigationState.Axis.UNKNOWN,
                null,null,-1,-1,-1,ObservationRecovery.navigationFingerprint(screen));
    }

    /** Navigation searches do not authorize taps or setting changes. Keep the logo visible. */
    private boolean startAutomaticHomeSearch(ScreenSnapshot expected) {
        if (expected.activeWindowId == null || expected.nodes.isEmpty()) return false;
        com.click.data.snapshot.NavigationState frame = navigationFrame(expected);
        // Node paths/window IDs can change during paging. They must not reset
        // the search budget; each gesture still validates its actual window.
        AutomaticScrollSearch.Plan plan = homeScrollSearch.plan(execution.sessionId() + ":" + execution.episodeId() + ":" + expected.packageName, frame);
        if (plan.status == AutomaticScrollSearch.Status.FINISHED) {
            hideGuideOverlay();hideConfirmation();
            if (execution.acceptedGoal()!=null && "coupang".equals(execution.acceptedGoal().optString("target_app")))
                showCoupangEntryRecovery(false);
            else showSettingsEntryRecovery();
            return true;
        }
        boolean forward = plan.forward;
        final int generation = execution.generation();
        final int token = ++homeSearchToken;
        scrollSearchGeneration = generation;
        ensureTaskFlow();
        String app = execution.acceptedGoal()!=null && "coupang".equals(execution.acceptedGoal().optString("target_app")) ? "쿠팡" : "설정";
        taskFlowView.waitingProgress(app + " 앱을 찾기 위해 화면을 넘겨 보고 있어요.");
        taskFlowView.setAutomaticProgress(automaticTouchEnabled());
        collapseTaskFlow();
        speechHandler.postDelayed(() -> {
            if (generation != execution.generation() || token != homeSearchToken) return;
            if (closing || execution.paused() || execution.pendingControl() != null || execution.sessionId() == null) {
                scrollSearchGeneration = -1;
                return;
            }
            ScreenSnapshot current = ClickAccessibilityService.freshSnapshot();
            // Clocks/widgets change text without leaving the launcher page. Revalidate
            // the foreground window and the missing icon instead of exact widget values.
            boolean searchable = current != null && NavigationRecovery.isHomeScreen(current)
                    && expected.packageName.equals(current.packageName)
                    && java.util.Objects.equals(expected.activeWindowId, current.activeWindowId)
                    && !current.nodes.isEmpty() && appIconTarget(current)==null;
            boolean moved = plan.status == AutomaticScrollSearch.Status.MOVE && searchable && ClickAccessibilityService.swipeHomePage(
                    forward, expected.packageName, expected.activeWindowId);
            // A rejected/unchanged dispatch is observed again before deciding it is an endpoint.
            android.util.Log.i("ClickScroll", "home forward=" + forward + " eligible=" + searchable + " dispatched=" + moved);
            speechHandler.postDelayed(() -> {
                if (generation != execution.generation() || token != homeSearchToken) return;
                scrollSearchGeneration = -1;
                if (closing || execution.paused() || execution.pendingControl() != null || execution.sessionId() == null) return;
                pendingActionType = AgentActionEvents.MANUAL_OVERRIDE;
                pendingActionOccurredAtMs = System.currentTimeMillis();
                execution.requestObservation();
                sendAgentTurn(null, true);
            }, moved ? 700L : 250L);
        }, 200L);
        return true;
    }

    private boolean revealClippedTarget(AgentTurnContract.Response response, ScreenSnapshot screen) {
        AgentTurnContract.GuideCommand command=response.guideCommand;
        boolean tap="tap".equals(command.actionType) || "confirmation".equals(command.commandType)
                && "tap".equals(command.proposedAction);
        if(!tap || !hasConfirmedGoal() || command.renderPolicy!=null && command.renderPolicy.optBoolean("vision_derived")
                || com.click.service.accessibility.TargetVisibility.plan(screen,command.targetNodeId,command.targetBounds,
                        getResources().getDisplayMetrics().density)==null) return false;
        if(closing || execution.paused() || execution.pendingControl()!=null || resultPrompt!=null) return true;
        hideGuideOverlay();hideConfirmation();stopNarration();actionTracker.clear();guideStepTimer.clear();
        if(scrollSearchGeneration==execution.generation()) return true;
        String scope=execution.sessionId()+":"+execution.episodeId()+":"+response.stateId;
        if(!scope.equals(targetRevealScope)) {targetRevealScope=scope;targetRevealAttempts=0;}
        final int generation=execution.generation();
        if(targetRevealAttempts++>=2) {
            execution.recovery();
            showObservationRecovery("항목이 화면 끝에 걸려 있어요. 조금 스크롤한 뒤 다시 시도해 주세요.",
                    () -> {targetRevealScope=null;retryObservation(generation,true);});
            placeTaskFlow(null,true);return true;
        }
        scrollSearchGeneration=generation;
        taskFlowView.waitingProgress("항목이 잘 보이도록 화면을 조금 이동하고 있어요.");
        collapseTaskFlow();
        boolean dispatched=ClickAccessibilityService.revealSettingsTarget(command.targetNodeId,command.targetBounds,
                screen.packageName,screen.activeWindowId);
        android.util.Log.i("ClickScroll","revealing_target dispatched="+dispatched);
        speechHandler.postDelayed(() -> {
            if(generation!=execution.generation()) return;
            scrollSearchGeneration=-1;
            if(closing || execution.paused() || execution.pendingControl()!=null || resultPrompt!=null || waitingForTargetHelp()) return;
            // Positions and guide IDs from before the scroll are never reused.
            pendingActionType=AgentActionEvents.MANUAL_OVERRIDE;
            pendingActionOccurredAtMs=System.currentTimeMillis();
            freshObservationRetries=0;execution.requestObservation();
            updateFloatingVisibility();sendAgentTurn(null,true);
        },dispatched?750L:200L);
        return true;
    }

    private boolean canSearchSettingsMenu(AgentTurnContract.Response response,ScreenSnapshot screen) {
        AgentTurnContract.GuideCommand command=response.guideCommand;
        return command.targetBounds==null && "observe_only".equals(command.actionType)
                && (ObservationRecovery.canSearchByScrolling(response.stateId,command.renderPolicy,screen)
                    || hasConfirmedGoal() && "highlight".equals(command.commandType)
                        && ObservationRecovery.canSearchJevWait(response.stateId,command.renderPolicy,response.metadata,screen));
    }

    /** Finding a Settings item is navigation, independent of automatic button/slider changes. */
    private boolean startAutomaticSettingsSearch(String stateId, ScreenSnapshot screen) {
        if (waitingForTargetHelp()) return false;
        if (stateId==null || screen==null || screen.activeWindowId==null) return false;
        // A repeated reply joins the queued search instead of spending another page attempt.
        if (scrollSearchGeneration==execution.generation() || execution.busy() || closing || execution.paused()
                || execution.pendingControl()!=null || execution.sessionId()==null) return true;
        ScreenSnapshot current=ClickAccessibilityService.freshSnapshot();
        if (!ObservationRecovery.sameNavigationScreen(screen,current)) {
            execution.requestObservation();ClickAccessibilityService.requestFreshSnapshot();return true;
        }
        String message="설정 목록에서 항목을 찾고 있어요.";
        taskFlowView.waitingProgress(message);
        taskFlowView.setAutomaticProgress(true);
        stopNarration();
        panelInterruptedNarration=false;
        lastSpokenMessage=TaskFlowOverlayView.AUTOMATIC_PROGRESS_MESSAGE;
        updateFloatingVisibility();
        if (overlayView!=null) overlayView.setRecognizedSpeech(message);
        collapseTaskFlow();
        searchSettingsByScrolling(null,stateId,screen,execution.generation());
        return true;
    }

    private void searchSettingsByScrolling(boolean forward,ScreenSnapshot expected,int generation) {
        searchSettingsByScrolling(Boolean.valueOf(forward),null,expected,generation);
    }

    private void searchSettingsByScrolling(Boolean requestedDirection,String stateId,ScreenSnapshot expected,int generation) {
        if (generation!=execution.generation() || closing || execution.paused() || execution.busy()
                || scrollSearchGeneration==execution.generation() || execution.pendingControl()!=null
                || execution.sessionId()==null || expected==null || expected.activeWindowId==null) return;
        if (!ObservationRecovery.sameNavigationScreen(expected,ClickAccessibilityService.freshSnapshot())) {
            execution.requestObservation();ClickAccessibilityService.requestFreshSnapshot();return;
        }
        scrollSearchGeneration=generation;
        updateFloatingVisibility();
        speechHandler.postDelayed(() -> {
            if (generation!=execution.generation()) return;
            if (closing || execution.paused() || execution.pendingControl()!=null || execution.sessionId()==null || waitingForTargetHelp()) {
                scrollSearchGeneration=-1;return;
            }
            ScreenSnapshot beforeScroll=ClickAccessibilityService.freshSnapshot();
            if (!ObservationRecovery.sameNavigationScreen(expected,beforeScroll)) {
                scrollSearchGeneration=-1;execution.requestObservation();
                ClickAccessibilityService.requestFreshSnapshot();sendAgentTurn(null,true);return;
            }
            com.click.data.snapshot.NavigationState frame=navigationFrame(beforeScroll);
            AutomaticScrollSearch.Plan plan=automaticScrollSearch.plan(execution.sessionId()+":"+execution.episodeId(),
                    stateId+":"+beforeScroll.packageName,frame);
            if(plan.status==AutomaticScrollSearch.Status.FINISHED) {
                scrollSearchGeneration=-1;showTargetHelp(stateId);return;
            }
            boolean forward=requestedDirection!=null?requestedDirection:plan.forward;
            boolean moved=plan.status==AutomaticScrollSearch.Status.MOVE
                    && ClickAccessibilityService.performSettingsScroll(forward,expected.packageName,expected.activeWindowId);
            android.util.Log.i("ClickScroll","settings forward="+forward+" dispatched="+moved);
            // Dispatch success/failure never proves that the list ended.
            speechHandler.postDelayed(() -> {
                if (generation!=execution.generation()) return;
                scrollSearchGeneration=-1;
                if (closing || execution.paused() || execution.pendingControl()!=null || execution.sessionId()==null) return;
                // List movement never proves that a requested tap or setting change succeeded.
                pendingActionType=AgentActionEvents.MANUAL_OVERRIDE;
                pendingActionOccurredAtMs=System.currentTimeMillis();
                freshObservationRetries=0;execution.requestObservation();
                updateFloatingVisibility();sendAgentTurn(null,true);
            },moved ? 700L : 250L);
        },200L);
    }

    private void executeScroll(AgentTurnContract.GuideCommand command) {
        if (waitingForTargetHelp()) return;
        ScreenSnapshot screen = ClickAccessibilityService.freshSnapshot();
        boolean settingsList = screen != null && screen.activeWindowId != null
                && com.click.service.accessibility.TouchAutomationPolicy.settingsPackage(screen.packageName)
                && screen.nodes.stream().anyMatch(node -> node.visibleToUser && node.scrollable && node.enabled);
        if (!settingsList && !new com.click.data.local.TouchAutomationSettings(this).isEnabled()) return;
        guideStepTimer.clear();
        if (settingsList) {
            searchSettingsByScrolling(null, "guided_navigation", screen, execution.generation());
            return;
        }
        String direction = command.renderPolicy == null
                ? "down" : command.renderPolicy.optString("direction", "down");
        boolean forward = !"up".equalsIgnoreCase(direction) && !"backward".equalsIgnoreCase(direction);
        boolean performed = ClickAccessibilityService.performScroll(forward);
        pendingActionOccurredAtMs = System.currentTimeMillis();
        pendingActionType = performed
                ? AgentActionEvents.SCROLL_COMPLETED : AgentActionEvents.SCROLL_FAILED;
        speechHandler.postDelayed(() -> sendAgentTurn(null, true), performed ? 650L : 400L);
    }

    private void showVisionConsent(String message, JSONObject policy) {
        String vocabularyKey = policy.optString("vocabulary_key", "");
        String strategy = policy.optString("strategy", "full_frame");
        org.json.JSONArray focus = policy.optJSONArray("focus_bounds");
        int[] focusBounds = focus != null && focus.length() == 4
                ? new int[]{focus.optInt(0), focus.optInt(1), focus.optInt(2), focus.optInt(3)} : null;
        hideConfirmation();
        if (windowManager == null) return;
        final int generation = execution.generation();
        final String consentId = agentConfirmationId;
        final ScreenSnapshot consentScreen = lastSubmittedScreen;
        confirmationView = new ConfirmationOverlayView(this, message, accepted -> {
            hideConfirmation();
            visionConsentRequested = false;
            if (generation != execution.generation() || !consentId.equals(agentConfirmationId)) return;
            if (!accepted || System.currentTimeMillis() >= confirmationExpiresAt * 1000L) {
                sendAgentTurn("rejected", true);
                return;
            }
            visionCaptureInFlight = true;
            visionImageCaptured = false;
            refreshLoadingStatus();
            hideGuideOverlay();
            updateFloatingVisibility();
            // Wait for our consent/guide overlays to leave the display before capturing once.
            speechHandler.postDelayed(() -> {
                if (generation != execution.generation() || !visionCaptureInFlight) return;
                ClickAccessibilityService.captureVision(consentScreen, vocabularyKey, strategy, focusBounds, new OnDeviceVision.Callback() {
                    @Override public void onImageCaptured() {
                        if (generation != execution.generation() || !visionCaptureInFlight) return;
                        visionImageCaptured = true;
                        refreshLoadingStatus();
                        updateFloatingVisibility();
                    }
                    @Override public void onSuccess(ScreenSnapshot screen, JSONObject vision) {
                        if (generation != execution.generation() || !visionCaptureInFlight) return;
                        ScreenSnapshot current = ClickAccessibilityService.freshSnapshot();
                        if (current == null || !screen.fingerprint().equals(current.fingerprint())
                                || System.currentTimeMillis() >= confirmationExpiresAt * 1000L) {
                            onFailure("screen_changed", "화면이 바뀌었거나 허용 시간이 지났어요. 다시 요청해 주세요.");
                            return;
                        }
                        clearVisionState();
                        pendingVisionSnapshot = vision;
                        pendingVisionScreen = screen;
                        sendAgentTurn("accepted", true);
                    }
                    @Override public void onFailure(String code, String error) {
                        if (generation != execution.generation() || !visionCaptureInFlight) return;
                        clearVisionState();
                        pendingVisionFailure = code;
                        sendAgentTurn("rejected", true);
                        Toast.makeText(ClickOverlayService.this, error, Toast.LENGTH_LONG).show();
                    }
                });
            }, Math.max(250L, Math.min(1500L, policy.optLong("retry_delay_ms", 250L))));
        });
        confirmationView.setMaximumHeight(screenHeight() - dp(56));
        confirmationParams = new WindowManager.LayoutParams(
                screenWidth() - dp(28), WindowManager.LayoutParams.WRAP_CONTENT, overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        confirmationParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        confirmationParams.y = dp(28);
        addOverlayView(confirmationView, confirmationParams);
        speechHandler.postDelayed(() -> {
            if (generation == execution.generation() && consentId.equals(agentConfirmationId)
                    && (visionConsentRequested || visionCaptureInFlight)) {
                clearVisionState();
                hideConfirmation();
                sendAgentTurn("rejected", true);
            }
        }, Math.max(1L, confirmationExpiresAt * 1000L - System.currentTimeMillis()));
    }

    private void clearVisionState() {
        visionConsentRequested = false;
        visionCaptureInFlight = false;
        visionImageCaptured = false;
        refreshLoadingStatus();
        pendingVisionSnapshot = null;
        pendingVisionFailure = null;
        pendingVisionScreen = null;
        updateFloatingVisibility();
    }

    private void silenceVoiceReply() {
        panelInterruptedNarration = false;
        voiceReplyEnabled = false;
        if (narrator != null) narrator.reset();
    }

    private void stopNarration() {
        if (narrator != null) narrator.stop();
        if (taskFlowView != null) taskFlowView.setNarrationState(false, ttsUnavailable);
    }

    private void replayGuidanceMessage(String message) {
        if (closing || message == null || message.trim().isEmpty()) return;
        voiceReplyEnabled = true;
        lastSpokenMessage = message.trim();
        if (narrator != null) narrator.replay(lastSpokenMessage);
    }

    private void noteNarrationSuppressed(String reason) {
        if (!reason.equals(lastNarrationSuppression)) android.util.Log.i("ClickNarration", "skip " + reason);
        lastNarrationSuppression = reason;
    }

    private void speak(String message) {
        if (message == null || message.trim().isEmpty()) return;
        if (closing) { noteNarrationSuppressed("closing"); return; }
        if (!voiceReplyEnabled) { noteNarrationSuppressed("voice_reply_disabled"); return; }
        if (acceptingVoiceCommand) { noteNarrationSuppressed("input_active"); return; }
        lastSpokenMessage = message.trim();
        if (overlayView != null && overlayView.getMode() != ClickOverlayLayout.Mode.COLLAPSED) {
            panelInterruptedNarration = true;
            noteNarrationSuppressed("input_panel_open");
            if (overlayView.getMode() == ClickOverlayLayout.Mode.TEXT) overlayView.showInputFeedback(message);
            return;
        }
        if (narrator == null) { noteNarrationSuppressed("narrator_missing"); return; }
        lastNarrationSuppression = null;
        narrator.speak(lastSpokenMessage);
    }

    private void restorePanelNarration() {
        if (!panelInterruptedNarration || !voiceReplyEnabled || execution.paused() || closing || taskFlowView == null
                || overlayView == null || overlayView.getMode() != ClickOverlayLayout.Mode.COLLAPSED) return;
        panelInterruptedNarration = false;
        if (automaticTouchEnabled() && taskFlowView.hasProgress()) return;
        String current = taskFlowView.guidanceMessage();
        replayGuidanceMessage(current == null || current.trim().isEmpty() ? lastSpokenMessage : current);
    }

    private void speakGuide(AgentTurnContract.Response response) {
        AgentTurnContract.GuideCommand command = response.guideCommand;
        if (automaticTouchEnabled() && !"confirmation".equals(command.commandType)
                && !"blocked".equals(command.commandType) && !"done".equals(command.commandType)) {
            panelInterruptedNarration = false;
            lastSpokenMessage = TaskFlowOverlayView.AUTOMATIC_PROGRESS_MESSAGE;
            noteNarrationSuppressed("automatic_progress");
            stopNarration();
            return;
        }
        // Node IDs, bounds and wording can change on each observation of the same graph step.
        String key = execution.episodeId() + ":" + response.stateId + ":" + command.commandType + ":" + command.actionType
                + ":" + command.dragDirection() + (response.stateId == null ? ":" + command.message : "");
        if (key.equals(lastNarratedGuideKey)) {
            lastSpokenMessage = command.message;
            if (narrator != null && taskFlowView != null)
                taskFlowView.setNarrationState(narrator.isSpeaking(), narrator.isUnavailable());
            return;
        }
        if (voiceReplyEnabled && !acceptingVoiceCommand && !closing && narrator != null
                && (overlayView == null || overlayView.getMode() == ClickOverlayLayout.Mode.COLLAPSED))
            lastNarratedGuideKey = key;
        speak(command.message);
    }

    private void showGuideOverlay(int[] bounds, String actionType, int dragDirection) {
        if (bounds == null || windowManager == null) {
            hideGuideOverlay();
            return;
        }
        attachGuideOverlay();
        visionGuideScreen = null;
        nativeGuideScreen = lastSubmittedScreen;
        guideOverlayView.showTarget(bounds, actionType, dragDirection);
        setOverlayVisibility(guideOverlayView, View.VISIBLE);
    }

    private void attachGuideOverlay() {
        if (guideOverlayView == null) guideOverlayView = new GuideOverlayView(this);
        if (guideOverlayView.isAttachedToWindow()) return;
        setOverlayVisibility(guideOverlayView, View.GONE);
        guideOverlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        guideOverlayParams.gravity = Gravity.START | Gravity.TOP;
        addOverlayView(guideOverlayView, guideOverlayParams);
    }

    private void hideGuideOverlay() {
        visionGuideScreen = null;
        nativeGuideScreen = null;
        // Reattaching on every guide would raise the highlight above the logo.
        if (guideOverlayView != null) setOverlayVisibility(guideOverlayView, View.GONE);
    }

    private void detachGuideOverlay() {
        hideGuideOverlay();
        if (windowManager != null && guideOverlayView != null && guideOverlayView.isAttachedToWindow()) {
            windowManager.removeViewImmediate(guideOverlayView);
        }
    }

    private void invalidateStaleGuide() {
        if (closing || execution.paused() || execution.pendingControl() != null || resultPrompt != null
                || followUp != null || waitingForTargetHelp()) return;
        ScreenSnapshot source = visionGuideScreen != null ? visionGuideScreen : nativeGuideScreen;
        ScreenSnapshot latest = LatestScreenSnapshot.get();
        if (source != null && (latest == null || !GuideFreshness.stillValid(source,
                latest, lastTargetNodeId, lastTargetBounds, visionGuideScreen != null))) {
            hideGuideOverlay();
            actionTracker.clear();
            guideStepTimer.clear();
            stopNarration();
            lastNarratedGuideKey = null;
            execution.requestObservation();
            // Navigation recovery deliberately blocks ordinary turns. Do not replace
            // its actionable UI with a waiting message that cannot make progress.
            if (navigationRecovery.hint() != NavigationRecovery.Action.NONE) {
                showSettingsEntryRecovery();
                return;
            }
            if (taskFlowView != null) {
                taskFlowView.waitingProgress("화면이 바뀌었어요. 현재 위치를 다시 확인하고 있어요.");
                taskFlowView.setAutomaticProgress(automaticTouchEnabled());
                updateFloatingVisibility();
                placeTaskFlow(null, false);
            }
            scheduleGuideResync();
        }
    }

    private void clearGuideResyncRetry() {
        if (guideResyncRetry != null && speechHandler != null) speechHandler.removeCallbacks(guideResyncRetry);
        guideResyncRetry = null;
    }

    /** A transient/duplicate AX event must not be the only way out of screen-change waiting. */
    private void scheduleGuideResync() {
        clearGuideResyncRetry();
        final int generation = execution.generation();
        final String session = execution.sessionId();
        guideResyncRetry = new Runnable() {
            private int attempts;
            @Override public void run() {
                if (guideResyncRetry != this) return;
                if (generation != execution.generation() || !java.util.Objects.equals(session, execution.sessionId())
                        || closing || execution.paused() || execution.pendingControl() != null || resultPrompt != null
                        || followUp != null || waitingForTargetHelp() || !hasConfirmedGoal()) {
                    clearGuideResyncRetry(); return;
                }
                // Its callback revalidates against the newest screen. Never send parallel turns.
                if (execution.busy()) { clearGuideResyncRetry(); return; }
                ScreenSnapshot current = ClickAccessibilityService.freshSnapshot();
                if (handleNavigationSnapshot(current)) {
                    clearGuideResyncRetry();
                    showSettingsEntryRecovery();
                    return;
                }
                sendAgentTurn(null, true);
                if (guideResyncRetry != this) return;
                if (execution.busy() || missingScreenRetry != null || missingScreenRecovery) {
                    clearGuideResyncRetry(); return;
                }
                if (++attempts < 3 && staleGuideDrops < 4) {
                    speechHandler.postDelayed(this, 750L);
                    return;
                }
                clearGuideResyncRetry();
                execution.clearObservation();
                showObservationRecovery("현재 위치를 다시 확인하지 못했어요. 화면 이동을 멈춘 뒤 ‘다시 시도하기’를 눌러주세요.",
                        () -> retryObservation(generation, false));
                placeTaskFlow(null, true);
            }
        };
        speechHandler.postDelayed(guideResyncRetry, 750L);
    }

    private void showConfirmation(String message) {
        hideConfirmation();
        ensureTaskFlow();
        final int generation = execution.generation();
        final String consentId = agentConfirmationId;
        final ScreenSnapshot consentScreen = lastSubmittedScreen;
        taskFlowView.confirmation(message, accepted -> {
            if (execution.busy() || execution.paused() || generation != execution.generation()
                    || consentId == null || !consentId.equals(agentConfirmationId)) return;
            stopNarration();
            sendAgentTurn(accepted ? "accepted" : "rejected", true, accepted ? consentScreen : null);
        });
        placeTaskFlow(lastTargetBounds, true);
    }

    private boolean automaticTouchEnabled() {
        if (execution.acceptedGoal() != null && "coupang".equals(execution.acceptedGoal().optString("target_app"))) return false;
        return new com.click.data.local.TouchAutomationSettings(this).isEnabled();
    }

    private boolean isAdjustmentConfirmation(AgentTurnContract.GuideCommand command) {
        return execution.acceptedGoal() != null && SettingsAdjustment.forGoal(execution.acceptedGoal().optString("goal_type")) != null
                && "confirmation".equals(command.commandType) && "observe_only".equals(command.actionType)
                && "drag".equals(command.proposedAction) && command.visionFallback() == null;
    }

    private void hideConfirmation() {
        if (windowManager != null && confirmationView != null && confirmationView.isAttachedToWindow()) {
            windowManager.removeView(confirmationView);
        }
        confirmationView = null;
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
