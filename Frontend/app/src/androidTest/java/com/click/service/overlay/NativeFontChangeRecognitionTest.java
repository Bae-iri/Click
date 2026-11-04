package com.click.service.overlay;

import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.remote.AgentTurnContract;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.ClickAccessibilityService;
import java.io.FileInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in Samsung regression: one native slider step, restored in finally; no API calls. */
@RunWith(AndroidJUnit4.class)
public class NativeFontChangeRecognitionTest {
    @Test public void detectsPersistedChangeAcrossSamsungWindowRecreation() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("nativeFontChange")));
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Unlock the phone before native validation", app.getSystemService(KeyguardManager.class).isDeviceLocked());
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        // Instrumentation restarts the target process; reconnect only Click's existing enabled service.
        String enabled = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = "com.click/com.click.service.accessibility.ClickAccessibilityService";
        assertNotNull(enabled);
        java.util.List<String> services = new java.util.ArrayList<>(java.util.Arrays.asList(enabled.split(":")));
        assertTrue("Click accessibility must already be enabled", services.remove(component));
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", services));
            SystemClock.sleep(300);
        } finally {
            Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            automation.dropShellPermissionIdentity();
        }
        float original = Settings.System.getFloat(app.getContentResolver(), Settings.System.FONT_SCALE);
        assertTrue(Float.isFinite(original) && original > 0 && original <= 10);
        app.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        openFontPage(automation);
        ScreenSnapshot before = waitForFontScreen();
        assertNotNull("Click accessibility must be connected on Samsung's font page", before);
        AccessibilityNodeSnapshot slider = slider(before);
        assertNotNull(slider); assertNotNull(slider.rangeMax); assertNotNull(slider.rangeMin);
        int direction = slider.progress < slider.rangeMax ? 1 : -1;
        String goal = "settings.font_size." + (direction > 0 ? "increase" : "decrease");
        JSONObject command = new JSONObject().put("command_type", "confirmation").put("action_type", "observe_only")
                .put("proposed_action", "drag").put("requires_user_action", true)
                .put("target_node_id", slider.nodeId).put("target_bounds", new JSONArray(slider.bounds));
        AgentTurnContract.GuideCommand guide = new AgentTurnContract.Response(new JSONObject().put("request_id", "native")
                .put("session_id", "local-test").put("task_status", "WAITING_CONFIRMATION").put("guide_command", command).toString()).guideCommand;
        AdjustmentResultCheck check = new AdjustmentResultCheck();
        check.bind("local-episode", goal, guide, before);
        try {
            tapValue(automation, slider, slider.progress + direction);
            ScreenSnapshot after = null;
            for (int i = 0; i < 50; i++) {
                SystemClock.sleep(150);
                after = ClickAccessibilityService.freshSnapshot();
                if (check.changed("local-episode", goal, after)) break;
            }
            assertTrue("A persisted native change must be recognized without a correlated drag event",
                    check.changed("local-episode", goal, after));
            android.util.Log.i("ClickNativeFontChange", "recognized=true before=" + before.appliedFontScale
                    + " after=" + after.appliedFontScale + " windowChanged="
                    + !java.util.Objects.equals(before.activeWindowId, after.activeWindowId));
        } finally {
            ScreenSnapshot current = waitForFontScreen();
            try { if (current != null) tapValue(automation, slider(current), slider.progress); }
            catch (AssertionError nativeRestoreFailed) { /* The saved system value below still restores it. */ }
            for (int i = 0; i < 30 && Math.abs(Settings.System.getFloat(app.getContentResolver(), Settings.System.FONT_SCALE) - original) > .001f; i++)
                SystemClock.sleep(100);
            if (Math.abs(Settings.System.getFloat(app.getContentResolver(), Settings.System.FONT_SCALE) - original) > .001f) {
                try (android.os.ParcelFileDescriptor fd = automation.executeShellCommand("settings put system font_scale " + Float.toString(original));
                     FileInputStream in = new FileInputStream(fd.getFileDescriptor())) { in.readAllBytes(); }
                SystemClock.sleep(500);
            }
            assertEquals("Restore the user's font size", original,
                    Settings.System.getFloat(app.getContentResolver(), Settings.System.FONT_SCALE), .001f);
            android.util.Log.i("ClickNativeFontChange", "restored=true fontScale=" + original);
        }
    }

    /** Resumes the already-running font request; uses the real authenticated gateway. */
    @Test public void existingFontRequestReachesResultWithMinimizeRetained() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("fontBackendFlow")));
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context app = instrumentation.getTargetContext();
        assertFalse("Unlock the phone before backend validation", app.getSystemService(KeyguardManager.class).isDeviceLocked());
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String enabled = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = "com.click/com.click.service.accessibility.ClickAccessibilityService";
        assertNotNull(enabled);
        java.util.List<String> services = new java.util.ArrayList<>(java.util.Arrays.asList(enabled.split(":")));
        assertTrue(services.remove(component));
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", services));
            SystemClock.sleep(300);
        } finally {
            Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            automation.dropShellPermissionIdentity();
        }
        float original = Settings.System.getFloat(app.getContentResolver(), Settings.System.FONT_SCALE);
        assertTrue(Float.isFinite(original) && original > 0 && original <= 10);
        app.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        openFontPage(automation);
        ScreenSnapshot before = waitForFontScreen(); assertNotNull(before);
        app.startService(new Intent(app, ClickOverlayService.class));
        ClickOverlayService service = null;
        for (int i = 0; i < 50 && service == null; i++) {
            SystemClock.sleep(100);
            java.lang.reflect.Field running = ClickOverlayService.class.getDeclaredField("runningService"); running.setAccessible(true);
            service = (ClickOverlayService) ((java.lang.ref.WeakReference<?>) running.get(null)).get();
        }
        assertNotNull(service);
        final ClickOverlayService active = service;
        boolean freshRequest = "true".equals(InstrumentationRegistry.getArguments().getString("freshFontBackendFlow"));
        com.click.data.local.TouchAutomationSettings automatic = new com.click.data.local.TouchAutomationSettings(app);
        boolean originalAutomatic = automatic.isEnabled();
        boolean startedFresh = false;
        try {
            if (freshRequest) {
                assertNull("Do not replace a running user request", field(active, "acceptedGoal"));
                automatic.setEnabled(false);
                app.startService(new Intent(app, ClickOverlayService.class)
                        .putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL, "휴대폰 전체 글자 크기 키워줘")
                        .putExtra(ClickOverlayService.EXTRA_SELECTED_GOAL_TYPE, "settings.font_size.increase"));
                startedFresh = true;
            } else instrumentation.runOnMainSync(() -> call(active, "claimNextQueuedRequest"));
            boolean ready = false;
            for (int i = 0; i < 160; i++) {
                SystemClock.sleep(250);
                final boolean[] state = {false};
                instrumentation.runOnMainSync(() -> {
                    if (freshRequest && field(active, "acceptedGoal") == null
                            && !(boolean) field(active, "turnInFlight")) call(active, "confirmRequirementReview");
                    JSONObject goal = (JSONObject) field(active, "acceptedGoal");
                    state[0] = goal != null && "settings.font_size.increase".equals(goal.optString("goal_type"))
                            && field(active, "lastAutomaticConfirmationId") != null
                            && field(active, "agentConfirmationId") == null && !(boolean) field(active, "turnInFlight");
                });
                if (state[0]) { ready = true; break; }
            }
            if (!ready) instrumentation.runOnMainSync(() -> {
                android.util.Log.i("ClickBackendFontFlow", "ready=false goal=" + field(active, "acceptedGoal")
                        + " inFlight=" + field(active, "turnInFlight") + " restored=" + field(active, "restoredStartedSession")
                        + " paused=" + field(active, "agentPaused") + " stale=" + field(active, "staleGuideDrops")
                        + " approved=" + (field(active, "lastAutomaticConfirmationId") != null)
                        + " screen=" + (ClickAccessibilityService.freshSnapshot() != null));
            });
            assertTrue("The real backend confirmation must be accepted and reach native slider guidance", ready);
            instrumentation.runOnMainSync(() -> {
                com.click.ui.overlay.TaskFlowOverlayView panel = (com.click.ui.overlay.TaskFlowOverlayView) field(active, "taskFlowView");
                android.view.View minimize = findText(panel, "줄이기");
                assertNotNull("Visible minimize control", minimize); assertTrue(minimize.performClick());
                assertTrue(panel.isMinimizedProgress());
            });
            ClickAccessibilityService.requestFreshSnapshot();
            SystemClock.sleep(1800);
            instrumentation.runOnMainSync(() -> {
                assertTrue("Response refresh must not reopen minimized UI", (boolean) field(active, "taskFlowCollapsed"));
                assertTrue(((com.click.ui.overlay.TaskFlowOverlayView) field(active, "taskFlowView")).isMinimizedProgress());
            });
            ScreenSnapshot current = waitForFontScreen(); assertNotNull(current);
            AccessibilityNodeSnapshot slider = slider(current); assertNotNull(slider);
            assertTrue("A larger step must exist", slider.progress < slider.rangeMax);
            tapValue(automation, slider, slider.progress + 1);
            SystemClock.sleep(700);
            android.util.Log.i("ClickBackendFontFlow", "nativeStep before=" + current.appliedFontScale
                    + " after=" + Settings.System.getFloat(app.getContentResolver(), Settings.System.FONT_SCALE)
                    + " target=" + java.util.Arrays.toString(slider.bounds));
            android.graphics.Bitmap diagnostic = automation.takeScreenshot();
            if (diagnostic != null) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(app.getCacheDir(), "native-font-after-tap.png"))) {
                    diagnostic.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                } finally { diagnostic.recycle(); }
            }
            boolean result = false;
            for (int i = 0; i < 160; i++) {
                SystemClock.sleep(250);
                final boolean[] state = {false};
                instrumentation.runOnMainSync(() -> state[0] = field(active, "resultPrompt") != null);
                if (state[0]) { result = true; break; }
            }
            assertTrue("A real persisted slider change must reach the server result prompt", result);
            SystemClock.sleep(500);
            android.graphics.Bitmap image = automation.takeScreenshot();
            assertNotNull(image);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(app.getCacheDir(), "native-font-result.png"))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally { image.recycle(); }
            android.util.Log.i("ClickBackendFontFlow", "confirmationAccepted=true minimizedRetained=true resultPrompt=true");
        } finally {
            if (startedFresh) {
                // Cancel only the test request; do not fabricate a satisfaction answer.
                instrumentation.runOnMainSync(active::onStopRequested);
                SystemClock.sleep(1500);
                automatic.setEnabled(originalAutomatic);
            }
            try (android.os.ParcelFileDescriptor fd = automation.executeShellCommand("settings put system font_scale " + Float.toString(original));
                 FileInputStream in = new FileInputStream(fd.getFileDescriptor())) { in.readAllBytes(); }
            SystemClock.sleep(500);
            assertEquals(original, Settings.System.getFloat(app.getContentResolver(), Settings.System.FONT_SCALE), .001f);
            android.util.Log.i("ClickBackendFontFlow", "restored=true fontScale=" + original);
        }
    }

    private static Object field(Object target, String name) {
        try { java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void call(Object target, String name) {
        try { java.lang.reflect.Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static android.view.View findText(android.view.View view, String text) {
        if (view.getVisibility() != android.view.View.VISIBLE) return null;
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = findText(group.getChildAt(i), text); if (found != null) return found;
            }
        }
        return null;
    }

    private void openFontPage(UiAutomation automation) {
        for (int attempt = 0; attempt < 8; attempt++) {
            SystemClock.sleep(500);
            if (slider(ClickAccessibilityService.freshSnapshot()) != null) return;
            android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) continue;
            try {
                if (!"com.android.settings".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                for (android.view.accessibility.AccessibilityNodeInfo label : root.findAccessibilityNodeInfosByText("글자 크기와 스타일")) {
                    android.view.accessibility.AccessibilityNodeInfo row = label;
                    while (row != null) {
                        if (row.isVisibleToUser() && row.isClickable()
                                && row.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                            row.recycle(); return;
                        }
                        android.view.accessibility.AccessibilityNodeInfo parent = row.getParent();
                        row.recycle(); row = parent;
                    }
                }
                scroll(root);
            } finally { root.recycle(); }
        }
    }

    private boolean scroll(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node.isScrollable() && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (scroll(child)) return true; } finally { child.recycle(); }
        }
        return false;
    }

    private ScreenSnapshot waitForFontScreen() {
        for (int i = 0; i < 50; i++) {
            ScreenSnapshot screen = ClickAccessibilityService.freshSnapshot();
            if (slider(screen) != null) return screen;
            SystemClock.sleep(150);
        }
        return null;
    }

    private AccessibilityNodeSnapshot slider(ScreenSnapshot screen) {
        if (screen == null || !"com.android.settings".equals(screen.packageName)) return null;
        for (AccessibilityNodeSnapshot node : screen.nodes)
            if ("com.android.settings:id/seekBarForFontSize".equals(node.resourceId) && node.visibleToUser
                    && node.enabled && node.progress != null && node.rangeMin != null && node.rangeMax != null) return node;
        return null;
    }

    private void tapValue(UiAutomation automation, AccessibilityNodeSnapshot slider, float value) {
        assertNotNull(slider);
        float fraction = (value - slider.rangeMin) / (slider.rangeMax - slider.rangeMin);
        float x = Math.max(slider.bounds[0] + 1, Math.min(slider.bounds[2] - 1,
                slider.bounds[0] + (slider.bounds[2] - slider.bounds[0]) * fraction));
        float y = (slider.bounds[1] + slider.bounds[3]) / 2f;
        long down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation.injectInputEvent(event, true)); } finally { event.recycle(); }
        SystemClock.sleep(100);
        event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation.injectInputEvent(event, true)); } finally { event.recycle(); }
    }
}
