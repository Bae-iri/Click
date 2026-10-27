package com.click.domain.goal;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class QuickHelpTaskTest {
    private GoalCatalogClassifier catalog(String status) throws Exception {
        return GoalCatalogClassifier.fromJson("{\"version\":\"2.1.0\",\"goals\":[{"
                + "\"goal_type\":\"settings.font_size.increase\",\"display_name\":\"이름이 바뀐 카드\","
                + "\"target_app\":\"android_settings\",\"execution_status\":\"" + status + "\","
                + "\"slots\":{\"setting\":\"font_size\",\"direction\":\"increase\"},"
                + "\"keyword_groups\":[[\"unrelated_keyword\"]]}]}");
    }

    @Test public void fontCardSelectsExactGoalEvenWithoutAnyKeywordMatch() throws Exception {
        GoalCatalogClassifier catalog = catalog("approved");
        assertFalse(catalog.classify(QuickHelpTask.FONT_SIZE.requestText, null).matched());
        JSONObject goal = catalog.executableGoal(QuickHelpTask.FONT_SIZE.goalType).toTypedGoalJson();
        assertEquals("settings.font_size.increase", goal.getString("goal_type"));
        assertEquals("font_size", goal.getJSONObject("slots").getString("setting"));
        assertEquals("increase", goal.getJSONObject("slots").getString("direction"));
    }

    @Test public void unknownOrUnapprovedGoalsCannotBeStartedByCard() throws Exception {
        assertNull(catalog("approved").executableGoal("settings.font_size.decrease"));
        assertNull(catalog("catalog_only").executableGoal(QuickHelpTask.FONT_SIZE.goalType));
        assertNull(QuickHelpTask.fromGoalType("settings.factory_reset"));
        assertNull(QuickHelpTask.fromGoalType(null));
    }
}
