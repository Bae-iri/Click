package com.click.service.overlay;

import com.click.data.remote.AgentActionEvents;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.Arrays;
import org.json.JSONObject;

/** Binds real accessibility events to the exact displayed guide, never to spoken claims. */
public final class GuidedActionTracker {
    private String session, episode, guide, action;
    private AccessibilityNodeSnapshot target;
    private int[] sliderTrackBounds;
    private long issuedAt;
    private boolean consumed;
    private final java.util.Map<String, AccessibilityNodeSnapshot> tapSources = new java.util.HashMap<>();

    public void bind(String session, String episode, String guide, String action,
                     String nodeId, int[] bounds, ScreenSnapshot screen, long issuedAt) {
        if (guide != null && guide.equals(this.guide) && java.util.Objects.equals(session, this.session)
                && java.util.Objects.equals(episode, this.episode)) return;
        clear();
        if (screen == null || nodeId == null || bounds == null) return;
        for (AccessibilityNodeSnapshot node : screen.nodes) {
            if (nodeId.equals(node.nodeId) && Arrays.equals(bounds, node.bounds)) {
                this.target = node; break;
            }
        }
        if (target == null) return;
        tapSources.put(target.nodeId, target);
        if ("tap".equals(action)) {
            for (AccessibilityNodeSnapshot child : screen.nodes) {
                if (child.visibleToUser && child.enabled && !child.sensitiveInput && !child.clickable
                        && !child.scrollable && !child.checkable && child.progress == null
                        && child.windowId == target.windowId && java.util.Objects.equals(child.packageName, target.packageName)
                        && contains(target.bounds, child.bounds) && descendant(screen, child, target.nodeId))
                    tapSources.put(child.nodeId, child);
            }
            AccessibilityNodeSnapshot click = com.click.service.accessibility.TouchAutomationPolicy.clickableTarget(
                    screen, nodeId, bounds);
            if (click != null) tapSources.put(click.nodeId, click);
        }
        // Some native sliders expose a moving thumb as the ranged child of a
        // stationary SeekBar. Retain that exact parent track for range events.
        if ("slider".equals(target.role) && target.parentId != null) {
            for (AccessibilityNodeSnapshot parent : screen.nodes) {
                if (target.parentId.equals(parent.nodeId) && "slider".equals(parent.role)
                        && parent.progress == null && parent.windowId == target.windowId
                        && java.util.Objects.equals(parent.packageName, target.packageName)
                        && contains(parent.bounds, target.bounds)) sliderTrackBounds = parent.bounds.clone();
            }
        }
        this.session = session; this.episode = episode; this.guide = guide;
        this.action = action; this.issuedAt = issuedAt;
    }

    public JSONObject record(String type, String packageName, int window, String nodeId,
                             String resourceId, int[] bounds, long occurredAt) {
        if ("tap".equals(action) && AgentActionEvents.TAP_COMPLETED.equals(type)) {
            AccessibilityNodeSnapshot source = tapSources.get(nodeId);
            if (source == null || !java.util.Objects.equals(source.resourceId, resourceId)
                    || !Arrays.equals(source.bounds, bounds)) return null;
            nodeId = target.nodeId; resourceId = target.resourceId; bounds = target.bounds;
        }
        if (target == null || consumed || occurredAt <= issuedAt || !java.util.Objects.equals(target.packageName, packageName)
                || target.windowId != window || !target.nodeId.equals(nodeId)
                || !java.util.Objects.equals(target.resourceId, resourceId)
                || !Arrays.equals(target.bounds, bounds)) return null;
        boolean expected = "tap".equals(action) && AgentActionEvents.TAP_COMPLETED.equals(type)
                || "drag".equals(action) && (AgentActionEvents.DRAG_COMPLETED.equals(type)
                    || AgentActionEvents.TAP_COMPLETED.equals(type));
        if (!expected) return null;
        JSONObject event = AgentActionEvents.of(type, guide, target.nodeId, target.bounds, occurredAt);
        try { event.put("session_id", session); event.put("episode_id", episode); }
        catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        consumed = true;
        return event;
    }

    /** A native range-value event can arrive without touch-exploration start/end events. */
    public JSONObject recordRangeChange(String packageName, int window, String nodeId,
                                        String resourceId, int[] bounds, long occurredAt,
                                        float value, float minimum, float maximum) {
        if (target == null || !"drag".equals(action) || !"slider".equals(target.role)
                || target.progress == null || !Float.isFinite(target.progress)
                || !Float.isFinite(value) || !Float.isFinite(minimum) || !Float.isFinite(maximum)
                || minimum >= maximum || value < minimum || value > maximum
                || target.rangeMin == null || target.rangeMax == null
                || Float.compare(target.rangeMin, minimum) != 0
                || Float.compare(target.rangeMax, maximum) != 0
                || Float.compare(target.progress, value) == 0) return null;
        if (!Arrays.equals(target.bounds, bounds)) {
            if (sliderTrackBounds == null || !contains(sliderTrackBounds, bounds)
                    || bounds[1] != target.bounds[1] || bounds[3] != target.bounds[3]
                    || bounds[2] - bounds[0] != target.bounds[2] - target.bounds[0]) return null;
            // Correlate with the issued guide's rectangle, while identity and
            // unchanged native range remain mandatory in record() and above.
            bounds = target.bounds;
        }
        // A font redraw can detach the real event source from the current tree.
        // Keep the issued target ID only when the native resource is present;
        // record() still requires its original package/window/bounds/time, and
        // the range checks above remain mandatory. Never replace a different ID.
        if (nodeId == null && resourceId != null && !resourceId.isEmpty()
                && resourceId.equals(target.resourceId)) nodeId = target.nodeId;
        return record(AgentActionEvents.DRAG_COMPLETED, packageName, window, nodeId,
                resourceId, bounds, occurredAt);
    }

    public boolean isBoundToGuide(String guideId) { return target != null && java.util.Objects.equals(guide, guideId); }
    public boolean hasSliderBinding() { return target != null && "drag".equals(action) && "slider".equals(target.role); }

    public boolean isTargetTap(String packageName, int window, String nodeId, int[] bounds) {
        if (target == null || !java.util.Objects.equals(target.packageName, packageName)
                || target.windowId != window || nodeId == null || bounds == null || bounds.length != 4) return false;
        AccessibilityNodeSnapshot source = tapSources.get(nodeId);
        return source != null && Arrays.equals(source.bounds, bounds);
    }

    private static boolean descendant(ScreenSnapshot screen, AccessibilityNodeSnapshot child, String ancestor) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        String parent = child.parentId;
        while (parent != null && seen.add(parent)) {
            if (parent.equals(ancestor)) return true;
            AccessibilityNodeSnapshot found = null;
            for (AccessibilityNodeSnapshot node : screen.nodes) if (parent.equals(node.nodeId)) { found = node; break; }
            if (found == null || found.windowId != child.windowId
                    || !java.util.Objects.equals(found.packageName, child.packageName)) return false;
            parent = found.parentId;
        }
        return false;
    }

    private static boolean contains(int[] outer, int[] inner) {
        return outer != null && inner != null && outer.length == 4 && inner.length == 4
                && inner[0] < inner[2] && inner[1] < inner[3]
                && outer[0] <= inner[0] && outer[1] <= inner[1]
                && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    public void clear() { target = null; sliderTrackBounds = null; guide = null; consumed = false; tapSources.clear(); }
}
