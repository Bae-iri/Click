package com.click.data.remote;

import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.LocalGoalResolver;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AgentServerContractTest {
    @Test public void manualEvidenceIsSentOnlyToAnExplicitlyCompatibleServer() throws Exception {
        JSONObject api;
        try(var input=getClass().getResourceAsStream("/openapi.json")) {
            api=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
        var request=AgentTurnContract.Request.control("s",3,"e","request_result",null);
        request.manualSettingEvidence=new JSONObject().put("schema_version","manual-setting-evidence.v1");
        var current=AgentServerContract.fromOpenApi(api);
        assertTrue(current.manualSettingEvidence);
        JSONObject wire=new JSONObject(current.encode(request));
        assertTrue(wire.has("manual_setting_evidence"));
        assertFalse(wire.has("user_action_event"));assertFalse(wire.has("user_confirmation"));
        api.getJSONObject("components").getJSONObject("schemas").getJSONObject("AgentCommandRequest")
                .getJSONObject("properties").remove("manual_setting_evidence");
        var old=AgentServerContract.fromOpenApi(api);
        assertFalse(old.manualSettingEvidence);
        assertFalse(new JSONObject(old.encode(request)).has("manual_setting_evidence"));
    }
    @Test public void completionConditionsRequireBothSidesOfTheAdvertisedTaskContract()throws Exception {
        JSONObject api=deployed();api.getJSONObject("paths").put(AppGuideGateway.TASK_PATH,new JSONObject());
        JSONObject schemas=api.getJSONObject("components").getJSONObject("schemas");
        schemas.put("TaskState",new JSONObject().put("properties",new JSONObject().put("completion",new JSONObject())));
        schemas.put("AppTaskResponse",new JSONObject().put("properties",new JSONObject().put("completion",new JSONObject())));
        assertTrue(AgentServerContract.fromOpenApi(api).appTaskCompletion);
        schemas.getJSONObject("TaskState").getJSONObject("properties").remove("completion");
        assertFalse(AgentServerContract.fromOpenApi(api).appTaskCompletion);
    }
    @Test public void goalFirstRequiresExplicitServerPlanningModeSupport()throws Exception {
        JSONObject api=deployed();api.getJSONObject("paths").put(AppGuideGateway.TASK_PATH,new JSONObject());
        JSONObject schemas=api.getJSONObject("components").getJSONObject("schemas");
        schemas.put("TaskState",new JSONObject().put("properties",new JSONObject().put("completion",new JSONObject())));
        schemas.put("AppTaskResponse",new JSONObject().put("properties",new JSONObject().put("completion",new JSONObject())));
        schemas.put("AppTaskRequest",new JSONObject().put("properties",new JSONObject()));
        assertFalse(AgentServerContract.fromOpenApi(api).appTaskGoalFirst);
        JSONObject mode=new JSONObject().put("type","string").put("enum",new org.json.JSONArray().put("next_action").put("result_only"));
        schemas.getJSONObject("AppTaskRequest").getJSONObject("properties").put("planning_mode",mode);
        assertFalse(AgentServerContract.fromOpenApi(api).appTaskGoalFirst);
        mode.getJSONArray("enum").put("goal_first");assertTrue(AgentServerContract.fromOpenApi(api).appTaskGoalFirst);
        schemas.getJSONObject("TaskState").getJSONObject("properties").remove("completion");assertFalse(AgentServerContract.fromOpenApi(api).appTaskGoalFirst);
    }
    @Test public void correctingARunningRequestRequiresTheAdvertisedEndpoint() throws Exception {
        JSONObject api=deployed();assertFalse(AgentServerContract.fromOpenApi(api).reviseRequest);
        api.getJSONObject("paths").put("/api/v1/voice-requests/{request_id}/revise",new JSONObject());
        assertTrue(AgentServerContract.fromOpenApi(api).reviseRequest);
    }
    @Test public void privacyDiagnosticsRequireExplicitSupportFromTheConnectedBackend() throws Exception {
        JSONObject api;
        try(var input=getClass().getResourceAsStream("/openapi.json")) {
            api=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
        assertTrue(AgentServerContract.fromOpenApi(api).gov24ObservationDiagnostics);
        api.getJSONObject("components").getJSONObject("schemas").getJSONObject("PublicGuideScreen")
            .getJSONObject("properties").remove("privacy_reason");
        assertFalse(AgentServerContract.fromOpenApi(api).gov24ObservationDiagnostics);
        assertFalse(AgentServerContract.fromOpenApi(deployed()).gov24ObservationDiagnostics);
    }
    @Test public void naturalGoalResolutionAdvertisesPublicGuideWithoutPreexistingCapabilities() throws Exception {
        JSONObject api;
        try(var input=getClass().getResourceAsStream("/openapi.json")) {
            api=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
        api.getJSONObject("paths").put("/api/v1/agent/public-guide/commands",new JSONObject());
        JSONObject schemas=api.getJSONObject("components").getJSONObject("schemas");
        JSONObject capabilities=schemas.optJSONObject("DeviceCapabilities");
        if(capabilities==null) {
            capabilities=new JSONObject().put("properties",new JSONObject());
            schemas.put("DeviceCapabilities",capabilities);
        }
        capabilities.getJSONObject("properties").put("gov24_login",new JSONObject().put("type","boolean"));
        AgentServerContract server=AgentServerContract.fromOpenApi(api);
        assertTrue(server.gov24Login);
        var request=AgentTurnContract.Request.resolve("session","정부24 로그인 도와줘",null);
        assertFalse(new JSONObject(request.toJson()).getJSONObject("device_context").has("capabilities"));
        JSONObject wire=new JSONObject(server.encode(request));
        assertTrue(wire.getJSONObject("device_context").getJSONObject("capabilities").getBoolean("gov24_login"));
        if(server.gov24Civil)assertTrue(wire.getJSONObject("device_context").getJSONObject("capabilities").getBoolean("gov24_civil"));
        assertFalse(wire.has("accessibility_tree"));
    }

    @Test public void legacyGoalResolutionDoesNotReceiveUnsupportedCapabilities() throws Exception {
        var request=AgentTurnContract.Request.resolve("session","정부24 로그인 도와줘",null);
        JSONObject wire=new JSONObject(AgentServerContract.fromOpenApi(deployed()).encode(request));
        assertFalse(wire.getJSONObject("device_context").has("capabilities"));
    }

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
