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

    public void bind(String session, String episode, String guide, String action,
                     String nodeId, int[] bounds, ScreenSnapshot screen, long issuedAt) {
        if (guide != null && guide.equals(this.guide)) return;
        clear();
        if (screen == null || nodeId == null || bounds == null) return;
        for (AccessibilityNodeSnapshot node : screen.nodes) {
            if (nodeId.equals(node.nodeId) && Arrays.equals(bounds, node.bounds)) {
                this.target = node; break;
            }
        }
        if (target == null) return;
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
        return record(AgentActionEvents.DRAG_COMPLETED, packageName, window, nodeId,
                resourceId, bounds, occurredAt);
    }

    public boolean isBoundToGuide(String guideId) { return target != null && java.util.Objects.equals(guide, guideId); }
    public boolean hasSliderBinding() { return target != null && "drag".equals(action) && "slider".equals(target.role); }

    public boolean isTargetTap(String packageName, int window, String nodeId, int[] bounds) {
        if (target == null || !java.util.Objects.equals(target.packageName, packageName)
                || target.windowId != window || nodeId == null || bounds == null || bounds.length != 4) return false;
        return target.nodeId.equals(nodeId) || target.nodeId.startsWith(nodeId + "_")
                && bounds[0] <= target.bounds[0] && bounds[1] <= target.bounds[1]
                && bounds[2] >= target.bounds[2] && bounds[3] >= target.bounds[3];
    }

    private static boolean contains(int[] outer, int[] inner) {
        return outer != null && inner != null && outer.length == 4 && inner.length == 4
                && inner[0] < inner[2] && inner[1] < inner[3]
                && outer[0] <= inner[0] && outer[1] <= inner[1]
                && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    public void clear() { target = null; sliderTrackBounds = null; guide = null; consumed = false; }
}
