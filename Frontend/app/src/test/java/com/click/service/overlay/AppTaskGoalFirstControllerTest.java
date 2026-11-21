package com.click.service.overlay;

import android.app.Application;
import android.os.Looper;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Goal assessment uses the existing planning call and never grants action/progress authority. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskGoalFirstControllerTest {
    private static final String PKG="org.example.viewer",TITLE="Library overview",GOAL="Open library overview";
    private static AccessibilityNodeSnapshot node(String id,String text,boolean heading,boolean clickable,int window){
        return new AccessibilityNodeSnapshot(id,null,true,PKG,text,null,PKG+":id/"+id,
            heading?"android.widget.TextView":"android.widget.Button",null,new int[]{10,20,500,120},
            clickable,true,false,false,false,null,window,false,null,null,false,null,heading,false);
    }
    private static JSONObject candidate(String label)throws Exception {
        return new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",PKG)
            .put("label",label).put("resource",PKG+":id/title").put("role","text")));
    }
    private static void idle(long ms){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    private static final class Harness implements AppTaskController.Listener,AutoCloseable {
        final AppGuideGateway gateway=mock(AppGuideGateway.class);final AppTaskMemory memory=new AppTaskMemory(GOAL);
        final AppTaskController controller;final List<JSONObject> requests=new ArrayList<>();final List<AppGuideGateway.Callback> callbacks=new ArrayList<>();
        String title=TITLE,footer="Before";int window=42,executions;Boolean sync;boolean privateInput,presentedBusy,accept=true;long clockOffset;JSONObject latest;Throwable failure;
        Harness(AppTaskMetaController.Arm arm,boolean supported)throws Exception {this(arm,supported,true);}
        Harness(AppTaskMetaController.Arm arm,boolean supported,boolean withPlan)throws Exception {
            if(withPlan)memory.plan(new JSONArray().put(new JSONObject().put("title","Open section"))
                .put(new JSONObject().put("title","Review destination")));
            doAnswer(call->{requests.add(new JSONObject(((JSONObject)call.getArgument(0)).toString()));callbacks.add(call.getArgument(1));return null;}).when(gateway).send(any(),any());
            controller=new AppTaskController(gateway,memory,null,this,true,false,true,arm,supported);controller.targetApp(PKG,"Viewer");
        }
        public ScreenSnapshot fresh(){
            List<AccessibilityNodeSnapshot> nodes=new ArrayList<>();nodes.add(node("title",title,true,false,window));nodes.add(node("footer",footer,false,true,window));
            if(sync!=null)nodes.add(new AccessibilityNodeSnapshot("sync",null,true,PKG,"Auto sync",null,PKG+":id/sync",
                "android.widget.Switch",null,new int[]{10,150,500,240},true,true,false,false,true,null,window,false,null,null,false,sync));
            if(privateInput)nodes.add(new AccessibilityNodeSnapshot("private",null,true,PKG,"",null,PKG+":id/password",
                "android.widget.EditText",null,new int[]{10,150,500,250},true,true,false,false,false,null,window,true));
            return new ScreenSnapshot(PKG,Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()+clockOffset).toString(),1080,2400,nodes,window);
        }
        public boolean canObserve(){return true;}public void busy(boolean value){presentedBusy=value;}
        public void present(JSONObject response,ScreenSnapshot sent){latest=response;}public void failure(Throwable error){failure=error;}
        public boolean execute(ScreenSnapshot before,JSONObject observed,JSONObject action,String input,String selected){executions++;return accept;}
        public boolean inputMatches(String id,String hash){return false;}
        void send(){controller.refresh();assertEquals(1,requests.size());}
        JSONObject response(String assessment,JSONObject completion)throws Exception {
            JSONObject request=requests.get(0),screen=request.getJSONObject("screen");
            JSONObject response=new JSONObject().put("protocol_version","app-task.v2").put("requires_confirmation",true)
                .put("request_id",request.getString("request_id")).put("task_id",memory.id())
                .put("observation_id",screen.getString("observation_id")).put("fingerprint",screen.getString("fingerprint"))
                .put("subgoals",new JSONArray(memory.state().getJSONArray("subgoals").toString()))
                .put("active_subgoal",memory.active()==null?JSONObject.NULL:memory.active())
                .put("status","satisfied".equals(assessment)?"confirm_result":"needs_input")
                .put("message","Check the current public destination")
                .put("diagnostic",new JSONObject().put("planning_mode","goal_first").put("goal_assessment",assessment));
            if(completion!=null)response.put("completion",completion);return response;
        }
        JSONObject action(JSONObject response)throws Exception {
            JSONObject n=AppTaskObservation.find(requests.get(0).getJSONObject("screen"),"footer");
            return response.put("status","proposed").put("action",new JSONObject().put("id","next-step").put("tool","tap")
                .put("package",PKG).put("target_id",n.getString("id")).put("bounds",n.getJSONArray("bounds"))
                .put("advances_subgoal",false).put("expectation",new JSONObject().put("kind","target_visible").put("label","Another section")));
        }
        void reply(JSONObject response,boolean validate)throws Exception {if(validate)AppGuideGateway.validateTask(requests.get(0),response);callbacks.get(0).success(response);}
        public void close(){controller.close();}
    }

    @Test public void rejectedResultReplansWithoutConfirmingOnAnUnrelatedRedraw()throws Exception{
        try(Harness h=new Harness(AppTaskMetaController.Arm.META,true)){
            h.controller.enableOutcomes(true);h.send();
            assertTrue(h.requests.get(0).getBoolean("prepare_outcome"));
            var goal=AppTaskOutcomesTest.goal(AppTaskOutcomesTest.clause("page",TITLE),AppTaskOutcomesTest.clause("off","Auto sync"));
            h.sync=false;h.reply(h.response("uncertain",null).put("outcome_goal",goal),false);
            idle(900);assertTrue(h.memory.completionSupported());assertEquals("confirm_result",h.latest.getString("status"));
            int prior=h.requests.size();h.controller.rejectResult();h.footer="Different button";idle(1000);
            assertFalse(h.memory.completionSupported());assertFalse(h.memory.finished());assertEquals(prior+1,h.requests.size());
            assertTrue(h.requests.get(prior).getBoolean("rejected_outcome"));
            assertEquals(0,h.executions);assertNotEquals("confirm_result",h.latest.getString("status"));assertNull(h.failure);
        }
    }
    @Test public void confirmTapRechecksSameSwitchAndReopensTheDisputedResult()throws Exception{
        try(Harness h=new Harness(AppTaskMetaController.Arm.META,true)){
            h.controller.enableOutcomes(true);h.sync=false;h.send();
            h.reply(h.response("uncertain",null).put("outcome_goal",AppTaskOutcomesTest.goal(AppTaskOutcomesTest.clause("off","Auto sync"))),false);
            idle(900);assertTrue(h.memory.completionSupported());
            h.sync=true;assertFalse(h.controller.mayConfirmResult());assertFalse(h.memory.completionSupported());
            idle(800);assertFalse(h.memory.finished());assertEquals(0,h.executions);assertNull(h.failure);
        }
    }
    @Test public void switchRecoveryUsesTheCurrentControlAndStillAcceptsALateEffectWithoutRetapping()throws Exception{
        for(boolean accepted:List.of(true,false))try(Harness h=new Harness(AppTaskMetaController.Arm.META,true)){
            h.controller.enableOutcomes(true);h.sync=true;h.accept=accepted;
            h.memory.installOutcome(AppTaskOutcomesTest.goal(AppTaskOutcomesTest.clause("page",TITLE),AppTaskOutcomesTest.clause("off","Auto sync")));
            h.controller.refresh();
            assertEquals("sync",h.latest.getJSONObject("action").getString("target_id"));assertTrue(h.requests.isEmpty());
            h.controller.execute(h.latest.getJSONObject("action").getString("id"),null);
            if(accepted){
                idle(5);h.footer="Redrawn";h.controller.screenChanged(h.fresh());
                idle(AppTaskTransitionDetector.STABLE_MS+5);
            }else idle(1);
            assertEquals(1,h.memory.actions());
            assertEquals(accepted?"value_contradicted":"execution_rejected",h.memory.stored().getString("last_verification_reason"));
            assertFalse(h.memory.completionSupported());assertEquals(1,h.executions);
            h.sync=false;h.controller.screenChanged(h.fresh());idle(850);
            assertTrue(h.memory.completionSupported());assertFalse(h.memory.finished());assertEquals(1,h.executions);assertNull(h.failure);
        }
    }
    @Test public void acceptingRecordedProofNeedsAReadableCurrentWindow()throws Exception{
        try(Harness h=new Harness(AppTaskMetaController.Arm.META,true)){
            h.controller.enableOutcomes(true);h.sync=false;
            h.memory.installOutcome(AppTaskOutcomesTest.goal(AppTaskOutcomesTest.clause("off","Auto sync")));
            h.controller.refresh();idle(400);assertTrue(h.memory.completionSupported());
            h.privateInput=true;assertFalse(h.controller.mayConfirmResult());idle(1);
            assertEquals("needs_input",h.latest.getString("status"));assertFalse(h.memory.finished());
            h.privateInput=false;h.clockOffset=-16000;assertFalse(h.controller.mayConfirmResult());
            h.clockOffset=0;assertTrue(h.controller.mayConfirmResult());assertEquals(0,h.executions);
        }
    }
    @Test public void pendingPlanCanVerifyCurrentGoalInTheSamePlanningCallWithoutAnotherAction()throws Exception {
        for(var arm:List.of(AppTaskMetaController.Arm.CURRENT,AppTaskMetaController.Arm.META))try(Harness h=new Harness(arm,true)){
            String plan=h.memory.state().getJSONArray("subgoals").toString(),active=h.memory.active();h.send();
            assertEquals("goal_first",h.requests.get(0).getString("planning_mode"));
            h.reply(h.response("satisfied",candidate(TITLE)),true);assertEquals("checking_result",h.latest.getString("status"));
            assertFalse(h.memory.completionSupported());idle(349);assertFalse(h.memory.completionSupported());idle(101);
            assertTrue(h.memory.completionSupported());assertEquals("verified",h.latest.getJSONObject("result_assessment").getString("status"));
            assertEquals(plan,h.memory.state().getJSONArray("subgoals").toString());assertEquals(active,h.memory.active());
            assertFalse(h.memory.finished());assertEquals(0,h.executions);verify(h.gateway,times(1)).send(any(),any());assertNull(h.failure);
        }
    }
    @Test public void supportedGoalAwaitsUserDecisionEvenWhenLaterFramesNoLongerMatch()throws Exception {
        for(var arm:List.of(AppTaskMetaController.Arm.CURRENT,AppTaskMetaController.Arm.META))try(Harness h=new Harness(arm,true)){
            h.send();String plan=h.memory.state().getJSONArray("subgoals").toString();h.reply(h.response("satisfied",candidate(TITLE)),true);idle(450);
            assertTrue(h.memory.completionSupported());h.title="Other section";h.footer="Changed";
            h.controller.refresh();assertEquals("confirm_result",h.latest.getString("status"));assertTrue(h.latest.isNull("action"));
            assertFalse(h.latest.has("result_assessment"));h.controller.screenChanged(h.fresh());idle(1000);
            assertEquals("confirm_result",h.latest.getString("status"));assertEquals(plan,h.memory.state().getJSONArray("subgoals").toString());
            assertEquals(0,h.executions);assertFalse(h.memory.finished());verify(h.gateway,times(1)).send(any(),any());assertNull(h.failure);
        }
    }
    @Test public void alreadyReachedGoalNeedsNoInventedPlanBeforeNativeVerification()throws Exception {
        try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true,false)){
            h.send();assertEquals("goal_first",h.requests.get(0).getString("planning_mode"));
            h.reply(h.response("satisfied",candidate(TITLE)),true);idle(450);
            assertTrue(h.memory.completionSupported());assertEquals(0,h.memory.state().getJSONArray("subgoals").length());
            assertEquals(0,h.executions);assertFalse(h.memory.finished());verify(h.gateway,times(1)).send(any(),any());assertNull(h.failure);
        }
    }
    @Test public void intermediateScreenUsesOneGoalFirstCallToProposeItsNextAction()throws Exception {
        try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.title="Intermediate section";h.send();h.reply(h.action(h.response("not_satisfied",candidate(TITLE))),true);
            assertEquals("proposed",h.latest.getString("status"));assertEquals("tap",h.latest.getJSONObject("action").getString("tool"));assertEquals("footer",h.latest.getJSONObject("action").getString("target_id"));
            assertFalse(h.memory.completionSupported());assertNotNull(h.memory.active());assertEquals(0,h.executions);
            idle(1000);verify(h.gateway,times(1)).send(any(),any());assertNull(h.failure);
        }
    }
    @Test public void uncertainGoalDoesNotInventCompletionOrTriggerAnotherPaidCall()throws Exception {
        try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.send();String plan=h.memory.state().getJSONArray("subgoals").toString();h.reply(h.response("uncertain",null),true);idle(1000);
            assertEquals("needs_input",h.latest.getString("status"));assertNull(h.memory.completion());assertFalse(h.memory.completionSupported());
            assertEquals(plan,h.memory.state().getJSONArray("subgoals").toString());assertEquals(0,h.executions);verify(h.gateway,times(1)).send(any(),any());
        }
    }
    @Test public void uncertainRawCallbackCannotInstallNewCompletionEvidence()throws Exception {
        try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.send();String plan=h.memory.state().getJSONArray("subgoals").toString();
            h.reply(h.response("uncertain",candidate(TITLE)),false);idle(1000);
            assertNull(h.memory.completion());assertFalse(h.memory.completionSupported());assertEquals("needs_input",h.latest.getString("status"));
            assertEquals(plan,h.memory.state().getJSONArray("subgoals").toString());assertEquals(0,h.executions);verify(h.gateway,times(1)).send(any(),any());assertNull(h.failure);
        }
    }
    @Test public void selectedTargetChangeCannotVerifyThePreviouslyVisibleAppWhileBusyOrIdle()throws Exception {
        for(boolean pending:List.of(false,true))try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.memory.completion(candidate(TITLE));h.title="Intermediate section";if(pending)h.send();
            h.controller.targetApp("org.example.other","Other");h.title=TITLE;h.controller.refresh();h.controller.screenChanged(h.fresh());idle(1000);
            assertFalse(h.memory.completionSupported());assertFalse(h.memory.finished());assertEquals(0,h.executions);
            assertNull(h.latest.optJSONObject("result_assessment"));verify(h.gateway,times(pending?1:0)).send(any(),any());assertNull(h.failure);
        }
    }
    @Test public void finalGoalResponseCannotRewritePendingProgressEvenIfTheCallbackIsMalformed()throws Exception {
        try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.send();String plan=h.memory.state().getJSONArray("subgoals").toString();JSONObject response=h.response("satisfied",candidate(TITLE));
            response.put("subgoals",new JSONArray().put(new JSONObject().put("id","g0").put("title","Skip everything").put("status","verified")));
            h.reply(response,false);idle(450);assertTrue(h.memory.completionSupported());assertEquals(plan,h.memory.state().getJSONArray("subgoals").toString());
            assertEquals(0,h.executions);assertFalse(h.memory.finished());assertNull(h.failure);
        }
    }
    @Test public void satisfiedOrUncertainAssessmentCannotSmuggleAnAction()throws Exception {
        for(String assessment:List.of("satisfied","uncertain"))try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.send();String plan=h.memory.state().getJSONArray("subgoals").toString();h.reply(h.action(h.response(assessment,candidate(TITLE))),false);
            assertNotNull(h.failure);assertNull(h.memory.completion());assertFalse(h.memory.completionSupported());assertEquals(plan,h.memory.state().getJSONArray("subgoals").toString());assertEquals(0,h.executions);
        }
    }
    @Test public void currentGoalEvidenceSurvivesUnrelatedSameWindowRedrawWithoutASecondRequest()throws Exception {
        try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.send();h.footer="After";h.reply(h.response("satisfied",candidate(TITLE)),true);idle(450);
            assertTrue(h.memory.completionSupported());assertEquals(0,h.executions);verify(h.gateway,times(1)).send(any(),any());assertNull(h.failure);
        }
    }
    @Test public void changedContextPrivateAndStaleFramesCannotSalvageAGoalAssessment()throws Exception {
        for(String failure:List.of("window","private","stale","selection"))try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.send();JSONObject response=h.response("satisfied",candidate(TITLE));h.footer="After";
            switch(failure){case "window"->h.window=43;case "private"->h.privateInput=true;case "stale"->h.clockOffset=-16000;
                case "selection"->h.controller.targetApp(PKG,"Viewer");}
            h.reply(response,true);assertNull(failure,h.memory.completion());assertFalse(failure,h.memory.completionSupported());assertEquals(failure,0,h.executions);
        }
    }
    @Test public void satisfiedPredictionWithoutCurrentEvidenceCannotCompleteOrReplanAutomatically()throws Exception {
        try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.title="Intermediate section";h.send();h.reply(h.response("satisfied",candidate(TITLE)),false);idle(1000);
            assertFalse(h.memory.completionSupported());assertEquals("needs_input",h.latest.getString("status"));
            assertEquals(0,h.executions);verify(h.gateway,times(1)).send(any(),any());
        }
    }
    @Test public void knownGoalCanVerifyWhileTheModelCallbackIsStillPending()throws Exception {
        try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.memory.completion(candidate(TITLE));h.title="Intermediate section";h.send();assertTrue(h.presentedBusy);
            h.title=TITLE;h.controller.screenChanged(h.fresh());idle(1);assertEquals("checking_result",h.latest.getString("status"));
            idle(AppTaskTransitionDetector.STABLE_MS-2);assertFalse(h.memory.completionSupported());idle(1);
            assertTrue(h.memory.completionSupported());assertEquals("verified",h.latest.getJSONObject("result_assessment").getString("status"));
            assertFalse(h.presentedBusy);assertEquals(0,h.executions);verify(h.gateway,times(1)).send(any(),any());assertNull(h.failure);
        }
    }
    @Test public void lateModelActionOrFailureCannotRestartACompletedLocalGoal()throws Exception {
        for(boolean providerFailure:List.of(false,true))try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,true)){
            h.memory.completion(candidate(TITLE));h.title="Intermediate section";h.send();String plan=h.memory.state().getJSONArray("subgoals").toString();
            h.title=TITLE;h.controller.screenChanged(h.fresh());idle(450);assertTrue(h.memory.completionSupported());
            if(providerFailure)h.callbacks.get(0).failure(new IllegalStateException("Late provider failure"));
            else h.reply(h.action(h.response("not_satisfied",null)),true);
            idle(1000);assertTrue(h.memory.completionSupported());assertEquals(plan,h.memory.state().getJSONArray("subgoals").toString());
            assertTrue(h.latest.isNull("action"));assertEquals(0,h.executions);verify(h.gateway,times(1)).send(any(),any());assertNull(h.failure);
        }
    }
    @Test public void olderServerContractKeepsLegacyRequestsWithoutTheNewField()throws Exception {
        try(Harness h=new Harness(AppTaskMetaController.Arm.CURRENT,false)){
            h.send();assertFalse(h.requests.get(0).has("planning_mode"));h.reply(h.response("not_satisfied",null),false);
            assertEquals("needs_input",h.latest.getString("status"));assertNull(h.failure);verify(h.gateway,times(1)).send(any(),any());
        }
    }
}
