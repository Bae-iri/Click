package com.click.data.remote;

import android.app.UiAutomation;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.RequirementReview;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in real API regression with the Galaxy combined font page; device settings stay unchanged. */
@RunWith(AndroidJUnit4.class)
public class LiveFontGuideTest {
    @Test public void settingsRouteDoesNotFollowVisibleAccessibilityRow() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveSettings")));
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AuthenticatedHttpClient http = new AuthenticatedHttpClient(context);
        AgentServerContract contract = AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json", null).body));
        GoalCatalogClassifier catalog;
        try (java.io.InputStream input = context.getAssets().open("mvp_goals.json")) {
            catalog = GoalCatalogClassifier.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        RequirementReview review = new RequirementReview("휴대폰 글자 크기 키워줘");
        review.choose("phone");
        String session = UUID.randomUUID().toString();
        AgentTurnContract.Request request = new AgentTurnContract.Request(session, review.summary(), settingsScreen(true, false), null);
        request.acceptedGoal = review.confirm(catalog, contract);
        try {
            JSONObject result = send(http, contract, request);
            assertEquals("display", result.getJSONObject("guide_command").optString("target_node_id"));
            request = AgentTurnContract.Request.control(session, result.getInt("session_version"),
                    result.getString("episode_id"), "observe", settingsScreen(false, false));
            result = send(http, contract, request);
            JSONObject guide = result.getJSONObject("guide_command");
            android.util.Log.i("ClickFontCheck", "navigation graph=" + result.optJSONObject("graph_reference")
                    + " state=" + result.optString("state_id") + " target=" + guide.optString("target_node_id")
                    + " message=" + guide.optString("message") + " policy=" + guide.optJSONObject("render_policy"));
            assertTrue("Offscreen Display must not be replaced by Accessibility", guide.isNull("target_node_id"));
            assertTrue(guide.getString("message").contains("디스플레이"));
            assertEquals("TARGET_NOT_VISIBLE", guide.getJSONObject("render_policy").getJSONObject("recovery").getString("reason_code"));
            request = AgentTurnContract.Request.control(session, result.getInt("session_version"),
                    result.getString("episode_id"), "observe", settingsScreen(false, true));
            result = send(http, contract, request);
            assertEquals("navigate_back", result.getJSONObject("guide_command").getJSONObject("render_policy")
                    .getJSONObject("recovery_action").getString("type"));
        } finally {
            try {
                JSONObject state = new JSONObject(http.get("/api/v1/agent/sessions/" + session).body);
                if (!"CANCELLED".equals(state.optString("status"))) send(http, contract,
                        AgentTurnContract.Request.control(session, state.getInt("version"),
                                state.optString("episode_id", null), "cancel_task", null));
            } catch (ApiException error) { if (error.status != 404) throw error; }
        }
    }

    private ScreenSnapshot settingsScreen(boolean displayVisible, boolean wrongPage) {
        java.util.ArrayList<AccessibilityNodeSnapshot> nodes = new java.util.ArrayList<>();
        nodes.add(new AccessibilityNodeSnapshot("list", null, true, "com.android.settings", null, null,
                "android:id/list", "androidx.recyclerview.widget.RecyclerView", null,
                new int[]{0, 240, 1080, 2200}, false, true, false, true, false, null, 42));
        nodes.add(node("title", wrongPage ? "접근성" : "설정", null, "title", "android.widget.TextView", null, 80, false, null));
        nodes.add(node("accessibility", wrongPage ? "시인성 향상" : "접근성", null, "title", "android.widget.TextView", null, 600, true, null));
        if (displayVisible) nodes.add(node("display", "디스플레이", null, "title", "android.widget.TextView", null, 300, true, null));
        if (wrongPage) nodes.add(new AccessibilityNodeSnapshot("back", null, true, "com.android.settings",
                null, "뒤로", "com.android.settings:id/back", "android.widget.ImageButton", null,
                new int[]{0, 80, 80, 170}, true, true, false, false, false, null, 42));
        return new ScreenSnapshot("com.android.settings", Instant.now().toString(), 1080, 2400, nodes, 42, true, 1.1f);
    }

    @Test public void samsungCombinedPageSelectsSliderBeforeResult() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveFont")));
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AuthenticatedHttpClient http = new AuthenticatedHttpClient(context);
        AgentServerContract contract = AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json", null).body));
        GoalCatalogClassifier catalog;
        try (java.io.InputStream input = context.getAssets().open("mvp_goals.json")) {
            catalog = GoalCatalogClassifier.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        RequirementReview review = new RequirementReview("휴대폰 글자 크기 키워줘");
        review.choose("phone");
        String session = UUID.randomUUID().toString();
        AgentTurnContract.Request request = new AgentTurnContract.Request(session, review.summary(), screen(), null);
        request.acceptedGoal = review.confirm(catalog, contract);
        try {
            JSONObject result = send(http, contract, request);
            JSONObject guide = result.getJSONObject("guide_command");
            android.util.Log.i("ClickFontCheck", "graph=" + result.optJSONObject("graph_reference")
                    + " state=" + result.optString("state_id") + " command=" + guide.optString("command_type")
                    + " policy=" + guide.optJSONObject("render_policy"));
            assertEquals("The combined font screen must select the actual slider", "font-slider", guide.optString("target_node_id"));
            assertEquals("S4_FONT_SIZE_CONTROL", result.optString("state_id"));
            assertEquals("WAITING_CONFIRMATION", result.optString("task_status"));
            AgentTurnContract.Request consent = new AgentTurnContract.Request(session, null, screen(), "accepted");
            consent.expectedVersion = result.getInt("session_version"); consent.episodeId = result.getString("episode_id");
            consent.confirmationId = result.getJSONObject("metadata").getJSONObject("confirmation").getString("id");
            result = send(http, contract, consent);
            guide = result.getJSONObject("guide_command");
            assertEquals("highlight", guide.getString("command_type"));
            assertEquals("drag", guide.getString("action_type"));
            assertEquals("font-slider", guide.getString("target_node_id"));
            long actionAt = System.currentTimeMillis();
            android.os.SystemClock.sleep(750);
            AgentTurnContract.Request changed = AgentTurnContract.Request.control(session,
                    result.getInt("session_version"), result.getString("episode_id"), "observe", screen(5f, 1.5f));
            changed.userActionEvent = AgentActionEvents.of(AgentActionEvents.DRAG_COMPLETED,
                    guide.getString("guide_command_id"), "font-slider", new int[]{135, 2009, 945, 2071}, actionAt);
            changed.userActionEvent.put("episode_id", result.getString("episode_id"));
            result = send(http, contract, changed);
            assertEquals("A verified font change must reach the result question", "WAITING_RESULT", result.getString("task_status"));
            assertNotNull(result.getJSONObject("metadata").optJSONObject("result_prompt"));
            assertEquals("CHANGED", result.getJSONObject("metadata").getJSONObject("task_outcome")
                    .getJSONObject("technical").getString("status"));
        } finally {
            try {
                JSONObject state = new JSONObject(http.get("/api/v1/agent/sessions/" + session).body);
                if (!"CANCELLED".equals(state.optString("status"))) send(http, contract,
                        AgentTurnContract.Request.control(session, state.getInt("version"),
                                state.optString("episode_id", null), "cancel_task", null));
            } catch (ApiException error) { if (error.status != 404) throw error; }
        }
    }
    private JSONObject send(AuthenticatedHttpClient http, AgentServerContract contract, AgentTurnContract.Request request) throws Exception {
        request.contractVersion = contract.contractVersion; request.interactiveWorkflow = contract.interactiveWorkflow;
        return new JSONObject(http.post(AgentTurnContract.PATH, request.toJson()).body);
    }
    private ScreenSnapshot screen() {
        return screen(4f, 1.3f);
    }
    private ScreenSnapshot screen(float value, float scale) {
        String p = "com.android.settings";
        return new ScreenSnapshot(p, Instant.now().toString(), 1080, 2400, List.of(
                node("title", "글자 크기와 스타일", null, "title", "android.widget.TextView", null, 179, false, null),
                node("family", "글꼴", null, "font_family", "android.widget.TextView", null, 1372, true, null),
                node("label", "글자 크기", null, "fontSize", "android.widget.TextView", null, 1826, false, null),
                node("smaller", null, "글자 크기 작게", "smaller", "android.widget.ImageView", null, 1995, true, null),
                new AccessibilityNodeSnapshot("font-slider", null, true, p, null, Integer.toString((int) value + 1), p + ":id/seekBarForFontSize",
                        "android.widget.SeekBar", "slider", new int[]{135, 2009, 945, 2071}, false, true,
                        false, false, false, value, 42, false, 0f, 7f),
                node("larger", null, "글자 크기 크게", "larger", "android.widget.ImageView", null, 1995, true, null)), 42, true, scale);
    }
    private AccessibilityNodeSnapshot node(String id, String text, String description, String resource,
            String type, String role, int top, boolean click, Float value) {
        return new AccessibilityNodeSnapshot(id, null, true, "com.android.settings", text, description,
                "com.android.settings:id/" + resource, type, role, new int[]{45, top, 1035, top + 90},
                click, true, false, false, false, value, 42);
    }
}
