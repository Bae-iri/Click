package com.click.service.accessibility;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.*;

/** A server choice never supplies a slider's setting/channel identity. */
public final class SettingsSliderTarget {
    private SettingsSliderTarget() {}
    private static final Map<String, Set<String>> NAMES = Map.of(
        "font_size", Set.of("글자 크기", "글꼴 크기", "폰트 크기", "글자 크기와 스타일", "글자 크기 및 스타일", "글꼴 크기 및 스타일", "font size", "text size", "font size and style"),
        "brightness", Set.of("밝기", "화면 밝기", "밝기 조절", "brightness", "brightness level", "display brightness"),
        "volume", Set.of("미디어", "미디어 음량", "미디어 볼륨", "media", "media volume"));
    private static final Map<String, Set<String>> RESOURCES = Map.of(
        "font_size", Set.of("com.android.settings:id/seekBarForFontSize", "com.android.settings:id/font_size"),
        "brightness", Set.of("com.android.settings:id/brightness_seekbar", "com.android.systemui:id/slider"),
        "volume", Set.of());
    private static String text(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    private static Set<String> labels(AccessibilityNodeSnapshot n) {
        return new HashSet<>(Arrays.asList(text(n.text), text(n.contentDescription)));
    }
    private static boolean named(AccessibilityNodeSnapshot n, String setting) {
        return !Collections.disjoint(labels(n), NAMES.get(setting));
    }
    private static boolean slider(AccessibilityNodeSnapshot n) {
        return "slider".equals(n.role) || n.className != null && n.className.toLowerCase(Locale.ROOT).contains("seekbar");
    }
    private static AccessibilityNodeSnapshot find(ScreenSnapshot s, String id) {
        for (AccessibilityNodeSnapshot n : s.nodes) if (Objects.equals(id, n.nodeId)) return n;
        return null;
    }
    private static boolean below(ScreenSnapshot s, AccessibilityNodeSnapshot n, String ancestor) {
        Set<String> seen = new HashSet<>();
        for (String id = n.parentId; id != null && seen.add(id);) {
            if (id.equals(ancestor)) return true;
            AccessibilityNodeSnapshot p = find(s, id);
            if (p == null || p.scrollable || p.clickable) return false;
            id = p.parentId;
        }
        return false;
    }
    private static boolean sameWindow(ScreenSnapshot s, AccessibilityNodeSnapshot n) {
        return n.visibleToUser && n.enabled && !n.sensitiveInput && Objects.equals(n.packageName, s.packageName)
            && s.activeWindowId != null && n.windowId == s.activeWindowId;
    }
    public static boolean matches(ScreenSnapshot s, AccessibilityNodeSnapshot n, String setting) {
        if (!evidence(s,n,setting)) return false;
        return s.nodes.stream().filter(other->evidence(s,other,setting)).count()==1;
    }
    private static boolean evidence(ScreenSnapshot s, AccessibilityNodeSnapshot n, String setting) {
        if (s == null || n == null || setting == null || !NAMES.containsKey(setting) || !s.privacyMaskingReady
                || !TouchAutomationPolicy.settingsPackage(s.packageName) || !sameWindow(s,n) || !slider(n)) return false;
        // A named different setting cannot be overridden by a resource ID or page title.
        for (String other : NAMES.keySet()) if (!other.equals(setting) && named(n, other)) return false;
        if ("volume".equals(setting)) for (String label : labels(n))
            if (label.matches(".*(벨소리|알림|알람|통화|시스템|ring|notification|alarm|call|system).*")) return false;
        if (named(n, setting) || n.resourceId != null && RESOURCES.get(setting).contains(n.resourceId)) return true;
        // Only an actual small AX ancestor with one range control can name a sibling slider.
        AccessibilityNodeSnapshot parent = find(s, n.parentId);
        for (int depth=0; parent != null && depth<4; depth++, parent=find(s,parent.parentId)) {
            if (parent.scrollable || parent.clickable || parent.parentId == null
                    || parent.bounds[3]-parent.bounds[1] >= s.screenHeight/2) break;
            int count=0; boolean identity=false;
            for (AccessibilityNodeSnapshot child : s.nodes) if (sameWindow(s,child) && below(s,child,parent.nodeId)) {
                if (slider(child)) count++;
                if (!child.clickable && !slider(child) && named(child,setting)
                        && (child.resourceId == null || !child.resourceId.endsWith("/summary"))) identity=true;
            }
            if (count==1 && identity) return true;
        }
        // General Volume titles never distinguish media from ring/alarm channels.
        if ("volume".equals(setting)) return false;
        long controls=s.nodes.stream().filter(c->sameWindow(s,c) && slider(c)).count();
        if (controls!=1) return false;
        for (AccessibilityNodeSnapshot label : s.nodes) {
            if (!sameWindow(s,label) || label.clickable || !named(label,setting) || slider(label)) continue;
            boolean independent=true;
            Set<String> seen=new HashSet<>();
            for (AccessibilityNodeSnapshot p=find(s,label.parentId); p!=null && seen.add(p.nodeId); p=find(s,p.parentId))
                if (p.clickable || p.scrollable) { independent=false; break; }
            if (independent) return true;
        }
        return false;
    }
    public static String identify(ScreenSnapshot s, AccessibilityNodeSnapshot node) {
        String result=null;
        for (String setting : NAMES.keySet()) if (matches(s,node,setting)) {
            if (result!=null) return null;
            result=setting;
        }
        return result;
    }
}
