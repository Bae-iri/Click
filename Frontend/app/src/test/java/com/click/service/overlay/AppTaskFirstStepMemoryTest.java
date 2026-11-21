package com.click.service.overlay;
import android.app.Application;
import android.os.Looper;
import com.click.data.local.AppTaskStore;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowSystem;
import java.time.Duration;
import java.time.Instant;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;
import static com.click.testing.AppTaskFixtures.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskFirstStepMemoryTest {
    static final String PKG="org.example.reader";
    static final class Listener implements AppTaskController.Listener {
        ScreenSnapshot current=screen(PKG,node(PKG,"node_0_1","검색","button"));JSONObject latest;int executions;
        public ScreenSnapshot fresh(){return new ScreenSnapshot(current.packageName,Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()).toString(),current.screenWidth,current.screenHeight,current.nodes,current.activeWindowId);}
        public boolean canObserve(){return true;}public void busy(boolean v){}
        public void present(JSONObject r,ScreenSnapshot s){latest=r;}public void failure(Throwable e){throw new AssertionError(e);}
        public boolean execute(ScreenSnapshot b,JSONObject o,JSONObject a,String input,String selected){executions++;return true;}
        public boolean inputMatches(String target,String hash){return false;}public String sessionId(){return "first-step-test";}
    }
    final Listener listener=new Listener();final AppGuideGateway gateway=mock(AppGuideGateway.class);final AppTaskStore store=mock(AppTaskStore.class);
    AppTaskController start(boolean enabled)throws Exception {
        AppTaskController c=new AppTaskController(gateway,new AppTaskMemory("검색 화면 열어줘"),store,listener,true,true,true,AppTaskMetaController.Arm.CURRENT,true,enabled);
        c.targetApp(PKG,"Reader");c.refresh();return c;
    }
    void recall(boolean rejected)throws Exception {
        var req=ArgumentCaptor.forClass(JSONObject.class);var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);
        verify(gateway).recall(req.capture(),callback.capture());JSONObject s=req.getValue().getJSONObject("screen");
        JSONObject r=new JSONObject().put("protocol_version","app-task-memory.v1").put("request_id",req.getValue().getString("request_id"))
            .put("observation_id",s.getString("observation_id")).put("fingerprint",s.getString("fingerprint"))
            .put("status","remembered").put("collection_enabled",true).put("consent_id","12345678-1234-1234-1234-123456789abc")
            .put("consent_recorded_at_ms",Math.max(1,ShadowSystem.currentTimeMillis()-1)).put("requires_confirmation",true)
            .put("action",action(s,"tap","screen_changed").put("advances_subgoal",false)).put("evidence",new JSONArray());
        if(rejected)r.getJSONArray("evidence").put(new JSONObject().put("tool","tap").put("target_id","node_0_1").put("rejections",2).put("supporting_tasks_after_adverse",0));
        callback.getValue().success(r);
    }
    void idle(long ms){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    @Test public void eligibleRecallSkipsInitialPlannerButRequiresApprovalAndCannotFinishTask()throws Exception {
        var c=start(true);recall(false);idle(1000);assertEquals("proposed",listener.latest.getString("status"));
        assertEquals("retained_experience",listener.latest.getJSONObject("diagnostic").getString("reason"));verify(gateway,never()).send(any(),any());
        assertEquals(0,listener.executions);assertNull(c.memory().completion());assertFalse(c.memory().finished());
        c.execute(listener.latest.getJSONObject("action").getString("id"),null);assertEquals(1,listener.executions);assertFalse(c.memory().finished());c.close();
    }
    @Test public void productionDefaultStillUsesPlannerBeforeCompletionBinding()throws Exception {
        var c=start(false);recall(false);idle(1500);verify(gateway).send(any(),any());assertEquals(0,listener.executions);c.close();
    }
    @Test public void missingRecallFallsBackAfterBoundedWait()throws Exception {
        var c=start(true);idle(1000);verify(gateway,never()).send(any(),any());idle(2500);verify(gateway).send(any(),any());assertFalse(c.memory().finished());c.close();
    }
    @Test public void rejectedMemoryFallsThroughToPlanner()throws Exception {
        var c=start(true);recall(true);idle(1500);verify(gateway).send(any(),any());assertEquals(0,listener.executions);c.close();
    }
    @Test public void changedPrivateFrameCannotExecuteTheRememberedProposal()throws Exception {
        var c=start(true);recall(false);idle(500);String id=listener.latest.getJSONObject("action").getString("id");
        listener.current=screen(PKG,node(PKG,"private","PRIVATE_PASSWORD","password"));c.execute(id,null);assertEquals(0,listener.executions);assertFalse(c.memory().finished());c.close();
    }
}
