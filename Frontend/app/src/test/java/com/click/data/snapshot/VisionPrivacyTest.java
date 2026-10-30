package com.click.data.snapshot;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

public class VisionPrivacyTest {
    private VisionPrivacy privacy;

    @Before public void loadSharedVocabulary() throws Exception {
        try (java.io.InputStream source = getClass().getResourceAsStream("/vision_vocabulary.json")) {
            assertNotNull(source);
            privacy = VisionPrivacy.load(source);
        }
    }

    @Test public void exportsOnlyKnownLabels() {
        assertEquals("font size", privacy.exportLabel("  Font   Size  ", "font_size"));
        assertEquals("디스플레이", privacy.exportLabel("디스플레이", "font_size"));
    }
    @Test public void scopesLabelsToCurrentGoalAndRejectsUnknownGoals() {
        assertEquals("wi-fi", privacy.exportLabel("Wi-Fi", "wifi"));
        assertEquals("bluetooth", privacy.exportLabel("Bluetooth", "bluetooth"));
        assertEquals("밝기", privacy.exportLabel("밝기", "brightness"));
        assertNull(privacy.exportLabel("Wi-Fi", "font_size"));
        assertNull(privacy.exportLabel("글자 크기", "wifi"));
        assertNull(privacy.exportLabel("설정", "unknown"));
        assertNull(privacy.exportLabel("settings", null));
        assertNull(privacy.exportLabel("Home Wi-Fi", "wifi"));
        assertNull(privacy.exportLabel("김철수의 이어폰", "bluetooth"));
    }
    @Test public void dropsNamesAddressesAndCredentials() {
        for (String text : new String[]{"김철수", "서울시 강남구 주소", "test@example.com",
                "010-1234-5678", "900101-1234567", "1234 5678 9012 3456", "OTP: 123456"}) {
            assertNull(privacy.exportLabel(text, "font_size"));
        }
    }
    @Test public void dropsEntireMixedLabelInsteadOfMakingItActionable() {
        assertNull(privacy.exportLabel("디스플레이 김철수", "font_size"));
        assertNull(privacy.exportLabel("설정 test@example.com", "font_size"));
    }
    @Test(expected=IllegalArgumentException.class) public void missingOcrTextFailsClosed() {
        privacy.exportLabel(null, "font_size");
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
