package com.click.service.overlay;

import org.json.JSONObject;
import org.json.JSONException;

/** Explicit, short-lived, single-operation delegation selected on the task review. */
final class AdjustmentDelegation {
    private String goal;
    private long grantedAt,expiresAt;
    private boolean spent;
    void grant(String goal,long now){clear();this.goal=goal;grantedAt=now;expiresAt=now+300000;}
    void clear(){goal=null;spent=false;grantedAt=expiresAt=0;}
    boolean permits(String goal,long now){return !spent && this.goal!=null && this.goal.equals(goal) && now>=grantedAt && now<expiresAt;}
    JSONObject consume(String goal,long now) throws JSONException {
        if(!permits(goal,now))return null;
        spent=true;
        return new JSONObject().put("source","delegated_goal").put("goal_type",goal)
                .put("granted_at_ms",grantedAt).put("expires_at_ms",expiresAt)
                .put("max_actions",1).put("max_delta_fraction",0.2);
    }
}
