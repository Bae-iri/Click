package com.click;

import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.NavigationRecovery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in native navigation test; no app icons are tapped and no settings are changed. */
@RunWith(AndroidJUnit4.class)
public class LauncherPageScrollTest {
    @Test public void launcherSwipeActuallyRevealsDifferentIcons() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveScroll")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName click = new ComponentName(context, ClickAccessibilityService.class);
        ArrayList<String> others = new ArrayList<>();
        boolean clickEnabled = false;
        if (enabled != null) for (String entry : enabled.split(":")) {
            if (click.equals(ComponentName.unflattenFromString(entry))) clickEnabled = true;
            else others.add(entry);
        }
        assertTrue("Click accessibility must already be enabled", clickEnabled);
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", others));
            SystemClock.sleep(300);
        } finally {
            Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            automation.dropShellPermissionIdentity();
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(home);
        ScreenSnapshot before = null;
        for (int i = 0; i < 30; i++) {
            SystemClock.sleep(200);
            before = ClickAccessibilityService.freshSnapshot();
            if (NavigationRecovery.isHomeScreen(before) && before.activeWindowId != null && !icons(before).isEmpty()) break;
        }
        assertTrue("Read a launcher with visible icons", NavigationRecovery.isHomeScreen(before));
        assertNotNull(before.activeWindowId);
        boolean moved = false;
        try {
            for (boolean forward : new boolean[]{true, false}) {
                boolean dispatched = ClickAccessibilityService.swipeHomePage(forward, before.packageName, before.activeWindowId);
                if (!dispatched) continue;
                for (int i = 0; i < 15; i++) {
                    SystemClock.sleep(150);
                    ScreenSnapshot after = ClickAccessibilityService.freshSnapshot();
                    if (NavigationRecovery.isHomeScreen(after) && !icons(before).equals(icons(after))) {
                        moved = true;
                        android.util.Log.i("ClickScrollTest", "launcher changed_icons=true forward=" + forward);
                        ClickAccessibilityService.swipeHomePage(!forward, after.packageName, after.activeWindowId);
                        SystemClock.sleep(700);
                        break;
                    }
                }
                if (moved) break;
            }
            assertTrue("A dispatched launcher swipe must reveal different icons", moved);
        } finally {
            context.startActivity(home);
        }
    }

    private Set<String> icons(ScreenSnapshot screen) {
        Set<String> labels = new HashSet<>();
        if (screen == null) return labels;
        for (AccessibilityNodeSnapshot node : screen.nodes) {
            if (!node.visibleToUser || !node.clickable || !node.enabled || node.rangeMin != null) continue;
            String label = node.text != null ? node.text : node.contentDescription;
            if (label != null && !label.trim().isEmpty() && !label.matches(".*\\d{1,2}:\\d{2}.*")) labels.add(label);
        }
        return labels;
    }
}
