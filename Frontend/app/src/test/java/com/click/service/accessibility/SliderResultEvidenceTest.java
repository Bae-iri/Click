package com.click.service.accessibility;

import com.click.data.snapshot.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SliderResultEvidenceTest {
    private AccessibilityNodeSnapshot slider(String id, String resource, int window, float value) {
        return new AccessibilityNodeSnapshot(id, "root", true, "com.android.settings", null, null,
                resource, "android.widget.SeekBar", "slider", new int[]{10,100,500,150}, false, true,
                false, false, false, value, window, false, 0f, 7f);
    }
    private ScreenSnapshot screen(int window, float scale, AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot("com.android.settings", "1970-01-01T00:00:02Z", 1080, 2400,
                Arrays.asList(nodes), window, true, scale);
    }
    @Test public void recreatedWindowAndReindexedNodeNeedUniqueSliderAndAppliedChange() {
        AccessibilityNodeSnapshot original = slider("old", "com.android.settings:id/seekBarForFontSize", 5, 2);
        AccessibilityNodeSnapshot after = slider("new", original.resourceId, 8, 3);
        ScreenSnapshot before = screen(5, 1f, original);
        assertTrue(SliderResultEvidence.changed(before, screen(8, 1.2f, after), original, 1, 1500));
        assertFalse(SliderResultEvidence.changed(before, screen(8, 1f, after), original, 1, 1500));
        assertFalse(SliderResultEvidence.changed(before, screen(8, 1.2f, after, after), original, 1, 1500));
        assertFalse(SliderResultEvidence.changed(before, screen(8, 1.2f, after), original, 1, 2500));
    }
    @Test public void missingTreeWrongDirectionAndUnrelatedResourceNeverProduceAction() {
        AccessibilityNodeSnapshot original = slider("old", "com.android.settings:id/font_size", 5, 2);
        ScreenSnapshot before = screen(5, 1f, original);
        assertFalse(SliderResultEvidence.changed(before, null, original, 1, 1500));
        assertFalse(SliderResultEvidence.changed(before, screen(5, 1.2f), original, 1, 1500));
        assertFalse(SliderResultEvidence.changed(before, screen(5, 1.2f, slider("old", "brightness", 5, 3)), original, 1, 1500));
        assertFalse(SliderResultEvidence.changed(before, screen(5, .8f, slider("old", original.resourceId, 5, 1)), original, 1, 1500));
    }
}
