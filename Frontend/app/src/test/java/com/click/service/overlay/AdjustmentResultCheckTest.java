package com.click.service.overlay;

import com.click.data.remote.AgentTurnContract;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import java.time.Instant;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdjustmentResultCheckTest {
    private final int[] bounds = {20, 100, 900, 180};
    private static final String INCREASE = "settings.font_size.increase";

    @Test public void manualEvidencePreservesTheOriginalGuideAcrossFontRedraws() throws Exception {
        var check=new AdjustmentResultCheck();
        var before=nativeFont(3f,1.1f,1000,42,"settings:id/font");
        var guide=guide("highlight");
        check.bind("episode",INCREASE,guide,before);
        var first=nativeFont(4f,1.3f,2000,43,"settings:id/font");
        var settled=nativeFont(4f,1.3f,2500,44,"settings:id/font");
        check.bind("episode",INCREASE,guide("highlight"),first);
        JSONObject proof=check.manualEvidence("episode",INCREASE,first,settled);
        assertNotNull(proof);
        assertEquals(before.observationId,proof.getString("baseline_observation_id"));
        assertEquals(2,proof.getJSONArray("observations").length());
        assertFalse(proof.toString().contains("user_action_event"));
        assertFalse(proof.toString().contains("글자 크기"));
        assertNull(check.manualEvidence("other",INCREASE,first,settled));
        check.clear();assertNull(check.manualEvidence("episode",INCREASE,first,settled));
    }

    @Test public void manualEvidenceRejectsMovingValuesAndOtherNativeControls() throws Exception {
        var check=new AdjustmentResultCheck();
        check.bind("episode",INCREASE,guide("highlight"),nativeFont(3f,1.1f,1000,42,"settings:id/font"));
        var first=nativeFont(4f,1.3f,2000,43,"settings:id/font");
        assertTrue(check.manualEvidenceNeedsSettling("episode",INCREASE,first,nativeFont(4f,1.3f,2100,43,"settings:id/font")));
        assertNull(check.manualEvidence("episode",INCREASE,first,nativeFont(4f,1.3f,2100,43,"settings:id/font")));
        assertNull(check.manualEvidence("episode",INCREASE,first,nativeFont(5f,1.3f,2500,44,"settings:id/font")));
        assertNull(check.manualEvidence("episode",INCREASE,first,nativeFont(4f,1.3f,2500,44,"settings:id/brightness")));
        assertNull(check.manualEvidence("episode",INCREASE,first,nativeFont(2f,.9f,2500,44,"settings:id/font")));
    }

    private ScreenSnapshot nativeFont(float value,float scale,long at,int window,String resource) {
        var slider=new AccessibilityNodeSnapshot("font",null,true,"com.android.settings","글자 크기",null,
                resource,"android.widget.SeekBar","slider",bounds,true,true,false,false,false,value,window,false,0f,7f);
        return new ScreenSnapshot("com.android.settings",Instant.ofEpochMilli(at).toString(),1080,2400,List.of(slider),window,true,scale);
    }

    @Test public void brightnessAndVolumeUsePersistedValuesInBothDirections() throws Exception {
        for (String setting : List.of("brightness", "volume")) for (String direction : List.of("increase", "decrease")) {
            AdjustmentResultCheck check = new AdjustmentResultCheck();
            String goal = "settings." + setting + "." + direction;
            ScreenSnapshot before = settingScreen(setting, 1000, 1f).withAdjustmentValues(100, 5);
            check.bind("episode", goal, guide("highlight"), before);
            int brightness = setting.equals("brightness") ? (direction.equals("increase") ? 120 : 80) : 100;
            int volume = setting.equals("volume") ? (direction.equals("increase") ? 6 : 4) : 5;
            assertTrue(check.changed("episode", goal, settingScreen(setting, 2000, 1f).withAdjustmentValues(brightness, volume)));
            assertFalse(check.changed("episode", goal, settingScreen(setting, 2000, 1f).withAdjustmentValues(100, 5)));
            assertFalse(check.changed("episode", goal, settingScreen(setting, 2000, 1f)));
        }
    }

    @Test public void persistedChangeInEitherRequestedDirectionAllowsAskingTheUser() throws Exception {
        for (String goal : List.of(INCREASE, "settings.font_size.decrease")) {
            AdjustmentResultCheck check = new AdjustmentResultCheck();
            check.bind("episode", goal, guide("highlight"), screen("com.android.settings", 1000, 1f));
            float after = goal.equals(INCREASE) ? 1.15f : .85f;
            assertTrue(check.changed("episode", goal, screen("com.android.settings", 2000, after)));
            // A recovery redraw must not replace the original setting baseline.
            check.bind("episode", goal, guide("highlight"), screen("com.android.settings", 2000, after));
            assertTrue(check.changed("episode", goal, screen("com.android.settings", 2500, after)));
        }
    }

    @Test public void previewUnchangedWrongDirectionMissingOrStaleValuesDoNotAsk() throws Exception {
        AdjustmentResultCheck check = new AdjustmentResultCheck();
        check.bind("episode", INCREASE, guide("highlight"), screen("com.android.settings", 1000, 1f));
        for (Float value : new Float[]{null, Float.NaN, Float.POSITIVE_INFINITY, 0f, 11f, 1f, .85f})
            assertFalse(check.changed("episode", INCREASE, screen("com.android.settings", 2000, value)));
        assertFalse(check.changed("episode", INCREASE, screen("com.android.settings", 1000, 1.15f)));
        assertFalse(check.changed("other-episode", INCREASE, screen("com.android.settings", 2000, 1.15f)));
        assertFalse(check.changed("episode", INCREASE, screen("com.example.other", 2000, 1.15f)));
        assertFalse(check.changed("episode", "settings.font_size.decrease", screen("com.android.settings", 2000, .85f)));
        check.clear();
        assertFalse(check.changed("episode", INCREASE, screen("com.android.settings", 2000, 1.15f)));
    }

    @Test public void nativeConfirmationCapturesBaselineBeforeUserCanMoveTheSlider() throws Exception {
        AdjustmentResultCheck check = new AdjustmentResultCheck();
        JSONObject command = new JSONObject().put("command_type", "confirmation")
                .put("action_type", "observe_only").put("proposed_action", "drag")
                .put("requires_user_action", true).put("target_node_id", "font")
                .put("target_bounds", new JSONArray(bounds));
        AgentTurnContract.GuideCommand guide = new AgentTurnContract.Response(new JSONObject()
                .put("request_id", "r").put("session_id", "s").put("task_status", "WAITING_CONFIRMATION")
                .put("guide_command", command).toString()).guideCommand;
        check.bind("episode", INCREASE, guide, screen("com.android.settings", 1000, 1f));
        check.bind("episode", INCREASE, guide("highlight"), screen("com.android.settings", 2000, 1.15f));
        assertTrue(check.changed("episode", INCREASE, screen("com.android.settings", 2500, 1.15f)));
    }

    @Test public void onlyAnApprovedFontSliderGuideArmsTheCheck() throws Exception {
        AdjustmentResultCheck check = new AdjustmentResultCheck();
        check.bind("episode", INCREASE, guide("confirmation"), screen("com.android.settings", 1000, 1f));
        assertFalse(check.changed("episode", INCREASE, screen("com.android.settings", 2000, 1.15f)));
        check.bind("episode", "settings.brightness.increase", guide("highlight"), screen("com.android.settings", 1000, 1f));
        assertFalse(check.changed("episode", "settings.brightness.increase", screen("com.android.settings", 2000, 1.15f)));
        check.bind("episode", INCREASE, guide("highlight"), screen("com.android.settings", 1000, null));
        assertFalse(check.changed("episode", INCREASE, screen("com.android.settings", 2000, 1.15f)));
    }

    private ScreenSnapshot settingScreen(String setting, long time, Float scale) {
        String label = setting.equals("volume") ? "미디어" : "밝기";
        AccessibilityNodeSnapshot node = new AccessibilityNodeSnapshot("font", null, true, "com.android.settings",
                label, null, "settings:id/"+setting, "android.widget.SeekBar", "slider", bounds,
                true, true, false, false, false, 3f, 42);
        return new ScreenSnapshot("com.android.settings", Instant.ofEpochMilli(time).toString(), 1080,2400,List.of(node),42,true,scale);
    }

    private ScreenSnapshot screen(String name, long time, Float scale) {
        AccessibilityNodeSnapshot slider = new AccessibilityNodeSnapshot("font", null, true, name,
                "글자 크기", null, "settings:id/font", "android.widget.SeekBar", "slider", bounds,
                true, true, false, false, false, 3f, 42);
        return new ScreenSnapshot(name, Instant.ofEpochMilli(time).toString(), 1080, 2400,
                List.of(slider), 42, true, scale);
    }

    private AgentTurnContract.GuideCommand guide(String type) throws Exception {
        JSONObject guide = new JSONObject().put("guide_command_id","font-guide").put("command_type", type).put("action_type", "drag")
                .put("requires_user_action", true).put("target_node_id", "font")
                .put("target_bounds", new JSONArray(bounds));
        return new AgentTurnContract.Response(new JSONObject().put("request_id", "r").put("session_id", "s")
                .put("task_status", "IN_PROGRESS").put("guide_command", guide).toString()).guideCommand;
    }
}
