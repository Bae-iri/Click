package com.click.service.overlay;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class ObservationRecoveryTest {
    private JSONObject jevWait() throws Exception {
        return new JSONObject().put("ui_selector", "jev").put("revalidate_before_action", true)
                .put("runtime_strategy", "wait_for_action")
                .put("fallback_action", new JSONObject().put("type", "request_fresh_observation"));
    }
    @Test public void unreasonedJevWaitSearchesKnownSettingsLists() throws Exception {
        for (String state : new String[]{"S1_SETTINGS_HOME", "S2_DISPLAY_MENU", "S3_FONT_MENU", "S2_SOUND_MENU"}) {
            assertTrue(ObservationRecovery.canSearchJevWait(state, jevWait(), null, settings("com.android.settings")));
        }
        JSONObject metadata = new JSONObject().put("verification", new JSONObject().put("result",
                new JSONObject().put("status", "SUCCESS").put("evidence", "observed_progress")));
        assertTrue(ObservationRecovery.canSearchJevWait("S2_DISPLAY_MENU", jevWait(), metadata, settings("com.android.settings")));
    }
    @Test public void jevWaitNeverScrollsPendingResultsOrProviderFailures() throws Exception {
        for (String status : new String[]{"WAITING_FOR_OBSERVATION", "UNKNOWN", "NO_CHANGE", "WRONG_STATE"}) {
            JSONObject metadata = new JSONObject().put("verification", new JSONObject().put("result",
                    new JSONObject().put("status", status)));
            assertFalse(ObservationRecovery.canSearchJevWait("S1_SETTINGS_HOME", jevWait(), metadata, settings("com.android.settings")));
        }
        JSONObject metadata = new JSONObject().put("debug", new JSONObject().put("ui_selection",
                new JSONObject().put("status", "provider_error")));
        assertFalse(ObservationRecovery.canSearchJevWait("S1_SETTINGS_HOME", jevWait(), metadata, settings("com.android.settings")));
        metadata.put("debug", new JSONObject().put("ui_selection", new JSONObject()
                .put("status", "low_confidence").put("grounded_candidate_count", 1)));
        assertFalse(ObservationRecovery.canSearchJevWait("S1_SETTINGS_HOME", jevWait(), metadata, settings("com.android.settings")));
    }
    @Test public void jevWaitDoesNotOverrideExplicitRecoveryOrUnknownScreens() throws Exception {
        for (String key : new String[]{"recovery", "vision_fallback", "backend_handoff", "recovery_action"})
            assertFalse(ObservationRecovery.canSearchJevWait("S1_SETTINGS_HOME", jevWait().put(key, new JSONObject()), null, settings("com.android.settings")));
        assertFalse(ObservationRecovery.canSearchJevWait("S4_FONT_SIZE_CONTROL", jevWait(), null, settings("com.android.settings")));
        assertFalse(ObservationRecovery.canSearchJevWait("S0_UNKNOWN", jevWait(), null, settings("com.android.settings")));
        assertFalse(ObservationRecovery.canSearchJevWait("S1_SETTINGS_HOME", jevWait(), null, settings("com.example.other")));
    }
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

    private JSONObject offscreenPolicy(String reason) throws Exception {
        return new JSONObject().put("recovery", new JSONObject()
                .put("recoverable", true).put("waiting_for", "user")
                .put("reason_code", reason).put("next_action", "manual_navigation"));
    }

    @Test public void offscreenSettingsMenuAllowsUserRequestedScrollInBothSettingsPackages() throws Exception {
        assertTrue(ObservationRecovery.canSearchByScrolling(offscreenPolicy("TARGET_NOT_VISIBLE"), settings("com.android.settings")));
        assertTrue(ObservationRecovery.canSearchByScrolling(offscreenPolicy("TARGET_NOT_VISIBLE"), settings("com.samsung.android.settings")));
    }

    @Test public void ambiguityAndResultVerificationNeverAllowScroll() throws Exception {
        for (String reason : new String[]{"TARGET_AMBIGUOUS", "ACTION_RESULT_UNOBSERVABLE", "STATE_UNKNOWN"}) {
            assertFalse(ObservationRecovery.canSearchByScrolling(offscreenPolicy(reason), settings("com.android.settings")));
        }
        assertFalse(ObservationRecovery.canSearchByScrolling(offscreenPolicy("TARGET_NOT_VISIBLE"), settings("com.example.other")));
        assertFalse(ObservationRecovery.canSearchByScrolling(null, settings("com.android.settings")));
        assertFalse(ObservationRecovery.canSearchByScrolling(offscreenPolicy("TARGET_NOT_VISIBLE"), null));
    }

    @Test public void missingWindowOrScrollableListCannotBeScrolled() throws Exception {
        ScreenSnapshot base = settings("com.android.settings");
        assertFalse(ObservationRecovery.canSearchByScrolling(offscreenPolicy("TARGET_NOT_VISIBLE"),
                new ScreenSnapshot(base.packageName, base.capturedAt, 1080, 2400, base.nodes)));
        assertFalse(ObservationRecovery.canSearchByScrolling(offscreenPolicy("TARGET_NOT_VISIBLE"),
                new ScreenSnapshot(base.packageName, base.capturedAt, 1080, 2400, Collections.emptyList(), 7)));
    }

    @Test public void currentMissingTargetCodesSearchMenusBeforeManualRecovery() throws Exception {
        JSONObject policy = new JSONObject().put("recovery", new JSONObject()
                .put("recoverable", true).put("waiting_for", "observation")
                .put("reason_code", "TARGET_NOT_FOUND").put("next_action", "reobserve"));
        for (String state : new String[]{"S1_SETTINGS_HOME", "S2_DISPLAY_MENU", "S3_FONT_MENU"})
            assertTrue(ObservationRecovery.canSearchByScrolling(state, policy, settings("com.android.settings")));
        for (String state : new String[]{"S0_UNKNOWN", "S4_FONT_SIZE_CONTROL", "S5_FONT_SIZE_CHANGED"})
            assertFalse(ObservationRecovery.canSearchByScrolling(state, policy, settings("com.android.settings")));
        policy.put("backend_handoff", new JSONObject().put("type", "task_graph_reselection_required"));
        assertFalse(ObservationRecovery.canSearchByScrolling("S1_SETTINGS_HOME", policy, settings("com.android.settings")));
    }

    @Test public void legacyAndCurrentFallbackKeysBothSearchFontSubmenu() throws Exception {
        for (String key : new String[]{"reason", "reason_code"}) {
            JSONObject policy = new JSONObject().put("fallback_action", new JSONObject()
                    .put("type", "request_fresh_observation")
                    .put(key, "reason".equals(key) ? "target_not_found" : "TARGET_NOT_FOUND"));
            assertTrue(ObservationRecovery.canSearchByScrolling("S3_FONT_MENU", policy, settings("com.android.settings")));
        }
        for (String reason : new String[]{"TARGET_AMBIGUOUS", "ACTION_RESULT_UNOBSERVABLE", "STATE_UNKNOWN", "ACTION_EVENT_MISSING"})
            assertFalse(ObservationRecovery.canSearchByScrolling("S1_SETTINGS_HOME", offscreenPolicy(reason), settings("com.android.settings")));
    }
}
