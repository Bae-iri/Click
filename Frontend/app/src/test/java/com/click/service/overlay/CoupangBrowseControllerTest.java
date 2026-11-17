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
}
