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
    public final Boolean checked;
    public final Float progress;
    public final Float rangeMin;
    public final Float rangeMax;
    public final boolean sensitiveInput;
    /** Alias used by the vision safety pipeline. */
    public final boolean sensitive;
    public final String publicBrowseQueryHash;
    /** Native semantics used locally for page continuity, never to authorize an action. */
    public final boolean heading;
    private final boolean searchInput;
    private final boolean passwordInput;
    private final boolean inputContentPresent;

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
        this(nodeId, parentId, visibleToUser, packageName, text, contentDescription, resourceId,
                className, role, bounds, clickable, enabled, selected, scrollable, checkable,
                progress, windowId, sensitiveInput, rangeMin, rangeMax, sensitiveInput);
    }

    public AccessibilityNodeSnapshot(String nodeId, String parentId, boolean visibleToUser,
            String packageName, String text, String contentDescription, String resourceId,
            String className, String role, int[] bounds, boolean clickable, boolean enabled,
            boolean selected, boolean scrollable, boolean checkable, Float progress,
            int windowId, boolean sensitiveInput, Float rangeMin, Float rangeMax, boolean passwordInput) {
        this(nodeId, parentId, visibleToUser, packageName, text, contentDescription, resourceId,
                className, role, bounds, clickable, enabled, selected, scrollable, checkable,
                progress, windowId, sensitiveInput, rangeMin, rangeMax, passwordInput, null);
    }

    public AccessibilityNodeSnapshot(String nodeId, String parentId, boolean visibleToUser,
            String packageName, String text, String contentDescription, String resourceId,
            String className, String role, int[] bounds, boolean clickable, boolean enabled,
            boolean selected, boolean scrollable, boolean checkable, Float progress,
            int windowId, boolean sensitiveInput, Float rangeMin, Float rangeMax, boolean passwordInput,
            Boolean checked) {
        this(nodeId, parentId, visibleToUser, packageName, text, contentDescription, resourceId,
                className, role, bounds, clickable, enabled, selected, scrollable, checkable,
                progress, windowId, sensitiveInput, rangeMin, rangeMax, passwordInput, checked, false, false);
    }

    public AccessibilityNodeSnapshot(String nodeId, String parentId, boolean visibleToUser,
            String packageName, String text, String contentDescription, String resourceId,
            String className, String role, int[] bounds, boolean clickable, boolean enabled,
            boolean selected, boolean scrollable, boolean checkable, Float progress,
            int windowId, boolean sensitiveInput, Float rangeMin, Float rangeMax, boolean passwordInput,
            Boolean checked, boolean heading, boolean searchInput) {
        this.heading = !sensitiveInput && heading;
        this.searchInput = sensitiveInput && !passwordInput && searchInput;
        this.checked = !sensitiveInput && checkable ? checked : null;
        this.passwordInput = passwordInput;
        this.publicBrowseQueryHash = CoupangBrowsePrivacy.captureQueryHash(packageName,resourceId,text,passwordInput);
        boolean searchIdentity = searchInput || sensitiveInput && !passwordInput
                && ScreenEnvironmentPolicy.contains("empty_search_resources", resourceId);
        String description = contentDescription == null ? "" : contentDescription.trim().toLowerCase(java.util.Locale.ROOT);
        boolean searchPrompt = searchIdentity && ("검색".equals(description) || "search".equals(description)
                || Gov24LoginPrivacy.PACKAGE.equals(packageName) && Gov24LoginPrivacy.isPublicSearchHint(contentDescription));
        this.inputContentPresent = sensitiveInput && (text != null && !text.isEmpty()
                || contentDescription != null && !contentDescription.isEmpty() && !searchPrompt);
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

    public boolean passwordInput() { return passwordInput; }
    boolean emptySearchInput() {
        return sensitiveInput && !passwordInput && !inputContentPresent
                && (searchInput || ScreenEnvironmentPolicy.contains("empty_search_resources",resourceId));
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
        if (heading) extras.put("heading", true);
        // Only a boolean native fact leaves the collector; hints and query text stay local.
        if (emptySearchInput()) extras.put("empty_search_input", true);
        if (checked != null) extras.put("checked", checked);
        if (sensitiveInput) extras.put("editable", true);
        if (passwordInput) extras.put("is_password", true);
        if (inputContentPresent) extras.put("input_content_present", true);
        // Keep native units locally for action validation. Oversized OEM ranges
        // travel as a ratio so the finite numeric privacy boundary keeps all three values.
        boolean normalizedRange = "slider".equals(role) && progress != null && rangeMin != null && rangeMax != null
                && Float.isFinite(progress) && Float.isFinite(rangeMin) && Float.isFinite(rangeMax)
                && rangeMin < rangeMax && progress >= rangeMin && progress <= rangeMax
                && (Math.abs((double) rangeMin) > 100_000_000 || Math.abs((double) rangeMax) > 100_000_000);
        if (normalizedRange) {
            extras.put("progress", ((double) progress - rangeMin) / ((double) rangeMax - rangeMin));
            extras.put("min", 0); extras.put("max", 1);
        } else {
            if (progress != null) extras.put("progress", progress);
            if (rangeMin != null) extras.put("min", rangeMin);
            if (rangeMax != null) extras.put("max", rangeMax);
        }
        object.put("extras", extras);
        return object;
    }

    private static Object nullable(String value) {
        return value == null ? JSONObject.NULL : value;
    }
}
