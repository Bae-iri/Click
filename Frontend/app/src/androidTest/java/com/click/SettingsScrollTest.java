package com.click;

import android.content.Context;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.ComponentName;
import android.provider.Settings;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.ClickAccessibilityService;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in device test: scroll settings, read newly visible rows, then restore the list. */
@RunWith(AndroidJUnit4.class)
public class SettingsScrollTest {
    @Test public void scrollingCollectsPreviouslyOffscreenSettingsRows() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveScroll")));
        InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Instrumentation restarts the app process. Rebind only an already-enabled Click service.
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName component = new ComponentName(context, ClickAccessibilityService.class);
        java.util.List<String> others = new java.util.ArrayList<>();
        boolean clickEnabled = false;
        if (enabled != null) for (String entry : enabled.split(":")) {
            if (component.equals(ComponentName.unflattenFromString(entry))) clickEnabled = true;
            else others.add(entry);
        }
        assertTrue("Enable Click accessibility before running this test", clickEnabled);
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", others));
            SystemClock.sleep(300);
        } finally {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            automation.dropShellPermissionIdentity();
        }
        context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ScreenSnapshot before = null;
        for (int i = 0; i < 30; i++) {
            SystemClock.sleep(200);
            before = ClickAccessibilityService.freshSnapshot();
            if (before != null && ("com.android.settings".equals(before.packageName)
                    || "com.samsung.android.settings".equals(before.packageName))
                    && before.activeWindowId != null && before.nodes.stream()
                    .anyMatch(n -> n.visibleToUser && n.enabled && n.scrollable)) break;
        }
        assertNotNull("Enable Click accessibility before running this test", before);
        assertNotNull(before.activeWindowId);
        ScreenSnapshot initial = before;
        assertFalse(ClickAccessibilityService.performSettingsScroll(true, "com.example.other", initial.activeWindowId));
        assertFalse(ClickAccessibilityService.performSettingsScroll(true, initial.packageName, -1));
        boolean forward = true;
        boolean moved = ClickAccessibilityService.performSettingsScroll(true, initial.packageName, initial.activeWindowId);
        if (!moved) {
            forward = false;
            moved = ClickAccessibilityService.performSettingsScroll(false, initial.packageName, initial.activeWindowId);
        }
        assertTrue("Settings list did not scroll", moved);
        try {
            ScreenSnapshot after = null;
            Set<String> newLabels = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                SystemClock.sleep(200);
                after = ClickAccessibilityService.freshSnapshot();
                if (after != null) {
                    newLabels = labels(after);
                    newLabels.removeAll(labels(initial));
                    if (!newLabels.isEmpty()) break;
                }
            }
            assertNotNull(after);
            assertEquals(initial.packageName, after.packageName);
            assertEquals(initial.activeWindowId, after.activeWindowId);
            assertFalse("No newly visible settings text was collected after scrolling", newLabels.isEmpty());
            android.util.Log.i("ClickScrollTest", "fresh_screen=true newly_visible_labels=" + newLabels.size());
        } finally {
            ClickAccessibilityService.performSettingsScroll(!forward, initial.packageName, initial.activeWindowId);
        }
    }

    private Set<String> labels(ScreenSnapshot screen) {
        Set<String> result = new HashSet<>();
        screen.nodes.stream().filter(n -> n.visibleToUser && n.text != null)
                .forEach(n -> result.add(n.text));
        return result;
    }
}
