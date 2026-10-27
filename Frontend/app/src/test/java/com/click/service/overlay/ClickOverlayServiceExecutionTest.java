package com.click.service.overlay;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import com.click.data.remote.AgentServerContract;
import com.click.data.remote.AgentTurnContract;
import com.click.data.remote.AgentTurnGateway;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.RequirementReview;
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
        accessibility.when(ClickAccessibilityService::freshSnapshot).thenAnswer(call -> {
            if (currentScreen != null) cachedScreen = currentScreen;
            return currentScreen;
        });
    }

    @After public void cleanup() {
        if (accessibility != null) accessibility.close();
        if (snapshots != null) snapshots.close();
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
        verify((WindowManager) get("windowManager")).removeView((GuideOverlayView) get("guideOverlayView"));
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

    @Test public void equivalentFreshCaptureCanRenderAndBindWithoutAnotherRequest() throws Exception {
        prepareGuideResponse();
        currentScreen = changedScreen("com.android.settings", 1080, 2400, 42, 100);
        assertNotEquals(screen.observationId, currentScreen.observationId);
        deliver(1, "highlight", "tap", null);
        verify((GuideOverlayView) get("guideOverlayView")).showTarget(any(), eq("tap"), eq(com.click.ui.overlay.GuideDragDirection.UNKNOWN));
        verify((TextToSpeech) get("textToSpeech")).speak(eq("대상 안내"), anyInt(), any(), anyString());
        assertEquals(2, sent.size());
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
        set("textToSpeech", mock(TextToSpeech.class));
        set("ttsReady", true);
        set("voiceReplyEnabled", true);
        service.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0);
    }

    private void assertStaleGuideSuppressed() throws Exception {
        verify((GuideOverlayView) get("guideOverlayView"), never()).showTarget(any(), any(), anyInt());
        verify((TaskFlowOverlayView) get("taskFlowView"), never()).progress(any(), any(), any(), anyBoolean());
        verify((TextToSpeech) get("textToSpeech"), never()).speak(anyString(), anyInt(), any(), anyString());
        assertNull(get("pendingSpeech"));
        assertNull(get("lastGuideCommandId"));
        accessibility.verify(ClickAccessibilityService::requestFreshSnapshot);
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
