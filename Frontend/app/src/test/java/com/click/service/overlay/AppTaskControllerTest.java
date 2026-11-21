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
    @Test public void firstPhoneEntryIsOfferedOnceAndUsesTheKeypadTabInsidePhone() throws Exception {
        var phone=new RequestedApp("org.phone","전화",null,true);
        var gateway=mock(AppGuideGateway.class);
        var listener=new Listener();
        var memory=new AppTaskMemory("전화번호 키패드에 즐겨찾기 하고 싶어");
        var controller=new AppTaskController(gateway,memory,null,listener,false);
        try {
            controller.targetApp(phone);
            listener.current=screen("org.contacts",node("org.contacts","node_0_1","연락처","tab",true));
            controller.refresh();
            JSONObject entry=listener.result.getJSONObject("action");
            assertEquals("open_app",entry.getString("tool"));assertEquals(phone.packageName,entry.getString("package"));
            controller.refresh();assertEquals(entry.getString("id"),listener.result.getJSONObject("action").getString("id"));
            listener.current=screen(phone.packageName,node(phone.packageName,"node_0_1","연락처","tab",true),node(phone.packageName,"node_0_2","키패드","tab",false));
            controller.refresh();
            assertEquals("tap",listener.result.getJSONObject("action").getString("tool"));
            assertEquals("키패드",listener.result.getString("target_label"));
            listener.current=screen(phone.packageName,node(phone.packageName,"node_0_1","키패드","tab",true),
                node(phone.packageName,"node_0_2","검색","button"));
            controller.refresh();
            assertTrue(listener.result.isNull("action"));assertFalse(memory.finished());
            // A later menu within Phone need not show the keypad again.
            listener.current=screen(phone.packageName,node(phone.packageName,"node_0_1","설정","button"));
            controller.refresh();assertTrue(listener.result.isNull("action"));
            listener.current=screen("org.contacts",node("org.contacts","node_0_1","검색","button"));
            controller.refresh();assertEquals("awaiting_screen",listener.result.getString("status"));
            assertTrue(listener.result.isNull("action"));assertFalse(controller.takeAutomaticEntry());
            assertFalse(memory.finished());verify(gateway,never()).send(any(),any());
        } finally {controller.close();}
    }
    @Test public void verificationSurvivesMissingAndForeignWindowsWithoutReopeningOrReplaying() throws Exception {
        String pkg="org.phone";
        var listener=new Listener();
        var gateway=mock(AppGuideGateway.class);
        var memory=new AppTaskMemory("전화에서 설정 메뉴 열어줘");
        var controller=new AppTaskController(gateway,memory,null,listener,false);
        try {
            controller.targetApp(pkg,"전화");
            listener.current=screen(pkg,node(pkg,"node_0_1","설정","button"));
            controller.refresh();
            assertEquals("tap",listener.result.getJSONObject("action").getString("tool"));
            controller.execute((String)null);
            listener.current=null;
            controller.screenChanged(null);shadowOf(Looper.getMainLooper()).idle();
            assertEquals("awaiting_screen",listener.result.getString("status"));
            listener.current=screen("org.contacts",node("org.contacts","node_0_1","설정","button"));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            assertEquals("awaiting_screen",listener.result.getString("status"));assertTrue(listener.result.isNull("action"));
            assertEquals(0,controller.availableTools().length());assertFalse(controller.takeAutomaticEntry());
            assertFalse(memory.finished());assertNotNull(memory.active());assertEquals(1,listener.executions);
            assertEquals(0,memory.actions());
            listener.current=screen(pkg,node(pkg,"node_0_2","목록","button"));
            controller.screenChanged(listener.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals(1,memory.actions());assertEquals(1,listener.executions);
            assertEquals("verified",memory.state().getJSONArray("attempts").getJSONObject(0).getString("outcome"));
            verify(gateway,never()).send(any(),any());
        } finally {controller.close();}
    }

    @Test public void restoredTaskWaitsForItsWindowWithoutAnAppLaunch() throws Exception {
        var memory=new AppTaskMemory("보관함 열어줘");memory.scope("org.library|4|34|app-task.v2");
        var listener=new Listener();listener.current=screen("org.other",node("org.other","n","보관함","tab",true));
        var gateway=mock(AppGuideGateway.class);
        var c=new AppTaskController(gateway,new AppTaskMemory(memory.stored()),null,listener,true);
        try{
            c.targetApp("org.library","자료");c.refresh();c.userRefresh();
            assertEquals("awaiting_screen",listener.result.getString("status"));assertTrue(listener.result.isNull("action"));
            assertFalse(c.mayConfirmResult());assertFalse(c.takeAutomaticEntry());assertEquals(0,listener.executions);
            verify(gateway,never()).send(any(),any());
        }finally{c.close();}
    }

    @Test public void unknownAppOnLauncherDoesNotSendFoldersToPlanner() throws Exception {
        var listener=new Listener();
        var gateway=mock(AppGuideGateway.class);
        String home="com.sec.android.app.launcher";
        listener.current=screen(home,node(home,"node_0_1","도구 폴더","button"),node(home,"node_0_2","검색","button"));
        var controller=new AppTaskController(gateway,new AppTaskMemory("목록을 정리하고 싶어"),null,listener,true);
        try {
            controller.refresh();
            assertEquals("needs_input",listener.result.getString("status"));assertTrue(listener.result.isNull("action"));
            assertEquals(0,listener.executions);verify(gateway,never()).send(any(),any());
        } finally {controller.close();}
    }
    @Test public void productRequestUsesVisibleSearchFieldWithoutCompletingTheOrder()throws Exception {
        String pkg="com.coupang.mobile";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","","search"));
        var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("쿠팡에서 물 주문하고 싶어"),null,l,false);c.targetApp(pkg,"쿠팡");c.refresh();
        assertEquals("set_text",l.result.getJSONObject("action").getString("tool"));assertEquals("물",l.result.getString("suggested_query"));assertEquals(0,l.executions);
        assertFalse(l.result.getBoolean("execute_allowed"));assertTrue(l.result.getString("message").endsWith("물"));
        l.current=screen(pkg,node(pkg,"node_0_1","물","search"));l.textMatches=true;c.screenChanged(l.current);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertEquals("키보드의 검색을 눌러주세요.",l.result.getString("message"));assertFalse(c.memory().finished());assertNotNull(c.memory().active());assertEquals(0,l.executions);
        l.current=screen(pkg,node(pkg,"node_0_1","물 상품","button"));c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals("검색 결과에서 원하는 상품을 골라주세요.",l.result.getString("message"));assertEquals(0,l.executions);c.close();
    }
    @Test public void calendarPointsToTheTitleWithoutExposingFormValues()throws Exception {
        String pkg="org.example.calendar";
        AccessibilityNodeSnapshot title=new AccessibilityNodeSnapshot("title",null,true,pkg,"개인 일정",null,pkg+":id/event_title",
                "android.widget.EditText","text_field",new int[]{40,120,600,230},true,true,false,false,false,null,42,true,null,null,false);
        AccessibilityNodeSnapshot location=new AccessibilityNodeSnapshot("place",null,true,pkg,"개인 장소",null,pkg+":id/event_location",
                "android.widget.EditText","text_field",new int[]{40,280,600,390},true,true,false,false,false,null,42,true,null,null,false);
        Listener listener=new Listener();listener.current=screen(pkg,title,location);var gateway=mock(AppGuideGateway.class);
        var controller=new AppTaskController(gateway,new AppTaskMemory("캘린더에서 12월 25일에 일정 만들고 싶어"),null,listener,true);
        controller.refresh();assertEquals("protected",listener.result.getString("status"));
        assertEquals("표시한 칸에 제목을 입력해 주세요.",listener.result.getString("message"));
        assertEquals(new JSONArray(title.bounds).toString(),listener.result.getJSONArray("input_bounds").toString());
        assertTrue(listener.result.isNull("action"));assertFalse(listener.result.toString().contains("개인 일정"));
        controller.userRefresh();assertEquals("protected",listener.result.getString("status"));assertFalse(listener.result.getBoolean("input_only"));
        String goal="캘린더에서 제목에 ‘치과 예약’을 입력해 줘";
        assertEquals("표시한 제목 칸에 이렇게 입력해 주세요.\n치과 예약",AppTaskFormInput.find(listener.current,goal).instruction(goal));
        listener.current=screen(pkg,title,node(pkg,"password","private","password"));controller.screenChanged(listener.current);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));assertFalse(listener.result.has("input_bounds"));
        assertFalse(controller.memory().finished());verify(gateway,never()).send(any(),any());controller.close();
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
    @Test public void inputFollowsTheNativeFieldAcrossKeyboardRedrawsAndWaitsForTheWholeQuery()throws Exception {
        String pkg="com.google.android.youtube";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","","search"));
        var gateway=mock(AppGuideGateway.class);
        var c=new AppTaskController(gateway,new AppTaskMemory("유튜브에서 공룡 검색해 줘"),null,l,true,false,true);
        c.targetApp(pkg,"유튜브");c.refresh();String id=l.result.getJSONObject("action").getString("id");
        assertEquals("표시한 검색창에 이렇게 입력해 주세요.\n공룡",l.result.getString("message"));
        assertFalse(l.result.getBoolean("execute_allowed"));c.execute(id,"대신 입력");assertEquals(0,l.executions);
        l.actualText="공";l.textMatches=true;l.current=screen(pkg,node(pkg,"node_0_2","공","search"),node(pkg,"suggestion","다른 추천","button"));
        c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(id,l.result.getJSONObject("action").getString("id"));assertEquals("node_0_2",l.result.getJSONObject("action").getString("target_id"));assertEquals(0,c.memory().actions());
        // The two nonempty values have the same redacted fingerprint.
        l.actualText="공룡";l.current=screen(pkg,node(pkg,"node_0_2","공룡","search"),node(pkg,"suggestion","다른 추천","button"));
        c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals("키보드의 검색을 눌러주세요.",l.result.getString("message"));assertTrue(l.result.isNull("action"));
        assertEquals(1,c.memory().actions());assertFalse(c.memory().finished());assertEquals(0,l.executions);verify(gateway,never()).send(any(),any());c.close();
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
    @Test public void explicitTypingRequestDoesNotAskTheUserToSubmitTheSearch()throws Exception {
        String pkg="org.example.reader";Listener l=new Listener();l.current=screen(pkg,node(pkg,"field","","search"));
        var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("검색창에 ‘서울역’을 입력해 줘"),null,l,false);
        c.refresh();assertEquals("서울역",l.result.getString("suggested_query"));
        l.current=screen(pkg,node(pkg,"field","서울역","search"));l.actualText="서울역";l.textMatches=true;c.screenChanged(l.current);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));assertEquals("confirm_result",l.result.getString("status"));
        assertEquals(0,l.executions);assertFalse(c.memory().finished());c.close();
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
            c.execute(l.result.getJSONObject("action").getString("id"),"PRIVATE_QUERY");assertEquals(1,l.executions);
            l.current=screen(pkg,node(pkg,"node_0_1","PRIVATE_QUERY","search"));l.textMatches=true;c.screenChanged(l.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals("g0",m.active());assertNotEquals("confirm_result",l.result.getString("status"));assertFalse(m.finished());assertFalse(m.stored().toString().contains("PRIVATE_QUERY"));
            c.confirmResult();assertTrue(m.finished());verify(gateway,never()).send(any(),any());c.close();
        }
    }
    @Test public void unchangedScreenDoesNotAdvanceAndRepeatedFailureStops()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));AppTaskMemory m=new AppTaskMemory("검색 열기");var c=new AppTaskController(mock(AppGuideGateway.class),m,null,l,false);
        c.refresh();c.execute((String)null);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));assertEquals("g0",m.active());assertEquals("waiting",m.state().getJSONArray("attempts").getJSONObject(0).getString("outcome"));
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
    @Test public void finalGoalCanBeProvedWhileAnIntermediatePredictionIsStillMissing()throws Exception {
        String pkg="org.example.app";
        Listener listener=new Listener();
        listener.advanceObservationClock=true;
        listener.current=screen(pkg,node(pkg,"entry","메뉴","button"));
        AppTaskMemory memory=new AppTaskMemory("목록 화면 열어줘");
        var gateway=mock(AppGuideGateway.class);
        var controller=new AppTaskController(gateway,memory,null,listener,true,false,true);
        controller.refresh();
        var callback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);
        verify(gateway).send(any(),callback.capture());
        JSONObject proposed=action(AppTaskObservation.capture(listener.current),"tap","target_visible");
        proposed.getJSONObject("expectation").put("label","중간 메뉴");
        callback.getValue().success(new JSONObject().put("status","proposed")
                .put("subgoals",new JSONArray().put(new JSONObject().put("title","중간 메뉴 열기")))
                .put("completion",AppTaskCompletionTest.completion(pkg,"목록")).put("action",proposed));
        controller.execute(listener.result.getJSONObject("action").getString("id"),null);
        listener.current=screen(pkg,AppTaskGoalCompletionTest.heading(pkg,"title","목록"));
        var main=shadowOf(Looper.getMainLooper());
        main.idleFor(Duration.ofMillis(300));
        assertEquals("checking_result",listener.result.getString("status"));
        assertFalse(memory.completionSupported());
        listener.current=screen(pkg,node(pkg,"other","다른 화면","button"));
        main.idleFor(Duration.ofMillis(400));
        assertFalse(memory.completionSupported());
        listener.current=screen(pkg,AppTaskGoalCompletionTest.heading(pkg,"title","목록"));
        main.idleFor(Duration.ofMillis(850));
        assertEquals("verified",listener.result.getJSONObject("result_assessment").getString("status"));
        assertFalse(listener.result.getBoolean("verifying"));
        assertFalse(memory.finished());
        assertEquals("g0",memory.active());
        assertEquals("waiting",memory.state().getJSONArray("attempts").getJSONObject(0).getString("outcome"));
        main.idleFor(Duration.ofSeconds(6));
        assertEquals(1,memory.actions());
        controller.confirmResult();
        assertTrue(memory.finished());
        assertEquals(0,memory.stored().getJSONArray("route").length());
        verify(gateway,times(1)).send(any(),any());
        controller.close();
    }
    @Test public void completedPlanWithoutResultEvidenceKeepsRecoveryAvailable()throws Exception {
        String pkg="org.example.app";
        Listener listener=new Listener();
        listener.current=screen(pkg,node(pkg,"entry","메뉴","button"));
        AppTaskMemory memory=new AppTaskMemory("목록 화면 열어줘");
        memory.localPlan();
        JSONObject before=AppTaskObservation.capture(listener.current);
        JSONObject step=action(before,"tap","target_visible");
        step.getJSONObject("expectation").put("label","중간 메뉴");
        memory.record(before,step,"verified",true);
        assertNull(memory.active());
        var controller=new AppTaskController(mock(AppGuideGateway.class),memory,null,listener,false,false,true);
        controller.refresh();
        assertEquals("needs_input",listener.result.getString("status"));
        assertEquals("unknown",listener.result.getJSONObject("result_assessment").getString("status"));
        assertFalse(memory.finished());
        controller.close();
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
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550)); // Event coalescing, then a stable planner frame pair.
        var observedCallback=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway,times(2)).send(any(),observedCallback.capture());
        observedCallback.getValue().success(new JSONObject().put("status","confirm_result").put("subgoals",m.state().getJSONArray("subgoals"))
            .put("completion",AppTaskCompletionTest.completion(pkg,"디스플레이 화면")));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals("confirm_result",l.result.getString("status"));assertEquals("verified",l.result.getJSONObject("result_assessment").getString("status"));
        assertEquals(id,m.id());assertEquals(plan,m.state().getJSONArray("subgoals").toString());assertFalse(m.finished());assertEquals(0,l.executions);c.close();
    }
    @Test public void ambiguousPredictedContractIsReleasedSoTheDestinationTitleCanBind()throws Exception {
        String pkg="com.android.settings";Listener l=new Listener();l.advanceObservationClock=true;
        // The predicted "밝기" appears twice on the reached page, so it can never become evidence there.
        l.current=screen(pkg,AppTaskGoalCompletionTest.heading(pkg,"title","디스플레이"),
            AppTaskGoalCompletionTest.heading(pkg,"a","밝기"),AppTaskGoalCompletionTest.heading(pkg,"b","밝기"));
        AppTaskMemory original=new AppTaskMemory("디스플레이 설정 열어줘");original.localPlan();original.completion(AppTaskCompletionTest.completion(pkg,"밝기"));
        AppTaskMemory m=new AppTaskMemory(original.stored());String id=m.id();
        var c=new AppTaskController(mock(AppGuideGateway.class),m,null,l,true,false,true);c.refresh();
        assertNull(m.completion());assertEquals(id,m.id());assertFalse(m.finished());assertEquals(0,l.executions);c.close();
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
        assertEquals("화면에서 ‘검색’ 항목을 눌러주세요.",l.result.getString("message"));assertFalse(l.result.getBoolean("can_read_labels"));
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
