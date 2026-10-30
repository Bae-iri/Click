package com.click.data.snapshot;

import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppliedSettingsTest {
    private ScreenSnapshot snapshot() {
        return new ScreenSnapshot("com.android.settings", "2026-10-30T00:00:00Z",
                1080, 2400, Collections.emptyList(), 42, true, 1.15f);
    }

    @Test public void revisionCopiesAllDeviceEvidenceWithItsSource() throws Exception {
        ScreenSnapshot original = snapshot().withAdjustmentValues(128, 7);
        ScreenSnapshot revised = original.withScreenRevision("revision");
        JSONObject settings = new JSONObject(revised.toJson()).getJSONObject("applied_settings");
        assertEquals(128, settings.getInt("screen_brightness"));
        assertEquals(7, settings.getInt("media_volume"));
        assertEquals("android.provider.Settings.System.SCREEN_BRIGHTNESS", settings.getString("brightness_source"));
        assertEquals("android.media.AudioManager.STREAM_MUSIC", settings.getString("media_volume_source"));
        assertEquals(1.15, settings.getDouble("font_scale"), .001);
        assertEquals(original.contentFingerprint(), revised.contentFingerprint());
        assertNotEquals(original.contentFingerprint(), original.withAdjustmentValues(129, 7).contentFingerprint());
        assertNotEquals(original.contentFingerprint(), original.withAdjustmentValues(128, 8).contentFingerprint());
    }

    @Test public void unavailableOrInvalidValuesAreNotReportedAsDeviceEvidence() throws Exception {
        for (ScreenSnapshot value : new ScreenSnapshot[]{snapshot(), snapshot().withAdjustmentValues(-1, -1),
                snapshot().withAdjustmentValues(65536, 1001)}) {
            JSONObject settings = new JSONObject(value.toJson()).getJSONObject("applied_settings");
            assertFalse(settings.has("screen_brightness"));
            assertFalse(settings.has("brightness_source"));
            assertFalse(settings.has("media_volume"));
            assertFalse(settings.has("media_volume_source"));
        }
    }
}
