package com.click.service.overlay;

import android.app.Application;
import android.os.Looper;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
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
import static com.click.testing.AppTaskFixtures.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** A probe-derived passive container must not consume its real page-heading proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskPassiveContainerCompletionTest {
    private static final String PKG="org.example.unregistered",TITLE="Display & touch";
    private static AccessibilityNodeSnapshot container(){return new AccessibilityNodeSnapshot("node_0_0",null,true,PKG,null,TITLE,
        PKG+":id/content_parent","android.widget.FrameLayout",null,new int[]{0,0,1080,1200},
        false,true,false,true,false,null,42,false,null,null,false,null,false,false);}
    private static AccessibilityNodeSnapshot heading(){return new AccessibilityNodeSnapshot("node_0_0_0","node_0_0",true,PKG,null,TITLE,
        PKG+":id/page_title","android.widget.FrameLayout",null,new int[]{0,63,1080,210},
        false,true,false,false,false,null,42,false,null,null,false,null,true,false);}
    private static JSONObject candidate()throws Exception{return new JSONObject().put("checks",new JSONArray().put(new JSONObject()
        .put("package",PKG).put("label",TITLE).put("resource",PKG+":id/page_title").put("role","text")));}
    private static void finishNavigationPlan(AppTaskMemory memory)throws Exception{
        memory.localPlan();JSONObject before=observation(PKG,"열기"),action=action(before,"tap","target_visible");
        action.getJSONObject("expectation").put("label",TITLE);memory.record(before,action,"verified",true);
        assertNull(memory.active());
    }
    private static final class Listener implements AppTaskController.Listener {
        final List<ScreenSnapshot> frames=new ArrayList<>();JSONObject latest;int executions;
        public ScreenSnapshot fresh(){ScreenSnapshot frame=new ScreenSnapshot(PKG,
            Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()).toString(),1080,2400,List.of(container(),heading()),42);
            frames.add(frame);return frame;}
        public boolean canObserve(){return true;}public void busy(boolean value){}
        public void present(JSONObject response,ScreenSnapshot baseline){latest=response;}
        public void failure(Throwable error){throw new AssertionError(error);}
        public boolean execute(ScreenSnapshot baseline,JSONObject observed,JSONObject action,String input,String selected){executions++;return true;}
        public boolean inputMatches(String id,String hash){return false;}
    }

    @Test public void currentArmVerifiesTheDeliveredHeadingCandidateOnlyAfterTwoFreshFrames()throws Exception{
        verifyDeliveredCandidate(AppTaskMetaController.Arm.CURRENT);
    }
    @Test public void metaArmVerifiesTheDeliveredHeadingCandidateOnlyAfterTwoFreshFrames()throws Exception{
        verifyDeliveredCandidate(AppTaskMetaController.Arm.META);
    }
    private void verifyDeliveredCandidate(AppTaskMetaController.Arm arm)throws Exception{
        AppTaskMemory memory=new AppTaskMemory("Display & touch 화면 열어줘");finishNavigationPlan(memory);
        String taskId=memory.id(),plan=memory.state().getJSONArray("subgoals").toString();
        Listener listener=new Listener();AppGuideGateway gateway=mock(AppGuideGateway.class);
        AppTaskController controller=new AppTaskController(gateway,memory,null,listener,true,false,true,arm);
        try{
            controller.refresh();ArgumentCaptor<JSONObject> request=ArgumentCaptor.forClass(JSONObject.class);
            ArgumentCaptor<AppGuideGateway.Callback> callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);
            verify(gateway).send(request.capture(),callback.capture());
            JSONObject sent=request.getValue(),screen=sent.getJSONObject("screen");
            JSONObject response=new JSONObject().put("protocol_version","app-task.v2").put("requires_confirmation",true)
                .put("request_id",sent.getString("request_id")).put("task_id",taskId)
                .put("observation_id",screen.getString("observation_id")).put("fingerprint",screen.getString("fingerprint"))
                .put("status","confirm_result").put("message","결과 확인 후보를 전달했어요.")
                .put("completion",candidate()).put("subgoals",memory.state().getJSONArray("subgoals"));
            AppGuideGateway.validateTask(sent,response);
            callback.getValue().success(response);
            assertEquals("A valid delivered candidate must enter native verification", "checking_result",listener.latest.getString("status"));
            assertFalse(memory.completionSupported());assertFalse(memory.state().has("completion"));
            assertFalse(listener.latest.has("result_assessment"));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(349));
            assertFalse("One frame or insufficient elapsed time must not verify",memory.completionSupported());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101));
            JSONObject assessment=listener.latest.getJSONObject("result_assessment");
            assertEquals("verified",assessment.getString("status"));assertEquals("confirm_result",listener.latest.getString("status"));
            String supportedFrameId=assessment.getString("observation_id");
            assertNotEquals(screen.getString("observation_id"),supportedFrameId);
            ScreenSnapshot supportedFrame=listener.frames.stream().filter(frame->supportedFrameId.equals(frame.observationId)).findFirst().orElseThrow();
            assertTrue(Instant.parse(supportedFrame.capturedAt).toEpochMilli()-screen.getLong("captured_at_ms")>=350);
            assertTrue(memory.completionSupported());assertTrue(memory.state().has("completion"));
            assertEquals(taskId,memory.id());assertEquals(plan,memory.state().getJSONArray("subgoals").toString());
            assertFalse("Verified evidence still requires the user's final answer",memory.finished());
            assertEquals(0,listener.executions);verify(gateway,times(1)).send(any(),any());
        }finally{controller.close();}
    }
}
