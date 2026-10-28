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
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;
import android.widget.Button;

import androidx.annotation.Nullable;

import com.click.data.remote.AgentActionEvents;
import com.click.data.local.QuickHelpSelectionStore;
import com.click.BuildConfig;
import com.click.core.permission.PermissionStatus;
import com.click.data.remote.AgentTurnContract;
import com.click.data.remote.AgentTurnGateway;
import com.click.data.remote.AgentServerContract;
import com.click.domain.goal.LocalGoalResolver;
import com.click.domain.goal.RequirementReview;
import com.click.data.remote.ErrorDetails;
import com.click.data.remote.VoiceQueueGateway;
import com.click.data.remote.PersonalizationEventGateway;
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
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public final class ClickOverlayService extends Service implements ClickOverlayLayout.Callback {
    public static final String EXTRA_INITIAL_GOAL = "com.click.extra.INITIAL_GOAL";
    public static final String EXTRA_OPEN_VOICE = "com.click.extra.OPEN_VOICE";
    public static final String EXTRA_SELECTED_GOAL_TYPE = "com.click.extra.SELECTED_GOAL_TYPE";
    private static WeakReference<ClickOverlayService> runningService = new WeakReference<>(null);
    private WindowManager windowManager;
    private int overlayWindowType;
    private WindowManager.LayoutParams windowParams;
    private ClickOverlayLayout overlayView;
    private int textRestingY;
    private final Runnable keyboardPlacement = this::fitTextAboveKeyboard;
    private TrashDropView trashDropView;
    private WindowManager.LayoutParams trashDropParams;
    private ValueAnimator snapAnimator;
    private CloudSpeechRecognizer cloudSpeechRecognizer;
    private Handler speechHandler;
    private boolean listening;
    private boolean acceptingVoiceCommand;
    private int speechGeneration;
    private int queueGeneration;
    private boolean requestActive;
    private boolean awaitingReplacement;
    private int executionGeneration;
    private VoiceQueueGateway queueGateway;
    private QuickHelpSelectionStore quickHelpSelections;
    private String activeVoiceRequestId;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    // Speech output starts only after a submitted request or explicit guidance control.
    private boolean voiceReplyEnabled;
    private boolean ttsUnavailable;
    private int narrationSequence;
    private String activeUtteranceId;
    private String pendingSpeech;
    private String lastSpokenMessage;
    private boolean overTrashTarget;
    private AgentTurnGateway agentGateway;
    private PersonalizationEventGateway personalizationEvents;
    private String personalizationStateId;
    private final GuideStepTimer guideStepTimer = new GuideStepTimer();
    private GoalCatalogClassifier goalClassifier;
    private GoalCatalogClassifier.Match activeGoalMatch;
    private JSONObject acceptedGoal;
    private JSONObject goalSelection;
    private RequirementReview requirementReview;
    private int taskFlowHeightLimit;
    private int taskFlowRestingGravity;
    private int appliedTaskFlowHeight, appliedTaskFlowFlags, appliedTaskFlowGravity;
    private String goalResolutionId;
    private com.click.domain.goal.GoalPreparationState goalPreparation;
    private JSONObject resultPrompt;
    private JSONObject followUp;
    private String pendingControl;
    private JSONObject pendingVerifiedAction;
    private final GuidedActionTracker actionTracker = new GuidedActionTracker();
    private TaskFlowOverlayView taskFlowView;
    private boolean taskFlowCollapsed;
    private WindowManager.LayoutParams taskFlowParams;
    private Runnable resultTimeout;
    private String pendingReplacement;
    private String agentSessionId;
    private int agentSessionVersion;
    private String agentEpisodeId;
    private String agentConfirmationId;
    private String initialGoal;
    private boolean firstTurnSent;
    private boolean turnInFlight;
    private boolean agentPaused;
    private boolean awaitingExitConfirmation;
    private String lastSnapshotFingerprint;
    private int homeScanAttempts;
    private boolean homeScanTowardNext = true;
    private String homeScanPreviousFingerprint;
    private String pendingActionType;
    private long pendingActionOccurredAtMs;
    private boolean forceNextObservation;
    private String lastGuideCommandId;
    private String lastTargetNodeId;
    private int[] lastTargetBounds;
    private boolean visionConsentRequested;
    private boolean visionCaptureInFlight;
    private JSONObject pendingVisionSnapshot;
    private String pendingVisionFailure;
    private ScreenSnapshot pendingVisionScreen;
    private ScreenSnapshot lastSubmittedScreen;
    private long confirmationExpiresAt;
    private int freshObservationRetries;
    private int staleGuideDrops;
    private int scrollExecutions;
    private GuideOverlayView guideOverlayView;
    private WindowManager.LayoutParams guideOverlayParams;
    private ConfirmationOverlayView confirmationView;
    private WindowManager.LayoutParams confirmationParams;
    private Button settingsOpenButton;
    private WindowManager.LayoutParams settingsOpenParams;
    private boolean collapseReceiverRegistered;
    private final BroadcastReceiver collapseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (OverlayActions.ACTION_COLLAPSE.equals(intent.getAction()) && overlayView != null) {
                overlayView.collapse();
            } else if (OverlayActions.ACTION_SNAPSHOT_UPDATED.equals(intent.getAction())) {
                sendAgentTurn(null, forceNextObservation);
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
        if (windowManager == null || windowParams == null || overlayView == null) return;
        windowParams.x = Math.round(dragTargetX);
        windowParams.y = Math.round(dragTargetY);
        windowManager.updateViewLayout(overlayView, windowParams);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        runningService = new WeakReference<>(this);
        WindowManager accessibilityWindows = ClickAccessibilityService.accessibilityWindowManager();
        if (!PermissionStatus.isAccessibilityEnabled(this) || accessibilityWindows == null) {
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
        trashDropView = new TrashDropView(this);
        trashDropParams = new WindowManager.LayoutParams(
                dp(112), dp(112), overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        trashDropParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        trashDropParams.y = dp(18);
        speechHandler = new Handler(getMainLooper());
        textToSpeech = new TextToSpeech(this, status -> speechHandler.post(() -> {
            if (closing || textToSpeech == null) return;
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                int language = textToSpeech.setLanguage(Locale.KOREA);
                ttsReady = language >= TextToSpeech.LANG_AVAILABLE;
                textToSpeech.setSpeechRate(0.92f);
            }
            ttsUnavailable = !ttsReady;
            if (ttsReady && pendingSpeech != null) {
                String message = pendingSpeech; pendingSpeech = null; speak(message);
            } else if (ttsUnavailable) {
                pendingSpeech = null;
                android.util.Log.w("ClickNarration", "Korean TTS initialization failed");
                if (taskFlowView != null) taskFlowView.setNarrationState(false, true);
            }
        }));
        textToSpeech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String id) { updateNarration(id, true, false); }
            @Override public void onDone(String id) { updateNarration(id, false, false); }
            @Override public void onError(String id) { updateNarration(id, false, true); }
            @Override public void onStop(String id, boolean interrupted) { updateNarration(id, false, false); }
        });
        agentGateway = new AgentTurnGateway(this);
        personalizationEvents = new PersonalizationEventGateway(this);
        queueGateway = new VoiceQueueGateway(this);
        quickHelpSelections = new QuickHelpSelectionStore(this);
        goalClassifier = loadGoalCatalog();
        cloudSpeechRecognizer = new CloudSpeechRecognizer(this);
        IntentFilter overlayFilter = new IntentFilter(OverlayActions.ACTION_COLLAPSE);
        overlayFilter.addAction(OverlayActions.ACTION_SNAPSHOT_UPDATED);
        registerReceiver(collapseReceiver, overlayFilter, Context.RECEIVER_NOT_EXPORTED);
        collapseReceiverRegistered = true;
        windowParams = new WindowManager.LayoutParams(
                dp(64), dp(64), overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        windowParams.gravity = Gravity.START | Gravity.TOP;
        // Position the overlay from screen coordinates ourselves, without an additional IME pan.
        windowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        windowParams.x = screenWidth() - dp(64) - dp(12);
        windowParams.y = screenHeight() - dp(64) - dp(48);
        windowManager.addView(overlayView, windowParams);
        sendBroadcast(new Intent(OverlayActions.ACTION_VISIBILITY_CHANGED).setPackage(getPackageName()));
        // Restoring an overlay must not claim or speak a request left on the server.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
                        overlayView.addUserMessage(selected.requestText);
                        enqueueNormalRequest(selected.requestText, selected.goalType);
                    });
                }
            } else if (initialRequest != null && !initialRequest.trim().isEmpty()) {
                silenceVoiceReply();
                voiceReplyEnabled = true;
                String request = initialRequest.trim();
                overlayView.post(() -> {
                    overlayView.addUserMessage(request);
                    handleRecognizedCommand(request);
                });
            } else if (openVoice) {
                overlayView.post(overlayView::openVoice);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onModeChanged(ClickOverlayLayout.Mode oldMode, ClickOverlayLayout.Mode newMode) {
        if (windowManager == null || windowParams == null) return;
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
        // Opening a panel never authorizes recording. Only the microphone button does.
        stopListening();
        if (newMode != ClickOverlayLayout.Mode.COLLAPSED) silenceVoiceReply();
        else if (textToSpeech != null) textToSpeech.stop();
        if (newMode == ClickOverlayLayout.Mode.COLLAPSED) {
            overlayView.post(this::snapToNearestEdge);
        }
    }

    @Override
    public void onDragStarted() {
        if (windowManager == null || windowParams == null || overlayView == null) return;
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
        if (windowManager == null || windowParams == null) return;
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
        if (overlayView == null || windowManager == null || windowParams == null) return;
        dragInProgress = false;
        overlayView.removeCallbacks(dragFrame);
        dragFrameScheduled = false;
        windowParams.x = Math.round(dragTargetX);
        windowParams.y = Math.round(dragTargetY);
        windowManager.updateViewLayout(overlayView, windowParams);
        updateTrashTargetState();
        if (overTrashTarget) {
            hideTrashTarget();
            shutdownClick();
            return;
        }
        hideTrashTarget();
        snapToNearestEdge();
    }

    @Override public void onDragCancelled() {
        dragInProgress = false;
        if (overlayView != null) overlayView.removeCallbacks(dragFrame);
        dragFrameScheduled = false;
        hideTrashTarget();
        snapToNearestEdge();
    }

    @Override
    public void onTextSubmitted(String text) {
        if (text == null || text.trim().isEmpty()) return;
        silenceVoiceReply();
        voiceReplyEnabled = true;
        Toast.makeText(this, "요청을 보내고 있습니다…", Toast.LENGTH_SHORT).show();
        if (overlayView != null) {
            overlayView.addUserMessage(text);
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
        if (overlayView != null) overlayView.removeCallbacks(keyboardPlacement);
        clearTaskFlow();
        executionGeneration++;
        queueGeneration++;
        speechGeneration++;
        acceptingVoiceCommand = false;
        clearVisionState();
        if (snapAnimator != null) snapAnimator.cancel();
        if (speechHandler != null) speechHandler.removeCallbacksAndMessages(null);
        if (cloudSpeechRecognizer != null) cloudSpeechRecognizer.close();
        listening = false;
        if (agentGateway != null) agentGateway.close();
        if (personalizationEvents != null) personalizationEvents.close();
        if (queueGateway != null) queueGateway.close();
        if (collapseReceiverRegistered) {
            unregisterReceiver(collapseReceiver);
            collapseReceiverRegistered = false;
        }
        hideGuideOverlay();
        hideConfirmation();
        hideSettingsOpenButton();
        hideTrashTarget();
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

    /** Only the first tap in the guided app is measured; it is not a success label. */
    public static void onGuidedViewClicked(String packageName, long eventUptimeMs) {
        ClickOverlayService service = runningService.get();
        if (service == null || service.agentPaused || service.agentSessionId == null) return;
        GuideStepTimer.Observation observation = service.guideStepTimer.firstTap(
                packageName, eventUptimeMs);
        if (observation != null && service.personalizationEvents != null) {
            service.personalizationEvents.recordFirstTap(observation.sessionId,
                    observation.stateId, observation.step, observation.durationMs);
        }
    }

    public static void onGuidedAction(String type, String packageName, int windowId, String nodeId,
                                     String resourceId, int[] bounds, long occurredAt) {
        ClickOverlayService service = runningService.get();
        if (service == null || service.agentPaused || service.pendingControl != null) return;
        JSONObject event = service.actionTracker.record(type, packageName, windowId, nodeId,
                resourceId, bounds, occurredAt);
        if (event == null) return;
        service.pendingVerifiedAction = event;
        final int generation = service.executionGeneration;
        // Read twice after the action, including unchanged screens, to settle or reject it.
        for (long delay : new long[]{350L, 1100L, 2200L, 5500L}) {
            service.speechHandler.postDelayed(() -> {
                if (generation != service.executionGeneration || service.agentPaused
                        || service.resultPrompt != null || service.pendingControl != null) return;
                service.forceNextObservation = true;
                ClickAccessibilityService.requestFreshSnapshot();
            }, delay);
        }
    }

    private void updateFloatingVisibility() {
        if (overlayView == null || closing) return;
        boolean showTask = taskFlowView != null && !taskFlowCollapsed
                && overlayView.getMode() == ClickOverlayLayout.Mode.COLLAPSED;
        overlayView.setVisibility(visionCaptureInFlight || showTask ? View.GONE : View.VISIBLE);
        if (taskFlowView != null) taskFlowView.setVisibility(showTask && !visionCaptureInFlight ? View.VISIBLE : View.GONE);
    }

    private void collapseTaskFlow() {
        taskFlowCollapsed = true;
        stopListening(); silenceVoiceReply();
        if (taskFlowView != null) {
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(taskFlowView.getWindowToken(), 0);
        }
        if (overlayView != null) overlayView.collapse();
        updateFloatingVisibility();
    }

    @Override public boolean onOpenRequested() {
        if (taskFlowView == null || !taskFlowCollapsed) return false;
        taskFlowCollapsed = false;
        updateFloatingVisibility();
        return true;
    }

    private void ensureTaskFlow() {
        if (taskFlowView != null || windowManager == null) return;
        // Hand off to guidance once, without closing a draft on later task updates.
        if (overlayView != null) overlayView.collapse();
        taskFlowView = new TaskFlowOverlayView(this, new TaskFlowOverlayView.Listener() {
            @Override public void collapse() { collapseTaskFlow(); }
            @Override public void openInput() { if (overlayView != null) overlayView.openVoice(); }
            @Override public void chooseGoal(JSONObject goal) { chooseResolvedGoal(goal); }
            @Override public void clarify(String choice) { resolveGoal(initialGoal, choice); }
            @Override public void rephrase(String text) { voiceReplyEnabled = true; initialGoal = text; resolveGoal(text, null); }
            @Override public void replayGuidance(String message) { voiceReplyEnabled = true; speak(message); }
            @Override public void stopGuidance() { silenceVoiceReply(); }
            @Override public void requirementChoice(String choice) {
                if (requirementReview == null || agentPaused || turnInFlight) return;
                voiceReplyEnabled = true;
                requirementReview.choose(choice); showRequirementReview();
            }
            @Override public void requirementAnswer(String answer) { updateRequirements(answer); }
            @Override public void confirmRequirements() { confirmRequirementReview(); }
            @Override public void answerGoalInput(String value) { ClickOverlayService.this.answerGoalInput(value); }
            @Override public void resetGoalInputs() {
                if (turnInFlight || agentPaused) return;
                acceptedGoal = null;
                resolveGoalOnServer(initialGoal, null, null, true);
            }
            @Override public void result(String answer) { sendResult(answer); }
            @Override public void adjust(String reason) { sendAdjustment(reason); }
            @Override public void control(String command) {
                if ("cancel_task".equals(command)) onStopRequested();
                else sendAgentControl(command);
            }
        });
        taskFlowParams = new WindowManager.LayoutParams(Math.min(screenWidth() - dp(24), dp(344)),
                WindowManager.LayoutParams.WRAP_CONTENT, overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        taskFlowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        taskFlowParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        taskFlowParams.y = dp(30);
        windowManager.addView(taskFlowView, taskFlowParams);
        updateFloatingVisibility();
    }

    private void placeTaskFlow(int[] target, boolean expanded) {
        ensureTaskFlow();
        if (taskFlowView == null) return;
        int height = (int) (screenHeight() * (expanded ? .65f : .38f));
        boolean atBottom = target != null && (target[1] + target[3]) / 2 < screenHeight() / 2;
        if (target != null) {
            int available = atBottom ? screenHeight() - target[3] - dp(32) : target[1] - dp(48);
            height = Math.max(dp(150), Math.min(height, available));
        }
        taskFlowParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        taskFlowParams.gravity = (atBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        taskFlowParams.y = dp(24);
        taskFlowParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | (goalSelection == null && (requirementReview == null || requirementReview.confirmed()) ? WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE : 0);
        taskFlowHeightLimit = height;
        taskFlowRestingGravity = taskFlowParams.gravity;
        fitTaskFlowAboveKeyboard();
    }

    private void clearTaskFlow() {
        if (resultTimeout != null) speechHandler.removeCallbacks(resultTimeout);
        resultTimeout = null;
        actionTracker.clear(); pendingVerifiedAction = null;
        resultPrompt = followUp = goalSelection = acceptedGoal = null;
        requirementReview = null;
        goalResolutionId = null; goalPreparation = null; pendingControl = null;
        if (taskFlowView != null && taskFlowView.isAttachedToWindow()) windowManager.removeView(taskFlowView);
        taskFlowView = null; taskFlowCollapsed = false;
        updateFloatingVisibility();
        appliedTaskFlowHeight = 0;
    }

    private void resolveGoal(String text, String clarification) {
        if (turnInFlight || agentSessionId == null) return;
        initialGoal = text;
        taskFlowCollapsed = false;
        goalPreparation = null;
        goalResolutionId = null;
        String selectedType = quickHelpSelections == null || activeVoiceRequestId == null
                ? null : quickHelpSelections.get(activeVoiceRequestId);
        QuickHelpTask selectedCard = QuickHelpTask.fromGoalType(selectedType);
        GoalCatalogClassifier.Match selected = selectedCard == null || goalClassifier == null
                || !selectedCard.requestText.equals(text) ? null : goalClassifier.executableGoal(selectedType);
        requirementReview = RequirementReview.isFontRequest(text)
                ? RequirementReview.forContext(text, foregroundPackage()) : null;
        if (selected != null && clarification == null) {
            try { requirementReview = RequirementReview.forGoal(text, selected.toTypedGoalJson(), selected.displayName); }
            catch (org.json.JSONException error) { showQueueError(error); return; }
        }
        ensureTaskFlow(); updateFloatingVisibility(); taskFlowView.waiting("사용자 요청을 처리하고 있어요");
        turnInFlight = true;
        final int generation = executionGeneration;
        agentGateway.loadContract(new AgentTurnGateway.ContractCallback() {
            @Override public void onSuccess(AgentServerContract contract) {
                if (generation != executionGeneration || closing) return;
                turnInFlight = false;
                if (pendingControl != null) {
                    String control = pendingControl; pendingControl = null;
                    sendAgentControl(control); return;
                }
                if (agentPaused) { taskFlowView.paused(); return; }
                if (contract.goalPreparation) {
                    requirementReview = null;
                    resolveGoalOnServer(text, clarification); return;
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
                    taskFlowView.goals(goalSelection.getString("message"), goalSelection.getJSONArray("candidates"));
                    placeTaskFlow(null, true);
                    speak(goalSelection.getString("message"));
                } catch (org.json.JSONException error) { showQueueError(error); }
            }
            @Override public void onFailure(Throwable error) {
                if (generation != executionGeneration || closing) return;
                turnInFlight = false;
                showQueueError(error);
            }
        });
    }

    private void resolveGoalOnServer(String text, String clarification) {
        resolveGoalOnServer(text, clarification, null, false);
    }

    private void resolveGoalOnServer(String text, String clarification, JSONObject answer, boolean reset) {
        final int generation = executionGeneration;
        final AgentTurnContract.Request request = AgentTurnContract.Request.resolve(agentSessionId, text, clarification)
                .withSessionState(agentSessionVersion, null, null);
        turnInFlight = true;
        boolean prepares = agentGateway.currentContract() != null && agentGateway.currentContract().goalPreparation;
        if (prepares) {
            request.preparationVersion = "goal-inputs.v1";
            request.clarificationAnswer = answer;
            request.goalResolutionId = answer != null && goalPreparation != null ? goalPreparation.id() : null;
            request.preparationReset = reset;
        } else goalResolutionId = null;
        ensureTaskFlow(); taskFlowView.waiting("요청에 필요한 내용을 확인하고 있어요…");
        updateRequestStatus(true, true);
        agentGateway.send(request, new AgentTurnGateway.Callback() {
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if (generation != executionGeneration || closing) return;
                if (!request.requestId.equals(response.requestId) || !request.sessionId.equals(response.sessionId)
                        || response.sessionVersion != agentSessionVersion || !"goal_resolution".equals(response.responseType)) {
                    onFailure(new java.io.IOException("INVALID_GOAL_RESOLUTION")); return;
                }
                turnInFlight = false;
                try {
                    if (prepares) {
                        if (response.preparation == null) throw new org.json.JSONException("Missing preparation response");
                        goalPreparation = new com.click.domain.goal.GoalPreparationState(
                                response.preparation, response.resolvedGoal, response.message);
                        goalResolutionId = goalPreparation.id();
                        initialGoal = response.preparation.getString("source_text");
                        requirementReview = null; goalSelection = null;
                    }
                } catch (org.json.JSONException error) { onFailure(error); return; }
                if (pendingControl != null) {
                    String control = pendingControl; pendingControl = null; sendAgentControl(control); return;
                }
                if (agentPaused) { taskFlowView.paused(); return; }
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
                if (generation != executionGeneration || closing) return;
                turnInFlight = false;
                if (pendingControl != null) {
                    String control = pendingControl; pendingControl = null; sendAgentControl(control); return;
                }
                if (agentPaused) { taskFlowView.paused(); return; }
                if (prepares) {
                    // Never turn a failed preparation into a locally executable goal.
                    if (answer != null || reset) {
                        // An answer may already have committed before its response was lost.
                        resolveGoalOnServer(text, null, null, false); return;
                    }
                    speak("답변을 확인하지 못했어요. 다시 시도해 주세요.");
                    goalPreparation = null; goalResolutionId = null;
                    showUnresolvedGoal();
                    return;
                }
                showUnresolvedGoal();
            }
        });
    }

    private void showGoalPreparation() {
        if (goalPreparation == null || closing) return;
        ensureTaskFlow(); taskFlowView.preparation(goalPreparation);
        updateRequestStatus(true, false); placeTaskFlow(null, true);
        speak(goalPreparation.message);
    }

    private void answerGoalInput(String value) {
        if (goalPreparation == null || turnInFlight || agentPaused || acceptedGoal != null) return;
        try {
            resolveGoalOnServer(initialGoal, null, goalPreparation.answer(value), false);
        } catch (org.json.JSONException error) {
            speak("화면에 나온 선택지로 다시 답해 주세요.");
        }
    }

    private void showUnresolvedGoal() {
        if (requirementReview != null) { showRequirementReview(); return; }
        try {
            String message = "요청의 대상을 확인하지 못했어요. 대상과 원하는 변경을 구체적으로 말씀해 주세요.";
            goalSelection = new JSONObject().put("message", message).put("candidates", new org.json.JSONArray());
            updateRequestStatus(true, false);
            taskFlowView.goals(message, goalSelection.getJSONArray("candidates"));
            placeTaskFlow(null, true); speak(message);
        } catch (org.json.JSONException error) { showQueueError(error); }
    }

    private void chooseResolvedGoal(JSONObject goal) {
        if (turnInFlight || agentPaused || goal == null || goalSelection == null) return;
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
            boolean executable = requirementReview.proposedGoal(goalClassifier, agentGateway.currentContract()) != null;
            taskFlowView.requirements(requirementReview, executable);
            updateRequestStatus(true, false); placeTaskFlow(null, true);
            speak(requirementReview.prompt(executable));
        } catch (org.json.JSONException error) { showQueueError(error); }
    }

    private void updateRequirements(String answer) {
        if (requirementReview == null || agentPaused || turnInFlight) return;
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
            if (agentPaused || turnInFlight || acceptedGoal != null || !goalPreparation.ready()) return;
            GoalCatalogClassifier.Match match = goalClassifier == null ? null
                    : goalClassifier.executableGoal(goalPreparation.goal.optString("goal_type"));
            if (match == null) { speak("이 안내를 시작하려면 앱을 업데이트해 주세요."); return; }
            acceptedGoal = goalPreparation.goal;
            activeGoalMatch = match;
            goalResolutionId = goalPreparation.id();
            startSelectedTask();
            return;
        }
        if (requirementReview == null || agentPaused || turnInFlight || acceptedGoal != null) return;
        voiceReplyEnabled = true;
        try {
            JSONObject goal = requirementReview.confirm(goalClassifier, agentGateway.currentContract());
            if (goal == null) { showRequirementReview(); return; }
            activeGoalMatch = goalClassifier.executableGoal(goal.optString("goal_type"));
            acceptedGoal = goal; goalSelection = null; goalResolutionId = null;
            initialGoal = requirementReview.request() + "\n사용자가 확인한 요청: " + requirementReview.summary();
            taskFlowView.setRequirements(requirementReview);
            startSelectedTask();
        } catch (org.json.JSONException error) { showQueueError(error); }
    }

    private void startSelectedTask() {
        ensureTaskFlow(); taskFlowView.waiting("사용자 요청을 처리하고 있어요");
        placeTaskFlow(null, false);
        final int generation = executionGeneration;
        speechHandler.postDelayed(() -> {
            if (generation == executionGeneration && !agentPaused) sendAgentTurn(null, true);
        }, 250L);
    }

    private void sendResult(String answer) {
        if (resultPrompt == null || turnInFlight || agentPaused) return;
        AgentTurnContract.Request request = AgentTurnContract.Request.control(agentSessionId,
                agentSessionVersion, agentEpisodeId, "respond_result", null);
        request.resultPromptId = resultPrompt.optString("id"); request.userResult = answer;
        sendFlowRequest(request);
    }

    private void sendAdjustment(String reason) {
        if (followUp == null || turnInFlight || agentPaused) return;
        AgentTurnContract.Request request = AgentTurnContract.Request.control(agentSessionId,
                agentSessionVersion, agentEpisodeId, "adjust_task", null);
        request.adjustmentReason = reason; sendFlowRequest(request);
    }

    private void sendFlowRequest(AgentTurnContract.Request request) {
        sendFlowRequest(request, true);
    }

    private void sendFlowRequest(AgentTurnContract.Request request, boolean mayRecover) {
        turnInFlight = true;
        updateRequestStatus(true, true);
        final int generation = executionGeneration;
        agentGateway.send(request, new AgentTurnGateway.Callback() {
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if (generation != executionGeneration || !response.sessionId.equals(agentSessionId)) return;
                turnInFlight = false;
                agentSessionVersion = response.sessionVersion; agentEpisodeId = response.episodeId;
                if (request.accessibilityTree != null) {
                    lastSubmittedScreen = request.accessibilityTree;
                    lastSnapshotFingerprint = request.accessibilityTree.fingerprint();
                }
                if (response.metadata != null && response.metadata.optJSONObject("goal_selection") != null) {
                    goalSelection = response.metadata.optJSONObject("goal_selection");
                    goalResolutionId = goalSelection.optString("id", null);
                }
                if (pendingControl != null) {
                    String command = pendingControl; pendingControl = null; sendAgentControl(command); return;
                }
                applyTaskFlowResponse(response);
            }
            @Override public void onFailure(Throwable error) {
                if (generation != executionGeneration) return;
                turnInFlight = false;
                if (pendingControl != null) {
                    String command = pendingControl; pendingControl = null; recoverControl(command); return;
                }
                if (mayRecover && ("cancel_task".equals(request.commandType()) || "pause_task".equals(request.commandType()))) {
                    recoverControl(request.commandType()); return;
                }
                showQueueError(error);
            }
        });
    }

    private void recoverControl(String command) {
        final int generation = executionGeneration;
        agentPaused = true; turnInFlight = true;
        agentGateway.synchronize(agentSessionId, new AgentTurnGateway.Callback() {
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if (generation != executionGeneration) return;
                turnInFlight = false; agentSessionVersion = response.sessionVersion; agentEpisodeId = response.episodeId;
                if ("CANCELLED".equals(response.taskStatus) || "EXPIRED".equals(response.taskStatus)
                        || "COMPLETED".equals(response.taskStatus)) { applyTaskFlowResponse(response); return; }
                sendFlowRequest(AgentTurnContract.Request.control(agentSessionId, agentSessionVersion,
                        agentEpisodeId, command, null), false);
            }
            @Override public void onFailure(Throwable error) {
                if (generation != executionGeneration) return;
                turnInFlight = false;
                if (error instanceof com.click.data.remote.ApiException && ((com.click.data.remote.ApiException) error).status == 404
                        && "cancel_task".equals(command)) {
                    abortActiveRequest(); finishActiveQueueRequest("USER_ABORTED"); return;
                }
                ensureTaskFlow(); taskFlowView.paused(); showQueueError(error);
            }
        });
    }

    private void applyTaskFlowResponse(AgentTurnContract.Response response) {
        updateRequestStatus(true, false);
        ensureTaskFlow();
        if (resultTimeout != null) speechHandler.removeCallbacks(resultTimeout);
        if ("WAITING_GOAL".equals(response.taskStatus)) {
            goalSelection = response.metadata.optJSONObject("goal_selection");
            goalResolutionId = goalSelection.optString("id", null);
            if (agentPaused) { taskFlowView.paused(); return; }
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
            agentPaused = true; actionTracker.clear(); hideGuideOverlay(); hideConfirmation();
            taskFlowView.paused(); placeTaskFlow(null, false); speak(response.message); return;
        }
        agentPaused = false;
        resultPrompt = response.metadata == null ? null : response.metadata.optJSONObject("result_prompt");
        followUp = response.metadata == null ? null : response.metadata.optJSONObject("follow_up");
        if ("WAITING_RESULT".equals(response.taskStatus) && resultPrompt != null) {
            actionTracker.clear(); pendingVerifiedAction = null;
            hideGuideOverlay(); hideConfirmation(); hideSettingsOpenButton();
            taskFlowView.setChecklist(response.metadata.optJSONArray("checklist"));
            taskFlowView.result(resultPrompt); placeTaskFlow(null, true);
            speak(resultPrompt.optString("message"));
            final String promptId = resultPrompt.optString("id");
            final int generation = executionGeneration;
            resultTimeout = () -> {
                if (generation == executionGeneration && resultPrompt != null && !agentPaused
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
            hideGuideOverlay(); hideConfirmation(); hideSettingsOpenButton();
            executionGeneration++;
            if (message != null) speak(message);
            if (satisfied && taskFlowView != null) {
                TaskFlowOverlayView completedView = taskFlowView;
                placeTaskFlow(null, false);
                final int completedGeneration = executionGeneration;
                completedView.completed(message, () -> {
                    if (completedGeneration != executionGeneration || taskFlowView != completedView) return;
                    clearTaskFlow();
                    finishActiveQueueRequest(null);
                    if (next != null) enqueueNormalRequest(next);
                });
            } else {
                clearTaskFlow();
                finishActiveQueueRequest("USER_ABORTED");
                if (next != null) enqueueNormalRequest(next);
            }
            return;
        }
        if (response.guideCommand != null) renderGuide(response);
        else { firstTurnSent = true; sendAgentTurn(null, true); }
    }

    private boolean handleTaskFlowAnswer(String text) {
        if (agentSessionId == null) return false;
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
            else speak("잘 보여요, 아직 불편해요, 그만할게요 중 하나로 답해 주세요.");
            return true;
        }
        if (followUp != null) {
            String reason = TaskFlowAnswers.adjustment(text);
            if (reason != null) sendAdjustment(reason);
            else speak(followUp.optString("message"));
            return true;
        }
        if (goalPreparation != null && acceptedGoal == null) {
            if (agentPaused || turnInFlight) return true;
            if (goalPreparation.ready() && normalized.matches("네|예|맞아|맞아요|이대로시작|이내용으로시작|시작해|시작해주세요"))
                confirmRequirementReview();
            else if (goalPreparation.needsInput()) {
                String value = goalPreparation.exactSpokenChoice(text);
                if (value != null) answerGoalInput(value);
                else speak("화면의 선택지를 눌러 답해 주세요.");
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
                || overlayWindowType == windowType) {
            return;
        }
        hideGuideOverlay();
        hideConfirmation();
        hideSettingsOpenButton();
        hideTrashTarget();
        if (snapAnimator != null) snapAnimator.cancel();
        if (windowManager != null && overlayView.isAttachedToWindow()) {
            try {
                windowManager.removeViewImmediate(overlayView);
            } catch (IllegalArgumentException ignored) {
            }
        }
        windowManager = target;
        overlayWindowType = windowType;
        windowParams.type = windowType;
        if (trashDropParams != null) trashDropParams.type = windowType;
        try {
            windowManager.addView(overlayView, windowParams);
        } catch (RuntimeException error) {
            android.util.Log.e("ClickOverlay", "Failed to rebind overlay window", error);
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
        if (windowManager == null || trashDropView == null || trashDropView.isAttachedToWindow()) return;
        overTrashTarget = false;
        trashDropView.setActive(false);
        trashDropView.setAlpha(0f);
        trashDropView.setScaleX(.72f);
        trashDropView.setScaleY(.72f);
        windowManager.addView(trashDropView, trashDropParams);
        trashDropView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180L).start();
    }

    private void hideTrashTarget() {
        overTrashTarget = false;
        if (windowManager != null && trashDropView != null && trashDropView.isAttachedToWindow()) {
            trashDropView.animate().cancel();
            windowManager.removeView(trashDropView);
        }
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

    @Override public void onMicrophoneClicked() {
        if (acceptingVoiceCommand) {
            stopListening();
            overlayView.setRecognizedSpeech("음성 입력을 취소했어요. 다시 입력하려면 마이크를 눌러주세요.");
            return;
        }
        if (closing || overlayView == null || overlayView.getMode() != ClickOverlayLayout.Mode.VOICE) return;
        silenceVoiceReply();
        acceptingVoiceCommand = true;
        startListening();
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
            overlayView.setRecognizedSpeech("마이크 권한을 허용해주세요.");
            return;
        }
        final int generation = ++speechGeneration;
        overlayView.setVoiceInputActive(true);
        // Audio leaves the device only for our authenticated backend/self-hosted STT.
        if (cloudSpeechRecognizer == null) {
            stopListening();
            overlayView.setRecognizedSpeech("음성 입력을 사용할 수 없어요. 글로 입력해 주세요.");
            return;
        }
        listening = true;
        overlayView.setRecognizedSpeech("듣고 있어요… 말씀을 마치면 자동으로 보내요.");
        cloudSpeechRecognizer.start(new CloudSpeechRecognizer.Callback() {
            @Override public void onRecordingFinished() {
                if (!acceptsSpeech(generation)) return;
                listening = false;
                overlayView.setRecognizedSpeech("말씀하신 내용을 확인하고 있어요…");
            }
            @Override public void onSuccess(String text) {
                if (!acceptsSpeech(generation)) return;
                stopListening();
                submitRecognizedSpeech(text);
            }
            @Override public void onFailure(Throwable error) {
                if (!acceptsSpeech(generation)) return;
                stopListening();
                // A provider failure must not reopen the microphone without another tap.
                SpeechErrorMessages.log(error);
                overlayView.setRecognizedSpeech(SpeechErrorMessages.message(error));
            }
        });
    }

    private void submitRecognizedSpeech(String text) {
        if (text == null || text.trim().isEmpty()) {
            overlayView.setRecognizedSpeech("마이크를 눌러 다시 말씀해 주세요.");
            return;
        }
        voiceReplyEnabled = true;
        overlayView.setRecognizedSpeech(text);
        overlayView.addUserMessage(text);
        overlayView.collapse();
        handleRecognizedCommand(text);
    }

    private void stopListening() {
        speechGeneration++;
        boolean wasAccepting = acceptingVoiceCommand;
        acceptingVoiceCommand = false;
        if (wasAccepting && overlayView != null)
            overlayView.setRecognizedSpeech("마이크 버튼을 누른 뒤 말씀해 주세요.");
        if (cloudSpeechRecognizer != null) cloudSpeechRecognizer.cancel();
        listening = false;
        if (overlayView != null) overlayView.setVoiceInputActive(false);
    }

    private void updateRequestStatus(boolean active, boolean processing) {
        requestActive = active;
        if (overlayView != null) overlayView.setRequestStatus(active
                ? processing ? "사용자 요청을 처리하고 있어요" : "요청을 진행하고 있어요" : null, active);
    }

    @Override public void onStopRequested() {
        if (closing || !requestActive) return;
        stopListening();
        processVipRequest("멈춰");
    }

    private void handleRecognizedCommand(String text) {
        String command = text == null ? "" : text.trim();
        if (command.isEmpty()) return;
        // Before contextual answers: "네?" / "응?" must not accept a confirmation or result prompt.
        if (RepeatRequest.matches(command)) { repeatLastGuide(); return; }
        if (handleTaskFlowAnswer(command)) return;
        if (handleExitConfirmation(command)) {
            return;
        }
        GoalCommandInterpreter.Result control = GoalCommandInterpreter.interpret(
                command, agentSessionId != null, agentPaused, agentConfirmationId != null,
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
        if (personalizationEvents != null && lastSpokenMessage != null) {
            personalizationEvents.recordGuideRepeated(agentSessionId, personalizationStateId);
        }
        String repeated = lastSpokenMessage == null
                ? "아직 다시 말씀드릴 안내가 없어요." : lastSpokenMessage;
        if (overlayView != null) overlayView.setRecognizedSpeech(repeated);
        speak(repeated);
    }

    private void enqueueNormalRequest(String text) {
        enqueueNormalRequest(text, null);
    }

    private void enqueueNormalRequest(String text, String selectedGoalType) {
        if (closing || text == null || text.trim().isEmpty() || queueGateway == null) return;
        if (agentPaused) {
            replacePausedQueue(text.trim(), selectedGoalType);
            return;
        }
        updateRequestStatus(true, true);
        if (agentSessionId == null) {
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
                awaitingReplacement = response.awaitingReplacement;
                if (overlayView != null && agentSessionId != null) {
                    overlayView.setRecognizedSpeech("요청을 대기열에 추가했어요. 대기 요청 "
                            + response.queuedCount + "개입니다.");
                }
                if (agentSessionId == null && !turnInFlight) claimNextQueuedRequest();
            }

            @Override public void onFailure(Throwable error) {
                if (queuedGeneration != queueGeneration) return;
                if (agentSessionId == null) updateRequestStatus(false, false);
                showQueueError(error);
            }
        });
    }

    private void processVipRequest(String command) {
        if (queueGateway == null) return;
        queueGeneration++;
        abortActiveRequest();
        awaitingReplacement = true;
        overlayView.setRequestStatus("요청을 멈췄어요. 서버에 중단을 전달하고 있어요…", false);
        overlayView.addAssistantMessage("요청을 멈췄어요.");
        if (overlayView.getMode() == ClickOverlayLayout.Mode.COLLAPSED) overlayView.openMenu();
        final long generation = executionGeneration;
        queueGateway.interrupt(command, new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (generation != executionGeneration) return;
                if (activeVoiceRequestId != null) quickHelpSelections.remove(activeVoiceRequestId);
                awaitingReplacement = response.awaitingReplacement;
                activeVoiceRequestId = null;
                String message = "요청을 멈췄어요. 새 음성 입력은 마이크 버튼을 눌러주세요.";
                if (!requestActive) {
                    overlayView.setRequestStatus("요청을 멈췄어요", false);
                    overlayView.setRecognizedSpeech(message);
                    speak(message);
                }
            }

            @Override public void onFailure(Throwable error) {
                if (generation != executionGeneration) return;
                requestActive = true;
                overlayView.setRequestStatus("기기에서 멈췄어요. 서버 중단을 다시 시도해 주세요.", true);
            }
        });
    }

    private void abortActiveRequest() {
        silenceVoiceReply();
        executionGeneration++;
        stopListening();
        pendingSpeech = null;
        updateRequestStatus(false, false);
        clearTaskFlow();
        clearVisionState();
        agentSessionId = null;
        agentSessionVersion = 0;
        agentEpisodeId = null;
        agentPaused = false;
        agentConfirmationId = null;
        initialGoal = null;
        activeGoalMatch = null;
        firstTurnSent = false;
        agentPaused = false;
        turnInFlight = false;
        pendingActionType = null;
        speechHandler.removeCallbacksAndMessages(null);
        if (textToSpeech != null) textToSpeech.stop();
        hideGuideOverlay();
        hideConfirmation();
        hideSettingsOpenButton();
    }

    private void claimNextQueuedRequest() {
        if (closing || queueGateway == null || agentSessionId != null || turnInFlight || awaitingReplacement) return;
        final long generation = executionGeneration;
        queueGateway.claim(new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (generation != executionGeneration) return;
                awaitingReplacement = response.awaitingReplacement;
                if (response.running != null && response.running.agentSessionId != null
                        && response.running.rawGoal != null) {
                    activeVoiceRequestId = response.running.id;
                    beginAgentSession(response.running.rawGoal, response.running.agentSessionId);
                }
            }

            @Override public void onFailure(Throwable error) { if (generation == executionGeneration) showQueueError(error); }
        });
    }

    private void finishActiveQueueRequest(String failureCode) {
        updateRequestStatus(false, false);
        final boolean completedSuccessfully = failureCode == null;
        if (activeVoiceRequestId == null || queueGateway == null) {
            agentSessionId = null;
            if (completedSuccessfully) askToExitAfterQueueCompleted();
            else claimNextQueuedRequest();
            return;
        }
        final long generation = executionGeneration;
        String finishedId = activeVoiceRequestId;
        VoiceQueueGateway.Callback callback = new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (generation != executionGeneration) return;
                quickHelpSelections.remove(finishedId);
                activeVoiceRequestId = null;
                agentSessionId = null;
                turnInFlight = false;
                awaitingReplacement = response.awaitingReplacement;
                if (response.running != null && response.running.agentSessionId != null
                        && response.running.rawGoal != null) {
                    activeVoiceRequestId = response.running.id;
                    beginAgentSession(response.running.rawGoal, response.running.agentSessionId);
                } else if (completedSuccessfully && response.queuedCount == 0
                        && !response.awaitingReplacement) {
                    askToExitAfterQueueCompleted();
                } else {
                    claimNextQueuedRequest();
                }
            }

            @Override public void onFailure(Throwable error) { if (generation == executionGeneration) showQueueError(error); }
        };
        if (failureCode == null) queueGateway.complete(finishedId, callback);
        else queueGateway.fail(finishedId, failureCode, callback);
    }

    private void failActiveQueueRequest(String failureCode) {
        if (activeVoiceRequestId != null) finishActiveQueueRequest(failureCode);
    }

    private void showQueueError(Throwable error) {
        android.util.Log.e("ClickQueue", ErrorDetails.describe("요청 처리", error), error);
        String message = ErrorDetails.requestMessage(error);
        if (overlayView != null) {
            overlayView.setRecognizedSpeech(message);
            overlayView.addAssistantMessage(message);
        }
        if (taskFlowView != null) taskFlowView.waiting(message);
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

    private void beginAgentSession(String goal, String sessionId) {
        if (closing || goal == null || goal.trim().isEmpty()) return;
        updateRequestStatus(true, true);
        executionGeneration++;
        guideStepTimer.clear();
        agentSessionId = sessionId;
        personalizationStateId = null;
        agentSessionVersion = 0;
        agentConfirmationId = null;
        initialGoal = goal.trim();
        goalPreparation = null;
        awaitingExitConfirmation = false;
        activeGoalMatch = null;
        acceptedGoal = null;
        goalSelection = resultPrompt = followUp = null;
        requirementReview = null;
        goalResolutionId = null;
        agentEpisodeId = null;
        agentPaused = false;
        pendingControl = null;
        pendingVerifiedAction = null;
        actionTracker.clear();
        firstTurnSent = false;
        lastSnapshotFingerprint = null;
        homeScanAttempts = 0;
        homeScanTowardNext = true;
        homeScanPreviousFingerprint = null;
        pendingActionType = null;
        forceNextObservation = false;
        lastGuideCommandId = null;
        lastTargetNodeId = null;
        lastTargetBounds = null;
        clearVisionState();
        freshObservationRetries = 0;
        staleGuideDrops = 0;
        scrollExecutions = 0;
        if (overlayView != null) overlayView.setRecognizedSpeech("화면을 확인하고 있어요…");
        // Quick-help cards provide a request, not confirmation of its target and scope.
        resolveGoal(initialGoal, null);
    }

    private void sendAgentTurn(String confirmation, boolean force) {
        if (closing || agentSessionId == null || agentPaused || turnInFlight || agentGateway == null
                || staleGuideDrops >= 4
                || !hasConfirmedGoal() || resultPrompt != null || followUp != null || pendingControl != null) return;
        if (confirmation == null && (visionConsentRequested || visionCaptureInFlight)) return;
        ScreenSnapshot snapshot = pendingVisionScreen != null ? pendingVisionScreen
                : (force || confirmation != null) ? ClickAccessibilityService.freshSnapshot() : LatestScreenSnapshot.get();
        if (snapshot == null) {
            if (overlayView != null) overlayView.setRecognizedSpeech("접근성 화면 정보를 기다리고 있어요…");
            return;
        }
        if (textToSpeech != null) textToSpeech.stop();
        if (!firstTurnSent && shouldScanHomeForSettings(snapshot)) {
            String fingerprint = snapshot.fingerprint();
            if (fingerprint.equals(homeScanPreviousFingerprint)) homeScanTowardNext = false;
            if (homeScanAttempts < 8) {
                homeScanAttempts++;
                homeScanPreviousFingerprint = fingerprint;
                if (overlayView != null) {
                    overlayView.setRecognizedSpeech("설정 앱을 찾기 위해 홈 화면을 이동하고 있어요…");
                }
                boolean swiped = ClickAccessibilityService.swipeHomePage(homeScanTowardNext);
                pendingActionOccurredAtMs = System.currentTimeMillis();
                pendingActionType = swiped
                        ? AgentActionEvents.SCROLL_COMPLETED : AgentActionEvents.SCROLL_FAILED;
                if (swiped) return;
            }
            showSettingsOpenButton();
        } else if (hasSettingsTarget(snapshot)) {
            hideSettingsOpenButton();
        }
        String fingerprint = snapshot.fingerprint();
        if (!force && pendingVerifiedAction == null && firstTurnSent && confirmation == null && fingerprint.equals(lastSnapshotFingerprint)) return;
        AgentTurnContract.Request request = new AgentTurnContract.Request(
                agentSessionId, firstTurnSent ? null : initialGoal, snapshot, confirmation,
                firstTurnSent ? null : activeGoalMatch)
                .withSessionState(agentSessionVersion, agentEpisodeId,
                        confirmation == null ? null : agentConfirmationId);
        if (!firstTurnSent) {
            request.acceptedGoal = acceptedGoal;
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
        if ("rejected".equals(confirmation)) request.visionFailureCode = pendingVisionFailure;
        turnInFlight = true;
        updateRequestStatus(true, true);
        lastSnapshotFingerprint = fingerprint;
        // Consume the fresh-read request only when an observation is actually sent.
        forceNextObservation = false;
        pendingActionType = null;
        pendingVerifiedAction = null;
        pendingVisionSnapshot = null;
        pendingVisionFailure = null;
        pendingVisionScreen = null;
        agentGateway.send(request, new AgentTurnGateway.Callback() {
            private final int requestGeneration = executionGeneration;
            @Override public void onSuccess(AgentTurnContract.Response response) {
                if (requestGeneration != executionGeneration) return;
                turnInFlight = false;
                firstTurnSent = true;
                agentSessionVersion = response.sessionVersion;
                agentEpisodeId = response.episodeId;
                JSONObject confirmationState = response.metadata == null
                        ? null : response.metadata.optJSONObject("confirmation");
                agentConfirmationId = confirmationState == null
                        ? null : confirmationState.optString("id", null);
                confirmationExpiresAt = confirmationState == null ? 0 : confirmationState.optLong("expires_at", 0);
                lastSubmittedScreen = request.accessibilityTree;
                if (pendingControl != null) {
                    String control = pendingControl; pendingControl = null;
                    sendAgentControl(control); return;
                }
                applyTaskFlowResponse(response);
                ScreenSnapshot latest = LatestScreenSnapshot.get();
                if (latest != null && (forceNextObservation || pendingVerifiedAction != null || !latest.fingerprint().equals(lastSnapshotFingerprint))) {
                    sendAgentTurn(null, forceNextObservation);
                }
            }

            @Override public void onFailure(Throwable error) {
                if (requestGeneration != executionGeneration) return;
                turnInFlight = false;
                if (pendingControl != null) {
                    String control = pendingControl; pendingControl = null;
                    recoverControl(control); return;
                }
                failActiveQueueRequest("NETWORK_ERROR");
                android.util.Log.e("ClickAgent", "Agent turn failed", error);
                String userMessage = ErrorDetails.requestMessage(error);
                if (taskFlowView != null) taskFlowView.waiting(userMessage);
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

    private boolean hasConfirmedGoal() {
        if (acceptedGoal == null) return false;
        // Server preparation replaces the local review, but still needs explicit user acceptance.
        if (goalPreparation != null) {
            return goalPreparation.ready() && !goalPreparation.id().isEmpty()
                    && goalPreparation.id().equals(goalResolutionId);
        }
        return requirementReview != null && requirementReview.confirmed();
    }

    private boolean shouldScanHomeForSettings(ScreenSnapshot snapshot) {
        if (initialGoal == null || !isSettingsGoal(initialGoal) || !isHomeLauncher(snapshot.packageName)) {
            return false;
        }
        return !hasSettingsTarget(snapshot);
    }

    private void replacePausedQueue(String replacement, String selectedGoalType) {
        final int queuedGeneration = queueGeneration;
        queueGateway.interrupt("새 요청으로 교체", true, new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (queuedGeneration != queueGeneration || closing) return;
                abortActiveRequest();
                quickHelpSelections.clear();
                activeVoiceRequestId = null;
                awaitingReplacement = response.awaitingReplacement;
                enqueueNormalRequest(replacement, selectedGoalType);
            }
            @Override public void onFailure(Throwable error) {
                if (queuedGeneration == queueGeneration && !closing) showQueueError(error);
            }
        });
    }

    private void shutdownClick() {
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
            overlayView.setVisibility(android.view.View.GONE);
        }
        awaitingReplacement = true;
        final long generation = executionGeneration;
        // Hiding is local and immediate, even when queue cancellation is offline or slow.
        speechHandler.postDelayed(this::stopSelf, 5000L);
        if (queueGateway == null) {
            abortActiveRequest();
            stopSelf();
            return;
        }
        queueGateway.interrupt("클릭 종료", true, new VoiceQueueGateway.Callback() {
            @Override public void onSuccess(VoiceQueueGateway.Response response) {
                if (generation != executionGeneration) return;
                quickHelpSelections.clear();
                activeVoiceRequestId = null;
                stopSelf();
            }
            @Override public void onFailure(Throwable error) {
                if (generation == executionGeneration) stopSelf();
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
            String message = "Click을 계속 사용할게요.";
            if (overlayView != null) overlayView.setRecognizedSpeech(message);
            speak(message);
            return true;
        }
        String message = "Click을 종료할지 예 또는 아니오로 말씀해주세요.";
        if (overlayView != null) overlayView.setRecognizedSpeech(message);
        speak(message);
        return true;
    }

    private void askToExitAfterQueueCompleted() {
        awaitingExitConfirmation = true;
        String message = "요청한 작업이 모두 끝났어요. Click을 종료할까요?";
        if (overlayView != null) {
            overlayView.setRecognizedSpeech(message);
            if (overlayView.getMode() == ClickOverlayLayout.Mode.TEXT) {
                overlayView.addAssistantMessage(message);
            }
        }
        speak(message);
    }

    private void sendAgentControl(String commandType) {
        if (agentSessionId == null || agentGateway == null) return;
        if (turnInFlight) {
            pendingControl = commandType;
            agentPaused = true;
            actionTracker.clear(); pendingVerifiedAction = null;
            hideGuideOverlay(); hideConfirmation(); hideSettingsOpenButton();
            ensureTaskFlow(); taskFlowView.waiting("요청한 중단을 처리하고 있어요…");
            return;
        }
        if (agentEpisodeId == null) {
            if ("pause_task".equals(commandType)) {
                agentPaused = true; ensureTaskFlow(); taskFlowView.paused(); return;
            }
            if ("resume_task".equals(commandType)) {
                agentPaused = false;
                if (goalPreparation != null && acceptedGoal == null) showGoalPreparation();
                else if (requirementReview != null && !requirementReview.confirmed()) showRequirementReview();
                else if (goalSelection != null) {
                    taskFlowView.goals("원하는 목표를 골라주세요.", goalSelection.optJSONArray("candidates"));
                    placeTaskFlow(null, true);
                } else if (acceptedGoal == null) resolveGoal(initialGoal, null);
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
        executionGeneration++;
        if ("cancel_task".equals(commandType) || "pause_task".equals(commandType)) agentPaused = true;
        clearVisionState(); actionTracker.clear(); pendingVerifiedAction = null;
        hideConfirmation(); hideGuideOverlay(); hideSettingsOpenButton();
        if (resultTimeout != null) speechHandler.removeCallbacks(resultTimeout);
        sendFlowRequest(AgentTurnContract.Request.control(agentSessionId,
                agentSessionVersion, agentEpisodeId, commandType, snapshot));
    }

    private void sendAgentReplacement(String sourceText) {
        pendingReplacement = sourceText;
        sendAgentControl("cancel_task");
    }

    private boolean hasSettingsTarget(ScreenSnapshot snapshot) {
        if (snapshot == null) return false;
        for (AccessibilityNodeSnapshot node : snapshot.nodes) {
            if (containsSettingsLabel(node.text) || containsSettingsLabel(node.contentDescription)) return true;
        }
        return false;
    }

    private boolean isSettingsGoal(String goal) {
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

    private boolean isHomeLauncher(String packageName) {
        return packageName != null && (packageName.equals("com.sec.android.app.launcher")
                || packageName.equals("com.android.launcher3")
                || packageName.toLowerCase(java.util.Locale.ROOT).contains("launcher"));
    }

    private boolean containsSettingsLabel(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("설정") || normalized.equals("settings")
                || normalized.startsWith("설정,") || normalized.startsWith("settings,");
    }

    private void showSettingsOpenButton() {
        showRecoveryButton("설정 열기", Settings.ACTION_SETTINGS);
    }

    /** Renders the recovery button described by {@code render_policy.recovery_action}. */
    private void showRecoveryButton(JSONObject recoveryAction) {
        String intentAction = recoveryAction == null ? Settings.ACTION_SETTINGS
                : recoveryAction.optString("android_intent_action", Settings.ACTION_SETTINGS);
        showRecoveryButton("설정 열기", intentAction);
    }

    private void showRecoveryButton(String label, String intentAction) {
        if (windowManager == null || overlayView == null || settingsOpenButton != null) return;
        final String action = intentAction == null ? Settings.ACTION_SETTINGS : intentAction;
        settingsOpenButton = new Button(this);
        settingsOpenButton.setText(label);
        settingsOpenButton.setTextSize(17f);
        settingsOpenButton.setTextColor(android.graphics.Color.WHITE);
        settingsOpenButton.setAllCaps(false);
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(0xFF3478F6);
        background.setCornerRadius(dp(22));
        settingsOpenButton.setBackground(background);
        settingsOpenButton.setOnClickListener(view -> {
            ScreenSnapshot current = LatestScreenSnapshot.get();
            if (current != null) onGuidedViewClicked(current.packageName, SystemClock.uptimeMillis());
            hideSettingsOpenButton();
            // After a manual recovery the contract wants a fresh tree + manual_override.
            pendingActionOccurredAtMs = System.currentTimeMillis();
            pendingActionType = AgentActionEvents.MANUAL_OVERRIDE;
            try {
                startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException error) {
                startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            speechHandler.postDelayed(() -> sendAgentTurn(null, true), 1200L);
        });

        int width = dp(124);
        int height = dp(50);
        int clickCenterX = windowParams.x + Math.round(overlayView.getClickCenterX(overlayView.getMode()));
        int clickCenterY = windowParams.y + Math.round(overlayView.getClickCenterY(overlayView.getMode()));
        settingsOpenParams = new WindowManager.LayoutParams(
                width, height, overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        settingsOpenParams.gravity = Gravity.START | Gravity.TOP;
        settingsOpenParams.x = Math.max(dp(8), Math.min(clickCenterX - width / 2, screenWidth() - width - dp(8)));
        settingsOpenParams.y = Math.max(dp(8), Math.min(clickCenterY + dp(40), screenHeight() - height - dp(24)));
        windowManager.addView(settingsOpenButton, settingsOpenParams);
    }

    private void hideSettingsOpenButton() {
        if (settingsOpenButton != null && windowManager != null && settingsOpenButton.isAttachedToWindow()) {
            windowManager.removeView(settingsOpenButton);
        }
        settingsOpenButton = null;
        settingsOpenParams = null;
    }

    private void renderGuide(AgentTurnContract.Response response) {
        response.logDebug("ClickAgent");
        if (response.guideCommand == null) return;
        ensureTaskFlow();
        AgentTurnContract.GuideCommand command = response.guideCommand;
        ScreenSnapshot displayedSnapshot = null;
        if (!"done".equals(command.commandType) && !"blocked".equals(command.commandType)) {
            // Read the actual screen before any message, overlay, confirmation, or action binding.
            // A new observation ID alone is fine; its screen content must still match the request.
            displayedSnapshot = ClickAccessibilityService.freshSnapshot();
            if (lastSubmittedScreen == null || displayedSnapshot == null
                    || !lastSubmittedScreen.fingerprint().equals(displayedSnapshot.fingerprint())
                    || !GuideFreshness.stillValid(lastSubmittedScreen, displayedSnapshot,
                            command.targetNodeId, command.targetBounds,
                            command.renderPolicy != null && command.renderPolicy.optBoolean("vision_derived"))) {
                waitForFreshGuide();
                return;
            }
        }
        staleGuideDrops = 0;
        taskFlowView.progress(requirementReview != null ? requirementReview.summary() : "선택한 작업",
                response.metadata == null ? null : response.metadata.optJSONArray("checklist"),
                response.guideCommand.message, agentGateway.currentContract() != null
                        && agentGateway.currentContract().supports("request_result"));
        personalizationStateId = response.guideCommand.requiresUserAction ? response.stateId : null;
        lastGuideCommandId = command.guideCommandId;
        lastTargetNodeId = command.targetNodeId;
        lastTargetBounds = command.targetBounds;
        if (command.requiresUserAction && !"confirmation".equals(command.commandType)) {
            actionTracker.bind(agentSessionId, agentEpisodeId, command.guideCommandId, command.actionType,
                    command.targetNodeId, command.targetBounds, displayedSnapshot, System.currentTimeMillis());
        } else actionTracker.clear();
        placeTaskFlow(command.targetBounds, false);
        if (command.requiresUserAction && "tap".equals(command.actionType)
                && displayedSnapshot != null && displayedSnapshot.fingerprint().equals(lastSnapshotFingerprint)) {
            guideStepTimer.start(agentSessionId, response.stateId, response.step,
                    displayedSnapshot.packageName,
                    SystemClock.uptimeMillis());
        } else {
            guideStepTimer.clear();
        }

        if (overlayView != null && command.message != null && !command.message.isEmpty()) {
            overlayView.setRecognizedSpeech(command.message);
            speak(command.message);
        }
        // BE-driven fallback / recovery policies are checked first: vision_fallback in
        // particular rides on a command_type of "confirmation".
        JSONObject recoveryAction = command.recoveryAction();
        JSONObject visionFallback = command.visionFallback();
        JSONObject fallbackAction = command.fallbackAction();
        if (recoveryAction != null) {
            hideConfirmation();
            hideGuideOverlay();
            showRecoveryButton(recoveryAction);
            return;
        }
        if (visionFallback != null) {
            hideConfirmation();
            hideGuideOverlay();
            handleVisionFallback(command, visionFallback);
            return;
        }
        if (ObservationRecovery.awaitsUser(command.renderPolicy)) {
            hideConfirmation(); hideGuideOverlay(); hideSettingsOpenButton();
            actionTracker.clear();
            final int generation = executionGeneration;
            taskFlowView.observationRecovery(requirementReview != null ? requirementReview.summary() : "선택한 작업", command.message, () -> {
                if (generation != executionGeneration || agentPaused || turnInFlight
                        || agentSessionId == null || pendingControl != null) return;
                freshObservationRetries = 0;
                pendingActionType = AgentActionEvents.MANUAL_OVERRIDE;
                pendingActionOccurredAtMs = System.currentTimeMillis();
                forceNextObservation = true;
                ClickAccessibilityService.requestFreshSnapshot();
            });
            return;
        }
        if (fallbackAction != null
                && "request_fresh_observation".equals(fallbackAction.optString("type"))) {
            hideConfirmation();
            hideGuideOverlay();
            handleFreshObservationRequest(command, response.stateId);
            return;
        }

        if ("confirmation".equals(command.commandType)) {
            hideGuideOverlay();
            showConfirmation(command.message);
            return;
        }
        hideConfirmation();
        if ("done".equals(command.commandType) || "blocked".equals(command.commandType)
                || "COMPLETED".equals(response.taskStatus) || "BLOCKED".equals(response.taskStatus)) {
            hideGuideOverlay();
            hideSettingsOpenButton();
            boolean completed = "done".equals(command.commandType)
                    || "COMPLETED".equals(response.taskStatus);
            finishActiveQueueRequest(completed ? null : "AGENT_ERROR");
            return;
        }

        freshObservationRetries = 0;
        hideSettingsOpenButton();
        showGuideOverlay(command.targetBounds, command.actionType,
                GuideDragDirection.resolve(command.renderPolicy, response.metadata));
        if ("scroll".equals(command.actionType)) {
            executeScroll(command);
        } else {
            scrollExecutions = 0;
        }
    }

    private void waitForFreshGuide() {
        actionTracker.clear();
        guideStepTimer.clear();
        lastGuideCommandId = lastTargetNodeId = null;
        lastTargetBounds = null;
        personalizationStateId = null;
        agentConfirmationId = null;
        confirmationExpiresAt = 0;
        clearVisionState();
        hideGuideOverlay();
        hideConfirmation();
        hideSettingsOpenButton();
        pendingSpeech = null;
        activeUtteranceId = null;
        if (textToSpeech != null) textToSpeech.stop();
        if (++staleGuideDrops >= 4) {
            forceNextObservation = false;
            final int generation = executionGeneration;
            String message = "화면이 계속 바뀌고 있어요. 화면을 멈춘 뒤 다시 확인을 눌러주세요.";
            lastSpokenMessage = message;
            taskFlowView.observationRecovery(message, () -> {
                if (generation != executionGeneration || agentPaused || turnInFlight
                        || agentSessionId == null || pendingControl != null) return;
                staleGuideDrops = 0;
                forceNextObservation = true;
                ClickAccessibilityService.requestFreshSnapshot();
            });
            placeTaskFlow(null, false);
            if (overlayView != null) overlayView.setRecognizedSpeech(message);
            speak(message);
            return;
        }
        String message = "현재 화면을 다시 확인하고 있어요. 잠시 기다려 주세요.";
        lastSpokenMessage = message;
        taskFlowView.waiting(message);
        placeTaskFlow(null, false);
        if (overlayView != null) overlayView.setRecognizedSpeech(message);
        forceNextObservation = true;
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
        showVisionConsent(command.message);
    }

    private void handleFreshObservationRequest(AgentTurnContract.GuideCommand command, String stateId) {
        ScreenSnapshot snapshot = lastSubmittedScreen;
        String recovery = com.click.service.overlay.ObservationRecovery.message(stateId,
                command.fallbackAction(), snapshot);
        if (recovery != null || ++freshObservationRetries > 4) {
            final int generation = executionGeneration;
            String message = recovery != null ? recovery
                    : "현재 화면에서 다음 항목을 찾지 못했어요. 화면을 확인한 뒤 다시 확인을 눌러주세요.";
            taskFlowView.observationRecovery(message, () -> {
                if (generation != executionGeneration || agentPaused || turnInFlight || agentSessionId == null) return;
                freshObservationRetries = 0;
                forceNextObservation = true;
                ClickAccessibilityService.requestFreshSnapshot();
            });
            if (overlayView != null) overlayView.setRecognizedSpeech(message);
            speak(message);
            return;
        }
        pendingActionOccurredAtMs = System.currentTimeMillis();
        pendingActionType = AgentActionEvents.OBSERVE_ONLY;
        final int generation = executionGeneration;
        speechHandler.postDelayed(() -> {
            if (generation != executionGeneration || agentPaused || pendingControl != null
                    || !java.util.Objects.equals(command.guideCommandId, lastGuideCommandId)) return;
            forceNextObservation = true;
            ClickAccessibilityService.requestFreshSnapshot();
        }, 700L);
    }

    private void executeScroll(AgentTurnContract.GuideCommand command) {
        if (++scrollExecutions > 4) return;
        String direction = command.renderPolicy == null
                ? "down" : command.renderPolicy.optString("direction", "down");
        boolean forward = !"up".equalsIgnoreCase(direction) && !"backward".equalsIgnoreCase(direction);
        boolean performed = ClickAccessibilityService.performScroll(forward);
        pendingActionOccurredAtMs = System.currentTimeMillis();
        pendingActionType = performed
                ? AgentActionEvents.SCROLL_COMPLETED : AgentActionEvents.SCROLL_FAILED;
        speechHandler.postDelayed(() -> sendAgentTurn(null, true), performed ? 650L : 400L);
    }

    private void showVisionConsent(String message) {
        hideConfirmation();
        if (windowManager == null) return;
        final int generation = executionGeneration;
        final String consentId = agentConfirmationId;
        final ScreenSnapshot consentScreen = lastSubmittedScreen;
        confirmationView = new ConfirmationOverlayView(this, message, accepted -> {
            hideConfirmation();
            visionConsentRequested = false;
            if (generation != executionGeneration || !consentId.equals(agentConfirmationId)) return;
            if (!accepted || System.currentTimeMillis() >= confirmationExpiresAt * 1000L) {
                sendAgentTurn("rejected", true);
                return;
            }
            visionCaptureInFlight = true;
            hideGuideOverlay();
            hideSettingsOpenButton();
            updateFloatingVisibility();
            // Wait for our consent/guide overlays to leave the display before capturing once.
            speechHandler.postDelayed(() -> {
                if (generation != executionGeneration || !visionCaptureInFlight) return;
                ClickAccessibilityService.captureVision(consentScreen, new OnDeviceVision.Callback() {
                    @Override public void onSuccess(ScreenSnapshot screen, JSONObject vision) {
                        if (generation != executionGeneration || !visionCaptureInFlight) return;
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
                        if (generation != executionGeneration || !visionCaptureInFlight) return;
                        clearVisionState();
                        pendingVisionFailure = code;
                        sendAgentTurn("rejected", true);
                        Toast.makeText(ClickOverlayService.this, error, Toast.LENGTH_LONG).show();
                    }
                });
            }, 250L);
        });
        confirmationParams = new WindowManager.LayoutParams(
                screenWidth() - dp(28), Math.min(dp(300), screenHeight() - dp(56)), overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        confirmationParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        confirmationParams.y = dp(28);
        windowManager.addView(confirmationView, confirmationParams);
        speechHandler.postDelayed(() -> {
            if (generation == executionGeneration && consentId.equals(agentConfirmationId)
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
        pendingVisionSnapshot = null;
        pendingVisionFailure = null;
        pendingVisionScreen = null;
        updateFloatingVisibility();
    }

    private String userMessageForAgentError(Throwable error) {
        if (error instanceof SocketTimeoutException) {
            return "서버 연결 또는 응답 대기 시간이 초과됐습니다. "
                    + "API 주소(" + BuildConfig.CLICK_API_BASE_URL + ")에 연결 가능한지, "
                    + "백엔드가 실행 중인지 확인해주세요. 연결 제한은 6초, 응답 제한은 15초입니다.";
        }
        if (error instanceof UnknownHostException) {
            return "서버 주소를 찾지 못했습니다: " + BuildConfig.CLICK_API_BASE_URL
                    + ". 주소와 Wi-Fi/DNS 연결을 확인해주세요.";
        }
        if (error instanceof ConnectException) {
            return "서버가 연결을 거부했습니다: " + BuildConfig.CLICK_API_BASE_URL
                    + ". 백엔드 실행 여부와 포트, 같은 네트워크인지 확인해주세요.";
        }
        if (error instanceof org.json.JSONException) {
            return "서버 응답 형식이 올바르지 않습니다. 백엔드 응답 규격을 확인해주세요.";
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("AUTH_REQUIRED") || message.startsWith("API 401")) {
            return "로그인이 만료되었습니다. 앱에서 다시 로그인해주세요.";
        }
        if (message.contains("QUEUE_BUSY") || message.contains("QUEUE_CONFLICT")) {
            return "요청 대기열을 다른 작업이 갱신 중입니다. 잠시 후 같은 요청을 다시 보내주세요.";
        }
        if (message.startsWith("API 409")) {
            return "요청 대기열 상태가 변경되어 처리하지 못했습니다. 오류 코드: " + message;
        }
        if (message.startsWith("API 429")) {
            return "요청 대기열이 가득 찼거나 호출이 너무 많습니다. 잠시 후 다시 시도해주세요.";
        }
        if (message.startsWith("API 422")) {
            return "서버가 요청 형식을 거부했습니다. 상세 응답: " + message;
        }
        if (message.startsWith("Agent API 400")) return "요청 형식이 올바르지 않습니다.";
        if (message.startsWith("Agent API 401") || message.startsWith("Agent API 403")) {
            return "서버 인증 또는 접근 권한을 확인해주세요.";
        }
        if (message.startsWith("Agent API 404")) return "에이전트 API 경로를 찾지 못했습니다.";
        if (message.startsWith("Agent API 409")) return "요청 순서가 맞지 않습니다. 새 요청으로 다시 시작해주세요.";
        if (message.startsWith("Agent API 422")) return "현재 화면 정보에 잘못된 값이 있어 처리하지 못했습니다.";
        if (message.startsWith("Agent API 429")) return "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.";
        if (message.startsWith("Agent API 500") || message.startsWith("Agent API 502")
                || message.startsWith("Agent API 503")) {
            return "백엔드 또는 에이전트 처리 중 오류가 발생했습니다.";
        }
        if (message.contains("unexpected end of stream") || message.contains("EOFException")) {
            return "서버가 응답을 보내는 중 연결을 종료했습니다. 백엔드 로그를 확인해주세요.";
        }
        if (error instanceof java.io.IOException) {
            return "네트워크 통신 중 오류가 발생했습니다. 연결 상태를 확인해주세요.";
        }
        return "요청 처리 중 알 수 없는 오류가 발생했습니다.";
    }

    private void updateNarration(String id, boolean speaking, boolean failed) {
        speechHandler.post(() -> {
            if (closing || id == null || !id.equals(activeUtteranceId)) return;
            if (taskFlowView != null) taskFlowView.setNarrationState(speaking, failed);
        });
    }

    private void silenceVoiceReply() {
        voiceReplyEnabled = false;
        pendingSpeech = null; activeUtteranceId = null;
        if (textToSpeech != null) textToSpeech.stop();
        if (taskFlowView != null) taskFlowView.setNarrationState(false, ttsUnavailable);
    }

    private void speak(String message) {
        if (message == null || message.trim().isEmpty()) return;
        lastSpokenMessage = message.trim();
        if (overlayView != null && overlayView.getMode() == ClickOverlayLayout.Mode.TEXT) {
            overlayView.addAssistantMessage(message);
            return;
        }
        if (!voiceReplyEnabled || acceptingVoiceCommand || closing) return;
        android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        boolean muted = audio != null && audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0;
        if (ttsUnavailable || muted) {
            if (taskFlowView != null) taskFlowView.setNarrationState(false, true);
            return;
        }
        if (!ttsReady || textToSpeech == null) {
            pendingSpeech = message;
            return;
        }
        stopListening();
        activeUtteranceId = "click_guide_" + (++narrationSequence);
        int result = textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
        if (taskFlowView != null) taskFlowView.setNarrationState(result != TextToSpeech.ERROR, result == TextToSpeech.ERROR);
    }

    private void showGuideOverlay(int[] bounds, String actionType, int dragDirection) {
        if (bounds == null || windowManager == null) {
            hideGuideOverlay();
            return;
        }
        if (guideOverlayView == null) guideOverlayView = new GuideOverlayView(this);
        guideOverlayView.showTarget(bounds, actionType, dragDirection);
        if (guideOverlayView.isAttachedToWindow()) return;
        guideOverlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        guideOverlayParams.gravity = Gravity.START | Gravity.TOP;
        windowManager.addView(guideOverlayView, guideOverlayParams);
    }

    private void hideGuideOverlay() {
        if (windowManager != null && guideOverlayView != null && guideOverlayView.isAttachedToWindow()) {
            windowManager.removeView(guideOverlayView);
        }
    }

    private void showConfirmation(String message) {
        hideConfirmation();
        if (windowManager == null) return;
        confirmationView = new ConfirmationOverlayView(this, message, accepted -> {
            hideConfirmation();
            sendAgentTurn(accepted ? "accepted" : "rejected", true);
        });
        confirmationParams = new WindowManager.LayoutParams(
                screenWidth() - dp(28), dp(176), overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        confirmationParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        confirmationParams.y = dp(28);
        windowManager.addView(confirmationView, confirmationParams);
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
