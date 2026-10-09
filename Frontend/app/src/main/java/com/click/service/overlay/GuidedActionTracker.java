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

    public void clear() { target = null; guide = null; consumed = false; }
}
