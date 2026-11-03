package com.click.service.accessibility;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.NavigationRecovery;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Resolve a server-selected Settings control using its title and real AX ancestry. */
public final class TouchAutomationPolicy {
    private static final Set<String> LABELS = Set.of("설정", "디스플레이", "화면", "일반", "접근성", "시인성 향상",
            "글자 크기와 스타일", "글자 크기 및 스타일", "글꼴 크기 및 스타일", "글자 크기", "글꼴 크기", "폰트 크기", "삼성 키보드 설정",
            "소리 및 진동", "소리", "음량", "밝기",
            "Settings", "Display", "General management", "Accessibility", "Font size", "Font size and style",
            "Sound & vibration", "Sound and vibration", "Sound", "Volume", "Brightness");
    private static final String[] RISKY = {"결제", "구매", "송금", "전화 걸기", "메시지 보내기", "초기화", "삭제",
            "비밀번호", "payment", "purchase", "send message", "make call", "reset", "delete", "password"};
    private TouchAutomationPolicy() {}

    public static boolean settingsPackage(String pkg) {
        return "com.android.settings".equals(pkg) || "com.samsung.android.settings".equals(pkg);
    }

    private static boolean usable(ScreenSnapshot screen, AccessibilityNodeSnapshot node) {
        return node != null && node.visibleToUser && node.enabled && !node.sensitiveInput
                && !node.checkable && node.progress == null && Objects.equals(screen.packageName, node.packageName)
                && screen.activeWindowId != null && node.windowId == screen.activeWindowId
                && node.bounds != null && node.bounds.length == 4 && node.bounds[0] >= 0 && node.bounds[1] >= 0
                && node.bounds[2] <= screen.screenWidth && node.bounds[3] <= screen.screenHeight
                && node.bounds[2] > node.bounds[0] && node.bounds[3] > node.bounds[1];
    }

    private static String label(AccessibilityNodeSnapshot node) {
        String text = node.text != null && !node.text.trim().isEmpty() ? node.text : node.contentDescription;
        return text == null ? "" : text.trim();
    }

    private static String canonical(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        switch (normalized) {
            case "settings": return "설정";
            case "display": case "screen": case "화면": return "디스플레이";
            case "accessibility": return "접근성";
            case "visibility": case "visibility enhancements": case "시인성": return "시인성 향상";
            case "font size": case "text size": case "글꼴 크기": case "폰트 크기": return "글자 크기";
            case "font size and style": case "글꼴 크기 및 스타일": case "글자 크기 및 스타일": return "글자 크기와 스타일";
            case "general management": return "일반";
            case "sound & vibration": case "sound and vibration": return "소리 및 진동";
            case "sound": return "소리";
            case "volume": return "음량";
            case "brightness": return "밝기";
            default: return normalized;
        }
    }
    private static boolean allowedTitle(ScreenSnapshot screen, String text) {
        String name = canonical(text);
        return NavigationRecovery.isHome(screen.packageName) ? name.equals("설정") : LABELS.contains(name);
    }

    private static boolean risky(AccessibilityNodeSnapshot node) {
        String text = ((node.text == null ? "" : node.text) + " "
                + (node.contentDescription == null ? "" : node.contentDescription)).toLowerCase(Locale.ROOT);
        for (String word : RISKY) if (text.contains(word)) return true;
        return false;
    }

    private static boolean descendant(ScreenSnapshot screen, AccessibilityNodeSnapshot child, String ancestor) {
        String parent = child.parentId;
        for (int depth = 0; parent != null && depth < 32; depth++) {
            if (parent.equals(ancestor)) return true;
            AccessibilityNodeSnapshot next = find(screen, parent);
            if (next == null || Objects.equals(parent, next.parentId)) return false;
            parent = next.parentId;
        }
        return false;
    }

    /** Subtitles and row descriptions are context, not the menu's identity. */
    private static boolean approvedRow(ScreenSnapshot screen, AccessibilityNodeSnapshot row) {
        if (!usable(screen, row) || row.scrollable || risky(row)) return false;
        Set<String> titles = new HashSet<>(), otherLabels = new HashSet<>();
        if (allowedTitle(screen, label(row))) otherLabels.add(canonical(label(row)));
        for (AccessibilityNodeSnapshot child : screen.nodes) {
            if (!child.visibleToUser || !descendant(screen, child, row.nodeId)) continue;
            if (child.sensitiveInput || child.checkable || child.progress != null || risky(child)
                    || child.clickable && !allowedTitle(screen, label(child))) return false;
            if (allowedTitle(screen, label(child))) {
                otherLabels.add(canonical(label(child)));
                if (child.resourceId != null && child.resourceId.endsWith(":id/title")) titles.add(canonical(label(child)));
            }
        }
        // Multiple independently named menu controls in a parent are ambiguous, not one row.
        return otherLabels.size() == 1 && titles.size() <= 1;
    }

    public static AccessibilityNodeSnapshot target(ScreenSnapshot screen, String id, int[] bounds) {
        if (screen == null || !(settingsPackage(screen.packageName) || NavigationRecovery.isHome(screen.packageName))
                || !screen.privacyMaskingReady || id == null || bounds == null || bounds.length != 4) return null;
        AccessibilityNodeSnapshot selected = find(screen, id);
        return usable(screen, selected) && Arrays.equals(selected.bounds, bounds) && approvedRow(screen, selected)
                ? selected : null;
    }

    public static AccessibilityNodeSnapshot clickableTarget(ScreenSnapshot screen, String id, int[] bounds) {
        AccessibilityNodeSnapshot selected = target(screen, id, bounds);
        if (selected == null) return null;
        if (selected.clickable) return selected;
        AccessibilityNodeSnapshot fallback = selected;
        AccessibilityNodeSnapshot current = selected;
        for (int depth = 0; depth < 6; depth++) {
            AccessibilityNodeSnapshot parent = find(screen, current.parentId);
            if (!usable(screen, parent) || parent.scrollable
                    || parent.bounds[3] - parent.bounds[1] > screen.screenHeight / 2
                    || parent.bounds[0] > selected.bounds[0] || parent.bounds[1] > selected.bounds[1]
                    || parent.bounds[2] < selected.bounds[2] || parent.bounds[3] < selected.bounds[3]) break;
            if (parent.clickable) return approvedRow(screen, parent) ? parent : null;
            if (approvedRow(screen, parent)) fallback = parent;
            current = parent;
        }
        return fallback;
    }

    /** Ignore unrelated clocks/revision events, but compare the selected control and all its children. */
    public static boolean sameTarget(ScreenSnapshot before, ScreenSnapshot after, String id, int[] bounds) {
        if (before == null || after == null || !after.privacyMaskingReady || id == null || bounds == null
                || !Objects.equals(before.packageName, after.packageName)
                || !Objects.equals(before.activeWindowId, after.activeWindowId)
                || before.screenWidth != after.screenWidth || before.screenHeight != after.screenHeight) return false;
        AccessibilityNodeSnapshot old = find(before, id), now = find(after, id);
        if (old == null || now == null || !now.visibleToUser || !now.enabled || now.sensitiveInput
                || !Arrays.equals(old.bounds, bounds) || !Arrays.equals(now.bounds, bounds)) return false;
        return shape(before, id).equals(shape(after, id));
    }
    private static String shape(ScreenSnapshot screen, String id) {
        StringBuilder result = new StringBuilder();
        for (AccessibilityNodeSnapshot n : screen.nodes) {
            if (!n.nodeId.equals(id) && !descendant(screen, n, id)) continue;
            result.append(n.nodeId).append('|').append(n.parentId).append('|').append(n.packageName)
                    .append('|').append(n.windowId).append('|').append(n.text).append('|').append(n.contentDescription)
                    .append('|').append(n.resourceId).append('|').append(n.className).append('|').append(n.visibleToUser)
                    .append('|').append(n.enabled).append('|').append(n.clickable).append('|').append(n.checkable)
                    .append('|').append(n.sensitiveInput).append('|').append(Arrays.toString(n.bounds))
                    .append('|').append(n.progress).append('|').append(n.rangeMin).append('|').append(n.rangeMax).append(';');
        }
        return result.toString();
    }

    private static AccessibilityNodeSnapshot find(ScreenSnapshot screen, String id) {
        if (id == null) return null;
        for (AccessibilityNodeSnapshot node : screen.nodes) if (id.equals(node.nodeId)) return node;
        return null;
    }
}
