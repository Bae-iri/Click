package com.click.service.overlay;

import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONObject;

/** Settings navigation recovery using fresh, visible screen evidence. */
public final class ObservationRecovery {
    private ObservationRecovery() {}
    public static boolean canSearchByScrolling(JSONObject policy, ScreenSnapshot screen) {
        JSONObject recovery = policy == null ? null : policy.optJSONObject("recovery");
        return awaitsUser(policy) && recovery != null
                && "TARGET_NOT_VISIBLE".equals(recovery.optString("reason_code"))
                && "manual_navigation".equals(recovery.optString("next_action"))
                && screen != null && screen.activeWindowId != null
                && ("com.android.settings".equals(screen.packageName)
                    || "com.samsung.android.settings".equals(screen.packageName))
                && screen.nodes.stream().anyMatch(node -> node.visibleToUser && node.scrollable && node.enabled);
    }
    public static boolean awaitsUser(JSONObject policy) {
        JSONObject recovery = policy == null ? null : policy.optJSONObject("recovery");
        return recovery != null && recovery.optBoolean("recoverable", false)
                && "user".equals(recovery.optString("waiting_for"));
    }
    public static String message(String stateId, JSONObject fallback, ScreenSnapshot screen) {
        if (fallback == null || !"target_not_found".equals(fallback.optString("reason")) || screen == null
                || !("com.android.settings".equals(screen.packageName) || "com.samsung.android.settings".equals(screen.packageName))
                || screen.nodes.stream().noneMatch(node -> node.visibleToUser && node.scrollable && node.enabled)) return null;
        String target;
        if ("S1_SETTINGS_HOME".equals(stateId)) target = "디스플레이";
        else if ("S2_DISPLAY_MENU".equals(stateId)) target = "글자 크기";
        else return null;
        return target + " 항목이 현재 화면에 보이지 않아요. X로 안내 창을 접고, 설정 목록을 위로 밀어 아래 항목을 살펴보세요. 화면이 바뀌면 다시 확인할게요.";
    }
}
