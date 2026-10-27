package com.click.ui.overlay;

import org.json.JSONObject;

/** Screen direction for a drag hint; never guessed when the server gives none or conflicting ones. */
public final class GuideDragDirection {
    public static final int LEFT = -1;
    public static final int UNKNOWN = 0;
    public static final int RIGHT = 1;

    private GuideDragDirection() {}

    /**
     * render_policy.direction is an explicit visual hint; metadata.typed_goal.slots.direction is the
     * current episode's goal (it already reflects needs_help adjustments). Font sliders grow rightwards.
     */
    public static int resolve(JSONObject renderPolicy, JSONObject metadata) {
        int visual = renderPolicy == null ? UNKNOWN : of(renderPolicy.optString("direction"));
        JSONObject goal = metadata == null ? null : metadata.optJSONObject("typed_goal");
        JSONObject slots = goal == null ? null : goal.optJSONObject("slots");
        int semantic = slots == null ? UNKNOWN : of(slots.optString("direction"));
        if (visual != UNKNOWN && semantic != UNKNOWN && visual != semantic) return UNKNOWN;
        return visual != UNKNOWN ? visual : semantic;
    }

    private static int of(String value) {
        if ("right".equals(value) || "increase".equals(value)) return RIGHT;
        if ("left".equals(value) || "decrease".equals(value)) return LEFT;
        return UNKNOWN;
    }
}
