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
import java.time.*;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppGuideControllerTest {
    private ScreenSnapshot screen(String pkg,String title) {
        var node=new AccessibilityNodeSnapshot("control",null,true,pkg,title,null,pkg+":id/control",
            "android.widget.Button",null,new int[]{10,20,400,120},true,true,false,false,false,null,42,false,null,null,false);
        return new ScreenSnapshot(pkg,Instant.now().toString(),1080,2400,List.of(node),42);
    }
    private static final class Listener implements AppGuideController.Listener {
        ScreenSnapshot current;
        int presents;
        boolean busy;
        boolean allowed=true;
        public ScreenSnapshot fresh(){return current;}
        public boolean canObserve(){return allowed;}
        public void busy(boolean value){busy=value;}
        public void present(JSONObject response,ScreenSnapshot submitted){presents++;}
        public void failure(Throwable error){throw new AssertionError(error);}
    }
    @Test public void unchangedScreenDoesNotSpendAnotherModelCall() throws Exception {
        var gateway=mock(AppGuideGateway.class);var listener=new Listener();listener.current=screen("com.example.first","검색");
        var controller=new AppGuideController(gateway,"검색 열어줘",listener);
        controller.refresh();var callbacks=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);
        verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","needs_input"));
        controller.screenChanged(listener.current);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        verify(gateway,times(1)).send(any(),any());assertEquals(1,listener.presents);
        controller.close();
    }
    @Test public void staleResponseIsDiscardedAndNewAppGetsFreshObservation() throws Exception {
        var gateway=mock(AppGuideGateway.class);var listener=new Listener();listener.current=screen("com.example.first","검색");
        var controller=new AppGuideController(gateway,"검색 열어줘",listener);controller.refresh();
        var callbacks=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callbacks.capture());
        listener.current=screen("org.mozilla.firefox","메뉴");callbacks.getValue().success(new JSONObject().put("status","guide"));
        assertEquals(0,listener.presents);shadowOf(Looper.getMainLooper()).idle();
        var requests=ArgumentCaptor.forClass(JSONObject.class);verify(gateway,times(2)).send(requests.capture(),any());
        assertEquals("org.mozilla.firefox",requests.getAllValues().get(1).getJSONObject("screen").getString("package_name"));
        controller.close();
    }
    @Test public void cancelledGuideCannotPresentALateModelResult() throws Exception {
        var gateway=mock(AppGuideGateway.class);var listener=new Listener();listener.current=screen("com.example.first","검색");
        var controller=new AppGuideController(gateway,"검색 열어줘",listener);controller.refresh();
        var callbacks=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callbacks.capture());
        controller.close();callbacks.getValue().success(new JSONObject().put("status","guide"));
        assertEquals(0,listener.presents);verify(gateway).close();
    }
    @Test public void pausingBeforeResponsePreventsGuidanceFromReappearing() throws Exception {
        var gateway=mock(AppGuideGateway.class);var listener=new Listener();listener.current=screen("com.example.first","검색");
        var controller=new AppGuideController(gateway,"검색 열어줘",listener);controller.refresh();
        var callbacks=ArgumentCaptor.forClass(AppGuideGateway.Callback.class);verify(gateway).send(any(),callbacks.capture());
        listener.allowed=false;callbacks.getValue().success(new JSONObject().put("status","guide"));
        assertEquals(0,listener.presents);assertFalse(listener.busy);controller.close();
    }
}
