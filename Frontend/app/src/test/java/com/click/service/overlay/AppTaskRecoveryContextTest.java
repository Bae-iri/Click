package com.click.service.overlay;

import android.app.Application;
import android.os.Looper;
import com.click.data.remote.AppGuideGateway;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import static com.click.testing.AppTaskFixtures.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Optional recovery metadata uses the actual request path and grants no new action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskRecoveryContextTest {
    private static final String PKG="org.example.recoverycontext",STAGE="공개 화면의 다음 단계를 진행";
    private static final Set<String> REF_FIELDS=Set.of("tool","package","target_id","fingerprint");

    private static final class Harness implements AppTaskController.Listener,AutoCloseable {
        final AppGuideGateway gateway=mock(AppGuideGateway.class);
        final List<JSONObject> requests=new ArrayList<>();
        final List<AppGuideGateway.Callback> callbacks=new ArrayList<>();
        final AppTaskController controller;
        JSONObject latest;int executions;boolean destination;
        Harness(AppTaskMetaController.Arm arm)throws Exception {
            doAnswer(invocation->{requests.add(new JSONObject(((JSONObject)invocation.getArgument(0)).toString()));
                callbacks.add(invocation.getArgument(1));return null;}).when(gateway).send(any(),any());
            controller=new AppTaskController(gateway,new AppTaskMemory("내용을 보고 안내해줘"),null,this,true,false,false,arm);
            controller.targetApp(PKG,"Fixture");
        }
        public ScreenSnapshot fresh(){return new ScreenSnapshot(PKG,Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()).toString(),1080,2400,
            destination?List.of(node(PKG,"destination","Beta route","button")):
                List.of(node(PKG,"target","Alpha route","button"),node(PKG,"alternative","Gamma route","button")),42);}
        public boolean canObserve(){return true;}public void busy(boolean value){}
        public void present(JSONObject response,ScreenSnapshot baseline){latest=response;}
        public void failure(Throwable error){throw new AssertionError(error);}
        public boolean inputMatches(String target,String hash){return false;}
        public boolean execute(ScreenSnapshot baseline,JSONObject observed,JSONObject action,String input,String selected){
            executions++;if(executions==2)destination=true;return true;
        }
        void startUnconfirmed()throws Exception {controller.refresh();assertEquals(1,requests.size());reply(0,"target");
            controller.execute(latest.getJSONObject("action").getString("id"),null);idle(5350);}
        void reply(int index,String target)throws Exception {
            JSONObject request=requests.get(index),screen=request.getJSONObject("screen"),node=AppTaskObservation.find(screen,target);
            assertNotNull(node);
            JSONObject response=new JSONObject().put("protocol_version","app-task.v2").put("requires_confirmation",true)
                .put("request_id",request.getString("request_id")).put("task_id",request.getJSONObject("state").getString("task_id"))
                .put("observation_id",screen.getString("observation_id")).put("fingerprint",screen.getString("fingerprint"))
                .put("status","proposed").put("message","공개 탐색 후보")
                .put("subgoals",new JSONArray().put(new JSONObject().put("id","g0").put("title",STAGE).put("status","pending")))
                .put("action",new JSONObject().put("id","fixture-"+index).put("tool","tap").put("package",PKG)
                    .put("target_id",target).put("bounds",node.getJSONArray("bounds"))
                    .put("expectation",new JSONObject().put("kind","screen_changed")).put("advances_subgoal",false));
            AppGuideGateway.validateTask(request,response);callbacks.get(index).success(response);
        }
        public void close(){controller.close();}
    }
    private static void idle(long ms){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    private static Set<String> keys(JSONObject value){Set<String> result=new HashSet<>();for(Iterator<String> i=value.keys();i.hasNext();)result.add(i.next());return result;}
    private static void exportControlledRequest(JSONObject request)throws Exception {
        String target=System.getProperty("click.recoveryContextRequestOutput");if(target==null||target.isEmpty())return;
        java.nio.file.Path file=Paths.get(target);if(file.getParent()!=null)Files.createDirectories(file.getParent());
        // The file is the strict DTO; its synthetic/in-process provenance belongs in the accompanying review.
        Files.write(file,(request.toString(2)+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Test public void currentAndInitialRequestsStayUnchangedWhileMetaReplanCarriesClosedContext()throws Exception {
        try(Harness current=new Harness(AppTaskMetaController.Arm.CURRENT)){
            current.startUnconfirmed();assertEquals(1,current.requests.size());assertFalse(current.requests.get(0).has("recovery"));
            assertNull(current.controller.metaController().recoveryContext(AppTaskObservation.capture(current.fresh())));
        }
        try(Harness meta=new Harness(AppTaskMetaController.Arm.META)){
            meta.startUnconfirmed();assertEquals(2,meta.requests.size());assertFalse(meta.requests.get(0).has("recovery"));
            JSONObject request=meta.requests.get(1),screen=request.getJSONObject("screen"),recovery=request.getJSONObject("recovery");
            assertEquals(Set.of("policy","reason","last_verdict","waited_ms","attempt","observation_id","last_action","blocked_actions"),keys(recovery));
            assertEquals("mc_v0",recovery.getString("policy"));assertEquals("accepted_action_unconfirmed",recovery.getString("reason"));
            assertEquals("waiting",recovery.getString("last_verdict"));assertEquals(1,recovery.getInt("attempt"));
            assertTrue(recovery.getLong("waited_ms")>=5000&&recovery.getLong("waited_ms")<=8000);
            assertEquals(screen.getString("observation_id"),recovery.getString("observation_id"));
            JSONObject last=recovery.getJSONObject("last_action");assertEquals(REF_FIELDS,keys(last));
            assertEquals("tap",last.getString("tool"));assertEquals(PKG,last.getString("package"));assertEquals("target",last.getString("target_id"));
            assertEquals(meta.requests.get(0).getJSONObject("screen").getString("fingerprint"),last.getString("fingerprint"));
            assertEquals(screen.getString("fingerprint"),last.getString("fingerprint"));
            assertEquals(1,recovery.getJSONArray("blocked_actions").length());
            assertEquals(last.toString(),recovery.getJSONArray("blocked_actions").getJSONObject(0).toString());
            assertFalse(recovery.toString().contains("Alpha route"));assertFalse(recovery.toString().contains("bounds"));
            assertFalse(recovery.toString().contains("내용을 보고"));assertFalse(request.getJSONObject("state").has("recovery"));
            exportControlledRequest(request);
        }
    }

    @Test public void aNewVerifiedActionClearsActiveContextWithoutChangingRejectedHistory()throws Exception {
        try(Harness meta=new Harness(AppTaskMetaController.Arm.META)){
            meta.startUnconfirmed();JSONObject failedScreen=meta.requests.get(1).getJSONObject("screen");
            JSONObject failedAction=action(failedScreen,"tap","screen_changed");
            assertTrue(meta.controller.metaController().blocked(failedScreen,failedAction));
            meta.reply(1,"alternative");assertEquals("proposed",meta.latest.getString("status"));
            meta.controller.execute(meta.latest.getJSONObject("action").getString("id"),null);idle(500);
            assertEquals(2,meta.executions);assertEquals(3,meta.requests.size());
            assertFalse(meta.requests.get(2).has("recovery"));
            assertTrue(meta.controller.metaController().blocked(failedScreen,failedAction));
            assertEquals(1,meta.controller.metaController().diagnostic().getInt("recovery_count"));
        }
    }

    @Test public void contextRetainsTheFailedFrameAndSelectedOpenAppDestinationWithoutInventingCurrentState()throws Exception {
        AppTaskMetaController meta=new AppTaskMetaController(AppTaskMetaController.Arm.META);
        JSONObject before=observation(PKG,"Alpha route"),after=observation(PKG,"Alpha route");
        String destination="org.example.selected";
        JSONObject open=new JSONObject().put("tool","open_app").put("package",destination).put("target_id",JSONObject.NULL)
            .put("input","PRIVATE_INPUT").put("bounds",new JSONArray().put(1).put(2).put(3).put(4));
        assertEquals(AppTaskMetaController.Decision.REOBSERVE,meta.after(before,after,open,AppTaskVerifier.Verdict.WAITING,5050,10000));
        JSONObject changed=observation(PKG,"Beta route"),context=meta.recoveryContext(changed),ref=context.getJSONObject("last_action");
        assertEquals(destination,ref.getString("package"));assertTrue(ref.isNull("target_id"));assertEquals(REF_FIELDS,keys(ref));
        assertEquals(before.getString("fingerprint"),ref.getString("fingerprint"));
        assertNotEquals(changed.getString("fingerprint"),ref.getString("fingerprint"));
        assertEquals(changed.getString("observation_id"),context.getString("observation_id"));
        assertFalse(context.toString().contains("PRIVATE_INPUT"));
        context.getJSONObject("last_action").put("package","org.example.mutated");
        assertEquals(destination,meta.recoveryContext(changed).getJSONObject("last_action").getString("package"));
        meta.after(before,after,open,AppTaskVerifier.Verdict.VERIFIED,5500,10500);
        assertNull(meta.recoveryContext(changed));assertTrue(meta.blocked(before,open));
    }
}
