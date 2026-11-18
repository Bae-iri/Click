package com.click.data.snapshot;

import com.click.service.accessibility.TouchAutomationPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Structural evidence for explicitly enabled diagnostic builds; excludes arbitrary screen text. */
public final class SettingsCandidateDiagnostics {
    private SettingsCandidateDiagnostics() {}

    /** Persist only closed server diagnostic fields, never arbitrary response strings. */
    public static JSONObject selection(JSONObject selection) throws Exception {
        JSONObject safe = new JSONObject();
        String status = selection.optString("status");
        if (Set.of("selected", "disabled", "skipped", "ineligible", "no_candidates", "candidate_limit",
                "not_configured", "invalid_candidates", "input_too_large", "deadline_exceeded", "provider_error",
                "timeout", "invalid_response", "uncertain", "low_confidence", "abstained", "invalid_choice", "waiting",
                "sensitive_screen", "unsupported_screen", "empty_observation", "network_error", "ambiguous_candidates").contains(status))
            safe.put("status", status);
        if (selection.opt("called") instanceof Boolean) safe.put("called", selection.getBoolean("called"));
        copyCount(selection, safe, "candidate_count");
        JSONObject counts = selection.optJSONObject("candidate_diagnostics");
        if (counts != null) {
            JSONObject filtered = new JSONObject();
            for (String key : Set.of("normalized_nodes", "display_normalized_nodes", "display_actionable_nodes",
                    "minimized_nodes", "display_minimized_nodes", "eligible_pairs", "selector_rejected_pairs",
                    "invalid_slider_pairs", "offscreen_pairs", "source_confidence_rejected_pairs", "excluded_pairs", "ambiguous_pairs"))
                copyCount(counts, filtered, key);
            safe.put("candidate_diagnostics", filtered);
        }
        return safe;
    }

    private static void copyCount(JSONObject from, JSONObject to, String key) throws Exception {
        Object value = from.opt(key);
        if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0
                && ((Number) value).longValue() <= 100_000_000) to.put(key, value);
    }

    public static JSONObject inspect(ScreenSnapshot screen, JSONObject filtered) throws Exception {
        JSONObject report = new JSONObject();
        if (screen == null || !TouchAutomationPolicy.settingsPackage(screen.packageName)) return report;
        Set<String> exported = new HashSet<>();
        JSONObject tree = filtered == null ? null : filtered.optJSONObject("accessibility_tree");
        JSONArray safe = tree == null ? null : tree.optJSONArray("nodes");
        if (safe != null) for (int i = 0; i < safe.length(); i++)
            exported.add(safe.getJSONObject(i).optString("node_id"));
        report.put("observation_id", screen.observationId).put("captured_at", screen.capturedAt)
                .put("collected_node_count", screen.nodes.size()).put("filter_completed", filtered != null);
        if (safe != null) report.put("exported_node_count", safe.length());
        JSONArray display = new JSONArray();
        int matched = 0;
        for (AccessibilityNodeSnapshot node : screen.nodes) {
            if (node.sensitiveInput || !screen.packageName.equals(node.packageName)
                    || !isDisplay(node.text) && !isDisplay(node.contentDescription)) continue;
            matched++;
            // Keep log records bounded even for malformed trees with repeated labels.
            if (display.length() >= 8) continue;
            JSONObject item = structure(node);
            JSONArray parents = new JSONArray();
            Set<String> seen = new HashSet<>();
            String parentId = node.parentId;
            for (int depth = 0; parentId != null && depth < 6 && seen.add(parentId); depth++) {
                AccessibilityNodeSnapshot parent = find(screen, parentId);
                if (parent == null) { item.put("missing_parent", true); break; }
                parents.put(structure(parent));
                parentId = parent.parentId;
            }
            item.put("parents", parents);
            AccessibilityNodeSnapshot target = TouchAutomationPolicy.clickableTarget(screen, node.nodeId, node.bounds);
            item.put("local_target_found", target != null);
            if (target != null) item.put("local_target", structure(target));
            if (filtered != null) {
                item.put("label_exported", exported.contains(node.nodeId));
                item.put("local_target_exported", target != null && exported.contains(target.nodeId));
            }
            display.put(item);
        }
        return report.put("display_label_count", matched).put("display_nodes", display);
    }

    private static boolean isDisplay(String value) {
        return value != null && Set.of("디스플레이", "화면", "display", "screen")
                .contains(value.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static JSONObject structure(AccessibilityNodeSnapshot node) throws Exception {
        // Hash identity fields as OEM resource IDs can contain user content.
        return new JSONObject().put("node_hash", hash(node.nodeId))
                .put("parent_hash", node.parentId == null ? JSONObject.NULL : hash(node.parentId))
                .put("bounds", node.bounds == null ? JSONObject.NULL : new JSONArray(node.bounds))
                .put("clickable", node.clickable).put("enabled", node.enabled)
                .put("visible", node.visibleToUser).put("scrollable", node.scrollable)
                .put("window_id", node.windowId).put("sensitive", node.sensitiveInput);
    }

    private static String hash(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 6; i++) result.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
        return result.toString();
    }

    private static AccessibilityNodeSnapshot find(ScreenSnapshot screen, String id) {
        for (AccessibilityNodeSnapshot node : screen.nodes) if (node.nodeId.equals(id)) return node;
        return null;
    }
}
