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

public class FontSizeResultCheckTest {
    private final int[] bounds = {20, 100, 900, 180};
    private static final String INCREASE = "settings.font_size.increase";

    @Test public void persistedChangeInEitherRequestedDirectionAllowsAskingTheUser() throws Exception {
        for (String goal : List.of(INCREASE, "settings.font_size.decrease")) {
            FontSizeResultCheck check = new FontSizeResultCheck();
            check.bind("episode", goal, guide("highlight"), screen("com.android.settings", 1000, 1f));
            float after = goal.equals(INCREASE) ? 1.15f : .85f;
            assertTrue(check.changed("episode", goal, screen("com.android.settings", 2000, after)));
            // A recovery redraw must not replace the original setting baseline.
            check.bind("episode", goal, guide("highlight"), screen("com.android.settings", 2000, after));
            assertTrue(check.changed("episode", goal, screen("com.android.settings", 2500, after)));
        }
    }

    @Test public void previewUnchangedWrongDirectionMissingOrStaleValuesDoNotAsk() throws Exception {
        FontSizeResultCheck check = new FontSizeResultCheck();
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

    @Test public void onlyAnApprovedFontSliderGuideArmsTheCheck() throws Exception {
        FontSizeResultCheck check = new FontSizeResultCheck();
        check.bind("episode", INCREASE, guide("confirmation"), screen("com.android.settings", 1000, 1f));
        assertFalse(check.changed("episode", INCREASE, screen("com.android.settings", 2000, 1.15f)));
        check.bind("episode", "settings.brightness.increase", guide("highlight"), screen("com.android.settings", 1000, 1f));
        assertFalse(check.changed("episode", "settings.brightness.increase", screen("com.android.settings", 2000, 1.15f)));
        check.bind("episode", INCREASE, guide("highlight"), screen("com.android.settings", 1000, null));
        assertFalse(check.changed("episode", INCREASE, screen("com.android.settings", 2000, 1.15f)));
    }

    private ScreenSnapshot screen(String name, long time, Float scale) {
        AccessibilityNodeSnapshot slider = new AccessibilityNodeSnapshot("font", null, true, name,
                "글자 크기", null, "settings:id/font", "android.widget.SeekBar", "slider", bounds,
                true, true, false, false, false, 3f, 42);
        return new ScreenSnapshot(name, Instant.ofEpochMilli(time).toString(), 1080, 2400,
                List.of(slider), 42, true, scale);
    }

    private AgentTurnContract.GuideCommand guide(String type) throws Exception {
        JSONObject guide = new JSONObject().put("command_type", type).put("action_type", "drag")
                .put("requires_user_action", true).put("target_node_id", "font")
                .put("target_bounds", new JSONArray(bounds));
        return new AgentTurnContract.Response(new JSONObject().put("request_id", "r").put("session_id", "s")
                .put("task_status", "IN_PROGRESS").put("guide_command", guide).toString()).guideCommand;
    }
}
