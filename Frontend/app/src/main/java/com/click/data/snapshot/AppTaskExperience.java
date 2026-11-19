package com.click.data.snapshot;

import org.json.*;
import java.util.UUID;
import com.click.service.overlay.AppTaskMemory;

/** Minimal device-reported evidence: never serializes goals, query text, labels or coordinates. */
public final class AppTaskExperience {
    private static final String ZERO="0000000000000000000000000000000000000000000000000000000000000000";
    public static JSONObject capture(AppTaskMemory memory,int sequence,JSONObject before,JSONObject after,
            JSONObject environment,JSONObject action,String outcome,boolean observed,boolean condition)throws JSONException {
        JSONObject event=new JSONObject().put("protocol_version","app-task-experience.v1")
            .put("event_id",UUID.randomUUID().toString()).put("task_id",memory.experienceId()).put("sequence",sequence)
            .put("observed_at_ms",System.currentTimeMillis()).put("goal_key",AppTaskObservation.hash(memory.goal()))
            .put("subgoal_key",AppTaskObservation.hash(memory.activeTitle())).put("environment",environment)
            .put("screen_key",AppTaskObservation.learningKey(before)).put("after_key",AppTaskObservation.learningKey(after))
            .put("after_package",after.optString("package")).put("kind",action==null?"result":"attempt")
            .put("starts_task",memory.actions()==0).put("outcome",outcome).put("action_observed",observed).put("condition_met",condition);
        if(action!=null){
            JSONObject node=AppTaskObservation.find(before,action.optString("target_id")),expect=action.getJSONObject("expectation");
            String tool=action.getString("tool");
            event.put("action",new JSONObject().put("tool",action.getString("tool"))
                .put("target_key",node==null?ZERO:AppTaskObservation.targetKey(node)).put("role",node==null?"none":tool.startsWith("scroll_")?"list":tool.equals("set_text")?"text_field":node.getString("role"))
                .put("expectation",expect.getString("kind")).put("expected_label_key",expect.optString("label").isEmpty()?"":AppTaskObservation.hash(expect.getString("label")))
                .put("expected_package",expect.optString("package")).put("advances_subgoal",action.optBoolean("advances_subgoal")));
        }
        return event;
    }
}
