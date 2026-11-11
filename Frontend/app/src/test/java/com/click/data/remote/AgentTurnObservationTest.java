package com.click.data.remote;

import android.app.Application;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AgentTurnObservationTest {
    private ScreenSnapshot screen(String pkg, boolean credential, boolean mixed) throws Exception {
        JSONArray nodes = new JSONArray().put(new JSONObject().put("node_id", "title")
                .put("package_name", pkg).put("visible_to_user", true).put("text", "설정")
                .put("role", "text").put("bounds_in_screen", new JSONArray("[0,0,1000,100]")));
        if (credential || mixed) nodes.put(new JSONObject().put("node_id", "other")
                .put("package_name", mixed ? "com.android.systemui" : pkg)
                .put("visible_to_user", true).put("role", credential ? "text_field" : "text")
                .put("text", credential ? "PRIVATE_VALUE" : "설정")
                .put("extras", new JSONObject().put("is_password", credential))
                .put("bounds_in_screen", new JSONArray("[0,200,1000,300]")));
        ScreenSnapshot snapshot = mock(ScreenSnapshot.class);
        when(snapshot.toJson()).thenReturn(new JSONObject().put("package_name", pkg).put("nodes", nodes).toString());
        return snapshot;
    }
    private AgentTurnGateway gateway() {
        try (org.mockito.MockedConstruction<com.click.data.local.AuthTokenStore> tokens =
                mockConstruction(com.click.data.local.AuthTokenStore.class)) {
            return new AgentTurnGateway(RuntimeEnvironment.getApplication());
        }
    }
    @Test public void unsupportedForegroundIsReobservedLocallyBeforeStartingOrStoppingATask() throws Exception {
        AgentTurnGateway gateway = gateway();
        assertTrue(gateway.needsFreshObservation(screen("com.android.systemui", false, false)));
        assertTrue(gateway.needsFreshObservation(screen("com.android.settings", false, true)));
        assertFalse(gateway.needsFreshObservation(screen("com.android.settings", false, false)));
    }
    @Test public void realCredentialBlockStillUsesTheFailClosedSendPath() throws Exception {
        AgentTurnGateway gateway = gateway();
        assertFalse(gateway.needsFreshObservation(screen("com.android.settings", true, false)));
    }
}
