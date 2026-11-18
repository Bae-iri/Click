package com.click.domain.goal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Server-owned input requirements and answers; this class never chooses a task graph. */
public final class GoalPreparationState {
    public final JSONObject data;
    public final JSONObject goal;
    public final String message;
    public GoalPreparationState(JSONObject preparation, JSONObject goal, String message) throws JSONException {
        if (!"goal-inputs.v1".equals(preparation.getString("version"))) throw new JSONException("Unknown input contract");
        preparation.getString("id"); preparation.getString("source_text"); preparation.getString("status");
        data = preparation; this.goal = goal; this.message = message;
    }
    public String id() { return data.optString("id"); }
    public boolean needsInput() { return "needs_input".equals(data.optString("status")) && question() != null; }
    public JSONObject question() { return data.optJSONObject("question"); }
    public String guidance() {
        JSONObject current = question();
        String guidance = current == null ? message : current.optString("message", message);
        // Keep the displayed question and TTS consistent with older server responses too.
        if ("바꾸려는 대상과 원하는 변경을 다시 말씀해 주세요.".equals(guidance)
                || "바꾸려는 대상과 원하는 변경을 다시 말씀해주세요.".equals(guidance))
            return "원하시는 작업을 조금 더 자세히 말해주세요.";
        if (guidance != null && (guidance.trim().equals("설명 요청") || guidance.trim().equals("추가 설명 요청")))
            return "원하시는 작업을 조금 더 자세히 말해주세요.";
        return guidance == null ? null : guidance.replace("요청", "작업");
    }
    public boolean ready() {
        JSONArray missing = data.optJSONArray("missing_fields");
        if(publicGuide())return "ready".equals(data.optString("status"))&&data.optJSONObject("public_guide_goal")!=null
                &&question()==null&&missing!=null&&missing.length()==0;
        if (browsing()) return "ready".equals(data.optString("status")) && data.optJSONObject("browse_goal") != null
                && question() == null && missing != null && missing.length() == 0;
        return "ready".equals(data.optString("status")) && goal != null && question() == null
                && missing != null && missing.length() == 0 && data.optJSONObject("graph_reference") != null;
    }
    public JSONObject answer(String value) throws JSONException {
        if (!needsInput() || value == null || value.trim().isEmpty()) throw new JSONException("No current input question");
        JSONObject question = question();
        String selected = value.trim();
        if (selected.codePointCount(0, selected.length()) > question.optInt("max_length", 100))
            throw new JSONException("Answer exceeds the server field limit");
        if ("choice".equals(question.optString("kind"))) {
            boolean allowed = false;
            JSONArray choices = question.optJSONArray("choices");
            if (choices != null) for (int i = 0; i < choices.length(); i++)
                if (selected.equals(choices.getJSONObject(i).getString("value"))) allowed = true;
            if (!allowed) throw new JSONException("Unknown choice");
        }
        return new JSONObject().put("question_id", question.getString("id")).put("value", selected);
    }
    public boolean publicGuide(){return "public_guide".equals(data.optString("execution_mode"));}
    public boolean browsing() { return "browse".equals(data.optString("execution_mode")); }
    public String exactSpokenChoice(String text) {
        if (!needsInput()) return null;
        if ("text".equals(question().optString("kind"))) return text;
        JSONArray choices = question().optJSONArray("choices");
        String normalized = GoalCatalogClassifier.normalize(text);
        if (choices != null) for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i);
            if (choice == null) continue;
            if (normalized.equals(GoalCatalogClassifier.normalize(choice.optString("label"))) || normalized.equals((i + 1) + "번"))
                return choice.optString("value");
            JSONArray aliases = choice.optJSONArray("aliases");
            if (aliases != null) for (int j = 0; j < aliases.length(); j++)
                if (normalized.equals(GoalCatalogClassifier.normalize(aliases.optString(j)))) return choice.optString("value");
        }
        return null;
    }
}
