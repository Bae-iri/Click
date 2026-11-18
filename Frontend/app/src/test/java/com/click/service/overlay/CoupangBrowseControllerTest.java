package com.click.service.overlay;

import android.app.Application;
import com.click.data.remote.CoupangBrowseGateway;
import com.click.data.snapshot.CoupangBrowsePrivacy;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class CoupangBrowseControllerTest {
    @Test public void localGuidanceDoesNotConsumeAModelTurn() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        var callback=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(any(),callback.capture());
        callback.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        when(listener.localGuide(null)).thenReturn(true);
        controller.observe(null);
        verify(gateway,times(1)).send(any(),any());
    }
    @Test public void oneExplicitRequestAndNoLatePresentationAfterStop() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        controller.observe(null);
        var request=ArgumentCaptor.forClass(JSONObject.class);
        var callback=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway,times(1)).send(request.capture(),callback.capture());
        assertEquals("start",request.getValue().getString("command"));
        controller.close();
        callback.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        verify(listener,never()).present(any(),any());
    }
    @Test public void lostResponseRetryKeepsTheExactRequestIdentity() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        var requests=ArgumentCaptor.forClass(JSONObject.class);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(requests.capture(),callbacks.capture());
        String original=requests.getValue().toString();
        callbacks.getValue().failure(new java.io.IOException("timeout"));
        var retry=ArgumentCaptor.forClass(Runnable.class);
        verify(listener).failed(any(),retry.capture());retry.getValue().run();
        verify(gateway,times(2)).send(requests.capture(),any());
        assertEquals(original,requests.getValue().toString());
    }

    private com.click.data.snapshot.ScreenSnapshot appScreen(String pkg) {
        var search = new com.click.data.snapshot.AccessibilityNodeSnapshot("search",null,true,pkg,"검색",null,
            pkg+":id/search_bar","android.widget.TextView",null,new int[]{0,100,300,200},true,true,false,false,false,null,42);
        return new com.click.data.snapshot.ScreenSnapshot(pkg,java.time.Instant.now().toString(),1080,2400,java.util.List.of(search),42);
    }

    @Test public void entryDuringStartIsObservedOnceAfterTheSessionVersionArrives() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        controller.screenChanged(appScreen("com.coupang.mobile"));
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        var requests=ArgumentCaptor.forClass(JSONObject.class);
        verify(gateway,times(2)).send(requests.capture(),callbacks.capture());
        assertEquals("observe",requests.getValue().getString("command"));
        assertEquals(1,requests.getValue().getInt("expected_version"));
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",2));
        controller.screenChanged(appScreen("com.coupang.mobile"));
        verify(gateway,times(2)).send(any(),any());
    }

    @Test public void entryWaitsWhilePausedAndIgnoresOtherApps() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(appScreen("com.coupang.mobile"));
        verify(gateway,times(1)).send(any(),any());
        when(listener.canObserve()).thenReturn(true);
        controller.screenChanged(appScreen("com.sec.android.app.launcher"));
        verify(gateway,times(1)).send(any(),any());
        controller.screenChanged(appScreen("com.coupang.mobile"));
        verify(gateway,times(2)).send(any(),any());
    }
    @Test public void publicLoginQuestionSurvivesScreenChangeAndReplyCarriesFreshQuestionBinding() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,view->new JSONObject().put("status","outside_app"),
            "kr.go.minwon.m","public-guide.v1",listener);
        controller.start("session","prepared");
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1)
            .put("question",new JSONObject().put("id","current-question")));
        controller.screenChanged(appScreen("other.app"));assertTrue(controller.awaitingAnswer());
        controller.answer(appScreen("kr.go.minwon.m"),"카카오로",false);
        var requests=ArgumentCaptor.forClass(JSONObject.class);verify(gateway,times(2)).send(requests.capture(),any());
        assertEquals("public-guide.v1",requests.getValue().getString("protocol_version"));
        assertEquals("current-question",requests.getValue().getString("question_id"));
        assertEquals("카카오로",requests.getValue().getString("answer"));
        controller.close();assertFalse(controller.awaitingAnswer());
    }
}
