package com.click.domain.goal;

import com.click.data.remote.AgentServerContract;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsExecutionGoalTest {
    private String resource(String name) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test public void all89SettingsActionsReachTheTypedServerContract() throws Exception {
        String source = resource("mvp_goals.json");
        GoalCatalogClassifier catalog = GoalCatalogClassifier.fromJson(source);
        AgentServerContract server = AgentServerContract.fromOpenApi(new JSONObject(resource("openapi.json")));
        JSONArray entries = new JSONObject(source).getJSONArray("goals");
        int changes = 0, assisted = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (!"android_settings".equals(entry.optString("target_app"))
                    || "settings_navigation".equals(entry.optString("task_type"))) continue;
            StringBuilder text = new StringBuilder();
            JSONArray groups = entry.getJSONArray("keyword_groups");
            for (int j = 0; j < groups.length(); j++) text.append(groups.getJSONArray(j).getString(0)).append(' ');
            GoalCommandInterpreter.Result result = GoalCommandInterpreter.interpret(text.toString(), false, false,
                    false, catalog, "com.android.settings");
            String goalType = entry.getString("goal_type");
            assertEquals(goalType, GoalCommandInterpreter.Kind.START_TASK, result.kind);
            assertEquals(goalType, result.goal.goalType);
            assertTrue(goalType, server.supportsGoal(goalType));
            assertEquals("2.3.0", result.goal.toTypedGoalJson().getString("catalog_version"));
            if ("settings_assisted".equals(result.goal.taskType)) assisted++; else changes++;
        }
        assertEquals(50, changes);
        assertEquals(39, assisted);
    }
    @Test public void conversationalSwitchRequestsReachTheSameTypedGoal() throws Exception {
        String source=resource("mvp_goals.json");
        GoalCatalogClassifier catalog=GoalCatalogClassifier.fromJson(source);
        AgentServerContract server=AgentServerContract.fromOpenApi(new JSONObject(resource("openapi.json")));
        JSONArray goals=new JSONObject(source).getJSONArray("goals");
        int checked=0;
        for(int i=0;i<goals.length();i++) {
            JSONObject entry=goals.getJSONObject(i),slots=entry.getJSONObject("slots");
            if(!"android_settings".equals(entry.optString("target_app")) || !slots.has("state"))continue;
            String state=slots.getString("state");
            String name=entry.getString("display_name").replaceAll(" (켜기|끄기)$", "");
            String text=name+("on".equals(state)?" 키고 싶어":" 끄고 싶어");
            JSONObject selection=LocalGoalResolver.resolve(catalog,server,text,null,null);
            JSONArray candidates=selection.getJSONArray("candidates");
            assertEquals(text,1,candidates.length());
            JSONObject goal=candidates.getJSONObject(0).getJSONObject("goal");
            assertEquals(text,entry.getString("goal_type"),goal.getString("goal_type"));
            assertEquals(state,goal.getJSONObject("slots").getString("state"));
            checked++;
        }
        assertTrue(checked>40);
        assertEquals(0,LocalGoalResolver.resolve(catalog,server,"다크모드 키고 싶지 않아",null,null)
            .getJSONArray("candidates").length());
    }
}
