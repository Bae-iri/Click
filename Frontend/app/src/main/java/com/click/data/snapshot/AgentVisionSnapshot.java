package com.click.data.snapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** Serializes derived, on-device OCR results. Raw image bytes are intentionally unsupported. */
public final class AgentVisionSnapshot {
    public static final String PROVIDER = "frontend_ocr";
    private static final int MAX_ELEMENTS = 200;
    private static final int MAX_TEXT_LENGTH = 200;

    private AgentVisionSnapshot() {}

    public static JSONObject fromOcr(
            ScreenSnapshot screen,
            boolean userConsented,
            List<VisionOcrElement> recognized,
            long capturedAtMs,
            long processingTimeMs
    ) {
        if (!userConsented) throw new IllegalArgumentException("Vision OCR requires user consent");
        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("user_consented", true);
            snapshot.put("redaction_applied", true);
            snapshot.put("provider", PROVIDER);
            snapshot.put("package_name", screen.packageName);
            snapshot.put("screen_width", screen.screenWidth);
            snapshot.put("screen_height", screen.screenHeight);
            snapshot.put("captured_at_ms", capturedAtMs);
            snapshot.put("processing_time_ms", Math.max(0L, processingTimeMs));
            JSONArray elements = new JSONArray();
            for (VisionOcrElement item : recognized) {
                if (elements.length() >= MAX_ELEMENTS) break;
                if (!item.isValid(screen.screenWidth, screen.screenHeight)) continue;
                JSONObject element = new JSONObject();
                element.put("element_id", item.elementId);
                element.put("text", item.text.length() <= MAX_TEXT_LENGTH
                        ? item.text : item.text.substring(0, MAX_TEXT_LENGTH));
                element.put("bounds_in_screen", new JSONArray(item.bounds));
                if (item.role != null) element.put("role", item.role);
                element.put("confidence", item.confidence);
                element.put("actionable", item.actionable);
                elements.put(element);
            }
            snapshot.put("elements", elements);
            return snapshot;
        } catch (JSONException error) {
            throw new IllegalStateException("vision_snapshot JSON 생성 실패", error);
        }
    }
}
