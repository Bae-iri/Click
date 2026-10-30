package com.click.data.remote;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.service.overlay.GuidedActionTracker;
import com.click.service.accessibility.ClickAccessibilityService;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in emulator fixture capture; temporarily adjusts and restores media volume, without network calls. */
@RunWith(AndroidJUnit4.class)
public class NativeSettingsSnapshotTest {
    @Test public void capturesActualBrightnessAndMediaEvidence() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("nativeSettings")));
        assumeTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context app = instrumentation.getTargetContext();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original = Settings.Secure.getString(app.getContentResolver(), key);
        int enabled = Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS", "android.permission.MODIFY_AUDIO_SETTINGS");
        try {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            Settings.Secure.putString(app.getContentResolver(), key, "");
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            SystemClock.sleep(300);
            Settings.Secure.putString(app.getContentResolver(), key,
                    app.getPackageName() + "/com.click.service.accessibility.ClickAccessibilityService");
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            for (String action : new String[]{Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS}) {
                app.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                SystemClock.sleep(1500);
                ScreenSnapshot screen = null;
                for (int i = 0; i < 40; i++) {
                    screen = ClickAccessibilityService.freshSnapshot();
                    if (screen != null && "com.android.settings".equals(screen.packageName)
                            && screen.nodes.stream().anyMatch(n -> action.equals(Settings.ACTION_SOUND_SETTINGS)
                            ? n.progress != null && n.contentDescription != null && n.contentDescription.startsWith("Media volume")
                            : "Brightness level".equals(n.text))) break;
                    SystemClock.sleep(150);
                }
                assertNotNull(screen);
                assertEquals("com.android.settings", screen.packageName);
                JSONObject evidence = new JSONObject(screen.toJson()).getJSONObject("applied_settings");
                assertEquals(Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS),
                        evidence.getInt("screen_brightness"));
                assertEquals(app.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC),
                        evidence.getInt("media_volume"));
                String name = action.equals(Settings.ACTION_SOUND_SETTINGS) ? "volume" : "brightness";
                Files.write(new File(app.getCacheDir(), "native-" + name + ".json").toPath(),
                        screen.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (name.equals("volume")) {
                    AccessibilityNodeSnapshot target = screen.nodes.stream().filter(n -> n.progress != null
                            && n.contentDescription != null && n.contentDescription.startsWith("Media volume")).findFirst().get();
                    GuidedActionTracker tracker = new GuidedActionTracker();
                    tracker.bind("local", "local", "media-guide", "drag", target.nodeId, target.bounds, screen, System.currentTimeMillis());
                    AudioManager audio = app.getSystemService(AudioManager.class);
                    float changedValue = target.progress < target.rangeMax ? target.progress + 1 : target.progress - 1;
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (int) changedValue, 0);
                        SystemClock.sleep(700);
                        ScreenSnapshot after = ClickAccessibilityService.freshSnapshot();
                        assertNotNull(after);
                        assertEquals((int) changedValue, after.appliedMediaVolume.intValue());
                        AccessibilityNodeSnapshot moved = after.nodes.stream().filter(n -> n.nodeId.equals(target.nodeId)).findFirst().get();
                        assertNotNull(tracker.recordRangeChange(moved.packageName, moved.windowId, moved.nodeId,
                                moved.resourceId, moved.bounds, System.currentTimeMillis(), moved.progress, moved.rangeMin, moved.rangeMax));
                        Files.write(new File(app.getCacheDir(), "native-volume-changed.json").toPath(),
                                after.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } finally {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target.progress.intValue(), 0);
                    }
                }
                if (name.equals("brightness")) {
                    android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                    boolean opened = false;
                    for (android.view.accessibility.AccessibilityNodeInfo match : root.findAccessibilityNodeInfosByText("Brightness level")) {
                        for (android.view.accessibility.AccessibilityNodeInfo row = match; row != null; row = row.getParent()) {
                            if (row.isClickable()) { opened = row.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); break; }
                        }
                        if (opened) break;
                    }
                    assertTrue("AOSP brightness row must open its control", opened);
                    SystemClock.sleep(600);
                    ScreenSnapshot control = ClickAccessibilityService.freshSnapshot();
                    assertNotNull(control);
                    assertTrue(control.nodes.stream().anyMatch(n -> n.progress != null));
                    Files.write(new File(app.getCacheDir(), "native-brightness-control.json").toPath(),
                            control.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } finally {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            Settings.Secure.putString(app.getContentResolver(), key, original);
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled);
            automation.dropShellPermissionIdentity();
        }
    }

}
