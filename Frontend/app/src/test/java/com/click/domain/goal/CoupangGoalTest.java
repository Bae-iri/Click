package com.click.domain.goal;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoupangGoalTest {
    private GoalCatalogClassifier catalog() throws Exception {
        try (java.io.InputStream input = getClass().getResourceAsStream("/legacy_coupang_goals.json")) {
            return GoalCatalogClassifier.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test public void conversationalPurchaseHelpMatchesOutsideCoupangWithoutShortImperativeKeywords() throws Exception {
        GoalCatalogClassifier catalog = catalog();
        for (String text : new String[]{"쿠팡에서 구매하는거 도와줘", "쿠팡에서 구매하는 거 도와줘",
                "쿠팡에서 구매하는 걸 도와줘", "쿠팡에서 물건 사는 거 도와줘", "쿠팡 구매 도와줘",
                "쿠팡에서 구매하는 방법 알려줘", "쿠팡에서 구매 도와주세요"}) {
            GoalCatalogClassifier.Classification result = catalog.classify(text, "com.android.settings");
            assertNotNull(text, result.match);
            assertEquals(text, "coupang.order.checkout", result.match.goalType);
        }
        for (String text : new String[]{"네이버에서 구매하는 거 도와줘", "정부24에서 구매하는 거 도와줘",
                "구매하는 거 도와줘", "쿠팡에서 구매하는 거 도와줬어"}) {
            assertNull(text, catalog.classify(text, "com.android.settings").match);
        }
        assertEquals("coupang.order.list", catalog.classify("쿠팡 구매내역 확인해줘", "com.android.settings").match.goalType);
        assertEquals("coupang.order.cancel", catalog.classify("쿠팡 주문 취소해줘", "com.android.settings").match.goalType);
    }

    @Test public void allCommerceGoalsCanBeSelectedAndResumedWithoutLosingTheirIdentity() throws Exception {
        String raw;
        try (java.io.InputStream input = getClass().getResourceAsStream("/legacy_coupang_goals.json")) {
            raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        GoalCatalogClassifier catalog = GoalCatalogClassifier.fromJson(raw);
        JSONArray goals = new JSONObject(raw).getJSONArray("goals");
        int checked = 0;
        for (int i = 0; i < goals.length(); i++) {
            JSONObject goal = goals.getJSONObject(i);
            if (!"coupang".equals(goal.getString("target_app"))) continue;
            String type = goal.getString("goal_type");
            GoalCatalogClassifier.Match match = catalog.executableGoal(type);
            assertNotNull(type, match);
            assertEquals(type, catalog.executableGraph(goal.getString("graph_id")).goalType);
            GoalCatalogClassifier.Classification classified = catalog.classify(goal.getString("display_name"), "com.coupang.mobile");
            assertNotNull(type, classified.match);
            assertEquals(type, classified.match.goalType);
            assertEquals("2.4.1", match.toTypedGoalJson().getString("catalog_version"));
            checked++;
        }
        assertEquals(71, checked);
    }
}
