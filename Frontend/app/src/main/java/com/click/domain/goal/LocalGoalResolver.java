package com.click.domain.goal;

import com.click.data.remote.AgentServerContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keywords handle simple requests; the server interprets complete contextual requests. */
public final class LocalGoalResolver {
    private LocalGoalResolver() {}

    public static boolean needsServerResolution(GoalCatalogClassifier catalog, AgentServerContract server,
                                                 String text, String foregroundPackage, String clarification) {
        if (catalog == null || server == null || !server.supports("resolve_goal")
                || !"agent-fe-contract.v0.3".equals(server.contractVersion) || clarification != null) return false;
        String normalized = GoalCatalogClassifier.normalize(text);
        if (normalized.isEmpty()) return false;
        if (GoalCommandInterpreter.requiresContextualInterpretation(text)) return true;
        GoalCatalogClassifier.Classification result = catalog.classify(text, foregroundPackage);
        if(result.matched() && server.gov24Planner
                && com.click.data.snapshot.Gov24LoginPrivacy.isPlannerGoal(result.match.goalType)) return true;
        if(result.matched() && "public_guide".equals(result.match.executionStatus)) {
            return "gov24.login".equals(result.match.goalType)?server.gov24Login
                    :"gov24.resident_copy.open".equals(result.match.goalType)?server.gov24Civil:server.gov24Services;
        }
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
                : "어떤 앱에서 무엇을 바꾸고 싶으신지 말씀해 주시겠어요?";
        return new JSONObject().put("message", prompt).put("candidates", candidates);
    }

    public static JSONObject resolve(GoalCatalogClassifier catalog, AgentServerContract server,
                                     String text, String foregroundPackage, String clarification) throws JSONException {
        JSONArray candidates = new JSONArray();
        if (GoalCommandInterpreter.requiresContextualInterpretation(text)) {
            return new JSONObject().put("message", "요청의 뜻을 확인하지 못했어요. 원하는 작업을 다시 말씀해 주시겠어요?")
                    .put("candidates", candidates);
        }
        if (catalog != null && clarification == null && !GoalCatalogClassifier.normalize(text).matches(".*(하지마|하지말|지마|지말|지않|안해|말아|취소).*")) {
            GoalCatalogClassifier.Classification selection = catalog.classify(text, foregroundPackage);
            if (selection.matched() && selection.match.goalType.startsWith("settings.navigation.")) {
                add(candidates, selection.match, server);
                return new JSONObject().put("message", candidates.length() > 0
                        ? "요청한 설정 화면으로 안내할까요?" : "설정 화면 안내를 지원하는 서버로 업데이트해 주세요.")
                        .put("candidates", candidates);
            }
        }
        if (RequirementReview.isFontRequest(text)) {
            RequirementReview review = new RequirementReview(text);
            JSONObject goal = clarification == null ? review.proposedGoal(catalog, server) : null;
            if (goal != null) candidates.put(new JSONObject().put("label", review.summary()).put("goal", goal));
            return new JSONObject().put("message", review.prompt(goal != null)).put("candidates", candidates);
        }
        String message = "어떤 내용을 바꾸고 싶으신지 말씀해 주시겠어요? 예를 들어 “글자 크기를 키워주세요”라고 요청하실 수 있어요.";
        String normalized = GoalCatalogClassifier.normalize(text);
        boolean negative = normalized.matches(".*(하지마|하지말|지마|지말|지않|안해|말아).*");
        if (catalog != null && clarification == null && !negative) {
            GoalCatalogClassifier.Classification result = catalog.classify(text, foregroundPackage);
            if (result.matched()) {
                add(candidates, result.match, server);
                if (candidates.length() == 0) message = "말씀하신 기능은 아직 준비 중이에요. 다른 도움이 필요하시면 말씀해 주세요.";
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
