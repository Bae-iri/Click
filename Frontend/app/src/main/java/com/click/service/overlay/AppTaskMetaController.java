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
    private final Map<String,String> rejectedControls=new HashMap<>();
    private final Map<String,JSONObject> rejectedReferences=new LinkedHashMap<>();
    private final ArrayDeque<String> outcomes=new ArrayDeque<>();
    private int recoveries;
    private JSONObject recoveryAction;
    private long recoveryWaitedMs;
    private int recoveryAttempt;
    private String failureReason="accepted_action_unconfirmed";
    private String recoveryReason="accepted_action_unconfirmed",activeSubgoal,transitionAttempt;
    private boolean subgoalInitialized;
    private int progressRescues,subgoalProgressRescues;
    private int failureReplanReservations;
    private boolean unresolvedFailure;
    private int failureEpochSequence,unresolvedFailurePlannerReservations;
    private String failureEpochRef,lastPlannerRouteRef;
    private JSONObject unresolvedFailureAction;
    private long unresolvedFailureWaitedMs;
    private enum ResetReason { INITIAL, NATIVE_SUBGOAL_ADVANCED, TARGET_CONTEXT, USER_REFRESH }
    private ResetReason scopedBudgetResetReason=ResetReason.INITIAL;
    private static final class Event {
        final String pkg;final long at;
        Event(String pkg,long at){this.pkg=pkg;this.at=at;}
    }
    public AppTaskMetaController(Arm arm){this(arm,()->System.currentTimeMillis());}
    AppTaskMetaController(Arm arm,LongSupplier wallClock){this.arm=Objects.requireNonNull(arm);this.wallClock=Objects.requireNonNull(wallClock);}
    /** Synchronizing a plan ID is not proof that the previous native subgoal advanced. */
    public void subgoal(String id){activeSubgoal=id;subgoalInitialized=true;}
    /** Called only after native verification has advanced the immutable task plan. */
    public void nativeSubgoalAdvanced(String previous,String current,JSONObject action,AppTaskVerifier.Verdict verdict){
        nativeSubgoalAdvanced(previous,current,action,verdict,null,null);
    }
    /** Action-level satisfaction releases no budget unless this action newly established its native predicate. */
    public void nativeSubgoalAdvanced(String previous,String current,JSONObject action,AppTaskVerifier.Verdict verdict,JSONObject before,JSONObject after){
        if(arm!=Arm.META||verdict!=AppTaskVerifier.Verdict.VERIFIED||!AppTaskPolicy.provesSubgoal(action)
                ||previous==null||Objects.equals(previous,current)||!subgoalInitialized||!Objects.equals(activeSubgoal,previous)
                ||!newlyEstablished(before,after,action))return;
        resetScopedBudget(ResetReason.NATIVE_SUBGOAL_ADVANCED);
        activeSubgoal=current;subgoalInitialized=true;
    }
    private boolean newlyEstablished(JSONObject before,JSONObject after,JSONObject action){
        if(before==null||after==null||!"public".equals(before.optString("status"))||!"public".equals(after.optString("status"))
                ||before.optString("observation_id").isEmpty()||after.optString("observation_id").isEmpty()
                ||before.optString("observation_id").equals(after.optString("observation_id"))
                ||after.optLong("captured_at_ms")<before.optLong("captured_at_ms")
                ||after.optLong("captured_at_ms")>wallClock.getAsLong()+2000
                ||wallClock.getAsLong()-after.optLong("captured_at_ms")>15000)return false;
        JSONObject expectation=action.optJSONObject("expectation");String kind=expectation.optString("kind");
        if("package_opened".equals(kind))return !before.optString("package").equals(expectation.optString("package"))
            &&after.optString("package").equals(expectation.optString("package"));
        if(!before.optString("package").equals(after.optString("package")))return false;
        if("target_visible".equals(kind))return AppTaskVerifier.check(before,after,action,true,false)==AppTaskVerifier.Verdict.VERIFIED;
        JSONObject old=com.click.data.snapshot.AppTaskObservation.find(before,action.optString("target_id"));
        JSONObject next=AppTaskVerifier.resolveTarget(before,after,action.optString("target_id"));
        if(old==null||next==null)return false;
        if("input_present".equals(kind))return old.optBoolean("editable")&&next.optBoolean("editable")
            &&Boolean.FALSE.equals(old.opt("has_value"))&&Boolean.TRUE.equals(next.opt("has_value"));
        if(!"selection_changed".equals(kind))return false;
        boolean changed=false;
        for(String state:List.of("checked","selected"))if(!expectation.isNull(state)){
            if(!(old.opt(state) instanceof Boolean)||!(next.opt(state) instanceof Boolean)
                    ||!expectation.opt(state).equals(next.opt(state)))return false;
            changed|=!old.opt(state).equals(next.opt(state));
        }
        return changed;
    }
    /** Explicit app selection starts fresh local observation bookkeeping, not new action authority. */
    public void resetTargetContext(){
        resetScopedBudget(ResetReason.TARGET_CONTEXT);activeSubgoal=null;subgoalInitialized=false;
    }
    /** Existing explicit user refresh can leave a local dead end, within the taskwide ceiling. */
    public void resetUserContext(){resetScopedBudget(ResetReason.USER_REFRESH);}
    private void resetScopedBudget(ResetReason reason){
        clearRecoveryContext();progressRoutes.clear();failureRequestRoutes.clear();stagnantRoutes.clear();
        subgoalProgressRescues=0;unresolvedFailure=false;unresolvedFailurePlannerReservations=0;
        unresolvedFailureAction=null;unresolvedFailureWaitedMs=0;
        failureEpochRef=null;lastPlannerRouteRef=null;scopedBudgetResetReason=reason;
    }
    private void beginFailureEpisode(JSONObject before,JSONObject action,long waitedMs){
        if(arm!=Arm.META)return;
        if(!unresolvedFailure){
            unresolvedFailure=true;failureEpochSequence++;
            failureEpochRef=reference(failureEpochSequence+"|"+activeSubgoal+"|"+key(before,action));
        }
        unresolvedFailureAction=actionReference(before,action);unresolvedFailureWaitedMs=waitedMs;
    }
    private static String reference(String value){
        return value==null?null:com.click.data.snapshot.AppTaskObservation.hash(value).substring(0,24);
    }
    private void rememberStagnant(String route){if(route!=null)stagnantRoutes.add(route);while(stagnantRoutes.size()>MAX_MEMORY)stagnantRoutes.remove(stagnantRoutes.iterator().next());}
    /** Charge every ordinary send in an unresolved failure episode, even after action-only success. */
    public boolean claimPlannerRequest(ScreenSnapshot current){
        if(arm!=Arm.META)return true;
        String route=AppTaskTransitionDetector.currentRoute(current,wallClock.getAsLong());lastPlannerRouteRef=reference(route);
        if(route!=null&&stagnantRoutes.contains(route))return false;
        if(!unresolvedFailure)return true;
        if(route==null||failureRequestRoutes.contains(route)||failureReplanReservations>=MAX_RECOVERIES){
            rememberStagnant(route);remember("stagnation_request_budget");return false;
        }
        failureRequestRoutes.add(route);failureReplanReservations++;unresolvedFailurePlannerReservations++;return true;
    }
    public Arm arm(){return arm;}
    public boolean enabled(){return arm!=Arm.CURRENT;}
    /** Only an observed native screen-change notification, not inferred task success. */
    public void observeEvent(JSONObject observation,long monotonicMs){
        if(arm!=Arm.META||observation==null||!"public".equals(observation.optString("status"))||monotonicMs<0)return;
        events.addLast(new Event(observation.optString("package"),monotonicMs));
        while(events.size()>MAX_MEMORY)events.removeFirst();
    }
    public boolean blocked(JSONObject before,JSONObject action){
        if(!enabled()||before==null||action==null)return false;
        if(rejected.contains(key(before,action)))return true;
        String control=statefulControl(before,action);
        return !control.isEmpty()&&rejectedControls.containsValue(control);
    }
    /** Bounds and accessibility path can move during redraws while a switch remains the same. */
    private static String statefulControl(JSONObject screen,JSONObject action){
        if(!"tap".equals(action.optString("tool"))||!"public".equals(screen.optString("status")))return "";
        JSONObject node=com.click.data.snapshot.AppTaskObservation.find(screen,action.optString("target_id"));
        if(node==null||!"accessibility".equals(node.optString("source"))||!Set.of("tab","toggle").contains(node.optString("role")))return "";
        String key=com.click.data.snapshot.AppTaskObservation.targetKey(node);
        JSONArray nodes=screen.optJSONArray("nodes");int matches=0;
        List<String> headings=new ArrayList<>();
        for(int i=0;nodes!=null&&i<nodes.length();i++){
            JSONObject other=nodes.optJSONObject(i);
            if(key.equals(com.click.data.snapshot.AppTaskObservation.targetKey(other)))matches++;
            if(other.optBoolean("native_heading"))headings.add(other.optString("label"));
        }
        boolean generic=AppTaskSwitchGoal.genericLabel(node.optString("label"))||node.optString("label").isEmpty();
        if(matches!=1||generic&&headings.size()!=1)return "";
        return screen.optString("package")+"|"+key+"|"+(generic?headings.get(0):"");
    }
    public Decision after(JSONObject before,JSONObject after,JSONObject action,AppTaskVerifier.Verdict verdict,long elapsedMs,long nowMs){
        return after(before,after,action,verdict,elapsedMs,nowMs,null,null);
    }
    public Decision after(JSONObject before,JSONObject after,JSONObject action,AppTaskVerifier.Verdict verdict,long elapsedMs,long nowMs,
            ScreenSnapshot beforeNative,ScreenSnapshot afterNative){
        if(verdict==AppTaskVerifier.Verdict.VERIFIED){clearRecoveryContext();remember("native_verified");return Decision.CONTINUE;}
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
        return replan(before,action,elapsedMs,"accepted_action_unconfirmed");
    }
    Decision replan(JSONObject before,JSONObject action,long elapsedMs,String reason){
        if(!enabled())return Decision.HANDOFF;
        String key=key(before,action);
        if(key.isEmpty()||blocked(before,action)||recoveries>=MAX_RECOVERIES){clearRecoveryContext();remember("recovery_budget");return Decision.HANDOFF;}
        // Accepted-but-unverified actions are not automatically performed again.
        rejected.addLast(key);while(rejected.size()>MAX_MEMORY){
            String stale=rejected.removeFirst();rejectedReferences.remove(stale);rejectedControls.remove(stale);
        }
        String control=statefulControl(before,action);
        if(!control.isEmpty())rejectedControls.put(key,control);
        recoveries++;beginFailureEpisode(before,action,elapsedMs);failureReason=reason;remember("accepted_action_unconfirmed".equals(reason)?"reobserve_after_unconfirmed_action":reason);
        recoveryAction=actionReference(before,action);recoveryWaitedMs=elapsedMs;recoveryAttempt=recoveries;recoveryReason=reason;
        if(recoveryAction!=null)rejectedReferences.put(key,recoveryAction);
        return Decision.REOBSERVE;
    }
    /** Context is advisory and cannot authorize a tool or replace native verification. */
    public JSONObject recoveryContext(JSONObject current)throws JSONException {
        boolean failureContext=arm==Arm.META&&unresolvedFailure&&unresolvedFailureAction!=null;
        JSONObject contextAction=failureContext?unresolvedFailureAction:recoveryAction;
        long waitedMs=failureContext?unresolvedFailureWaitedMs:recoveryWaitedMs;
        int attempt=failureContext?Math.max(1,unresolvedFailurePlannerReservations):recoveryAttempt;
        String reason=failureContext?failureReason:recoveryReason;
        if(!enabled()||contextAction==null||current==null||!"public".equals(current.optString("status"))
                ||current.optString("observation_id").isEmpty()||waitedMs<0||waitedMs>120000
                ||attempt<1||attempt>MAX_RECOVERIES)return null;
        JSONArray references=new JSONArray();
        for(var entry:rejectedReferences.entrySet()){
            JSONObject reference=new JSONObject(entry.getValue().toString());
            String control=rejectedControls.get(entry.getKey());
            if(control!=null&&!reference.optString("fingerprint").equals(current.optString("fingerprint"))){
                JSONArray nodes=current.getJSONArray("nodes");
                for(int i=0;i<nodes.length();i++){
                    JSONObject n=nodes.getJSONObject(i);
                    if(!Set.of("toggle","tab").contains(n.optString("role")))continue;
                    JSONObject target=new JSONObject().put("tool","tap").put("target_id",n.getString("id"));
                    if(control.equals(statefulControl(current,target))){
                        reference.put("target_id",n.getString("id")).put("fingerprint",current.getString("fingerprint"));break;
                    }
                }
            }
            references.put(reference);
        }
        return new JSONObject().put("policy",arm==Arm.META?"mc_v2":"mc_v0").put("reason",reason)
            .put("last_verdict","execution_rejected".equals(reason)?"rejected":"value_contradicted".equals(reason)?"contradicted":"waiting").put("waited_ms",waitedMs).put("attempt",attempt)
            .put("observation_id",current.getString("observation_id"))
            .put("last_action",new JSONObject(contextAction.toString())).put("blocked_actions",references);
    }
    /** End only the immediate action decision; an unresolved failure retains its advisory context. */
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
        return new JSONObject().put("arm",arm.name()).put("policy",arm==Arm.META?"mc_v2":arm==Arm.CURRENT?"current":"mc_v0").put("event_source","native_screen_change_only")
            .put("event_count",events.size()).put("recovery_count",recoveries).put("blocked_count",rejected.size())
            .put("progress_rescue_count",progressRescues).put("subgoal_progress_rescue_count",subgoalProgressRescues)
            .put("failure_replan_reservations",failureReplanReservations)
            .put("unresolved_failure_active",unresolvedFailure)
            .put("unresolved_failure_planner_reservations",unresolvedFailurePlannerReservations)
            .put("failure_epoch_ref",failureEpochRef==null?JSONObject.NULL:failureEpochRef)
            .put("planner_route_ref",lastPlannerRouteRef==null?JSONObject.NULL:lastPlannerRouteRef)
            .put("scoped_budget_reset_reason",scopedBudgetResetReason.name().toLowerCase(Locale.ROOT))
            .put("outcomes",new JSONArray(outcomes)).put("reflection_model_calls",0);
    }
}
