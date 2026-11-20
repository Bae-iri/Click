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
import org.robolectric.annotation.*;
import java.time.Duration;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;
import static com.click.testing.AppTaskFixtures.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskControllerTest {
    @Test public void namedAppEntryWaitsForTargetAndManualOpeningContinuesAutomatically()throws Exception {
        Listener l=new Listener();l.current=screen("com.android.launcher",node("com.android.launcher","node_0_1","설정","button"));
        var gateway=mock(AppGuideGateway.class);var c=new AppTaskController(gateway,new AppTaskMemory("쿠팡에서 물 주문하고 싶어"),null,l,false);
        c.targetApp("com.coupang.mobile","쿠팡");c.refresh();
        assertEquals("open_app",l.result.getJSONObject("action").getString("tool"));assertEquals("쿠팡",l.result.getString("target_label"));
        assertNull(c.memory().scope());assertEquals(0,l.executions);String old=l.result.getJSONObject("action").getString("id");
        l.current=screen("com.coupang.mobile",node("com.coupang.mobile","node_0_1","쿠팡에서 검색하세요","button"));
        c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals("tap",l.result.getJSONObject("action").getString("tool"));assertEquals("com.coupang.mobile",c.memory().scope());
        c.execute(old,null);assertEquals(0,l.executions);verify(gateway,never()).send(any(),any());c.close();
    }
    @Test public void productRequestUsesVisibleSearchFieldWithoutCompletingTheOrder()throws Exception {
        String pkg="com.coupang.mobile";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","","search"));
        var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("쿠팡에서 물 주문하고 싶어"),null,l,false);c.targetApp(pkg,"쿠팡");c.refresh();
        assertEquals("set_text",l.result.getJSONObject("action").getString("tool"));assertEquals("물",l.result.getString("suggested_query"));assertEquals(0,l.executions);
        c.execute(l.result.getJSONObject("action").getString("id"),"물");l.current=screen(pkg,node(pkg,"node_0_1","물","search"));l.textMatches=true;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertEquals("키보드의 검색을 눌러주세요.",l.result.getString("message"));assertFalse(c.memory().finished());assertNotNull(c.memory().active());assertEquals(1,l.executions);
        l.current=screen(pkg,node(pkg,"node_0_1","물 상품","button"));c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals("검색 결과에서 원하는 상품을 골라주세요.",l.result.getString("message"));assertEquals(1,l.executions);c.close();
    }
    @Test public void guardedInputScreenKeepsItsPanelAndCanBeFinishedByTheUser()throws Exception {
        String pkg="com.google.android.gm";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","","password"));
        var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("지메일로 메일 보내줘"),null,l,false);c.targetApp(pkg,"지메일");c.refresh();
        assertEquals("protected",l.result.getString("status"));int presents=l.presents;
        for(int i=0;i<5;i++){l.current=screen(pkg,node(pkg,"node_0_"+(i+2),"","password"));c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));}
        assertEquals(presents,l.presents);
        c.refresh();assertEquals("confirm_result",l.result.getString("status"));
        c.confirmResult();assertTrue(c.memory().finished());c.close();
    }
    @Test public void keyboardLayoutChurnKeepsTheSearchInputProposal()throws Exception {
        String pkg="com.google.android.youtube";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","","search"));
        var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("유튜브에서 고양이 영상 검색해줘"),null,l,false);c.targetApp(pkg,"유튜브");c.refresh();
        assertEquals("set_text",l.result.getJSONObject("action").getString("tool"));String id=l.result.getJSONObject("action").getString("id");int presents=l.presents;
        // Opening the keyboard in the panel resizes the app and adds suggestion rows.
        l.current=screen(pkg,node(pkg,"node_0_2","","search"),node(pkg,"node_0_3","고양이","button"));
        c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(presents,l.presents);
        l.current=screen(pkg,node(pkg,"node_0_4","","search"));
        c.execute(id,"고양이 영상");assertEquals(1,l.executions);c.close();
    }
    @Test public void targetAuthenticationStillStopsAndAmbiguousSearchIsNeverClicked()throws Exception {
        String pkg="com.coupang.mobile";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","비밀번호","password"));
        var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("쿠팡에서 물 주문하고 싶어"),null,l,false);c.targetApp(pkg,"쿠팡");c.refresh();
        assertEquals("protected",l.result.getString("status"));
        l.current=screen(pkg,node(pkg,"node_0_1","검색","button"),node(pkg,"node_0_2","상품 검색","button"));c.refresh();
        assertTrue(l.result.isNull("action"));assertEquals(0,l.executions);c.close();
    }
    @Test public void authenticationPreservesTheSameTaskAndResumesAcrossDifferentApps()throws Exception {
        for(String pkg:List.of("kr.go.minwon.m","com.coupang.mobile","org.example.reader")){
            Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));
            var gateway=mock(AppGuideGateway.class);AppTaskMemory memory=new AppTaskMemory("검색 열기 그리고 검색어 입력");
            var controller=new AppTaskController(gateway,memory,null,l,false);controller.targetApp(pkg,"요청한 앱");
            controller.refresh();String id=memory.id(),active=memory.active();
            String plan=memory.state().getJSONArray("subgoals").toString();
            l.current=screen(pkg,node(pkg,"node_0_1","PRIVATE_PASSWORD","password"));controller.refresh();
            assertEquals("protected",l.result.getString("status"));assertEquals(active,memory.active());
            assertEquals(plan,memory.state().getJSONArray("subgoals").toString());assertFalse(memory.finished());
            assertFalse(memory.stored().toString().contains("PRIVATE_PASSWORD"));
            l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));controller.screenChanged(l.current);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertEquals(id,memory.id());assertEquals(active,memory.active());
            assertEquals("proposed",l.result.getString("status"));assertEquals("tap",l.result.getJSONObject("action").getString("tool"));
            assertEquals(0,l.executions);verify(gateway,never()).send(any(),any());controller.close();
        }
    }
    @Test public void searchUsesTheQueryTheUserEditedAndApproved()throws Exception {
        String pkg="com.coupang.mobile";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","","search"));
        var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("쿠팡에서 물 주문하고 싶어"),null,l,false);c.targetApp(pkg,"쿠팡");c.refresh();
        c.execute(l.result.getJSONObject("action").getString("id"),"생수");l.actualText="생수";l.textMatches=true;l.current=screen(pkg,node(pkg,"node_0_1","생수","search"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertEquals("키보드의 검색을 눌러주세요.",l.result.getString("message"));assertTrue(l.result.isNull("action"));assertEquals(1,l.executions);c.close();
    }
    private static final class Listener implements AppTaskController.Listener {
        ScreenSnapshot current;JSONObject result;int executions,presents;boolean accepted=true,allowed=true,textMatches,freshReads=true,advanceObservationClock;String actualText;
        private final long observationClockStart=android.os.SystemClock.uptimeMillis();
        public ScreenSnapshot fresh(){long time=System.currentTimeMillis()+(advanceObservationClock?android.os.SystemClock.uptimeMillis()-observationClockStart:0);return current==null||!freshReads?current:new ScreenSnapshot(current.packageName,java.time.Instant.ofEpochMilli(time).toString(),current.screenWidth,current.screenHeight,current.nodes,current.activeWindowId).withWindowContext(current.windowContext);}public boolean canObserve(){return allowed;}public void busy(boolean value){}
        public void present(JSONObject r,ScreenSnapshot submitted){result=r;presents++;}
        public void failure(Throwable error){throw new AssertionError(error);}
        public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selectedPackage){executions++;return accepted;}
        public boolean inputMatches(String target,String hash){return textMatches&&(actualText==null||AppTaskObservation.hash(actualText).equals(hash));}
    }
    @Test public void localNavigationAndTypingDoNotClaimTheWholeMultistepGoalInThreeApps()throws Exception {
        for(String pkg:List.of("com.spotify.music","org.mozilla.firefox","com.google.android.youtube")){
            Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));
            AppTaskMemory m=new AppTaskMemory("검색 열기 그리고 검색어 입력");var gateway=mock(AppGuideGateway.class);var c=new AppTaskController(gateway,m,null,l,false);
            c.refresh();assertEquals(0,l.executions);assertTrue(l.result.getBoolean("execute_allowed"));assertEquals("검색",l.result.getString("target_label"));
            String oldId=l.result.getJSONObject("action").getString("id");c.execute(oldId,null);assertEquals(1,l.executions);assertEquals("g0",m.active());
            l.current=screen(pkg,node(pkg,"node_0_1","","search"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertEquals("g0",m.active());c.chooseTool("set_text");assertEquals("set_text",l.result.getJSONObject("action").getString("tool"));
            c.execute(oldId,"stale approval");assertEquals(1,l.executions);
            c.execute(l.result.getJSONObject("action").getString("id"),"PRIVATE_QUERY");assertEquals(2,l.executions);
            l.current=screen(pkg,node(pkg,"node_0_1","PRIVATE_QUERY","search"));l.textMatches=true;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals("g0",m.active());assertNotEquals("confirm_result",l.result.getString("status"));assertFalse(m.finished());assertFalse(m.stored().toString().contains("PRIVATE_QUERY"));
            c.confirmResult();assertTrue(m.finished());verify(gateway,never()).send(any(),any());c.close();
        }
    }
    @Test public void unchangedScreenDoesNotAdvanceAndRepeatedFailureStops()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));AppTaskMemory m=new AppTaskMemory("검색 열기");var c=new AppTaskController(mock(AppGuideGateway.class),m,null,l,false);
        c.refresh();c.execute((String)null);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));assertEquals("g0",m.active());assertEquals("failed",m.state().getJSONArray("attempts").getJSONObject(0).getString("outcome"));
        c.refresh();c.execute((String)null);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));c.refresh();assertEquals("needs_input",l.result.getString("status"));assertEquals(2,l.executions);c.close();
    }
    @Test public void staleApprovalAndProtectedScreenDoNotExecute()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("검색 열기"),null,l,false);
        c.refresh();String id=l.result.getJSONObject("action").getString("id");l.current=screen(pkg,node(pkg,"node_0_1","다른 화면","button"));c.execute(id,null);assertEquals(0,l.executions);
        l.current=screen(pkg,node(pkg,"node_0_1","PRIVATE","password"));c.refresh();assertEquals("protected",l.result.getString("status"));c.execute(id,null);assertEquals(0,l.executions);c.close();
    }
    @Test public void remotePlanningIsDebouncedAndLateResponsesCannotCrossApps()throws Exception {
        Listener l=new Listener();l.current=screen("org.first.app",node("org.first.app","node_0_1","검색","button"));var gateway=mock(AppGuideGateway.class);var c=new AppTaskController(gateway,new AppTaskMemory("노래를 찾아서 비교해 줘"),null,l,true);
        c.refresh();var callbacks=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callbacks.capture());
        l.current=screen("org.second.app",node("org.second.app","node_0_1","목록","button"));callbacks.getValue().success(new JSONObject().put("status","needs_input"));assertEquals(1,l.presents);assertEquals("planning",l.result.getString("status"));shadowOf(Looper.getMainLooper()).idle();
        verify(gateway,times(2)).send(any(),any());c.close();int presents=l.presents;callbacks.getValue().success(new JSONObject().put("status","proposed"));assertEquals(presents,l.presents);
    }
    @Test public void pauseFreezesVerificationAndResumeUsesActualState()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));AppTaskMemory m=new AppTaskMemory("검색 열기");var c=new AppTaskController(mock(AppGuideGateway.class),m,null,l,false);
        c.refresh();c.execute((String)null);l.allowed=false;l.current=screen(pkg,node(pkg,"node_0_1","목록","button"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals("g0",m.active());
        l.allowed=true;c.resume();shadowOf(Looper.getMainLooper()).idle();assertEquals("g0",m.active());assertEquals("verified",m.state().getJSONArray("attempts").getJSONObject(0).getString("outcome"));c.close();
    }
    @Test public void commonToolsAndAppOpeningAreExplicitProposals()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","목록","list"));var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("목록 확인"),null,l,false);
        c.refresh();for(String tool:List.of("scroll_forward","scroll_backward","back")){c.chooseTool(tool);assertEquals(tool,l.result.getJSONObject("action").getString("tool"));assertTrue(l.result.getBoolean("execute_allowed"));assertEquals(0,l.executions);}
        c.chooseApp("org.selected.app","선택 앱");assertEquals("open_app",l.result.getJSONObject("action").getString("tool"));assertTrue(l.result.getBoolean("execute_allowed"));c.execute((String)null);assertEquals(1,l.executions);
        l.current=screen("org.selected.app",node("org.selected.app","node_0_1","홈","button"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));assertEquals("verified",c.memory().state().getJSONArray("attempts").getJSONObject(0).getString("outcome"));c.close();
    }
    @Test public void noModelCallForStableObservationAndCancellationDiscardsResponse()throws Exception {
        Listener l=new Listener();l.current=screen("org.first.app",node("org.first.app","node_0_1","검색","button"));var gateway=mock(AppGuideGateway.class);var c=new AppTaskController(gateway,new AppTaskMemory("노래를 찾아서 비교해 줘"),null,l,true);
        c.refresh();var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callback.capture());callback.getValue().success(new JSONObject().put("status","needs_input").put("message","확인"));
        c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));verify(gateway,times(1)).send(any(),any());int before=l.presents;c.close();callback.getValue().success(new JSONObject().put("status","proposed"));assertEquals(before,l.presents);
    }
    @Test public void finalResultIsDetectedAcrossAppsEvenWhenManuallyReachedWithPendingSubgoals()throws Exception {
        for(String pkg:List.of("com.spotify.music","org.mozilla.firefox","org.example.newapp")){
            Listener l=new Listener();l.advanceObservationClock=true;l.current=screen(pkg,node(pkg,"start","검색","button"));AppTaskMemory m=new AppTaskMemory("목록 열어줘");m.localPlan();m.completion(AppTaskCompletionTest.completion(pkg,"목록"));
            var gateway=mock(AppGuideGateway.class);var c=new AppTaskController(gateway,m,null,l,false,false,true);c.refresh();
            l.current=screen(pkg,AppTaskGoalCompletionTest.heading(pkg,"result","목록"));c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            assertEquals("confirm_result",l.result.getString("status"));assertEquals("verified",l.result.getJSONObject("result_assessment").getString("status"));
            assertEquals("g0",m.active());assertFalse(m.finished());assertEquals(0,l.executions);verify(gateway,never()).send(any(),any());
            int before=l.presents;c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(before,l.presents);c.close();
        }
    }
    @Test public void transientResultOrOldObservationDoesNotConfirmOrCountAsFailedAction()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,AppTaskGoalCompletionTest.heading(pkg,"result","목록"));AppTaskMemory m=new AppTaskMemory("목록 열어줘");m.completion(AppTaskCompletionTest.completion(pkg,"목록"));
        var c=new AppTaskController(mock(AppGuideGateway.class),m,null,l,false,false,true);c.refresh();assertEquals("checking_result",l.result.getString("status"));
        l.current=screen(pkg,node(pkg,"other","홈","button"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));assertNotEquals("confirm_result",l.result.getString("status"));
        l.current=screen(pkg,AppTaskGoalCompletionTest.heading(pkg,"result","목록"));l.freshReads=false;c.refresh();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals("needs_input",l.result.getString("status"));assertEquals("unknown",l.result.getJSONObject("result_assessment").getString("status"));assertEquals(0,m.actions());c.close();
    }
    @Test public void missingFreshActionEvidenceIsUnknownInsteadOfAFailedAttempt()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,node(pkg,"search","검색","button"));l.freshReads=false;AppTaskMemory m=new AppTaskMemory("검색 열기");
        var c=new AppTaskController(mock(AppGuideGateway.class),m,null,l,false);c.refresh();c.execute((String)null);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals("waiting",m.state().getJSONArray("attempts").getJSONObject(0).getString("outcome"));assertEquals("g0",m.active());c.close();
    }
    @Test public void remotePlanBindsFinalResultBeforeLocalShortcutsAndChecksItWithoutAnotherModelCall()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.advanceObservationClock=true;l.current=screen(pkg,node(pkg,"search","검색","button"));var gateway=mock(AppGuideGateway.class);AppTaskMemory m=new AppTaskMemory("검색 열기");
        var c=new AppTaskController(gateway,m,null,l,true,false,true);c.refresh();var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callback.capture());
        JSONObject observed=AppTaskObservation.capture(l.current),a=action(observed,"tap","target_visible");a.getJSONObject("expectation").put("label","검색어 입력");
        callback.getValue().success(new JSONObject().put("status","proposed").put("subgoals",new JSONArray().put(new JSONObject().put("title","검색 열기")))
            .put("completion",AppTaskCompletionTest.completion(pkg,"검색어 입력")).put("action",a));
        c.execute(l.result.getJSONObject("action").getString("id"),null);l.current=screen(pkg,node(pkg,"field","","search"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals("confirm_result",l.result.getString("status"));assertEquals("verified",l.result.getJSONObject("result_assessment").getString("status"));assertEquals(1,l.executions);verify(gateway,times(1)).send(any(),any());c.close();
    }
    @Test public void modelCannotBindCurrentNavigationMenuAsFinalProofInEitherArm()throws Exception {
        for(AppTaskMetaController.Arm arm:List.of(AppTaskMetaController.Arm.CURRENT,AppTaskMetaController.Arm.META)){
            String pkg="org.example.app";Listener l=new Listener();l.advanceObservationClock=true;l.current=screen(pkg,node(pkg,"display","디스플레이","button"));
            var gateway=mock(AppGuideGateway.class);AppTaskMemory m=new AppTaskMemory("디스플레이 열어줘");
            var c=new AppTaskController(gateway,m,null,l,true,false,true,arm);c.refresh();
            var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callback.capture());
            JSONObject a=action(AppTaskObservation.capture(l.current),"tap","target_visible");a.getJSONObject("expectation").put("label","밝기");
            callback.getValue().success(new JSONObject().put("status","proposed").put("subgoals",new JSONArray().put(new JSONObject().put("title","디스플레이 열기")))
                .put("completion",AppTaskCompletionTest.completion(pkg,"디스플레이")).put("action",a));
            assertNull(m.completion());assertEquals("proposed",l.result.getString("status"));assertFalse(l.result.has("result_assessment"));assertEquals(0,l.executions);
            c.close();
        }
    }
    @Test public void unsafeRestoredContractIsDiscardedAndReboundWithoutLosingProgress()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.advanceObservationClock=true;l.current=screen(pkg,node(pkg,"display","디스플레이","button"));
        AppTaskMemory original=new AppTaskMemory("디스플레이 열어줘");original.localPlan();original.completion(AppTaskCompletionTest.completion(pkg,"디스플레이"));
        AppTaskMemory m=new AppTaskMemory(original.stored());String id=m.id(),plan=m.state().getJSONArray("subgoals").toString();
        var gateway=mock(AppGuideGateway.class);var c=new AppTaskController(gateway,m,null,l,true,false,true);c.refresh();
        assertNull(m.completion());assertEquals("planning",l.result.getString("status"));assertEquals(id,m.id());assertEquals(plan,m.state().getJSONArray("subgoals").toString());
        var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callback.capture());
        JSONObject a=action(AppTaskObservation.capture(l.current),"tap","target_visible");a.getJSONObject("expectation").put("label","밝기");
        callback.getValue().success(new JSONObject().put("status","proposed").put("subgoals",m.state().getJSONArray("subgoals"))
            .put("completion",AppTaskCompletionTest.completion(pkg,"디스플레이 화면")).put("action",a));
        // A discarded prediction is not repaired with another unseen guess.
        assertNull(m.completion());assertEquals("proposed",l.result.getString("status"));
        l.current=screen(pkg,AppTaskGoalCompletionTest.heading(pkg,"title","디스플레이 화면"));c.screenChanged(l.current);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        var observedCallback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway,times(2)).send(any(),observedCallback.capture());
        observedCallback.getValue().success(new JSONObject().put("status","confirm_result").put("subgoals",m.state().getJSONArray("subgoals"))
            .put("completion",AppTaskCompletionTest.completion(pkg,"디스플레이 화면")));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals("confirm_result",l.result.getString("status"));assertEquals("verified",l.result.getJSONObject("result_assessment").getString("status"));
        assertEquals(id,m.id());assertEquals(plan,m.state().getJSONArray("subgoals").toString());assertFalse(m.finished());assertEquals(0,l.executions);c.close();
    }
    @Test public void manualTapOnATargetsLabelChildIsCorrelatedButAnUnrelatedChildIsIgnored()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.advanceObservationClock=true;
        AccessibilityNodeSnapshot child=new AccessibilityNodeSnapshot("label","row",true,pkg,"검색",null,"label_resource","android.widget.TextView",null,new int[]{20,30,300,100},false,true,false,false,false,null,42);
        AccessibilityNodeSnapshot other=new AccessibilityNodeSnapshot("other",null,true,pkg,"다른 글자",null,"other_resource","android.widget.TextView",null,new int[]{20,200,300,280},false,true,false,false,false,null,42);
        l.current=screen(pkg,node(pkg,"row","검색","button"),child,other);var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("검색 열기"),null,l,false);c.refresh();
        long occurred=System.currentTimeMillis()+1;c.manualTap(pkg,42,other.nodeId,other.resourceId,other.bounds,occurred);assertFalse(l.result.optBoolean("verifying"));
        c.manualTap(pkg,42,child.nodeId,child.resourceId,child.bounds,occurred);assertTrue(l.result.getBoolean("verifying"));
        l.current=screen(pkg,node(pkg,"field","","search"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals("verified",c.memory().state().getJSONArray("attempts").getJSONObject(0).getString("outcome"));assertEquals("g0",c.memory().active());c.close();
    }
    @Test public void planningClearsOldActionAndReportsOnlyAvailableRecoveryTools()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));
        var gateway=mock(AppGuideGateway.class);var c=new AppTaskController(gateway,new AppTaskMemory("검색 열기"),null,l,true);
        c.refresh();assertEquals("proposed",l.result.getString("status"));
        assertTrue(l.result.getString("message").contains("직접 눌러주세요"));assertFalse(l.result.getBoolean("can_read_labels"));
        assertEquals(2,l.result.getJSONArray("available_tools").length());
        l.current=screen(pkg,node(pkg,"node_0_1","홈","button"));c.refresh();
        assertEquals("planning",l.result.getString("status"));assertTrue(l.result.isNull("action"));assertEquals(0,l.result.getJSONArray("available_tools").length());
        c.execute((String)null);assertEquals(0,l.executions);c.close();
    }
    @Test public void protectedAndAmbiguousScreensDoNotOfferInapplicableRecovery()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","PRIVATE","password"));
        var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("다음 화면"),null,l,false);c.refresh();
        assertEquals(0,c.availableTools().length());assertFalse(l.result.getBoolean("can_read_labels"));
        l.current=screen(pkg,node(pkg,"node_0_1","목록","list"),node(pkg,"node_0_2","다른 목록","list"));c.refresh();
        String tools=c.availableTools().toString();assertFalse(tools.contains("scroll_forward"));assertFalse(tools.contains("set_text"));assertTrue(tools.contains("back"));c.close();
    }

    @Test public void pausedPlanningIgnoresLateErrorsAndCanResume()throws Exception {
        Listener l=new Listener();l.current=screen("org.example.app",node("org.example.app","node_0_1","검색","button"));
        var gateway=mock(AppGuideGateway.class);var c=new AppTaskController(gateway,new AppTaskMemory("노래를 찾아서 비교해 줘"),null,l,true);
        c.refresh();var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callback.capture());
        int shown=l.presents;l.allowed=false;callback.getValue().failure(new java.io.IOException("offline"));assertEquals(shown,l.presents);
        l.allowed=true;c.resume();verify(gateway,times(2)).send(any(),any());assertEquals("planning",l.result.getString("status"));c.close();
    }

}
