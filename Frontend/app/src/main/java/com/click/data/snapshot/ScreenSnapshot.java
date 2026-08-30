package com.click.data.snapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

public final class ScreenSnapshot {
    public final String packageName;
    public final String capturedAt;
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
            object.put("captured_at", capturedAt);
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
}
