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
import java.time.Duration;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;
import static com.click.testing.AppTaskFixtures.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskLearningControllerTest {
    private static final String PKG="org.example.reader",CONSENT=UUID.randomUUID().toString();
    private static final class Listener implements AppTaskController.Listener {
        ScreenSnapshot current;JSONObject result;int executions;boolean textMatches;
        public ScreenSnapshot fresh(){return current;}public boolean canObserve(){return true;}public void busy(boolean v){}
        public void present(JSONObject result,ScreenSnapshot screen){this.result=result;}public void failure(Throwable error){throw new AssertionError(error);}
        public boolean execute(ScreenSnapshot b,JSONObject o,JSONObject a,String input,String selected){executions++;return true;}
        public boolean inputMatches(String target,String hash){return textMatches;}
        public String sessionId(){return "owned-host-session";}
        public String scope(String pkg){return pkg+"|12|34|app-task.v2";}
    }
    private final AppGuideGateway gateway=mock(AppGuideGateway.class);
    private final AppTaskStore store=mock(AppTaskStore.class);
    private final Listener listener=new Listener();
    private AppTaskController controller(String goal,String kind)throws Exception {
        listener.current=screen(PKG,node(PKG,"node_0_1",kind.equals("search")?"":"검색",kind));
        AppTaskController c=new AppTaskController(gateway,new AppTaskMemory(goal),store,listener,false,true);c.refresh();return c;
    }
    private JSONObject reply(JSONObject request,boolean enabled)throws Exception {
        JSONObject screen=request.getJSONObject("screen");
        return new JSONObject().put("protocol_version","app-task-memory.v1").put("request_id",request.getString("request_id"))
            .put("observation_id",screen.getString("observation_id")).put("fingerprint",screen.getString("fingerprint"))
            .put("status",enabled?"needs_data":"disabled").put("collection_enabled",enabled).put("consent_id",enabled?CONSENT:JSONObject.NULL)
            .put("consent_recorded_at_ms",System.currentTimeMillis()-1000)
            .put("requires_confirmation",true).put("action",JSONObject.NULL).put("evidence",new JSONArray());
    }
    private JSONObject consent(boolean enabled)throws Exception {
        var request=ArgumentCaptor.forClass(JSONObject.class);var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);
        verify(gateway).recall(request.capture(),callback.capture());JSONObject response=reply(request.getValue(),enabled);callback.getValue().success(response);return response;
    }
    @Test public void nativeSuccessQueuesOnlyHashedEvidenceAndPersistedSequence()throws Exception {
        AppTaskController c=controller("검색 열기","button");consent(true);c.execute((String)null);
        listener.current=screen(PKG,node(PKG,"node_0_1","검색어 입력","button"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        var event=ArgumentCaptor.forClass(JSONObject.class);verify(store).saveExperience(eq(c.memory()),eq("owned-host-session"),eq(CONSENT),event.capture());
        JSONObject body=event.getValue();assertEquals("verified",body.getString("outcome"));assertTrue(body.getBoolean("action_observed"));assertTrue(body.getBoolean("starts_task"));assertFalse(body.toString().contains("검색"));assertFalse(body.toString().contains("bounds"));assertEquals(1,new AppTaskMemory(c.memory().stored()).experienceSequence());c.close();
    }
    @Test public void searchValueAndItsExactHashNeverEnterTrainingEvidence()throws Exception {
        AppTaskController c=controller("검색어 입력","search");consent(true);c.execute("PRIVATE_QUERY");listener.textMatches=true;
        listener.current=screen(PKG,node(PKG,"node_0_1","PRIVATE_QUERY","search"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        var event=ArgumentCaptor.forClass(JSONObject.class);verify(store).saveExperience(any(),anyString(),anyString(),event.capture());String raw=event.getValue().toString();
        assertFalse(raw.contains("PRIVATE_QUERY"));assertFalse(raw.contains(AppTaskObservation.hash("PRIVATE_QUERY")));assertEquals("input_present",event.getValue().getJSONObject("action").getString("expectation"));c.close();
    }
    @Test public void noConsentMeansNoExperienceCollection()throws Exception {
        AppTaskController c=controller("검색 열기","button");consent(false);c.execute((String)null);listener.current=screen(PKG,node(PKG,"node_0_1","목록","button"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        verify(store,never()).saveExperience(any(),anyString(),anyString(),any());assertEquals(0,c.memory().experienceSequence());c.close();
    }
    @Test public void twoRejectedTasksBlockRepetitionButOneUnconfirmedObservationDoesNot()throws Exception {
        for(boolean rejected:new boolean[]{false,true}){
            reset(gateway,store);AppTaskController c=controller("검색 열기","button");
            var request=ArgumentCaptor.forClass(JSONObject.class);var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).recall(request.capture(),callback.capture());
            JSONObject r=reply(request.getValue(),true).put("evidence",new JSONArray().put(new JSONObject().put("tool","tap").put("target_id","node_0_1").put("rejections",rejected?2:0).put("unconfirmed",rejected?0:1).put("supporting_tasks_after_adverse",0)));
            callback.getValue().success(r);int before=listener.executions;c.execute((String)null);assertEquals(rejected?before:before+1,listener.executions);c.close();
        }
    }
    @Test public void rememberedProposalGetsNewApprovalIdentityAndOptOutClearsIt()throws Exception {
        AppTaskController c=controller("검색 열기","button");
        var request=ArgumentCaptor.forClass(JSONObject.class);var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).recall(request.capture(),callback.capture());
        JSONObject response=reply(request.getValue(),true).put("status","remembered").put("action",action(request.getValue().getJSONObject("screen"),"tap","screen_changed"));callback.getValue().success(response);
        c.refresh();String old=listener.result.getJSONObject("action").getString("id");c.refresh();assertNotEquals(old,listener.result.getJSONObject("action").getString("id"));c.execute(old,null);assertEquals(0,listener.executions);
        when(store.learningDisabled()).thenReturn(true);c.execute((String)null);assertEquals(0,listener.executions);verify(store,atLeastOnce()).retainExperienceConsent(isNull());c.close();
    }
    @Test public void consentEpochChangesResetOnlyExperienceIdentity()throws Exception {
        AppTaskMemory m=new AppTaskMemory("검색 열기");m.localPlan();m.experienceConsent(CONSENT);m.experienceSequence(3);String episode=m.experienceId(),task=m.id();
        m.experienceConsent(CONSENT);assertEquals(3,m.experienceSequence());m.experienceConsent(UUID.randomUUID().toString());assertEquals(0,m.experienceSequence());assertNotEquals(episode,m.experienceId());assertEquals(task,m.id());assertEquals("g0",m.active());
    }
    @Test public void explicitResultWaitsForServerAndWorksWithTrainingDisabled()throws Exception {
        AppTaskController c=controller("검색 열기","button");consent(false);int[] done={0};c.confirmResultOnServer(()->done[0]++,e->{throw new AssertionError(e);});assertFalse(c.memory().finished());
        var request=ArgumentCaptor.forClass(JSONObject.class);var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).finishTask(request.capture(),callback.capture());assertEquals("user_confirmed",request.getValue().getString("outcome"));
        callback.getValue().success(new JSONObject());assertTrue(c.memory().finished());assertEquals(1,done[0]);verify(store,never()).saveExperience(any(),anyString(),anyString(),any());c.close();
    }
    @Test public void failedResultSubmissionNeverClaimsCompletion()throws Exception {
        AppTaskController c=controller("검색 열기","button");int[] failed={0};c.confirmResultOnServer(()->fail("Completed without receipt"),e->failed[0]++);
        var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).finishTask(any(),callback.capture());callback.getValue().failure(new java.io.IOException("offline"));assertFalse(c.memory().finished());assertEquals(1,failed[0]);c.close();
    }
    @Test public void anOlderV2ServerNeverReceivesNewLearningFields()throws Exception {
        listener.current=screen(PKG,node(PKG,"node_0_1","검색","button"));AppTaskController c=new AppTaskController(gateway,new AppTaskMemory("노래를 찾아서 비교해 줘"),store,listener,true,false);c.refresh();
        var request=ArgumentCaptor.forClass(JSONObject.class);verify(gateway).send(request.capture(),any());assertFalse(request.getValue().has("environment"));assertFalse(request.getValue().has("path_prefix"));verify(gateway,never()).recall(any(),any());c.close();
    }
    @Test public void explicitAppOpeningCanStartAConsentedExperienceOutsideTheTargetApp()throws Exception {
        listener.current=screen("com.clueclick.app",node("com.clueclick.app","node_0_1","홈","button"));
        AppTaskController c=new AppTaskController(gateway,new AppTaskMemory("테스트앱 열기"),store,listener,false,true);c.targetApp(PKG,"테스트앱");c.refresh();consent(true);
        c.execute((String)null);listener.current=screen(PKG,node(PKG,"node_0_1","홈","button"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        var event=ArgumentCaptor.forClass(JSONObject.class);verify(store).saveExperience(any(),anyString(),eq(CONSENT),event.capture());assertEquals("open_app",event.getValue().getJSONObject("action").getString("tool"));assertTrue(event.getValue().getBoolean("starts_task"));c.close();
    }
    @Test public void aGrantArrivingAfterAnActionDoesNotRetroactivelyCollectThatAction()throws Exception {
        AppTaskController c=controller("검색 열기","button");c.execute((String)null);
        var request=ArgumentCaptor.forClass(JSONObject.class);var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).recall(request.capture(),callback.capture());
        JSONObject granted=reply(request.getValue(),true).put("consent_recorded_at_ms",System.currentTimeMillis()+1);callback.getValue().success(granted);
        listener.current=screen(PKG,node(PKG,"node_0_1","목록","button"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));verify(store,never()).saveExperience(any(),anyString(),anyString(),any());c.close();
    }
}
