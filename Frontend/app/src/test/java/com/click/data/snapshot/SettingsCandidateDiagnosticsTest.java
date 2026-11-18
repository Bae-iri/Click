package com.click.data.snapshot;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SettingsCandidateDiagnosticsTest {
    private AccessibilityNodeSnapshot node(String id, String parent, String label, boolean clickable) {
        return new AccessibilityNodeSnapshot(id, parent, true, "com.android.settings", label, null,
                null, "android.widget.TextView", "text", new int[]{20, 200, 700, 260},
                clickable, true, false, false, false, null, 1);
    }
    private ScreenSnapshot screen(List<AccessibilityNodeSnapshot> nodes) {
        return new ScreenSnapshot("com.android.settings", "2026-11-18T00:00:00Z", 1080, 2400, nodes, 1);
    }
    @Test public void missingParentIsVisibleWithoutDumpingUnrelatedTextOrIds() throws Exception {
        JSONObject report = SettingsCandidateDiagnostics.inspect(screen(List.of(
                node("PRIVATE_ID", "missing", "디스플레이", false),
                node("other", null, "PRIVATE_NAME", false))), null);
        JSONObject label = report.getJSONArray("display_nodes").getJSONObject(0);
        assertTrue(label.getBoolean("missing_parent"));
        assertFalse(report.getBoolean("filter_completed"));
        assertFalse(report.toString().contains("PRIVATE"));
        assertFalse(report.toString().contains("디스플레이"));
    }
    @Test public void filteredReportShowsTheActualClickableRegionAndExportLoss() throws Exception {
        ScreenSnapshot screen = screen(List.of(node("label", "row", "디스플레이", false),
                node("row", "list", "디스플레이", true)));
        JSONObject body = new JSONObject().put("accessibility_tree", new JSONObject().put("nodes",
                new JSONArray().put(new JSONObject().put("node_id", "label"))));
        JSONObject label = SettingsCandidateDiagnostics.inspect(screen, body)
                .getJSONArray("display_nodes").getJSONObject(0);
        assertTrue(label.getBoolean("local_target_found"));
        assertTrue(label.getJSONObject("local_target").getBoolean("clickable"));
        assertTrue(label.getBoolean("label_exported"));
        assertFalse(label.getBoolean("local_target_exported"));
    }
    @Test public void otherAppsDoNotProduceSettingsDiagnostics() throws Exception {
        ScreenSnapshot other = new ScreenSnapshot("com.other.app", "2026-11-18T00:00:00Z", 1080, 2400,
                List.of(node("label", null, "디스플레이", true)), 1);
        assertEquals(0, SettingsCandidateDiagnostics.inspect(other, null).length());
    }
    @Test public void serverSelectionCannotPersistArbitraryStringsOrInvalidCounters() throws Exception {
        JSONObject response = new JSONObject().put("status", "PRIVATE_RESPONSE").put("called", true)
                .put("candidate_count", -1).put("raw_response", "PRIVATE_TEXT")
                .put("candidate_diagnostics", new JSONObject().put("normalized_nodes", 4)
                        .put("minimized_nodes", true).put("eligible_pairs", -1).put("screen_text", "PRIVATE_NAME"));
        JSONObject safe = SettingsCandidateDiagnostics.selection(response);
        assertFalse(safe.toString().contains("PRIVATE"));
        assertFalse(safe.has("status"));
        assertFalse(safe.has("candidate_count"));
        assertTrue(safe.getBoolean("called"));
        assertEquals(1, safe.getJSONObject("candidate_diagnostics").length());
        assertEquals(4, safe.getJSONObject("candidate_diagnostics").getInt("normalized_nodes"));
    }
}
