package com.click.data.remote;

import android.app.UiAutomation;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.domain.goal.GoalCatalogClassifier;
import com.click.domain.goal.RequirementReview;
import com.click.service.accessibility.ClickAccessibilityService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt in only after consent to send the current Settings font page to the configured server. */
@RunWith(AndroidJUnit4.class)
public class LiveNativeFontConfirmationTest {
    @Test public void actualFontPageCanApproveItsUnchangedObservation() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveNativeFont")));
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String enabled = Settings.Secure.getString(app.getContentResolver(), key);
        String component = "com.click/com.click.service.accessibility.ClickAccessibilityService";
        assertNotNull(enabled);
        assertTrue(java.util.Arrays.asList(enabled.split(":")).contains(component));
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            java.util.List<String> others = new java.util.ArrayList<>(java.util.Arrays.asList(enabled.split(":")));
            others.remove(component);
            Settings.Secure.putString(app.getContentResolver(), key, String.join(":", others));
            SystemClock.sleep(300);
        } finally {
            Settings.Secure.putString(app.getContentResolver(), key, enabled);
            automation.dropShellPermissionIdentity();
        }
        app.startActivity(new android.content.Intent(Settings.ACTION_DISPLAY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(1200);
        boolean opened = false;
        for (int attempt = 0; attempt < 5 && !opened; attempt++) {
            ScreenSnapshot current = ClickAccessibilityService.freshSnapshot();
            if (current != null && "com.android.settings".equals(current.packageName)
                    && current.nodes.stream().anyMatch(n -> n.resourceId != null && n.resourceId.endsWith("seekBarForFontSize"))) {
                opened = true;
                break;
            }
            android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null && "com.android.settings".contentEquals(root.getPackageName())) {
                for (android.view.accessibility.AccessibilityNodeInfo match :
                        root.findAccessibilityNodeInfosByText("글자 크기와 스타일")) {
                    if (!match.isVisibleToUser()) continue;
                    for (android.view.accessibility.AccessibilityNodeInfo row = match; row != null; row = row.getParent()) {
                        if (row.isClickable()) {
                            opened = row.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                            break;
                        }
                    }
                    if (opened) break;
                }
                if (!opened) scroll(root);
            }
            SystemClock.sleep(700);
        }
        assertTrue("Could not open the font-size menu from Display", opened);
        ScreenSnapshot screen = null;
        for (int i = 0; i < 50; i++) {
            SystemClock.sleep(200);
            screen = ClickAccessibilityService.freshSnapshot();
            if (screen != null && "com.android.settings".equals(screen.packageName)
                    && screen.nodes.stream().anyMatch(n -> n.resourceId != null && n.resourceId.endsWith("seekBarForFontSize"))) break;
        }
        assertNotNull(screen);
        assertEquals("Open Samsung's font-size page before running", "com.android.settings", screen.packageName);
        Files.write(new java.io.File(app.getCacheDir(), "native-font-observation.json").toPath(), screen.toJson().getBytes(StandardCharsets.UTF_8));
        AuthenticatedHttpClient http = new AuthenticatedHttpClient(app);
        AgentServerContract contract = AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json", null).body));
        GoalCatalogClassifier catalog;
        try (java.io.InputStream in = app.getAssets().open("mvp_goals.json")) {
            catalog = GoalCatalogClassifier.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        RequirementReview review = new RequirementReview("휴대폰 글자 크기 키워줘");
        review.choose("phone");
        String session = UUID.randomUUID().toString();
        try {
            AgentTurnContract.Request start = new AgentTurnContract.Request(session, review.summary(), screen, null);
            start.acceptedGoal = review.confirm(catalog, contract);
            JSONObject result = send(http, contract, start);
            Files.write(new java.io.File(app.getCacheDir(), "native-font-challenge.json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
            android.util.Log.i("ClickNativeFont", "challenge=" + result);
            assertEquals("WAITING_CONFIRMATION", result.getString("task_status"));
            SystemClock.sleep(100);
            ScreenSnapshot fresh = ClickAccessibilityService.freshSnapshot();
            assertTrue("The confirmed native screen must still be unchanged", screen.sameNativeContent(fresh));
            AgentTurnContract.Request accept = new AgentTurnContract.Request(session, null,
                    fresh.withScreenRevision(screen.screenRevision), "accepted");
            accept.expectedVersion = result.getInt("session_version");
            accept.episodeId = result.getString("episode_id");
            accept.confirmationId = result.getJSONObject("metadata").getJSONObject("confirmation").getString("id");
            result = send(http, contract, accept);
            android.util.Log.i("ClickNativeFont", "approved=" + result);
            assertEquals("highlight", result.getJSONObject("guide_command").getString("command_type"));
            assertEquals("drag", result.getJSONObject("guide_command").getString("action_type"));
        } finally {
            try {
                JSONObject state = new JSONObject(http.get("/api/v1/agent/sessions/" + session).body);
                send(http, contract, AgentTurnContract.Request.control(session, state.getInt("version"),
                        state.optString("episode_id", null), "cancel_task", null));
            } catch (ApiException error) { if (error.status != 404) throw error; }
        }
    }

    private JSONObject send(AuthenticatedHttpClient http, AgentServerContract contract,
                            AgentTurnContract.Request request) throws Exception {
        request.contractVersion = contract.contractVersion;
        request.interactiveWorkflow = contract.interactiveWorkflow;
        return new JSONObject(http.post(AgentTurnContract.PATH, request.toJson()).body);
    }

    private boolean scroll(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node.isScrollable() && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && scroll(child)) return true;
        }
        return false;
    }
}
