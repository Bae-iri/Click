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

    @Test public void guidanceAndInputNeverShareTheScreenAndUpdatesPreserveDraft() throws Exception {
        Context app = instrumentation.getTargetContext();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original = Settings.Secure.getString(app.getContentResolver(), key);
        int enabled = Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        String component = "com.click/com.click.service.accessibility.ClickAccessibilityService";
        Intent serviceIntent = new Intent(app, ClickOverlayService.class);
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            FloatingClickHost[] homeControl = {null};
            scenario.onActivity(activity -> homeControl[0] = findHomeControl(activity.getWindow().getDecorView()));
            assertNotNull(homeControl[0]);
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
            await(() -> !homeControl[0].isShown());
            instrumentation.runOnMainSync(() -> {
                // Keep this UI regression independent of shared-server task state.
                VoiceQueueGateway gateway = (VoiceQueueGateway) field(service(), "queueGateway");
                gateway.close();
                setField(service(), "queueGateway", null);
                ClickOverlayLayout input = control();
                assertNull("Opening Click must not restore a queued request", field(service(), "agentSessionId"));
                assertFalse((Boolean) field(service(), "turnInFlight"));
                boolean ready = (Boolean) field(service(), "ttsReady");
                setField(service(), "ttsReady", false);
                assertFalse((Boolean) field(service(), "voiceReplyEnabled"));
                invokeString(service(), "speak", "이전 요청의 늦은 안내");
                assertNull("Unrequested speech must not be buffered", field(service(), "pendingSpeech"));
                input.openVoice();
                assertFalse(input.isVoiceInputActive());
                assertFalse((Boolean) field(service(), "acceptingVoiceCommand"));
                invokeString(service(), "speak", "입력 전 안내");
                assertNull(field(service(), "pendingSpeech"));
                invokeString(service(), "submitRecognizedSpeech", "  ");
                assertFalse((Boolean) field(service(), "voiceReplyEnabled"));
                // A completed, nonempty microphone result enables spoken replies.
                invokeString(service(), "submitRecognizedSpeech", "다시 말해줘");
                assertTrue((Boolean) field(service(), "voiceReplyEnabled"));
                assertNotNull(field(service(), "pendingSpeech"));
                input.openText();
                assertFalse((Boolean) field(service(), "voiceReplyEnabled"));
                assertNull("Changing input mode clears pending speech", field(service(), "pendingSpeech"));
                setField(service(), "ttsReady", ready);
                invoke(service(), "ensureTaskFlow");
                TaskFlowOverlayView guide = guide();
                guide.waiting("현재 화면을 확인하고 있어요…");
                assertEquals(ClickOverlayLayout.Mode.COLLAPSED, input.getMode());
                assertEquals(View.VISIBLE, guide.getVisibility());

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
                assertEquals(ClickOverlayLayout.Mode.COLLAPSED, control().getMode());
                assertEquals(View.VISIBLE, guide().getVisibility());
            });
            screenshot("floating-guide-without-input");
            instrumentation.runOnMainSync(() -> {
                invoke(service(), "clearTaskFlow");
                control().openText();
                assertNull(guide());
                control().collapse();
                assertNull("A finished guide must not reappear", guide());
            });
            app.stopService(serviceIntent);
            await(() -> service() == null && homeControl[0].isShown());
        } finally {
            app.stopService(serviceIntent);
            Settings.Secure.putString(app.getContentResolver(), key, original);
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled);
            automation.dropShellPermissionIdentity();
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
    private Object field(Object target, String name) {
        try { Field f = ClickOverlayService.class.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void setField(Object target, String name, Object value) {
        try { Field f = ClickOverlayService.class.getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
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
