package com.click.service.accessibility;

import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.SettingsScreenPrivacy;
import com.click.ui.overlay.GuideOverlayView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in native capture-to-draw timing. No login, network, taps or setting changes. */
@RunWith(AndroidJUnit4.class)
public class HighlightLatencyProbeTest {
    @Test public void nativeScreenReachesAnActualOverlayFrame() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("highlightLatencyProbe")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context app = instrumentation.getTargetContext();
        assertFalse("Unlock the device before measuring", app.getSystemService(KeyguardManager.class).isDeviceLocked());
        assertTrue("Grant overlay access to the isolated probe app", Settings.canDrawOverlays(app));
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        SettingsScreenPrivacy privacy;
        try (var vocabulary = app.getAssets().open("vision_vocabulary.json");
             var policy = app.getAssets().open("screen_privacy_policy.json")) {
            privacy = SettingsScreenPrivacy.load(vocabulary, policy);
        }
        app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        automation.waitForIdle(300, 5000);
        WindowManager manager = app.getSystemService(WindowManager.class);
        GuideOverlayView[] guide = {null};
        AtomicLong requested = new AtomicLong();
        ArrayBlockingQueue<Long> frames = new ArrayBlockingQueue<>(1);
        ViewTreeObserver.OnDrawListener drawn = () -> {
            long at = requested.getAndSet(0);
            if (at != 0) frames.offer(SystemClock.elapsedRealtimeNanos());
        };
        JSONObject report = new JSONObject().put("source", "physical_device_native_capture_privacy_overlay_draw")
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("model", android.os.Build.MODEL)
            .put("network_requests", 0).put("setting_changes", 0)
            .put("excludes", new JSONArray().put("AI selection").put("HTTP").put("event debounce"));
        JSONArray samples = new JSONArray();
        try {
            instrumentation.runOnMainSync(() -> {
                guide[0] = new GuideOverlayView(app);
                guide[0].getViewTreeObserver().addOnDrawListener(drawn);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                manager.addView(guide[0], params);
            });
            for (int sample = 0; sample < 22; sample++) {
                long started = SystemClock.elapsedRealtimeNanos();
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                assertNotNull("Settings must expose a native root", root);
                ScreenSnapshot screen;
                try {
                    String pkg = String.valueOf(root.getPackageName());
                    assertTrue("Measure only Settings", pkg.equals("com.android.settings") || pkg.equals("com.samsung.android.settings"));
                    screen = new AccessibilitySnapshotCollector().collect(root, pkg, app, null);
                } finally { root.recycle(); }
                long captured = SystemClock.elapsedRealtimeNanos();
                JSONObject filtered = privacy.filter(new JSONObject().put("accessibility_tree", new JSONObject(screen.toJson())));
                long filteredAt = SystemClock.elapsedRealtimeNanos();
                int[] target = null;
                for (AccessibilityNodeSnapshot node : screen.nodes) {
                    if (node.visibleToUser && node.enabled && ("디스플레이".equals(node.text) || "Display".equals(node.text)
                            || "연결".equals(node.text) || "Connections".equals(node.text))) {
                        target = node.bounds; break;
                    }
                }
                assertNotNull("A reviewed public Settings menu must be visible", target);
                int[] bounds = target;
                long[] drawRequested = {0};
                instrumentation.runOnMainSync(() -> {
                    drawRequested[0] = SystemClock.elapsedRealtimeNanos();
                    requested.set(drawRequested[0]);
                    guide[0].showTarget(bounds, "tap", 0);
                });
                Long drawnAt = frames.poll(3, TimeUnit.SECONDS);
                assertNotNull("Overlay must reach onDraw", drawnAt);
                if (sample >= 2) samples.put(new JSONObject()
                    .put("capture_ms", (captured - started) / 1_000_000.0)
                    .put("privacy_ms", (filteredAt - captured) / 1_000_000.0)
                    .put("draw_wait_ms", (drawnAt - drawRequested[0]) / 1_000_000.0)
                    .put("capture_to_draw_ms", (drawnAt - started) / 1_000_000.0)
                    .put("native_nodes", screen.nodes.size())
                    .put("exported_nodes", filtered.getJSONObject("accessibility_tree").getJSONArray("nodes").length())
                    .put("navigation_fingerprint", screen.navigation == null ? JSONObject.NULL : screen.navigation.fingerprint));
                SystemClock.sleep(40);
            }
            report.put("samples", samples).put("assertions_passed", true);
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (guide[0] != null) {
                    guide[0].getViewTreeObserver().removeOnDrawListener(drawn);
                    if (guide[0].isAttachedToWindow()) manager.removeViewImmediate(guide[0]);
                }
            });
            report.put("overlay_removed", true);
            Files.write(new File(app.getCacheDir(), "highlight-latency-probe.json").toPath(),
                report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
}
