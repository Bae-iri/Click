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
    private static boolean settingsScreen(ScreenSnapshot screen) {
        return screen != null && TouchAutomationPolicy.settingsPackage(screen.packageName);
    }
    private static boolean hasVisibleList(ScreenSnapshot screen) {
        return screen.nodes.stream().anyMatch(node -> node.visibleToUser && node.scrollable && node.enabled);
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
        return target + " 항목이 현재 화면에 보이지 않아요. 줄이기로 안내 창을 접고, 설정 목록을 위로 밀어 아래 항목을 살펴보세요. 화면이 바뀌면 다시 확인할게요.";
    }
}
