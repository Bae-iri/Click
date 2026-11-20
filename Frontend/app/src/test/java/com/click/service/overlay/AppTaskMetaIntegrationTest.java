package com.click.service.overlay;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;
import static com.click.testing.AppTaskFixtures.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskMetaIntegrationTest {
    private static final String PKG="org.example.app";
    private static final class Listener implements AppTaskController.Listener {
        ScreenSnapshot current=screen(PKG,node(PKG,"target","검색","button"));JSONObject response;int executed;boolean observable=true;
        public ScreenSnapshot fresh(){return new ScreenSnapshot(current.packageName,java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(),current.screenWidth,current.screenHeight,current.nodes,current.activeWindowId).withWindowContext(current.windowContext);}
        public boolean canObserve(){return observable;}public void busy(boolean value){}
        public void present(JSONObject response,ScreenSnapshot baseline){this.response=response;}
        public void failure(Throwable error){throw new AssertionError(error);}
        public boolean execute(ScreenSnapshot before,JSONObject observation,JSONObject action,String input,String selectedPackage){executed++;return true;}
        public boolean inputMatches(String id,String hash){return false;}
    }
    private AppTaskController controller(Listener l,AppTaskMetaController.Arm arm)throws Exception{
        return new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("검색 열기"),null,l,false,false,false,arm);
    }
    private void idle(long ms){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    @Test public void existingConstructorRemainsCurrentArm()throws Exception {
        var l=new Listener();var c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("검색 열기"),null,l,false);
        assertEquals(AppTaskMetaController.Arm.CURRENT,c.metaController().arm());c.close();
    }
    @Test public void reobservationNeverAutomaticallyRepeatsAnAcceptedAction()throws Exception {
        var l=new Listener();var c=controller(l,AppTaskMetaController.Arm.META);c.refresh();c.execute((String)null);idle(9000); // mc_v1 keeps the accepted action read-only for at most 8s.
        assertEquals(1,l.executed);assertFalse(c.memory().finished());assertEquals("g0",c.memory().active());
        assertEquals(1,c.metaController().diagnostic().getInt("recovery_count"));assertEquals("needs_input",l.response.getString("status"));
        assertTrue(l.response.isNull("action"));c.refresh();c.execute((String)null);assertEquals(1,l.executed);c.close();
    }
    @Test public void noEventsAblationAlsoRetainsNativeVerificationAndNoRepeat()throws Exception {
        var l=new Listener();var c=controller(l,AppTaskMetaController.Arm.NO_EVENTS);c.refresh();c.execute((String)null);idle(6000);
        assertEquals(1,l.executed);assertEquals(1,c.metaController().diagnostic().getInt("recovery_count"));
        assertEquals(0,c.metaController().diagnostic().getInt("event_count"));assertFalse(c.memory().finished());c.close();
    }
    @Test public void freshEventExtendsWaitButNeverClaimsSuccess()throws Exception {
        var l=new Listener();var c=controller(l,AppTaskMetaController.Arm.META);c.refresh();c.execute((String)null);idle(4800);
        c.screenChanged(l.current);idle(600);
        assertEquals("verifying",l.response.getString("status"));assertEquals(0,c.memory().actions());assertFalse(c.memory().finished());
        idle(3500);assertEquals(1,l.executed);assertFalse(c.memory().finished());assertEquals(1,c.metaController().diagnostic().getInt("recovery_count"));c.close();
    }
    @Test public void protectedScreenAndPauseKeepTaskAndNeverTriggerRecovery()throws Exception {
        var l=new Listener();var c=controller(l,AppTaskMetaController.Arm.META);c.refresh();c.execute((String)null);
        l.observable=false;idle(6000);assertEquals(0,c.memory().actions());
        l.current=screen(PKG,node(PKG,"private","PRIVATE_PASSWORD","password"));l.observable=true;c.resume();idle(500);
        assertEquals(0,c.metaController().diagnostic().getInt("recovery_count"));assertFalse(c.memory().finished());assertEquals(1,l.executed);
        assertFalse(c.metaController().diagnostic().toString().contains("PRIVATE_PASSWORD"));c.close();
    }
    @Test public void cancellationDiscardsScheduledVerificationAndRecovery()throws Exception {
        var l=new Listener();var c=controller(l,AppTaskMetaController.Arm.META);c.refresh();c.execute((String)null);c.close();idle(10000);
        assertEquals(0,c.memory().actions());assertEquals(0,c.metaController().diagnostic().getInt("recovery_count"));assertEquals(1,l.executed);
    }
}
