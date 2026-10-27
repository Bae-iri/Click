package com.click.service.overlay;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class ObservationRecoveryTest {
    @Test public void structuredRecoveryWaitsWithoutEndingTask() throws Exception {
        for (String decision : new String[]{"wait", "handoff"}) {
            JSONObject policy = new JSONObject().put("recovery", new JSONObject()
                    .put("decision", decision).put("recoverable", true).put("waiting_for", "user"));
            assertTrue(ObservationRecovery.awaitsUser(policy));
        }
        assertFalse(ObservationRecovery.awaitsUser(null));
        assertFalse(ObservationRecovery.awaitsUser(new JSONObject().put("recovery", new JSONObject()
                .put("recoverable", true).put("waiting_for", "observation"))));
        assertFalse(ObservationRecovery.awaitsUser(new JSONObject().put("recovery", new JSONObject()
                .put("recoverable", false).put("waiting_for", "user"))));
    }
    private ScreenSnapshot settings(String pkg) {
        AccessibilityNodeSnapshot list = new AccessibilityNodeSnapshot("list", null, true, pkg, null, null,
                "recycler_view", "androidx.recyclerview.widget.RecyclerView", "list", new int[]{0, 400, 1080, 2200},
                false, true, false, true, false, null, 7);
        return new ScreenSnapshot(pkg, "2026-10-11T06:00:00Z", 1080, 2400, Collections.singletonList(list), 7);
    }
    @Test public void missingSettingsTargetGuidesManualScrolling() throws Exception {
        String message = ObservationRecovery.message("S1_SETTINGS_HOME", new JSONObject().put("reason", "target_not_found"), settings("com.android.settings"));
        assertTrue(message.contains("디스플레이")); assertTrue(message.contains("위로 밀어"));
    }
    @Test public void uncertaintyOrVerificationNeverSuggestsScrolling() throws Exception {
        assertNull(ObservationRecovery.message("S1_SETTINGS_HOME", new JSONObject().put("reason", "ambiguous_target"), settings("com.android.settings")));
        assertNull(ObservationRecovery.message("S1_SETTINGS_HOME", new JSONObject().put("reason", "target_not_found"), settings("com.example.other")));
        assertNull(ObservationRecovery.message("S4_FONT_SIZE_CONTROL", new JSONObject().put("reason", "target_not_found"), settings("com.android.settings")));
    }
}
