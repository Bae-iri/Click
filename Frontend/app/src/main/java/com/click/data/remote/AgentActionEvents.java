package com.click.data.remote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Allowed {@code user_action_event.action_type} values for {@code POST /api/v1/agent/turn}
 * and a factory for the event payload. The BE rejects any other action type.
 */
public final class AgentActionEvents {
    public static final String TAP_COMPLETED = "tap_completed";
    public static final String DRAG_COMPLETED = "drag_completed";
    public static final String SCROLL_COMPLETED = "scroll_completed";
    public static final String SCROLL_FAILED = "scroll_failed";
    public static final String BACK_NAVIGATED = "back_navigated";
    public static final String MANUAL_OVERRIDE = "manual_override";
    public static final String OBSERVE_ONLY = "observe_only";

    private AgentActionEvents() {}

    public static JSONObject of(String actionType, String guideCommandId,
                                String targetNodeId, int[] targetBounds) {
        try {
            JSONObject event = new JSONObject();
            event.put("action_type", actionType);
            if (guideCommandId != null) event.put("guide_command_id", guideCommandId);
            if (targetNodeId != null) event.put("target_node_id", targetNodeId);
            if (targetBounds != null && targetBounds.length == 4) {
                event.put("target_bounds", new JSONArray(targetBounds));
            }
            event.put("occurred_at_ms", System.currentTimeMillis());
            return event;
        } catch (JSONException error) {
            throw new IllegalStateException("user_action_event JSON 생성 실패", error);
        }
    }
}
