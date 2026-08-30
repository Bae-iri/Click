package com.click.data.snapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class AccessibilityNodeSnapshot {
    public final String nodeId;
    public final String text;
    public final String contentDescription;
    public final String resourceId;
    public final String className;
    public final String role;
    public final int[] bounds;
    public final boolean clickable;
    public final boolean enabled;
    public final boolean selected;
    public final boolean scrollable;
    public final boolean checkable;

    public AccessibilityNodeSnapshot(
            String nodeId,
            String text,
            String contentDescription,
            String resourceId,
            String className,
            String role,
            int[] bounds,
            boolean clickable,
            boolean enabled,
            boolean selected,
            boolean scrollable,
            boolean checkable
    ) {
        this.nodeId = nodeId;
        this.text = text;
        this.contentDescription = contentDescription;
        this.resourceId = resourceId;
        this.className = className;
        this.role = role;
        this.bounds = bounds;
        this.clickable = clickable;
        this.enabled = enabled;
        this.selected = selected;
        this.scrollable = scrollable;
        this.checkable = checkable;
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("node_id", nodeId);
        object.put("text", nullable(text));
        object.put("content_description", nullable(contentDescription));
        object.put("resource_id", nullable(resourceId));
        object.put("class_name", nullable(className));
        object.put("role", nullable(role));
        object.put("bounds", new JSONArray(bounds));
        object.put("clickable", clickable);
        object.put("enabled", enabled);
        object.put("selected", selected);
        object.put("scrollable", scrollable);
        object.put("checkable", checkable);
        object.put("extras", new JSONObject());
        return object;
    }

    private static Object nullable(String value) {
        return value == null ? JSONObject.NULL : value;
    }
}
