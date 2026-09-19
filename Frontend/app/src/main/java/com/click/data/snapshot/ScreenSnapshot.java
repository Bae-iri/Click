package com.click.data.snapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

public final class ScreenSnapshot {
    public final String packageName;
    public final Integer activeWindowId;
    public final String capturedAt;
    public final String observationId = java.util.UUID.randomUUID().toString();
    public final int screenWidth;
    public final int screenHeight;
    public final List<AccessibilityNodeSnapshot> nodes;

    public ScreenSnapshot(
            String packageName,
            String capturedAt,
            int screenWidth,
            int screenHeight,
            List<AccessibilityNodeSnapshot> nodes
    ) {
        this(packageName, capturedAt, screenWidth, screenHeight, nodes, null);
    }

    public ScreenSnapshot(String packageName, String capturedAt, int screenWidth, int screenHeight,
                          List<AccessibilityNodeSnapshot> nodes, Integer activeWindowId) {
        this.activeWindowId = activeWindowId;
        this.packageName = packageName;
        this.capturedAt = capturedAt;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.nodes = Collections.unmodifiableList(nodes);
    }

    public String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("package_name", packageName);
            if (activeWindowId != null) object.put("active_window_id", activeWindowId);
            object.put("captured_at", capturedAt);
            object.put("observation_id", observationId);
            object.put("screen_width", screenWidth);
            object.put("screen_height", screenHeight);
            JSONArray nodeArray = new JSONArray();
            for (AccessibilityNodeSnapshot node : nodes) nodeArray.put(node.toJson());
            object.put("nodes", nodeArray);
            return object.toString();
        } catch (JSONException error) {
            throw new IllegalStateException("ScreenSnapshot JSON 생성 실패", error);
        }
    }

    public String fingerprint() {
        StringBuilder value = new StringBuilder(packageName).append('|').append(screenWidth)
                .append('x').append(screenHeight).append('|').append(activeWindowId);
        for (AccessibilityNodeSnapshot node : nodes) {
            value.append('|').append(node.nodeId).append(':').append(node.text)
                    .append(':').append(node.contentDescription).append(':').append(node.className)
                    .append(':').append(java.util.Arrays.toString(node.bounds))
                    .append(':').append(node.parentId).append(':').append(node.visibleToUser)
                    .append(':').append(node.clickable).append(':').append(node.enabled)
                    .append(':').append(node.scrollable).append(':').append(node.checkable)
                    .append(':').append(node.selected).append(':').append(node.progress);
        }
        return Integer.toHexString(value.toString().hashCode());
    }
}
