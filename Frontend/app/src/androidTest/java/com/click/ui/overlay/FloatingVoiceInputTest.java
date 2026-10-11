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
public class FloatingVoiceInputTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void installedPhoneHasUsableDefaultRecognizer() {
        Context app = instrumentation.getTargetContext();
        String configured = Settings.Secure.getString(app.getContentResolver(), "voice_recognition_service");
        assertNotNull(configured);
        assertFalse(configured.contains("TestRecognitionService"));
        android.content.ComponentName component = android.content.ComponentName.unflattenFromString(configured);
        assertNotNull(component);
        boolean visible = false;
        for (android.content.pm.ResolveInfo info : app.getPackageManager().queryIntentServices(
                new Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)) {
            if (component.equals(new android.content.ComponentName(info.serviceInfo.packageName, info.serviceInfo.name))) visible = true;
        }
        assertTrue("The configured recognition provider must be visible to Click", visible);
        assertTrue(android.speech.SpeechRecognizer.isRecognitionAvailable(app));
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                app.checkSelfPermission("android.permission.RECORD_AUDIO"));
    }

    @Test public void explicitMicrophoneShowsPartialAndFinalTextAndCancels() throws Exception {
        Context app = instrumentation.getTargetContext();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original = Settings.Secure.getString(app.getContentResolver(), key);
        String recognizer = Settings.Secure.getString(app.getContentResolver(), "voice_recognition_service");
        int enabled = Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        String component = "com.click/com.click.service.accessibility.ClickAccessibilityService";
        Intent serviceIntent = new Intent(app, ClickOverlayService.class);
        automation.grantRuntimePermission("com.click", "android.permission.RECORD_AUDIO");
        automation.grantRuntimePermission("com.click.test", "android.permission.RECORD_AUDIO");
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            Settings.Secure.putString(app.getContentResolver(), "voice_recognition_service",
                    "com.click.test/com.click.ui.overlay.TestRecognitionService");
            Settings.Secure.putString(app.getContentResolver(), key, component);
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            await(() -> ClickAccessibilityService.accessibilityWindowManager() != null);
            scenario.onActivity(activity -> activity.startService(serviceIntent));
            await(() -> service() != null && control().isAttachedToWindow());
            instrumentation.runOnMainSync(() -> {
                VoiceQueueGateway gateway = (VoiceQueueGateway) field(service(), "queueGateway");
                gateway.close();
                setField(service(), "queueGateway", null);
                control().openVoice();
                assertEquals(false, field(service(), "acceptingVoiceCommand"));
                assertTrue(android.speech.SpeechRecognizer.isRecognitionAvailable(app));
                assertTrue(hasText(control(), "마이크 버튼을 누른 뒤 말씀해 주세요.\n인식한 말이 아래에 표시돼요."));
            });
            screenshot("floating-voice-idle");
            instrumentation.runOnMainSync(() -> control().findViewById(com.click.R.id.overlay_microphone).performClick());
            await(() -> hasText(control(), "글자 크기"));
            screenshot("floating-voice-partial");
            await(() -> hasText(control(), "글자 크기를 크게 해줘"));
            instrumentation.runOnMainSync(() -> assertEquals(false, field(service(), "acceptingVoiceCommand")));
            screenshot("floating-voice-result");
            instrumentation.runOnMainSync(() -> control().findViewById(com.click.R.id.overlay_microphone).performClick());
            await(() -> hasText(control(), "글자 크기"));
            instrumentation.runOnMainSync(() -> control().findViewById(com.click.R.id.overlay_microphone).performClick());
            SystemClock.sleep(3000);
            instrumentation.runOnMainSync(() -> {
                assertEquals(false, field(service(), "acceptingVoiceCommand"));
                assertTrue(hasText(control(), "음성 입력을 취소했어요. 다시 입력하려면 마이크를 눌러주세요."));
                control().collapse();
                control().openVoice();
                assertEquals(false, field(service(), "acceptingVoiceCommand"));
            });
        } finally {
            app.stopService(serviceIntent);
            Settings.Secure.putString(app.getContentResolver(), "voice_recognition_service", recognizer);
            Settings.Secure.putString(app.getContentResolver(), key, original);
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled);
            automation.dropShellPermissionIdentity();
        }
    }
    private String allText(View view) {
        String value = view instanceof android.widget.TextView ? ((android.widget.TextView) view).getText().toString() + " | " : "";
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            value += allText(((ViewGroup) view).getChildAt(i));
        return value;
    }
    private boolean hasText(View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasText(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
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
    private interface Condition { boolean check(); }
    private void await(Condition condition) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            instrumentation.runOnMainSync(() -> ready.set(condition.check()));
            if (ready.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        final String[] actual = {""};
        instrumentation.runOnMainSync(() -> actual[0] = allText(control()));
        fail("Timed out: " + actual[0]);
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
