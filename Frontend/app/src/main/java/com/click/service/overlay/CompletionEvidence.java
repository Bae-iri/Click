package com.click.service.overlay;

import org.json.JSONObject;
import com.click.data.snapshot.TaskSuccess;

/** Device success is goal-bound; an explicit user report remains a separate completion path. */
final class CompletionEvidence {
    private CompletionEvidence() {}
    static JSONObject technical(JSONObject metadata) {
        if(metadata==null)return null;
        JSONObject outcome=metadata.optJSONObject("task_outcome");
        return outcome==null?metadata.optJSONObject("technical_outcome"):outcome.optJSONObject("technical");
    }
    static String boundGoal(JSONObject metadata) {
        if(metadata==null)return null;
        JSONObject outcome=metadata.optJSONObject("task_outcome"),typed=metadata.optJSONObject("typed_goal");
        return outcome!=null && !outcome.optString("goal_type").isEmpty()?outcome.optString("goal_type")
            :typed==null?null:typed.optString("goal_type",null);
    }
    static boolean verified(JSONObject metadata,String goal) {
        JSONObject technical=technical(metadata);
        return technical!=null && "CHANGED".equals(technical.optString("status")) && TaskSuccess.verified(technical,goal);
    }
    static boolean verified(JSONObject metadata){return verified(metadata,boundGoal(metadata));}
    static boolean accepted(JSONObject metadata,String goal) {
        String bound=boundGoal(metadata);
        if(bound!=null && goal!=null && !bound.equals(goal))return false;
        JSONObject proof=technical(metadata);
        if(goal!=null && goal.startsWith("coupang.") && proof!=null
                && goal.equals(proof.optString("goal_type"))
                && "OPENED".equals(proof.optString("status"))
                && "commerce_page_visible".equals(proof.optString("reason")))return true;
        return TaskSuccess.verified(technical(metadata),goal) || userSatisfied(metadata);
    }
    static boolean accepted(JSONObject metadata){return accepted(metadata,boundGoal(metadata));}
    private static boolean userSatisfied(JSONObject metadata) {
        if(metadata==null)return false;
        JSONObject outcome=metadata.optJSONObject("task_outcome");
        JSONObject user=outcome==null?null:outcome.optJSONObject("user");
        return user!=null && "satisfied".equals(user.optString("status"))
            || "satisfied".equals(metadata.optString("user_result"));
    }
}
