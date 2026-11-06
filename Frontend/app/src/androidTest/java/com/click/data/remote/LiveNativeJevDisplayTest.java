package com.click.data.remote;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.BuildConfig;
import com.click.data.local.AuthTokenStore;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.SettingsScreenPrivacy;
import com.click.service.accessibility.ClickAccessibilityService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in native Settings/Jev probe. Exports only filtered UI and bounded decisions; never changes font size. */
@RunWith(AndroidJUnit4.class)
public class LiveNativeJevDisplayTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();
    private final File reportFile = new File(app.getCacheDir(), "native-jev-display-result.json");

    @Test public void actualSettingsDisplayDecisionsAndNavigation() throws Exception {
        boolean captureOnly = "true".equals(InstrumentationRegistry.getArguments().getString("captureNativeOnly"));
        assumeTrue(captureOnly || "true".equals(InstrumentationRegistry.getArguments().getString("liveJevDisplay")));
        JSONObject report = new JSONObject().put("source", "physical_device_native_settings")
                .put("server", BuildConfig.CLICK_API_BASE_URL).put("actual_clicks", false)
                .put("mode", captureOnly ? "native_capture_only" : "native_live_server").put("external_requests", 0)
                .put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE).put("observations", new JSONArray());
        JSONArray observations = report.getJSONArray("observations");
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original = Settings.Secure.getString(app.getContentResolver(), key);
        int originalEnabled = Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        float originalScale = Settings.System.getFloat(app.getContentResolver(), Settings.System.FONT_SCALE, 1f);
        AuthenticatedHttpClient http = new AuthenticatedHttpClient(app);
        JSONObject last = null;
        String session = UUID.randomUUID().toString();
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            assertFalse("Unlock the phone before the native probe", app.getSystemService(KeyguardManager.class).isDeviceLocked());
            if (!captureOnly) assertTrue("Log into the installed Click app before the native server probe", new AuthTokenStore(app).hasRefreshToken());
            ComponentName component = new ComponentName(app, ClickAccessibilityService.class);
            List<String> services = new ArrayList<>();
            if (original != null) for (String value : original.split(":"))
                if (!value.isEmpty() && !component.equals(ComponentName.unflattenFromString(value))) services.add(value);
            Settings.Secure.putString(app.getContentResolver(), key, String.join(":", services));
            SystemClock.sleep(400);
            services.add(component.flattenToString());
            Settings.Secure.putString(app.getContentResolver(), key, String.join(":", services));
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            for (int attempt = 0; attempt < 50 && ClickAccessibilityService.freshSnapshot() == null; attempt++) SystemClock.sleep(100);
            app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ScreenSnapshot first = null;
            for (int attempt = 0; attempt < 60; attempt++) {
                SystemClock.sleep(200);
                first = ClickAccessibilityService.freshSnapshot();
                if (settings(first) && first.nodes.stream().anyMatch(n -> n.visibleToUser && n.enabled && "디스플레이".equals(n.text))) break;
                if (attempt > 10 && attempt % 6 == 0 && scroll(automation.getRootInActiveWindow()))
                    report.put("native_scrolls", report.optInt("native_scrolls") + 1);
            }
            assertNotNull("Native accessibility screen is unavailable", first);
            assertTrue("Display must be visible on the actual Settings home", first.nodes.stream().anyMatch(n -> n.visibleToUser && n.enabled && "디스플레이".equals(n.text)));
            SettingsScreenPrivacy privacy;
            try (java.io.InputStream vocabulary = app.getAssets().open("vision_vocabulary.json");
                 java.io.InputStream policy = app.getAssets().open("screen_privacy_policy.json")) {
                privacy = SettingsScreenPrivacy.load(vocabulary, policy);
            }
            if (captureOnly) {
                JSONObject filtered = privacy.filter(new JSONObject().put("accessibility_tree", new JSONObject(first.toJson())));
                Files.write(new File(app.getCacheDir(), "native-jev-display-screen.json").toPath(),
                        filtered.getJSONObject("accessibility_tree").toString().getBytes(StandardCharsets.UTF_8));
                report.put("filtered_nodes", filtered.getJSONObject("accessibility_tree").getJSONArray("nodes").length())
                        .put("font_scale_unchanged", Math.abs(originalScale - Settings.System.getFloat(
                            app.getContentResolver(), Settings.System.FONT_SCALE, 1f)) < .001f)
                        .put("assertions_passed", true).put("probe_completed", true);
                write(report);
                return;
            }
            AgentServerContract contract = AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json", null).body));
            JSONObject catalog;
            try (java.io.InputStream input = app.getAssets().open("mvp_goals.json")) {
                catalog = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            JSONObject goal = new JSONObject().put("catalog_version", catalog.getString("version"))
                    .put("goal_type", "settings.font_size.increase").put("target_app", "android_settings")
                    .put("slots", new JSONObject().put("setting", "font_size").put("direction", "increase"));
            for (int turn = 0; turn < 5; turn++) {
                SystemClock.sleep(700);
                ScreenSnapshot nativeScreen = ClickAccessibilityService.freshSnapshot();
                assertTrue("Actual Settings screen must remain available", settings(nativeScreen));
                JSONObject body = command(contract, session, last, turn == 0 ? "start_task" : "observe")
                        .put("accessibility_tree", new JSONObject(nativeScreen.toJson()));
                if (turn == 0) body.put("goal", goal).put("device_context", new JSONObject()
                        .put("platform", "android").put("locale", "ko-KR")
                        .put("manufacturer", android.os.Build.MANUFACTURER)
                        .put("os_version", android.os.Build.VERSION.RELEASE).put("foreground_package", nativeScreen.packageName));
                body = privacy.filter(body);
                Files.write(new File(app.getCacheDir(), "native-jev-display-screen.json").toPath(),
                        body.getJSONObject("accessibility_tree").toString().getBytes(StandardCharsets.UTF_8));
                long started = SystemClock.elapsedRealtime();
                report.put("external_requests", report.getInt("external_requests") + 1);
                last = new JSONObject(http.post(AgentTurnContract.PATH, body.toString()).body);
                JSONObject guide = last.getJSONObject("guide_command");
                JSONObject selection = last.getJSONObject("metadata").optJSONObject("debug");
                selection = selection == null ? null : selection.optJSONObject("ui_selection");
                JSONObject observation = new JSONObject().put("turn", turn + 1)
                        .put("latency_ms", SystemClock.elapsedRealtime() - started)
                        .put("same_native_screen", first.sameNativeContent(nativeScreen))
                        .put("state", last.optString("state_id")).put("task_status", last.optString("task_status"))
                        .put("action_type", guide.optString("action_type"))
                        .put("display_target", targetHasLabel(nativeScreen, guide.optString("target_node_id"), "디스플레이"))
                        .put("ui_selection", selection == null ? JSONObject.NULL : selection)
                        .put("recovery", guide.optJSONObject("render_policy") == null ? JSONObject.NULL
                                : guide.getJSONObject("render_policy").opt("recovery"));
                observations.put(observation);
                write(report);
            }
            JSONObject guide = last.getJSONObject("guide_command");
            ScreenSnapshot beforeTap = ClickAccessibilityService.freshSnapshot();
            JSONObject lastSelection = observations.getJSONObject(observations.length() - 1).optJSONObject("ui_selection");
            if (lastSelection != null && "jev".equals(lastSelection.optString("provider"))
                    && "tap".equals(guide.optString("action_type"))
                    && targetHasLabel(beforeTap, guide.optString("target_node_id"), "디스플레이")) {
                long at = System.currentTimeMillis();
                assertTrue("The approved Display row must be physically clickable", clickDisplay(automation));
                report.put("actual_clicks", true);
                ScreenSnapshot after = null;
                for (int attempt = 0; attempt < 40; attempt++) {
                    SystemClock.sleep(200);
                    after = ClickAccessibilityService.freshSnapshot();
                    if (independentTitle(after, "디스플레이")) break;
                }
                report.put("display_page_reached", independentTitle(after, "디스플레이"));
                assertTrue("A real click must reach the Display page", independentTitle(after, "디스플레이"));
                JSONObject event = AgentActionEvents.of(AgentActionEvents.TAP_COMPLETED,
                        guide.getString("guide_command_id"), guide.getString("target_node_id"),
                        new int[]{guide.getJSONArray("target_bounds").getInt(0), guide.getJSONArray("target_bounds").getInt(1),
                                guide.getJSONArray("target_bounds").getInt(2), guide.getJSONArray("target_bounds").getInt(3)}, at);
                event.put("episode_id", last.getString("episode_id"));
                JSONObject body = command(contract, session, last, "observe")
                        .put("accessibility_tree", new JSONObject(after.toJson())).put("user_action_event", event);
                report.put("external_requests", report.getInt("external_requests") + 1);
                last = new JSONObject(http.post(AgentTurnContract.PATH, privacy.filter(body).toString()).body);
                report.put("after_click_state", last.optString("state_id"));
                report.put("after_click_selection", last.getJSONObject("metadata").optJSONObject("debug"));
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            }
            report.put("font_scale_unchanged", Math.abs(originalScale - Settings.System.getFloat(
                    app.getContentResolver(), Settings.System.FONT_SCALE, 1f)) < .001f);
            report.put("probe_completed", true);
            write(report);
            for (int i = 0; i < observations.length(); i++) {
                JSONObject selection = observations.getJSONObject(i).optJSONObject("ui_selection");
                assertNotNull("Server must expose primary decision diagnostics", selection);
                assertEquals("Probe server must actually use Jev", "jev", selection.optString("provider"));
            }
            report.put("assertions_passed", true);
        } catch (Throwable failure) {
            report.put("error_type", failure.getClass().getSimpleName());
            if (failure instanceof ApiException) report.put("http_status", ((ApiException) failure).status);
            write(report);
            throw failure;
        } finally {
            if (last != null) {
                try {
                    JSONObject state = new JSONObject(http.get("/api/v1/agent/sessions/" + session).body);
                    JSONObject cancel = command(null, session, null, "cancel_task")
                            .put("expected_version", state.getInt("version")).put("episode_id", state.getString("episode_id"));
                    cancel.put("contract_version", "agent-fe-contract.v0.2");
                    http.post(AgentTurnContract.PATH, cancel.toString());
                    report.put("test_session_cancelled", true);
                } catch (Exception ignored) { report.put("test_session_cancelled", false); }
            }
            Settings.Secure.putString(app.getContentResolver(), key, original == null ? "" : original);
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled);
            automation.dropShellPermissionIdentity();
            write(report);
        }
    }

    private JSONObject command(AgentServerContract contract, String session, JSONObject previous, String command) throws Exception {
        JSONObject body = new JSONObject().put("contract_version", contract == null ? "agent-fe-contract.v0.2" : contract.contractVersion)
                .put("request_id", UUID.randomUUID().toString()).put("session_id", session)
                .put("expected_version", previous == null ? 0 : previous.getInt("session_version")).put("command_type", command);
        if (previous != null) body.put("episode_id", previous.getString("episode_id"));
        return body;
    }
    private void write(JSONObject report) throws Exception {
        Files.write(reportFile.toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private boolean settings(ScreenSnapshot screen) { return screen != null && "com.android.settings".equals(screen.packageName); }
    private boolean targetHasLabel(ScreenSnapshot screen, String id, String label) {
        if (!settings(screen)) return false;
        Map<String, AccessibilityNodeSnapshot> nodes = new HashMap<>();
        for (AccessibilityNodeSnapshot node : screen.nodes) nodes.put(node.nodeId, node);
        for (AccessibilityNodeSnapshot node : screen.nodes) if (label.equals(node.text) || label.equals(node.contentDescription)) {
            for (int depth = 0; node != null && depth < 20; depth++, node = nodes.get(node.parentId))
                if (id.equals(node.nodeId)) return true;
        }
        return false;
    }
    private boolean independentTitle(ScreenSnapshot screen, String text) {
        if (!settings(screen)) return false;
        Map<String, AccessibilityNodeSnapshot> nodes = new HashMap<>();
        for (AccessibilityNodeSnapshot node : screen.nodes) nodes.put(node.nodeId, node);
        for (AccessibilityNodeSnapshot node : screen.nodes) if (text.equals(node.text) && !node.clickable) {
            boolean independent = true;
            for (AccessibilityNodeSnapshot parent = nodes.get(node.parentId); parent != null; parent = nodes.get(parent.parentId))
                if (parent.clickable || parent.scrollable) { independent = false; break; }
            if (independent) return true;
        }
        return false;
    }
    private boolean clickDisplay(UiAutomation automation) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null || !"com.android.settings".contentEquals(root.getPackageName())) return false;
        try {
            for (AccessibilityNodeInfo match : root.findAccessibilityNodeInfosByText("디스플레이")) {
                if (!match.isVisibleToUser() || !"디스플레이".contentEquals(match.getText())) continue;
                for (AccessibilityNodeInfo row = match; row != null; row = row.getParent())
                    if (row.isClickable()) return row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            return false;
        } finally { root.recycle(); }
    }
    private boolean scroll(AccessibilityNodeInfo root) {
        if (root == null) return false;
        if (root.isScrollable() && root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) try { if (scroll(child)) return true; } finally { child.recycle(); }
        }
        return false;
    }
}
