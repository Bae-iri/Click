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
    static boolean accepted(JSONObject metadata, String goalType) {
        if (accepted(metadata)) return true;
        if (metadata == null || goalType == null || !goalType.startsWith("settings.navigation.")) return false;
        JSONObject outcome = metadata.optJSONObject("task_outcome");
        JSONObject proof = outcome == null ? null : outcome.optJSONObject("technical");
        return proof != null && "OPENED".equals(proof.optString("status"))
            && "settings_page_visible".equals(proof.optString("reason"))
            && ("navigation_" + goalType.substring("settings.navigation.".length())).equals(proof.optString("setting"));
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
