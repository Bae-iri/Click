package com.click.data.snapshot;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RingerModeSnapshotTest {
    private ScreenSnapshot screen(int mode) {
        AccessibilityNodeSnapshot title=new AccessibilityNodeSnapshot("title",null,true,"com.android.settings",
            "소리 및 진동",null,null,"android.widget.TextView","text",new int[]{20,20,700,100},
            false,true,false,false,false,null,42);
        return new ScreenSnapshot("com.android.settings","2026-11-10T00:00:00Z",1080,2400,List.of(title),42).withRingerMode(mode);
    }
    private SettingsScreenPrivacy policy() throws Exception {
        try(var v=getClass().getResourceAsStream("/vision_vocabulary.json");var p=getClass().getResourceAsStream("/screen_privacy_policy.json")) {
            return SettingsScreenPrivacy.load(v,p);
        }
    }
    @Test public void modeSurvivesCopiesAndInvalidatesOldEvidence() throws Exception {
        ScreenSnapshot copy=screen(0).withScreenRevision("r2").withAdjustmentValues(100,7)
            .withWifiState(3).withNightMode(2).withNavigation(null);
        assertEquals(Integer.valueOf(0),copy.appliedRingerMode);
        assertEquals(0,new JSONObject(copy.toJson()).getJSONObject("applied_settings").getInt("ringer_mode"));
        assertFalse(screen(0).sameNativeContent(screen(2)));
    }
    @Test public void onlySoundModeGoalsExportTheFiniteEnum() throws Exception {
        JSONObject body=new JSONObject().put("accessibility_tree",new JSONObject(screen(1).toJson()));
        for(String goal:new String[]{"mute","vibrate"}) {
            JSONObject values=policy().filter(body,goal).getJSONObject("accessibility_tree").getJSONObject("applied_settings");
            assertEquals(1,values.getInt("ringer_mode"));
            assertEquals("android.media.AudioManager.getRingerMode",values.getString("ringer_mode_source"));
        }
        JSONObject unrelated=policy().filter(body,"brightness").getJSONObject("accessibility_tree");
        assertTrue(!unrelated.has("applied_settings") || !unrelated.getJSONObject("applied_settings").has("ringer_mode"));
    }
    @Test public void invalidOrUntrustedModeIsNotExported() throws Exception {
        JSONObject invalid=new JSONObject(screen(3).toJson());
        assertTrue(!invalid.has("applied_settings") || !invalid.getJSONObject("applied_settings").has("ringer_mode"));
        JSONObject body=new JSONObject().put("accessibility_tree",new JSONObject(screen(1).toJson()));
        body.getJSONObject("accessibility_tree").getJSONObject("applied_settings").put("ringer_mode_source","selected");
        JSONObject result=policy().filter(body,"mute").getJSONObject("accessibility_tree");
        assertTrue(!result.has("applied_settings") || !result.getJSONObject("applied_settings").has("ringer_mode"));
    }
}
