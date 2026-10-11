package com.click.data.snapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class AccessibilityNodeSnapshot {
    public final String nodeId;
    public final int windowId;
    public final String parentId;
    public final boolean visibleToUser;
    public final String packageName;
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
    public final Float progress;
    public final Float rangeMin;
    public final Float rangeMax;
    public final boolean sensitiveInput;
    /** Alias used by the vision safety pipeline. */
    public final boolean sensitive;

    public AccessibilityNodeSnapshot(
            String nodeId,
            String parentId,
            boolean visibleToUser,
            String packageName,
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
            boolean checkable,
            Float progress,
            int windowId
    ) {
        this(nodeId, parentId, visibleToUser, packageName, text, contentDescription, resourceId,
                className, role, bounds, clickable, enabled, selected, scrollable, checkable,
                progress, windowId, false);
    }

    public AccessibilityNodeSnapshot(String nodeId, String parentId, boolean visibleToUser,
            String packageName, String text, String contentDescription, String resourceId,
            String className, String role, int[] bounds, boolean clickable, boolean enabled,
            boolean selected, boolean scrollable, boolean checkable, Float progress,
            int windowId, boolean sensitiveInput) {
        this(nodeId, parentId, visibleToUser, packageName, text, contentDescription, resourceId,
                className, role, bounds, clickable, enabled, selected, scrollable, checkable,
                progress, windowId, sensitiveInput, null, null);
    }

    public AccessibilityNodeSnapshot(String nodeId, String parentId, boolean visibleToUser,
            String packageName, String text, String contentDescription, String resourceId,
            String className, String role, int[] bounds, boolean clickable, boolean enabled,
            boolean selected, boolean scrollable, boolean checkable, Float progress,
            int windowId, boolean sensitiveInput, Float rangeMin, Float rangeMax) {
        this.rangeMin = sensitiveInput ? null : rangeMin;
        this.rangeMax = sensitiveInput ? null : rangeMax;
        this.sensitiveInput = sensitiveInput;
        this.sensitive = sensitiveInput;
        this.nodeId = nodeId;
        this.windowId = windowId;
        this.parentId = parentId;
        this.visibleToUser = visibleToUser;
        this.packageName = packageName;
        this.text = sensitiveInput ? null : text;
        this.contentDescription = sensitiveInput ? null : contentDescription;
        this.resourceId = resourceId;
        this.className = className;
        this.role = role;
        this.bounds = bounds;
        this.clickable = !sensitiveInput && clickable;
        this.enabled = !sensitiveInput && enabled;
        this.selected = selected;
        this.scrollable = scrollable;
        this.checkable = checkable;
        this.progress = sensitiveInput ? null : progress;
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("node_id", nodeId);
        object.put("window_id", windowId);
        object.put("parent_id", nullable(parentId));
        object.put("visible_to_user", visibleToUser);
        object.put("package_name", nullable(packageName));
        object.put("text", nullable(text));
        object.put("content_description", nullable(contentDescription));
        object.put("resource_id", nullable(resourceId));
        object.put("class_name", nullable(className));
        object.put("role", nullable(role));
        object.put("bounds_in_screen", new JSONArray(bounds));
        object.put("clickable", clickable);
        object.put("enabled", enabled);
        object.put("selected", selected);
        object.put("scrollable", scrollable);
        object.put("checkable", checkable);
        JSONObject extras = new JSONObject();
        if (sensitiveInput) extras.put("editable", true);
        if (sensitive) extras.put("is_password", true);
        if (progress != null) extras.put("progress", progress);
        if (rangeMin != null) extras.put("min", rangeMin);
        if (rangeMax != null) extras.put("max", rangeMax);
        object.put("extras", extras);
        return object;
    }

    private static Object nullable(String value) {
        return value == null ? JSONObject.NULL : value;
    }
}
