package com.click.data.remote;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.TouchAutomationSettings;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.SettingsScreenPrivacy;
import com.click.domain.goal.GoalCatalogClassifier;
import com.click.service.accessibility.ClickAccessibilityService;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in Samsung brightness regression; one bounded native change, restored in finally. */
public class NativeBrightnessFlowTest {
    @Test public void highResolutionBrightnessReachesServerResult() throws Exception {
        boolean localOnly = "true".equals(InstrumentationRegistry.getArguments().getString("localBrightnessOnly"));
        assumeTrue(localOnly || "true".equals(InstrumentationRegistry.getArguments().getString("liveBrightnessFlow")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context app = instrumentation.getTargetContext();
        assertFalse(app.getSystemService(android.app.KeyguardManager.class).isDeviceLocked());
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String enabled = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = "com.click/com.click.service.accessibility.ClickAccessibilityService";
        assertTrue(enabled != null && java.util.Arrays.asList(enabled.split(":")).contains(component));
        int original = Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        int originalMode = Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE);
        TouchAutomationSettings automatic = new TouchAutomationSettings(app);
        boolean originalAutomatic = automatic.isEnabled();
        String session = UUID.randomUUID().toString();
        AuthenticatedHttpClient http = new AuthenticatedHttpClient(app);
        AgentServerContract contract = null;
        JSONObject result = null, report = new JSONObject().put("before", original).put("original_mode", originalMode);
        try {
            shell(automation, "settings put system screen_brightness_mode 0");
            var others = new java.util.ArrayList<>(java.util.Arrays.asList(enabled.split(":"))); others.remove(component);
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
            try {
                Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", others));
                SystemClock.sleep(300);
                Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            } finally { automation.dropShellPermissionIdentity(); }
            app.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ScreenSnapshot before = null;
            for (int i = 0; i < 40; i++) {
                SystemClock.sleep(150); before = ClickAccessibilityService.freshSnapshot();
                if (slider(before) != null && before.appliedBrightness != null) break;
            }
            assertNotNull(slider(before));
            // Samsung finishes laying out its Display header after the first readable tree.
            SystemClock.sleep(900); before = ClickAccessibilityService.freshSnapshot();
            assertNotNull(slider(before));
            int baseline = before.appliedBrightness;
            JSONObject guide = null;
            if (!localOnly) {
            contract = AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json", null).body));
            GoalCatalogClassifier catalog;
            try (var input = app.getAssets().open("mvp_goals.json")) {
                catalog = GoalCatalogClassifier.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            var start = new AgentTurnContract.Request(session, "화면을 더 밝게 해줘", before, null);
            start.acceptedGoal = catalog.executableGoal("settings.brightness.increase").toTypedGoalJson();
            result = send(app, http, contract, start);
            for (int i = 0; i < 3 && !"WAITING_CONFIRMATION".equals(result.optString("task_status")); i++) {
                SystemClock.sleep(700);
                var observe = new AgentTurnContract.Request(session, null, ClickAccessibilityService.freshSnapshot(), null);
                bind(observe, result); result = send(app, http, contract, observe);
            }
            assertEquals("WAITING_CONFIRMATION", result.getString("task_status"));
            ScreenSnapshot consent = ClickAccessibilityService.freshSnapshot();
            assertTrue("Wait for a stable native Display screen before approving its control", before.sameNativeContent(consent));
            var accept = new AgentTurnContract.Request(session, null, consent.withScreenRevision(before.screenRevision), "accepted");
            bind(accept, result);
            accept.confirmationId = result.getJSONObject("metadata").getJSONObject("confirmation").getString("id");
            result = send(app, http, contract, accept);
            guide = result.getJSONObject("guide_command");
            assertEquals("drag", guide.getString("action_type"));
            }
            ScreenSnapshot nativeBefore = ClickAccessibilityService.freshSnapshot();
            AccessibilityNodeSnapshot target = slider(nativeBefore);
            if (guide != null) assertEquals(target.nodeId, guide.getString("target_node_id"));
            automatic.setEnabled(true);
            long actionAt = System.currentTimeMillis();
            final ScreenSnapshot captured = nativeBefore;
            final boolean[] performed = {false};
            instrumentation.runOnMainSync(() -> performed[0] = ClickAccessibilityService.performGuidedSlider(
                    captured, target.nodeId, target.bounds, 1));
            assertTrue("The real native brightness action must dispatch", performed[0]);
            ScreenSnapshot after = null;
            for (int i = 0; i < 30; i++) {
                SystemClock.sleep(150); after = ClickAccessibilityService.freshSnapshot();
                if (slider(after) != null && after.appliedBrightness != null && after.appliedBrightness > baseline
                        && slider(after).progress > target.progress) break;
            }
            assertNotNull(after); assertTrue("Persisted brightness must increase", after.appliedBrightness > baseline);
            report.put("after", after.appliedBrightness).put("native_progress_before", target.progress)
                    .put("native_progress_after", slider(after).progress).put("native_range_max", target.rangeMax);
            if (localOnly) { report.put("local_only", true); return; }
            JSONObject event = AgentActionEvents.of(AgentActionEvents.DRAG_COMPLETED,
                    guide.getString("guide_command_id"), target.nodeId, target.bounds, actionAt);
            event.put("episode_id", result.getString("episode_id"));
            var observe = new AgentTurnContract.Request(session, null, after, null);
            bind(observe, result); observe.userActionEvent = event;
            result = send(app, http, contract, observe);
            for (int i = 0; i < 5 && result.getJSONObject("metadata").optJSONObject("result_prompt") == null; i++) {
                SystemClock.sleep(700);
                observe = new AgentTurnContract.Request(session, null, ClickAccessibilityService.freshSnapshot(), null);
                bind(observe, result); result = send(app, http, contract, observe);
            }
            report.put("result_prompt", result.getJSONObject("metadata").optJSONObject("result_prompt") != null)
                    .put("technical_outcome", result.getJSONObject("metadata").optJSONObject("technical_outcome"));
            assertTrue("A real brightness change must reach the server result prompt", report.getBoolean("result_prompt"));
        } finally {
            if (result != null && contract != null) {
                try { send(app, http, contract, AgentTurnContract.Request.control(session, result.getInt("session_version"),
                        result.getString("episode_id"), "cancel_task", null)); } catch (Exception ignored) { }
            }
            shell(automation, "settings put system screen_brightness " + original);
            shell(automation, "settings put system screen_brightness_mode " + originalMode);
            automatic.setEnabled(originalAutomatic);
            report.put("restored_brightness", Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS))
                    .put("restored_mode", Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE));
            Files.write(new File(app.getCacheDir(), "native-brightness-flow.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private void shell(UiAutomation automation, String command) throws Exception {
        try (var fd = automation.executeShellCommand(command); var in = new FileInputStream(fd.getFileDescriptor())) { in.readAllBytes(); }
    }
    private void bind(AgentTurnContract.Request request, JSONObject previous) throws Exception {
        request.expectedVersion = previous.getInt("session_version"); request.episodeId = previous.getString("episode_id");
    }
    private JSONObject send(Context app, AuthenticatedHttpClient http, AgentServerContract contract, AgentTurnContract.Request request) throws Exception {
        SettingsScreenPrivacy privacy;
        try (var vocabulary = app.getAssets().open("vision_vocabulary.json"); var policy = app.getAssets().open("screen_privacy_policy.json")) {
            privacy = SettingsScreenPrivacy.load(vocabulary, policy);
        }
        request.contractVersion = contract.contractVersion; request.interactiveWorkflow = contract.interactiveWorkflow;
        JSONObject body = new JSONObject(request.toJson());
        return new JSONObject(http.post(AgentTurnContract.PATH, privacy.filter(body).toString()).body);
    }
    private AccessibilityNodeSnapshot slider(ScreenSnapshot screen) {
        if (screen == null || !"com.android.settings".equals(screen.packageName)) return null;
        return screen.nodes.stream().filter(n -> n.visibleToUser && n.enabled
                && "com.android.settings:id/brightness_seekbar".equals(n.resourceId)).findFirst().orElse(null);
    }
}
