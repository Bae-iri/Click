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
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.click.testing.AppTaskFixtures.action;
import static com.click.testing.AppTaskFixtures.observation;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
// Exercises the planner-delivered result path; goal-derived completion is covered by AppTaskSpeedExperimentTest.
public class AppTaskStaleResultCandidateTest {
    private static final String PKG="org.example.unregistered",TITLE="Library overview",GOAL="Open library overview";
    private static JSONObject candidate()throws Exception {return new JSONObject().put("checks",new JSONArray().put(new JSONObject()
        .put("package",PKG).put("label",TITLE).put("resource",PKG+":id/title").put("role","text")));}
    private static AccessibilityNodeSnapshot node(String pkg,String id,String label,String resource,int window,boolean heading,boolean clickable,boolean password,boolean toggle){
        return new AccessibilityNodeSnapshot(id,null,true,pkg,label,null,resource,password?"android.widget.EditText":heading?"android.widget.TextView":"android.widget.Button",
            null,new int[]{10,20,400,120},clickable,true,false,false,toggle,null,window,password,null,null,password,toggle?true:null,heading,false);}
    private static final class Listener implements AppTaskController.Listener {
        String pkg=PKG,footer="Before";Integer window=42;long offset;boolean duplicate,menu,privateInput,notifications;
        JSONObject latest;int executions;
        public ScreenSnapshot fresh(){int nodeWindow=window==null?42:window;List<AccessibilityNodeSnapshot> nodes=new ArrayList<>();
            nodes.add(node(pkg,"title",TITLE,PKG+":id/title",nodeWindow,!menu,menu,false,false));
            nodes.add(node(pkg,"footer",footer,PKG+":id/footer",nodeWindow,false,true,false,false));
            if(duplicate)nodes.add(node(pkg,"title2",TITLE,PKG+":id/title",nodeWindow,true,false,false,false));
            if(privateInput)nodes.add(node(pkg,"secret","",PKG+":id/password",nodeWindow,false,true,true,false));
            if(notifications)nodes.add(node(pkg,"notifications","Notifications",PKG+":id/notifications",nodeWindow,false,true,false,true));
            return new ScreenSnapshot(pkg,Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()+offset).toString(),1080,2400,nodes,window);}
        public boolean canObserve(){return true;}public void busy(boolean value){}
        public void present(JSONObject response,ScreenSnapshot baseline){latest=response;}
        public void failure(Throwable error){throw new AssertionError(error);}
        public boolean execute(ScreenSnapshot baseline,JSONObject observed,JSONObject action,String input,String selected){executions++;return true;}
        public boolean inputMatches(String id,String hash){return false;}
    }
    private static void finishPlan(AppTaskMemory memory)throws Exception {memory.localPlan();JSONObject observed=observation(PKG,"Open"),step=action(observed,"tap","target_visible");
        step.getJSONObject("expectation").put("label",TITLE);memory.record(observed,step,"verified",true);assertNull(memory.active());}
    private static final class Pending implements AutoCloseable {
        final Listener listener=new Listener();final AppGuideGateway gateway=mock(AppGuideGateway.class);final AppTaskMemory memory;
        final AppTaskController controller;JSONObject request;AppGuideGateway.Callback callback;
        Pending(AppTaskMetaController.Arm arm,boolean resultOnly,AppTaskMemory supplied)throws Exception {
            memory=supplied==null?new AppTaskMemory(GOAL):supplied;if(resultOnly)finishPlan(memory);
            controller=new AppTaskController(gateway,memory,null,listener,true,false,true,arm,true,AppTaskController.SpeedOptions.PRODUCTION.withGoalDerived(false));controller.targetApp(PKG,"Library");}
        void send()throws Exception {controller.refresh();var requests=ArgumentCaptor.forClass(JSONObject.class);var callbacks=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);
            verify(gateway).send(requests.capture(),callbacks.capture());request=requests.getValue();callback=callbacks.getValue();}
        JSONObject response(JSONObject completion)throws Exception {JSONObject sent=request.getJSONObject("screen");return new JSONObject().put("protocol_version","app-task.v2")
            .put("requires_confirmation",true).put("request_id",request.getString("request_id")).put("task_id",memory.id())
            .put("observation_id",sent.getString("observation_id")).put("fingerprint",sent.getString("fingerprint"))
            .put("status","confirm_result").put("completion",completion).put("subgoals",memory.state().getJSONArray("subgoals"))
            .put("active_subgoal",memory.active()==null?JSONObject.NULL:memory.active())
            .put("diagnostic",new JSONObject().put("goal_assessment","not_satisfied"));}
        public void close(){controller.close();}
    }

    @Test public void changedSameWindowResultIsReprovedWithoutAnotherRequestInBothArms()throws Exception {
        for(var arm:List.of(AppTaskMetaController.Arm.CURRENT,AppTaskMetaController.Arm.META))try(Pending p=new Pending(arm,true,null)){
            p.send();String id=p.memory.id(),plan=p.memory.state().getJSONArray("subgoals").toString();p.listener.footer="After";
            ScreenSnapshot current=p.listener.fresh();assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal(GOAL,candidate(),AppTaskObservation.capture(current),current));
            assertNotEquals(p.request.getJSONObject("screen").getString("fingerprint"),AppTaskObservation.capture(p.listener.fresh()).getString("fingerprint"));
            JSONObject response=p.response(candidate());response.put("subgoals",new JSONArray().put(new JSONObject().put("id","replacement").put("title","Do something else")));
            try{AppGuideGateway.validateTask(p.request,response);fail("Plan rewrite accepted");}catch(java.io.IOException expected){}
            // Defense in depth: a raw callback can salvage only the read-only candidate, never its plan.
            p.callback.success(response);
            assertEquals("checking_result",p.listener.latest.getString("status"));assertFalse(p.memory.completionSupported());assertEquals(0,p.listener.executions);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(349));assertFalse(p.memory.completionSupported());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101));assertTrue(p.memory.completionSupported());
            assertEquals("verified",p.listener.latest.getJSONObject("result_assessment").getString("status"));
            assertEquals(id,p.memory.id());assertEquals(GOAL,p.memory.goal());assertEquals(plan,p.memory.state().getJSONArray("subgoals").toString());
            assertFalse(p.memory.finished());assertEquals(0,p.listener.executions);verify(p.gateway,times(1)).send(any(),any());
        }
    }
    @Test public void unsafeChangedFramesAndUnboundContextsCannotInstallCandidate()throws Exception {
        for(String failure:List.of("window","package","missing_window","private","stale","future","duplicate","menu","task","goal","selection"))
            try(Pending p=new Pending(AppTaskMetaController.Arm.CURRENT,true,null)){
                p.send();p.listener.footer="After";JSONObject response=p.response(candidate());
                switch(failure){case "window"->p.listener.window=43;case "package"->p.listener.pkg="org.other.app";case "missing_window"->p.listener.window=null;
                    case "private"->p.listener.privateInput=true;case "stale"->p.listener.offset=-16000;case "future"->p.listener.offset=500;
                    case "duplicate"->p.listener.duplicate=true;case "menu"->p.listener.menu=true;case "task"->response.put("task_id","another-task");
                    case "goal"->p.request.put("goal","A different goal");case "selection"->p.controller.targetApp(PKG,"Library");}
                p.callback.success(response);assertNull(failure,p.memory.completion());assertFalse(failure,p.memory.completionSupported());assertEquals(failure,0,p.listener.executions);
            }
    }
    @Test public void staleActionOrNextActionRequestCannotUseReadOnlyException()throws Exception {
        for(boolean resultOnly:List.of(false,true))try(Pending p=new Pending(AppTaskMetaController.Arm.META,resultOnly,null)){
            p.send();p.listener.footer="After";JSONObject response=p.response(candidate());
            if(resultOnly){JSONObject footer=null;JSONArray nodes=p.request.getJSONObject("screen").getJSONArray("nodes");
                for(int i=0;i<nodes.length();i++)if("Before".equals(nodes.getJSONObject(i).optString("label")))footer=nodes.getJSONObject(i);
                assertNotNull(footer);response.put("status","proposed").put("action",new JSONObject().put("id","stale-action").put("package",PKG).put("tool","tap")
                    .put("target_id",footer.getString("id")).put("bounds",footer.getJSONArray("bounds")).put("expectation",new JSONObject().put("kind","screen_changed")));}
            if(resultOnly){try{AppGuideGateway.validateTask(p.request,response);fail("Read-only action accepted");}catch(java.io.IOException expected){}}
            else AppGuideGateway.validateTask(p.request,response);
            p.callback.success(response);
            assertNull(p.memory.completion());assertFalse(p.memory.completionSupported());assertEquals(0,p.listener.executions);
        }
    }
    @Test public void identicalProjectionCannotHideAChangedNativeWindow()throws Exception {
        try(Pending p=new Pending(AppTaskMetaController.Arm.CURRENT,true,null)){
            p.send();p.listener.window=43;
            assertEquals(p.request.getJSONObject("screen").getString("fingerprint"),AppTaskObservation.capture(p.listener.fresh()).getString("fingerprint"));
            p.callback.success(p.response(candidate()));assertNull(p.memory.completion());assertFalse(p.memory.completionSupported());assertEquals(0,p.listener.executions);
        }
    }
    @Test public void targetSelectionGenerationRejectsEvenAnUnchangedFrameResponse()throws Exception {
        try(Pending p=new Pending(AppTaskMetaController.Arm.CURRENT,true,null)){
            p.send();p.controller.targetApp("org.other.app","Other");p.controller.targetApp(PKG,"Library");
            p.callback.success(p.response(candidate()));assertNull(p.memory.completion());assertFalse(p.memory.completionSupported());assertEquals(0,p.listener.executions);
        }
    }
    @Test public void changedTargetContinuesFromFreshObservationWithoutApplyingTheOldResponse()throws Exception {
        try(Pending p=new Pending(AppTaskMetaController.Arm.CURRENT,false,null)){
            p.send();p.controller.targetApp("org.other.app","Other");p.callback.success(p.response(candidate()));
            assertNull(p.memory.completion());advance(1);
            assertEquals("open_app",p.listener.latest.getJSONObject("action").getString("tool"));
            assertEquals("org.other.app",p.listener.latest.getJSONObject("action").getString("package"));
            assertEquals(0,p.listener.executions);verify(p.gateway,times(1)).send(any(),any());
        }
    }
    @Test public void observedCandidateCannotDropAnExistingNativeStateRequirement()throws Exception {
        AppTaskMemory memory=new AppTaskMemory("Open library overview and enable notifications");JSONObject old=candidate();old.getJSONArray("checks").getJSONObject(0).put("label","Future title");
        old.getJSONArray("checks").put(new JSONObject().put("package",PKG).put("label","Notifications").put("resource",PKG+":id/notifications").put("role","toggle").put("checked",true));
        memory.completion(old);String original=AppTaskCompletion.key(old);
        try(Pending p=new Pending(AppTaskMetaController.Arm.CURRENT,true,memory)){
            p.listener.notifications=true;p.send();p.listener.footer="After";JSONObject response=p.response(candidate());AppGuideGateway.validateTask(p.request,response);p.callback.success(response);
            assertEquals(original,AppTaskCompletion.key(memory.completion()));assertEquals("requirements_not_preserved",memory.lastCompletionOfferReason());
            assertFalse(memory.completionSupported());assertEquals(0,p.listener.executions);
        }
    }
    @Test public void matchingFramesFromDifferentNativeWindowsCannotBeCombined()throws Exception {
        for(var arm:List.of(AppTaskMetaController.Arm.CURRENT,AppTaskMetaController.Arm.META)){
            Listener listener=new Listener();AppTaskMemory memory=new AppTaskMemory(GOAL);memory.completion(candidate());AppGuideGateway gateway=mock(AppGuideGateway.class);
            ScreenSnapshot current=listener.fresh();assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal(GOAL,candidate(),AppTaskObservation.capture(current),current));
            AppTaskController controller=new AppTaskController(gateway,memory,null,listener,false,false,true,arm);
            try{controller.refresh();assertEquals("checking_result",listener.latest.getString("status"));
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));listener.window=43;
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));assertFalse(memory.completionSupported());
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));assertFalse(memory.completionSupported());
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertTrue(memory.completionSupported());
                assertEquals("verified",listener.latest.getJSONObject("result_assessment").getString("status"));assertEquals(0,listener.executions);verify(gateway,never()).send(any(),any());
            }finally{controller.close();}
        }
    }
    @Test public void nativeClockJumpCannotReplaceTheMinimumMonotonicProofInterval()throws Exception {
        Listener listener=new Listener();AppTaskMemory memory=new AppTaskMemory(GOAL);memory.completion(candidate());
        AppTaskController controller=new AppTaskController(mock(AppGuideGateway.class),memory,null,listener,false,false,true);
        try{controller.refresh();listener.offset=1000;advance(10);controller.screenChanged(listener.fresh());advance(250);
            assertFalse(memory.completionSupported());advance(400);assertTrue(memory.completionSupported());
        }finally{controller.close();}
    }
    private static void advance(long millis){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));}
}
