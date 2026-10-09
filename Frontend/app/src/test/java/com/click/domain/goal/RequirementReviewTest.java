package com.click.domain.goal;

import com.click.data.remote.AgentServerContract;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class RequirementReviewTest {
    private GoalCatalogClassifier catalog;
    private AgentServerContract server;
    @Before public void setup() throws Exception {
        catalog = GoalCatalogClassifier.fromJson("{\"version\":\"2.1\",\"goals\":["
                + rule("increase", "크게") + "," + rule("decrease", "작게") + "]}");
        try (java.io.InputStream in = getClass().getResourceAsStream("/agent-server-v02.json")) {
            server = AgentServerContract.fromOpenApi(new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }
    private String rule(String direction, String keyword) {
        return "{\"goal_type\":\"settings.font_size." + direction + "\",\"display_name\":\"글자를 " + keyword
                + "\",\"target_app\":\"android_settings\",\"execution_status\":\"approved\",\"slots\":{\"direction\":\""
                + direction + "\"},\"keyword_groups\":[[\"글자\"],[\"" + keyword + "\"]]}";
    }
    @Test public void vagueRequestAndQuickCardNeedScopeEvenInSettings() throws Exception {
        RequirementReview review = new RequirementReview("글자 크기 키워줘");
        assertTrue(review.needsTarget()); assertFalse(review.needsDirection());
        assertNull(review.confirm(catalog, server)); assertFalse(review.confirmed());
        assertEquals(0, LocalGoalResolver.resolve(catalog, server, "글자 크게", "com.android.settings", null)
                .getJSONArray("candidates").length());
        review.choose("phone");
        assertNotNull(review.proposedGoal(catalog, server)); assertFalse(review.confirmed());
        assertEquals("settings.font_size.increase", review.confirm(catalog, server).getString("goal_type"));
        assertTrue(review.confirmed());
    }
    @Test public void namedAppAndAppWideScopeCannotExecuteSystemGoal() throws Exception {
        RequirementReview review = new RequirementReview("카카오톡 전체 글자를 크게 해줘");
        assertFalse(review.needsTarget());
        assertTrue(review.rows().toString().contains("앱 전체 글자"));
        assertNull(review.confirm(catalog, server));
        review.update("전체 글자요");
        assertFalse(review.isPhone()); assertNull(review.proposedGoal(catalog, server));
        assertEquals(0, LocalGoalResolver.resolve(catalog, server, "카톡 글자를 크게", null, null)
                .getJSONArray("candidates").length());
    }
    @Test public void shortFollowupRetainsDirectionAndCanNameAnUnknownApp() throws Exception {
        RequirementReview review = new RequirementReview("글자를 크게 해줘");
        review.choose("app"); review.update("밀리의 서재");
        assertTrue(review.summary().contains("밀리의 서재"));
        assertTrue(review.summary().contains("더 크게"));
        assertNull(review.confirm(catalog, server));
    }
    @Test public void explicitScopeIsPrefilledButStillNeedsConfirmation() throws Exception {
        RequirementReview review = new RequirementReview("휴대폰 전체 글자를 작게 해줘");
        assertFalse(review.needsTarget()); assertFalse(review.confirmed());
        assertNotNull(review.proposedGoal(catalog, server));
        assertFalse(review.rows().toString().contains("completed"));
        assertEquals("settings.font_size.decrease", review.confirm(catalog, server).getString("goal_type"));
        assertTrue(review.rows().toString().contains("completed"));
    }
    @Test public void correctionInvalidatesConfirmationAndPreservesNewApp() throws Exception {
        RequirementReview review = new RequirementReview("휴대폰 전체 글자를 크게");
        assertNotNull(review.confirm(catalog, server));
        review.update("카카오톡에서만");
        assertFalse(review.confirmed()); assertFalse(review.isPhone());
        assertNull(review.confirm(catalog, server));
        review.choose("phone"); // An explicit user change, never an automatic fallback.
        assertFalse(review.confirmed()); assertNotNull(review.confirm(catalog, server));
    }
    @Test public void missingOrConflictingDirectionAndTargetBlockExecution() throws Exception {
        RequirementReview review = new RequirementReview("휴대폰 전체 글자가 안 보여요");
        assertTrue(review.needsDirection()); assertNull(review.confirm(catalog, server));
        review.choose("increase"); assertNotNull(review.confirm(catalog, server));
        review.update("크게 할지 작게 할지");
        assertNull(review.confirm(catalog, server));
        review.update("휴대폰 전체랑 카카오톡 글자를 크게");
        assertTrue(review.needsTarget()); assertNull(review.confirm(catalog, server));
    }
    @Test public void negationAndUncertaintyDoNotConfirmAnOldGoal() throws Exception {
        RequirementReview review = new RequirementReview("휴대폰 전체 글자를 크게 하지마");
        assertNull(review.confirm(catalog, server));
        review.update("모르겠어요");
        assertNull(review.confirm(catalog, server));
        review.choose("phone"); review.choose("increase");
        assertNotNull(review.confirm(catalog, server));
        review.update("다른 뜻이에요");
        assertNull(review.confirm(catalog, server));
    }
}
