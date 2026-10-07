package com.click.data.snapshot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class VisionFallbackTest {
    @Test public void blocksPasswordAndPaymentScreensBeforeCapture() {
        ScreenSnapshot password = screen("com.android.settings",
                node("secret", "비밀번호", new int[]{0, 100, 500, 180}, true, true));
        assertFalse(VisionSafetyPolicy.assess(password).allowed);
        assertEquals("password_or_secret_input", VisionSafetyPolicy.assess(password).reason);

        ScreenSnapshot payment = screen("com.store.app",
                node("pay", "카드 번호", new int[]{0, 100, 500, 180}, false, false));
        assertFalse(VisionSafetyPolicy.assess(payment).allowed);
        assertEquals("sensitive_screen_content", VisionSafetyPolicy.assess(payment).reason);

        assertFalse(VisionSafetyPolicy.assess(screen("com.android.systemui")).allowed);
        assertFalse(VisionSafetyPolicy.assess(screen("com.google.android.inputmethod.latin")).allowed);
    }

    @Test public void allowsOrdinarySettingsScreen() {
        ScreenSnapshot settings = screen("com.android.settings",
                node("display", "디스플레이", new int[]{0, 100, 500, 180}, true, false));
        assertTrue(VisionSafetyPolicy.assess(settings).allowed);
    }

    @Test public void accountCardIsRedactedInsteadOfBlockingOrdinarySettings() {
        ScreenSnapshot settings = screen("com.android.settings",
                node("account", "삼성 계정", new int[]{0, 100, 500, 180}, true, false),
                node("display", "디스플레이", new int[]{0, 220, 500, 300}, true, false));
        assertTrue(VisionSafetyPolicy.assess(settings).allowed);
    }

    @Test public void mapsCoordinatesFiltersConfidenceAndUsesClickableContainer() {
        AccessibilityNodeSnapshot row = node("display-row", null,
                new int[]{20, 180, 1060, 360}, true, false);
        ScreenSnapshot screen = screen("com.android.settings", row);
        List<VisionElementMapper.OcrLine> lines = Arrays.asList(
                new VisionElementMapper.OcrLine("디스플레이", new int[]{40, 100, 500, 150}, 0.93f),
                new VisionElementMapper.OcrLine("noise", new int[]{10, 10, 20, 20}, 0.2f));

        List<VisionOcrElement> mapped = VisionElementMapper.map(lines, 1080, 1200, screen);

        assertEquals(1, mapped.size());
        assertEquals("디스플레이", mapped.get(0).text);
        assertTrue(mapped.get(0).actionable);
        assertArrayEquals(row.bounds, mapped.get(0).bounds);
    }

    @Test public void ocrTextWithoutClickableParentRemainsGuideEligible() {
        AccessibilityNodeSnapshot label = node("display-label", "디스플레이",
                new int[]{40, 200, 400, 280}, false, false);
        ScreenSnapshot screen = screen("com.android.settings", label);
        List<VisionOcrElement> mapped = VisionElementMapper.map(
                Collections.singletonList(new VisionElementMapper.OcrLine(
                        "디스플레이", new int[]{40, 100, 400, 140}, 0.92f)),
                1080, 1200, screen);
        assertEquals(1, mapped.size());
        assertTrue(mapped.get(0).actionable);
        assertArrayEquals(label.bounds, mapped.get(0).bounds);
    }

    @Test public void payloadContainsOnlyDerivedOcrAndRequiresConsent() throws Exception {
        ScreenSnapshot screen = screen("com.android.settings",
                node("display", "디스플레이", new int[]{20, 180, 1060, 360}, true, false));
        VisionOcrElement element = new VisionOcrElement("ocr_0", "디스플레이",
                new int[]{20, 180, 1060, 360}, "menu_item", 0.9f, true);
        JSONObject payload = AgentVisionSnapshot.fromOcr(
                screen, true, Collections.singletonList(element), 1234L, 45L);

        assertEquals("frontend_ocr", payload.getString("provider"));
        assertEquals(45L, payload.getLong("processing_time_ms"));
        assertFalse(payload.has("screenshot_base64"));
        assertFalse(payload.has("ocr_text"));
        assertEquals(1, payload.getJSONArray("elements").length());

        boolean rejected = false;
        try {
            AgentVisionSnapshot.fromOcr(screen, false,
                    Collections.singletonList(element), 1234L, 45L);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test public void rejectsUnknownOcrConfidence() {
        ScreenSnapshot screen = screen("com.android.settings");
        List<VisionElementMapper.OcrLine> lines = Collections.singletonList(
                new VisionElementMapper.OcrLine("디스플레이", new int[]{20, 40, 400, 100}, null));
        assertTrue(VisionElementMapper.map(lines, 1080, 2400, screen).isEmpty());
    }

    private static ScreenSnapshot screen(String packageName, AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot(packageName, "2026-10-07T00:00:00Z",
                1080, 2400, Arrays.asList(nodes), 1);
    }

    private static AccessibilityNodeSnapshot node(
            String id, String text, int[] bounds, boolean clickable, boolean sensitive) {
        return new AccessibilityNodeSnapshot(id, null, true, "com.android.settings",
                text, null, null, "android.widget.TextView",
                clickable ? "menu_item" : "text", bounds, clickable, true,
                false, false, false, null, 1, sensitive);
    }
}
