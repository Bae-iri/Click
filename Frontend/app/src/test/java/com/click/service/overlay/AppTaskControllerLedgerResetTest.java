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
import static com.click.testing.AppTaskFixtures.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Native subgoal boundaries and explicit user refresh own scoped-ledger resets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskControllerLedgerResetTest {
    private static final String PKG="org.example.ledger";
    private static final class Harness implements AppTaskController.Listener,AutoCloseable {
        final AppTaskMemory memory=new AppTaskMemory("Open destination");
        final AppGuideGateway gateway=mock(AppGuideGateway.class);final AppTaskController controller;
        final List<JSONObject> requests=new ArrayList<>();final List<AppGuideGateway.Callback> callbacks=new ArrayList<>();
        boolean nextSection,goalVisible,executeScroll,scrollChangesRoute;int page,executions;JSONObject latest;
        Harness(boolean remote)throws Exception {
            memory.plan(new JSONArray().put(new JSONObject().put("title","Open section")).put(new JSONObject().put("title","Review destination")));
            doAnswer(call->{requests.add(new JSONObject(((JSONObject)call.getArgument(0)).toString()));callbacks.add(call.getArgument(1));return null;}).when(gateway).send(any(),any());
            controller=new AppTaskController(gateway,memory,null,this,remote,false,true,AppTaskMetaController.Arm.META);controller.targetApp(PKG,"Ledger");
        }
        public ScreenSnapshot fresh(){List<AccessibilityNodeSnapshot> nodes=new ArrayList<>();
            nodes.add(node(PKG,"failed","Old entry","button"));nodes.add(node(PKG,"target",scrollChangesRoute&&page>0?"Open next section":"Open section","button"));
            nodes.add(new AccessibilityNodeSnapshot("list",null,true,PKG,null,null,PKG+":id/list","android.widget.ScrollView",null,
                new int[]{0,300,1080,1800},false,true,false,true,false,null,42,false,null,null,false,null,false,false));
            nodes.add(new AccessibilityNodeSnapshot("list_label","list",true,PKG,"Content",null,null,"android.widget.TextView",null,
                new int[]{20,320,500,390},false,true,false,false,false,null,42,false,null,null,false,null,false,false));
            nodes.add(new AccessibilityNodeSnapshot("page_text","list",true,PKG,"Page "+page,null,null,"android.widget.TextView",null,
                new int[]{20,410,500,480},false,true,false,false,false,null,42,false,null,null,false,null,false,false));
            if(nextSection)nodes.add(AppTaskGoalCompletionTest.heading(PKG,"next","Next section"));
            if(goalVisible)nodes.add(AppTaskGoalCompletionTest.heading(PKG,"goal","Destination"));
            return new ScreenSnapshot(PKG,Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()).toString(),1080,2400,nodes,42);
        }
        public boolean canObserve(){return true;}public void busy(boolean value){}public boolean inputMatches(String id,String value){return false;}
        public void present(JSONObject value,ScreenSnapshot baseline){latest=value;}public void failure(Throwable error){throw new AssertionError(error);}
        public boolean execute(ScreenSnapshot baseline,JSONObject observed,JSONObject action,String input,String selected){executions++;if(executeScroll)page++;else nextSection=true;return true;}
        String seedUnresolvedFailure()throws Exception {
            controller.metaController().subgoal(memory.active());ScreenSnapshot before=fresh(),after=fresh();JSONObject b=AppTaskObservation.capture(before),a=action(b,"tap","screen_changed");
            a.put("target_id","failed").put("bounds",AppTaskObservation.find(b,"failed").getJSONArray("bounds"));
            assertEquals(AppTaskMetaController.Decision.REOBSERVE,controller.metaController().after(b,AppTaskObservation.capture(after),a,AppTaskVerifier.Verdict.WAITING,8000,8000,before,after));
            assertTrue(controller.metaController().claimPlannerRequest(fresh()));return diagnostic().getString("failure_epoch_ref");
        }
        JSONObject diagnostic()throws Exception{return controller.metaController().diagnostic();}
        void reply(boolean scroll)throws Exception {
            JSONObject request=requests.get(0),screen=request.getJSONObject("screen"),target=AppTaskObservation.find(screen,scroll?"list":"target");assertNotNull(target);
            JSONObject expectation=new JSONObject().put("kind",scroll?"screen_changed":"target_visible");if(!scroll)expectation.put("label","Next section");
            JSONObject response=new JSONObject().put("protocol_version","app-task.v2").put("requires_confirmation",true).put("request_id",request.getString("request_id"))
                .put("task_id",memory.id()).put("observation_id",screen.getString("observation_id")).put("fingerprint",screen.getString("fingerprint"))
                .put("status","proposed").put("subgoals",memory.state().getJSONArray("subgoals"))
                .put("action",new JSONObject().put("id","next-step").put("tool",scroll?"scroll_forward":"tap").put("package",PKG)
                    .put("target_id",target.getString("id")).put("bounds",target.getJSONArray("bounds")).put("expectation",expectation).put("advances_subgoal",!scroll));
            AppGuideGateway.validateTask(request,response);callbacks.get(0).success(response);
        }
        public void close(){controller.close();}
    }
    private static void idle(long millis){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));}

    @Test public void verifiedScrollWithPendingSubgoalCannotClearLedgerOrBuySameRouteRequest()throws Exception {
        try(Harness h=new Harness(true)){
            h.controller.refresh();assertEquals(1,h.requests.size());String epoch=h.seedUnresolvedFailure();h.executeScroll=true;h.reply(true);
            h.controller.execute(h.latest.getJSONObject("action").getString("id"),null);idle(700);
            assertEquals(1,h.executions);assertEquals("g0",h.memory.active());assertEquals("verified",h.memory.state().getJSONArray("attempts").getJSONObject(0).getString("outcome"));
            assertEquals(epoch,h.diagnostic().getString("failure_epoch_ref"));assertTrue(h.diagnostic().getBoolean("unresolved_failure_active"));
            assertEquals(1,h.diagnostic().getInt("failure_replan_reservations"));assertEquals(1,h.requests.size());assertEquals("needs_input",h.latest.getString("status"));
        }
    }
    @Test public void actualNativeSubgoalAdvanceReleasesScopeAfterMemoryRecord()throws Exception {
        try(Harness h=new Harness(true)){
            h.controller.refresh();h.seedUnresolvedFailure();h.reply(false);h.controller.execute(h.latest.getJSONObject("action").getString("id"),null);idle(300);
            assertEquals("g1",h.memory.active());assertEquals("native_subgoal_advanced",h.diagnostic().getString("scoped_budget_reset_reason"));
            assertFalse(h.diagnostic().getBoolean("unresolved_failure_active"));assertTrue(h.diagnostic().isNull("failure_epoch_ref"));
            assertEquals(1,h.diagnostic().getInt("failure_replan_reservations"));assertTrue(h.controller.metaController().claimPlannerRequest(h.fresh()));
            idle(400);assertEquals(2,h.requests.size());assertFalse(h.requests.get(1).has("recovery"));assertEquals(1,h.executions);
        }
    }
    @Test public void verifiedScrollContinuationCarriesOriginalFailureAndJustReservedAttempt()throws Exception {
        try(Harness h=new Harness(true)){
            h.controller.refresh();String originalFingerprint=h.requests.get(0).getJSONObject("screen").getString("fingerprint");
            String epoch=h.seedUnresolvedFailure();h.executeScroll=true;h.scrollChangesRoute=true;h.reply(true);
            h.controller.execute(h.latest.getJSONObject("action").getString("id"),null);idle(700);
            assertEquals(2,h.requests.size());JSONObject continuation=h.requests.get(1),recovery=continuation.getJSONObject("recovery");
            assertEquals("mc_v2",recovery.getString("policy"));assertEquals("accepted_action_unconfirmed",recovery.getString("reason"));assertEquals(2,recovery.getInt("attempt"));
            assertEquals("failed",recovery.getJSONObject("last_action").getString("target_id"));assertEquals("tap",recovery.getJSONObject("last_action").getString("tool"));
            assertEquals(originalFingerprint,recovery.getJSONObject("last_action").getString("fingerprint"));
            assertEquals(continuation.getJSONObject("screen").getString("observation_id"),recovery.getString("observation_id"));
            assertNotEquals(originalFingerprint,continuation.getJSONObject("screen").getString("fingerprint"));
            assertEquals("g0",h.memory.active());assertEquals(epoch,h.diagnostic().getString("failure_epoch_ref"));
            assertEquals(2,h.diagnostic().getInt("failure_replan_reservations"));assertEquals(1,h.executions);
        }
    }
    @Test public void automaticRefreshResumeAndVisualResultKeepScopeButExplicitUserRefreshResetsIt()throws Exception {
        try(Harness h=new Harness(false)){
            String epoch=h.seedUnresolvedFailure();h.controller.refresh();assertEquals(epoch,h.diagnostic().getString("failure_epoch_ref"));
            h.controller.resume();assertEquals(epoch,h.diagnostic().getString("failure_epoch_ref"));
            ScreenSnapshot screen=h.fresh();h.controller.visualObservation(screen,AppTaskObservation.capture(screen));assertEquals(epoch,h.diagnostic().getString("failure_epoch_ref"));
            h.controller.userRefresh();assertFalse(h.diagnostic().getBoolean("unresolved_failure_active"));assertTrue(h.diagnostic().isNull("failure_epoch_ref"));
            assertEquals("user_refresh",h.diagnostic().getString("scoped_budget_reset_reason"));assertEquals(1,h.diagnostic().getInt("failure_replan_reservations"));
            assertEquals(1,h.diagnostic().getInt("recovery_count"));assertEquals(1,h.diagnostic().getInt("blocked_count"));assertEquals(0,h.executions);verify(h.gateway,never()).send(any(),any());
        }
    }
    @Test public void localCompletionCanStillVerifyOnAnAlreadyChargedReturnRoute()throws Exception {
        try(Harness h=new Harness(true)){
            h.seedUnresolvedFailure();assertFalse(h.controller.metaController().claimPlannerRequest(h.fresh()));
            h.goalVisible=true;h.memory.completion(AppTaskCompletionTest.completion(PKG,"Destination"));h.controller.refresh();
            assertEquals("checking_result",h.latest.getString("status"));idle(450);assertTrue(h.memory.completionSupported());
            assertEquals("verified",h.latest.getJSONObject("result_assessment").getString("status"));assertEquals(0,h.executions);verify(h.gateway,never()).send(any(),any());
        }
    }
}
