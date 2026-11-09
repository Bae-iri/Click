package com.click.data.remote;

import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.LocalGoalResolver;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AgentServerContractTest {
    @Test public void oldServersNeverReceiveNewSystemModeFields() throws Exception {
        JSONObject api;
        try(var in=getClass().getResourceAsStream("/openapi.json")) {
            api=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
        var snapshot=new com.click.data.snapshot.ScreenSnapshot("com.android.settings","2026-11-09T00:00:00Z",
            1080,2400,java.util.Collections.emptyList(),42).withNightMode(2);
        var request=AgentTurnContract.Request.control("s",1,"e","observe",snapshot);
        assertEquals(2,new JSONObject(AgentServerContract.fromOpenApi(api).encode(request))
            .getJSONObject("accessibility_tree").getJSONObject("applied_settings").getInt("night_mode"));
        JSONObject fields=api.getJSONObject("components").getJSONObject("schemas").getJSONObject("AppliedSettings").getJSONObject("properties");
        fields.remove("night_mode");fields.remove("night_mode_source");
        assertFalse(new JSONObject(AgentServerContract.fromOpenApi(api).encode(request))
            .getJSONObject("accessibility_tree").has("applied_settings"));
    }
    @Test public void deviceEvidenceUsesOnlyFieldsAcceptedByTheConnectedBackend() throws Exception {
        JSONObject current;
        try (java.io.InputStream in = getClass().getResourceAsStream("/openapi.json")) {
            current = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        com.click.data.snapshot.ScreenSnapshot screen = new com.click.data.snapshot.ScreenSnapshot(
                "com.android.settings", "2026-10-30T00:00:00Z", 1080, 2400,
                java.util.Collections.emptyList(), 42, true, 1.15f).withAdjustmentValues(120, 8);
        AgentTurnContract.Request request = AgentTurnContract.Request.control("session", 1, "episode", "observe", screen);
        JSONObject full = new JSONObject(AgentServerContract.fromOpenApi(current).encode(request))
                .getJSONObject("accessibility_tree").getJSONObject("applied_settings");
        assertEquals(120, full.getInt("screen_brightness"));
        assertEquals(8, full.getInt("media_volume"));
        JSONObject properties = current.getJSONObject("components").getJSONObject("schemas")
                .getJSONObject("AppliedSettings").getJSONObject("properties");
        for (String key : new String[]{"screen_brightness", "brightness_source", "media_volume", "media_volume_source"})
            properties.remove(key);
        JSONObject old = new JSONObject(AgentServerContract.fromOpenApi(current).encode(request))
                .getJSONObject("accessibility_tree").getJSONObject("applied_settings");
        assertEquals(2, old.length());
        assertEquals(1.15, old.getDouble("font_scale"), .001);
        assertEquals(120, new JSONObject(screen.toJson()).getJSONObject("applied_settings").getInt("screen_brightness"));
    }
    @Test public void currentServerAdvertisesPersistedInputPreparation() throws Exception {
        try (java.io.InputStream stream = getClass().getResourceAsStream("/agent-server-goal-inputs.json")) {
            assertNotNull(stream);
            AgentServerContract server = AgentServerContract.fromOpenApi(new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
            assertTrue(server.goalPreparation);
            assertTrue(server.supports("resolve_goal"));
            assertEquals("agent-fe-contract.v0.3", server.contractVersion);
        }
        assertFalse(AgentServerContract.fromOpenApi(deployed()).goalPreparation);
    }
    private JSONObject deployed() throws Exception {
        try (java.io.InputStream stream = getClass().getResourceAsStream("/agent-server-v02.json")) {
            assertNotNull(stream);
            return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private GoalCatalogClassifier catalog() throws Exception {
        String rules = "{\"version\":\"2.1.0\",\"goals\":["
                + "{\"goal_type\":\"settings.font_size.increase\",\"display_name\":\"글자를 크게 보기\",\"target_app\":\"android_settings\",\"execution_status\":\"approved\",\"slots\":{\"setting\":\"font_size\",\"direction\":\"increase\"},\"keyword_groups\":[[\"글자\"],[\"크게\",\"키워\"]]},"
                + "{\"goal_type\":\"settings.font_size.decrease\",\"display_name\":\"글자를 작게 보기\",\"target_app\":\"android_settings\",\"execution_status\":\"approved\",\"slots\":{\"setting\":\"font_size\",\"direction\":\"decrease\"},\"keyword_groups\":[[\"글자\"],[\"작게\"]]}]}";
        return GoalCatalogClassifier.fromJson(rules);
    }
    @Test public void deployedServerExecutesGoalsWithoutResolutionOrResultCommands() throws Exception {
        AgentServerContract contract = AgentServerContract.fromOpenApi(deployed());
        assertTrue(contract.supports("start_task"));
        assertTrue(contract.supports("cancel_task"));
        assertFalse(contract.supports("resolve_goal"));
        assertFalse(contract.supports("request_result"));
        assertTrue(contract.supportsGoal("settings.font_size.increase"));
    }
    @Test public void upgradedServerUsesAdvertisedResolutionCapability() throws Exception {
        JSONObject doc = deployed();
        doc.getJSONObject("components").getJSONObject("schemas").getJSONObject("AgentCommandRequest")
                .getJSONObject("properties").getJSONObject("command_type").getJSONArray("enum").put("resolve_goal");
        assertTrue(AgentServerContract.fromOpenApi(doc).supports("resolve_goal"));
    }
    @Test public void explicitGoalStillNeedsUserSelectionBeforeExecution() throws Exception {
        JSONObject choice = LocalGoalResolver.resolve(catalog(), AgentServerContract.fromOpenApi(deployed()),
                "휴대폰 전체 글자 크기 키워줘", "com.android.settings", null);
        assertFalse(choice.has("automatic_goal"));
        assertFalse(choice.has("id"));
        assertEquals(1, choice.getJSONArray("candidates").length());
        assertEquals("settings.font_size.increase", choice.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("goal").getString("goal_type"));
    }
    @Test public void unclearFontRequestOffersDirectionsAndNegationDoesNotStartAnything() throws Exception {
        AgentServerContract server = AgentServerContract.fromOpenApi(deployed());
        assertEquals(0, LocalGoalResolver.resolve(catalog(), server, "글자가 잘 안 보여", null, null)
                .getJSONArray("candidates").length());
        assertEquals(0, LocalGoalResolver.resolve(catalog(), server, "글자를 크게 하지마", null, null)
                .getJSONArray("candidates").length());
        assertEquals(0, LocalGoalResolver.resolve(catalog(), server, "글자를 크게", null, "different")
                .getJSONArray("candidates").length());
    }
    @Test(expected = org.json.JSONException.class) public void unknownServerDoesNotAssumeLegacyCapabilities() throws Exception {
        AgentServerContract.fromOpenApi(new JSONObject());
    }
}
