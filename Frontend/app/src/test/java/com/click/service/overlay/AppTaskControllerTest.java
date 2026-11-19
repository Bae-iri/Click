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
    private static final class Listener implements AppTaskController.Listener {
        ScreenSnapshot current;JSONObject result;int executions,presents;boolean accepted=true,allowed=true,textMatches;
        public ScreenSnapshot fresh(){return current;}public boolean canObserve(){return allowed;}public void busy(boolean value){}
        public void present(JSONObject r,ScreenSnapshot submitted){result=r;presents++;}
        public void failure(Throwable error){throw new AssertionError(error);}
        public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selectedPackage){executions++;return accepted;}
        public boolean inputMatches(String target,String hash){return textMatches;}
    }
    @Test public void approvedMultistepTaskRunsThroughSameEngineInThreeApps()throws Exception {
        for(String pkg:List.of("com.spotify.music","org.mozilla.firefox","com.google.android.youtube")){
            Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));
            AppTaskMemory m=new AppTaskMemory("검색 열기 그리고 검색어 입력");var gateway=mock(AppGuideGateway.class);var c=new AppTaskController(gateway,m,null,l,false);
            c.refresh();assertEquals(0,l.executions);assertTrue(l.result.getBoolean("execute_allowed"));assertEquals("검색",l.result.getString("target_label"));
            String oldId=l.result.getJSONObject("action").getString("id");c.execute(oldId,null);assertEquals(1,l.executions);assertEquals("g0",m.active());
            l.current=screen(pkg,node(pkg,"node_0_1","","search"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertEquals("g1",m.active());assertEquals("set_text",l.result.getJSONObject("action").getString("tool"));
            c.execute(oldId,"stale approval");assertEquals(1,l.executions);
            c.execute(l.result.getJSONObject("action").getString("id"),"PRIVATE_QUERY");assertEquals(2,l.executions);
            l.current=screen(pkg,node(pkg,"node_0_1","PRIVATE_QUERY","search"));l.textMatches=true;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertNull(m.active());assertEquals("confirm_result",l.result.getString("status"));assertFalse(m.finished());assertFalse(m.stored().toString().contains("PRIVATE_QUERY"));
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
        l.current=screen("org.second.app",node("org.second.app","node_0_1","목록","button"));callbacks.getValue().success(new JSONObject().put("status","needs_input"));assertEquals(0,l.presents);shadowOf(Looper.getMainLooper()).idle();
        verify(gateway,times(2)).send(any(),any());c.close();callbacks.getValue().success(new JSONObject().put("status","proposed"));assertEquals(0,l.presents);
    }
    @Test public void pauseFreezesVerificationAndResumeUsesActualState()throws Exception {
        String pkg="org.example.app";Listener l=new Listener();l.current=screen(pkg,node(pkg,"node_0_1","검색","button"));AppTaskMemory m=new AppTaskMemory("검색 열기");var c=new AppTaskController(mock(AppGuideGateway.class),m,null,l,false);
        c.refresh();c.execute((String)null);l.allowed=false;l.current=screen(pkg,node(pkg,"node_0_1","목록","button"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals("g0",m.active());
        l.allowed=true;c.resume();shadowOf(Looper.getMainLooper()).idle();assertNull(m.active());c.close();
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
}
