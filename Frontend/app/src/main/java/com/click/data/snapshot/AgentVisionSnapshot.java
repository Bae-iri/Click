package com.click.data.snapshot;

import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One consented OCR observation. Never serializes screenshots or arbitrary screen text. */
public final class AgentVisionSnapshot {
    private AgentVisionSnapshot() {}

    public static JSONObject fromOcr(ScreenSnapshot screen, Text result, long capturedAtMs) {
        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("user_consented", true);
            snapshot.put("redaction_applied", true);
            snapshot.put("provider", "mlkit_on_device_ocr");
            snapshot.put("observation_id", screen.observationId);
            snapshot.put("active_window_id", screen.activeWindowId);
            snapshot.put("package_name", screen.packageName);
            snapshot.put("screen_width", screen.screenWidth);
            snapshot.put("screen_height", screen.screenHeight);
            snapshot.put("captured_at_ms", capturedAtMs);
            JSONArray elements = new JSONArray();
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    String label = VisionPrivacy.exportLabel(line.getText());
                    Rect bounds = line.getBoundingBox();
                    if (label == null || bounds == null || bounds.isEmpty()
                            || bounds.left < 0 || bounds.top < 0
                            || bounds.right > screen.screenWidth || bounds.bottom > screen.screenHeight) continue;
                    elements.put(new JSONObject().put("element_id", "ocr_" + elements.length())
                            .put("text", label).put("role", "menu_item")
                            .put("actionable", !label.equals("설정") && !label.equals("settings"))
                            .put("bounds_in_screen", new JSONArray(new int[]{
                                    bounds.left, bounds.top, bounds.right, bounds.bottom})));
                }
            }
            snapshot.put("elements", elements);
            return snapshot;
        } catch (JSONException error) {
            throw new IllegalStateException("vision_snapshot JSON 생성 실패", error);
        }
    }
}
