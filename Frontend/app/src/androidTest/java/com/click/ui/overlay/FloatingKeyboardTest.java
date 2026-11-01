package com.click.ui.overlay;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingKeyboardTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void homeMicrophoneOpensVoiceAndInputAvoidsKeyboard() throws Exception {
        Context app = instrumentation.getTargetContext();
        android.app.UiAutomation automation = instrumentation.getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.content.ContentResolver resolver = app.getContentResolver();
        String key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original = android.provider.Settings.Secure.getString(resolver, key);
        int originallyEnabled = android.provider.Settings.Secure.getInt(resolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        String component = "com.click/com.click.service.accessibility.ClickAccessibilityService";
        Intent serviceIntent = new Intent(app, ClickOverlayService.class);
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            // Instrumentation force-stops the app on startup. Rebind this service inside the test.
            java.util.List<String> others = new java.util.ArrayList<>();
            if (original != null) for (String service : original.split(":")) {
                if (!service.isEmpty() && !service.equals(component)) others.add(service);
            }
            android.provider.Settings.Secure.putString(resolver, key, String.join(":", others));
            SystemClock.sleep(250);
            others.add(component);
            android.provider.Settings.Secure.putString(resolver, key, String.join(":", others));
            android.provider.Settings.Secure.putInt(resolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            await(() -> ClickAccessibilityService.accessibilityWindowManager() != null);
            scenario.onActivity(activity -> activity.findViewById(com.click.R.id.home_microphone).performClick());
            await(() -> runningOverlay() != null && overlayPanel().isAttachedToWindow()
                    && overlayPanel().getMode() == ClickOverlayLayout.Mode.VOICE);
            awaitBackground(scenario);
            ClickOverlayService initialService = runningOverlay();
            instrumentation.runOnMainSync(() -> {
                assertEquals(android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        ((android.view.WindowManager.LayoutParams) overlayPanel().getLayoutParams()).type);
                assertNotNull(overlayPanel().findViewById(com.click.R.id.overlay_microphone));
                assertFalse(overlayPanel().isVoiceInputActive());
            });
            screenshot("home-microphone-system-voice");
            ClickOverlayLayout[] panel = {null};
            EditText[] input = {null};
            int[] restingY = {0};
            instrumentation.runOnMainSync(() -> {
                panel[0] = overlayPanel();
                panel[0].openText();
                input[0] = find(panel[0], EditText.class);
                restingY[0] = ((android.view.WindowManager.LayoutParams) panel[0].getLayoutParams()).y;
                input[0].setText("글자 크기");
                input[0].requestFocus();
            });
            await(() -> input[0].hasWindowFocus() && input[0].isLaidOut());
            instrumentation.runOnMainSync(() -> app.getSystemService(InputMethodManager.class)
                    .showSoftInput(input[0], InputMethodManager.SHOW_IMPLICIT));
            await(() -> {
                Rect keyboard = ClickAccessibilityService.keyboardBounds();
                if (keyboard == null) return false;
                int[] location = new int[2];
                panel[0].getLocationOnScreen(location);
                return location[1] + panel[0].getHeight() <= keyboard.top
                        && ((android.view.WindowManager.LayoutParams) panel[0].getLayoutParams()).y < restingY[0];
            });
            screenshot("floating-keyboard-overlay");
            instrumentation.runOnMainSync(() -> app.getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(input[0].getWindowToken(), 0));
            await(() -> ClickAccessibilityService.keyboardBounds() == null
                    && ((android.view.WindowManager.LayoutParams) panel[0].getLayoutParams()).y == restingY[0]);
            instrumentation.runOnMainSync(() -> assertEquals("글자 크기", input[0].getText().toString()));
            try (ActivityScenario<HomeActivity> reopened = ActivityScenario.launch(HomeActivity.class)) {
                reopened.onActivity(activity -> activity.findViewById(com.click.R.id.home_microphone).performClick());
                await(() -> overlayPanel().getMode() == ClickOverlayLayout.Mode.VOICE);
                awaitBackground(reopened);
                assertSame("Reopening from home must reuse the existing popup", initialService, runningOverlay());
            }
        } finally {
            app.stopService(serviceIntent);
            android.provider.Settings.Secure.putString(resolver, key, original);
            android.provider.Settings.Secure.putInt(resolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, originallyEnabled);
            automation.dropShellPermissionIdentity();
        }
    }

    @Test public void homeInputStaysAboveKeyboardAndPreservesDraft() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            EditText[] input = {null};
            scenario.onActivity(a -> {
                input[0] = a.findViewById(com.click.R.id.home_request_input);
                input[0].setText("화면 밝기를 높여줘");
                input[0].requestFocus();
                a.getSystemService(InputMethodManager.class).showSoftInput(input[0], InputMethodManager.SHOW_IMPLICIT);
            });
            await(() -> {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(input[0]);
                if (insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime())) return false;
                int[] location = new int[2]; input[0].getLocationOnScreen(location);
                return location[1] + input[0].getHeight() <= input[0].getRootView().getHeight()
                        - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            });
            screenshot("home-direct-input-keyboard");
            scenario.onActivity(a -> {
                assertFalse(a.findViewById(com.click.R.id.home_nav_home).isShown());
                a.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input[0].getWindowToken(), 0);
            });
            await(() -> !ViewCompat.getRootWindowInsets(input[0]).isVisible(WindowInsetsCompat.Type.ime()));
            scenario.recreate();
            scenario.onActivity(a -> {
                assertEquals("화면 밝기를 높여줘", ((EditText) a.findViewById(com.click.R.id.home_request_input)).getText().toString());
                assertTrue(a.findViewById(com.click.R.id.home_nav_home).isShown());
            });
        }
    }

    private ClickOverlayService runningOverlay() {
        return ((WeakReference<ClickOverlayService>) field(ClickOverlayService.class, null, "runningService")).get();
    }
    private ClickOverlayLayout overlayPanel() {
        return (ClickOverlayLayout) field(ClickOverlayService.class, runningOverlay(), "overlayView");
    }
    private Object field(Class<?> type, Object target, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @Test public void hostedInputMovesAboveRealKeyboardAndReturnsWithoutLosingText() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> activity.setContentView(new FloatingClickHost(activity,
                    new FloatingClickHost.Listener() {
                        @Override public void onVoiceRequested() {}
                        @Override public void onTextSubmitted(String text) {}
                    })));
            instrumentation.waitForIdleSync();
            ClickOverlayLayout[] panel = {null};
            EditText[] input = {null};
            float[] restingY = {0};
            scenario.onActivity(activity -> {
                panel[0] = find(activity.getWindow().getDecorView(), ClickOverlayLayout.class);
                panel[0].performClick();
                click(findText(panel[0], "글로 입력"));
                input[0] = find(panel[0], EditText.class);
            });
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                restingY[0] = panel[0].getY();
                input[0].setText("글자 크기");
                input[0].requestFocus();
                activity.getSystemService(InputMethodManager.class).showSoftInput(input[0], InputMethodManager.SHOW_IMPLICIT);
            });
            await(() -> {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(panel[0]);
                if (insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime())) return false;
                Rect visible = new Rect(), bounds = new Rect();
                panel[0].getWindowVisibleDisplayFrame(visible);
                panel[0].getGlobalVisibleRect(bounds);
                return bounds.bottom <= visible.bottom && panel[0].getY() < restingY[0];
            });
            screenshot("floating-keyboard-home");
            scenario.onActivity(activity -> {
                Rect visible = new Rect(), bounds = new Rect();
                input[0].getWindowVisibleDisplayFrame(visible);
                assertTrue(input[0].getGlobalVisibleRect(bounds));
                assertTrue("Input must be above keyboard", bounds.bottom <= visible.bottom);
                assertEquals("글자 크기", input[0].getText().toString());
                activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input[0].getWindowToken(), 0);
            });
            await(() -> {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(panel[0]);
                return insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
                        && Math.abs(panel[0].getY() - restingY[0]) < 2;
            });
            scenario.onActivity(activity -> assertEquals("글자 크기", input[0].getText().toString()));
        }
    }

    private interface Condition { boolean check(); }
    private void awaitBackground(ActivityScenario<HomeActivity> scenario) {
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (scenario.getState() != androidx.lifecycle.Lifecycle.State.CREATED
                && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        assertEquals("Home must be in the background while the system popup is open",
                androidx.lifecycle.Lifecycle.State.CREATED, scenario.getState());
    }

    private void await(Condition condition) {
        long deadline = SystemClock.uptimeMillis() + 8000;
        AtomicBoolean ready = new AtomicBoolean();
        int stable = 0;
        do {
            instrumentation.runOnMainSync(() -> ready.set(condition.check()));
            stable = ready.get() ? stable + 1 : 0;
            if (stable >= 3) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Keyboard position did not settle before timeout");
    }

    private void screenshot(String name) throws Exception {
        Bitmap image = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        image.recycle();
    }

    private void click(View view) {
        assertNotNull(view);
        while (!view.isClickable()) view = (View) view.getParent();
        view.performClick();
    }
    private TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView match = findText(((ViewGroup) view).getChildAt(i), text);
            if (match != null) return match;
        }
        return null;
    }
    private <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T match = find(((ViewGroup) view).getChildAt(i), type);
            if (match != null) return match;
        }
        return null;
    }
}
