package com.click.ui.overlay;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class GuideDragDirectionTest {
    private static JSONObject goal(String direction) throws Exception {
        return new JSONObject().put("typed_goal", new JSONObject()
                .put("goal_type", "settings.font_size." + direction)
                .put("slots", new JSONObject().put("direction", direction)));
    }
    private static JSONObject policy(String direction) throws Exception {
        return new JSONObject().put("avoid_covering_target", true).put("direction", direction);
    }

    @Test public void runtimeGoalDirectionPicksSide() throws Exception {
        JSONObject runtimePolicy = new JSONObject().put("preferred_position", "below_target");
        assertEquals(GuideDragDirection.RIGHT, GuideDragDirection.resolve(runtimePolicy, goal("increase")));
        assertEquals(GuideDragDirection.LEFT, GuideDragDirection.resolve(runtimePolicy, goal("decrease")));
    }
    @Test public void explicitVisualDirectionIsUsedWithoutGoal() throws Exception {
        assertEquals(GuideDragDirection.RIGHT, GuideDragDirection.resolve(policy("right"), null));
        assertEquals(GuideDragDirection.LEFT, GuideDragDirection.resolve(policy("left"), new JSONObject()));
    }
    @Test public void missingOrConflictingDirectionIsNotGuessed() throws Exception {
        assertEquals(GuideDragDirection.UNKNOWN, GuideDragDirection.resolve(null, null));
        assertEquals(GuideDragDirection.UNKNOWN, GuideDragDirection.resolve(policy("down"), null));
        assertEquals(GuideDragDirection.UNKNOWN, GuideDragDirection.resolve(policy("right"), goal("decrease")));
    }
}
