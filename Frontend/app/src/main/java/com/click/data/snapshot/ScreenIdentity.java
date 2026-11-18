package com.click.data.snapshot;

import java.util.*;
import org.json.*;

/** Page identity is for read-only continuity. It never authorizes a tap or a value change. */
public final class ScreenIdentity {
    private ScreenIdentity() {}
    public static String pageTitle(ScreenSnapshot screen) {
        if(screen==null || screen.activeWindowId==null || !screen.privacyMaskingReady)return null;
        Map<String, AccessibilityNodeSnapshot> byId = new HashMap<>();
        for (AccessibilityNodeSnapshot n : screen.nodes) {
            if (n.visibleToUser && n.sensitiveInput && !n.emptySearchInput()) return null;
            byId.put(n.nodeId, n);
        }
        AccessibilityNodeSnapshot top = null;
        for (AccessibilityNodeSnapshot n : screen.nodes) {
            if (!n.visibleToUser || !n.enabled || n.clickable || n.scrollable || n.checkable
                    || !screen.packageName.equals(n.packageName) || !Objects.equals(screen.activeWindowId, n.windowId)
                    || !(n.heading || "android.widget.TextView".equals(n.className) || toolbarTitle(n.resourceId))
                    || titleText(n) == null
                    || n.bounds == null || n.bounds.length != 4 || n.bounds[0] >= n.bounds[2] || n.bounds[1] >= n.bounds[3]
                    || SettingsCapabilities.privateIdentity(n.resourceId)
                    || n.resourceId != null && (n.resourceId.endsWith("/summary") || n.resourceId.endsWith("/description"))) continue;
            java.util.Set<String> seen = new java.util.HashSet<>();seen.add(n.nodeId);
            String parent = n.parentId;boolean independent = true;
            while (parent != null) {
                if (!seen.add(parent)) { independent=false;break; }
                AccessibilityNodeSnapshot ancestor = byId.get(parent);
                if (ancestor == null) { independent=false;break; }
                if (ancestor.clickable || ancestor.checkable || ancestor.scrollable
                        || !screen.packageName.equals(ancestor.packageName) || !Objects.equals(screen.activeWindowId, ancestor.windowId)
                        || SettingsCapabilities.privateIdentity(ancestor.resourceId) || "list".equals(ancestor.role)
                        || !ancestor.visibleToUser || !ancestor.enabled) { independent=false;break; }
                parent=ancestor.parentId;
            }
            if (independent && (top == null || n.heading && !top.heading
                    || n.heading == top.heading && (n.bounds[1] < top.bounds[1]
                        || n.bounds[1] == top.bounds[1] && n.bounds[0] < top.bounds[0]))) top=n;
        }
        if (top == null) return null;
        return SettingsCapabilities.canonical(titleText(top));
    }
    private static String titleText(AccessibilityNodeSnapshot node) {
        if (node.text != null && !node.text.isBlank()) return node.text;
        return node.heading && node.contentDescription != null && !node.contentDescription.isBlank()
                ? node.contentDescription : null;
    }
    private static boolean toolbarTitle(String resource) {
        return resource != null && (resource.endsWith("/action_bar_title") || resource.endsWith("/toolbar_title")
                || resource.endsWith("/collapsing_toolbar_title"));
    }
    public static boolean samePage(ScreenSnapshot before, ScreenSnapshot after) {
        String title=pageTitle(before);
        return title!=null && after!=null && Objects.equals(before.packageName,after.packageName)
                && title.equals(pageTitle(after));
    }
    /** Exact current layout for scrolling/navigation, excluding persisted values and control values.
     * Window, node identities, native labels, bounds and structure remain part of this identity. */
    public static String navigationFingerprint(ScreenSnapshot screen) {
        if(screen==null)return null;
        try {
            JSONObject content=new JSONObject(screen.toJson());
            for(String key:new String[]{"captured_at","captured_at_ms","observation_id","screen_revision","applied_settings"})content.remove(key);
            JSONArray nodes=content.getJSONArray("nodes");
            for(int i=0;i<nodes.length();i++) {
                JSONObject node=nodes.getJSONObject(i);
                node.remove("selected");
                JSONObject extras=node.optJSONObject("extras");
                if(extras!=null){extras.remove("checked");extras.remove("progress");}
                // A slider's numerical value is evidence for result verification, not page navigation.
                if("slider".equals(node.optString("role"))) {
                    for(String key:new String[]{"text","content_description"}) {
                        String value=node.optString(key,"");
                        if(value.matches("[\\d\\s.,%+−-]+"))node.remove(key);
                    }
                }
            }
            return ScreenSnapshot.digest(content.toString()+"|"+screen.privacyMaskingReady);
        } catch(JSONException error){throw new IllegalStateException(error);}
    }
}
