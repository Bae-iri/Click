package com.click.service.overlay;

import org.json.*;
import java.util.*;
import java.util.function.LongSupplier;
import com.click.data.snapshot.ScreenSnapshot;

/**
 * Opt-in, non-root adaptation of UIAnchor's per-action coordinator.
 * Native verification remains authoritative. This policy never executes actions,
 * invents a result, captures toast content, or invokes a model.
 */
public final class AppTaskMetaController {
    public enum Arm { CURRENT, META, NO_EVENTS }
    public enum Decision { CONTINUE, WAIT, REOBSERVE, REOBSERVE_PROGRESS, HANDOFF }
    public static final long NORMAL_WAIT_MS=5000, MAX_EVENT_WAIT_MS=8000, EVENT_FRESH_MS=1200;
    private static final int MAX_MEMORY=16, MAX_RECOVERIES=3;
    private static final int MAX_PROGRESS_PER_SUBGOAL=2;
    private final Arm arm;
    private final LongSupplier wallClock;
    private final AppTaskTransitionDetector transition=new AppTaskTransitionDetector();
    private final LinkedHashSet<String> progressRoutes=new LinkedHashSet<>();
    private final Set<String> failureRequestRoutes=new HashSet<>();
    private final LinkedHashSet<String> stagnantRoutes=new LinkedHashSet<>();
    private final ArrayDeque<Event> events=new ArrayDeque<>();
    private final ArrayDeque<String> rejected=new ArrayDeque<>();
    private final Map<String,JSONObject> rejectedReferences=new LinkedHashMap<>();
    private final ArrayDeque<String> outcomes=new ArrayDeque<>();
    private int recoveries;
    private JSONObject recoveryAction;
    private long recoveryWaitedMs;
    private int recoveryAttempt;
    private String recoveryReason="accepted_action_unconfirmed",activeSubgoal,transitionAttempt;
    private boolean subgoalInitialized;
    private int progressRescues,subgoalProgressRescues;
    private int failureReplanReservations;
    private static final class Event {
        final String pkg;final long at;
        Event(String pkg,long at){this.pkg=pkg;this.at=at;}
    }
    public AppTaskMetaController(Arm arm){this(arm,()->System.currentTimeMillis());}
    AppTaskMetaController(Arm arm,LongSupplier wallClock){this.arm=Objects.requireNonNull(arm);this.wallClock=Objects.requireNonNull(wallClock);}
    /** A native step can be verified without completing its active subgoal. */
    public void subgoal(String id){
        if(!subgoalInitialized||!Objects.equals(activeSubgoal,id)){
            activeSubgoal=id;subgoalInitialized=true;subgoalProgressRescues=0;clearScopedRoutes();
        }
    }
    /** Explicit app selection starts fresh local observation bookkeeping, not new action authority. */
    public void resetTargetContext(){
        clearRecoveryContext();clearScopedRoutes();activeSubgoal=null;subgoalInitialized=false;subgoalProgressRescues=0;
    }
    private void clearScopedRoutes(){progressRoutes.clear();failureRequestRoutes.clear();stagnantRoutes.clear();}
    private void rememberStagnant(String route){if(route!=null)stagnantRoutes.add(route);while(stagnantRoutes.size()>MAX_MEMORY)stagnantRoutes.remove(stagnantRoutes.iterator().next());}
    /** Reserve at the actual send boundary; repeated events/response discards cannot buy more calls. */
    public boolean claimPlannerRequest(ScreenSnapshot current){
        if(arm!=Arm.META)return true;
        String route=AppTaskTransitionDetector.currentRoute(current,wallClock.getAsLong());
        if(route!=null&&stagnantRoutes.contains(route))return false;
        if(recoveryAction==null||!"accepted_action_unconfirmed".equals(recoveryReason))return true;
        if(route==null||failureRequestRoutes.contains(route)||failureReplanReservations>=MAX_RECOVERIES){
            rememberStagnant(route);remember("stagnation_request_budget");return false;
        }
        failureRequestRoutes.add(route);failureReplanReservations++;return true;
    }
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
        return after(before,after,action,verdict,elapsedMs,nowMs,null,null);
    }
    public Decision after(JSONObject before,JSONObject after,JSONObject action,AppTaskVerifier.Verdict verdict,long elapsedMs,long nowMs,
            ScreenSnapshot beforeNative,ScreenSnapshot afterNative){
        if(verdict==AppTaskVerifier.Verdict.VERIFIED){if(arm==Arm.META)clearScopedRoutes();clearRecoveryContext();remember("native_verified");return Decision.CONTINUE;}
        if(verdict==AppTaskVerifier.Verdict.PROTECTED||verdict==AppTaskVerifier.Verdict.STALE){clearRecoveryContext();remember(verdict.name());return Decision.HANDOFF;}
        if(arm==Arm.META){
            String attempt=key(before,action)+"|"+(before==null?"":before.optString("observation_id"))+"|"+(action==null?"":action.optString("id"));
            if(!attempt.equals(transitionAttempt)){transition.reset();transitionAttempt=attempt;}
            var evidence=transition.observe(beforeNative,afterNative,nowMs,wallClock.getAsLong());
            if(verdict==AppTaskVerifier.Verdict.WAITING&&evidence.kind()==AppTaskTransitionDetector.Kind.STABLE_CHANGED&&navigationAction(before,action)){
                if(progressRoutes.contains(evidence.afterRoute())||subgoalProgressRescues>=MAX_PROGRESS_PER_SUBGOAL){
                    clearRecoveryContext();remember("progress_budget_or_cycle");return Decision.HANDOFF;
                }
                JSONObject reference=actionReference(before,action);
                if(reference==null){clearRecoveryContext();remember("unobservable");return Decision.HANDOFF;}
                progressRoutes.add(evidence.beforeRoute());progressRoutes.add(evidence.afterRoute());
                while(progressRoutes.size()>MAX_MEMORY)progressRoutes.remove(progressRoutes.iterator().next());
                progressRescues++;subgoalProgressRescues++;
                recoveryAction=reference;recoveryWaitedMs=elapsedMs;recoveryAttempt=subgoalProgressRescues;
                recoveryReason="observed_transition_unconfirmed";remember("reobserve_after_native_transition");
                return Decision.REOBSERVE_PROGRESS;
            }
            // Acceptance does not identify when asynchronous UI effects finish. Read only until
            // the existing absolute lease; unrelated event storms cannot extend it.
            if(elapsedMs<MAX_EVENT_WAIT_MS&&(verdict==AppTaskVerifier.Verdict.WAITING||verdict==AppTaskVerifier.Verdict.UNKNOWN))return Decision.WAIT;
            if((beforeNative!=null||afterNative!=null)&&!evidence.safeCurrent()){
                clearRecoveryContext();remember("unobservable");return Decision.HANDOFF;
            }
            if(evidence.afterRoute()!=null&&failureRequestRoutes.contains(evidence.afterRoute())){
                rememberStagnant(evidence.afterRoute());clearRecoveryContext();remember("stagnation_route_budget");return Decision.HANDOFF;
            }
        }
        if(elapsedMs<NORMAL_WAIT_MS&&(verdict==AppTaskVerifier.Verdict.WAITING||verdict==AppTaskVerifier.Verdict.UNKNOWN))return Decision.WAIT;
        if(!enabled()){clearRecoveryContext();return Decision.HANDOFF;}
        // Missing/stale observations cannot justify replay or automatic recovery.
        if(verdict!=AppTaskVerifier.Verdict.WAITING||!freshPublic(before,after)){
            clearRecoveryContext();remember("unobservable");return Decision.HANDOFF;
        }
        if(arm==Arm.NO_EVENTS&&elapsedMs<MAX_EVENT_WAIT_MS&&recentEvent(after.optString("package"),nowMs,nowMs-elapsedMs))return Decision.WAIT;
        String key=key(before,action);
        if(key.isEmpty()||rejected.contains(key)||recoveries>=MAX_RECOVERIES){clearRecoveryContext();remember("recovery_budget");return Decision.HANDOFF;}
        // Accepted-but-unverified actions are not automatically performed again.
        rejected.addLast(key);while(rejected.size()>MAX_MEMORY)rejectedReferences.remove(rejected.removeFirst());
        recoveries++;remember("reobserve_after_unconfirmed_action");
        recoveryAction=actionReference(before,action);recoveryWaitedMs=elapsedMs;recoveryAttempt=recoveries;recoveryReason="accepted_action_unconfirmed";
        if(recoveryAction!=null)rejectedReferences.put(key,recoveryAction);
        return Decision.REOBSERVE;
    }
    /** Context is advisory and cannot authorize a tool or replace native verification. */
    public JSONObject recoveryContext(JSONObject current)throws JSONException {
        if(!enabled()||recoveryAction==null||current==null||!"public".equals(current.optString("status"))
                ||current.optString("observation_id").isEmpty()||recoveryWaitedMs<0||recoveryWaitedMs>120000
                ||recoveryAttempt<1||recoveryAttempt>MAX_RECOVERIES)return null;
        JSONArray references=new JSONArray();
        for(JSONObject reference:rejectedReferences.values())references.put(new JSONObject(reference.toString()));
        return new JSONObject().put("policy",arm==Arm.META?"mc_v1":"mc_v0").put("reason",recoveryReason)
            .put("last_verdict","waiting").put("waited_ms",recoveryWaitedMs).put("attempt",recoveryAttempt)
            .put("observation_id",current.getString("observation_id"))
            .put("last_action",new JSONObject(recoveryAction.toString())).put("blocked_actions",references);
    }
    /** Keep rejected-history policy intact, but a new action/result ends this recovery decision. */
    public void clearRecoveryContext(){recoveryAction=null;recoveryWaitedMs=0;recoveryAttempt=0;recoveryReason="accepted_action_unconfirmed";transition.reset();transitionAttempt=null;}
    private static boolean navigationAction(JSONObject before,JSONObject action){
        if(action==null)return false;
        String tool=action.optString("tool");JSONObject expectation=action.optJSONObject("expectation");
        if(expectation==null||!Set.of("screen_changed","target_visible").contains(expectation.optString("kind")))return false;
        if("back".equals(tool)||tool.startsWith("scroll_"))return true;
        if(!"tap".equals(tool)||!AppTaskPolicy.manualTapAllowed(before,action))return false;
        JSONObject node=com.click.data.snapshot.AppTaskObservation.find(before,action.optString("target_id"));
        return node!=null&&!node.optBoolean("editable")&&node.isNull("checked")&&!Set.of("toggle","tab").contains(node.optString("role"));
    }
    private static JSONObject actionReference(JSONObject before,JSONObject action){
        if(before==null||action==null)return null;
        String fingerprint=before.optString("fingerprint"),tool=action.optString("tool"),pkg=action.optString("package",before.optString("package"));
        Object target=action.opt("target_id");
        if(!fingerprint.matches("[0-9a-f]{64}")||!Set.of("tap","set_text","scroll_forward","scroll_backward","back","open_app").contains(tool)
                ||pkg.length()>200||!pkg.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")
                ||target!=null&&target!=JSONObject.NULL&&(!(target instanceof String)||((String)target).length()>200))return null;
        try{return new JSONObject().put("tool",tool).put("package",pkg).put("target_id",target==null?JSONObject.NULL:target).put("fingerprint",fingerprint);}
        catch(JSONException invalid){return null;}
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
            .put("progress_rescue_count",progressRescues).put("subgoal_progress_rescue_count",subgoalProgressRescues)
            .put("failure_replan_reservations",failureReplanReservations)
            .put("outcomes",new JSONArray(outcomes)).put("reflection_model_calls",0);
    }
}
