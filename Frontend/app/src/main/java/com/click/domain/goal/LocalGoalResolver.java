package com.click.domain.goal;

import com.click.data.remote.AgentServerContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keyword mapping first; only genuine misses may ask our server for interpretation. */
public final class LocalGoalResolver {
    private LocalGoalResolver() {}

    public static boolean needsServerResolution(GoalCatalogClassifier catalog, AgentServerContract server,
                                                 String text, String foregroundPackage, String clarification) {
        if (catalog == null || server == null || !server.supports("resolve_goal")
                || !"agent-fe-contract.v0.3".equals(server.contractVersion) || clarification != null) return false;
        String normalized = GoalCatalogClassifier.normalize(text);
        if (normalized.isEmpty() || normalized.matches(".*(하지마|하지말|지마|지말|지않|안해|말아).*")) return false;
        GoalCatalogClassifier.Classification result = catalog.classify(text, foregroundPackage);
        return !result.matched() && !result.ambiguous;
    }

    public static JSONObject serverSelection(GoalCatalogClassifier catalog, AgentServerContract server,
                                              String status, JSONObject goal, String message) throws JSONException {
        JSONArray candidates = new JSONArray();
        if ("confirmation_required".equals(status) && goal != null && catalog != null) {
            GoalCatalogClassifier.Match match = catalog.executableGoal(goal.optString("goal_type"));
            // Reconstruct slots from the local approved catalog, never from generated content.
            if (match != null && match.catalogVersion.equals(goal.optString("catalog_version"))
                    && match.targetApp.equals(goal.optString("target_app"))) add(candidates, match, server);
        }
        String prompt = candidates.length() > 0 ? "요청을 이렇게 이해했어요. 내용을 확인해 주세요."
                : "대상과 원하는 변경을 구체적으로 말씀해 주세요.";
        return new JSONObject().put("message", prompt).put("candidates", candidates);
    }

    public static JSONObject resolve(GoalCatalogClassifier catalog, AgentServerContract server,
                                     String text, String foregroundPackage, String clarification) throws JSONException {
        JSONArray candidates = new JSONArray();
        if (RequirementReview.isFontRequest(text)) {
            RequirementReview review = new RequirementReview(text);
            JSONObject goal = clarification == null ? review.proposedGoal(catalog, server) : null;
            if (goal != null) candidates.put(new JSONObject().put("label", review.summary()).put("goal", goal));
            return new JSONObject().put("message", review.prompt(goal != null)).put("candidates", candidates);
        }
        String message = "원하는 대상과 변경 방향을 구체적으로 입력해 주세요. 예: 글자 크기를 키워줘.";
        String normalized = GoalCatalogClassifier.normalize(text);
        boolean negative = normalized.matches(".*(하지마|하지말|지마|지말|지않|안해|말아).*");
        if (catalog != null && clarification == null && !negative) {
            GoalCatalogClassifier.Classification result = catalog.classify(text, foregroundPackage);
            if (result.matched()) {
                add(candidates, result.match, server);
                if (candidates.length() == 0) message = "이 요청의 실행 안내는 아직 지원하지 않아요. 다른 요청을 입력해 주세요.";
            } else if (normalized.contains("글자") || normalized.contains("글씨") || normalized.contains("폰트")) {
                add(candidates, catalog.executableGoal("settings.font_size.increase"), server);
                add(candidates, catalog.executableGoal("settings.font_size.decrease"), server);
            }
        }
        if (candidates.length() > 0) message = "원하는 목표를 선택하면 실제 화면 안내를 시작해요.";
        if (negative) message = "변경을 원하시는지 다시 확인해 주세요. 아직 작업을 시작하지 않았어요.";
        // No server resolution ID or synthetic execution response: the user chooses a typed goal,
        // then start_task goes to the real backend runtime.
        return new JSONObject().put("message", message).put("candidates", candidates);
    }

    private static void add(JSONArray candidates, GoalCatalogClassifier.Match match,
                            AgentServerContract server) throws JSONException {
        if (match == null || !match.isExecutable() || !server.supportsGoal(match.goalType)) return;
        candidates.put(new JSONObject().put("label", match.displayName).put("goal", match.toTypedGoalJson()));
    }
}
