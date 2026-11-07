package com.click.service.overlay;

import org.json.JSONObject;

/** A done command/status alone is not device evidence or a user's answer. */
final class CompletionEvidence {
    private CompletionEvidence() {}
    static boolean verified(JSONObject metadata) {
        if(metadata==null)return false;
        JSONObject outcome=metadata.optJSONObject("task_outcome");
        JSONObject technical=outcome==null?metadata.optJSONObject("technical_outcome"):outcome.optJSONObject("technical");
        return technical!=null && "CHANGED".equals(technical.optString("status"));
    }
    static boolean accepted(JSONObject metadata) {
        if(verified(metadata))return true;
        if(metadata==null)return false;
        JSONObject outcome=metadata.optJSONObject("task_outcome");
        JSONObject user=outcome==null?null:outcome.optJSONObject("user");
        return user!=null && "satisfied".equals(user.optString("status"))
            || "satisfied".equals(metadata.optString("user_result"));
    }
}
