package com.click.data.remote;

import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.LocalGoalResolver;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AgentServerContractTest {
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
    @Test public void negotiatesInteractiveWorkflowOnlyWhenAdvertised() throws Exception {
        JSONObject doc = deployed();
        assertEquals("agent-fe-contract.v0.2", AgentServerContract.fromOpenApi(doc).contractVersion);
        assertFalse(AgentServerContract.fromOpenApi(doc).interactiveWorkflow);
        JSONObject properties = doc.getJSONObject("components").getJSONObject("schemas")
                .getJSONObject("AgentCommandRequest").getJSONObject("properties");
        properties.put("contract_version", new JSONObject().put("enum", new org.json.JSONArray()
                .put("agent-fe-contract.v0.2").put("agent-fe-contract.v0.3")));
        assertFalse(AgentServerContract.fromOpenApi(doc).interactiveWorkflow);
        properties.put("workflow_version", new JSONObject());
        AgentServerContract server = AgentServerContract.fromOpenApi(doc);
        assertEquals("agent-fe-contract.v0.3", server.contractVersion);
        assertTrue(server.interactiveWorkflow);
    }
}
