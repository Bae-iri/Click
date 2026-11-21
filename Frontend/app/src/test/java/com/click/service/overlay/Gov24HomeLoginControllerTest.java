package com.click.service.overlay;

import android.app.Application;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.*;
import java.time.Instant;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class Gov24HomeLoginControllerTest {
    private AccessibilityNodeSnapshot node(String id,String label,boolean click,boolean input) {
        return new AccessibilityNodeSnapshot(id,null,true,Gov24LoginPrivacy.PACKAGE,label,null,null,
            input?"android.widget.EditText":"android.widget.Button",null,new int[]{20,100,300,180},
            click,true,false,false,false,null,42,input,null,null,false,null,false,false);
    }
    @Test public void homeSearchDoesNotPreventManualLoginGuidanceOrAuthorizeAuthentication() throws Exception {
        var home=new ScreenSnapshot(Gov24LoginPrivacy.PACKAGE,Instant.now().toString(),1080,2400,List.of(
            node("brand","정부24",false,false),node("services","민원서비스",false,false),
            node("node_0_2","로그인",true,false),node("query","PRIVATE_QUERY",true,true)),42);
        var listener=mock(AppTaskController.Listener.class);
        when(listener.fresh()).thenReturn(home);when(listener.canObserve()).thenReturn(true);
        var gateway=mock(AppGuideGateway.class);
        var memory=new AppTaskMemory("정부24 로그인 도와줘");
        var controller=new AppTaskController(gateway,memory,null,listener,true);
        try {
            controller.targetApp(Gov24LoginPrivacy.PACKAGE,"정부24");controller.refresh();
            var presented=org.mockito.ArgumentCaptor.forClass(JSONObject.class);
            verify(listener,atLeastOnce()).present(presented.capture(),any());
            JSONObject response=presented.getValue();
            assertEquals("proposed",response.getString("status"));
            assertEquals("node_0_2",response.getJSONObject("action").getString("target_id"));
            assertFalse(response.getBoolean("execute_allowed"));
            assertTrue(response.getString("message").contains("로그인"));
            controller.execute(response.getJSONObject("action").getString("id"),null);
            verify(listener,never()).execute(any(),any(),any(),any(),any());
            verify(gateway,never()).send(any(),any());assertFalse(memory.finished());
            JSONObject observation=AppTaskObservation.capture(home);
            JSONObject action=response.getJSONObject("action");
            assertTrue(AppTaskPolicy.manualTapAllowed(observation,action));
            assertFalse("Serialized data cannot grant a native manual-login exception",
                AppTaskPolicy.manualTapAllowed(new JSONObject(observation.toString()),action));
            assertFalse(AppTaskPolicy.allowed(observation,observation,action,Gov24LoginPrivacy.PACKAGE));
        } finally {controller.close();}
    }
}
