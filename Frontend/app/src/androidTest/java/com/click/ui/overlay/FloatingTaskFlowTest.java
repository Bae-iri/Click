package com.click.ui.overlay;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import com.click.R;
import com.click.domain.goal.RequirementReview;
import android.widget.TextView;
import com.click.data.remote.VoiceQueueGateway;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingTaskFlowTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void actualPauseAndEndButtonsStopWaitingBlockedAndOrphanedPanels() throws Exception {
        Context app = instrumentation.getTargetContext();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        // Instrumentation restarts the app process; rebind the already-enabled
        // service so this test uses the same real overlay WindowManager as the app.
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original = Settings.Secure.getString(app.getContentResolver(), key);
        String component = "com.click/com.click.service.accessibility.ClickAccessibilityService";
        assertNotNull(original);
        assertTrue(java.util.Arrays.asList(original.split(":")).contains(component));
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            List<String> others = new ArrayList<>(java.util.Arrays.asList(original.split(":")));
            others.remove(component);
            Settings.Secure.putString(app.getContentResolver(), key, String.join(":", others));
            SystemClock.sleep(250);
        } finally {
            Settings.Secure.putString(app.getContentResolver(), key, original);
            automation.dropShellPermissionIdentity();
        }
        Intent serviceIntent = new Intent(app, ClickOverlayService.class);
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            await(() -> ClickAccessibilityService.accessibilityWindowManager() != null);
            scenario.onActivity(activity -> activity.startService(serviceIntent));
            await(() -> service() != null && control() != null && control().isAttachedToWindow());
            final TextView[] stalePause = {null};
            instrumentation.runOnMainSync(() -> {
                // Real windows and button listeners, without sending test work to the server.
                VoiceQueueGateway queue = (VoiceQueueGateway) field(service(), "queueGateway");
                if (queue != null) queue.close();
                setField(service(), "queueGateway", null);
                setField(service(), "agentSessionId", "local-control-check");
                setField(service(), "requestActive", true);
                setField(service(), "turnInFlight", true);
                invoke(service(), "ensureTaskFlow");
                guide().waiting("응답을 기다리고 있어요");
                stalePause[0] = findText(guide(), "일시정지");
                stalePause[0].performClick();
                assertTrue((Boolean) field(service(), "agentPaused"));
                assertEquals("pause_task", field(service(), "pendingControl"));
                assertNotNull(findText(guide(), "이어서 진행"));
                assertNull(field(service(), "missingScreenRetry"));
            });
            screenshot("floating-controls-paused-immediately");
            instrumentation.runOnMainSync(() -> {
                findText(guide(), "끝내기").performClick();
                assertNull(guide()); assertNull(field(service(), "agentSessionId"));
                assertFalse((Boolean) field(service(), "turnInFlight"));
                stalePause[0].performClick();
                assertNull("A removed panel must never restart the task", guide());
                invoke(service(), "ensureTaskFlow");
                guide().waiting("남아 있는 안내창");
                setField(service(), "requestActive", false);
                findText(guide(), "끝내기").performClick();
                assertNull("End closes a panel even without an active request", guide());
                setField(service(), "agentSessionId", "blocked-control-check");
                try {
                    Method apply = ClickOverlayService.class.getDeclaredMethod("applyTaskFlowResponse",
                            com.click.data.remote.AgentTurnContract.Response.class);
                    apply.setAccessible(true);
                    apply.invoke(service(), new com.click.data.remote.AgentTurnContract.Response(new org.json.JSONObject()
                            .put("request_id", "blocked-check").put("session_id", "blocked-control-check")
                            .put("task_status", "BLOCKED").put("message", "현재 화면을 확인하지 못했어요.").toString()));
                } catch (Exception error) { throw new AssertionError(error); }
                assertTrue((Boolean) field(service(), "agentPaused"));
                assertFalse((Boolean) field(service(), "requestActive"));
                assertFalse(findText(guide(), "일시정지").isShown());
                assertTrue(findText(guide(), "끝내기").isShown());
            });
            screenshot("floating-controls-blocked-end-only");
            instrumentation.runOnMainSync(() -> {
                findText(guide(), "끝내기").performClick();
                assertNull(guide()); assertNull(field(service(), "agentSessionId"));
            });
        } finally {
            app.stopService(serviceIntent);
            await(() -> service() == null);
        }
    }

    @Test public void guidanceAndInputNeverShareTheScreenAndUpdatesPreserveDraft() throws Exception {
        Context app = instrumentation.getTargetContext();
        com.click.data.local.TouchAutomationSettings mode = new com.click.data.local.TouchAutomationSettings(app);
        boolean originalAutomaticMode = mode.isEnabled();
        // This scenario checks spoken manual guidance; automatic mode intentionally stays quiet.
        mode.setEnabled(false);
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original = Settings.Secure.getString(app.getContentResolver(), key);
        int enabled = Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        String originalKeyboard = Settings.Secure.getString(app.getContentResolver(), "show_ime_with_hard_keyboard");
        String component = "com.click/com.click.service.accessibility.ClickAccessibilityService";
        Intent serviceIntent = new Intent(app, ClickOverlayService.class);
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            Settings.Secure.putInt(app.getContentResolver(), "show_ime_with_hard_keyboard", 1);
            List<String> services = new ArrayList<>();
            if (original != null) for (String name : original.split(":"))
                if (!name.isEmpty() && !name.equals(component)) services.add(name);
            Settings.Secure.putString(app.getContentResolver(), key, String.join(":", services));
            SystemClock.sleep(250);
            services.add(component);
            Settings.Secure.putString(app.getContentResolver(), key, String.join(":", services));
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            await(() -> ClickAccessibilityService.accessibilityWindowManager() != null);
            scenario.onActivity(activity -> activity.startService(serviceIntent));
            await(() -> service() != null && control().isAttachedToWindow());
            assertHighlightBehindLogo(automation);
            instrumentation.runOnMainSync(() -> {
                // Keep this UI regression independent of shared-server task state.
                VoiceQueueGateway gateway = (VoiceQueueGateway) field(service(), "queueGateway");
                gateway.close();
                setField(service(), "queueGateway", null);
                ClickOverlayLayout input = control();
                assertNull("Opening Click must not restore a queued request", field(service(), "agentSessionId"));
                assertFalse((Boolean) field(service(), "turnInFlight"));
                com.click.designpreview.TutorialNarrator narrator =
                        (com.click.designpreview.TutorialNarrator) field(service(), "narrator");
                assertFalse((Boolean) field(service(), "voiceReplyEnabled"));
                invokeString(service(), "speak", "이전 요청의 늦은 안내");
                assertFalse("Unrequested speech must not be buffered", narrator.isSpeaking());
                input.openVoice();
                assertFalse(input.isVoiceInputActive());
                assertFalse((Boolean) field(service(), "acceptingVoiceCommand"));
                invokeString(service(), "speak", "입력 전 안내");
                assertFalse(narrator.isSpeaking());
                invokeString(service(), "submitRecognizedSpeech", "  ");
                assertFalse((Boolean) field(service(), "voiceReplyEnabled"));
                invokeString(service(), "submitRecognizedSpeech", "다시 말해줘");
                assertTrue((Boolean) field(service(), "voiceReplyEnabled"));
                assertTrue(narrator.isSpeaking());
                input.openText();
                assertTrue("Opening input does not mute future guidance", (Boolean) field(service(), "voiceReplyEnabled"));
                assertFalse("Changing input mode cancels pending speech", narrator.isSpeaking());
                invoke(service(), "ensureTaskFlow");
                TaskFlowOverlayView guide = guide();
                guide.waiting("현재 화면을 확인하고 있어요…");
                assertEquals(ClickOverlayLayout.Mode.COLLAPSED, input.getMode());
                assertEquals(View.VISIBLE, guide.getVisibility());
                assertEquals("Expanded guidance owns the only visible surface", View.GONE, input.getVisibility());
                guide.findViewById(R.id.task_flow_collapse).performClick();
                assertEquals("Waiting dialogue remains as a bubble", View.VISIBLE, guide.getVisibility());
                assertTrue(guide.isMinimizedProgress());
                assertEquals(View.VISIBLE, input.getVisibility());
                invoke(service(), "ensureTaskFlow");
                guide.progress("글자를 크게 보기", null, "다음 항목을 눌러주세요.");
                invoke(service(), "updateFloatingVisibility");
                assertTrue("Ongoing guidance stays in a compact bubble", guide.isMinimizedProgress());
                assertEquals(View.VISIBLE, guide.getVisibility());
                assertEquals("The bubble keeps the movable click button visible", View.VISIBLE, input.getVisibility());
                assertFalse(guide.findViewById(R.id.task_flow_collapse).isShown());
                findText(guide, "다음 항목을 눌러주세요.").performClick();
                assertFalse(guide.isMinimizedProgress());
                assertEquals(View.VISIBLE, guide.getVisibility());
                assertEquals(View.GONE, input.getVisibility());
                invokeString(service(), "speak", "다음 항목을 눌러주세요.");
                assertTrue(narrator.isSpeaking());
                assertNull(guide.findViewById(R.id.task_flow_input));
                guide.findViewById(R.id.task_flow_replay).performClick();
                assertFalse(narrator.isSpeaking());
                assertEquals(ClickOverlayLayout.Mode.COLLAPSED, input.getMode());
                guide.findViewById(R.id.task_flow_replay).performClick();
                assertTrue(narrator.isSpeaking());
                assertEquals(View.VISIBLE, guide.getVisibility());
                input.openVoice();
                assertEquals(ClickOverlayLayout.Mode.VOICE, input.getMode());
                assertFalse(narrator.isSpeaking());
                assertFalse("Opening input does not record", input.isVoiceInputActive());
                assertEquals(View.GONE, guide.getVisibility());
                assertEquals(View.VISIBLE, input.getVisibility());

                input.collapse();
                assertEquals("Closing input restores the current dialogue", View.VISIBLE, guide.getVisibility());
                assertTrue("Closing input resumes the interrupted current cue", narrator.isSpeaking());
                narrator.stop();

                input.openMenu();
                assertEquals(View.GONE, guide.getVisibility());
                input.openText();
                input.setDraftText("글자를 조금 더 크게");
                invoke(service(), "ensureTaskFlow");
                guide.progress("글자를 크게 보기", null, "글자 크기 버튼을 눌러 주세요.");
                assertEquals("A late task update must not close the editor", ClickOverlayLayout.Mode.TEXT, input.getMode());
                assertEquals(View.GONE, guide.getVisibility());
                assertEquals("글자를 조금 더 크게", input.getDraftText());
            });
            screenshot("floating-input-without-guide");
            instrumentation.runOnMainSync(() -> {
                control().collapse();
                assertEquals(View.VISIBLE, guide().getVisibility());
                assertEquals("글자를 조금 더 크게", control().getDraftText());
                control().openText();
                assertEquals(View.GONE, guide().getVisibility());
                service().onTextSubmitted("글자를 크게 해줘");
                assertTrue("Submitted text also enables spoken guidance", (Boolean) field(service(), "voiceReplyEnabled"));
                assertEquals(ClickOverlayLayout.Mode.COLLAPSED, control().getMode());
                assertEquals(View.VISIBLE, guide().getVisibility());
            });
            screenshot("floating-guide-without-input");
            instrumentation.runOnMainSync(() -> {
                guide().progress("글자를 크게 보기", null, "파란색으로 표시된 글자 크기 버튼을 눌러주세요.");
                guide().findViewById(R.id.task_flow_collapse).performClick();
                assertTrue(guide().isMinimizedProgress());
            });
            screenshot("floating-progress-bubble");
            instrumentation.runOnMainSync(() -> {
                android.view.WindowManager.LayoutParams before = (android.view.WindowManager.LayoutParams) guide().getLayoutParams();
                int oldY = before.y;
                service().onDragStarted();
                service().onDragBy(0, -120);
                ((Runnable) field(service(), "dragFrame")).run();
                assertNotEquals("The bubble follows the click button", oldY,
                        ((android.view.WindowManager.LayoutParams) guide().getLayoutParams()).y);
                service().onDragCancelled();
            });
            instrumentation.runOnMainSync(() -> {
                invoke(service(), "clearTaskFlow");
                control().openText();
                assertNull(guide());
                control().collapse();
                assertNull("A finished guide must not reappear", guide());
            });
            instrumentation.runOnMainSync(() -> {
                RequirementReview review = new RequirementReview("글자 크기를 키워줘");
                setField(service(), "requirementReview", review);
                invoke(service(), "ensureTaskFlow");
                guide().requirements(review, false);
                try {
                    Method place = ClickOverlayService.class.getDeclaredMethod("placeTaskFlow", int[].class, boolean.class);
                    place.setAccessible(true); place.invoke(service(), null, true);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                findText(guide(), "직접 입력하기").performClick();
            });
            await(() -> ClickAccessibilityService.keyboardBounds() != null);
            await(() -> {
                int[] location = new int[2]; guide().getLocationOnScreen(location);
                android.graphics.Rect keyboard = ClickAccessibilityService.keyboardBounds();
                if (keyboard == null) return false;
                int screenHeight = app.getResources().getDisplayMetrics().heightPixels;
                int margin = Math.round(64 * app.getResources().getDisplayMetrics().density);
                return !FloatingPanelPosition.isDockedKeyboard(screenHeight, keyboard.top, keyboard.bottom, margin)
                        ? guide().getHeight() > Math.round(160 * app.getResources().getDisplayMetrics().density)
                        : location[1] + guide().getHeight() <= keyboard.top;
            });
            screenshot("floating-request-editor-above-keyboard");
            instrumentation.runOnMainSync(() -> {
                android.view.inputmethod.InputMethodManager keyboard = app.getSystemService(android.view.inputmethod.InputMethodManager.class);
                keyboard.hideSoftInputFromWindow(guide().getWindowToken(), 0);
            });
            app.stopService(serviceIntent);
            await(() -> service() == null);
        } finally {
            app.stopService(serviceIntent);
            mode.setEnabled(originalAutomaticMode);
            Settings.Secure.putString(app.getContentResolver(), "show_ime_with_hard_keyboard", originalKeyboard);
            Settings.Secure.putString(app.getContentResolver(), key, original);
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled);
            automation.dropShellPermissionIdentity();
        }
    }

    private void assertHighlightBehindLogo(UiAutomation automation) throws Exception {
        int[] position = new int[2], size = new int[2];
        instrumentation.runOnMainSync(() -> {
            control().getLocationOnScreen(position);
            size[0] = control().getWidth(); size[1] = control().getHeight();
        });
        instrumentation.waitForIdleSync();
        SystemClock.sleep(300);
        Bitmap before = automation.takeScreenshot();
        assertNotNull(before);
        try {
            // Cross the opaque centre of the logo; the same border must remain
            // visible to its left while being occluded by the floating window.
            int left = position[0] - size[0], top = position[1] + size[1] / 2;
            int[] target = {left, top, position[0] + size[0], position[1] + size[1]};
            for (int pass = 0; pass < 3; pass++) {
                instrumentation.runOnMainSync(() -> {
                    try {
                        Method show = ClickOverlayService.class.getDeclaredMethod("showGuideOverlay",
                                int[].class, String.class, int.class);
                        show.setAccessible(true); show.invoke(service(), target, "tap", 0);
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                instrumentation.waitForIdleSync();
                SystemClock.sleep(300);
                Bitmap after = automation.takeScreenshot();
                assertNotNull(after);
                try {
                    int changedLogo = 0, changedBackground = 0;
                    for (int y = top; y < top + size[1] / 5; y++) {
                        for (int x = position[0] + size[0] / 3; x < position[0] + size[0] * 2 / 3; x++)
                            if (before.getPixel(x, y) != after.getPixel(x, y)) changedLogo++;
                        for (int x = left + size[0] / 3; x < left + size[0] * 2 / 3; x++)
                            if (before.getPixel(x, y) != after.getPixel(x, y)) changedBackground++;
                    }
                    assertTrue("The highlight must actually be visible beside the logo", changedBackground > 20);
                    assertEquals("A new, updated or restored highlight must stay behind the logo", 0, changedLogo);
                } finally { after.recycle(); }
                // Also exercise updating an attached highlight, then hiding/restoring it.
                if (pass == 1) instrumentation.runOnMainSync(() -> invoke(service(), "hideGuideOverlay"));
            }
            screenshot("floating-logo-above-highlight");
        } finally {
            before.recycle();
            instrumentation.runOnMainSync(() -> invoke(service(), "hideGuideOverlay"));
        }
    }

    @SuppressWarnings("unchecked")
    private ClickOverlayService service() {
        return ((WeakReference<ClickOverlayService>) field(null, "runningService")).get();
    }
    private ClickOverlayLayout control() { return (ClickOverlayLayout) field(service(), "overlayView"); }
    private TaskFlowOverlayView guide() { return (TaskFlowOverlayView) field(service(), "taskFlowView"); }
    private FloatingClickHost findHomeControl(View view) {
        if (view instanceof FloatingClickHost) return (FloatingClickHost) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            FloatingClickHost found = findHomeControl(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private TextView findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private Object field(Object target, String name) {
        return com.click.testutil.ServiceStateAccess.get(target,name);
    }
    private void setField(Object target, String name, Object value) {
        com.click.testutil.ServiceStateAccess.set(target,name,value);
    }
    private void invoke(Object target, String name) {
        try { Method m = ClickOverlayService.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void invokeString(Object target, String name, String value) {
        try { Method m = ClickOverlayService.class.getDeclaredMethod(name, String.class); m.setAccessible(true); m.invoke(target, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private interface Condition { boolean check(); }
    private void await(Condition condition) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            instrumentation.runOnMainSync(() -> ready.set(condition.check()));
            if (ready.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        try { screenshot("floating-timeout"); } catch (Exception ignored) {}
        fail("Overlay did not become ready.");
    }
    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(300);
        Bitmap image = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        image.recycle();
    }
}
