package com.click.service.overlay;

import android.app.Application;
import android.os.Looper;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
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
import static com.click.testing.AppTaskFixtures.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
// Exercises the planner-delivered result path; goal-derived completion is covered by AppTaskSpeedExperimentTest.
public class AppTaskPlannerStabilityTest {
    private static final String PKG="org.example.reader";
    private static final class Listener implements AppTaskController.Listener {
        String label="Start",clock="00:00";boolean protectedInput,heading;JSONObject latest;int executions;
        public ScreenSnapshot fresh(){List<AccessibilityNodeSnapshot> nodes=new ArrayList<>();nodes.add(node(PKG,"control",label,"button"));
            nodes.add(new AccessibilityNodeSnapshot("clock",null,true,PKG,clock,null,PKG+":id/clock","android.widget.TextView",null,
                new int[]{20,180,400,220},false,true,false,false,false,null,42,false,null,null,false,null,true,false));
            if(heading)nodes.add(AppTaskGoalCompletionTest.heading(PKG,"result","Destination"));
            if(protectedInput)nodes.add(node(PKG,"secret","","password"));
            return new ScreenSnapshot(PKG,Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()).toString(),1080,2400,nodes,42);}
        public boolean canObserve(){return true;}public void busy(boolean value){}public void present(JSONObject response,ScreenSnapshot baseline){latest=response;}
        public void failure(Throwable error){throw new AssertionError(error);}public boolean execute(ScreenSnapshot b,JSONObject o,JSONObject a,String i,String p){executions++;return true;}
        public boolean inputMatches(String id,String value){return false;}
    }
    private static final class Scenario implements AutoCloseable {
        final Listener listener=new Listener();final AppGuideGateway gateway=mock(AppGuideGateway.class);final AppTaskMemory memory=new AppTaskMemory("Open destination");
        final AppTaskController controller;
        Scenario()throws Exception {controller=new AppTaskController(gateway,memory,null,listener,true,false,true,AppTaskMetaController.Arm.CURRENT,true,AppTaskController.SpeedOptions.PRODUCTION.withGoalDerived(false));controller.targetApp(PKG,"Reader");}
        void initialRequest()throws Exception {controller.refresh();var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callback.capture());
            callback.getValue().success(new JSONObject().put("status","needs_input").put("message","Continue"));}
        void event(){controller.screenChanged(listener.fresh());}
        public void close(){controller.close();}
    }
    private static void advance(long ms){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}

    @Test public void initialExplicitRequestStaysImmediateAndAutomaticReplanWaitsForFreshPair()throws Exception {
        try(Scenario s=new Scenario()){s.initialRequest();s.listener.label="Next";s.event();advance(250);verify(s.gateway,times(1)).send(any(),any());
            advance(249);verify(s.gateway,times(1)).send(any(),any());advance(1);verify(s.gateway,times(2)).send(any(),any());}
    }
    @Test public void frequentClockEventsCannotStarveAStableInteractivePage()throws Exception {
        try(Scenario s=new Scenario()){s.initialRequest();s.listener.label="Next";
            for(int i=0;i<12;i++){s.listener.clock="Clock "+i;s.event();advance(50);}
            verify(s.gateway,times(2)).send(any(),any());assertEquals(0,s.listener.executions);}
    }
    @Test public void continuouslyChangingControlsUseLatestFrameByTheFirstPendingDeadline()throws Exception {
        try(Scenario s=new Scenario()){s.initialRequest();
            for(int i=0;i<25;i++){s.listener.label="Item "+i;s.event();advance(50);}
            var requests=ArgumentCaptor.forClass(JSONObject.class);verify(s.gateway,times(2)).send(requests.capture(),any());
            assertTrue(requests.getAllValues().get(1).getJSONObject("screen").toString().contains("Item 24"));
            assertEquals(0,s.listener.executions);}
    }
    @Test public void deferredResultDiscoveryEventuallySendsAndDoesNotCommitItsDebounceEarly()throws Exception {
        try(Scenario s=new Scenario()){
            s.memory.localPlan();JSONObject observed=observation(PKG,"Open"),step=action(observed,"tap","target_visible");step.getJSONObject("expectation").put("label","Destination");s.memory.record(observed,step,"verified",true);
            s.listener.heading=true;s.event();advance(250);verify(s.gateway,never()).send(any(),any());
            advance(250);verify(s.gateway,times(1)).send(any(),any());
            s.event();advance(250);verify(s.gateway,times(1)).send(any(),any());
        }
    }
    @Test public void existingCompletionProofBypassesThePaidPlannerGate()throws Exception {
        try(Scenario s=new Scenario()){s.listener.heading=true;s.memory.completion(AppTaskCompletionTest.completion(PKG,"Destination"));s.event();
            advance(250);assertEquals("checking_result",s.listener.latest.getString("status"));advance(400);
            assertEquals("verified",s.listener.latest.getJSONObject("result_assessment").getString("status"));verify(s.gateway,never()).send(any(),any());}
    }
    @Test public void protectedScreenTargetSwitchAndCloseCancelPendingPlannerWork()throws Exception {
        for(String stop:List.of("protected","selection","close"))try(Scenario s=new Scenario()){
            s.initialRequest();s.listener.label="Next";s.event();advance(250);
            switch(stop){case "protected"->{s.listener.protectedInput=true;s.event();advance(250);}case "selection"->s.controller.targetApp(PKG,"Reader");case "close"->s.controller.close();}
            advance(1200);verify(s.gateway,times(1)).send(any(),any());assertEquals(0,s.listener.executions);
        }
    }
}
