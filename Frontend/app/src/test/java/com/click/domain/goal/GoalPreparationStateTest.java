package com.click.domain.goal;

import com.click.data.remote.AgentTurnContract;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalPreparationStateTest {
    private JSONObject preparation(String field) throws Exception {
        return new JSONObject().put("version", "goal-inputs.v1").put("id", "resolution-1")
                .put("source_text", "글자 크기 키워줘").put("status", "needs_input")
                .put("values", new JSONObject().put("direction", "increase"))
                .put("missing_fields", new JSONArray().put(field))
                .put("question", new JSONObject().put("id", "resolution-1:" + field).put("field", field)
                        .put("kind", "choice").put("message", "어디에 적용할까요?")
                        .put("choices", new JSONArray().put(new JSONObject().put("value", "system")
                                .put("label", "휴대폰 설정에서 기본 글자 크기 변경")
                                .put("aliases", new JSONArray().put("설정").put("휴대폰 전체")))
                                .put(new JSONObject().put("value", "app").put("label", "특정 앱 안에서만 변경"))));
    }
    @Test public void partialInputCannotStartAndAnswerBindsCurrentQuestion() throws Exception {
        GoalPreparationState state = new GoalPreparationState(preparation("scope"), null, "범위 확인");
        assertTrue(state.needsInput()); assertFalse(state.ready());
        assertEquals("resolution-1:scope", state.answer("system").getString("question_id"));
        assertEquals("app", state.answer("app").getString("value"));
        assertEquals("system", state.exactSpokenChoice("설정"));
        assertEquals("app", state.exactSpokenChoice("2번"));
        assertNull(state.exactSpokenChoice("모르겠어요"));
    }
    @Test(expected = org.json.JSONException.class) public void unknownOptionCannotBeSubmitted() throws Exception {
        new GoalPreparationState(preparation("scope"), null, "범위 확인").answer("invented-option");
    }
    @Test public void additionalGraphFieldRequiresNoHardcodedAndroidMapping() throws Exception {
        JSONObject data = preparation("reading_distance");
        GoalPreparationState state = new GoalPreparationState(data, null, "읽는 거리 확인");
        assertEquals("resolution-1:reading_distance", state.answer("app").getString("question_id"));
    }
    @Test public void appNameUsesTextQuestionAndNeverSuppliesAReplacementGoal() throws Exception {
        JSONObject data = preparation("app_name");
        data.getJSONObject("question").put("kind", "text").remove("choices");
        GoalPreparationState state = new GoalPreparationState(data, null, "앱 이름 확인");
        assertEquals("카카오톡", state.answer("카카오톡").getString("value"));
        assertNull(state.goal); assertFalse(state.ready());
    }
    @Test public void readyRequiresCompleteInputsAndSelectedGraph() throws Exception {
        JSONObject data = preparation("scope").put("status", "ready").put("missing_fields", new JSONArray());
        data.remove("question");
        JSONObject goal = new JSONObject().put("goal_type", "settings.font_size.increase");
        assertFalse(new GoalPreparationState(data, goal, "확인").ready());
        data.put("graph_reference", new JSONObject().put("task_id", "MVP-01").put("sha256", "graph-hash"));
        assertTrue(new GoalPreparationState(data, goal, "확인").ready());
        data.put("status", "unsupported");
        assertFalse(new GoalPreparationState(data, goal, "미지원").ready());
    }
    @Test(expected = org.json.JSONException.class) public void textAnswerUsesServerFieldLimit() throws Exception {
        JSONObject data = preparation("app_name");
        data.getJSONObject("question").put("kind", "text").put("max_length", 3);
        new GoalPreparationState(data, null, "앱 이름 확인").answer("카카오톡");
    }
    @Test public void answerRequestUsesStructuredFieldsRatherThanRewritingUserUtterance() throws Exception {
        GoalPreparationState state = new GoalPreparationState(preparation("scope"), null, "확인");
        AgentTurnContract.Request request = AgentTurnContract.Request.resolve("session", "글자 크기 키워줘", null);
        request.preparationVersion = "goal-inputs.v1";
        request.goalResolutionId = state.id(); request.clarificationAnswer = state.answer("app");
        JSONObject body = new JSONObject(request.toJson());
        assertEquals("글자 크기 키워줘", body.getString("source_text"));
        assertEquals("app", body.getJSONObject("clarification_answer").getString("value"));
        assertEquals("resolution-1", body.getString("goal_resolution_id"));
        assertFalse(body.has("goal")); assertFalse(body.has("accessibility_tree"));
    }
    @Test public void legacyResolveDoesNotSendUnsupportedPreparationFields() throws Exception {
        AgentTurnContract.Request request = AgentTurnContract.Request.resolve("session", "글자 키워줘", null);
        request.contractVersion = "agent-fe-contract.v0.2";
        JSONObject body = new JSONObject(request.toJson());
        assertFalse(body.has("preparation_version"));
        assertFalse(body.has("clarification_answer"));
        assertFalse(body.has("goal_resolution_id"));
    }
}
