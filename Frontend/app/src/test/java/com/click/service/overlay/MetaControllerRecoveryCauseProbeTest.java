package com.click.service.overlay;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.click.testing.AppTaskFixtures.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Opt-in characterization of MC-v0, not acceptance criteria for a future policy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class MetaControllerRecoveryCauseProbeTest {
    private static final String PKG="org.example.recoveryprobe";
    private static final String STAGE="공개 화면의 다음 단계를 진행";

    private static final class Harness implements AppTaskController.Listener, AutoCloseable {
        final AppGuideGateway gateway=mock(AppGuideGateway.class);
        final List<JSONObject> requests=new ArrayList<>();
        final List<AppGuideGateway.Callback> callbacks=new ArrayList<>();
        final AppTaskController controller;
        final long nativeDelayMs;
        final Handler main=new Handler(Looper.getMainLooper());
        JSONObject latest;
        int acceptedExecutions,nativeDispatches;
        boolean destination;
        long actionAcceptedAt=-1,nativeDispatchedAt=-1;

        Harness(AppTaskMetaController.Arm arm,long nativeDelayMs)throws Exception {
            this.nativeDelayMs=nativeDelayMs;
            doAnswer(invocation->{
                requests.add(new JSONObject(((JSONObject)invocation.getArgument(0)).toString()));
                callbacks.add(invocation.getArgument(1));
                return null;
            }).when(gateway).send(any(),any());
            controller=new AppTaskController(gateway,new AppTaskMemory("내용을 보고 안내해줘"),null,this,true,false,false,arm);
            controller.targetApp(PKG,"Fixture");
        }
        public ScreenSnapshot fresh(){return new ScreenSnapshot(PKG,
            Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()).toString(),1080,2400,
            List.of(node(PKG,"target",destination?"Beta route":"Alpha route","button")),42);}
        public boolean canObserve(){return true;}
        public void busy(boolean value){}
        public void present(JSONObject response,ScreenSnapshot baseline){latest=response;}
        public void failure(Throwable error){throw new AssertionError(error);}
        public boolean inputMatches(String target,String expectedHash){return false;}
        public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selected){
            acceptedExecutions++;actionAcceptedAt=SystemClock.elapsedRealtime();
            if(nativeDelayMs>=0)main.postDelayed(()->{
                nativeDispatches++;nativeDispatchedAt=SystemClock.elapsedRealtime();destination=true;
                // Explicit controlled event: this probe does not simulate a real AX event source.
                controller.screenChanged(fresh());
            },nativeDelayMs);
            return true;
        }
        void startAndApprove()throws Exception {
            controller.refresh();assertEquals(1,requests.size());reply(0);
            assertEquals("proposed",latest.getString("status"));
            controller.execute(latest.getJSONObject("action").getString("id"),null);
            assertEquals(1,acceptedExecutions);
        }
        void reply(int index)throws Exception {
            JSONObject request=requests.get(index),screen=request.getJSONObject("screen");
            JSONObject response=new JSONObject().put("protocol_version","app-task.v2").put("requires_confirmation",true)
                .put("request_id",request.getString("request_id"))
                .put("task_id",request.getJSONObject("state").getString("task_id"))
                .put("observation_id",screen.getString("observation_id")).put("fingerprint",screen.getString("fingerprint"))
                .put("status","proposed").put("message","공개 탐색 후보")
                .put("subgoals",new JSONArray().put(new JSONObject().put("id","g0").put("title",STAGE).put("status","pending")))
                .put("action",action(screen,"tap","screen_changed").put("advances_subgoal",false));
            AppGuideGateway.validateTask(request,response);
            callbacks.get(index).success(response);
        }
        JSONObject facts()throws Exception{return new JSONObject()
            .put("planner_requests",requests.size()).put("accepted_executions",acceptedExecutions)
            .put("native_dispatches",nativeDispatches).put("recovery_count",controller.metaController().diagnostic().getInt("recovery_count"))
            .put("blocked_key_count",controller.metaController().diagnostic().getInt("blocked_count"))
            .put("latest_status",latest==null?"none":latest.optString("status"));}
        public void close(){main.removeCallbacksAndMessages(null);controller.close();}
    }
    private static void idle(long ms){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    private static void enabled(){Assume.assumeTrue("Opt in with click.metaRecoveryCauseProbe",Boolean.getBoolean("click.metaRecoveryCauseProbe"));}
    private static void record(JSONObject value)throws Exception {
        value.put("schema_version","click.meta-recovery-cause-probe.v1")
            .put("controlled_in_process",true).put("actual_native",false).put("model_calls",0)
            .put("shared_completion_gate_enabled",false).put("performance_cohort",false);
        System.out.println("META_RECOVERY_CAUSE "+value);
        String path=System.getProperty("click.metaRecoveryCauseOutput");
        if(path!=null&&!path.isEmpty()){
            Path file=java.nio.file.Paths.get(path);if(file.getParent()!=null)Files.createDirectories(file.getParent());
            Files.write(file,(value+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
    }

    @Test public void acceptedNoEffectReplanRejectsTheOtherwiseValidSameTargetRetry()throws Exception {
        enabled();JSONObject report=new JSONObject().put("scenario","accepted_no_effect_same_target_retry");
        try(Harness a=new Harness(AppTaskMetaController.Arm.CURRENT,-1)){
            a.startAndApprove();idle(5350);
            assertEquals(1,a.requests.size());assertEquals("needs_input",a.latest.getString("status"));
            assertEquals(0,a.controller.metaController().diagnostic().getInt("recovery_count"));
            report.put("A",a.facts());
        }
        try(Harness b=new Harness(AppTaskMetaController.Arm.META,-1)){
            b.startAndApprove();idle(5350);assertEquals(2,b.requests.size());
            JSONObject sent=b.requests.get(1),sameAction=action(sent.getJSONObject("screen"),"tap","screen_changed");
            assertTrue("The ordinary policy still permits this exact fresh read-only target",
                AppTaskPolicy.allowed(sent.getJSONObject("screen"),sent.getJSONObject("screen"),sameAction,PKG));
            b.reply(1);
            assertEquals("needs_input",b.latest.getString("status"));assertTrue(b.latest.isNull("action"));
            assertEquals("Replanning must not be mistaken for a corrective native action",1,b.acceptedExecutions);
            assertEquals(0,b.nativeDispatches);assertEquals(1,b.controller.metaController().diagnostic().getInt("recovery_count"));
            assertTrue(b.controller.metaController().blocked(sent.getJSONObject("screen"),sameAction));
            report.put("B",b.facts()).put("fresh_same_target_policy_allowed",true)
                .put("meta_same_target_blocked",true).put("corrective_execute_attempts",0);
        }
        record(report);
    }

    @Test public void replanWhileOriginalDispatchIsPendingCreatesAStaleResponseAndAnotherRequest()throws Exception {
        enabled();JSONObject report=new JSONObject().put("scenario","pending_5500ms_dispatch_old_frame_replan")
            .put("native_dispatch_delay_ms",5500).put("checkpoint_ms",5350);
        try(Harness a=new Harness(AppTaskMetaController.Arm.CURRENT,5500)){
            a.startAndApprove();idle(5350);
            assertEquals(1,a.requests.size());assertEquals(0,a.nativeDispatches);
            report.put("A_before_dispatch",a.facts());idle(450);
            assertEquals(1,a.nativeDispatches);assertEquals(2,a.requests.size());
            report.put("A_after_dispatch",a.facts());
        }
        try(Harness b=new Harness(AppTaskMetaController.Arm.META,5500)){
            b.startAndApprove();idle(5350);
            assertEquals(2,b.requests.size());assertEquals(0,b.nativeDispatches);
            String oldFingerprint=b.requests.get(1).getJSONObject("screen").getString("fingerprint");
            report.put("B_before_dispatch",b.facts());idle(450);
            assertEquals(1,b.nativeDispatches);assertEquals(2,b.requests.size());
            assertNotEquals(oldFingerprint,com.click.data.snapshot.AppTaskObservation.capture(b.fresh()).getString("fingerprint"));
            b.reply(1);idle(0);
            assertEquals("Old-frame response must be discarded, then a new screen request follows",3,b.requests.size());
            assertEquals(1,b.acceptedExecutions);assertEquals(1,b.nativeDispatches);
            assertNotEquals(oldFingerprint,b.requests.get(2).getJSONObject("screen").getString("fingerprint"));
            report.put("B_after_stale_response",b.facts()).put("old_frame_response_discarded",true)
                .put("B_additional_model_opportunities_relative_to_A",1);
        }
        record(report);
    }
}
