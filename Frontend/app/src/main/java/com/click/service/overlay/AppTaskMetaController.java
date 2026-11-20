package com.click.service.overlay;

import org.json.*;
import java.util.*;

/**
 * Opt-in, non-root adaptation of UIAnchor's per-action coordinator.
 * Native verification remains authoritative. This policy never executes actions,
 * invents a result, captures toast content, or invokes a model.
 */
public final class AppTaskMetaController {
    public enum Arm { CURRENT, META, NO_EVENTS }
    public enum Decision { CONTINUE, WAIT, REOBSERVE, HANDOFF }
    public static final long NORMAL_WAIT_MS=5000, MAX_EVENT_WAIT_MS=8000, EVENT_FRESH_MS=1200;
    private static final int MAX_MEMORY=16, MAX_RECOVERIES=3;
    private final Arm arm;
    private final ArrayDeque<Event> events=new ArrayDeque<>();
    private final ArrayDeque<String> rejected=new ArrayDeque<>();
    private final ArrayDeque<String> outcomes=new ArrayDeque<>();
    private int recoveries;
    private static final class Event {
        final String pkg;final long at;
        Event(String pkg,long at){this.pkg=pkg;this.at=at;}
    }
    public AppTaskMetaController(Arm arm){this.arm=Objects.requireNonNull(arm);}
    public Arm arm(){return arm;}
    public boolean enabled(){return arm!=Arm.CURRENT;}
    /** Only an observed native screen-change notification, not inferred task success. */
    public void observeEvent(JSONObject observation,long monotonicMs){
        if(arm!=Arm.META||observation==null||!"public".equals(observation.optString("status"))||monotonicMs<0)return;
        events.addLast(new Event(observation.optString("package"),monotonicMs));
        while(events.size()>MAX_MEMORY)events.removeFirst();
    }
    public boolean blocked(JSONObject before,JSONObject action){return enabled()&&rejected.contains(key(before,action));}
    public Decision after(JSONObject before,JSONObject after,JSONObject action,AppTaskVerifier.Verdict verdict,long elapsedMs,long nowMs){
        if(verdict==AppTaskVerifier.Verdict.VERIFIED){remember("native_verified");return Decision.CONTINUE;}
        if(verdict==AppTaskVerifier.Verdict.PROTECTED||verdict==AppTaskVerifier.Verdict.STALE){remember(verdict.name());return Decision.HANDOFF;}
        if(elapsedMs<NORMAL_WAIT_MS&&(verdict==AppTaskVerifier.Verdict.WAITING||verdict==AppTaskVerifier.Verdict.UNKNOWN))return Decision.WAIT;
        if(!enabled()){return Decision.HANDOFF;}
        // Missing/stale observations cannot justify replay or automatic recovery.
        if(verdict!=AppTaskVerifier.Verdict.WAITING||!freshPublic(before,after)){
            remember("unobservable");return Decision.HANDOFF;
        }
        if(elapsedMs<MAX_EVENT_WAIT_MS&&recentEvent(after.optString("package"),nowMs,nowMs-elapsedMs))return Decision.WAIT;
        String key=key(before,action);
        if(key.isEmpty()||rejected.contains(key)||recoveries>=MAX_RECOVERIES){remember("recovery_budget");return Decision.HANDOFF;}
        // Accepted-but-unverified actions are not automatically performed again.
        rejected.addLast(key);while(rejected.size()>MAX_MEMORY)rejected.removeFirst();
        recoveries++;remember("reobserve_after_unconfirmed_action");return Decision.REOBSERVE;
    }
    private boolean recentEvent(String pkg,long now,long actionStarted){
        for(Event event:events)if(event.pkg.equals(pkg)&&event.at>=actionStarted&&event.at<=now&&now-event.at<=EVENT_FRESH_MS)return true;
        return false;
    }
    private static boolean freshPublic(JSONObject before,JSONObject after){
        return before!=null&&after!=null&&"public".equals(after.optString("status"))
            &&before.optString("package").equals(after.optString("package"))
            &&!before.optString("observation_id").isEmpty()&&!after.optString("observation_id").isEmpty()
            &&!before.optString("observation_id").equals(after.optString("observation_id"))
            &&after.optLong("captured_at_ms")>=before.optLong("captured_at_ms");
    }
    private static String key(JSONObject before,JSONObject action){
        if(before==null||action==null||before.optString("fingerprint").isEmpty()||action.optString("tool").isEmpty())return "";
        return before.optString("fingerprint")+"|"+action.optString("tool")+"|"+action.optString("target_id")+"|"+action.optString("package");
    }
    private void remember(String outcome){if(!enabled())return;outcomes.addLast(outcome);while(outcomes.size()>MAX_MEMORY)outcomes.removeFirst();}
    /** Counts only; public labels, text input and screenshots are never retained here. */
    public JSONObject diagnostic()throws JSONException {
        return new JSONObject().put("arm",arm.name()).put("event_source","native_screen_change_only")
            .put("event_count",events.size()).put("recovery_count",recoveries).put("blocked_count",rejected.size())
            .put("outcomes",new JSONArray(outcomes)).put("reflection_model_calls",0);
    }
}
