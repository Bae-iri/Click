package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.AppTaskCompletion;
import com.click.service.overlay.AppTaskController;
import com.click.service.overlay.AppTaskMemory;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in paired experiment. Runs on Korean Samsung Settings in a separate diagnostic APK. */
@RunWith(AndroidJUnit4.class)
public final class GoalOutcomeDeviceTest {
    private static final String PKG = "com.android.settings";
    private static final String CLOCK = "com.sec.android.app.clockpackage";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private UiAutomation ui;

    private enum Page {
        DISPLAY(Settings.ACTION_DISPLAY_SETTINGS, "디스플레이"),
        SOUND(Settings.ACTION_SOUND_SETTINGS, "소리 및 진동"),
        NOTIFICATIONS("android.settings.NOTIFICATION_SETTINGS", "알림");

        final String action, label;
        Page(String action, String label) { this.action = action; this.label = label; }
    }
    private enum Case { NORMAL, WRONG_PREDICTION, LATE, UNCHANGED, WRONG_PAGE, REJECTED }

    private void connect() {
        assertTrue("Use a separate diagnostic application ID", context.getPackageName().endsWith(".outcomestudy"));
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = ui.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        ui.setServiceInfo(info);
    }
    @Test public void compareOutcomesOnDevice() throws Exception {
        String arm = InstrumentationRegistry.getArguments().getString("outcomeStudy", "");
        assumeTrue(List.of("baseline", "candidate").contains(arm));
        connect();
        JSONArray rows = new JSONArray();
        File output = new File(context.getFilesDir(), "outcomes-" + arm + ".json");
        for (Page page : Page.values()) {
            for (Case scenario : Case.values()) {
                open(Settings.ACTION_SETTINGS);
                awaitForeground(Settings.ACTION_SETTINGS);
                Trial trial = new Trial(page, scenario);
                try {
                    instrumentation.runOnMainSync(trial::start);
                    assertTrue("Proposal did not reach the executor", trial.executions == 1);
                    boolean arrives = scenario == Case.NORMAL || scenario == Case.WRONG_PREDICTION || scenario == Case.LATE;
                    if (arrives || scenario == Case.WRONG_PAGE) {
                        SystemClock.sleep(scenario == Case.LATE ? 5800 : 200);
                        Page destination = arrives ? page : Page.values()[(page.ordinal() + 1) % Page.values().length];
                        open(destination.action);
                        awaitForeground(destination.action);
                        // A real transition wakes the controller, including after a previous handoff.
                        instrumentation.runOnMainSync(() -> trial.controller.screenChanged(trial.fresh()));
                    }
                    long end = trial.started + (scenario == Case.LATE ? 12000 : 8500);
                    while (trial.verifiedAt == 0 && SystemClock.elapsedRealtime() < end) {
                        if (!arrives && trial.handoffAt != 0) break;
                        SystemClock.sleep(80);
                    }
                    String foreground = foreground();
                    String expected = arrives ? page.action : scenario == Case.WRONG_PAGE
                            ? Page.values()[(page.ordinal() + 1) % Page.values().length].action : Settings.ACTION_SETTINGS;
                    assertEquals("Unexpected physical foreground", component(expected), foreground);
                    JSONObject row = trial.result(foreground.equals(component(page.action)));
                    rows.put(row);
                    Files.write(output.toPath(), rows.toString(2).getBytes(StandardCharsets.UTF_8));
                } finally {
                    instrumentation.runOnMainSync(() -> { if (trial.controller != null) trial.controller.close(); });
                }
            }
        }
        assertEquals(Page.values().length * Case.values().length, rows.length());
    }

    private Intent intent(String action) { return new Intent(action).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }
    private void open(String action) {
        launch("am start -W --activity-clear-top -a " + action + " -n " + component(action));
    }
    private void launch(String command) {
        // Start as shell so the diagnostic package never needs to take foreground or overlay the device.
        try (BufferedReader input = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(
                ui.executeShellCommand(command)),
                StandardCharsets.UTF_8))) {
            boolean launched = false;
            String line;
            while ((line = input.readLine()) != null) if (line.trim().equals("Status: ok")) launched = true;
            assertTrue("Android could not open the study page", launched);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private String component(String action) {
        return intent(action).resolveActivity(context.getPackageManager()).flattenToShortString();
    }
    private String foreground() throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys activity activities")),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) if (line.contains("topResumedActivity=")) {
                for (String field : line.split("\\s+")) if (field.startsWith(PKG + "/")) return field.replace("}", "");
            }
        }
        return "";
    }
    private void awaitForeground(String action) throws Exception {
        String expected = component(action);
        long end = SystemClock.elapsedRealtime() + 4500;
        while (SystemClock.elapsedRealtime() < end) {
            if (expected.equals(foreground())) { SystemClock.sleep(450); return; }
            SystemClock.sleep(120);
        }
        assertEquals(expected, foreground());
    }

    private ScreenSnapshot snapshot(String pkg) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null) return null;
        try {
            return pkg.contentEquals(String.valueOf(root.getPackageName()))
                    ? new AccessibilitySnapshotCollector().collect(root, pkg, context, null) : null;
        } finally { root.recycle(); }
    }

    @Test public void inspectSelectedTabAfterRealClockNavigation() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("clockOutcome")));
        connect();
        launch("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n " + CLOCK + "/.ClockPackage");
        SystemClock.sleep(700);
        ScreenSnapshot entry = snapshot(CLOCK);
        assertNotNull("Clock window unavailable", entry);
        List<String> tabs = List.of("알람", "세계시각", "스톱워치", "타이머");
        String original = null;
        for (var node : entry.nodes) {
            if (!node.selected || node.bounds[1] < entry.screenHeight / 2) continue;
            for (String tab : tabs) if (tab.equals(node.text) || tab.equals(node.contentDescription)) original = tab;
        }
        JSONObject check = new JSONObject().put("package", CLOCK).put("label", "스톱워치").put("role", "tab").put("selected", true);
        JSONObject condition = new JSONObject().put("checks", new JSONArray().put(check));
        JSONArray samples = new JSONArray();
        AppTaskMemory memory = new AppTaskMemory("스톱워치 탭으로 이동해줘");
        memory.completion(condition);
        AppTaskController.Listener observer = new AppTaskController.Listener() {
            public ScreenSnapshot fresh() { return snapshot(CLOCK); }
            public boolean canObserve() { return true; }
            public void busy(boolean busy) { }
            public void present(JSONObject response, ScreenSnapshot initial) { }
            public boolean inputMatches(String target, String hash) { return false; }
            public void failure(Throwable error) { throw new AssertionError(error); }
            public boolean execute(ScreenSnapshot initial, JSONObject observed, JSONObject action, String input, String selected) {
                throw new AssertionError("Manual tab navigation must not dispatch a second action");
            }
        };
        AppTaskController controller = new AppTaskController(new AppGuideGateway(context), memory, null, observer, false, false, true);
        boolean first = true;
        try {
            for (String tab : List.of("타이머", "세계시각", "스톱워치")) {
                tapClockTab(tab, entry.screenHeight);
                if (first) {
                    instrumentation.runOnMainSync(() -> { controller.targetApp(CLOCK, "시계"); controller.refresh(); });
                    first = false;
                } else {
                    instrumentation.runOnMainSync(() -> controller.screenChanged(snapshot(CLOCK)));
                }
                for (int frame = 0; frame < 2; frame++) {
                    SystemClock.sleep(500);
                    ScreenSnapshot current = snapshot(CLOCK);
                    assertNotNull(current);
                    boolean stopwatchBody = current.nodes.stream().anyMatch(n -> n.visibleToUser && n.resourceId != null
                            && n.bounds[1] < current.screenHeight * 3 / 4
                            && n.resourceId.matches(".*:id/stopwatch_.*(?:time|button|btn).*"));
                    JSONObject projected = AppTaskObservation.capture(current, AppTaskCompletion.labels(condition));
                    var verdict = AppTaskCompletion.checkForGoal("스톱워치 탭으로 이동해줘", condition, projected, current);
                    samples.put(new JSONObject().put("tab", tab).put("frame", frame + 1)
                            .put("stopwatch_body", stopwatchBody).put("verdict", verdict.name())
                            .put("tab_nodes", tabNodes(current)));
                }
                // Read the actual controller's two-frame decision on its owning thread.
                JSONObject summary = samples.getJSONObject(samples.length() - 1);
                instrumentation.runOnMainSync(() -> {
                    try { summary.put("controller_supported", memory.completionSupported()).put("user_confirmed", memory.finished()); }
                    catch (org.json.JSONException error) { throw new AssertionError(error); }
                });
            }
            Files.write(new File(context.getFilesDir(), "clock-outcomes.json").toPath(), samples.toString(2).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                boolean reached = "스톱워치".equals(sample.getString("tab"));
                assertEquals("Independent clock body oracle", reached, sample.getBoolean("stopwatch_body"));
                if ("true".equals(InstrumentationRegistry.getArguments().getString("requireClockMatch"))) {
                    assertEquals("Requested tab proof", reached, "MATCHED".equals(sample.getString("verdict")));
                    if (sample.has("controller_supported")) {
                        assertEquals(reached, sample.getBoolean("controller_supported"));
                        assertFalse(sample.getBoolean("user_confirmed"));
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync(controller::close);
            if (original != null) tapClockTab(original, entry.screenHeight);
        }
    }
    private JSONArray tabNodes(ScreenSnapshot screen) throws Exception {
        JSONArray rows = new JSONArray();
        for (var node : screen.nodes) {
            if (node.bounds[1] < screen.screenHeight / 2 || !("스톱워치".equals(node.text)
                    || "스톱워치".equals(node.contentDescription))) continue;
            for (var current = node; current != null;) {
                rows.put(new JSONObject().put("class", current.className).put("resource", current.resourceId)
                        .put("selected", current.selected).put("clickable", current.clickable)
                        .put("enabled", current.enabled).put("has_text", "스톱워치".equals(current.text))
                        .put("has_description", "스톱워치".equals(current.contentDescription)));
                if (rows.length() >= 8) return rows;
                String parent = current.parentId;
                current = screen.nodes.stream().filter(n -> n.nodeId.equals(parent)).findFirst().orElse(null);
            }
        }
        return rows;
    }
    private void tapClockTab(String label, int height) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        assertNotNull(root);
        try {
            assertEquals(CLOCK, String.valueOf(root.getPackageName()));
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
            try {
                for (AccessibilityNodeInfo node : matches) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (!node.isVisibleToUser() || bounds.top < height / 2
                            || !(label.contentEquals(String.valueOf(node.getText())) || label.contentEquals(String.valueOf(node.getContentDescription())))) continue;
                    AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
                    while (current != null) {
                        AccessibilityNodeInfo parent;
                        try {
                            if (current.isSelected() || current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
                            parent = current.getParent();
                        } finally { current.recycle(); }
                        current = parent;
                    }
                }
            } finally { for (AccessibilityNodeInfo match : matches) match.recycle(); }
        } finally { root.recycle(); }
        fail("Could not select the public Clock tab " + label);
    }

    private final class Trial implements AppTaskController.Listener {
        final Page page;
        final Case scenario;
        final AppTaskMemory memory;
        AppTaskController controller;
        JSONObject panel;
        volatile long started, verifiedAt, handoffAt;
        int executions;
        Trial(Page page, Case scenario) throws Exception {
            this.page = page;
            this.scenario = scenario;
            memory = new AppTaskMemory(page.label + " 설정 화면을 열어줘");
            memory.localPlan();
            JSONObject check = new JSONObject().put("package", PKG).put("label", page.label).put("role", "text");
            memory.completion(new JSONObject().put("checks", new JSONArray().put(check)));
        }
        void start() {
            try {
                controller = new AppTaskController(new AppGuideGateway(context), memory, null, this, false, false, true);
                controller.targetApp(PKG, "설정");
                controller.chooseTool("back");
                // Supply a deterministic planner expectation on the real proposal before approval.
                // The executor outcome/arrival is controlled outside this listener in both builds.
                JSONObject action = panel.getJSONObject("action");
                if (scenario != Case.NORMAL) action.put("expectation", new JSONObject()
                        .put("kind", "target_visible").put("label", "예측한 중간 화면"));
                started = SystemClock.elapsedRealtime();
                controller.execute(action.getString("id"), null);
            } catch (Exception error) { throw new AssertionError(error); }
        }
        public ScreenSnapshot fresh() {
            return snapshot(PKG);
        }
        public boolean execute(ScreenSnapshot before, JSONObject observed, JSONObject action, String input, String selected) {
            executions++;
            return scenario != Case.REJECTED;
        }
        public void present(JSONObject response, ScreenSnapshot screen) {
            panel = response;
            JSONObject assessment = response.optJSONObject("result_assessment");
            if (assessment != null && "verified".equals(assessment.optString("status")) && verifiedAt == 0)
                verifiedAt = SystemClock.elapsedRealtime();
            if ("needs_input".equals(response.optString("status")) && started > 0 && handoffAt == 0)
                handoffAt = SystemClock.elapsedRealtime();
        }
        public boolean canObserve() { return true; }
        public boolean inputMatches(String target, String hash) { return false; }
        public void busy(boolean busy) { }
        public void failure(Throwable error) { throw new AssertionError(error); }
        JSONObject result(boolean reached) throws Exception {
            JSONArray attempts = memory.state().getJSONArray("attempts");
            JSONObject last = attempts.optJSONObject(attempts.length() - 1);
            return new JSONObject().put("page", page.name()).put("case", scenario.name()).put("reached", reached)
                    .put("verified_ms", verifiedAt == 0 ? JSONObject.NULL : verifiedAt - started)
                    .put("handoff_ms", handoffAt == 0 ? JSONObject.NULL : handoffAt - started)
                    .put("attempt", last == null ? JSONObject.NULL : last.getString("outcome"))
                    .put("executions", executions).put("attempt_count", attempts.length())
                    .put("user_confirmed", memory.finished()).put("status", panel.getString("status"));
        }
    }
}
