package com.click.service.overlay;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import com.click.designpreview.TutorialNarrator;
import android.view.WindowManager;
import com.click.data.remote.AgentServerContract;
import com.click.data.remote.AgentActionEvents;
import com.click.data.remote.AgentTurnContract;
import com.click.data.remote.AgentTurnGateway;
import com.click.data.local.QuickHelpSelectionStore;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.NavigationState;
import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.RequirementReview;
import com.click.domain.goal.QuickHelpTask;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.accessibility.LatestScreenSnapshot;
import com.click.ui.overlay.GuideOverlayView;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.TaskFlowOverlayView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the real resolution -> confirmation -> delayed send path, not just its DTOs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ClickOverlayServiceExecutionTest {
    private static final String SOURCE = "휴대폰 전체 글자 크기 키워줘";
    private static final String RESOLUTION = "prepared-resolution";
    private ClickOverlayService service;
    private AgentTurnGateway gateway;
    private AgentServerContract contract;
    private GoalCatalogClassifier catalog;
    private JSONObject goal;
    private JSONObject preparation;
    private final List<JSONObject> sent = new ArrayList<>();
    private final List<AgentTurnContract.Request> requests = new ArrayList<>();
    private final List<AgentTurnGateway.Callback> callbacks = new ArrayList<>();
    private MockedStatic<ClickAccessibilityService> accessibility;
    private MockedStatic<LatestScreenSnapshot> snapshots;
    private ScreenSnapshot screen;
    private ScreenSnapshot currentScreen;
    private ScreenSnapshot cachedScreen;

    @Before public void setup() throws Exception {
        // Skip onCreate: these tests use a controlled gateway and no real overlay or microphone.
        service = Robolectric.buildService(ClickOverlayService.class).get();
        gateway = mock(AgentTurnGateway.class);
        contract = AgentServerContract.fromOpenApi(resource("/openapi.json"));
        assertTrue("Use the current backend's advertised preparation contract", contract.goalPreparation);
        try (java.io.InputStream in = service.getAssets().open("mvp_goals.json")) {
            catalog = GoalCatalogClassifier.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        goal = catalog.executableGoal("settings.font_size.increase").toTypedGoalJson();
        preparation = new JSONObject().put("version", "goal-inputs.v1").put("id", RESOLUTION)
                .put("source_text", SOURCE).put("status", "ready")
                .put("missing_fields", new JSONArray()).put("values", new JSONObject()
                        .put("scope", "system").put("direction", "increase"))
                .put("graph_reference", new JSONObject().put("task_id", "MVP-01").put("sha256", "test-graph"));
        set("agentGateway", gateway);
        set("agentSessionId", "frontend-session");
        set("goalClassifier", catalog);
        set("speechHandler", new Handler(Looper.getMainLooper()));
        set("taskFlowView", mock(TaskFlowOverlayView.class));
        set("taskFlowParams", new WindowManager.LayoutParams());
        when(gateway.currentContract()).thenAnswer(call -> contract);
        doAnswer(call -> {
            ((AgentTurnGateway.ContractCallback) call.getArgument(0)).onSuccess(contract);
            return null;
        }).when(gateway).loadContract(any());
        doAnswer(call -> {
            AgentTurnContract.Request request = call.getArgument(0);
            requests.add(request);
            callbacks.add(call.getArgument(1));
            // The real gateway applies negotiated capabilities immediately before serialization.
            request.contractVersion = contract.contractVersion;
            request.interactiveWorkflow = contract.interactiveWorkflow;
            sent.add(new JSONObject(request.toJson()));
            if ("resolve_goal".equals(request.commandType())) {
                JSONObject response = new JSONObject().put("request_id", request.requestId)
                        .put("session_id", request.sessionId).put("session_version", 0)
                        .put("task_status", "PENDING").put("response_type", "goal_resolution")
                        .put("preparation", preparation).put("goal", goal).put("message", "이 내용으로 시작할까요?");
                ((AgentTurnGateway.Callback) call.getArgument(1)).onSuccess(
                        new AgentTurnContract.Response(response.toString()));
            }
            return null;
        }).when(gateway).send(any(), any());
        AccessibilityNodeSnapshot display = new AccessibilityNodeSnapshot("display", null, true,
                "com.android.settings", "디스플레이", null, null, "android.widget.TextView", null,
                new int[]{20, 100, 900, 200}, true, true, false, false, false, null, 42);
        AccessibilityNodeSnapshot title = new AccessibilityNodeSnapshot("title", null, true,
                "com.android.settings", "설정", null, null, "android.widget.TextView", null,
                new int[]{20, 20, 500, 70}, false, true, false, false, false, null, 42);
        screen = new ScreenSnapshot("com.android.settings", Instant.now().toString(),
                1080, 2400, List.of(title, display), 42);
        currentScreen = screen;
        snapshots = mockStatic(LatestScreenSnapshot.class);
        snapshots.when(LatestScreenSnapshot::get).thenAnswer(call -> cachedScreen);
        accessibility = mockStatic(ClickAccessibilityService.class);
        accessibility.when(ClickAccessibilityService::accessibilityWindowManager).thenReturn(mock(WindowManager.class));
        accessibility.when(ClickAccessibilityService::freshSnapshot).thenAnswer(call -> {
            if (currentScreen != null) cachedScreen = currentScreen;
            return currentScreen;
        });
    }

    @After public void cleanup() {
        if (accessibility != null) accessibility.close();
        if (snapshots != null) snapshots.close();
    }

    @Test public void microphoneReleaseFinishesRecordingWithoutCancellingRecognition() throws Exception {
        SpeechInput speech = mock(SpeechInput.class);
        ClickOverlayLayout overlay = mock(ClickOverlayLayout.class);
        when(overlay.getMode()).thenReturn(ClickOverlayLayout.Mode.VOICE);
        set("speechInput", speech); set("overlayView", overlay);
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        service.onMicrophonePressed();
        org.mockito.ArgumentCaptor<SpeechInput.Callback> callback = org.mockito.ArgumentCaptor.forClass(SpeechInput.Callback.class);
        verify(speech).start(callback.capture());
        assertTrue((boolean) get("listening"));
        service.onMicrophoneReleased(); service.onMicrophoneReleased();
        verify(speech).finish();
        verify(speech, never()).cancel();
        assertFalse((boolean) get("listening"));
        assertTrue((boolean) get("speechProcessing"));
        assertTrue((boolean) get("acceptingVoiceCommand"));
        service.onMicrophonePressed();
        verify(speech, times(1)).start(any());
        callback.getValue().onFailure(new java.io.IOException("NO_SPEECH"));
        assertFalse((boolean) get("acceptingVoiceCommand"));
        verify(overlay).setVoiceInputProcessing(false);
    }

    @Test public void privacyBlockPausesScreenRequestsAndShowsManualGuidance() throws Exception {
        resolve();
        confirm();
        assertTrue(sent.size() >= 2);
        int count = sent.size();
        callbacks.get(callbacks.size() - 1).onFailure(new com.click.data.snapshot.SettingsScreenPrivacy.Blocked());
        assertTrue((boolean) get("agentPaused"));
        assertFalse((boolean) get("turnInFlight"));
        verify((TaskFlowOverlayView) get("taskFlowView")).failed(contains("직접 진행"));
        send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(count, sent.size());
    }

    @Test public void cancelledMicrophoneIgnoresLateRecognitionResult() throws Exception {
        SpeechInput speech = mock(SpeechInput.class);
        ClickOverlayLayout overlay = mock(ClickOverlayLayout.class);
        when(overlay.getMode()).thenReturn(ClickOverlayLayout.Mode.VOICE);
        set("speechInput", speech); set("overlayView", overlay);
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        service.onMicrophonePressed();
        org.mockito.ArgumentCaptor<SpeechInput.Callback> callback = org.mockito.ArgumentCaptor.forClass(SpeechInput.Callback.class);
        verify(speech).start(callback.capture());
        service.onMicrophoneCancelled(); service.onMicrophoneReleased();
        callback.getValue().onSuccess("글자 크기를 키워줘");
        verify(speech).cancel();
        verify(speech, never()).finish();
        verify(overlay, never()).collapse();
        assertFalse((boolean) get("acceptingVoiceCommand"));
    }

    @Test public void terminalWarningStopsLocallyAndEndStillWorksWithInactiveRequestFlag() throws Exception {
        prepareGuideResponse();
        com.click.data.remote.VoiceQueueGateway queue = mock(com.click.data.remote.VoiceQueueGateway.class);
        set("queueGateway", queue); set("activeVoiceRequestId", "blocked-request");
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        applyFlow(new JSONObject().put("task_status", "BLOCKED").put("message", "현재 화면을 확인하지 못했어요."));
        assertTrue((boolean) get("agentPaused"));
        assertFalse((boolean) get("requestActive"));
        assertFalse((boolean) get("turnInFlight"));
        verify(panel).failed("현재 화면을 확인하지 못했어요.");
        verify(queue, never()).claim(any());
        verify(queue, never()).fail(anyString(), anyString(), any());
        service.onStopRequested();
        assertNull(get("taskFlowView")); assertNull(get("agentSessionId"));
        verify(queue).interrupt(eq("멈춰"), any());
        int count = requests.size();
        deliver(1, "highlight", "tap", null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(count, requests.size());
        assertNull(get("taskFlowView"));
        verify((GuideOverlayView) get("guideOverlayView"), never()).showTarget(any(), any(), anyInt());
    }

    @Test public void endClearsAnOrphanedPanelWithoutSessionQueueOrActiveFlag() throws Exception {
        set("agentSessionId", null); set("requestActive", false);
        Runnable retry = mock(Runnable.class);
        ((Handler) get("speechHandler")).postDelayed(retry, 750);
        service.onStopRequested();
        assertNull(get("taskFlowView"));
        assertNull(get("agentSessionId"));
        assertFalse((boolean) get("turnInFlight"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        verify(retry, never()).run();
    }

    @Test public void pauseShowsPausedImmediatelyAndCancelsRetriesBeforeTheReply() throws Exception {
        prepareGuideResponse();
        Runnable retry = mock(Runnable.class);
        set("missingScreenRetry", retry);
        ((Handler) get("speechHandler")).postDelayed(retry, 750);
        control("pause_task");
        assertTrue((boolean) get("agentPaused"));
        assertEquals("pause_task", get("pendingControl"));
        assertNull(get("missingScreenRetry"));
        verify((TaskFlowOverlayView) get("taskFlowView")).paused();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        verify(retry, never()).run();
        assertEquals(2, requests.size());
        deliver(1, "highlight", "tap", null);
        assertEquals("pause_task", requests.get(2).commandType());
        verify((GuideOverlayView) get("guideOverlayView"), never()).showTarget(any(), any(), anyInt());
    }

    @Test public void immediateResumeWaitsForThePauseToReachTheServer() throws Exception {
        prepareGuideResponse();
        control("pause_task"); control("resume_task");
        assertEquals("pause_task", get("pendingControl"));
        assertTrue((boolean) get("resumeAfterPause"));
        deliver(1, "highlight", "tap", null);
        assertEquals("pause_task", requests.get(2).commandType());
        callbacks.get(2).onSuccess(sessionState(8, "episode", "PAUSED", "MVP-01", false));
        assertEquals("resume_task", requests.get(3).commandType());
        assertEquals(8, requests.get(3).expectedVersion.intValue());
        assertFalse((boolean) get("resumeAfterPause"));
    }

    @Test public void queueClaimCannotStartWorkDuringAnEarlyPause() throws Exception {
        set("agentSessionId", null);
        com.click.data.remote.VoiceQueueGateway queue = mock(com.click.data.remote.VoiceQueueGateway.class);
        set("queueGateway", queue);
        invoke("claimNextQueuedRequest");
        org.mockito.ArgumentCaptor<com.click.data.remote.VoiceQueueGateway.Callback> callback =
                org.mockito.ArgumentCaptor.forClass(com.click.data.remote.VoiceQueueGateway.Callback.class);
        verify(queue).claim(callback.capture());
        control("pause_task");
        callback.getValue().onSuccess(queueResponse("pending-request", "pending-session", true));
        assertNull(get("agentSessionId"));
        assertEquals(0, requests.size());
        control("resume_task");
        assertFalse((boolean) get("agentPaused"));
        verify(queue, times(2)).claim(any());
    }

    @Test public void offlinePauseRecoveryKeepsThePausedControls() throws Exception {
        prepareGuideResponse(); deliver(1, "highlight", "tap", null);
        control("pause_task");
        callbacks.get(2).onFailure(new java.io.IOException("offline"));
        org.mockito.ArgumentCaptor<AgentTurnGateway.Callback> sync =
                org.mockito.ArgumentCaptor.forClass(AgentTurnGateway.Callback.class);
        verify(gateway).synchronize(eq("frontend-session"), sync.capture());
        sync.getValue().onFailure(new java.io.IOException("offline"));
        assertTrue((boolean) get("agentPaused"));
        assertFalse((boolean) get("turnInFlight"));
        verify((TaskFlowOverlayView) get("taskFlowView"), atLeastOnce()).paused();
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).failed(anyString());
    }

    @Test public void replayUsesTheExplicitReplayPathAndServiceClosesItsHandle() throws Exception {
        prepareGuideResponse();
        Method speak = ClickOverlayService.class.getDeclaredMethod("speak", String.class); speak.setAccessible(true);
        speak.invoke(service, "디스플레이를 눌러주세요.");
        TutorialNarrator narrator = (TutorialNarrator) get("narrator");
        verify(narrator).speak("디스플레이를 눌러주세요.");
        Method replay = ClickOverlayService.class.getDeclaredMethod("repeatLastGuide"); replay.setAccessible(true);
        replay.invoke(service); verify(narrator).replay("디스플레이를 눌러주세요.");
        service.onDestroy(); verify(narrator).close(); assertNull(get("narrator"));
    }

    @Test public void minimizingTheTaskKeepsSpeechEnabledForFollowingSteps() throws Exception {
        prepareGuideResponse();
        Method collapse = ClickOverlayService.class.getDeclaredMethod("collapseTaskFlow"); collapse.setAccessible(true);
        collapse.invoke(service);
        assertTrue((boolean) get("voiceReplyEnabled"));
        verify((TutorialNarrator) get("narrator"), never()).stop();
        Method speak = ClickOverlayService.class.getDeclaredMethod("speak", String.class); speak.setAccessible(true);
        speak.invoke(service, "다음 항목을 눌러주세요.");
        verify((TutorialNarrator) get("narrator")).speak("다음 항목을 눌러주세요.");
    }

    @Test public void confirmationStopsTheOldQuestionBeforeSendingTheTask() throws Exception {
        resolve(); set("narrator", mock(TutorialNarrator.class)); confirm();
        verify((TutorialNarrator) get("narrator")).stop();
    }

    @Test public void confirmedRequestRetriesMissingScreenWithoutWaitingForAnAccessibilityEvent() throws Exception {
        resolve(); currentScreen = null; confirm();
        assertEquals(1, requests.size()); // Only goal resolution; no fabricated observation is sent.
        assertFalse((boolean) get("turnInFlight"));
        verify((TaskFlowOverlayView) get("taskFlowView")).waiting("안내를 시작할 화면을 확인하고 있어요…");
        currentScreen = screen;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(750));
        assertEquals(2, requests.size());
        assertSame(screen, requests.get(1).accessibilityTree);
        assertNull(get("missingScreenRetry"));
    }

    @Test public void incompleteSettingsRetriesLocallyBeforeConsumingActionEvidence() throws Exception {
        resolve();
        when(gateway.needsFreshObservation(any())).thenReturn(true);
        JSONObject event = new JSONObject().put("action_type", AgentActionEvents.DRAG_COMPLETED);
        set("pendingVerifiedAction", event);
        confirm();
        assertEquals(1, requests.size());
        assertSame(event, get("pendingVerifiedAction"));
        assertFalse((boolean) get("agentPaused"));
        when(gateway.needsFreshObservation(any())).thenReturn(false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(750));
        assertEquals(2, requests.size());
        assertNull(get("missingScreenRetry"));
        assertSame(event, requests.get(1).userActionEvent);
    }

    @Test public void permanentlyMissingScreenOffersRecoveryAndExplicitRetryKeepsConfirmedGoal() throws Exception {
        accessibility.when(ClickAccessibilityService::accessibilityWindowManager).thenReturn(null);
        resolve(); currentScreen = null; confirm();
        TaskFlowOverlayView view = (TaskFlowOverlayView) get("taskFlowView");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        org.mockito.ArgumentCaptor<Runnable> retry = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        org.mockito.ArgumentCaptor<Runnable> guide = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(view).accessibilityRecovery(contains("화면 읽기 연결이 끊겼어요"), guide.capture(), retry.capture());
        Object session = get("agentSessionId");
        guide.getValue().run();
        Intent opened = shadowOf(service).getNextStartedActivity();
        assertEquals(com.click.designpreview.PermissionGuideActivity.class.getName(), opened.getComponent().getClassName());
        assertEquals(session, get("agentSessionId"));
        assertEquals(1, requests.size());
        assertFalse((boolean) get("turnInFlight"));
        send(null);
        verify(view, times(1)).accessibilityRecovery(anyString(), any(), any());
        currentScreen = screen;
        retry.getValue().run();
        assertEquals(2, requests.size());
        assertEquals(RESOLUTION, requests.get(1).goalResolutionId);
        assertNotNull(requests.get(1).acceptedGoal);
    }

    @Test public void missingScreenRetryCannotStartAReplacedSession() throws Exception {
        resolve(); currentScreen = null; confirm();
        set("executionGeneration", ((Integer) get("executionGeneration")) + 1);
        currentScreen = screen;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertEquals(1, requests.size());
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).observationRecovery(any(), anyString(), any());
    }

    @Test public void returningScreenCancelsScheduledRetryInsteadOfSendingTwice() throws Exception {
        resolve(); currentScreen = null; confirm();
        currentScreen = screen; send(null);
        assertEquals(2, requests.size());
        set("turnInFlight", false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertEquals(2, requests.size());
    }

    @Test public void staleDragReleaseCannotMoveOrCloseClickWithoutActiveDrag() throws Exception {
        ClickOverlayLayout logo = mock(ClickOverlayLayout.class);
        set("overlayView", logo);
        WindowManager windows = mock(WindowManager.class);
        set("windowManager", windows);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.x = 100; params.y = 300;
        set("windowParams", params);
        set("dragTargetX", 400f); set("dragTargetY", 800f);
        set("overTrashTarget", true);
        service.onDragEnded();
        assertEquals(false, get("closing"));
        assertEquals(100, params.x); assertEquals(300, params.y);
        verify(windows, never()).updateViewLayout(any(), any());
    }

    @Test public void minimizingTaskCancelsQueuedDragAndIgnoresItsLateRelease() throws Exception {
        ClickOverlayLayout logo = mock(ClickOverlayLayout.class);
        when(logo.getMode()).thenReturn(ClickOverlayLayout.Mode.COLLAPSED);
        set("overlayView", logo);
        WindowManager windows = mock(WindowManager.class);
        set("windowManager", windows);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.x = 100; params.y = 300;
        set("windowParams", params);
        set("dragTargetX", 400f); set("dragTargetY", 800f);
        set("dragInProgress", true); set("overTrashTarget", true);
        invoke("collapseTaskFlow");
        ((Runnable) get("dragFrame")).run();
        service.onDragBy(40, 80);
        service.onDragEnded();
        assertEquals(false, get("closing"));
        assertEquals(false, get("dragInProgress"));
        assertEquals(100, params.x); assertEquals(300, params.y);
        verify(logo).setVisibility(android.view.View.VISIBLE);
    }

    @Test public void minimizingAndReopeningTaskRepeatedlyKeepsClickAlive() throws Exception {
        ClickOverlayLayout logo = mock(ClickOverlayLayout.class);
        when(logo.getMode()).thenReturn(ClickOverlayLayout.Mode.COLLAPSED);
        set("overlayView", logo);
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        when(panel.hasCompactContent()).thenReturn(true);
        for (int i = 0; i < 5; i++) {
            clearInvocations(logo, panel);
            invoke("collapseTaskFlow");
            verify(logo).setVisibility(android.view.View.VISIBLE);
            verify(panel).setMinimizedProgress(true);
            assertEquals(false, get("closing"));
            assertTrue(service.onOpenRequested());
            assertEquals(false, get("taskFlowCollapsed"));
            verify(panel).setMinimizedProgress(false);
        }
        invoke("collapseTaskFlow");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));
        assertEquals(false, get("closing"));
        assertSame(panel, get("taskFlowView"));
    }

    @Test public void closingInputRestoresOnlyLatestDeferredGuidanceOnce() throws Exception {
        prepareGuideResponse();
        ClickOverlayLayout input = (ClickOverlayLayout) get("overlayView");
        TutorialNarrator narrator = (TutorialNarrator) get("narrator");
        when(input.getMode()).thenReturn(ClickOverlayLayout.Mode.TEXT);
        Method speak = ClickOverlayService.class.getDeclaredMethod("speak", String.class); speak.setAccessible(true);
        speak.invoke(service, "이전 화면 안내");
        speak.invoke(service, "슬라이더를 오른쪽으로 움직여 주세요.");
        verify(narrator, never()).speak(anyString());
        when(((TaskFlowOverlayView) get("taskFlowView")).guidanceMessage()).thenReturn("슬라이더를 오른쪽으로 움직여 주세요.");
        when(input.getMode()).thenReturn(ClickOverlayLayout.Mode.COLLAPSED);
        invoke("restorePanelNarration"); invoke("restorePanelNarration");
        verify(narrator, times(1)).replay("슬라이더를 오른쪽으로 움직여 주세요.");
        verify(narrator, never()).replay("이전 화면 안내");
    }

    @Test public void initialWaitingWindowDoesNotStealNativeScreenFocus() throws Exception {
        set("taskFlowView", null);
        set("windowManager", mock(WindowManager.class));
        invoke("ensureTaskFlow");
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) get("taskFlowParams");
        assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
    }

    @Test public void minimizedResultQuestionRemainsVisibleAsConversationBubble() throws Exception {
        prepareGuideResponse();
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        when(panel.hasProgress()).thenReturn(false);
        when(panel.hasCompactContent()).thenReturn(true);
        invoke("collapseTaskFlow");
        verify(panel).setMinimizedProgress(true);
        verify(panel).setVisibility(android.view.View.VISIBLE);
        Method place = ClickOverlayService.class.getDeclaredMethod("placeTaskFlow", int[].class, boolean.class);
        place.setAccessible(true); place.invoke(service, null, true);
        assertTrue("Layout refresh must retain the user's collapse", (boolean) get("taskFlowCollapsed"));
    }

    @Test public void rewordedObservationDoesNotInterruptTheSameStepButNextStepSpeaks() throws Exception {
        prepareGuideResponse();
        Method narrate = ClickOverlayService.class.getDeclaredMethod("speakGuide", AgentTurnContract.Response.class);
        narrate.setAccessible(true);
        JSONObject guide = new JSONObject().put("command_type", "highlight").put("action_type", "tap")
                .put("target_node_id", "display-1").put("message", "디스플레이를 눌러주세요.");
        JSONObject response = new JSONObject().put("request_id", "request").put("session_id", "session")
                .put("task_status", "IN_PROGRESS").put("state_id", "S1_SETTINGS_HOME").put("guide_command", guide);
        narrate.invoke(service, new AgentTurnContract.Response(response.toString()));
        guide.put("message", "화면의 디스플레이를 눌러주세요.").put("guide_command_id", "new-observation")
                .put("target_node_id", "display-2");
        narrate.invoke(service, new AgentTurnContract.Response(response.toString()));
        TutorialNarrator narrator = (TutorialNarrator) get("narrator");
        verify(narrator).speak("디스플레이를 눌러주세요.");
        verify(narrator, never()).speak("화면의 디스플레이를 눌러주세요.");
        invoke("repeatLastGuide");
        verify(narrator).replay("화면의 디스플레이를 눌러주세요.");
        response.put("state_id", "S2_DISPLAY");
        guide.put("target_node_id", "font-size").put("message", "글자 크기와 스타일을 눌러주세요.");
        narrate.invoke(service, new AgentTurnContract.Response(response.toString()));
        verify(narrator).speak("글자 크기와 스타일을 눌러주세요.");
    }

    @Test public void pauseStopsSpeechEvenWhileAnAgentResponseIsPending() throws Exception {
        prepareGuideResponse(); set("turnInFlight", true);
        control("pause_task");
        verify((TutorialNarrator) get("narrator")).stop();
        assertEquals("pause_task", get("pendingControl"));
    }

    @Test public void navigationAndUnchangedSliderNeverOfferResultCheck() throws Exception {
        prepareGuideResponse();
        deliver(1, "highlight", "tap", null);
        verify((TaskFlowOverlayView) get("taskFlowView")).progress(any(), any(), any(), eq(false));
    }

    @Test public void permissionPromptShowsSliderTargetBeforeTheUserAdjustsIt() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        deliver(1, "confirmation", "observe_only", null);
        verify((GuideOverlayView) get("guideOverlayView")).showTarget(any(), eq("observe_only"), eq(0));
        verify((TaskFlowOverlayView) get("taskFlowView")).confirmation(eq("대상 안내"), any());
        assertNull(get("confirmationView"));
    }

    @Test public void wrongSettingsPageOffersBackAndDoesNotNavigateUntilUserChooses() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false);
        NavigationRecovery recovery=(NavigationRecovery)get("navigationRecovery");
        recovery.onPath(screen); recovery.unplannedTap();
        AccessibilityNodeSnapshot back = new AccessibilityNodeSnapshot("back", null, true,
                "com.android.settings", null, "뒤로", null, "android.widget.ImageButton", "button",
                new int[]{0, 40, 80, 120}, true, true, false, false, false, null, 42);
        ScreenSnapshot wrong = new ScreenSnapshot("com.android.settings", Instant.now().toString(),
                1080, 2400, List.of(back), 42);
        currentScreen = wrong;
        Method recover = ClickOverlayService.class.getDeclaredMethod("recoverSettingsBack", ScreenSnapshot.class);
        recover.setAccessible(true); recover.invoke(service, wrong);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsBack(anyString(), anyInt()), never());
        accessibility.verify(() -> ClickAccessibilityService.performRecoveryNavigation(anyBoolean()), never());
        org.mockito.ArgumentCaptor<Runnable> click=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify((TaskFlowOverlayView)get("taskFlowView")).navigationRecovery(eq(true),anyString(),click.capture());
        currentScreen=screen;
        click.getValue().run();
        accessibility.verify(() -> ClickAccessibilityService.performRecoveryNavigation(anyBoolean()), never());
    }

    @Test public void preparedGoalConfirmationSendsStartWithoutLegacyReview() throws Exception {
        resolve();
        assertNull(get("requirementReview"));
        assertNull(get("acceptedGoal"));
        assertEquals(1, sent.size());
        confirm();
        assertEquals("Confirmation must send start_task, not remain on the waiting screen", 2, sent.size());
        JSONObject start = sent.get(1);
        assertEquals("start_task", start.getString("command_type"));
        assertEquals("goal-inputs.v1", start.getString("preparation_version"));
        assertEquals(RESOLUTION, start.getString("goal_resolution_id"));
        assertEquals("interactive.v1", start.getString("workflow_version"));
        assertEquals(goal.toString(), start.getJSONObject("goal").toString());
        assertFalse(start.has("episode_id"));
        // Duplicate taps while the first request is pending must not start a second task.
        confirm();
        assertEquals(2, sent.size());
        export("prepared-start", start);
    }

    @Test public void readyGoalCannotStartBeforeUserConfirmation() throws Exception {
        resolve();
        send(null);
        assertEquals(1, sent.size());
    }

    @Test public void failedResolutionShowsRecoveryWithOriginalRequestWithoutStartingTask() throws Exception {
        set("initialGoal", "이거 좀 바꿔줘");
        doAnswer(call -> {
            ((AgentTurnGateway.Callback) call.getArgument(1)).onFailure(new java.io.IOException("unavailable"));
            return null;
        }).when(gateway).send(any(), any());
        Method resolve = ClickOverlayService.class.getDeclaredMethod("resolveGoal", String.class, String.class);
        resolve.setAccessible(true);
        resolve.invoke(service, "이거 좀 바꿔줘", null);
        verify((TaskFlowOverlayView) get("taskFlowView")).resolutionUnavailable(eq("이거 좀 바꿔줘"), anyString(), any());
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).unresolvedGoal(any());
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).goals(any(), any());
        assertNull(get("acceptedGoal"));
        assertNull(get("goalSelection"));
    }

    @Test public void restoredRunningQueueResumesServerEpisodeWithoutPreparingAgain() throws Exception {
        stubSession(9, "existing-episode", "IN_PROGRESS", "MVP-02", false);
        beginQueuedSession(false);
        assertEquals(1, requests.size());
        assertEquals("observe", requests.get(0).commandType());
        assertEquals(9, sent.get(0).getInt("expected_version"));
        assertEquals("existing-episode", sent.get(0).getString("episode_id"));
        assertEquals("settings.font_size.decrease", ((JSONObject) get("acceptedGoal")).getString("goal_type"));
        assertNull("Do not fabricate a new preparation", get("goalPreparation"));
    }

    @Test public void restoredUnstartedQueueStillAsksForConfirmation() throws Exception {
        stubSession(0, null, "IN_PROGRESS", null, true);
        beginQueuedSession(false);
        assertEquals(1, requests.size());
        assertEquals("resolve_goal", requests.get(0).commandType());
        assertNull(get("acceptedGoal"));
        assertNotNull(get("goalPreparation"));
    }

    @Test public void newlyClaimedQueuePreparesNormally() throws Exception {
        beginQueuedSession(true);
        verify(gateway, never()).synchronize(anyString(), any());
        assertEquals(1, requests.size());
        assertEquals("resolve_goal", requests.get(0).commandType());
        assertNull(get("acceptedGoal"));
    }

    @Test public void restoredPausedQueueDoesNotAutomaticallyResume() throws Exception {
        stubSession(9, "existing-episode", "PAUSED", "MVP-01", false);
        beginQueuedSession(false);
        assertTrue(requests.isEmpty());
        assertTrue((Boolean) get("agentPaused"));
        verify((TaskFlowOverlayView) get("taskFlowView")).paused();
    }

    @Test public void restoredUnknownGraphDoesNotGuessFromRequestText() throws Exception {
        stubSession(9, "existing-episode", "IN_PROGRESS", "unknown-graph", false);
        beginQueuedSession(false);
        assertTrue(requests.isEmpty());
        assertNull(get("acceptedGoal"));
        verify((TaskFlowOverlayView) get("taskFlowView")).resolutionUnavailable(anyString(), anyString(), any());
    }

    @Test public void restoredQueueWithMissingCheckpointDoesNotRestartTask() throws Exception {
        stubSession(9, "existing-episode", "IN_PROGRESS", "MVP-01", true);
        beginQueuedSession(false);
        assertTrue(requests.isEmpty());
        assertNull(get("acceptedGoal"));
    }

    @Test public void lateSessionRestoreCannotOverwriteAnotherRequest() throws Exception {
        AgentTurnGateway.Callback[] pending = new AgentTurnGateway.Callback[1];
        doAnswer(call -> { pending[0] = call.getArgument(1); return null; })
                .when(gateway).synchronize(anyString(), any());
        beginQueuedSession(false);
        set("executionGeneration", ((Integer) get("executionGeneration")) + 1);
        pending[0].onSuccess(sessionState(9, "existing-episode", "IN_PROGRESS", "MVP-01", false));
        assertTrue(requests.isEmpty());
        assertNull(get("acceptedGoal"));
        assertEquals(0, get("agentSessionVersion"));
    }

    @Test public void preparationConflictRestoresRunningEpisodeInsteadOfShowingError() throws Exception {
        stubSession(9, "existing-episode", "IN_PROGRESS", "MVP-01", false);
        Class<?> resultClass = Class.forName("com.click.data.remote.HttpResult");
        java.lang.reflect.Constructor<?> resultConstructor = resultClass.getDeclaredConstructor(int.class, String.class, String.class);
        resultConstructor.setAccessible(true);
        Object result = resultConstructor.newInstance(409, "{}", "GOAL_PREPARATION_NOT_ALLOWED");
        java.lang.reflect.Constructor<?> errorConstructor = com.click.data.remote.ApiException.class
                .getDeclaredConstructor(String.class, String.class, resultClass);
        errorConstructor.setAccessible(true);
        Throwable conflict = (Throwable) errorConstructor.newInstance("POST", "/api/v1/agent/turn", result);
        doAnswer(call -> {
            AgentTurnContract.Request request = call.getArgument(0);
            requests.add(request);
            if ("resolve_goal".equals(request.commandType()))
                ((AgentTurnGateway.Callback) call.getArgument(1)).onFailure(conflict);
            return null;
        }).when(gateway).send(any(), any());
        beginQueuedSession(true);
        assertEquals(2, requests.size());
        assertEquals("resolve_goal", requests.get(0).commandType());
        assertEquals("observe", requests.get(1).commandType());
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).resolutionUnavailable(anyString(), anyString(), any());
    }

    private void beginQueuedSession(boolean newlyClaimed) throws Exception {
        Method method = ClickOverlayService.class.getDeclaredMethod("beginAgentSession", String.class, String.class, boolean.class);
        method.setAccessible(true);
        method.invoke(service, SOURCE, "frontend-session", newlyClaimed);
    }

    private void stubSession(int version, String episode, String status, String task, boolean requiresGoal) throws Exception {
        doAnswer(call -> {
            ((AgentTurnGateway.Callback) call.getArgument(1)).onSuccess(sessionState(version, episode, status, task, requiresGoal));
            return null;
        }).when(gateway).synchronize(anyString(), any());
    }

    private AgentTurnContract.Response sessionState(int version, String episode, String status, String task,
                                                    boolean requiresGoal) throws Exception {
        return new AgentTurnContract.Response(new JSONObject().put("request_id", "sync")
                .put("session_id", "frontend-session").put("session_version", version)
                .put("episode_id", episode).put("task_status", status).put("response_type", "control")
                .put("metadata", new JSONObject().put("task_id", task).put("requires_goal", requiresGoal)).toString());
    }

    @Test public void failedFontResolutionKeepsScopeChoicesAndRequiresServerPreparationBeforeStart() throws Exception {
        String source = "글자 크기를 키워줘";
        boolean[] unavailable = {true};
        doAnswer(call -> {
            AgentTurnContract.Request request = call.getArgument(0);
            AgentTurnGateway.Callback callback = call.getArgument(1);
            requests.add(request);
            request.contractVersion = contract.contractVersion;
            request.interactiveWorkflow = contract.interactiveWorkflow;
            sent.add(new JSONObject(request.toJson()));
            if ("resolve_goal".equals(request.commandType())) {
                if (unavailable[0]) callback.onFailure(new java.net.SocketTimeoutException());
                else callback.onSuccess(new AgentTurnContract.Response(new JSONObject()
                        .put("request_id", request.requestId).put("session_id", request.sessionId)
                        .put("session_version", 0).put("task_status", "IN_PROGRESS")
                        .put("response_type", "goal_resolution")
                        .put("preparation", preparation).put("goal", goal).put("message", "이 내용으로 시작할까요?")
                        .toString()));
            }
            return null;
        }).when(gateway).send(any(), any());
        Method resolve = ClickOverlayService.class.getDeclaredMethod("resolveGoal", String.class, String.class);
        resolve.setAccessible(true);
        resolve.invoke(service, source, null);
        RequirementReview review = (RequirementReview) get("requirementReview");
        assertNotNull(review);
        assertEquals(source, review.request());
        assertTrue("Even Settings in the foreground must leave scope selectable", review.needsTarget());
        verify((TaskFlowOverlayView) get("taskFlowView")).requirements(review, false);
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).unresolvedGoal(any());
        confirm();
        assertEquals("No request can start before choosing a scope", 1, sent.size());

        review.choose("phone");
        confirm();
        assertEquals("resolve_goal", sent.get(1).getString("command_type"));
        assertEquals("goal-inputs.v1", sent.get(1).getString("preparation_version"));
        assertTrue(sent.get(1).getString("source_text").startsWith(source));
        assertTrue(sent.get(1).getString("source_text").contains("휴대폰 전체"));
        assertSame("A repeated failure must retain the user's choice", review, get("requirementReview"));
        assertTrue(review.isPhone());
        assertFalse(review.confirmed());
        assertNull(get("acceptedGoal"));

        unavailable[0] = false;
        confirm();
        assertEquals("resolve_goal", sent.get(2).getString("command_type"));
        assertNull(get("acceptedGoal"));
        confirm();
        assertEquals("start_task", sent.get(3).getString("command_type"));
        assertEquals(RESOLUTION, sent.get(3).getString("goal_resolution_id"));
        assertEquals("goal-inputs.v1", sent.get(3).getString("preparation_version"));
    }

    @Test public void favoriteSelectionReachesServerPreparationAndStillRequiresConfirmation() throws Exception {
        QuickHelpSelectionStore selections = mock(QuickHelpSelectionStore.class);
        set("quickHelpSelections", selections);
        set("activeVoiceRequestId", "favorite-request");
        Method resolve = ClickOverlayService.class.getDeclaredMethod("resolveGoal", String.class, String.class);
        resolve.setAccessible(true);
        for (QuickHelpTask task : QuickHelpTask.values()) {
            when(selections.get("favorite-request")).thenReturn(task.goalType);
            goal = catalog.executableGoal(task.goalType).toTypedGoalJson();
            resolve.invoke(service, task.requestText, null);
            JSONObject request = sent.get(sent.size() - 1);
            assertEquals("resolve_goal", request.getString("command_type"));
            assertEquals("goal-inputs.v1", request.getString("preparation_version"));
            assertTrue(request.getString("source_text").startsWith(task.requestText));
            assertTrue(request.getString("source_text").contains("사용자가 선택한 범위: 휴대폰 전체"));
            assertNull("Selecting a favorite must not start an unconfirmed task", get("acceptedGoal"));
        }
        assertEquals(QuickHelpTask.values().length, sent.size());
    }

    @Test public void editedFavoriteRequestDoesNotReuseItsOriginalScope() throws Exception {
        QuickHelpSelectionStore selections = mock(QuickHelpSelectionStore.class);
        when(selections.get("favorite-request")).thenReturn(QuickHelpTask.FONT_SIZE.goalType);
        set("quickHelpSelections", selections);
        set("activeVoiceRequestId", "favorite-request");
        Method resolve = ClickOverlayService.class.getDeclaredMethod("resolveGoal", String.class, String.class);
        resolve.setAccessible(true);
        String edited = "카카오톡에서 글자 크기 키워줘";
        resolve.invoke(service, edited, null);
        assertEquals(edited, sent.get(0).getString("source_text"));
    }

    @Test public void normalFontPreparationShowsServerScopeChoicesBeforeStart() throws Exception {
        preparation.put("source_text", "글자 크기를 키워줘").put("status", "needs_input")
                .put("missing_fields", new JSONArray().put("scope"))
                .put("values", new JSONObject().put("direction", "increase"))
                .put("question", new JSONObject().put("id", "scope-question").put("field", "scope")
                        .put("kind", "choice").put("message", "어디의 글자 크기를 바꿀까요?")
                        .put("choices", new JSONArray()
                                .put(new JSONObject().put("value", "system").put("label", "휴대폰 기본 글자"))
                                .put(new JSONObject().put("value", "app").put("label", "특정 앱 안에서만"))));
        Method resolve = ClickOverlayService.class.getDeclaredMethod("resolveGoal", String.class, String.class);
        resolve.setAccessible(true);
        resolve.invoke(service, "글자 크기를 키워줘", null);
        assertEquals("글자 크기를 키워줘", sent.get(0).getString("source_text"));
        com.click.domain.goal.GoalPreparationState state =
                (com.click.domain.goal.GoalPreparationState) get("goalPreparation");
        assertTrue(state.needsInput()); assertFalse(state.ready());
        verify((TaskFlowOverlayView) get("taskFlowView")).preparation(state);
        confirm();
        assertEquals(1, sent.size());
        Method answer = ClickOverlayService.class.getDeclaredMethod("answerGoalInput", String.class);
        answer.setAccessible(true);
        answer.invoke(service, "system");
        assertEquals("scope-question", sent.get(1).getJSONObject("clarification_answer").getString("question_id"));
        assertEquals("system", sent.get(1).getJSONObject("clarification_answer").getString("value"));
        assertNull(get("acceptedGoal"));
    }

    @Test public void incompleteOrUnsupportedPreparationCannotStart() throws Exception {
        for (String status : List.of("needs_input", "unsupported", "cancelled")) {
            preparation.put("status", status);
            resolve();
            confirm();
            assertNull(get("acceptedGoal"));
            assertEquals("resolve_goal", sent.get(sent.size() - 1).getString("command_type"));
        }
        assertEquals(3, sent.size());
    }

    @Test public void staleResolutionCannotStartEvenWithAcceptedGoal() throws Exception {
        resolve();
        set("acceptedGoal", goal);
        set("goalResolutionId", "stale-resolution");
        send(null);
        assertEquals(1, sent.size());
    }

    @Test public void pauseDuringDelayedStartDoesNotSend() throws Exception {
        resolve();
        invoke("confirmRequirementReview");
        set("agentPaused", true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(1, sent.size());
    }

    @Test public void cancelledGenerationDoesNotSendDelayedStart() throws Exception {
        resolve();
        invoke("confirmRequirementReview");
        set("executionGeneration", 1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(1, sent.size());
    }

    @Test public void legacyReviewStillRequiresAndAcceptsConfirmation() throws Exception {
        contract = AgentServerContract.fromOpenApi(resource("/agent-server-v02.json"));
        set("initialGoal", SOURCE);
        set("requirementReview", RequirementReview.forGoal(SOURCE, goal, "글자 크게"));
        send(null);
        assertTrue(sent.isEmpty());
        confirm();
        assertEquals(1, sent.size());
        assertEquals("start_task", sent.get(0).getString("command_type"));
        assertEquals("agent-fe-contract.v0.2", sent.get(0).getString("contract_version"));
        assertFalse(sent.get(0).has("preparation_version"));
    }

    @Test public void preparedSessionCanObserveAndReportEveryVisionFailure() throws Exception {
        resolve();
        confirm();
        set("firstTurnSent", true);
        set("turnInFlight", false);
        set("agentSessionVersion", 1);
        set("agentEpisodeId", "frontend-episode");
        send(null);
        assertEquals("observe", sent.get(2).getString("command_type"));
        assertFalse(sent.get(2).has("preparation_version"));
        assertFalse(sent.get(2).has("goal"));
        export("prepared-observe", sent.get(2));
        accessibility.when(ClickAccessibilityService::freshSnapshot).thenReturn(new ScreenSnapshot(
                "com.android.settings", Instant.now().toString(), 1080, 2400, List.of(), 42));
        for (String failure : List.of("capture_failed", "masking_failed", "ocr_failed", "screen_changed", "unsupported")) {
            set("turnInFlight", false);
            set("agentConfirmationId", "00000000-0000-4000-8000-000000000001");
            set("pendingVisionFailure", failure);
            send("rejected");
            JSONObject request = sent.get(sent.size() - 1);
            assertEquals("respond_confirmation", request.getString("command_type"));
            assertEquals("rejected", request.getString("user_confirmation"));
            assertEquals(failure, request.getString("vision_failure"));
            assertFalse(request.has("vision_snapshot"));
            assertFalse(request.has("goal"));
            export("vision-" + failure, request);
        }
    }

    @Test public void sliderActionWaitsThroughEmptyRedrawAndIsSentWithTheAppliedValue() throws Exception {
        prepareGuideResponse();
        set("turnInFlight", false); set("firstTurnSent", true); set("lastSubmittedScreen", screen);
        JSONObject event = com.click.data.remote.AgentActionEvents.of("drag_completed", "slider-guide",
                "display", screen.nodes.get(1).bounds, System.currentTimeMillis());
        set("pendingVerifiedAction", event);
        currentScreen = new ScreenSnapshot("com.android.settings", Instant.now().toString(),
                1080, 2400, List.of(), 42);
        send(null);
        assertEquals("An empty redraw must not consume the action on the server", 2, requests.size());
        assertSame(event, get("pendingVerifiedAction"));
        assertEquals(true, get("forceNextObservation"));
        currentScreen = fontScreen(3f, 1.1f, System.currentTimeMillis());
        send(null);
        assertEquals(3, requests.size());
        assertEquals(event.toString(), requests.get(2).userActionEvent.toString());
        assertEquals(1.1f, requests.get(2).accessibilityTree.appliedFontScale, 0f);
        assertNull(get("pendingVerifiedAction"));
    }

    @Test public void missingSliderScreenEventuallyReachesServerRecoveryWithOriginalEvidence() throws Exception {
        prepareGuideResponse();
        set("turnInFlight", false); set("firstTurnSent", true); set("lastSubmittedScreen", screen);
        JSONObject event = com.click.data.remote.AgentActionEvents.of("drag_completed", "slider-guide",
                "display", screen.nodes.get(1).bounds, System.currentTimeMillis() - 6500L);
        set("pendingVerifiedAction", event);
        currentScreen = new ScreenSnapshot("com.android.settings", Instant.now().toString(),
                1080, 2400, List.of(), 42);
        send(null);
        assertEquals("Do not wait forever for a missing native root", 3, requests.size());
        assertEquals(event.toString(), requests.get(2).userActionEvent.toString());
    }

    @Test public void movedTargetDiscardsGuideBeforeRenderingSpeakingOrBindingAndPreservesActionEvidence() throws Exception {
        prepareGuideResponse();
        GuidedActionTracker tracker = (GuidedActionTracker) get("actionTracker");
        tracker.bind("frontend-session", "episode", "previous-guide", "tap", "display",
                screen.nodes.get(1).bounds, screen, 1);
        JSONObject event = com.click.data.remote.AgentActionEvents.of("tap_completed", "previous-guide",
                "display", screen.nodes.get(1).bounds, System.currentTimeMillis());
        set("pendingVerifiedAction", event);
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 300);
        deliver(1, "highlight", "tap", null);
        assertStaleGuideSuppressed();
        assertEquals(3, sent.size());
        JSONObject observation = sent.get(2);
        assertEquals("observe", observation.getString("command_type"));
        assertEquals(7, observation.getInt("expected_version"));
        assertEquals("episode", observation.getString("episode_id"));
        assertEquals(currentScreen.observationId, observation.getJSONObject("accessibility_tree").getString("observation_id"));
        assertEquals(event.toString(), observation.getJSONObject("user_action_event").toString());
        assertNull(tracker.record("tap_completed", "com.android.settings", 42, "display", null,
                screen.nodes.get(1).bounds, System.currentTimeMillis()));
        verify((GuideOverlayView) get("guideOverlayView")).setVisibility(android.view.View.GONE);
    }

    @Test public void appSwitchDiscardsOldGuide() throws Exception {
        prepareGuideResponse();
        currentScreen = changedScreen("com.example.other", 1080, 2400, 42, 100);
        deliver(1, "highlight", "tap", null);
        assertStaleGuideSuppressed();
    }

    @Test public void rotationDiscardsOldGuide() throws Exception {
        prepareGuideResponse();
        currentScreen = changedScreen("com.android.settings", 2400, 1080, 42, 100);
        deliver(1, "highlight", "tap", null);
        assertStaleGuideSuppressed();
    }

    @Test public void screenChangeAlsoDiscardsTextOnlyNavigationAdvice() throws Exception {
        prepareGuideResponse();
        currentScreen = changedScreen("com.example.other", 1080, 2400, 42, 100);
        deliver(1, "highlight", "observe_only", null);
        assertStaleGuideSuppressed();
    }

    @Test public void changedWindowDiscardsConfirmationBeforeShowingIt() throws Exception {
        prepareGuideResponse();
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 43, 100);
        deliver(1, "confirmation", "observe_only", null);
        assertStaleGuideSuppressed();
        assertNull(get("confirmationView"));
        assertNull(get("agentConfirmationId"));
    }

    @Test public void nativeConfirmationKeepsTheChallengeObservationAfterRedundantEvents() throws Exception {
        screen = screen.withScreenRevision("screen:1"); currentScreen = screen;
        prepareGuideResponse();
        deliver(1, "confirmation", "observe_only", null);
        java.util.function.Consumer<Boolean> answer = confirmationAnswer();
        currentScreen = new ScreenSnapshot(screen.packageName, Instant.now().plusMillis(100).toString(),
                screen.screenWidth, screen.screenHeight, screen.nodes, screen.activeWindowId)
                .withScreenRevision("screen:2");
        answer.accept(true);
        assertEquals(3, requests.size());
        assertEquals("respond_confirmation", requests.get(2).commandType());
        assertEquals("confirmation-id", requests.get(2).confirmationId);
        ScreenSnapshot submitted = requests.get(2).accessibilityTree;
        assertEquals(screen.screenRevision, submitted.screenRevision);
        assertEquals("The runtime needs a fresh capture time", currentScreen.capturedAt, submitted.capturedAt);
        assertNotEquals("Do not replay the old observation", screen.observationId, submitted.observationId);
        assertTrue(screen.sameNativeContent(submitted));
    }

    @Test public void nativeConfirmationReobservesInsteadOfApprovingAChangedScreen() throws Exception {
        prepareGuideResponse();
        deliver(1, "confirmation", "observe_only", null);
        java.util.function.Consumer<Boolean> answer = confirmationAnswer();
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 300);
        answer.accept(true);
        assertTrue(requests.stream().noneMatch(r -> "accepted".equals(r.userConfirmation)));
        assertNull(get("agentConfirmationId"));
        assertEquals(true, get("forceNextObservation"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private java.util.function.Consumer<Boolean> confirmationAnswer() throws Exception {
        org.mockito.ArgumentCaptor<java.util.function.Consumer<Boolean>> answer =
                org.mockito.ArgumentCaptor.forClass((Class) java.util.function.Consumer.class);
        verify((TaskFlowOverlayView) get("taskFlowView")).confirmation(anyString(), answer.capture());
        return answer.getValue();
    }

    @Test public void oldConfirmationButtonCannotApproveAReplacementChallenge() throws Exception {
        prepareGuideResponse();
        deliver(1, "confirmation", "observe_only", null);
        java.util.function.Consumer<Boolean> answer = confirmationAnswer();
        set("agentConfirmationId", "replacement-confirmation");
        answer.accept(true);
        assertEquals(2, requests.size());
        assertEquals("replacement-confirmation", get("agentConfirmationId"));
    }

    @Test public void confirmedIncreaseDoesNotAskForDirectionAgainInManualMode() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(false);
        deliverFontConfirmation(1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertEquals(3, requests.size());
        assertEquals("respond_confirmation", requests.get(2).commandType());
        assertEquals("accepted", requests.get(2).userConfirmation);
        assertEquals("confirmation-id", requests.get(2).confirmationId);
        assertTrue(screen.sameNativeContent(requests.get(2).accessibilityTree));
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).confirmation(anyString(), any());
        assertFalse("Reusing the requested direction does not enable auto-touch",
                new com.click.data.local.TouchAutomationSettings(service).isEnabled());
    }

    @Test public void backendConfirmationDoesNotReopenMinimizedPanel() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        when(((TaskFlowOverlayView) get("taskFlowView")).hasCompactContent()).thenReturn(true);
        invoke("collapseTaskFlow");
        deliverFontConfirmation(1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertTrue((boolean) get("taskFlowCollapsed"));
        assertEquals("accepted", requests.get(2).userConfirmation);
    }

    @Test public void changedSliderBeforeAutomaticApprovalCannotApproveAnOldChallenge() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        deliverFontConfirmation(1);
        currentScreen = fontScreen(3f, 1.15f, 2000);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertTrue(requests.stream().noneMatch(r -> "accepted".equals(r.userConfirmation)));
        assertNull(get("agentConfirmationId"));
    }

    @Test public void unsupportedFontConfirmationStopsAfterBoundedRefreshes() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        for (int i = 1; i <= 4; i++) deliverFontConfirmation(i, true);
        assertEquals(4, get("staleGuideDrops"));
        assertEquals("Unsupported responses must not create an endless observe loop", 5, requests.size());
        verify((TaskFlowOverlayView) get("taskFlowView")).observationRecovery(anyString(), any());
    }

    @Test public void brightnessUsesAutomaticApprovalAndAutomaticSliderWithoutAnExtraQuestion() throws Exception {
        automaticAdjustment("brightness");
    }
    @Test public void volumeUsesAutomaticApprovalAndAutomaticSliderWithoutAnExtraQuestion() throws Exception {
        automaticAdjustment("volume");
    }
    private void automaticAdjustment(String setting) throws Exception {
        screen = settingScreen(setting, 2f, 1f, 1000).withAdjustmentValues(100, 5); currentScreen = screen;
        prepareGuideResponse();
        goal = catalog.executableGoal("settings." + setting + ".increase").toTypedGoalJson();
        set("acceptedGoal", goal);
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(true);
        deliverFontConfirmation(1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertEquals("respond_confirmation", requests.get(2).commandType());
        assertEquals("accepted", requests.get(2).userConfirmation);
        verify((TaskFlowOverlayView)get("taskFlowView"), never()).confirmation(anyString(), any());
        verify((TaskFlowOverlayView)get("taskFlowView")).progress(anyString(), any(), anyString(), eq(false));
        accessibility.when(() -> ClickAccessibilityService.performGuidedSlider(any(), eq("display"), any(), eq(1), eq(setting))).thenReturn(true);
        JSONObject guide = new JSONObject().put("guide_command_id", "adjust-control")
                .put("command_type", "highlight").put("action_type", "drag").put("requires_user_action", true)
                .put("target_node_id", "display").put("target_bounds", new JSONArray(screen.nodes.get(1).bounds))
                .put("message", "조절 막대를 오른쪽으로 움직여주세요.");
        callbacks.get(2).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", requests.get(2).requestId).put("session_id", "frontend-session")
                .put("episode_id", "episode").put("session_version", 8).put("task_status", "IN_PROGRESS")
                .put("guide_command", guide).put("metadata", new JSONObject().put("typed_goal", goal)).toString()));
        accessibility.verify(() -> ClickAccessibilityService.performGuidedSlider(any(), eq("display"), any(), eq(1), eq(setting)));
    }

    @Test public void movingBrightnessWaitsForASettledValueWithoutStaleScreenRecovery() throws Exception {
        screen = settingScreen("brightness", 2f, 1f, 1000).withAdjustmentValues(100, 5); currentScreen = screen;
        prepareGuideResponse(); goal = catalog.executableGoal("settings.brightness.increase").toTypedGoalJson();
        set("acceptedGoal", goal); deliver(1, "highlight", "drag", "right");
        currentScreen = settingScreen("brightness", 3f, 1f, 2000).withAdjustmentValues(120, 5); send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        currentScreen = settingScreen("brightness", 4f, 1f, 2100).withAdjustmentValues(140, 5);
        deliver(2, "highlight", "observe_only", null);
        assertEquals(3, requests.size()); assertEquals(0, get("staleGuideDrops"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(4, requests.size()); assertNotEquals("request_result", requests.get(3).commandType());
        deliver(3, "highlight", "observe_only", null);
        assertEquals("request_result", requests.get(4).commandType());
        verify((TaskFlowOverlayView)get("taskFlowView"), never()).observationRecovery(anyString(), any());
    }

    @Test public void brightnessChangeDuringRedrawAsksForResultInsteadOfReportingAnUnstableScreen() throws Exception {
        adjustedSettingRecovery("brightness");
    }
    @Test public void mediaVolumeChangeDuringRedrawUsesTheSameResultFlow() throws Exception {
        adjustedSettingRecovery("volume");
    }
    private void adjustedSettingRecovery(String setting) throws Exception {
        screen = settingScreen(setting, 2f, 1f, 1000).withAdjustmentValues(100, 5); currentScreen = screen;
        prepareGuideResponse();
        goal = catalog.executableGoal("settings." + setting + ".increase").toTypedGoalJson();
        set("acceptedGoal", goal);
        deliver(1, "highlight", "drag", "right");
        currentScreen = settingScreen(setting, 3f, 1f, 2000).withAdjustmentValues(setting.equals("brightness") ? 120 : 100,
                setting.equals("volume") ? 6 : 5);
        send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        deliver(2, "highlight", "observe_only", null);
        assertEquals(4, requests.size());
        assertEquals("request_result", requests.get(3).commandType());
        assertNull(requests.get(3).userActionEvent);
        assertNull(requests.get(3).userResult);
        verify((TaskFlowOverlayView)get("taskFlowView"), never()).observationRecovery(anyString(), any());
        assertEquals(0, get("staleGuideDrops"));
    }

    private void deliverFontConfirmation(int index) throws Exception {
        deliverFontConfirmation(index, false);
    }

    private void deliverFontConfirmation(int index, boolean unsupported) throws Exception {
        AgentTurnContract.Request request = requests.get(index);
        JSONObject guide = new JSONObject().put("guide_command_id", "font-confirm")
                .put("command_type", "confirmation").put("action_type", "observe_only")
                .put("proposed_action", "drag").put("requires_user_action", true)
                .put("target_node_id", "display").put("target_bounds", new JSONArray(request.accessibilityTree.nodes.get(1).bounds))
                .put("message", "계속할까요?").put("render_policy", new JSONObject()
                        .put("avoid_covering_target", true).put("preferred_position", "below_target"));
        if (unsupported) guide.getJSONObject("render_policy").put("unknown_consent", true);
        JSONObject metadata = new JSONObject().put("typed_goal", goal).put("confirmation", new JSONObject()
                .put("id", "confirmation-id").put("expires_at", System.currentTimeMillis() / 1000 + 120));
        callbacks.get(index).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", request.requestId).put("session_id", request.sessionId)
                .put("session_version", 7).put("episode_id", "episode").put("task_status", "WAITING_CONFIRMATION")
                .put("state_id", "S4_FONT_SIZE_CONTROL").put("guide_command", guide).put("metadata", metadata).toString()));
    }

    @Test public void automaticStepsStayQuietWhileReplayAndResultQuestionsStillSpeak() throws Exception {
        prepareGuideResponse();
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(true);
        deliver(1, "highlight", "tap", null);
        TutorialNarrator narrator = (TutorialNarrator) get("narrator");
        verify(narrator, never()).speak(anyString());
        verify((TaskFlowOverlayView) get("taskFlowView")).setAutomaticProgress(true);
        Method replay = ClickOverlayService.class.getDeclaredMethod("replayGuidanceMessage", String.class);
        replay.setAccessible(true); replay.invoke(service, "디스플레이를 눌러주세요.");
        verify(narrator).replay("디스플레이를 눌러주세요.");
        JSONObject prompt = new JSONObject().put("id", "result").put("message", "변경 결과를 확인하지 못했어요. 이제 글자가 보기 편한가요?")
                .put("expires_at", System.currentTimeMillis() / 1000 + 120);
        applyFlow(new JSONObject().put("task_status", "WAITING_RESULT")
                .put("metadata", new JSONObject().put("result_prompt", prompt)));
        verify(narrator).speak("변경 결과를 확인하지 못했어요. 이제 글자가 보기 편한가요?");
        assertFalse((boolean) get("taskFlowCollapsed"));
    }

    @Test public void completedResultKeepsItsWindowUntilExitIsPressed() throws Exception {
        prepareGuideResponse();
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        applyFlow(new JSONObject().put("task_status", "COMPLETED").put("message", "보기 편해졌어요.")
                .put("metadata", new JSONObject().put("user_result","satisfied")));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertSame(panel, get("taskFlowView"));
        assertTrue((boolean) get("awaitingExitConfirmation"));
        assertFalse((boolean) get("closing"));
        verify((WindowManager) get("windowManager"), never()).removeView(panel);
        org.mockito.ArgumentCaptor<Runnable> exit = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(panel, atLeastOnce()).completed(anyString(), eq(false), exit.capture());
        exit.getValue().run();
        assertTrue((boolean) get("closing"));
        assertNull(get("taskFlowView"));
    }

    @Test public void duplicateResultDoesNotReopenMinimizedWindowOrRepeatSpeech() throws Exception {
        prepareGuideResponse();
        JSONObject prompt=new JSONObject().put("id","stable-result").put("technical_status","UNVERIFIABLE")
            .put("message","변경 결과를 확인하지 못했어요. 이제 글자가 보기 편한가요?")
            .put("expires_at",System.currentTimeMillis()/1000+120);
        JSONObject response=new JSONObject().put("task_status","WAITING_RESULT")
            .put("metadata",new JSONObject().put("result_prompt",prompt));
        applyFlow(response);
        set("taskFlowCollapsed",true);
        applyFlow(response);
        assertTrue((boolean)get("taskFlowCollapsed"));
        verify((TaskFlowOverlayView)get("taskFlowView"),times(1)).result(any(),eq(true));
        verify((TutorialNarrator)get("narrator"),times(1)).speak("변경 결과를 확인하지 못했어요. 현재 설정이 원하시는 상태인가요?");
    }

    @Test public void unverifiedDoneCannotCompleteTheQueueOrCelebrate() throws Exception {
        prepareGuideResponse();
        TaskFlowOverlayView panel=(TaskFlowOverlayView)get("taskFlowView");
        applyFlow(new JSONObject().put("task_status","COMPLETED").put("metadata",new JSONObject()));
        assertTrue((boolean)get("agentPaused"));
        verify(panel,never()).completed(anyString(),anyBoolean(),any());
        verify(panel).failed(contains("완료로 처리하지 않고"));
        assertFalse((boolean)get("awaitingExitConfirmation"));
    }

    @Test public void satisfiedResultDoesNotStartQueuedRequestUntilExplicitNext() throws Exception {
        com.click.data.remote.VoiceQueueGateway queue = prepareResultWithQueue();
        Method result = ClickOverlayService.class.getDeclaredMethod("sendResult", String.class);
        result.setAccessible(true);
        result.invoke(service, "satisfied");
        result.invoke(service, "satisfied");
        assertEquals(3, requests.size());
        AgentTurnContract.Request answer = requests.get(2);
        assertEquals("respond_result", answer.commandType());
        assertEquals("satisfied", answer.userResult);
        callbacks.get(2).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", answer.requestId).put("session_id", "frontend-session")
                .put("session_version", 8).put("episode_id", "episode")
                .put("task_status", "COMPLETED").put("metadata", new JSONObject().put("user_result","satisfied")).toString()));
        org.mockito.ArgumentCaptor<com.click.data.remote.VoiceQueueGateway.Callback> callback =
                org.mockito.ArgumentCaptor.forClass(com.click.data.remote.VoiceQueueGateway.Callback.class);
        verify(queue).complete(eq("finished-request"), callback.capture());
        callback.getValue().onSuccess(queueResponse("next-request", "next-session", true));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals("Completing one request must not resolve the next request", 3, requests.size());
        assertNull(get("agentSessionId"));
        assertTrue((boolean) get("agentPaused"));
        org.mockito.ArgumentCaptor<Runnable> next = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify((TaskFlowOverlayView) get("taskFlowView")).completedWithNext(eq(SOURCE), next.capture(), any());
        next.getValue().run();
        assertEquals("next-session", get("agentSessionId"));
        assertEquals(4, requests.size());
        assertEquals("resolve_goal", requests.get(3).commandType());
        next.getValue().run();
        assertEquals("A repeated click must not start the request twice", 4, requests.size());
    }

    @Test public void completedQueueResponseCannotRestartSameRequestOrSession() throws Exception {
        for (boolean sameRequest : new boolean[]{true, false}) {
            com.click.data.remote.VoiceQueueGateway queue = prepareResultWithQueue();
            applyFlow(new JSONObject().put("task_status", "COMPLETED").put("metadata", new JSONObject().put("user_result","satisfied")));
            org.mockito.ArgumentCaptor<com.click.data.remote.VoiceQueueGateway.Callback> callback =
                    org.mockito.ArgumentCaptor.forClass(com.click.data.remote.VoiceQueueGateway.Callback.class);
            verify(queue).complete(eq("finished-request"), callback.capture());
            int count = requests.size();
            callback.getValue().onSuccess(queueResponse(sameRequest ? "finished-request" : "different-request",
                    sameRequest ? "different-session" : "frontend-session", true));
            assertEquals(count, requests.size());
            assertNull(get("nextQueuedRequest"));
            assertNull(get("agentSessionId"));
            assertTrue((boolean) get("awaitingExitConfirmation"));
        }
    }

    @Test public void queuedClaimAfterCompletionAlsoWaitsForUser() throws Exception {
        com.click.data.remote.VoiceQueueGateway queue = prepareResultWithQueue();
        applyFlow(new JSONObject().put("task_status", "COMPLETED").put("metadata", new JSONObject().put("user_result","satisfied")));
        org.mockito.ArgumentCaptor<com.click.data.remote.VoiceQueueGateway.Callback> callback =
                org.mockito.ArgumentCaptor.forClass(com.click.data.remote.VoiceQueueGateway.Callback.class);
        verify(queue).complete(eq("finished-request"), callback.capture());
        callback.getValue().onSuccess(queueResponseBody(new JSONObject().put("queued", new JSONArray().put(new JSONObject()))));
        org.mockito.ArgumentCaptor<com.click.data.remote.VoiceQueueGateway.Callback> claim =
                org.mockito.ArgumentCaptor.forClass(com.click.data.remote.VoiceQueueGateway.Callback.class);
        verify(queue).claim(claim.capture());
        int count = requests.size();
        claim.getValue().onSuccess(queueResponse("next-request", "next-session", false));
        assertEquals(count, requests.size());
        assertNotNull(get("nextQueuedRequest"));
        verify(gateway, never()).synchronize(eq("next-session"), any());
    }

    private com.click.data.remote.VoiceQueueGateway prepareResultWithQueue() throws Exception {
        set("agentSessionId", "frontend-session");
        set("agentPaused", false); set("turnInFlight", false);
        prepareGuideResponse();
        set("turnInFlight", false);
        set("activeVoiceRequestId", "finished-request");
        set("quickHelpSelections", mock(com.click.data.local.QuickHelpSelectionStore.class));
        com.click.data.remote.VoiceQueueGateway queue = mock(com.click.data.remote.VoiceQueueGateway.class);
        set("queueGateway", queue);
        applyFlow(new JSONObject().put("task_status", "WAITING_RESULT").put("metadata", new JSONObject()
                .put("result_prompt", new JSONObject().put("id", "result-prompt").put("message", "보기 편한가요?")
                        .put("expires_at", System.currentTimeMillis() / 1000 + 120))));
        return queue;
    }
    private com.click.data.remote.VoiceQueueGateway.Response queueResponse(String request, String session, boolean claimed) throws Exception {
        return queueResponseBody(new JSONObject().put("claimed", claimed).put("running", new JSONObject()
                .put("id", request).put("agent_session_id", session).put("raw_goal", SOURCE).put("status", "RUNNING")));
    }
    private com.click.data.remote.VoiceQueueGateway.Response queueResponseBody(JSONObject body) throws Exception {
        java.lang.reflect.Constructor<com.click.data.remote.VoiceQueueGateway.Response> constructor =
                com.click.data.remote.VoiceQueueGateway.Response.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(body.toString());
    }

    private void applyFlow(JSONObject body) throws Exception {
        body.put("request_id", "flow-response").put("session_id", "frontend-session");
        Method apply = ClickOverlayService.class.getDeclaredMethod("applyTaskFlowResponse", AgentTurnContract.Response.class);
        apply.setAccessible(true);
        apply.invoke(service, new AgentTurnContract.Response(body.toString()));
    }

    @Test public void missingFreshScreenWaitsThenReobservesEvenIfContentIsUnchanged() throws Exception {
        prepareGuideResponse();
        cachedScreen = screen; // A cached screen must not authorize displaying a guide.
        currentScreen = null;
        deliver(1, "highlight", "tap", null);
        assertStaleGuideSuppressed();
        assertEquals(2, sent.size());
        assertEquals(true, get("forceNextObservation"));
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 100);
        ((BroadcastReceiver) get("collapseReceiver")).onReceive(service,
                new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED));
        assertEquals(3, sent.size());
        assertEquals(7, sent.get(2).getInt("expected_version"));
        assertEquals(false, get("forceNextObservation"));
    }

    @Test public void progressPreservesTheUsersPanelChoiceAcrossGuideUpdates() throws Exception {
        prepareGuideResponse();
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        when(panel.hasProgress()).thenReturn(true);
        when(panel.hasCompactContent()).thenReturn(true);
        deliver(1, "highlight", "tap", null);
        assertEquals("A new step must not automatically collapse the panel", false, get("taskFlowCollapsed"));
        invoke("collapseTaskFlow");
        assertEquals(true, get("taskFlowCollapsed"));
        deliver(1, "highlight", "tap", null);
        assertEquals("A subsequent step keeps the user's minimized choice", true, get("taskFlowCollapsed"));
    }

    @Test public void ordinaryObservationReadsVisibleLauncherInsteadOfPreviousPageCache() throws Exception {
        resolve(); confirm();
        set("turnInFlight", false);
        set("firstTurnSent", true);
        cachedScreen = screen;
        AccessibilityNodeSnapshot settingsIcon = new AccessibilityNodeSnapshot("settings-icon", null, true,
                "com.sec.android.app.launcher", "설정", "설정", "com.sec.android.app.launcher:id/home_icon",
                "android.widget.TextView", "menu_item", new int[]{39, 1209, 285, 1558},
                true, true, false, false, false, null, 43);
        currentScreen = new ScreenSnapshot("com.sec.android.app.launcher", Instant.now().toString(),
                1080, 2400, List.of(settingsIcon), 43);
        Method method = ClickOverlayService.class.getDeclaredMethod("sendAgentTurn", String.class, boolean.class);
        method.setAccessible(true);
        method.invoke(service, null, false);
        assertEquals("A new launcher page must not be skipped as an unchanged cached screen", 3, requests.size());
        assertSame(currentScreen, requests.get(2).accessibilityTree);
        assertEquals("설정", requests.get(2).accessibilityTree.nodes.get(0).text);
    }

    @Test public void equivalentFreshCaptureCanRenderAndBindWithoutAnotherRequest() throws Exception {
        prepareGuideResponse();
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 100);
        assertNotEquals(screen.observationId, currentScreen.observationId);
        deliver(1, "highlight", "tap", null);
        verify((GuideOverlayView) get("guideOverlayView")).showTarget(any(), eq("tap"), eq(com.click.ui.overlay.GuideDragDirection.UNKNOWN));
        verify((TutorialNarrator) get("narrator")).speak("대상 안내");
        assertEquals(2, sent.size());
        assertNotNull(((GuidedActionTracker) get("actionTracker")).record("tap_completed",
                "com.android.settings", 42, "display", null, screen.nodes.get(1).bounds,
                System.currentTimeMillis() + 1000));
    }

    @Test public void unrelatedScreenTextChangeDoesNotDiscardAnUnchangedMenuGuide() throws Exception {
        prepareGuideResponse();
        java.util.List<AccessibilityNodeSnapshot> nodes = new java.util.ArrayList<>(screen.nodes);
        nodes.add(new AccessibilityNodeSnapshot("clock", "root", true, "com.android.settings", "12:34", null,
                null, "android.widget.TextView", null, new int[]{0,0,60,20}, false, true, false, false, false, null, 42));
        currentScreen = new ScreenSnapshot(screen.packageName, screen.capturedAt, screen.screenWidth,
                screen.screenHeight, nodes, screen.activeWindowId);
        deliver(1, "highlight", "tap", null);
        verify((GuideOverlayView) get("guideOverlayView")).showTarget(any(), eq("tap"), eq(com.click.ui.overlay.GuideDragDirection.UNKNOWN));
        assertNotNull(((GuidedActionTracker) get("actionTracker")).record("tap_completed",
                "com.android.settings", 42, "display", null, screen.nodes.get(1).bounds,
                System.currentTimeMillis() + 1000));
    }

    @Test public void repeatedStaleGuidesStopAutomaticObservationUntilUserRetries() throws Exception {
        prepareGuideResponse();
        for (int index = 1; index <= 4; index++) {
            currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 100 + index * 100);
            deliver(index, "highlight", "tap", null);
        }
        assertEquals(5, sent.size());
        assertEquals(4, get("staleGuideDrops"));
        assertEquals(false, get("forceNextObservation"));
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 900);
        cachedScreen = currentScreen;
        ((BroadcastReceiver) get("collapseReceiver")).onReceive(service,
                new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED));
        assertEquals("Background snapshots cannot bypass the retry limit", 5, sent.size());
        org.mockito.ArgumentCaptor<Runnable> retry = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify((TaskFlowOverlayView) get("taskFlowView")).observationRecovery(anyString(), retry.capture());
        retry.getValue().run();
        assertEquals("An explicit retry sends even without a snapshot broadcast", 6, sent.size());
        ((BroadcastReceiver) get("collapseReceiver")).onReceive(service,
                new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED));
        assertEquals(6, sent.size());
        assertEquals(0, get("staleGuideDrops"));
        assertEquals(7, sent.get(5).getInt("expected_version"));
    }

    @Test public void servicePassesExplicitDragDirectionToOverlay() throws Exception {
        prepareGuideResponse();
        deliver(1, "highlight", "drag", "left");
        verify((GuideOverlayView) get("guideOverlayView")).showTarget(any(), eq("drag"), eq(com.click.ui.overlay.GuideDragDirection.LEFT));
    }

    @Test public void unchangedSliderWithNewAccessibilityEventStillRendersAndTracksTheAction() throws Exception {
        screen = fontScreen(2f, 1f, 1000).withScreenRevision("screen:1");
        currentScreen = screen;
        prepareGuideResponse();
        currentScreen = fontScreen(2f, 1f, 1100).withScreenRevision("screen:2");
        deliver(1, "highlight", "drag", "right");
        verify((GuideOverlayView) get("guideOverlayView")).showTarget(any(), eq("drag"),
                eq(com.click.ui.overlay.GuideDragDirection.RIGHT));
        assertEquals("An unchanged control must not enter the stale-guide retry loop", 2, requests.size());
        assertNotNull(((GuidedActionTracker) get("actionTracker")).record("drag_completed",
                "com.android.settings", 42, "display", "settings:id/font", screen.nodes.get(1).bounds,
                System.currentTimeMillis() + 1000));
    }

    @Test public void unchangedFontRecoveryWithNewAccessibilityEventStillAsksForTheResult() throws Exception {
        prepareFontRecovery(1.15f, true);
        assertEquals(4, requests.size());
        assertEquals("request_result", requests.get(3).commandType());
        assertNull(requests.get(3).userActionEvent);
        assertNull(requests.get(3).userResult);
    }

    @Test public void resumedGuideUsesTheResumeRequestsObservation() throws Exception {
        prepareGuideResponse();
        set("turnInFlight", false);
        set("firstTurnSent", true);
        set("agentEpisodeId", "episode");
        set("agentSessionVersion", 2);
        set("lastSubmittedScreen", screen);
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 300);
        control("resume_task");
        assertEquals("resume_task", sent.get(2).getString("command_type"));
        deliver(2, "highlight", "tap", null);
        verify((GuideOverlayView) get("guideOverlayView")).showTarget(any(), eq("tap"), eq(com.click.ui.overlay.GuideDragDirection.UNKNOWN));
        assertEquals(3, sent.size());
    }

    @Test public void pendingCancelConsumesVersionWithoutDisplayingReturnedGuide() throws Exception {
        prepareGuideResponse();
        control("cancel_task");
        deliver(1, "highlight", "tap", null);
        verify((GuideOverlayView) get("guideOverlayView"), never()).showTarget(any(), any(), anyInt());
        assertEquals("cancel_task", sent.get(2).getString("command_type"));
        assertEquals(7, sent.get(2).getInt("expected_version"));
    }

    private void prepareGuideResponse() throws Exception {
        resolve();
        confirm();
        clearInvocations((TaskFlowOverlayView) get("taskFlowView"));
        set("windowManager", mock(WindowManager.class));
        GuideOverlayView view = mock(GuideOverlayView.class);
        when(view.isAttachedToWindow()).thenReturn(true);
        set("guideOverlayView", view);
        set("overlayView", mock(ClickOverlayLayout.class));
        when(((ClickOverlayLayout) get("overlayView")).getMode()).thenReturn(ClickOverlayLayout.Mode.COLLAPSED);
        set("narrator", mock(TutorialNarrator.class));
        set("voiceReplyEnabled", true);
        service.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0);
    }

    @Test public void settingsRecoveryUsesExistingPanelWithoutAnExtraWindow() throws Exception {
        prepareGuideResponse();
        JSONObject guide = new JSONObject().put("guide_command_id", "open-settings")
                .put("command_type", "highlight").put("action_type", "observe_only")
                .put("requires_user_action", true).put("message", "설정 앱을 현재 화면에서 찾지 못했어요.")
                .put("render_policy", new JSONObject().put("recovery_action", new JSONObject()
                        .put("type", "open_android_settings_or_show_app_drawer")
                        .put("android_intent_action", "android.settings.SETTINGS")));
        callbacks.get(1).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", requests.get(1).requestId).put("session_id", "frontend-session")
                .put("session_version", 1).put("episode_id", "episode").put("state_id", "S0_UNKNOWN")
                .put("task_status", "IN_PROGRESS").put("guide_command", guide).toString()));
        verify((TaskFlowOverlayView) get("taskFlowView"))
                .progress(any(), any(), eq("설정 앱을 현재 화면에서 찾지 못했어요."), anyBoolean());
        verify((WindowManager) get("windowManager"), never()).addView(isA(android.widget.Button.class), any());
        verify((TaskFlowOverlayView) get("taskFlowView")).settingsEntryRecovery(any());
        verify((ClickOverlayLayout) get("overlayView")).setRecognizedSpeech("‘설정 열기’를 눌러주세요. 설정 화면에서 이어서 안내할게요.");
        verify((TutorialNarrator) get("narrator")).speak("‘설정 열기’를 눌러주세요. 설정 화면에서 이어서 안내할게요.");
    }

    @Test public void observationRetryHidesLegacyDiagnosticsAndRefreshesSilently() throws Exception {
        prepareGuideResponse();
        String diagnostic = "현재 화면에서 다음 조작을 확정하지 못했어요. 화면을 다시 확인해 주세요.";
        JSONObject guide = new JSONObject().put("guide_command_id", "internal-retry")
                .put("command_type", "highlight").put("action_type", "observe_only")
                .put("requires_user_action", false).put("message", diagnostic)
                .put("render_policy", new JSONObject().put("ui_selector", "jev")
                        .put("fallback_action", new JSONObject().put("type", "request_fresh_observation")));
        AgentTurnContract.Response response = new AgentTurnContract.Response(new JSONObject()
                .put("request_id", requests.get(1).requestId).put("session_id", "frontend-session")
                .put("session_version", 1).put("episode_id", "episode").put("state_id", "S1_SETTINGS_HOME")
                .put("task_status", "IN_PROGRESS").put("guide_command", guide).toString());
        callbacks.get(1).onSuccess(response);
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        verify(panel).progress(any(), any(), eq("안내를 준비하고 있어요."), anyBoolean());
        verify(panel, never()).progress(any(), any(), eq(diagnostic), anyBoolean());
        verify((ClickOverlayLayout) get("overlayView"), never()).setRecognizedSpeech(diagnostic);
        verify((TutorialNarrator) get("narrator"), never()).speak(anyString());
        verify(panel, never()).observationRecovery(anyString(), anyString(), any());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));
        accessibility.verify(ClickAccessibilityService::requestFreshSnapshot);
    }

    @Test public void changedValueWaitsBrieflyForTheRealActionBeforePostingAnObservation() throws Exception {
        screen=fontScreen(2f,1f,1000);currentScreen=screen;
        prepareGuideResponse();deliver(1,"highlight","drag","right");
        currentScreen=fontScreen(3f,1.15f,2000);send(null);
        assertEquals("Do not send the changed frame ahead of the native action",2,requests.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        JSONObject event=new JSONObject().put("event_id","actual-delayed-event").put("action_type","drag_completed")
            .put("guide_command_id",get("lastGuideCommandId")).put("occurred_at_ms",1500L).put("episode_id","episode");
        set("pendingVerifiedAction",event);send(null);
        assertEquals(3,requests.size());
        assertSame(event,requests.get(2).userActionEvent);
        assertEquals("observe",requests.get(2).commandType());
    }

    @Test public void missingActionEvidenceWaitIsBoundedAndCannotResumeAPausedTask() throws Exception {
        screen=fontScreen(2f,1f,1000);currentScreen=screen;
        prepareGuideResponse();deliver(1,"highlight","drag","right");
        currentScreen=fontScreen(3f,1.15f,2000);send(null);
        assertEquals(2,requests.size());
        set("agentPaused",true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(2,requests.size());
        set("agentPaused",false);send(null);
        assertEquals(3,requests.size());
        assertNull(requests.get(2).userActionEvent);
    }

    @Test public void changedFontWithoutDragEventAsksResultAndReopensCollapsedPanel() throws Exception {
        prepareFontRecovery(1.15f);
        assertEquals(4, requests.size());
        AgentTurnContract.Request resultRequest = requests.get(3);
        assertEquals("request_result", resultRequest.commandType());
        assertEquals(8, resultRequest.expectedVersion.intValue());
        assertEquals("episode", resultRequest.episodeId);
        assertNull("Do not manufacture a drag event", resultRequest.userActionEvent);
        assertNull("Only the user can say whether it is comfortable", resultRequest.userResult);
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).observationRecovery(anyString(), anyString(), any());
        send(null);
        assertEquals("Do not duplicate requests while confirmation is loading", 4, requests.size());
        set("taskFlowCollapsed", true);
        when(((ClickOverlayLayout) get("overlayView")).getMode()).thenReturn(ClickOverlayLayout.Mode.COLLAPSED);
        JSONObject prompt = new JSONObject().put("id", "font-result").put("message", "이제 글자가 보기 편한가요?")
                .put("expires_at", System.currentTimeMillis() / 1000 + 120);
        callbacks.get(3).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", resultRequest.requestId).put("session_id", resultRequest.sessionId)
                .put("session_version", 9).put("episode_id", "episode").put("task_status", "WAITING_RESULT")
                .put("metadata", new JSONObject().put("result_prompt", prompt)).toString()));
        verify((TaskFlowOverlayView) get("taskFlowView")).result(any(JSONObject.class), eq(true));
        assertEquals(false, get("taskFlowCollapsed"));
        verify((TaskFlowOverlayView) get("taskFlowView"), atLeastOnce()).setVisibility(android.view.View.VISIBLE);
        assertEquals(4, requests.size());
    }

    @Test public void fontMovedWhileFirstSliderReplyIsInFlightRetainsTheOriginalBaseline() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        currentScreen = fontScreen(3f, 1.15f, 2000);
        deliverFontConfirmation(1);
        // Approval is deliberately deferred; do not pretend a reply arrived before its request.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertEquals("respond_confirmation", requests.get(2).commandType());
        send(null);
        deliver(2, "highlight", "drag", "right");
        assertEquals("request_result", requests.get(3).commandType());
        assertEquals(4, requests.size());
    }

    @Test public void highlightsAndRecoveryBubblesNeverMoveTheCollapsedControl() throws Exception {
        prepareGuideResponse();
        ClickOverlayLayout control = (ClickOverlayLayout) get("overlayView");
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = params.height = 64; params.x = 300; params.y = 600;
        set("windowParams", params); set("taskFlowCollapsed", true);
        when(control.isAttachedToWindow()).thenReturn(true);
        when(control.getVisibility()).thenReturn(android.view.View.VISIBLE);
        when(control.getMode()).thenReturn(ClickOverlayLayout.Mode.COLLAPSED);
        TaskFlowOverlayView bubble = (TaskFlowOverlayView) get("taskFlowView");
        when(bubble.isAttachedToWindow()).thenReturn(true);
        when(bubble.isMinimizedProgress()).thenReturn(true);
        when(bubble.getWindowWidth(anyInt())).thenReturn(260);
        when(bubble.getMeasuredHeight()).thenReturn(90);
        Method show = ClickOverlayService.class.getDeclaredMethod("showGuideOverlay", int[].class, String.class, int.class);
        show.setAccessible(true);
        Method place = ClickOverlayService.class.getDeclaredMethod("placeProgressBubble"); place.setAccessible(true);
        for (int[] target : new int[][]{{20, 200, 380, 260}, {250, 580, 390, 700}}) {
            set("lastTargetBounds", target);
            show.invoke(service, target, "tap", 0); place.invoke(service);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(300, params.x); assertEquals(600, params.y);
        }
        verify((WindowManager) get("windowManager"), never()).updateViewLayout(eq(control), any());
    }

    @Test public void persistedFontChangeAfterStaleRetryLimitStillReachesResultPrompt() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        deliver(1, "highlight", "drag", "right");
        // Settings can finish applying FONT_SCALE after its redraw exhausted guide retries.
        set("staleGuideDrops", 4);
        currentScreen = fontScreen(3f, 1.15f, 2000);
        send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        assertEquals("A persisted change must not be dropped by the stale-guide limit", 3, requests.size());
        deliver(2, "highlight", "drag", "right");
        assertEquals("request_result", requests.get(3).commandType());
        assertNull(requests.get(3).userActionEvent);
        assertNull(requests.get(3).userResult);
    }

    @Test public void staleRetryLimitStillStopsUnchangedOrOppositeFontChanges() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        deliver(1, "highlight", "drag", "right");
        set("staleGuideDrops", 4);
        currentScreen = fontScreen(2f, 1f, 2000); send(null);
        currentScreen = fontScreen(1f, .85f, 3000); send(null);
        assertEquals(2, requests.size());
    }

    @Test public void persistedFontChangeAdvancesEvenWhenServerRepeatsTheSliderGuide() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        deliver(1, "highlight", "drag", "right");
        ScreenSnapshot changed = fontScreen(3f, 1.15f, 2000);
        currentScreen = new ScreenSnapshot(changed.packageName, changed.capturedAt, 1080, 2400,
                changed.nodes, 99, true, changed.appliedFontScale);
        send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        deliver(2, "highlight", "drag", "right");
        assertEquals("request_result", requests.get(3).commandType());
        assertNull(requests.get(3).userActionEvent);
        assertNull(requests.get(3).userResult);
        assertEquals("A changed native window must not restart confirmation", 4, requests.size());
    }

    @Test public void changeDuringFontConfirmationIsNotLostBeforeTheDragGuide() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse();
        deliverFontConfirmation(1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        currentScreen = fontScreen(3f, 1.15f, 2000);
        deliver(2, "highlight", "drag", "right");
        // The reply predates the change, so first send a fresh observation.
        send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        int observation = requests.size() - 1;
        deliver(observation, "confirmation", "observe_only", null);
        assertEquals("request_result", requests.get(requests.size() - 1).commandType());
        assertNull(requests.get(requests.size() - 1).userActionEvent);
    }

    @Test public void unchangedPersistedFontKeepsRecoveryInsteadOfAskingForSuccess() throws Exception {
        prepareFontRecovery(1f);
        assertEquals(3, requests.size());
        verify((TaskFlowOverlayView) get("taskFlowView")).observationRecovery(anyString(), anyString(), any());
    }

    private void prepareFontRecovery(float appliedScale) throws Exception {
        prepareFontRecovery(appliedScale, false);
    }

    private void prepareFontRecovery(float appliedScale, boolean eventWhileWaiting) throws Exception {
        screen = fontScreen(2f, 1f, 1000);
        currentScreen = screen;
        prepareGuideResponse();
        deliver(1, "highlight", "drag", "right");
        currentScreen = fontScreen(3f, appliedScale, 2000);
        send(null);
        if (appliedScale > 1f) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        AgentTurnContract.Request observation = requests.get(2);
        if (eventWhileWaiting) currentScreen = currentScreen.withScreenRevision("same-screen-new-event");
        JSONObject recovery = new JSONObject().put("recoverable", true).put("waiting_for", "user")
                .put("reason_code", "ACTION_EVENT_MISSING").put("next_action", "user_assistance");
        JSONObject guide = new JSONObject().put("guide_command_id", "recovery-guide")
                .put("command_type", "highlight").put("action_type", "observe_only")
                .put("requires_user_action", false).put("message", "조작 결과를 확인하지 못했어요.")
                .put("render_policy", new JSONObject().put("recovery", recovery));
        callbacks.get(2).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", observation.requestId).put("session_id", observation.sessionId)
                .put("session_version", 8).put("episode_id", "episode").put("task_status", "IN_PROGRESS")
                .put("state_id", "S4_FONT_SIZE_CONTROL").put("guide_command", guide).toString()));
    }

    private ScreenSnapshot settingScreen(String setting, float progress, float appliedScale, long time) {
        String label=setting.equals("volume") ? "미디어" : "밝기";
        AccessibilityNodeSnapshot title=new AccessibilityNodeSnapshot("title",null,true,"com.android.settings",label,null,null,
                "android.widget.TextView","text",new int[]{20,20,500,70},false,true,false,false,false,null,42);
        AccessibilityNodeSnapshot slider=new AccessibilityNodeSnapshot("display",null,true,"com.android.settings",label,null,
                "settings:id/"+setting,"android.widget.SeekBar","slider",new int[]{20,100,900,180},true,true,false,false,false,progress,42,false,0f,7f);
        return new ScreenSnapshot("com.android.settings",Instant.ofEpochMilli(time).toString(),1080,2400,List.of(title,slider),42,true,appliedScale);
    }

    private ScreenSnapshot fontScreen(float progress, float appliedScale, long time) {
        AccessibilityNodeSnapshot title = new AccessibilityNodeSnapshot("title", null, true,
                "com.android.settings", "글자 크기", null, null, "android.widget.TextView", "text",
                new int[]{20, 20, 500, 70}, false, true, false, false, false, null, 42);
        AccessibilityNodeSnapshot slider = new AccessibilityNodeSnapshot("display", null, true,
                "com.android.settings", "글자 크기", null, "settings:id/font", "android.widget.SeekBar", "slider",
                new int[]{20, 100, 900, 180}, true, true, false, false, false, progress, 42, false, 0f, 7f);
        return new ScreenSnapshot("com.android.settings", Instant.ofEpochMilli(time).toString(), 1080, 2400,
                List.of(title, slider), 42, true, appliedScale);
    }

    private void assertStaleGuideSuppressed() throws Exception {
        verify((GuideOverlayView) get("guideOverlayView"), never()).showTarget(any(), any(), anyInt());
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).progress(any(), any(), any(), anyBoolean());
        verify((TutorialNarrator) get("narrator"), never()).speak(anyString());
        assertNull(get("lastGuideCommandId"));
        accessibility.verify(ClickAccessibilityService::requestFreshSnapshot);
    }

    @Test public void requestedScrollReadsNewScreenWithoutClaimingSettingSuccess() throws Exception {
        resolve(); confirm();
        set("turnInFlight", false);
        set("firstTurnSent", true);
        accessibility.when(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42))
                .thenAnswer(call -> {
                    currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 600);
                    return true;
                });
        searchScroll(true, screen);
        send(null);
        assertEquals("No observation while the scroll is settling", 2, requests.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42));
        assertEquals(3, requests.size());
        assertSame(currentScreen, requests.get(2).accessibilityTree);
        assertEquals(-1, get("scrollSearchGeneration"));
        assertNull(get("pendingVerifiedAction"));
    }

    @Test public void pauseOrChangedWindowPreventsDelayedScroll() throws Exception {
        resolve(); confirm(); set("turnInFlight", false);
        searchScroll(true, screen);
        currentScreen = changedScreen("com.example.other", 1080, 2400, 99, 600);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(anyBoolean(), anyString(), anyInt()), never());
        set("turnInFlight", false);
        currentScreen = screen;
        searchScroll(false, screen);
        set("agentPaused", true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(anyBoolean(), anyString(), anyInt()), never());
        assertEquals(-1, get("scrollSearchGeneration"));
    }

    @Test public void unrelatedBrightnessChangeDoesNotPreventMissingVolumeMenuSearch() throws Exception {
        currentScreen=scrollableScreen("settings page").withAdjustmentValues(150,6);
        resolve();confirm();
        set("acceptedGoal",new JSONObject().put("target_app","android_settings").put("goal_type","settings.volume.decrease"));
        currentScreen=currentScreen.withAdjustmentValues(151,6);
        accessibility.when(() -> ClickAccessibilityService.performSettingsScroll(true,"com.android.settings",42)).thenReturn(true);
        deliverMissingTarget(1,"TARGET_NOT_VISIBLE");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true,"com.android.settings",42));
        verify((TaskFlowOverlayView)get("taskFlowView"),never()).targetHelp(anyString(),any());
    }

    @Test public void duplicateRepliesWhileMenuScrollIsQueuedDoNotConsumeSearchBudget() throws Exception {
        currentScreen=scrollableScreen("settings page");resolve();confirm();set("turnInFlight",false);
        Method start=ClickOverlayService.class.getDeclaredMethod("startAutomaticSettingsSearch",String.class,ScreenSnapshot.class);
        start.setAccessible(true);
        for(int duplicate=0;duplicate<6;duplicate++)assertEquals(true,start.invoke(service,"S1_SETTINGS_HOME",currentScreen));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true,"com.android.settings",42),times(1));
        verify((TaskFlowOverlayView)get("taskFlowView"),never()).targetHelp(anyString(),any());
    }

    @Test public void screenResyncBeforeListScrollDoesNotSpendAnAttempt() throws Exception {
        currentScreen=scrollableScreen("old page");resolve();confirm();set("turnInFlight",false);
        ScreenSnapshot expected=currentScreen;
        Method start=ClickOverlayService.class.getDeclaredMethod("startAutomaticSettingsSearch",String.class,ScreenSnapshot.class);
        start.setAccessible(true);currentScreen=scrollableScreen("new page");
        for(int stale=0;stale<6;stale++)assertEquals(true,start.invoke(service,"S1_SETTINGS_HOME",expected));
        assertEquals(true,start.invoke(service,"S1_SETTINGS_HOME",currentScreen));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true,"com.android.settings",42),times(1));
        verify((TaskFlowOverlayView)get("taskFlowView"),never()).targetHelp(anyString(),any());
    }

    @Test public void offscreenTargetAutomaticallyScrollsThenStopsWhenTargetAppears() throws Exception {
        // Settings list navigation should not ask the user to enable automatic setting changes.
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(false);
        currentScreen = scrollableScreen("first page");
        resolve(); confirm();
        accessibility.when(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42))
                .thenAnswer(call -> { currentScreen = screen; return true; });
        deliverMissingTarget(1, "TARGET_NOT_VISIBLE");
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(anyBoolean(), anyString(), anyInt()), never());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertEquals("Wait for the moved screen to settle", 2, requests.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));
        assertEquals(3, requests.size());
        assertSame(screen, requests.get(2).accessibilityTree);
        assertTrue("Navigation must not claim the requested tap or setting succeeded",
                sent.get(2).toString().contains("manual_override"));
        assertNull(get("pendingVerifiedAction"));
        deliver(2, "highlight", "tap", null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42), times(1));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(false, "com.android.settings", 42), never());
        assertEquals(3, requests.size());
        verify((TaskFlowOverlayView) get("taskFlowView"), never())
                .targetHelp(anyString(), any());
    }

    @Test public void unreasonedJevWaitScrollsAndSendsTheChangedScreenBeforeSelectingAgain() throws Exception {
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(false);
        currentScreen = scrollableScreen("settings page");
        resolve(); confirm();
        accessibility.when(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42))
                .thenAnswer(call -> { currentScreen = screen; return true; });
        JSONObject guide = new JSONObject().put("guide_command_id", "jev-wait")
                .put("command_type", "highlight").put("action_type", "observe_only")
                .put("render_policy", new JSONObject().put("ui_selector", "jev")
                        .put("runtime_strategy", "wait_for_action").put("revalidate_before_action", true)
                        .put("fallback_action", new JSONObject().put("type", "request_fresh_observation")));
        callbacks.get(1).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", requests.get(1).requestId).put("session_id", "frontend-session")
                .put("session_version", 1).put("episode_id", "episode").put("state_id", "S1_SETTINGS_HOME")
                .put("task_status", "IN_PROGRESS").put("guide_command", guide)
                .put("metadata", new JSONObject().put("verification", new JSONObject().put("result",
                        new JSONObject().put("status", "SUCCESS").put("evidence", "observed_progress"))))
                .toString()));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42));
        assertEquals(3, requests.size());
        assertSame(screen, requests.get(2).accessibilityTree);
        assertTrue(sent.get(2).toString().contains("manual_override"));
        deliver(2, "highlight", "tap", null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(anyBoolean(), anyString(), anyInt()), times(1));
        assertEquals("The visible target must stop exploration", 3, requests.size());
    }

    @Test public void legacyMissingTargetAlsoSearchesSettingsWithoutAsking() throws Exception {
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(false);
        currentScreen = scrollableScreen("settings page");
        resolve(); confirm();
        JSONObject guide = new JSONObject().put("guide_command_id", "legacy-missing")
                .put("command_type", "highlight").put("action_type", "observe_only")
                .put("render_policy", new JSONObject().put("fallback_action", new JSONObject()
                        .put("type", "request_fresh_observation").put("reason", "target_not_found")));
        AgentTurnContract.Response response = new AgentTurnContract.Response(new JSONObject()
                .put("request_id", requests.get(1).requestId).put("session_id", "frontend-session")
                .put("session_version", 1).put("episode_id", "episode").put("state_id", "S1_SETTINGS_HOME")
                .put("task_status", "IN_PROGRESS").put("guide_command", guide).toString());
        callbacks.get(1).onSuccess(response);
        // A redundant native event must not cancel the scheduled scroll.
        currentScreen = currentScreen.withScreenRevision("redundant-event");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42));
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).observationRecovery(anyString(), any());
    }

    @Test public void targetNotFoundOnKnownMenuSearchesBeforeHomeRecovery() throws Exception {
        currentScreen = scrollableScreen("settings page");
        resolve(); confirm();
        deliverMissingTarget(1, "TARGET_NOT_FOUND");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42));
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).navigationRecovery(anyBoolean(), anyString(), any());
    }

    @Test public void currentReobserveCodeSearchesFontSubmenuInsteadOfWaitingForUser() throws Exception {
        currentScreen = scrollableScreen("font menu");
        resolve(); confirm();
        JSONObject policy = new JSONObject().put("recovery", new JSONObject()
                .put("recoverable", true).put("reason_code", "TARGET_NOT_FOUND")
                .put("waiting_for", "observation").put("next_action", "reobserve"))
                .put("fallback_action", new JSONObject().put("type", "request_fresh_observation")
                        .put("reason_code", "TARGET_NOT_FOUND"));
        JSONObject guide = new JSONObject().put("guide_command_id", "font-menu-missing")
                .put("command_type", "highlight").put("action_type", "observe_only").put("render_policy", policy);
        callbacks.get(1).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", requests.get(1).requestId).put("session_id", "frontend-session")
                .put("session_version", 1).put("episode_id", "episode").put("state_id", "S3_FONT_MENU")
                .put("task_status", "IN_PROGRESS").put("guide_command", guide).toString()));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42));
        assertTrue((boolean) get("taskFlowCollapsed"));
    }

    @Test public void automaticSearchContinuesBeyondEightPagesAndStopsAtObservedEnd() throws Exception {
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(true);
        currentScreen = scrollableScreen("page 0");
        resolve(); confirm();
        int[] page = {0};
        accessibility.when(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42))
                .thenAnswer(call -> {
                    int index = ++page[0];
                    currentScreen = scrollableScreen("page " + index).withNavigation(new NavigationState(
                            "list", NavigationState.Axis.VERTICAL, true, index < 12,
                            index * 6, index * 6 + 5, 78, "page " + index));
                    return true;
                });
        for (int i = 1; i <= 12; i++) {
            deliverMissingTarget(i, "TARGET_NOT_VISIBLE");
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
            assertEquals(i + 2, requests.size());
            verify((TaskFlowOverlayView) get("taskFlowView"), never()).targetHelp(anyString(), any());
        }
        deliverMissingTarget(13, "TARGET_NOT_VISIBLE");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42), times(12));
        assertEquals(14, requests.size());
        assertTrue((boolean) get("taskFlowCollapsed"));
        org.mockito.ArgumentCaptor<Runnable> notFound = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify((TaskFlowOverlayView) get("taskFlowView")).targetHelp(eq("디스플레이"), notFound.capture());
        JSONObject confirmedGoal = (JSONObject) get("acceptedGoal");
        accessibility.when(() -> ClickAccessibilityService.performRecoveryNavigation(false)).thenReturn(true);
        notFound.getValue().run();
        notFound.getValue().run();
        verify((TaskFlowOverlayView) get("taskFlowView"), times(1)).recoveryApology(any());
        accessibility.verify(() -> ClickAccessibilityService.performRecoveryNavigation(anyBoolean()), never());
        assertSame(confirmedGoal, get("acceptedGoal"));
        assertEquals(NavigationRecovery.Action.NONE, ((NavigationRecovery) get("navigationRecovery")).awaiting());
        assertEquals(get("executionGeneration"), get("recoveryApologyGeneration"));
    }

    @Test public void staleTargetHelpCannotNavigateAfterANewRequest() throws Exception {
        resolve(); confirm();
        Method show = ClickOverlayService.class.getDeclaredMethod("showTargetHelp", String.class);
        show.setAccessible(true); show.invoke(service, "S1_SETTINGS_HOME");
        org.mockito.ArgumentCaptor<Runnable> notFound = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify((TaskFlowOverlayView) get("taskFlowView")).targetHelp(eq("디스플레이"), notFound.capture());
        set("executionGeneration", (int) get("executionGeneration") + 1);
        notFound.getValue().run();
        accessibility.verify(() -> ClickAccessibilityService.performRecoveryNavigation(anyBoolean()), never());
    }

    @Test public void openingAndCollapsingTargetHelpReplayTheTargetInsteadOfOldGuidance() throws Exception {
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        TutorialNarrator narrator = mock(TutorialNarrator.class);
        set("narrator", narrator);
        when(panel.isTargetHelp()).thenReturn(true);
        when(panel.hasOpenedTargetHelp()).thenReturn(true);
        when(panel.guidanceMessage()).thenReturn("디스플레이 항목을 찾아주세요");
        set("taskFlowCollapsed", true);
        assertTrue(service.onOpenRequested());
        Method collapse = ClickOverlayService.class.getDeclaredMethod("collapseTaskFlow");
        collapse.setAccessible(true); collapse.invoke(service);
        verify(narrator, times(2)).replay("디스플레이 항목을 찾아주세요");
    }

    @Test public void expandedTargetHelpSurvivesSnapshotEventsForcedReadsAndLateGuides() throws Exception {
        resolve(); confirm();
        Method show = ClickOverlayService.class.getDeclaredMethod("showTargetHelp", String.class);
        show.setAccessible(true); show.invoke(service, "S1_SETTINGS_HOME");
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        assertTrue(service.onOpenRequested());
        clearInvocations(panel);
        set("turnInFlight", false);
        set("forceNextObservation", true);
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 200);
        cachedScreen = currentScreen;
        ((BroadcastReceiver) get("collapseReceiver")).onReceive(service,
                new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED));
        Method observe = ClickOverlayService.class.getDeclaredMethod("sendAgentTurn", String.class, boolean.class);
        observe.setAccessible(true); observe.invoke(service, null, true);
        // A response already on its way when help appeared must not overwrite the card.
        deliverMissingTarget(1, "TARGET_NOT_VISIBLE");
        callbacks.get(1).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", requests.get(1).requestId).put("session_version", 2)
                .put("session_id", "frontend-session").put("episode_id", "episode")
                .put("task_status", "WAITING_CONFIRMATION").put("state_id", "S1_SETTINGS_HOME")
                .put("guide_command", new JSONObject().put("guide_command_id", "late-confirmation")
                        .put("command_type", "confirmation").put("action_type", "observe_only")
                        .put("message", "이전 화면의 확인 안내")).toString()));
        show.invoke(service, "S1_SETTINGS_HOME");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertEquals(2, requests.size());
        assertFalse((boolean) get("taskFlowCollapsed"));
        verify(panel, never()).targetHelp(anyString(), any());
        verify(panel, never()).waitingProgress(anyString());
        verify(panel, never()).progress(anyString(), any(), any(), anyBoolean());
        verify(panel, never()).confirmation(anyString(), any());
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(anyBoolean(), anyString(), anyInt()), never());
    }

    @Test public void compactTargetHelpWaitsThroughTransientReadsAndResumesOnChangedNativeScreen() throws Exception {
        resolve(); confirm(); set("turnInFlight", false);
        Method show = ClickOverlayService.class.getDeclaredMethod("showTargetHelp", String.class);
        show.setAccessible(true); show.invoke(service, "S1_SETTINGS_HOME");
        Method observe = ClickOverlayService.class.getDeclaredMethod("sendAgentTurn", String.class, boolean.class);
        observe.setAccessible(true);
        JSONObject confirmedGoal = (JSONObject) get("acceptedGoal");
        for (ScreenSnapshot transientScreen : new ScreenSnapshot[]{null,
                new ScreenSnapshot("com.android.settings", Instant.now().toString(), 1080, 2400, List.of(), 42),
                changedScreen("com.click", 1080, 2400, 43, 200),
                changedScreen("com.android.settings", 1080, 2400, 42, 100)}) {
            currentScreen = transientScreen;
            observe.invoke(service, null, true);
            assertEquals("Overlay redraws and identical native captures must keep help waiting", 2, requests.size());
        }
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 43, 300);
        observe.invoke(service, null, true);
        assertEquals(3, requests.size());
        assertSame(currentScreen, requests.get(2).accessibilityTree);
        assertSame(confirmedGoal, get("acceptedGoal"));
        assertEquals(-1, get("targetHelpGeneration"));
    }

    @Test public void middleOfSettingsSearchesTowardBeginningBeforeSweepingForward() throws Exception {
        currentScreen = scrollableScreen("middle").withNavigation(new NavigationState(
                "list", NavigationState.Axis.VERTICAL, true, true, 20, 25, 40, "middle"));
        resolve(); confirm();
        accessibility.when(() -> ClickAccessibilityService.performSettingsScroll(false, "com.android.settings", 42))
                .thenAnswer(call -> { currentScreen = scrollableScreen("beginning"); return true; });
        accessibility.when(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42))
                .thenAnswer(call -> {
                    currentScreen = scrollableScreen("end").withNavigation(new NavigationState(
                            "list", NavigationState.Axis.VERTICAL, true, false, 34, 39, 40, "end"));
                    return true;
                });
        deliverMissingTarget(1, "TARGET_NOT_VISIBLE");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        deliverMissingTarget(2, "TARGET_NOT_VISIBLE");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        deliverMissingTarget(3, "TARGET_NOT_VISIBLE");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(false, "com.android.settings", 42), times(1));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(true, "com.android.settings", 42), times(1));
        verify((TaskFlowOverlayView) get("taskFlowView")).targetHelp(anyString(), any());
        assertEquals(4, requests.size());
    }

    @Test public void pauseCancelsAutomaticScrollBeforeItRuns() throws Exception {
        currentScreen = scrollableScreen("page");
        resolve(); confirm();
        deliverMissingTarget(1, "TARGET_NOT_VISIBLE");
        control("pause_task");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(anyBoolean(), anyString(), anyInt()), never());
        assertEquals("pause_task", requests.get(2).commandType());
        assertEquals(3, requests.size());
    }

    @Test public void cancelCancelsAutomaticScrollBeforeItRuns() throws Exception {
        currentScreen = scrollableScreen("page");
        resolve(); confirm();
        deliverMissingTarget(1, "TARGET_NOT_VISIBLE");
        control("cancel_task");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(anyBoolean(), anyString(), anyInt()), never());
        assertEquals("cancel_task", requests.get(2).commandType());
        assertEquals(3, requests.size());
    }

    @Test public void changedWindowCancelsAutomaticScrollBeforeItRuns() throws Exception {
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(true);
        currentScreen = scrollableScreen("page");
        resolve(); confirm();
        deliverMissingTarget(1, "TARGET_NOT_VISIBLE");
        currentScreen = changedScreen("com.example.other", 1080, 2400, 99, 600);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(anyBoolean(), anyString(), anyInt()), never());
        assertSame(currentScreen, requests.get(2).accessibilityTree);
    }

    @Test public void ambiguousTargetNeverStartsAutomaticScroll() throws Exception {
        currentScreen = scrollableScreen("page");
        resolve(); confirm();
        deliverMissingTarget(1, "TARGET_AMBIGUOUS");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        accessibility.verify(() -> ClickAccessibilityService.performSettingsScroll(anyBoolean(), anyString(), anyInt()), never());
        assertEquals(2, requests.size());
    }

    @Test public void recoveryRetrySendsUnchangedScreenOnceWithoutWaitingForAnEvent() throws Exception {
        resolve(); confirm();
        deliverMissingTarget(1, "ACTION_RESULT_UNOBSERVABLE");
        org.mockito.ArgumentCaptor<Runnable> retry = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify((TaskFlowOverlayView) get("taskFlowView"))
                .observationRecovery(anyString(), anyString(), retry.capture());
        retry.getValue().run();
        assertEquals(3, requests.size());
        assertSame(currentScreen, requests.get(2).accessibilityTree);
        assertEquals(AgentActionEvents.MANUAL_OVERRIDE, requests.get(2).userActionEvent.getString("action_type"));
        verify((TaskFlowOverlayView) get("taskFlowView")).waitingProgress("현재 화면을 다시 확인하고 있어요.");
        retry.getValue().run();
        assertEquals("Repeated taps must not send another in-flight request", 3, requests.size());
    }

    @Test public void recoveryRetryExplainsUnavailableScreenAndRespectsCancellation() throws Exception {
        resolve(); confirm();
        deliverMissingTarget(1, "ACTION_RESULT_UNOBSERVABLE");
        org.mockito.ArgumentCaptor<Runnable> retry = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        verify(panel).observationRecovery(anyString(), anyString(), retry.capture());
        currentScreen = null;
        retry.getValue().run();
        assertEquals(2, requests.size());
        verify(panel).recoveryApology(any());
        assertEquals(false, get("taskFlowCollapsed"));
        currentScreen = screen;
        set("pendingControl", "cancel_task");
        retry.getValue().run();
        assertEquals("A pending stop must not be bypassed by retry", 2, requests.size());
    }

    @Test public void unsuccessfulAssistedRetryShowsApologyAndWaitsForExplicitRetry() throws Exception {
        resolve(); confirm();
        deliverMissingTarget(1, "ACTION_RESULT_UNOBSERVABLE");
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        org.mockito.ArgumentCaptor<Runnable> help = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(panel).observationRecovery(anyString(), anyString(), help.capture());
        help.getValue().run();
        assertEquals(3, requests.size());
        deliverMissingTarget(2, "ACTION_RESULT_UNOBSERVABLE");
        org.mockito.ArgumentCaptor<Runnable> retry = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(panel).recoveryApology(retry.capture());
        verify(panel, times(1)).observationRecovery(anyString(), anyString(), any());
        assertFalse((boolean) get("taskFlowCollapsed"));
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 43, 300);
        send(null);
        assertEquals("The apology must not restart work on screen events", 3, requests.size());
        retry.getValue().run();
        assertEquals(4, requests.size());
        assertEquals(-1, get("recoveryApologyGeneration"));
    }

    @Test public void exhaustedFreshObservationsOfferTargetHelpWithoutAnotherRetry() throws Exception {
        prepareGuideResponse();
        set("freshObservationRetries", 4);
        set("taskFlowCollapsed", true);
        set("taskFlowHeightLimit", 100);
        when(((TaskFlowOverlayView) get("taskFlowView")).hasCompactContent()).thenReturn(true);
        JSONObject guide = new JSONObject().put("guide_command_id", "fresh-retry-limit")
                .put("command_type", "highlight").put("action_type", "observe_only")
                .put("render_policy", new JSONObject().put("fallback_action", new JSONObject()
                        .put("type", "request_fresh_observation")));
        callbacks.get(1).onSuccess(new AgentTurnContract.Response(new JSONObject()
                .put("request_id", requests.get(1).requestId).put("session_id", "frontend-session")
                .put("session_version", 1).put("episode_id", "episode").put("state_id", "S1_SETTINGS_HOME")
                .put("task_status", "IN_PROGRESS").put("guide_command", guide).toString()));
        verify((TaskFlowOverlayView) get("taskFlowView")).targetHelp(eq("디스플레이"), any());
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).observationRecovery(anyString(), any());
        assertEquals(true, get("taskFlowCollapsed"));
        assertEquals("Exhausted observations wait for the user, without another automatic retry", 2, requests.size());
    }

    private ScreenSnapshot scrollableScreen(String label) {
        AccessibilityNodeSnapshot list = new AccessibilityNodeSnapshot("list", null, true,
                "com.android.settings", label, null, null, "androidx.recyclerview.widget.RecyclerView", "list",
                new int[]{0, 200, 1080, 2200}, false, true, false, true, false, null, 42);
        return new ScreenSnapshot("com.android.settings", Instant.now().toString(),
                1080, 2400, List.of(list), 42).withNavigation(new NavigationState(
                        "list", NavigationState.Axis.VERTICAL, false, true, 0, 5, 100, label));
    }

    private void deliverMissingTarget(int index, String reason) throws Exception {
        AgentTurnContract.Request request = requests.get(index);
        JSONObject guide = new JSONObject().put("guide_command_id", "missing-guide-" + index)
                .put("command_type", "highlight").put("action_type", "observe_only")
                .put("requires_user_action", false).put("message", "목록에서 항목을 찾아주세요.")
                .put("render_policy", new JSONObject().put("recovery", new JSONObject()
                        .put("recoverable", true).put("waiting_for", "user").put("reason_code", reason)
                        .put("next_action", "manual_navigation")));
        JSONObject response = new JSONObject().put("request_id", request.requestId).put("session_id", request.sessionId)
                .put("session_version", index).put("episode_id", "episode").put("task_status", "IN_PROGRESS")
                .put("state_id", "S1_SETTINGS_HOME").put("guide_command", guide).put("metadata", new JSONObject());
        callbacks.get(index).onSuccess(new AgentTurnContract.Response(response.toString()));
    }

    private void searchScroll(boolean forward, ScreenSnapshot expected) throws Exception {
        Method method = ClickOverlayService.class.getDeclaredMethod("searchSettingsByScrolling",
                boolean.class, ScreenSnapshot.class, int.class);
        method.setAccessible(true);
        method.invoke(service, forward, expected, (int) get("executionGeneration"));
    }

    private ScreenSnapshot changedScreen(String packageName, int width, int height, int window, int top) {
        AccessibilityNodeSnapshot title = new AccessibilityNodeSnapshot("title", null, true,
                packageName, "설정", null, null, "android.widget.TextView", null,
                new int[]{20, 20, 500, 70}, false, true, false, false, false, null, window);
        AccessibilityNodeSnapshot display = new AccessibilityNodeSnapshot("display", null, true,
                packageName, "디스플레이", null, null, "android.widget.TextView", null,
                new int[]{20, top, 900, top + 100}, true, true, false, false, false, null, window);
        return new ScreenSnapshot(packageName, Instant.now().toString(), width, height, List.of(title, display), window);
    }

    private void deliver(int index, String commandType, String action, String direction) throws Exception {
        AgentTurnContract.Request request = requests.get(index);
        JSONObject guide = new JSONObject().put("guide_command_id", "returned-guide")
                .put("command_type", commandType).put("action_type", action).put("requires_user_action", true)
                .put("target_node_id", "display").put("target_bounds", new JSONArray(request.accessibilityTree.nodes.get(1).bounds))
                .put("message", "대상 안내").put("render_policy", new JSONObject().put("direction", direction));
        if ("highlight".equals(commandType) && "observe_only".equals(action)) {
            guide.remove("target_node_id");
            guide.remove("target_bounds");
            guide.put("requires_user_action", false);
        }
        JSONObject metadata = new JSONObject();
        if ("confirmation".equals(commandType)) metadata.put("confirmation", new JSONObject()
                .put("id", "confirmation-id").put("expires_at", System.currentTimeMillis() / 1000 + 120));
        JSONObject response = new JSONObject().put("request_id", request.requestId).put("session_id", request.sessionId)
                .put("session_version", 7).put("episode_id", "episode").put("task_status", "IN_PROGRESS")
                .put("state_id", "S1_SETTINGS_HOME").put("guide_command", guide).put("metadata", metadata);
        callbacks.get(index).onSuccess(new AgentTurnContract.Response(response.toString()));
    }

    private void control(String command) throws Exception {
        Method method = ClickOverlayService.class.getDeclaredMethod("sendAgentControl", String.class);
        method.setAccessible(true);
        method.invoke(service, command);
    }

    @Test public void staleVisionHighlightIsRemovedBeforeServerResponds() throws Exception {
        ScreenSnapshot before = new ScreenSnapshot("com.android.settings", Instant.now().toString(),
                1080, 2400, List.of(), 42).withScreenRevision("epoch:1");
        ScreenSnapshot after = before.withScreenRevision("epoch:2");
        WindowManager manager = mock(WindowManager.class);
        GuideOverlayView guide = mock(GuideOverlayView.class);
        when(guide.isAttachedToWindow()).thenReturn(true);
        set("windowManager", manager);
        set("guideOverlayView", guide);
        set("visionGuideScreen", before);
        set("lastTargetBounds", new int[]{20, 100, 900, 200});
        set("lastTargetNodeId", "vision:ocr_0");
        set("turnInFlight", true);
        cachedScreen = after;
        invoke("invalidateStaleGuide");
        verify(guide).setVisibility(android.view.View.GONE);
        assertNull(get("visionGuideScreen"));
        verify(gateway, never()).send(any(), any());

    }

    @Test public void changedScreenCannotReplaceNavigationRecoveryWithEndlessWaiting() throws Exception {
        prepareGuideResponse();
        deliver(1, "highlight", "tap", null);
        NavigationRecovery recovery = (NavigationRecovery) get("navigationRecovery");
        recovery.forceHome();
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 43, 400);
        cachedScreen = currentScreen;
        TaskFlowOverlayView card = (TaskFlowOverlayView) get("taskFlowView");
        clearInvocations(card);
        ((BroadcastReceiver) get("collapseReceiver")).onReceive(service,
                new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED));
        verify(card).navigationRecovery(eq(false), anyString(), any());
        verify(card, never()).waitingProgress(contains("화면이 바뀌었어요"));
        assertEquals("Keep explicit recovery instead of sending behind its back", 2, requests.size());
    }

    @Test public void invalidatedGuideReobservesWithoutAnotherAccessibilityBroadcast() throws Exception {
        prepareGuideResponse();
        deliver(1, "highlight", "tap", null);
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 43, 400);
        cachedScreen = currentScreen;
        invoke("invalidateStaleGuide");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
        assertEquals(3, requests.size());
        assertSame(currentScreen, requests.get(2).accessibilityTree);
        assertNull("Re-observation must not invent a successful tap", requests.get(2).userActionEvent);
    }

    @Test public void pausedTaskIsNotReplacedByScreenChangedWaiting() throws Exception {
        prepareGuideResponse();
        deliver(1, "highlight", "tap", null);
        set("agentPaused", true);
        cachedScreen = changedScreen("com.android.settings", 1080, 2400, 43, 400);
        TaskFlowOverlayView card = (TaskFlowOverlayView) get("taskFlowView");
        clearInvocations(card);
        invoke("invalidateStaleGuide");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        verify(card, never()).waitingProgress(anyString());
        assertEquals(2, requests.size());
    }

    @Test public void repeatedManualBackAcrossNewWindowsSendsCurrentScreenAndResumesGuidance() throws Exception {
        prepareGuideResponse(); deliver(1, "highlight", "tap", null);
        NavigationRecovery recovery = (NavigationRecovery) get("navigationRecovery");
        for (int i = 0; i < 2; i++) {
            ScreenSnapshot child = changedScreen("com.android.settings", 1080, 2400, 50 + i, 500);
            recovery.observe(child); recovery.requestedByUser(NavigationRecovery.Action.BACK, child);
            currentScreen = changedScreen("com.android.settings", 1080, 2400, 60 + i, 100);
            cachedScreen = currentScreen;
            ((BroadcastReceiver) get("collapseReceiver")).onReceive(service,
                    new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED));
            assertEquals(3 + i, requests.size());
            assertSame(currentScreen, requests.get(2 + i).accessibilityTree);
            assertEquals("back_navigated", requests.get(2 + i).userActionEvent.getString("action_type"));
            deliver(2 + i, "highlight", "tap", null);
            assertEquals(NavigationRecovery.Action.NONE, recovery.hint());
            assertNotNull(get("nativeGuideScreen"));
        }
    }

    @Test public void resyncAtRetryLimitShowsAnActionInsteadOfLeavingWaitingText() throws Exception {
        prepareGuideResponse(); deliver(1, "highlight", "tap", null);
        set("staleGuideDrops", 4);
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 43, 400);
        cachedScreen = currentScreen;
        invoke("invalidateStaleGuide");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        verify((TaskFlowOverlayView) get("taskFlowView")).observationRecovery(contains("현재 위치"), any());
        assertEquals(2, requests.size());
    }

    @Test public void validReplyCancelsDeferredResyncAndDoesNotSendDuplicateTurns() throws Exception {
        prepareGuideResponse(); deliver(1, "highlight", "tap", null);
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 43, 400);
        cachedScreen = currentScreen;
        ((BroadcastReceiver) get("collapseReceiver")).onReceive(service,
                new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED));
        assertEquals(3, requests.size());
        deliver(2, "highlight", "tap", null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(3, requests.size());
    }

    @Test public void scrollingToAccessibilityClearsNativeGuideBeforePendingServerReply() throws Exception {
        prepareGuideResponse();
        deliver(1, "highlight", "tap", null);
        GuideOverlayView guide = (GuideOverlayView) get("guideOverlayView");
        TutorialNarrator narrator = (TutorialNarrator) get("narrator");
        clearInvocations(guide, narrator);
        set("turnInFlight", true);
        AccessibilityNodeSnapshot changed = new AccessibilityNodeSnapshot("display", null, true,
                "com.android.settings", "접근성", null, null, "android.widget.TextView", null,
                screen.nodes.get(1).bounds, true, true, false, false, false, null, 42);
        cachedScreen = new ScreenSnapshot("com.android.settings", Instant.now().toString(),
                1080, 2400, List.of(screen.nodes.get(0), changed), 42);
        ((BroadcastReceiver) get("collapseReceiver")).onReceive(service,
                new Intent(OverlayActions.ACTION_SNAPSHOT_UPDATED));
        verify(guide).setVisibility(android.view.View.GONE);
        verify(narrator).stop();
        assertNull(get("nativeGuideScreen"));
        assertEquals(true, get("forceNextObservation"));
        assertEquals("Wait for the pending reply before sending another request", 2, sent.size());
    }

    @Test public void automationOffNeverExecutesAgentScroll() throws Exception {
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(false);
        JSONObject body = new JSONObject().put("request_id", "scroll").put("session_id", "frontend-session")
                .put("task_status", "IN_PROGRESS").put("guide_command", new JSONObject()
                    .put("guide_command_id", "scroll-guide").put("command_type", "gesture_hint")
                    .put("action_type", "scroll").put("message", "아래로 스크롤해 주세요."));
        AgentTurnContract.Response response = new AgentTurnContract.Response(body.toString());
        Method execute = ClickOverlayService.class.getDeclaredMethod("executeScroll", AgentTurnContract.GuideCommand.class);
        execute.setAccessible(true); execute.invoke(service, response.guideCommand);
        accessibility.verify(() -> ClickAccessibilityService.performScroll(anyBoolean()), never());
    }

    @Test public void emptyLauncherObservationNeverStartsBlindSwipes() throws Exception {
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(false);
        currentScreen = new ScreenSnapshot("com.android.launcher3", Instant.now().toString(), 1080, 2400, List.of(), 42);
        resolve(); confirm();
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(anyBoolean()), never());
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(anyBoolean(), anyString(), any()), never());
        assertEquals("start_task", sent.get(1).getString("command_type"));
    }

    private ScreenSnapshot launcher(boolean settingsVisible) {
        String pkg = "com.sec.android.app.launcher";
        AccessibilityNodeSnapshot folder = new AccessibilityNodeSnapshot("folder", null, true, pkg, "Google", null,
                pkg + ":id/home_icon", "android.widget.TextView", "menu_item", new int[]{40, 200, 260, 400},
                true, true, false, false, false, null, 43);
        AccessibilityNodeSnapshot settings = new AccessibilityNodeSnapshot("settings", null, settingsVisible, pkg, "설정", "설정",
                pkg + ":id/home_icon", "android.widget.TextView", "menu_item", new int[]{40, 1200, 260, 1400},
                true, true, false, false, false, null, 43);
        return new ScreenSnapshot(pkg, Instant.now().toString(), 1080, 2400, List.of(folder, settings), 43);
    }

    @Test public void olderServerWaitCanOfferSettingsEntryOnlyAtTheLauncher() throws Exception {
        prepareGuideResponse();
        Method method=ClickOverlayService.class.getDeclaredMethod("canOfferSettingsEntry",AgentTurnContract.Response.class,ScreenSnapshot.class);
        method.setAccessible(true);
        for (String reason : new String[]{"LOW_CONFIDENCE","MODEL_ABSTAINED","MODEL_WAIT_WITHOUT_ACTION","MODEL_UNAVAILABLE","APP_NOT_OPEN","ACTION_BUDGET_EXHAUSTED"}) {
            JSONObject body=new JSONObject().put("request_id","old-server").put("session_id","frontend-session")
                .put("state_id","S0_UNKNOWN").put("task_status","IN_PROGRESS")
                .put("guide_command",new JSONObject().put("guide_command_id","old-wait").put("command_type","highlight")
                    .put("action_type","observe_only").put("message","다시 시도해 주세요.").put("render_policy",new JSONObject()
                        .put("recovery",new JSONObject().put("waiting_for","user").put("reason_code",reason))));
            AgentTurnContract.Response response=new AgentTurnContract.Response(body.toString());
            assertEquals(!"ACTION_BUDGET_EXHAUSTED".equals(reason),method.invoke(service,response,launcher(true)));
            assertEquals(false,method.invoke(service,response,screen));
            body.getJSONObject("guide_command").getJSONObject("render_policy").getJSONObject("recovery").put("waiting_for","observation");
            assertEquals(false,method.invoke(service,new AgentTurnContract.Response(body.toString()),launcher(true)));
        }
    }

    @Test public void explicitSettingsEntryRecoveryTakesPriorityOverGenericHomeRecovery() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false);
        JSONObject body = new JSONObject().put("request_id", "entry-recovery").put("session_id", "frontend-session")
                .put("state_id", "S0_UNKNOWN").put("task_status", "IN_PROGRESS")
                .put("guide_command", new JSONObject().put("guide_command_id", "settings-entry")
                    .put("command_type", "highlight").put("action_type", "observe_only")
                    .put("message", "설정 열기").put("render_policy", new JSONObject()
                        .put("recovery", new JSONObject().put("waiting_for", "user").put("reason_code", "APP_NOT_OPEN"))
                        .put("recovery_action", new JSONObject().put("type", "open_android_settings_or_show_app_drawer")
                            .put("android_intent_action", android.provider.Settings.ACTION_SETTINGS))));
        Method method = ClickOverlayService.class.getDeclaredMethod("showNavigationRecoveryIfNeeded",
                AgentTurnContract.Response.class, ScreenSnapshot.class);
        method.setAccessible(true);
        ScreenSnapshot other = new ScreenSnapshot("com.click", Instant.now().toString(),1080,2400,List.of(),42);
        assertEquals(false, method.invoke(service, new AgentTurnContract.Response(body.toString()), other));
        verify((TaskFlowOverlayView)get("taskFlowView"), never()).navigationRecovery(anyBoolean(),anyString(),any());
    }

    @Test public void settingsEntryButtonClearsPriorWaitAndLaunchesOnlyOnUserActivation() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false);
        Method show = ClickOverlayService.class.getDeclaredMethod("showSettingsEntryRecovery");
        show.setAccessible(true); show.invoke(service);
        org.mockito.ArgumentCaptor<Runnable> click=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify((TaskFlowOverlayView)get("taskFlowView")).settingsEntryRecovery(click.capture());
        assertNull(shadowOf(service).getNextStartedActivity());
        assertEquals(true,get("waitingForExplicitRetry"));
        click.getValue().run();
        assertEquals(android.provider.Settings.ACTION_SETTINGS,shadowOf(service).getNextStartedActivity().getAction());
        assertEquals(false,get("waitingForExplicitRetry"));
        assertEquals(AgentActionEvents.MANUAL_OVERRIDE,get("pendingActionType"));
    }

    @Test public void obsoleteSettingsEntryActionCannotLaunchAfterTheTaskChanges() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false);
        Method show = ClickOverlayService.class.getDeclaredMethod("showSettingsEntryRecovery");
        show.setAccessible(true); show.invoke(service);
        org.mockito.ArgumentCaptor<Runnable> click=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify((TaskFlowOverlayView)get("taskFlowView")).settingsEntryRecovery(click.capture());
        set("executionGeneration",(Integer)get("executionGeneration")+1);
        click.getValue().run();
        assertNull(shadowOf(service).getNextStartedActivity());
    }

    @Test public void launcherSearchContinuesAfterFirstTurnAndIgnoresHiddenSettingsEvenWithAutomationOff() throws Exception {
        prepareGuideResponse();
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(false);
        set("turnInFlight", false); set("firstTurnSent", true);
        currentScreen = launcher(false);
        accessibility.when(() -> ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43))
                .thenAnswer(call -> { currentScreen = launcher(true); return true; });
        send(null);
        assertEquals("Wait for navigation before sending another observation", 2, requests.size());
        verify((ClickOverlayLayout) get("overlayView")).setVisibility(android.view.View.VISIBLE);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43));
        assertEquals(3, requests.size());
        assertSame(currentScreen, requests.get(2).accessibilityTree);
        assertTrue(requests.get(2).accessibilityTree.nodes.get(1).visibleToUser);
    }

    @Test public void serverResolvedBrightnessGoalAlsoSearchesLauncher() throws Exception {
        prepareGuideResponse();
        set("turnInFlight", false); set("firstTurnSent", true);
        set("initialGoal", "밝기를 높여줘");
        set("acceptedGoal", new JSONObject().put("target_app", "android_settings").put("goal_type", "settings.brightness.increase"));
        currentScreen = launcher(false);
        send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43));
    }

    @Test public void serverReplyAfterHomeNavigationStartsSearchWithoutAnotherNativeEvent() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false); set("firstTurnSent", true);
        currentScreen = launcher(false);
        set("pendingActionType", AgentActionEvents.HOME_NAVIGATED);
        send(null);
        assertEquals(3, requests.size());
        accessibility.when(() -> ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43))
                .thenAnswer(call -> { currentScreen = launcher(true); return true; });
        deliver(2, "highlight", "observe_only", null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43));
        assertEquals(4, requests.size());
        assertSame(currentScreen, requests.get(3).accessibilityTree);
    }

    @Test public void changingLauncherWidgetDoesNotCancelScheduledPageSearch() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false); set("firstTurnSent", true);
        currentScreen = launcher(false);
        send(null);
        List<AccessibilityNodeSnapshot> nodes = new ArrayList<>(currentScreen.nodes);
        nodes.add(new AccessibilityNodeSnapshot("clock", null, true, currentScreen.packageName,
                "11:24", null, "clock_widget", "android.widget.TextView", null,
                new int[]{100, 20, 800, 150}, false, true, false, false, false, null, 43));
        currentScreen = new ScreenSnapshot(currentScreen.packageName, Instant.now().toString(), 1080, 2400, nodes, 43);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43));
    }

    @Test public void settingsIconOutsideScreenDoesNotStopPageSearch() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false); set("firstTurnSent", true);
        ScreenSnapshot home = launcher(true);
        home.nodes.get(1).bounds[0] = 1200; home.nodes.get(1).bounds[2] = 1400;
        currentScreen = home;
        send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43));
    }

    @Test public void launcherSearchReversesAtTheEndAndStopsOnAnUnchangedPage() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false); set("firstTurnSent", true);
        set("windowParams", new WindowManager.LayoutParams());
        currentScreen = launcher(false);
        send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(true, "com.sec.android.app.launcher", 43));
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(false, "com.sec.android.app.launcher", 43));
        assertEquals(3, requests.size());
        assertEquals(-1, get("scrollSearchGeneration"));
    }

    @Test public void pauseOrWindowChangeBeforeLauncherSwipePreventsMovement() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false); set("firstTurnSent", true);
        currentScreen = launcher(false);
        send(null); set("agentPaused", true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(anyBoolean(), anyString(), any()), never());
        assertEquals(-1, get("scrollSearchGeneration"));
        set("agentPaused", false);
        send(null); currentScreen = screen;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        accessibility.verify(() -> ClickAccessibilityService.swipeHomePage(anyBoolean(), anyString(), any()), never());
    }

    @Test public void expiredRequestKeepsAnExplanationVisibleUntilTheUserChooses() throws Exception {
        prepareGuideResponse(); set("turnInFlight", false);
        Method apply = ClickOverlayService.class.getDeclaredMethod("applyTaskFlowResponse", AgentTurnContract.Response.class);
        apply.setAccessible(true);
        apply.invoke(service, new AgentTurnContract.Response(new JSONObject()
                .put("request_id", "expired").put("session_id", "frontend-session")
                .put("task_status", "EXPIRED").put("metadata", new JSONObject()).toString()));
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        assertNotNull(panel);
        assertEquals("안내를 잠시 멈췄어요", panel.phaseLabel());
        assertEquals(android.view.View.VISIBLE, panel.getVisibility());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));
        assertSame(panel, get("taskFlowView"));
        assertNull(get("agentSessionId"));
    }

    @Test public void reconnectingAccessibilityRestoresBothLogoAndExpandedTaskWindow() throws Exception {
        prepareGuideResponse();
        WindowManager previous = (WindowManager) get("windowManager"), replacement = mock(WindowManager.class);
        ClickOverlayLayout logo = (ClickOverlayLayout) get("overlayView");
        TaskFlowOverlayView panel = (TaskFlowOverlayView) get("taskFlowView");
        when(logo.isAttachedToWindow()).thenReturn(true); when(panel.isAttachedToWindow()).thenReturn(true);
        set("windowParams", new WindowManager.LayoutParams());
        set("overlayWindowType", WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        set("taskFlowCollapsed", false);
        Method rebind = ClickOverlayService.class.getDeclaredMethod("rebindWindows", WindowManager.class, int.class);
        rebind.setAccessible(true);
        rebind.invoke(service, replacement, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        verify(previous).removeViewImmediate(panel);
        verify(replacement).addView(eq(panel), any());
        verify(replacement).addView(eq(logo), any());
        verify(panel).setVisibility(android.view.View.VISIBLE);
    }

    @Test public void oneBackRecoveryIsHighlightedAndEscalatesWhenUnchanged() throws Exception {
        resolve(); confirm(); set("turnInFlight", false); set("firstTurnSent", true);
        NavigationRecovery recovery = (NavigationRecovery)get("navigationRecovery");
        recovery.onPath(screen); recovery.unplannedTap();
        currentScreen = new ScreenSnapshot("com.android.settings", Instant.now().toString(), 1080, 2400, List.of(), 42);
        JSONObject body = new JSONObject().put("request_id", "recovery").put("session_id", "frontend-session")
                .put("state_id", "S0_UNKNOWN").put("task_status", "IN_PROGRESS")
                .put("guide_command", new JSONObject().put("guide_command_id", "recover-guide")
                    .put("command_type", "highlight").put("action_type", "observe_only")
                    .put("message", "화면을 확인해 주세요.").put("render_policy", new JSONObject()
                        .put("recovery", new JSONObject().put("waiting_for", "user").put("reason_code", "STATE_UNKNOWN"))));
        AgentTurnContract.Response response = new AgentTurnContract.Response(body.toString());
        GuideStepTimer timer=(GuideStepTimer)get("guideStepTimer");
        timer.start("frontend-session", "S1_SETTINGS_HOME", 1, "com.android.settings", 1000);
        Method render=ClickOverlayService.class.getDeclaredMethod("showNavigationRecoveryIfNeeded",
                AgentTurnContract.Response.class,ScreenSnapshot.class);
        render.setAccessible(true);assertEquals(true,render.invoke(service,response,currentScreen));
        assertNull(timer.firstTap("com.android.settings",5000));
        org.mockito.ArgumentCaptor<Runnable> click=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        TaskFlowOverlayView card=(TaskFlowOverlayView)get("taskFlowView");
        verify(card).navigationRecovery(eq(true),contains("뒤로가기"),click.capture());
        accessibility.when(() -> ClickAccessibilityService.performRecoveryNavigation(true)).thenReturn(true);
        click.getValue().run();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2600));
        verify(card).navigationRecovery(eq(false),contains("홈 버튼"),any());
        assertEquals(NavigationRecovery.Action.HOME,recovery.hint());
    }

    @Test public void automaticFontFlowAcceptsFinalApprovalThenExecutesSliderWithoutNewPolicyField() throws Exception {
        assertAutomaticFontFlow(null);
    }

    @Test public void jevFontFlowAcceptsNativeApprovalAndExecutesSlider() throws Exception {
        assertAutomaticFontFlow(new JSONObject().put("ui_selector", "jev")
                .put("revalidate_before_action", true).put("runtime_strategy", "continue"));
    }

    private void assertAutomaticFontFlow(JSONObject policy) throws Exception {
        new com.click.data.local.TouchAutomationSettings(service).setEnabled(true);
        int[] bounds={20,500,1000,600};
        AccessibilityNodeSnapshot slider=new AccessibilityNodeSnapshot("node_0_1","node_0",true,
                "com.android.settings",null,null,"com.android.settings:id/font_size","android.widget.SeekBar","slider",bounds,
                false,true,false,false,false,2f,42,false,0f,7f);
        currentScreen=new ScreenSnapshot("com.android.settings",Instant.now().toString(),1080,2400,List.of(slider),42);
        resolve();confirm();
        JSONObject metadata=new JSONObject().put("confirmation",new JSONObject().put("id","font-confirmation")
                    .put("expires_at",System.currentTimeMillis()/1000+120))
                .put("typed_goal",new JSONObject().put("target_app","android_settings")
                    .put("slots",new JSONObject().put("setting","font_size").put("direction","increase")));
        JSONObject guide=new JSONObject().put("guide_command_id","font-confirm").put("command_type","confirmation")
                .put("action_type","observe_only").put("proposed_action","drag").put("requires_user_action",true)
                .put("target_node_id","node_0_1").put("target_bounds",new JSONArray(bounds)).put("message","크기를 바꿀까요?");
        if (policy != null) guide.put("render_policy", policy);
        JSONObject body=new JSONObject().put("request_id",requests.get(1).requestId).put("session_id","frontend-session")
                .put("episode_id","episode").put("session_version",1).put("task_status","IN_PROGRESS")
                .put("state_id","S4_FONT_SIZE_CONTROL").put("guide_command",guide).put("metadata",metadata);
        callbacks.get(1).onSuccess(new AgentTurnContract.Response(body.toString()));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));
        assertEquals("respond_confirmation",sent.get(2).getString("command_type"));
        assertEquals("accepted",sent.get(2).getString("user_confirmation"));
        accessibility.when(()->ClickAccessibilityService.performGuidedSlider(any(),eq("node_0_1"),any(),eq(1),eq("font_size"))).thenReturn(true);
        guide.put("guide_command_id","font-drag").put("command_type","highlight").put("action_type","drag");
        metadata.remove("confirmation");
        body.put("request_id",requests.get(2).requestId).put("session_version",2);
        callbacks.get(2).onSuccess(new AgentTurnContract.Response(body.toString()));
        accessibility.verify(()->ClickAccessibilityService.performGuidedSlider(any(),eq("node_0_1"),any(),eq(1),eq("font_size")));
    }

    @Test public void waitingResponseKeepsOriginalActionBindingForDelayedAutomaticVerification() throws Exception {
        prepareGuideResponse();deliver(1,"highlight","tap",null);
        GuidedActionTracker tracker=(GuidedActionTracker)get("actionTracker");
        assertTrue(tracker.isBoundToGuide("returned-guide"));
        JSONObject body=new JSONObject().put("request_id","wait").put("session_id","frontend-session")
                .put("task_status","IN_PROGRESS").put("state_id","S1_SETTINGS_HOME")
                .put("guide_command",new JSONObject().put("guide_command_id","returned-guide")
                    .put("command_type","highlight").put("action_type","observe_only")
                    .put("message","조작 결과를 확인하고 있어요."));
        Method render=ClickOverlayService.class.getDeclaredMethod("renderGuide",AgentTurnContract.Response.class);
        render.setAccessible(true);render.invoke(service,new AgentTurnContract.Response(body.toString()));
        assertTrue(tracker.isBoundToGuide("returned-guide"));
    }

    @Test public void manualSettingsHandoffOffersResultCheckAndStopsFurtherScreenUploads() throws Exception {
        prepareGuideResponse();
        AgentTurnContract.Request request = requests.get(1);
        JSONObject guide = new JSONObject().put("guide_command_id", "manual-settings")
                .put("command_type", "highlight").put("action_type", "observe_only")
                .put("requires_user_action", false).put("message", "원하는 항목을 직접 선택하세요.")
                .put("render_policy", new JSONObject().put("manual_handoff", true).put("allow_result_check", true));
        JSONObject body = new JSONObject().put("request_id", request.requestId).put("session_id", request.sessionId)
                .put("session_version", 7).put("episode_id", "episode").put("task_status", "IN_PROGRESS")
                .put("state_id", "S_TARGET").put("guide_command", guide).put("metadata", new JSONObject());
        callbacks.get(1).onSuccess(new AgentTurnContract.Response(body.toString()));
        assertEquals(true, get("manualSettingsHandoff"));
        verify((TaskFlowOverlayView) get("taskFlowView")).progress(any(), any(), any(), eq(true));
        int count = requests.size();
        // Even a forced snapshot refresh must not resume exporting personal inputs.
        send(null);
        assertEquals(count, requests.size());
    }

    @Test public void transientTurnFailureRetainsExactActionEvidenceAndWaitsForExplicitRecovery() throws Exception {
        prepareGuideResponse(); deliver(1, "highlight", "drag", "right");
        JSONObject event = new JSONObject().put("event_id", "original-event")
                .put("action_type", "drag_completed").put("guide_command_id", "original-guide")
                .put("occurred_at_ms", 1234L).put("episode_id", "episode");
        set("pendingVerifiedAction", event); send(null);
        int index = requests.size() - 1;
        assertSame(event, requests.get(index).userActionEvent);
        callbacks.get(index).onFailure(new java.net.SocketTimeoutException("timeout"));
        assertSame("A lost response must not erase the performed action", event, get("pendingVerifiedAction"));
        assertEquals(true, get("waitingForExplicitRetry"));
        int count = requests.size(); send(null);
        assertEquals("Do not send or repeat an operation before explicit recovery", count, requests.size());
    }

    @Test public void newUnsentEvidenceIsObservedBeforeRequestingAResult() throws Exception {
        screen = fontScreen(2f, 1f, 1000); currentScreen = screen;
        prepareGuideResponse(); deliver(1, "highlight", "drag", "right");
        currentScreen = fontScreen(3f, 1.15f, 2000); send(null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        JSONObject event = new JSONObject().put("event_id", "late-action")
                .put("action_type", "drag_completed").put("guide_command_id", "original-guide")
                .put("occurred_at_ms", 1500L).put("episode_id", "episode");
        set("pendingVerifiedAction", event);
        deliver(2, "highlight", "observe_only", null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        AgentTurnContract.Request next = requests.get(3);
        assertEquals("observe", next.commandType());
        assertSame("Send the original action before a tree-less result request", event, next.userActionEvent);
    }

    private void resolve() throws Exception {
        Method method = ClickOverlayService.class.getDeclaredMethod("resolveGoal", String.class, String.class);
        method.setAccessible(true);
        method.invoke(service, SOURCE, null);
    }

    private void confirm() throws Exception {
        invoke("confirmRequirementReview");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }

    private void send(String confirmation) throws Exception {
        Method method = ClickOverlayService.class.getDeclaredMethod("sendAgentTurn", String.class, boolean.class);
        method.setAccessible(true);
        method.invoke(service, confirmation, true);
    }

    private void invoke(String name) throws Exception {
        Method method = ClickOverlayService.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(service);
    }

    private void set(String name, Object value) throws Exception {
        Field field = ClickOverlayService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private Object get(String name) throws Exception {
        Field field = ClickOverlayService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }

    private JSONObject resource(String name) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(name, in);
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void export(String name, JSONObject request) throws Exception {
        String output = System.getProperty("click.contractOutput");
        assertNotNull("Gradle must configure the FE/BE contract output directory", output);
        Path directory = Path.of(output);
        Files.createDirectories(directory);
        Files.write(directory.resolve(name + ".json"), request.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
