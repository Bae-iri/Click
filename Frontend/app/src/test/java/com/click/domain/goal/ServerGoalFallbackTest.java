package com.click.domain.goal;

import com.click.data.remote.AgentServerContract;
import com.click.data.remote.AgentTurnContract;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ServerGoalFallbackTest {
    private GoalCatalogClassifier catalog;
    private AgentServerContract server;
    private JSONObject oldSchema;
    @Before public void setup() throws Exception {
        catalog = GoalCatalogClassifier.fromJson("{\"version\":\"2.1.0\",\"goals\":[" + rule("increase", "크게", "approved")
                + "," + rule("decrease", "작게", "approved") + "," + rule("unknown", "밝게", "catalog_only") + "]}");
        try (java.io.InputStream in = getClass().getResourceAsStream("/agent-server-v02.json")) {
            oldSchema = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        JSONObject schema = new JSONObject(oldSchema.toString());
        JSONObject fields = schema.getJSONObject("components").getJSONObject("schemas")
                .getJSONObject("AgentCommandRequest").getJSONObject("properties");
        fields.put("contract_version", new JSONObject().put("const", "agent-fe-contract.v0.3"));
        fields.getJSONObject("command_type").getJSONArray("enum").put("resolve_goal");
        server = AgentServerContract.fromOpenApi(schema);
    }
    private String rule(String direction, String word, String status) {
        return "{\"goal_type\":\"settings.font_size." + direction + "\",\"display_name\":\"글자 " + word
                + "\",\"target_app\":\"android_settings\",\"execution_status\":\"" + status
                + "\",\"slots\":{\"setting\":\"font_size\",\"direction\":\"" + direction
                + "\"},\"keyword_groups\":[[\"글자\"],[\"" + word + "\"]]}";
    }
    private boolean fallback(String text) {
        return LocalGoalResolver.needsServerResolution(catalog, server, text, null, null);
    }
    @Test public void onlyTrueMissUsesServer() {
        assertTrue(fallback("휴대폰 전체 문자가 깨알 같아요"));
        assertFalse(fallback("글자 크게"));
        assertFalse(fallback("글자 크게 작게"));
        assertFalse(fallback("글자 밝게")); // Known, unsupported goal.
        assertTrue(fallback("변경하지마"));
        assertFalse(fallback(""));
    }
    @Test public void correctionsReachServerEvenWithMatchingOrTiedKeywords() throws Exception {
        for (String source : new String[]{"휴대폰 전체 글자 크게 하지 말고 작게 해줘",
                "글자 말고 밝기를 올려줘", "카카오톡 말고 휴대폰 전체 글자를 작게 해줘"}) {
            assertTrue(source, fallback(source));
            assertEquals(0, LocalGoalResolver.resolve(catalog, server, source, null, null)
                    .getJSONArray("candidates").length());
        }
    }
    @Test public void oldServerKeepsLocalClarification() throws Exception {
        assertFalse(LocalGoalResolver.needsServerResolution(catalog, AgentServerContract.fromOpenApi(oldSchema),
                "깨알 같아요", null, null));
    }
    @Test public void modelSuggestionNeverConfirmsOrExpandsNamedAppScope() throws Exception {
        JSONObject goal = catalog.executableGoal("settings.font_size.increase").toTypedGoalJson();
        RequirementReview review = RequirementReview.forResolvedGoal("휴대폰 전체 문자가 깨알 같아요", goal, "글자 크게");
        assertNotNull(review.proposedGoal(catalog, server));
        assertFalse(review.confirmed());
        RequirementReview app = RequirementReview.forResolvedGoal("카카오톡에서 문자가 깨알 같아요", goal, "글자 크게");
        assertFalse(app.isPhone()); assertNull(app.confirm(catalog, server));
        RequirementReview unclear = RequirementReview.forResolvedGoal("문자가 깨알 같아요", goal, "글자 크게");
        assertTrue(unclear.needsTarget()); assertNull(unclear.confirm(catalog, server));
    }
    @Test public void unapprovedAndStaleCatalogSuggestionsCannotBeSelected() throws Exception {
        JSONObject goal = catalog.executableGoal("settings.font_size.increase").toTypedGoalJson();
        assertEquals(1, LocalGoalResolver.serverSelection(catalog, server, "confirmation_required", goal, "")
                .getJSONArray("candidates").length());
        goal.put("catalog_version", "stale");
        assertEquals(0, LocalGoalResolver.serverSelection(catalog, server, "confirmation_required", goal, "")
                .getJSONArray("candidates").length());
        goal.put("catalog_version", "2.1.0").put("goal_type", "invented.goal");
        assertEquals(0, LocalGoalResolver.serverSelection(catalog, server, "confirmation_required", goal, "")
                .getJSONArray("candidates").length());
    }
    @Test public void resolutionResponseIsParsedAsProposal() throws Exception {
        JSONObject goal = catalog.executableGoal("settings.font_size.increase").toTypedGoalJson();
        JSONObject payload = new JSONObject().put("request_id", "r").put("session_id", "s").put("session_version", 0)
                .put("response_type", "goal_resolution").put("resolution_status", "confirmation_required")
                .put("task_status", "IN_PROGRESS").put("goal", goal);
        AgentTurnContract.Response response = new AgentTurnContract.Response(payload.toString());
        assertEquals("confirmation_required", response.resolutionStatus);
        assertEquals("settings.font_size.increase", response.resolvedGoal.getString("goal_type"));
        assertNull(response.guideCommand);
    }
}
