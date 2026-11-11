package com.click.data.snapshot;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class DisplayDensitySnapshotTest {
    private ScreenSnapshot screen(int density) {
        var title=new AccessibilityNodeSnapshot("title",null,true,"com.android.settings","화면 크게/작게",null,null,
            "android.widget.TextView","text",new int[]{20,20,700,100},false,true,false,false,false,null,42);
        return new ScreenSnapshot("com.android.settings","2026-11-10T00:00:00Z",1080,2400,List.of(title),42).withDisplayDensity(density);
    }
    private SettingsScreenPrivacy policy() throws Exception {
        try(var v=getClass().getResourceAsStream("/vision_vocabulary.json");var p=getClass().getResourceAsStream("/screen_privacy_policy.json")) {return SettingsScreenPrivacy.load(v,p);}
    }
    @Test public void densityAndSoundModeInvalidateObservationFingerprintsAndSurviveCopies() throws Exception {
        ScreenSnapshot a=screen(420).withRingerMode(0).withScreenRevision("r").withAdjustmentValues(100,7).withWifiState(3).withNightMode(2).withNavigation(null);
        assertEquals(Integer.valueOf(420),a.appliedDisplayDensityDpi);
        assertNotEquals(screen(420).contentFingerprint(),screen(480).contentFingerprint());
        assertNotEquals(screen(420).withRingerMode(0).contentFingerprint(),screen(420).withRingerMode(2).contentFingerprint());
        assertFalse(screen(420).sameNativeContent(screen(480)));
    }
    @Test public void onlySizeGoalsExportTrustedBoundedDensity() throws Exception {
        JSONObject request=new JSONObject().put("accessibility_tree",new JSONObject(screen(420).toJson()));
        JSONObject applied=policy().filter(request,"display_size").getJSONObject("accessibility_tree").getJSONObject("applied_settings");
        assertEquals(420,applied.getInt("display_density_dpi"));
        assertEquals("android.content.res.Configuration.densityDpi",applied.getString("display_density_source"));
        JSONObject other=policy().filter(request,"font_size").getJSONObject("accessibility_tree");
        assertFalse(other.has("applied_settings"));
        for(int value:new int[]{0,71,2001}) {
            JSONObject invalid=new JSONObject(screen(value).toJson());assertFalse(invalid.has("applied_settings"));
        }
        request.getJSONObject("accessibility_tree").getJSONObject("applied_settings").put("display_density_source","slider");
        assertFalse(policy().filter(request,"display_size").getJSONObject("accessibility_tree").has("applied_settings"));
    }
}
