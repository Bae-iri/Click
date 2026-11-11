package com.click.domain.goal;

import com.click.data.remote.AgentServerContract;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsNavigationGoalTest {
    private String resource(String name) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void everyNavigationGoalIsRecognizedAndSupportedByTheNewServer() throws Exception {
        String source = resource("mvp_goals.json");
        GoalCatalogClassifier catalog = GoalCatalogClassifier.fromJson(source);
        AgentServerContract server = AgentServerContract.fromOpenApi(new JSONObject(resource("openapi.json")));
        JSONArray goals = new JSONObject(source).getJSONArray("goals");
        int count = 0;
        for (int i = 0; i < goals.length(); i++) {
            JSONObject goal = goals.getJSONObject(i);
            if (!"settings_navigation".equals(goal.optString("task_type"))) continue;
            count++;
            String text = goal.getJSONArray("utterances").getString(0);
            GoalCatalogClassifier.Classification result = catalog.classify(text, "com.android.settings");
            assertTrue(text, result.matched());
            assertEquals(goal.getString("goal_type"), result.match.goalType);
            assertTrue(result.match.isExecutable());
            assertEquals("open", result.match.slots.getString("action"));
            assertTrue(server.supportsGoal(result.match.goalType));
            JSONObject resolution = LocalGoalResolver.resolve(catalog, server, text, null, null);
            assertEquals(text, 1, resolution.getJSONArray("candidates").length());
            assertEquals(result.match.goalType, resolution.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("goal").getString("goal_type"));
            assertEquals(GoalCommandInterpreter.Kind.START_TASK,
                    GoalCommandInterpreter.interpret(text, false, false, false, catalog, null).kind);
        }
        assertEquals(21, count);
    }

    @Test public void negatedNavigationNeverProducesAStartCandidate() throws Exception {
        GoalCatalogClassifier catalog = GoalCatalogClassifier.fromJson(resource("mvp_goals.json"));
        AgentServerContract server = AgentServerContract.fromOpenApi(new JSONObject(resource("openapi.json")));
        for (String text : new String[]{"와이파이 설정 열어주지 마", "글자 크기 설정 열어주지마"}) {
            assertEquals(0, LocalGoalResolver.resolve(catalog, server, text, null, null)
                    .getJSONArray("candidates").length());
        }
    }
}
