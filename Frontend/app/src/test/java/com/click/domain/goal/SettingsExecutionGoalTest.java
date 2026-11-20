package com.click.domain.goal;

import com.click.data.remote.AgentServerContract;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsExecutionGoalTest {
    @Test public void displayModeRequestsCannotSelectGalaxyThemes() throws Exception {
        GoalCatalogClassifier catalog = GoalCatalogClassifier.fromJson(resource("mvp_goals.json"));
        for (String text : new String[]{"어두운 테마로 바꿔줘", "밝은 테마로 바꿔줘", "다크모드 테마 바꿔줘",
                "라이트모드 테마 바꿔줘", "검은 배경 테마 바꿔줘", "흰 바탕 테마 바꿔줘"}) {
            GoalCatalogClassifier.Classification result = catalog.classify(text, "com.android.settings");
            assertTrue(text, result.match == null || !"settings.theme.change".equals(result.match.goalType));
        }
    }

    @Test public void foregroundCommerceAppCannotMakeSimRequestsAmbiguous() throws Exception {
        GoalCatalogClassifier catalog = GoalCatalogClassifier.fromJson(resource("mvp_goals.json"));
        for (String text : new String[]{"심카드 추가", "심카드 등록"}) {
            GoalCatalogClassifier.Classification result = catalog.classify(text, "com.coupang.mobile");
            assertFalse(text, result.ambiguous);
            assertNotNull(text, result.match);
            assertEquals(text, "settings.sim.manage", result.match.goalType);
        }
    }

    @Test public void reportedSettingsRequestsKeepTheirExactGoal() throws Exception {
        GoalCatalogClassifier catalog = GoalCatalogClassifier.fromJson(resource("mvp_goals.json"));
        String[][] cases = {
            {"배터리 잔량 표시해줘", "settings.battery_percentage.enable"},
            {"배터리 잔량 표시 꺼줘", "settings.battery_percentage.disable"},
            {"기본 키보드 설정", "settings.keyboard.change"},
            {"잠금 화면의 알림 설정", "settings.lockscreen_notifications.manage"},
            {"테마 바꿔줘", "settings.theme.change"},
            {"갤럭시 테마 적용해줘", "settings.theme.change"},
            {"어두운 테마 켜줘", "settings.dark_mode.enable"},
            {"핫스팟 비번 바꿔줘", "settings.hotspot.password.change"},
            {"SIM 추가하기", "settings.sim.manage"}
        };
        for (String[] item : cases) {
            GoalCommandInterpreter.Result result = GoalCommandInterpreter.interpret(
                item[0], false, false, false, catalog, "com.android.settings");
            assertEquals(item[0], GoalCommandInterpreter.Kind.START_TASK, result.kind);
            assertEquals(item[0], item[1], result.goal.goalType);
        }
    }

    private String resource(String name) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test public void all90SettingsActionsReachTheTypedServerContract() throws Exception {
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
            assertEquals("2.6.0", result.goal.toTypedGoalJson().getString("catalog_version"));
            if ("settings_assisted".equals(result.goal.taskType)) assisted++; else changes++;
        }
        assertEquals(56, changes);
        assertEquals(34, assisted);
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
    @Test public void everyApprovedGoalCanResumeFromItsExactServerGraphIdentity() throws Exception {
        String source=resource("mvp_goals.json");
        GoalCatalogClassifier catalog=GoalCatalogClassifier.fromJson(source);
        JSONArray entries=new JSONObject(source).getJSONArray("goals");int checked=0;
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);
            if(!"approved".equals(entry.optString("execution_status")))continue;
            String task=entry.getString("graph_id");
            GoalCatalogClassifier.Match resumed=catalog.executableGraph(task);
            assertNotNull(task,resumed);
            assertEquals(task,entry.getString("goal_type"),resumed.goalType);
            checked++;
        }
        assertEquals(119,checked);
    }
}
