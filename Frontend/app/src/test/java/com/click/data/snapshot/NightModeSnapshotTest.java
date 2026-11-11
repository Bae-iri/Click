package com.click.data.snapshot;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NightModeSnapshotTest {
    private ScreenSnapshot screen(int mode) {
        AccessibilityNodeSnapshot title=new AccessibilityNodeSnapshot("title",null,true,"com.android.settings",
            "디스플레이",null,null,"android.widget.TextView","text",new int[]{20,20,700,100},
            false,true,false,false,false,null,42);
        return new ScreenSnapshot("com.android.settings","2026-11-09T00:00:00Z",1080,2400,List.of(title),42).withNightMode(mode);
    }
    private SettingsScreenPrivacy policy() throws Exception {
        try(var v=getClass().getResourceAsStream("/vision_vocabulary.json");var p=getClass().getResourceAsStream("/screen_privacy_policy.json")) {
            return SettingsScreenPrivacy.load(v,p);
        }
    }
    @Test public void nativeModeSurvivesCopiesAndInvalidatesStaleModeEvidence() throws Exception {
        ScreenSnapshot copied=screen(2).withScreenRevision("r2").withAdjustmentValues(100,7).withWifiState(3);
        assertEquals(Integer.valueOf(2),copied.appliedNightMode);
        assertEquals(2,new JSONObject(copied.toJson()).getJSONObject("applied_settings").getInt("night_mode"));
        assertFalse(screen(1).sameNativeContent(screen(2)));
    }
    @Test public void onlyDarkModeRequestsExportTheFiniteSystemMode() throws Exception {
        JSONObject body=new JSONObject().put("accessibility_tree",new JSONObject(screen(2).toJson()));
        JSONObject mode=policy().filter(body,"dark_mode").getJSONObject("accessibility_tree").getJSONObject("applied_settings");
        assertEquals(2,mode.getInt("night_mode"));
        assertEquals("android.app.UiModeManager.getNightMode",mode.getString("night_mode_source"));
        JSONObject unrelated=policy().filter(body,"brightness").getJSONObject("accessibility_tree");
        assertTrue(!unrelated.has("applied_settings") || !unrelated.getJSONObject("applied_settings").has("night_mode"));
    }
}
