package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.NavigationRecovery;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ForegroundWindowReadTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void focusedOverlayStillReadsSettingsAndLauncher() throws Exception {
        Context app = instrumentation.getTargetContext();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original = Settings.Secure.getString(app.getContentResolver(), key);
        int enabled = Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        TextView[] guide = {null};
        WindowManager[] manager = {null};
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            // Instrumentation can stop an already-enabled service's process. Rebind
            // it explicitly; writing the same enabled value does not reconnect it.
            java.util.List<String> services = new java.util.ArrayList<>();
            android.content.ComponentName component = new android.content.ComponentName(app, ClickAccessibilityService.class);
            if (original != null) for (String value : original.split(":"))
                if (!value.isEmpty() && !component.equals(android.content.ComponentName.unflattenFromString(value))) services.add(value);
            Settings.Secure.putString(app.getContentResolver(), key, String.join(":", services));
            SystemClock.sleep(400);
            services.add(component.flattenToString());
            Settings.Secure.putString(app.getContentResolver(), key,
                    String.join(":", services));
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            await(() -> service() != null);
            app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> {
                ScreenSnapshot snapshot = ClickAccessibilityService.freshSnapshot();
                return snapshot != null && "com.android.settings".equals(snapshot.packageName) && !snapshot.nodes.isEmpty();
            });
            for (boolean launcher : new boolean[]{false, true}) {
                if (launcher) {
                    assertTrue(service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
                    await(() -> NavigationRecovery.isHomeScreen(ClickAccessibilityService.freshSnapshot()));
                }
                instrumentation.runOnMainSync(() -> {
                    guide[0] = new TextView(service());
                    guide[0].setText("화면을 함께 확인해요");
                    guide[0].setBackgroundColor(android.graphics.Color.WHITE);
                    guide[0].setFocusableInTouchMode(true);
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(600, 300,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.TOP | Gravity.LEFT;
                    params.y = 200;
                    manager[0] = service().getSystemService(WindowManager.class);
                    manager[0].addView(guide[0], params);
                    guide[0].requestFocus();
                });
                await(() -> {
                    AccessibilityNodeInfo active = service().getRootInActiveWindow();
                    if (active == null) return false;
                    try { return "com.click".contentEquals(active.getPackageName() == null ? "" : active.getPackageName()); }
                    finally { active.recycle(); }
                });
                // This is the real failure condition: Android reports Click as the active root.
                // Window lists can briefly lag the focus event. Require the native
                // observation to become readable while the guide keeps its focus.
                await(() -> {
                    ScreenSnapshot current = ClickAccessibilityService.freshSnapshot();
                    return current != null && !current.nodes.isEmpty()
                            && (launcher ? NavigationRecovery.isHomeScreen(current)
                                         : "com.android.settings".equals(current.packageName));
                });
                ScreenSnapshot snapshot = ClickAccessibilityService.freshSnapshot();
                assertNotNull("A focused guide must not make the screen unreadable: " + windowState(), snapshot);
                assertFalse("Unreadable native window: " + windowState(), snapshot.nodes.isEmpty());
                if (launcher) assertTrue(NavigationRecovery.isHomeScreen(snapshot));
                else assertEquals("com.android.settings", snapshot.packageName);
                assertTrue(snapshot.nodes.stream().anyMatch(node -> node.visibleToUser));
                assertTrue(snapshot.nodes.stream().noneMatch(node -> "com.click".equals(node.packageName)));
                instrumentation.runOnMainSync(() -> { manager[0].removeViewImmediate(guide[0]); guide[0] = null; });
            }
        } finally {
            instrumentation.runOnMainSync(() -> { if (guide[0] != null) manager[0].removeViewImmediate(guide[0]); });
            Settings.Secure.putString(app.getContentResolver(), key, original == null ? "" : original);
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled);
            automation.dropShellPermissionIdentity();
        }
    }
    private static String windowState() {
        StringBuilder state = new StringBuilder();
        AccessibilityNodeInfo active = service().getRootInActiveWindow();
        if (active != null) {
            state.append("active=").append(active.getWindowId()).append(":").append(active.getPackageName());
            active.recycle();
        }
        for (android.view.accessibility.AccessibilityWindowInfo window : service().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            state.append(" window=").append(window.getId()).append(" type=").append(window.getType())
                    .append(" layer=").append(window.getLayer()).append(" package=")
                    .append(root == null ? "null" : root.getPackageName());
            if (root != null) root.recycle();
            window.recycle();
        }
        return state.toString();
    }
    private static ClickAccessibilityService service() {
        try {
            Field field = ClickAccessibilityService.class.getDeclaredField("connectedService");
            field.setAccessible(true);
            return (ClickAccessibilityService) ((WeakReference<?>) field.get(null)).get();
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        }
        fail("Expected accessibility window was not ready: " + (service() == null ? "service disconnected" : windowState()));
    }
}
