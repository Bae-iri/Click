package com.click.data.snapshot;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisionPrivacyTest {
    @Test public void exportsOnlyKnownLabels() {
        assertEquals("font size", VisionPrivacy.exportLabel("  Font   Size  "));
        assertEquals("디스플레이", VisionPrivacy.exportLabel("디스플레이"));
    }
    @Test public void dropsNamesAddressesAndCredentials() {
        for (String text : new String[]{"김철수", "서울시 강남구 주소", "test@example.com",
                "010-1234-5678", "900101-1234567", "1234 5678 9012 3456", "OTP: 123456"}) {
            assertNull(VisionPrivacy.exportLabel(text));
        }
    }
    @Test public void dropsEntireMixedLabelInsteadOfMakingItActionable() {
        assertNull(VisionPrivacy.exportLabel("디스플레이 김철수"));
        assertNull(VisionPrivacy.exportLabel("설정 test@example.com"));
    }
    @Test(expected=IllegalArgumentException.class) public void missingOcrTextFailsClosed() {
        VisionPrivacy.exportLabel(null);
    }
    @Test public void acceptsValidMaskBounds() {
        VisionPrivacy.requireMaskBounds(new int[]{0, 0, 1080, 2400}, 1080, 2400);
    }
    @Test(expected=IllegalArgumentException.class) public void unknownMaskAreaFailsClosed() {
        VisionPrivacy.requireMaskBounds(null, 1080, 2400);
    }
    @Test(expected=IllegalArgumentException.class) public void emptyMaskAreaFailsClosed() {
        VisionPrivacy.requireMaskBounds(new int[]{10, 10, 10, 20}, 1080, 2400);
    }
    @Test(expected=IllegalArgumentException.class) public void partialMaskOutsideScreenFailsClosed() {
        VisionPrivacy.requireMaskBounds(new int[]{0, 0, 1081, 2400}, 1080, 2400);
    }
}
