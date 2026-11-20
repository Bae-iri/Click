package com.click.service.overlay;

import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static com.click.service.overlay.AppTaskMetaController.*;
import static com.click.service.overlay.AppTaskTransitionDetectorTest.*;

public class AppTaskMetaControllerV1Test {
    private JSONObject action(ScreenSnapshot before,String suffix)throws Exception {
        JSONObject observed=AppTaskObservation.capture(before),node=observed.getJSONArray("nodes").getJSONObject(0);
        return new JSONObject().put("id","action_"+suffix).put("tool","tap").put("package",before.packageName)
            .put("target_id",node.getString("id")).put("bounds",node.getJSONArray("bounds"))
            .put("expectation",new JSONObject().put("kind","target_visible").put("label","Unseen destination heading"));
    }
    private Decision after(AppTaskMetaController meta,AtomicLong clock,ScreenSnapshot before,ScreenSnapshot current,JSONObject action,long elapsed)throws Exception {
        long at=java.time.Instant.parse(current.capturedAt).toEpochMilli();clock.set(at);
        JSONObject original=AppTaskObservation.capture(before),observed=AppTaskObservation.capture(current);
        var verdict=AppTaskVerifier.check(original,observed,action,true,false,java.time.Instant.parse(before.capturedAt).toEpochMilli()+1);
        return meta.after(original,observed,action,verdict,elapsed,at-WALL+10000,before,current);
    }
    @Test public void stableNativeTransitionReobservesEarlyWithoutClaimingStepOrGoalSuccess()throws Exception {
        for(String pkg:PACKAGES){
            AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
            var before=frame(pkg,0,42,"Alpha");var action=action(before,pkg);
            assertEquals(Decision.WAIT,after(meta,clock,before,frame(pkg,100,43,"Beta"),action,100));
            var current=frame(pkg,450,43,"Beta");assertEquals(Decision.REOBSERVE_PROGRESS,after(meta,clock,before,current,action,450));
            assertFalse(meta.blocked(AppTaskObservation.capture(before),action));assertEquals(0,meta.diagnostic().getInt("recovery_count"));
            assertEquals(1,meta.diagnostic().getInt("progress_rescue_count"));
            JSONObject context=meta.recoveryContext(AppTaskObservation.capture(current));
            assertEquals("mc_v1",context.getString("policy"));assertEquals("observed_transition_unconfirmed",context.getString("reason"));
            assertEquals("waiting",context.getString("last_verdict"));assertEquals(1,context.getInt("attempt"));
            assertEquals(0,context.getJSONArray("blocked_actions").length());
            assertEquals(AppTaskObservation.capture(before).getString("fingerprint"),context.getJSONObject("last_action").getString("fingerprint"));
        }
    }
    @Test public void progressBudgetAndCyclesAreScopedToTheUnverifiedSubgoal()throws Exception {
        String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
        String[] routes={"Alpha","Beta","Gamma","Delta"};
        for(int i=0;i<3;i++){
            meta.clearRecoveryContext();var before=frame(pkg,i*1000,42,routes[i]);var action=action(before,"progress"+i);
            after(meta,clock,before,frame(pkg,i*1000+100,42,routes[i+1]),action,100);
            assertEquals(i<2?Decision.REOBSERVE_PROGRESS:Decision.HANDOFF,
                after(meta,clock,before,frame(pkg,i*1000+450,42,routes[i+1]),action,450));
        }
        assertEquals(2,meta.diagnostic().getInt("progress_rescue_count"));meta.subgoal("g1");
        var before=frame(pkg,4000,42,"Delta");var action=action(before,"new_subgoal");
        after(meta,clock,before,frame(pkg,4100,42,"Epsilon"),action,100);
        assertEquals(Decision.REOBSERVE_PROGRESS,after(meta,clock,before,frame(pkg,4450,42,"Epsilon"),action,450));
        var cycle=new AppTaskMetaController(Arm.META,clock::get);cycle.subgoal("g0");
        before=frame(pkg,5000,42,"Alpha");action=action(before,"cycle1");
        after(cycle,clock,before,frame(pkg,5100,42,"Beta"),action,100);after(cycle,clock,before,frame(pkg,5450,42,"Beta"),action,450);
        cycle.clearRecoveryContext();before=frame(pkg,6000,42,"Beta");action=action(before,"cycle2");
        after(cycle,clock,before,frame(pkg,6100,42,"Alpha"),action,100);
        assertEquals(Decision.HANDOFF,after(cycle,clock,before,frame(pkg,6450,42,"Alpha"),action,450));
    }
    @Test public void silentDelaysAcrossTheLeaseNeverCreateAnEarlyRecoveryOrReplay()throws Exception {
        for(String pkg:PACKAGES)for(long delay:new long[]{0,300,1700,4000,6800,7900,9500}){
            AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
            var before=frame(pkg,0,42,"Alpha");var action=action(before,"delay"+delay);long stop=Math.min(delay,8000);
            for(long at=100;at<stop;at+=400){
                assertEquals(Decision.WAIT,after(meta,clock,before,frame(pkg,at,42,"Alpha"),action,at));
                assertEquals(0,meta.diagnostic().getInt("failure_replan_reservations"));
            }
            if(delay<8000){
                var reached=frame(pkg,Math.max(1,delay),42,"Beta");clock.set(WALL+Math.max(1,delay));
                assertEquals(Decision.CONTINUE,meta.after(AppTaskObservation.capture(before),AppTaskObservation.capture(reached),action,
                    AppTaskVerifier.Verdict.VERIFIED,delay,10000+delay,before,reached));
                assertEquals(0,meta.diagnostic().getInt("recovery_count"));
            }else assertEquals(Decision.REOBSERVE,after(meta,clock,before,frame(pkg,8000,42,"Alpha"),action,8000));
        }
    }
    @Test public void eventStormsAreBoundedAndOneStagnantRouteCanBuyOnlyOneFailureReplan()throws Exception {
        String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
        var before=frame(pkg,0,42,"Alpha");var action=action(before,"stagnant");
        for(int at=100;at<8000;at+=100){meta.observeEvent(AppTaskObservation.capture(frame(pkg,at,42,"Alpha")),10000+at);
            assertEquals(Decision.WAIT,after(meta,clock,before,frame(pkg,at,42,"Alpha"),action,at));}
        var current=frame(pkg,8000,42,"Alpha");assertEquals(Decision.REOBSERVE,after(meta,clock,before,current,action,8000));
        assertTrue(meta.claimPlannerRequest(current));clock.set(WALL+8100);assertFalse(meta.claimPlannerRequest(frame(pkg,8100,42,"Alpha",20,"Changed title")));
        meta.clearRecoveryContext();assertFalse(meta.claimPlannerRequest(frame(pkg,8100,42,"Alpha")));
        before=frame(pkg,9000,42,"Alpha");action=action(before,"second_accepted_same_route");
        assertEquals(Decision.WAIT,after(meta,clock,before,frame(pkg,9100,42,"Alpha"),action,100));
        assertEquals(Decision.HANDOFF,after(meta,clock,before,frame(pkg,17000,42,"Alpha"),action,8000));
        assertEquals(1,meta.diagnostic().getInt("failure_replan_reservations"));assertEquals(1,meta.diagnostic().getInt("recovery_count"));
    }
    @Test public void changedRoutesCanClaimOnceButFailureRequestsRemainTaskBounded()throws Exception {
        String pkg=PACKAGES[1];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
        var before=frame(pkg,0,42,"Alpha");var action=action(before,"one");
        assertEquals(Decision.REOBSERVE,after(meta,clock,before,frame(pkg,8000,42,"Alpha"),action,8000));
        for(int i=0;i<3;i++){clock.set(WALL+8000+i*100);assertTrue(meta.claimPlannerRequest(frame(pkg,8000+i*100,42,"Route"+i)));}
        clock.set(WALL+8400);assertFalse(meta.claimPlannerRequest(frame(pkg,8400,42,"Route3")));
        assertEquals(3,meta.diagnostic().getInt("failure_replan_reservations"));
    }
    @Test public void nativeVerifiedStepAndNewSubgoalReleaseOnlyScopedStagnationBudget()throws Exception {
        String pkg=PACKAGES[2];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
        var before=frame(pkg,0,42,"Alpha");var action=action(before,"failure1");var current=frame(pkg,8000,42,"Alpha");
        after(meta,clock,before,current,action,8000);assertTrue(meta.claimPlannerRequest(current));assertFalse(meta.claimPlannerRequest(current));
        var verifiedBefore=frame(pkg,8100,42,"Alpha");var verifiedAfter=frame(pkg,8500,42,"Beta");
        var verifiedAction=action(verifiedBefore,"native_success");verifiedAction.getJSONObject("expectation").put("kind","screen_changed");
        var verified=AppTaskVerifier.check(AppTaskObservation.capture(verifiedBefore),AppTaskObservation.capture(verifiedAfter),verifiedAction,true,false,WALL+8101);
        assertEquals(AppTaskVerifier.Verdict.VERIFIED,verified);clock.set(WALL+8500);
        meta.after(AppTaskObservation.capture(verifiedBefore),AppTaskObservation.capture(verifiedAfter),verifiedAction,verified,400,18500,verifiedBefore,verifiedAfter);
        before=frame(pkg,9000,42,"Alpha");action=action(before,"failure2");current=frame(pkg,17000,42,"Alpha");
        after(meta,clock,before,current,action,8000);assertTrue(meta.claimPlannerRequest(current));
        meta.subgoal("g1");before=frame(pkg,18000,42,"Alpha");action=action(before,"failure3");current=frame(pkg,26000,42,"Alpha");
        after(meta,clock,before,current,action,8000);assertTrue(meta.claimPlannerRequest(current));assertEquals(3,meta.diagnostic().getInt("failure_replan_reservations"));
    }
    @Test public void projectionOnlyChangesCriticalStateAndMissingNativeProofCannotRescue()throws Exception {
        String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL+450);var before=frame(pkg,0,42,"Alpha");var action=action(before,"none");
        var meta=new AppTaskMetaController(Arm.META,clock::get);JSONObject jsonAfter=AppTaskObservation.capture(frame(pkg,450,42,"Beta"));
        assertEquals(Decision.WAIT,meta.after(AppTaskObservation.capture(before),jsonAfter,action,AppTaskVerifier.Verdict.WAITING,450,10450));
        assertEquals(0,meta.diagnostic().getInt("progress_rescue_count"));
        action.getJSONObject("expectation").put("kind","selection_changed").put("checked",true);
        meta=new AppTaskMetaController(Arm.META,clock::get);
        meta.after(AppTaskObservation.capture(before),jsonAfter,action,AppTaskVerifier.Verdict.WAITING,100,10100,before,frame(pkg,100,42,"Beta"));
        assertEquals(Decision.WAIT,meta.after(AppTaskObservation.capture(before),jsonAfter,action,AppTaskVerifier.Verdict.WAITING,450,10450,before,frame(pkg,450,42,"Beta")));
        assertEquals(0,meta.diagnostic().getInt("progress_rescue_count"));
        for(var verdict:new AppTaskVerifier.Verdict[]{AppTaskVerifier.Verdict.PROTECTED,AppTaskVerifier.Verdict.STALE})
            assertEquals(Decision.HANDOFF,meta.after(AppTaskObservation.capture(before),jsonAfter,action,verdict,450,10450,before,frame(pkg,450,42,"Beta")));
    }
    @Test public void currentAndNoEventsKeepTheirFiveSecondBehaviorAndNoNativeProgressHook()throws Exception {
        for(var arm:new Arm[]{Arm.CURRENT,Arm.NO_EVENTS}){
            String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(arm,clock::get);
            var before=frame(pkg,0,42,"Alpha");var action=action(before,"baseline");
            assertEquals(Decision.WAIT,after(meta,clock,before,frame(pkg,100,42,"Beta"),action,100));
            assertEquals(Decision.WAIT,after(meta,clock,before,frame(pkg,450,42,"Beta"),action,450));
            assertEquals(arm==Arm.CURRENT?Decision.HANDOFF:Decision.REOBSERVE,
                after(meta,clock,before,frame(pkg,5000,42,"Beta"),action,5000));
            assertEquals(0,meta.diagnostic().getInt("progress_rescue_count"));
        }
    }
    @Test public void explicitTargetResetClearsScopedBudgetsButKeepsTaskCostAndRejectedActionCeilings()throws Exception {
        String pkg=PACKAGES[0];AtomicLong clock=new AtomicLong(WALL);var meta=new AppTaskMetaController(Arm.META,clock::get);meta.subgoal("g0");
        var before=frame(pkg,0,42,"Alpha");var originalAction=action(before,"first");var current=frame(pkg,8000,42,"Alpha");
        assertEquals(Decision.REOBSERVE,after(meta,clock,before,current,originalAction,8000));
        assertTrue(meta.claimPlannerRequest(current));assertFalse(meta.claimPlannerRequest(current));
        meta.resetTargetContext();assertTrue(meta.blocked(AppTaskObservation.capture(before),originalAction));
        assertEquals(1,meta.diagnostic().getInt("failure_replan_reservations"));assertEquals(1,meta.diagnostic().getInt("recovery_count"));
        meta.subgoal("g0");before=frame(pkg,9000,42,"Alpha");var newAction=action(before,"new_selection");current=frame(pkg,17000,42,"Alpha");
        assertEquals(Decision.REOBSERVE,after(meta,clock,before,current,newAction,8000));assertTrue(meta.claimPlannerRequest(current));
        assertEquals(2,meta.diagnostic().getInt("failure_replan_reservations"));
    }
}
