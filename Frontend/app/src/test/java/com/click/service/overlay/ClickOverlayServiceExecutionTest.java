package com.click.service.overlay;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import com.click.data.remote.AgentServerContract;
import com.click.data.remote.AgentTurnContract;
import com.click.data.remote.AgentTurnGateway;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.RequirementReview;
import com.click.service.accessibility.ClickAccessibilityService;
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
    private MockedStatic<ClickAccessibilityService> accessibility;

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
        ScreenSnapshot screen = new ScreenSnapshot("com.android.settings", Instant.now().toString(),
                1080, 2400, List.of(title, display), 42);
        accessibility = mockStatic(ClickAccessibilityService.class);
        accessibility.when(ClickAccessibilityService::freshSnapshot).thenReturn(screen);
    }

    @After public void cleanup() {
        if (accessibility != null) accessibility.close();
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
