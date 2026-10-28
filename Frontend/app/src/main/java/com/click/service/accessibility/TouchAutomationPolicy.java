package com.click.service.accessibility;
import com.click.data.snapshot.*;
import java.util.*;
/** Only known Settings navigation labels; no arbitrary app actions or coordinate gestures. */
public final class TouchAutomationPolicy {
 private static final Set<String> LABELS = Set.of("디스플레이", "일반", "접근성", "시인성 향상", "글자 크기와 스타일", "글자 크기 및 스타일", "글자 크기", "삼성 키보드 설정", "Display", "General management", "Accessibility", "Font size", "Font size and style");
 /** A label may live inside a clickable row. Only a small, non-interactive ancestor is eligible. */
 public static AccessibilityNodeSnapshot clickableTarget(ScreenSnapshot screen, String id, int[] bounds) {
  AccessibilityNodeSnapshot label = target(screen, id, bounds);
  if (label == null) return null;
  if (label.clickable) return label;
  AccessibilityNodeSnapshot child = label;
  for (int depth = 0; depth < 2; depth++) {
   AccessibilityNodeSnapshot parent = null;
   for (AccessibilityNodeSnapshot n : screen.nodes) if (Objects.equals(child.parentId, n.nodeId)) parent = n;
   if (parent == null || !parent.visibleToUser || !parent.enabled || parent.sensitiveInput
       || parent.checkable || parent.progress != null || !Objects.equals(parent.packageName, screen.packageName)
       || parent.windowId != label.windowId || parent.bounds == null || parent.bounds.length != 4
       || parent.bounds[0] < 0 || parent.bounds[1] < 0 || parent.bounds[2] > screen.screenWidth
       || parent.bounds[3] > screen.screenHeight || parent.bounds[0] > bounds[0] || parent.bounds[1] > bounds[1]
       || parent.bounds[2] < bounds[2] || parent.bounds[3] < bounds[3]
       || parent.bounds[3] - parent.bounds[1] > Math.max(1, bounds[3] - bounds[1]) * 4) return null;
   String parentLabel = parent.text != null ? parent.text : parent.contentDescription;
   if (parentLabel != null && !parentLabel.trim().isEmpty() && !LABELS.contains(parentLabel)) return null;
   if (parent.clickable) {
    // Refuse rows containing another control, sensitive input, or a conflicting visible label.
    for (AccessibilityNodeSnapshot n : screen.nodes) {
     if (n.nodeId.startsWith(parent.nodeId + "_") && !n.nodeId.equals(label.nodeId)) {
      String text = n.text != null ? n.text : n.contentDescription;
      if (n.visibleToUser && (n.sensitiveInput || n.checkable || n.progress != null || n.clickable
          || text != null && !text.trim().isEmpty())) return null;
     }
    }
    return parent;
   }
   child = parent;
  }
  return null;
 }
 public static AccessibilityNodeSnapshot target(ScreenSnapshot screen, String id, int[] bounds) {
  if (screen == null || !"com.android.settings".equals(screen.packageName) || !screen.privacyMaskingReady || id == null || bounds == null || bounds.length != 4) return null;
  for (AccessibilityNodeSnapshot n : screen.nodes) {
   String label = n.text != null ? n.text : n.contentDescription == null ? "" : n.contentDescription;
   if (id.equals(n.nodeId) && "com.android.settings".equals(n.packageName) && screen.activeWindowId != null && n.windowId == screen.activeWindowId && n.visibleToUser && n.enabled && !n.sensitiveInput && !n.checkable && n.progress == null && Arrays.equals(bounds,n.bounds) && bounds[0]>=0 && bounds[1]>=0 && bounds[2]<=screen.screenWidth && bounds[3]<=screen.screenHeight && bounds[2]>bounds[0] && bounds[3]>bounds[1] && LABELS.contains(label)) return n;
  }
  return null;
 }
}
