package com.click.service.overlay;

import android.app.Application;
import android.os.Looper;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowSystem;
import static com.click.testing.AppTaskFixtures.*;
import static com.click.service.overlay.AppTaskCompletion.Verdict.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** The server accepts selected native navigation buttons; device proof must agree. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskNativeButtonOutcomeTest {
    private static final String PKG="org.example.video";
    private static JSONObject goal(String kind)throws Exception {
        return AppTaskOutcomesTest.goal(AppTaskOutcomesTest.clause(kind,"Shorts"));
    }
    @Test public void selectedNativeButtonProvesBothPageAndSelectedClauses()throws Exception {
        var s=screen(PKG,node(PKG,"shorts","Shorts","button",true));
        var observed=AppTaskObservation.capture(s);
        assertEquals("button",observed.getJSONArray("nodes").getJSONObject(0).getString("role"));
        for(String kind:List.of("page","selected"))
            assertEquals(kind,MATCHED,AppTaskOutcomes.check(goal(kind),observed,s,new JSONObject(),null).verdict());
    }
    @Test public void unselectedDuplicateAndOcrButtonsCannotProveArrival()throws Exception {
        for(String kind:List.of("page","selected")){
            var s=screen(PKG,node(PKG,"shorts","Shorts","button",false));
            assertNotEquals(MATCHED,AppTaskOutcomes.check(goal(kind),AppTaskObservation.capture(s),s,new JSONObject(),null).verdict());
            s=screen(PKG,node(PKG,"one","Shorts","button",true),node(PKG,"two","Shorts","button",true));
            assertNotEquals(MATCHED,AppTaskOutcomes.check(goal(kind),AppTaskObservation.capture(s),s,new JSONObject(),null).verdict());
            s=screen(PKG,node(PKG,"shorts","Shorts","button",true));
            var observed=AppTaskObservation.capture(s);
            observed.getJSONArray("nodes").getJSONObject(0).put("source","ocr");
            assertNotEquals(MATCHED,AppTaskOutcomes.check(goal(kind),observed,s,new JSONObject(),null).verdict());
        }
    }
    @Test public void explicitSelectionUsesNativeStateExpectationAndNeverRetapsSelectedButton()throws Exception {
        var task=goal("selected");
        var s=screen(PKG,node(PKG,"shorts","Shorts","button",false));
        var tap=AppTaskOutcomes.firstTap(task,AppTaskObservation.capture(s),s,new JSONObject(),null);
        assertNotNull(tap);assertEquals("selection_changed",tap.expectation().getString("kind"));
        assertTrue(tap.expectation().getBoolean("selected"));
        s=screen(PKG,node(PKG,"shorts","Shorts","button",true));
        assertNull(AppTaskOutcomes.firstTap(task,AppTaskObservation.capture(s),s,new JSONObject(),null));
    }
    @Test public void controllerConfirmsSelectedDestinationAlreadyVisibleOrReachedByTapAcrossWindows()throws Exception {
      for(int mode:List.of(0,1,2)){
        var memory=new AppTaskMemory("Shorts 탭으로 이동해줘");assertTrue(memory.installOutcome(goal("selected")));
        var gateway=mock(AppGuideGateway.class);
        class Listener implements AppTaskController.Listener {
            JSONObject result;Throwable error;int executions,window=42;
            boolean selected=mode==0;
            public ScreenSnapshot fresh(){return new ScreenSnapshot(PKG,
                Instant.ofEpochMilli(ShadowSystem.currentTimeMillis()).toString(),1080,2400,
                List.of(new AccessibilityNodeSnapshot("shorts",null,true,PKG,"Shorts",null,PKG+":id/shorts",
                    "android.widget.Button",null,new int[]{10,20,400,120},true,true,selected,false,false,null,window)),window);}
            public boolean canObserve(){return true;} public void busy(boolean ignored){}
            public void present(JSONObject response,ScreenSnapshot sent){result=response;}
            public void failure(Throwable e){error=e;}
            public boolean execute(ScreenSnapshot b,JSONObject o,JSONObject a,String i,String p){
                executions++;selected=true;if(mode==2)window=43;return true;}
            public boolean inputMatches(String id,String hash){return false;}
        }
        var listener=new Listener();
        var c=new AppTaskController(gateway,memory,null,listener,true,false,true,AppTaskMetaController.Arm.CURRENT,true);
        try{
            c.enableOutcomes(true);c.targetApp(PKG,"Video");c.refresh();
            if(mode!=0){
                assertEquals("proposed",listener.result.getString("status"));
                c.execute(listener.result.getJSONObject("action").getString("id"),null);
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
            }
            assertFalse(memory.completionSupported());
            assertEquals("checking_result",listener.result.getString("status"));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450));
            assertTrue(memory.completionSupported());
            assertEquals("verified",listener.result.getJSONObject("result_assessment").getString("status"));
            assertEquals("confirm_result",listener.result.getString("status"));
            assertFalse(memory.finished());assertEquals(mode==0?0:1,listener.executions);assertNull(listener.error);
            verify(gateway,never()).send(any(),any());
        }finally{c.close();}
      }
    }
}
