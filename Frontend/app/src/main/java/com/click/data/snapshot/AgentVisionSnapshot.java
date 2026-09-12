package com.click.data.snapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds a {@code vision_snapshot} payload from an on-device accessibility capture.
 *
 * <p>Only derived UI elements are sent (never a raw screenshot), which is the path the
 * FE/BE contract prefers ("가능하면 FE에서 먼저 마스킹 또는 온디바이스 OCR을 수행하고,
 * elements만 전달한다"). Because no image leaves the device, {@code redaction_applied}
 * is always {@code true} and image-analysis consent is not strictly required, but the
 * caller still passes through {@code userConsented} so the BE can see it.
 */
public final class AgentVisionSnapshot {
    private AgentVisionSnapshot() {}

    public static JSONObject fromScreen(ScreenSnapshot screen, boolean userConsented) {
        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("user_consented", userConsented);
            snapshot.put("redaction_applied", true);
            snapshot.put("provider", "frontend_tree");
            snapshot.put("package_name", screen.packageName);
            snapshot.put("screen_width", screen.screenWidth);
            snapshot.put("screen_height", screen.screenHeight);
            snapshot.put("captured_at_ms", System.currentTimeMillis());
            JSONArray elements = new JSONArray();
            for (AccessibilityNodeSnapshot node : screen.nodes) {
                if (!node.visibleToUser || !node.enabled || node.bounds == null
                        || node.bounds.length != 4 || node.bounds[0] >= node.bounds[2]
                        || node.bounds[1] >= node.bounds[3]) continue;
                String label = node.text != null ? node.text : node.contentDescription;
                if (label == null || label.trim().isEmpty()) continue;
                JSONObject element = new JSONObject();
                element.put("element_id", node.nodeId);
                element.put("text", label);
                if (node.bounds != null && node.bounds.length == 4) {
                    element.put("bounds_in_screen", new JSONArray(node.bounds));
                }
                if (node.role != null) element.put("role", node.role);
                element.put("confidence", 1.0);
                element.put("actionable", node.clickable || node.scrollable || node.checkable);
                elements.put(element);
            }
            snapshot.put("elements", elements);
            return snapshot;
        } catch (JSONException error) {
            throw new IllegalStateException("vision_snapshot JSON 생성 실패", error);
        }
    }
}
