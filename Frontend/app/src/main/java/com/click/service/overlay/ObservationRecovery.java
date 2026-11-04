package com.click.service.overlay;

import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.TouchAutomationPolicy;
import org.json.JSONObject;

/** Settings navigation recovery using fresh, visible screen evidence. */
public final class ObservationRecovery {
    private ObservationRecovery() {}
    public static boolean canReturnFromSettings(ScreenSnapshot screen) {
        if (!settingsScreen(screen) || screen.activeWindowId == null) return false;
        return screen.nodes.stream().anyMatch(node -> node.visibleToUser && node.enabled && node.clickable
                && screen.packageName.equals(node.packageName) && (backLabel(node.text) || backLabel(node.contentDescription)));
    }
    private static boolean backLabel(String label) {
        return label != null && label.trim().toLowerCase(java.util.Locale.ROOT)
                .matches("뒤로|뒤로 이동|이전|이전 화면|navigate up|back");
    }
    public static boolean canSearchByScrolling(JSONObject policy, ScreenSnapshot screen) {
        JSONObject recovery = policy == null ? null : policy.optJSONObject("recovery");
        return awaitsUser(policy) && recovery != null
                && "TARGET_NOT_VISIBLE".equals(recovery.optString("reason_code"))
                && "manual_navigation".equals(recovery.optString("next_action"))
                && settingsScreen(screen) && screen.activeWindowId != null
                && hasVisibleList(screen);
    }
    /** Accept both current recovery codes and older fallback responses for navigation menus. */
    public static boolean canSearchByScrolling(String stateId, JSONObject policy, ScreenSnapshot screen) {
        if (canSearchByScrolling(policy, screen)) return true;
        if (policy == null || !settingsScreen(screen) || screen.activeWindowId == null
                || screen.nodes.stream().noneMatch(n -> n.visibleToUser && n.enabled && n.scrollable
                    && n.rangeMin == null && n.rangeMax == null)
                || !navigationMenu(stateId) || policy.has("backend_handoff")
                || policy.has("vision_fallback") || policy.has("recovery_action")) return false;
        JSONObject recovery = policy.optJSONObject("recovery");
        if (recovery != null) {
            // Unknown screens, ambiguous targets and result verification require other recovery.
            return recovery.optBoolean("recoverable", false)
                    && "TARGET_NOT_FOUND".equals(recovery.optString("reason_code"))
                    && !"reselect_graph".equals(recovery.optString("next_action"));
        }
        JSONObject fallback = policy.optJSONObject("fallback_action");
        return fallback != null && "request_fresh_observation".equals(fallback.optString("type"))
                && ("target_not_found".equals(fallback.optString("reason"))
                    || "TARGET_NOT_FOUND".equals(fallback.optString("reason_code")));
    }
    private static boolean navigationMenu(String stateId) {
        return "S1_SETTINGS_HOME".equals(stateId) || "S2_DISPLAY_MENU".equals(stateId)
                || "S3_FONT_MENU".equals(stateId) || "S2_SOUND_MENU".equals(stateId);
    }
    /** Older primary-Jev responses omitted the missing-target reason. A recognized
     * Settings list can still be explored, but never while an action result is pending.
     * The caller bounds navigation attempts and sends each new screen back to Jev. */
    public static boolean canSearchJevWait(String stateId, JSONObject policy, JSONObject metadata,
                                          ScreenSnapshot screen) {
        if (policy == null || !"jev".equals(policy.optString("ui_selector"))
                || !navigationMenu(stateId) || !settingsScreen(screen) || screen.activeWindowId == null
                || !hasVisibleList(screen) || policy.has("recovery") || policy.has("backend_handoff")
                || policy.has("vision_fallback") || policy.has("recovery_action")) return false;
        JSONObject fallback = policy.optJSONObject("fallback_action");
        if (fallback == null || !"request_fresh_observation".equals(fallback.optString("type"))
                || !fallback.optString("reason").isEmpty() || !fallback.optString("reason_code").isEmpty()) return false;
        JSONObject verification = metadata == null ? null : metadata.optJSONObject("verification");
        JSONObject result = verification == null ? null : verification.optJSONObject("result");
        if (result != null && !java.util.Set.of("SUCCESS", "WAITING_FOR_ACTION").contains(result.optString("status")))
            return false;
        JSONObject debug = metadata == null ? null : metadata.optJSONObject("debug");
        JSONObject selection = debug == null ? null : debug.optJSONObject("ui_selection");
        if (selection != null && selection.optInt("grounded_candidate_count", 0) > 0) return false;
        if (selection != null && java.util.Set.of("provider_error", "not_configured", "timeout", "deadline_exceeded",
                "invalid_response", "invalid_candidates", "input_too_large", "candidate_limit")
                .contains(selection.optString("status"))) return false;
        return true;
    }
    private static boolean settingsScreen(ScreenSnapshot screen) {
        return screen != null && TouchAutomationPolicy.settingsPackage(screen.packageName);
    }
    private static boolean hasVisibleList(ScreenSnapshot screen) {
        return screen.nodes.stream().anyMatch(node -> node.visibleToUser && node.scrollable && node.enabled
                && node.rangeMin == null && node.rangeMax == null
                && screen.packageName.equals(node.packageName)
                && screen.activeWindowId != null && node.windowId == screen.activeWindowId);
    }
    public static boolean awaitsUser(JSONObject policy) {
        JSONObject recovery = policy == null ? null : policy.optJSONObject("recovery");
        return recovery != null && recovery.optBoolean("recoverable", false)
                && "user".equals(recovery.optString("waiting_for"));
    }
    public static String message(String stateId, JSONObject fallback, ScreenSnapshot screen) {
        if (fallback == null || !"target_not_found".equals(fallback.optString("reason"))
                || !settingsScreen(screen) || !hasVisibleList(screen)) return null;
        String target;
        if ("S1_SETTINGS_HOME".equals(stateId)) target = "디스플레이";
        else if ("S2_DISPLAY_MENU".equals(stateId) || "S3_FONT_MENU".equals(stateId)) target = "글자 크기";
        else return null;
        return target + " 항목을 찾아주세요";
    }
}
