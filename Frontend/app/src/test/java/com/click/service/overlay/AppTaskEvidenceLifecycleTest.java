package com.click.service.overlay;

import android.app.Application;
import android.os.Looper;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystem;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.click.testing.AppTaskFixtures.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskEvidenceLifecycleTest {
    private static final String PKG="org.example.unseen";
    private static JSONObject result(String label)throws Exception{return AppTaskCompletionTest.completion(PKG,label);}
    private static AccessibilityNodeSnapshot title(String id,String label){return AppTaskGoalCompletionTest.heading(PKG,id,label);}
    private static ScreenSnapshot page(AccessibilityNodeSnapshot... nodes){return new ScreenSnapshot(PKG,Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()).toString(),1080,2400,List.of(nodes),42);}
    private static JSONObject projected(ScreenSnapshot s,JSONObject candidate)throws Exception{return AppTaskObservation.capture(s,AppTaskCompletion.labels(candidate));}
    private static void finishPlan(AppTaskMemory memory)throws Exception{
        memory.localPlan();JSONObject screen=observation(PKG,"열기"),a=action(screen,"tap","target_visible");
        a.getJSONObject("expectation").put("label","목적 화면");memory.record(screen,a,"verified",true);assertNull(memory.active());
    }
    private static final class Listener implements AppTaskController.Listener{
        ScreenSnapshot current;JSONObject latest;int executions;
        public ScreenSnapshot fresh(){return new ScreenSnapshot(current.packageName,Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()).toString(),1080,2400,current.nodes,42);}
        public boolean canObserve(){return true;}public void busy(boolean value){}
        public void present(JSONObject response,ScreenSnapshot baseline){latest=response;}
        public void failure(Throwable error){throw new AssertionError(error);}
        public boolean execute(ScreenSnapshot baseline,JSONObject observed,JSONObject action,String input,String selectedPackage){executions++;return true;}
        public boolean inputMatches(String target,String hash){return false;}
    }

    @Test public void wrongFutureLabelCanBeRefinedByObservedDestinationAfterAllStepsFinish()throws Exception{
        AppTaskMemory memory=new AppTaskMemory("목적 화면 열어줘");memory.completion(result("추측한 목적 제목"));finishPlan(memory);
        String task=memory.id(),plan=memory.state().getJSONArray("subgoals").toString();
        AppTaskMemory restored=new AppTaskMemory(memory.stored());assertFalse(restored.state().has("completion"));
        Listener listener=new Listener();listener.current=page(title("actual","실제 목적 제목"));AppGuideGateway gateway=mock(AppGuideGateway.class);
        AppTaskController controller=new AppTaskController(gateway,restored,null,listener,true,false,true);
        controller.refresh();ArgumentCaptor<JSONObject> request=ArgumentCaptor.forClass(JSONObject.class);ArgumentCaptor<AppGuideGateway.Callback> callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);
        verify(gateway).send(request.capture(),callback.capture());assertFalse(request.getValue().getJSONObject("state").has("completion"));
        callback.getValue().success(new JSONObject().put("status","confirm_result").put("completion",result("실제 목적 제목")).put("subgoals",restored.state().getJSONArray("subgoals")));
        assertEquals("checking_result",listener.latest.getString("status"));assertFalse(restored.state().has("completion"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450));
        assertEquals("verified",listener.latest.getJSONObject("result_assessment").getString("status"));assertTrue(restored.state().has("completion"));
        assertEquals(task,restored.id());assertEquals(plan,restored.state().getJSONArray("subgoals").toString());assertFalse(restored.finished());
        assertEquals(0,listener.executions);assertEquals(2,restored.evidenceVersion());
        verify(gateway,times(1)).send(any(),any());controller.close();
    }

    @Test public void unchangingDestinationDoesNotPollForEvidenceAfterFailureOrEmptyResult()throws Exception{
        AppTaskMemory memory=new AppTaskMemory("목적 화면 열어줘");finishPlan(memory);Listener listener=new Listener();listener.current=page(title("result","목적 화면"));
        AppGuideGateway gateway=mock(AppGuideGateway.class);AppTaskController controller=new AppTaskController(gateway,memory,null,listener,true,false,true);
        controller.refresh();ArgumentCaptor<AppGuideGateway.Callback> callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callback.capture());
        callback.getValue().success(new JSONObject().put("status","confirm_result").put("subgoals",memory.state().getJSONArray("subgoals")));
        for(int i=0;i<5;i++){controller.refresh();controller.screenChanged(listener.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));}
        verify(gateway,times(1)).send(any(),any());assertFalse(memory.completionSupported());assertEquals(0,listener.executions);
        listener.current=page(title("different","다른 목적 화면"));controller.screenChanged(listener.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550));
        verify(gateway,times(2)).send(any(),any());controller.close();
    }

    @Test public void unseenReplacementOrWeakerConjunctionCannotRepairAFalsePrediction()throws Exception{
        AppTaskMemory memory=new AppTaskMemory("화면 열고 알림 꺼줘");JSONObject old=result("추측한 제목");
        JSONObject toggle=new JSONObject().put("package",PKG).put("label","알림").put("resource",PKG+":id/notifications").put("role","toggle").put("checked",false);
        old.getJSONArray("checks").put(toggle);memory.completion(old);
        ScreenSnapshot current=page(title("actual","목적 제목"),node(PKG,"notifications","알림","toggle",false));
        JSONObject weaker=result("목적 제목");assertFalse(memory.offerCompletion(weaker,projected(current,weaker),current));
        JSONObject unseen=result("다른 추측");unseen.getJSONArray("checks").put(toggle);assertFalse(memory.offerCompletion(unseen,projected(current,unseen),current));
        JSONObject reversed=result("목적 제목");reversed.getJSONArray("checks").put(new JSONObject(toggle.toString()).put("checked",true));assertFalse(memory.offerCompletion(reversed,projected(current,reversed),current));
        JSONObject duplicate=result("목적 제목");duplicate.getJSONArray("checks").put(new JSONObject().put("package",PKG).put("resource",PKG+":id/actual").put("role","text"));
        assertEquals(AppTaskCompletion.Verdict.UNKNOWN,AppTaskCompletion.checkForGoal(memory.goal(),duplicate,projected(current,duplicate),current));
        assertFalse(memory.offerCompletion(duplicate,projected(current,duplicate),current));assertEquals(1,memory.evidenceVersion());
        JSONObject observed=result("목적 제목");observed.getJSONArray("checks").put(toggle);assertTrue(memory.offerCompletion(observed,projected(current,observed),current));
        assertEquals(2,memory.evidenceVersion());assertEquals(AppTaskCompletion.key(observed),AppTaskCompletion.key(memory.completion()));
    }

    @Test public void changedCandidateCannotReuseAnOlderCandidatesFirstFrame()throws Exception{
        AppTaskMemory memory=new AppTaskMemory("목적 화면 열어줘");memory.completion(result("첫 제목"));Listener listener=new Listener();listener.current=page(title("one","첫 제목"),title("two","둘째 제목"));
        AppTaskController controller=new AppTaskController(mock(AppGuideGateway.class),memory,null,listener,false,false,true);controller.refresh();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));JSONObject next=result("둘째 제목");ScreenSnapshot frame=listener.fresh();
        assertTrue(memory.offerCompletion(next,projected(frame,next),frame));controller.screenChanged(frame);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));assertFalse(memory.completionSupported());assertEquals("checking_result",listener.latest.getString("status"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450));assertTrue(memory.completionSupported());
        assertEquals(2,listener.latest.getJSONObject("result_assessment").getInt("evidence_version"));controller.close();
    }

    @Test public void supportedAndUserConfirmedEvidenceCannotBeRewrittenOrMutatedThroughGetter()throws Exception{
        AppTaskMemory memory=new AppTaskMemory("목적 화면 열어줘");JSONObject original=result("목적 제목");memory.completion(original);assertTrue(memory.supportCompletion(memory.evidenceVersion()));
        memory.completion().getJSONArray("checks").getJSONObject(0).put("label","외부 변경");assertEquals(AppTaskCompletion.key(original),AppTaskCompletion.key(memory.completion()));
        JSONObject next=result("다른 제목");ScreenSnapshot current=page(title("different","다른 제목"));assertFalse(memory.offerCompletion(next,projected(current,next),current));
        memory.discardCompletion();assertNotNull(memory.completion());memory.confirmResult();
        AppTaskMemory restored=new AppTaskMemory(memory.stored());assertTrue(restored.finished());assertTrue(restored.completionSupported());assertFalse(restored.offerCompletion(next,projected(current,next),current));
        assertTrue(restored.evidenceState().getJSONArray("history").length()<=8);
    }

    @Test public void restoredEvidenceCannotCrossAnEditedGoalOrTaskIdentity()throws Exception{
        AppTaskMemory memory=new AppTaskMemory("목적 화면 열어줘");memory.completion(result("목적 제목"));
        for(String field:List.of("goal","task_id")){
            JSONObject altered=memory.stored().put(field,"다른 값");
            try{new AppTaskMemory(altered);fail("Evidence crossed a changed "+field);}catch(JSONException expected){}
        }
        JSONObject changed=memory.stored();changed.getJSONObject("completion").getJSONArray("checks").getJSONObject(0).put("label","다른 제목");
        try{new AppTaskMemory(changed);fail("Candidate changed without its version");}catch(JSONException expected){}
    }

    @Test public void unsafeLegacyUnselectedDirectionCanBeRepairedWithoutDroppingOtherNativeRequirements()throws Exception{
        AppTaskMemory memory=new AppTaskMemory("기록 탭 선택하고 녹화 상태 확인해줘");
        JSONObject wrong=new JSONObject().put("checks",new JSONArray()
            .put(new JSONObject().put("package",PKG).put("label","기록").put("resource",PKG+":id/history").put("role","tab").put("selected",false))
            .put(new JSONObject().put("package",PKG).put("label","녹화").put("resource",PKG+":id/recording").put("role","toggle").put("checked",true)));
        memory.completion(wrong);AppTaskMemory restored=new AppTaskMemory(memory.stored());restored.discardCompletion();
        ScreenSnapshot current=page(node(PKG,"history","기록","tab",true),node(PKG,"recording","녹화","toggle",true));
        JSONObject correct=new JSONObject(wrong.toString());correct.getJSONArray("checks").getJSONObject(0).put("selected",true);
        JSONObject missing=new JSONObject(correct.toString());missing.getJSONArray("checks").remove(1);
        assertFalse(restored.offerCompletion(missing,projected(current,missing),current));
        assertTrue(restored.offerCompletion(correct,projected(current,correct),current));
        assertEquals(2,restored.completion().getJSONArray("checks").length());assertEquals(memory.goal(),restored.goal());assertEquals(memory.id(),restored.id());
    }
}
