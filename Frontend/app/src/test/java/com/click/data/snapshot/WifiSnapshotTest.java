package com.click.data.snapshot;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WifiSnapshotTest {
    private AccessibilityNodeSnapshot master(Boolean checked) {
        return new AccessibilityNodeSnapshot("master", null, true, "com.android.settings", "Wi-Fi", null,
                "com.android.settings:id/switch_widget", "android.widget.Switch", "switch",
                new int[]{0, 200, 1000, 350}, true, true, false, false, true, null, 42,
                false, null, null, false, checked);
    }
    private ScreenSnapshot screen(Boolean checked) {
        return new ScreenSnapshot("com.android.settings", "2026-11-07T00:00:00Z", 1080, 2400,
                List.of(master(checked)), 42).withWifiState(checked != null && checked ? 3 : 1);
    }
    private SettingsScreenPrivacy privacy() throws Exception {
        try (java.io.InputStream vocabulary = getClass().getResourceAsStream("/vision_vocabulary.json");
             java.io.InputStream policy = getClass().getResourceAsStream("/screen_privacy_policy.json")) {
            return SettingsScreenPrivacy.load(vocabulary, policy);
        }
    }
    @Test public void falseCheckedIsExplicitAndSurvivesTheHttpPrivacyBoundary() throws Exception {
        JSONObject safe = privacy().filter(new JSONObject().put("accessibility_tree", new JSONObject(screen(false).toJson())))
                .getJSONObject("accessibility_tree");
        JSONObject extras = safe.getJSONArray("nodes").getJSONObject(0).getJSONObject("extras");
        assertTrue(extras.has("checked"));
        assertEquals(Boolean.FALSE, extras.get("checked"));
        assertEquals(1, safe.getJSONObject("applied_settings").getInt("wifi_state"));
        assertEquals("android.net.wifi.WifiManager.getWifiState", safe.getJSONObject("applied_settings").getString("wifi_source"));
    }
    @Test public void checkedAndAdapterStateRefreshObservationAndPersistAcrossCopies() throws Exception {
        assertNotEquals(screen(false).contentFingerprint(), screen(true).contentFingerprint());
        ScreenSnapshot original = screen(true);
        ScreenSnapshot changed = original.withScreenRevision("r2").withAdjustmentValues(100, 3);
        assertEquals(Integer.valueOf(3), changed.appliedWifiState);
        assertEquals(3, new JSONObject(changed.toJson()).getJSONObject("applied_settings").getInt("wifi_state"));
        ScreenSnapshot before = new ScreenSnapshot("com.android.settings", original.capturedAt, 1080, 2400,
                List.of(master(false)), 42).withWifiState(3);
        assertNotEquals(before.contentFingerprint(), original.contentFingerprint());
    }
    @Test public void missingOrStringCheckedCannotBecomeNativeEvidence() throws Exception {
        assertFalse(master(null).toJson().getJSONObject("extras").has("checked"));
        JSONObject body = new JSONObject(screen(false).toJson());
        body.getJSONArray("nodes").getJSONObject(0).getJSONObject("extras").put("checked", "false");
        JSONObject safe = privacy().filter(new JSONObject().put("accessibility_tree", body)).getJSONObject("accessibility_tree");
        assertFalse(safe.getJSONArray("nodes").getJSONObject(0).getJSONObject("extras").has("checked"));
    }
}
