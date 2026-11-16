package com.click.domain.goal;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoupangGoalTest {
    @Test public void allCommerceGoalsCanBeSelectedAndResumedWithoutLosingTheirIdentity() throws Exception {
        String raw;
        try (java.io.InputStream input = getClass().getResourceAsStream("/mvp_goals.json")) {
            raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        GoalCatalogClassifier catalog = GoalCatalogClassifier.fromJson(raw);
        JSONArray goals = new JSONObject(raw).getJSONArray("goals");
        int checked = 0;
        for (int i = 0; i < goals.length(); i++) {
            JSONObject goal = goals.getJSONObject(i);
            if (!"coupang".equals(goal.getString("target_app"))) continue;
            String type = goal.getString("goal_type");
            GoalCatalogClassifier.Match match = catalog.executableGoal(type);
            assertNotNull(type, match);
            assertEquals(type, catalog.executableGraph(goal.getString("graph_id")).goalType);
            GoalCatalogClassifier.Classification classified = catalog.classify(goal.getString("display_name"), "com.coupang.mobile");
            assertNotNull(type, classified.match);
            assertEquals(type, classified.match.goalType);
            assertEquals("2.4.0", match.toTypedGoalJson().getString("catalog_version"));
            checked++;
        }
        assertEquals(71, checked);
    }
}
