package com.click.service.overlay;

import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static com.click.service.overlay.AppTaskMetaController.*;
import static com.click.service.overlay.AppTaskTransitionDetectorTest.*;

/** Cost bounds survive action-only success; they never establish goal success or authorize replay. */
public class AppTaskMetaControllerV2Test {
    private JSONObject tap(ScreenSnapshot before,String suffix)throws Exception {
        JSONObject observed=AppTaskObservation.capture(before),node=null;
        JSONArray nodes=observed.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++)if(nodes.getJSONObject(i).optBoolean("clickable")){node=nodes.getJSONObject(i);break;}
        assertNotNull(node);
        return new JSONObject().put("id","candidate_"+suffix).put("tool","tap").put("package",before.packageName)
            .put("target_id",node.getString("id")).put("bounds",node.getJSONArray("bounds"))
            .put("expectation",new JSONObject().put("kind","target_visible").put("label","Unseen destination"));
    }
    private JSONObject fail(AppTaskMetaController meta,AtomicLong clock,ScreenSnapshot before,ScreenSnapshot current,String suffix)throws Exception {
        JSONObject action=tap(before,suffix);long at=Instant.parse(current.capturedAt).toEpochMilli();clock.set(at);
        assertEquals(Decision.REOBSERVE,meta.after(AppTaskObservation.capture(before),AppTaskObservation.capture(current),action,
            AppTaskVerifier.Verdict.WAITING,8000,at-WALL+10000,before,current));
        return action;
    }
    private void verified(AppTaskMetaController meta,AtomicLong clock,ScreenSnapshot before,ScreenSnapshot after,JSONObject action)throws Exception {
        JSONObject original=AppTaskObservation.capture(before),current=AppTaskObservation.capture(after);
        var verdict=AppTaskVerifier.check(original,current,action,true,false,Instant.parse(before.capturedAt).toEpochMilli()+1);
        assertEquals(AppTaskVerifier.Verdict.VERIFIED,verdict);long at=Instant.parse(after.capturedAt).toEpochMilli();clock.set(at);
        assertEquals(Decision.CONTINUE,meta.after(original,current,action,verdict,400,at-WALL+10000,before,after));
    }
    private ScreenSnapshot listFrame(String pkg,long at,String route,boolean interactive){
        var list=new AccessibilityNodeSnapshot("list",null,true,pkg,"Entries",null,pkg+":id/list","android.widget.ScrollView",null,
            new int[]{0,0,1000,2200},false,true,false,true,false,null,42);
        var child=new AccessibilityNodeSnapshot("entry","list",true,pkg,route,null,pkg+":id/entry",
            interactive?"android.widget.Button":"android.widget.TextView",null,new int[]{20,30,500,130},interactive,true,false,false,false,null,42);
        return new ScreenSnapshot(pkg,Instant.ofEpochMilli(WALL+at).toString(),1080,2400,List.of(list,child),42)
            .withWindowContext(new NativeWindowContext("UNKNOWN","APPLICATION",false));
    }
    private JSONObject scroll(ScreenSnapshot before,String tool)throws Exception {
        JSONObject observed=AppTaskObservation.capture(before),node=AppTaskObservation.find(observed,"list");assertNotNull(node);
        return new JSONObject().put("tool",tool).put("package",before.packageName).put("target_id","list")
            .put("bounds",node.getJSONArray("bounds")).put("advances_subgoal",true)
            .put("expectation",new JSONObject().put("kind","screen_changed"));
    }
    @Test public void rejectedOrContradictedSwitchCannotBeTappedAgainAfterARedraw()throws Exception{
        for(String reason:java.util.List.of("execution_rejected","value_contradicted")){
            var meta=new AppTaskMetaController(AppTaskMetaController.Arm.META);
            var node=new JSONObject().put("id","old").put("label","자동 동기화").put("role","toggle")
                .put("resource","org.reader:id/sync").put("source","accessibility").put("checked",true);
            var before=new JSONObject().put("status","public").put("package","org.reader").put("fingerprint","a".repeat(64))
                .put("observation_id","one").put("nodes",new org.json.JSONArray().put(node));
            var tap=new JSONObject().put("tool","tap").put("target_id","old").put("package","org.reader");
            assertEquals(AppTaskMetaController.Decision.REOBSERVE,meta.replan(before,tap,0,reason));
            var after=new JSONObject(before.toString()).put("fingerprint","b".repeat(64)).put("observation_id","two");
            after.getJSONArray("nodes").getJSONObject(0).put("id","new");
            tap.put("target_id","new");
            assertTrue(meta.blocked(after,tap));
            var recovery=meta.recoveryContext(after);
            assertEquals(reason,recovery.getString("reason"));
            assertEquals("new",recovery.getJSONArray("blocked_actions").getJSONObject(0).getString("target_id"));
            assertEquals(AppTaskMetaController.Decision.HANDOFF,meta.replan(after,tap,0,reason));
        }
    }
    @Test public void verifiedSameAppOpenAndOrdinaryRefreshCannotReleaseAnUnresolvedFailure()throws Exception {
        for(String pkg:PACKAGES){
            AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
            ScreenSnapshot before=frame(pkg,0,42,"Alpha"),current=frame(pkg,8000,42,"Alpha");fail(meta,clock,before,current,"failure");
            assertTrue(meta.claimPlannerRequest(current));String epoch=meta.diagnostic().getString("failure_epoch_ref");meta.clearRecoveryContext();
            var opened=frame(pkg,8500,42,"Alpha");JSONObject open=new JSONObject().put("tool","open_app").put("package",pkg)
                .put("target_id",JSONObject.NULL).put("advances_subgoal",true)
                .put("expectation",new JSONObject().put("kind","package_opened").put("package",pkg));
            verified(meta,clock,current,opened,open);meta.nativeSubgoalAdvanced("g0","g0",open,AppTaskVerifier.Verdict.VERIFIED,AppTaskObservation.capture(current),AppTaskObservation.capture(opened));
            assertFalse(meta.claimPlannerRequest(opened));meta.clearRecoveryContext();meta.subgoal("g0");
            assertFalse(meta.claimPlannerRequest(frame(pkg,8500,42,"Alpha",35,"Changed passive heading")));
            assertEquals(epoch,meta.diagnostic().getString("failure_epoch_ref"));assertEquals(1,meta.diagnostic().getInt("failure_replan_reservations"));
            assertEquals("initial",meta.diagnostic().getString("scoped_budget_reset_reason"));
        }
    }
    @Test public void oppositeVerifiedScrollsCannotPurchaseASecondCallOnTheReturnedRoute()throws Exception {
        for(String pkg:PACKAGES){
            AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
            ScreenSnapshot home=listFrame(pkg,0,"Alpha",true),current=listFrame(pkg,8000,"Alpha",true);
            fail(meta,clock,home,current,"first");assertTrue(meta.claimPlannerRequest(current));
            var lower=listFrame(pkg,8500,"Beta",true);JSONObject forward=scroll(current,"scroll_forward");
            assertFalse(AppTaskPolicy.provesSubgoal(forward));verified(meta,clock,current,lower,forward);
            assertTrue(meta.claimPlannerRequest(lower));
            var returned=listFrame(pkg,9000,"Alpha",true);verified(meta,clock,lower,returned,scroll(lower,"scroll_backward"));
            assertFalse(meta.claimPlannerRequest(returned));assertEquals(2,meta.diagnostic().getInt("failure_replan_reservations"));
            assertEquals(1,meta.diagnostic().getInt("recovery_count"));assertTrue(meta.diagnostic().getBoolean("unresolved_failure_active"));
        }
    }
    @Test public void viewportOnlyScrollVerificationDoesNotCreateAFreeSemanticRoute()throws Exception {
        String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
        ScreenSnapshot before=frame(pkg,0,42,"Alpha"),current=frame(pkg,8000,42,"Alpha");fail(meta,clock,before,current,"first");assertTrue(meta.claimPlannerRequest(current));
        ScreenSnapshot passiveBefore=listFrame(pkg,8100,"Old paragraph",false),passiveAfter=listFrame(pkg,8500,"New paragraph",false);
        verified(meta,clock,passiveBefore,passiveAfter,scroll(passiveBefore,"scroll_forward"));assertTrue(meta.claimPlannerRequest(passiveAfter));
        var next=listFrame(pkg,9000,"Third paragraph",false);verified(meta,clock,passiveAfter,next,scroll(passiveAfter,"scroll_backward"));
        assertFalse(meta.claimPlannerRequest(next));assertEquals(2,meta.diagnostic().getInt("failure_replan_reservations"));
    }
    @Test public void ordinarySendsAcrossDistinctRoutesShareTheTaskwideThreeCallCeiling()throws Exception {
        String pkg=PACKAGES[1];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
        ScreenSnapshot before=frame(pkg,0,42,"Alpha"),current=frame(pkg,8000,42,"Alpha");fail(meta,clock,before,current,"first");assertTrue(meta.claimPlannerRequest(current));
        for(int i=0;i<3;i++){
            var next=frame(pkg,8500+i*500,42,"Distinct"+i);var action=tap(current,"ordinary"+i);action.getJSONObject("expectation").put("kind","screen_changed");
            verified(meta,clock,current,next,action);assertNotNull(meta.recoveryContext(AppTaskObservation.capture(next)));
            assertEquals(i<2,meta.claimPlannerRequest(next));
            JSONObject context=meta.recoveryContext(AppTaskObservation.capture(next));
            assertEquals("accepted_action_unconfirmed",context.getString("reason"));
            assertEquals(Math.min(i+2,3),context.getInt("attempt"));
            assertEquals(AppTaskObservation.capture(before).getString("fingerprint"),context.getJSONObject("last_action").getString("fingerprint"));
            assertEquals(AppTaskObservation.capture(next).getString("observation_id"),context.getString("observation_id"));current=next;
        }
        assertEquals(3,meta.diagnostic().getInt("failure_replan_reservations"));assertEquals(3,meta.diagnostic().getInt("unresolved_failure_planner_reservations"));
        assertEquals(1,meta.diagnostic().getInt("recovery_count"));
    }
    @Test public void nativeProvenNewSubgoalAllowsLegitimateReturnToHubWithoutResettingTaskCost()throws Exception {
        String pkg=PACKAGES[2];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);
        var memory=new AppTaskMemory("Explore two public destinations");memory.plan(new JSONArray().put(new JSONObject().put("title","First"))
            .put(new JSONObject().put("title","Second")));meta.subgoal(memory.active());
        ScreenSnapshot hub=frame(pkg,0,42,"Hub"),current=frame(pkg,8000,42,"Hub");JSONObject failed=fail(meta,clock,hub,current,"first");
        assertTrue(meta.claimPlannerRequest(current));assertFalse(meta.claimPlannerRequest(current));
        var reached=frame(pkg,8500,42,"Destination");JSONObject action=tap(current,"verified").put("advances_subgoal",true);
        action.getJSONObject("expectation").put("label","Destination");verified(meta,clock,current,reached,action);
        String previous=memory.active();memory.record(AppTaskObservation.capture(current),action,"verified",true);
        assertEquals("g1",memory.active());meta.nativeSubgoalAdvanced(previous,memory.active(),action,AppTaskVerifier.Verdict.VERIFIED,AppTaskObservation.capture(current),AppTaskObservation.capture(reached));
        assertEquals("native_subgoal_advanced",meta.diagnostic().getString("scoped_budget_reset_reason"));assertFalse(meta.diagnostic().getBoolean("unresolved_failure_active"));
        clock.set(WALL+9000);assertTrue(meta.claimPlannerRequest(frame(pkg,9000,42,"Hub")));
        assertEquals(1,meta.diagnostic().getInt("failure_replan_reservations"));assertTrue(meta.blocked(AppTaskObservation.capture(hub),failed));
    }
    @Test public void planIdChangesScrollAndNonverifiedClaimsCannotReleaseFailureBudget()throws Exception {
        String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
        ScreenSnapshot before=frame(pkg,0,42,"Alpha"),current=frame(pkg,8000,42,"Alpha");JSONObject failed=fail(meta,clock,before,current,"first");assertTrue(meta.claimPlannerRequest(current));
        meta.subgoal("g1");assertFalse(meta.claimPlannerRequest(current));
        JSONObject purported=tap(current,"purported").put("advances_subgoal",true);
        for(var verdict:List.of(AppTaskVerifier.Verdict.WAITING,AppTaskVerifier.Verdict.PROTECTED,AppTaskVerifier.Verdict.STALE))
            meta.nativeSubgoalAdvanced("g1","g2",purported,verdict);
        meta.nativeSubgoalAdvanced("g0","g1",purported,AppTaskVerifier.Verdict.VERIFIED);
        meta.nativeSubgoalAdvanced("g1","g2",scroll(listFrame(pkg,8000,"A",true),"scroll_forward"),AppTaskVerifier.Verdict.VERIFIED);
        assertFalse(meta.claimPlannerRequest(current));assertTrue(meta.diagnostic().getBoolean("unresolved_failure_active"));
        assertTrue(meta.blocked(AppTaskObservation.capture(before),failed));
    }
    @Test public void explicitUserResetRetainsTaskwideCapAndRejectedHistory()throws Exception {
        String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
        ScreenSnapshot before=frame(pkg,0,42,"Alpha"),current=frame(pkg,8000,42,"Alpha");JSONObject failed=fail(meta,clock,before,current,"first");
        for(int i=0;i<3;i++){clock.set(WALL+8000+i*100);assertTrue(meta.claimPlannerRequest(frame(pkg,8000+i*100,42,"Route"+i)));}
        meta.resetUserContext();assertEquals("user_refresh",meta.diagnostic().getString("scoped_budget_reset_reason"));
        assertFalse(meta.diagnostic().getBoolean("unresolved_failure_active"));assertTrue(meta.blocked(AppTaskObservation.capture(before),failed));
        assertTrue(meta.claimPlannerRequest(frame(pkg,8200,42,"Route0")));
        fail(meta,clock,frame(pkg,9000,42,"Alpha"),frame(pkg,17000,42,"Alpha"),"second");assertFalse(meta.claimPlannerRequest(frame(pkg,17000,42,"Alpha")));
        assertEquals(3,meta.diagnostic().getInt("failure_replan_reservations"));
    }
    @Test public void diagnosticsUseClosedResetReasonsAndHashedRefsWithoutRawContent()throws Exception {
        String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("PRIVATE_SUBGOAL_ID");
        ScreenSnapshot before=frame(pkg,0,42,"PRIVATE_ROUTE_A"),current=frame(pkg,8000,42,"PRIVATE_ROUTE_A");fail(meta,clock,before,current,"first");
        assertTrue(meta.claimPlannerRequest(current));JSONObject diagnostic=meta.diagnostic();String serialized=diagnostic.toString();
        assertTrue(diagnostic.getString("failure_epoch_ref").matches("[0-9a-f]{24}"));assertTrue(diagnostic.getString("planner_route_ref").matches("[0-9a-f]{24}"));
        assertFalse(serialized.contains("PRIVATE"));assertFalse(serialized.contains(pkg));assertEquals("mc_v2",meta.recoveryContext(AppTaskObservation.capture(current)).getString("policy"));
        meta.resetTargetContext();assertEquals("target_context",meta.diagnostic().getString("scoped_budget_reset_reason"));
        assertTrue(meta.diagnostic().isNull("failure_epoch_ref"));assertTrue(meta.diagnostic().isNull("planner_route_ref"));
    }
    @Test public void everyArmReportsItsActualClosedPolicyVersion()throws Exception {
        assertEquals("current",new AppTaskMetaController(Arm.CURRENT).diagnostic().getString("policy"));
        assertEquals("mc_v2",new AppTaskMetaController(Arm.META).diagnostic().getString("policy"));
        assertEquals("mc_v0",new AppTaskMetaController(Arm.NO_EVENTS).diagnostic().getString("policy"));
    }
    @Test public void alreadySatisfiedPackageSelectionAndInputCannotReleaseBudgetEvenIfPlanAdvances()throws Exception {
        for(String kind:List.of("package_opened","selection_changed","input_present"))for(boolean alreadySatisfied:List.of(true,false)){
            String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
            ScreenSnapshot start=frame(pkg,0,42,"Alpha"),current=frame(pkg,8000,42,"Alpha");
            JSONObject failed=fail(meta,clock,start,current,"original");assertTrue(meta.claimPlannerRequest(current));
            JSONObject before=AppTaskObservation.capture(frame(pkg,8500,42,"Alpha"));
            JSONObject after=AppTaskObservation.capture(frame(pkg,8900,42,"Alpha"));
            JSONObject action=tap(frame(pkg,8500,42,"Alpha"),"satisfied").put("advances_subgoal",true);
            JSONObject expectation=new JSONObject().put("kind",kind);action.put("expectation",expectation);
            JSONObject old=before.getJSONArray("nodes").getJSONObject(0),next=after.getJSONArray("nodes").getJSONObject(0);
            if(kind.equals("package_opened")){
                String destination=alreadySatisfied?pkg:PACKAGES[1];action.put("tool","open_app").put("package",destination).put("target_id",JSONObject.NULL);
                expectation.put("package",destination);after.put("package",destination);
            }else if(kind.equals("selection_changed")){
                old.put("selected",alreadySatisfied);next.put("selected",true);expectation.put("selected",true);
            }else{
                action.put("tool","set_text");old.put("editable",true).put("has_value",alreadySatisfied);
                next.put("editable",true).put("has_value",true);
            }
            var verdict=AppTaskVerifier.check(before,after,action,true,kind.equals("input_present"),WALL+8501);
            assertEquals(AppTaskVerifier.Verdict.VERIFIED,verdict);clock.set(WALL+8900);
            meta.nativeSubgoalAdvanced("g0","g1",action,verdict,before,after);
            assertEquals(alreadySatisfied,meta.diagnostic().getBoolean("unresolved_failure_active"));
            assertEquals(!alreadySatisfied,meta.claimPlannerRequest(frame(pkg,8900,42,"Alpha")));
            assertEquals(1,meta.diagnostic().getInt("failure_replan_reservations"));
            assertTrue(meta.blocked(AppTaskObservation.capture(start),failed));
        }
    }
}
