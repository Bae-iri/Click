package com.click.service.overlay;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.Locale;
import org.json.JSONArray;

/** Identifies a native form field locally using its resource name and visible bounds. */
record AppTaskFormInput(String field, String pkg, int window, int left, int top, int right, int bottom) {
    static AppTaskFormInput find(ScreenSnapshot screen, String goal) {
        if (screen == null || !screen.privacyMaskingReady || screen.activeWindowId == null) return null;
        AppTaskFormInput only = null, requested = null;
        int count = 0, matches = 0;
        for (AccessibilityNodeSnapshot node : screen.nodes) {
            if (!node.visibleToUser || !node.sensitiveInput || node.windowId != screen.activeWindowId
                    || !screen.packageName.equals(node.packageName) || AppTaskObservation.searchField(node)) continue;
            if (!node.publicSearchEnabled() || node.resourceId == null) return null;
            String id = node.resourceId.substring(node.resourceId.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            String field;
            if (id.matches("(?:.*_)?(?:title|subject)(?:_.*)?")) field = "제목";
            else if (id.matches("(?:.*_)?(?:location|place)(?:_.*)?")) field = "장소";
            else if (id.matches("(?:.*_)?(?:memo|note|description)(?:_.*)?")) field = "메모";
            else return null;
            int[] b = node.bounds;
            if (b == null || b.length != 4 || b[0] < 0 || b[1] < 0 || b[2] > screen.screenWidth
                    || b[3] > screen.screenHeight || b[0] >= b[2] || b[1] >= b[3]) return null;
            only = new AppTaskFormInput(field, node.packageName, node.windowId, b[0], b[1], b[2], b[3]);
            count++;
            boolean calendarTitle = field.equals("제목") && goal.contains("일정") && !goal.contains("장소") && !goal.contains("메모");
            if (goal.contains(field) || calendarTitle) {
                requested = only;
                matches++;
            }
        }
        return matches == 1 ? requested : count == 1 ? only : null;
    }
    String instruction(String goal) {
        String supplied = AppTaskSearchIntent.explicitInput(goal, field);
        if (supplied != null) return "표시한 " + field + " 칸에 이렇게 입력해 주세요.\n" + supplied;
        return switch (field) {
            case "제목" -> "표시한 칸에 제목을 입력해 주세요.";
            case "장소" -> "표시한 칸에 장소를 입력해 주세요.";
            default -> "표시한 칸에 남길 메모를 입력해 주세요.";
        };
    }
    JSONArray bounds() { return new JSONArray().put(left).put(top).put(right).put(bottom); }
}
