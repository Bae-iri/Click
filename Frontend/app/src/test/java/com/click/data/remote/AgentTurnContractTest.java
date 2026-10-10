package com.click.data.remote;

import com.click.data.snapshot.ScreenSnapshot;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgentTurnContractTest {
    @Test public void interactiveStartUsesV03AndExplicitWorkflow() throws Exception {
        ScreenSnapshot screen = new ScreenSnapshot("com.android.settings", "2026-10-10T00:00:00Z",
                1080, 2400, Collections.emptyList());
        AgentTurnContract.Request request = new AgentTurnContract.Request("session", "increase font size", screen, null);
        request.acceptedGoal = new JSONObject().put("catalog_version", "2.1.0")
                .put("goal_type", "settings.font_size.increase").put("target_app", "android_settings")
                .put("slots", new JSONObject().put("setting", "font_size").put("direction", "increase"));
        JSONObject body = new JSONObject(request.toJson());
        assertEquals("agent-fe-contract.v0.3", body.getString("contract_version"));
        assertEquals("interactive.v1", body.getString("workflow_version"));
        assertTrue(body.has("goal"));
        assertTrue(body.has("device_context"));
        request.contractVersion = "agent-fe-contract.v0.2";
        request.interactiveWorkflow = false;
        assertFalse(new JSONObject(request.toJson()).has("workflow_version"));
    }

    @Test public void resultUsesPromptAndSeparateSatisfactionField() throws Exception {
        AgentTurnContract.Request request = AgentTurnContract.Request.control("session", 3, "episode", "respond_result", null);
        request.resultPromptId = "prompt";
        request.userResult = "needs_help";
        JSONObject body = new JSONObject(request.toJson());
        assertEquals("prompt", body.getString("result_prompt_id"));
        assertEquals("needs_help", body.getString("user_result"));
        assertFalse(body.has("user_confirmation"));
        assertFalse(body.has("result_response"));
    }

    @Test public void adjustmentSendsReasonWithoutInventingScreen() throws Exception {
        AgentTurnContract.Request request = AgentTurnContract.Request.control("session", 4, "episode", "adjust_task", null);
        request.adjustmentReason = "too_large";
        JSONObject body = new JSONObject(request.toJson());
        assertEquals("too_large", body.getString("adjustment_reason"));
        assertFalse(body.has("goal"));
        assertFalse(body.has("accessibility_tree"));
    }

    @Test public void resolutionIncludesRequiredDeviceAndOmitsUnsupportedFields() throws Exception {
        AgentTurnContract.Request request = AgentTurnContract.Request.resolve("session", "font size", "increase");
        request.goalResolutionId = "old-local-id";
        JSONObject body = new JSONObject(request.toJson());
        assertEquals("android", body.getJSONObject("device_context").getString("platform"));
        assertTrue(body.getString("source_text").contains("increase"));
        assertFalse(body.has("goal_resolution_id"));
        assertFalse(body.has("clarification_choice"));
    }
}
