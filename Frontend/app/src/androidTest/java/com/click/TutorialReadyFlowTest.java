package com.click;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.core.permission.PermissionStatus;
import com.click.service.accessibility.ClickAccessibilityService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TutorialReadyFlowTest {
    @Before public void connectPreviouslyEnabledService() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Context context = instrumentation.getTargetContext();
        org.junit.Assume.assumeTrue("Grant all three required permissions before testing the welcome screen",
                PermissionStatus.isAccessibilityEnabled(context)
                        && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        && Settings.canDrawOverlays(context));
        reconnectIfNeeded(context, automation);
    }

    static void reconnectIfNeeded(Context context, UiAutomation automation) {
        if (ClickAccessibilityService.accessibilityWindowManager() != null) return;
        // Instrumentation restarts the target app, disconnecting its previously enabled service.
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original = Settings.Secure.getString(context.getContentResolver(), key);
        ComponentName click = new ComponentName(context, ClickAccessibilityService.class);
        List<String> otherServices = new ArrayList<>(Arrays.asList(original.split(":")));
        otherServices.removeIf(value -> click.equals(ComponentName.unflattenFromString(value)));
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            Settings.Secure.putString(context.getContentResolver(), key, String.join(":", otherServices));
            SystemClock.sleep(400);
        } finally {
            Settings.Secure.putString(context.getContentResolver(), key, original);
            automation.dropShellPermissionIdentity();
        }
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (ClickAccessibilityService.accessibilityWindowManager() == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
        }
        assertNotNull("The enabled accessibility service must be connected", ClickAccessibilityService.accessibilityWindowManager());
    }

    @Test public void bothOnboardingEntriesStartAtTheHomeRequestField() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        for (Class<?> entry : new Class<?>[] {RequiredPermissionsActivity.class, TutorialReadyActivity.class}) {
            HomeActivity host = (HomeActivity) instrumentation.startActivitySync(new Intent(context, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            var displayStarts = new java.util.concurrent.atomic.AtomicInteger();
            Instrumentation.ActivityMonitor display = new Instrumentation.ActivityMonitor() {
                @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                    if (Settings.ACTION_DISPLAY_SETTINGS.equals(intent.getAction())) displayStarts.incrementAndGet();
                    return null;
                }
            };
            instrumentation.addMonitor(display);
            try {
                instrumentation.runOnMainSync(() -> {
                    host.closeTutorial();
                    assertNull(host.findViewById(R.id.tutorial_title));
                    host.startActivity(new Intent(host, entry)
                            .putExtra(RequiredPermissionsActivity.EXTRA_DESTINATION, "tutorial"));
                });
                long deadline = SystemClock.uptimeMillis() + 8000;
                boolean[] atInput = {false};
                String[] screen = {"no resumed activity"};
                while (!atInput[0] && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync();
                    instrumentation.runOnMainSync(() -> {
                        for (Activity resumed : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                            screen[0] = resumed.getLocalClassName();
                            if (!(resumed instanceof HomeActivity)) continue;
                            android.widget.TextView message = resumed.findViewById(R.id.tutorial_title);
                            atInput[0] = message != null && context.getString(R.string.tour_input).contentEquals(message.getText())
                                    && resumed.findViewById(R.id.home_request_input).isShown();
                            if (atInput[0]) return;
                        }
                    });
                    if (!atInput[0]) SystemClock.sleep(50);
                }
                assertTrue(entry.getSimpleName() + " should return to the Home input lesson, last screen " + screen[0], atInput[0]);
                assertEquals(0, displayStarts.get());
            } finally {
                instrumentation.removeMonitor(display);
                instrumentation.runOnMainSync(host::finish);
            }
        }
    }
}
