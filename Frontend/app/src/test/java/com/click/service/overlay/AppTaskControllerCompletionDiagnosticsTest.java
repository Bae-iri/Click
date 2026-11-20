package com.click.service.overlay;

import android.app.Application;
import android.os.Looper;
import com.click.data.remote.AppGuideGateway;
import com.click.data.remote.AppTaskDiagnostics;
import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;
import static com.click.testing.AppTaskFixtures.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskControllerCompletionDiagnosticsTest {
    private static final String PKG="org.example.app";
    private static final class Listener implements AppTaskController.Listener {
        ScreenSnapshot current;JSONObject presented;boolean freshReads=true;int executions;
        final long started=android.os.SystemClock.uptimeMillis();
        public ScreenSnapshot fresh(){
            if(!freshReads||current==null)return current;
            long now=System.currentTimeMillis()+android.os.SystemClock.uptimeMillis()-started;
            return new ScreenSnapshot(current.packageName,Instant.ofEpochMilli(now).toString(),current.screenWidth,current.screenHeight,current.nodes,current.activeWindowId).withWindowContext(current.windowContext);
        }
        public boolean canObserve(){return true;}public void busy(boolean value){}
        public void present(JSONObject response,ScreenSnapshot screen){presented=response;}
        public void failure(Throwable error){throw new AssertionError(error);}
        public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selected){executions++;return true;}
        public boolean inputMatches(String target,String value){return false;}
    }
    private List<JSONObject> events(AppGuideGateway gateway){var captured=ArgumentCaptor.forClass(JSONObject.class);verify(gateway,atLeastOnce()).recordCompletion(captured.capture());return captured.getAllValues();}
    private boolean has(List<JSONObject> events,String stage,String reason,String verdict){return events.stream().anyMatch(e->stage.equals(e.optString("stage"))&&reason.equals(e.optString("reason"))&&(verdict==null||verdict.equals(e.optString("verdict"))));}
    private JSONObject candidate()throws Exception{return AppTaskCompletionTest.completion(PKG,"디스플레이");}

    @Test public void changedFrameResponseIsTracedAndCannotInstallItsCandidate()throws Exception {
        Listener listener=new Listener();listener.current=screen(PKG,node(PKG,"start","홈","button"));
        var gateway=mock(AppGuideGateway.class);var memory=new AppTaskMemory("디스플레이 열어줘");var controller=new AppTaskController(gateway,memory,null,listener,true,false,true);
        controller.refresh();var requests=ArgumentCaptor.forClass(JSONObject.class);var callbacks=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);
        verify(gateway).send(requests.capture(),callbacks.capture());listener.current=screen("org.other.app",node("org.other.app","other","다른 화면","button"));
        callbacks.getValue().success(new JSONObject().put("status","confirm_result").put("completion",candidate()));
        List<JSONObject> trace=events(gateway);assertTrue(has(trace,"response","received",null));assertTrue(has(trace,"discarded","changed_frame",null));
        assertNull(memory.completion());assertEquals(0,listener.executions);
        JSONObject discard=trace.stream().filter(e->"changed_frame".equals(e.optString("reason"))).findFirst().orElseThrow();
        assertEquals(AppTaskDiagnostics.ref(requests.getValue().getString("request_id")),discard.getString("request_ref"));controller.close();
    }

    @Test public void acceptedFuturePredictionExplicitlyReportsNotMetInsteadOfVerified()throws Exception {
        Listener listener=new Listener();listener.current=screen(PKG,node(PKG,"home","홈","button"));var gateway=mock(AppGuideGateway.class);
        var memory=new AppTaskMemory("디스플레이 열어줘");var controller=new AppTaskController(gateway,memory,null,listener,true,false,true);controller.refresh();
        var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callback.capture());
        callback.getValue().success(new JSONObject().put("status","needs_input").put("message","test").put("completion",candidate()));
        List<JSONObject> trace=events(gateway);assertTrue(has(trace,"candidate_accepted","initial_prediction",null));assertTrue(has(trace,"goal_check","checked","not_met"));
        assertFalse(trace.stream().anyMatch(e->"verified".equals(e.optString("stage"))));
        JSONObject checked=trace.stream().filter(e->"not_met".equals(e.optString("verdict"))).findFirst().orElseThrow();
        assertEquals(1,checked.getInt("check_count"));assertEquals("fresh",checked.getString("freshness"));assertNotNull(memory.completion());assertFalse(memory.completionSupported());
        assertEquals(0,listener.executions);verify(gateway,times(1)).send(any(),any());controller.close();
    }

    @Test public void unsafeMenuCandidateRejectionIsVisibleInBothArmsWithoutWeakeningGuard()throws Exception {
        for(var arm:AppTaskMetaController.Arm.values()){
            Listener listener=new Listener();listener.current=screen(PKG,node(PKG,"menu","디스플레이","button"));var gateway=mock(AppGuideGateway.class);
            var memory=new AppTaskMemory("디스플레이 열어줘");var controller=new AppTaskController(gateway,memory,null,listener,true,false,true,arm);controller.refresh();
            var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callback.capture());
            callback.getValue().success(new JSONObject().put("status","needs_input").put("message","test").put("completion",candidate()));
            assertTrue(has(events(gateway),"candidate_rejected","unsafe_for_goal",null));assertNull(memory.completion());assertEquals(0,listener.executions);controller.close();
        }
    }

    @Test public void matchedFreshFramesTraceFirstSecondAndVerifiedWithoutAnotherModelCall()throws Exception {
        for(var arm:AppTaskMetaController.Arm.values()){
            Listener listener=new Listener();listener.current=screen(PKG,AppTaskGoalCompletionTest.heading(PKG,"title","디스플레이"));
            var gateway=mock(AppGuideGateway.class);var memory=new AppTaskMemory("디스플레이 열어줘");memory.completion(candidate());
            var controller=new AppTaskController(gateway,memory,null,listener,false,false,true,arm);controller.refresh();
            assertEquals("checking_result",listener.presented.getString("status"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            List<JSONObject> trace=events(gateway);assertTrue(has(trace,"evidence","first_frame","matched"));assertTrue(has(trace,"evidence","second_frame","matched"));
            assertTrue(has(trace,"verified","fresh_native_observations","matched"));assertEquals("verified",listener.presented.getJSONObject("result_assessment").getString("status"));
            JSONObject verified=trace.stream().filter(e->"verified".equals(e.optString("stage"))).findFirst().orElseThrow();assertEquals(2,verified.getInt("frame_count"));
            assertTrue(memory.completionSupported());assertFalse(memory.finished());assertEquals(0,listener.executions);verify(gateway,never()).send(any(),any());
            String text=trace.toString();assertFalse(text.contains("디스플레이"));assertFalse(text.contains(PKG));controller.close();
        }
    }

    @Test public void staleMatchedObservationIsTracedWithoutStartingEvidenceOrClaimingSuccess()throws Exception {
        Listener listener=new Listener();listener.freshReads=false;
        listener.current=new ScreenSnapshot(PKG,Instant.ofEpochMilli(System.currentTimeMillis()-16000).toString(),1080,2400,
            List.of(AppTaskGoalCompletionTest.heading(PKG,"title","디스플레이")),42);
        var gateway=mock(AppGuideGateway.class);var memory=new AppTaskMemory("디스플레이 열어줘");memory.completion(candidate());
        var controller=new AppTaskController(gateway,memory,null,listener,false,false,true);controller.refresh();
        List<JSONObject> trace=events(gateway);JSONObject checked=trace.stream().filter(e->"goal_check".equals(e.optString("stage"))&&"checked".equals(e.optString("reason"))).findFirst().orElseThrow();
        assertEquals("matched",checked.getString("verdict"));assertEquals("stale",checked.getString("freshness"));
        assertFalse(trace.stream().anyMatch(e->"evidence".equals(e.optString("stage"))||"verified".equals(e.optString("stage"))));
        assertFalse(memory.completionSupported());assertEquals(0,listener.executions);controller.close();
    }

    @Test public void failingDiagnosticSinkCannotChangeMatchedFrameVerification()throws Exception {
        Listener listener=new Listener();listener.current=screen(PKG,AppTaskGoalCompletionTest.heading(PKG,"title","디스플레이"));
        var gateway=mock(AppGuideGateway.class);doThrow(new IllegalStateException("PRIVATE diagnostic error")).when(gateway).recordCompletion(any());
        var memory=new AppTaskMemory("디스플레이 열어줘");memory.completion(candidate());var controller=new AppTaskController(gateway,memory,null,listener,false,false,true);
        controller.refresh();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals("verified",listener.presented.getJSONObject("result_assessment").getString("status"));assertTrue(memory.completionSupported());controller.close();
    }
}
