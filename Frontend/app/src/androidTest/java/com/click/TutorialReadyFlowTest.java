package com.click;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.PixelCopy;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.core.permission.PermissionStatus;
import com.click.designpreview.ClueArtworkView;
import com.click.service.accessibility.ClickAccessibilityService;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        if (PermissionStatus.areRequiredPermissionsGranted(context)) return;
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
        while (!PermissionStatus.areRequiredPermissionsGranted(context) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
        }
        assertTrue("The enabled accessibility service must be connected", PermissionStatus.areRequiredPermissionsGranted(context));
    }

    @Test public void welcomeWaitsForStartEvenAfterRecreation() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertWelcomeFlow(new Intent(context, TutorialReadyActivity.class),
                context.getString(R.string.tutorial_ready_title), "click-tutorial-ready.png");
    }

    @Test public void completingRequiredPermissionsShowsClueBeforeStartingTheTour() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, RequiredPermissionsActivity.class)
                .putExtra(RequiredPermissionsActivity.EXTRA_DESTINATION, "tutorial");
        assertWelcomeFlow(intent, "이제 클릭을 사용할 수 있어요!", "click-permissions-ready.png");
    }

    private void assertWelcomeFlow(Intent intent, String heading, String screenshotName) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor tutorial = instrumentation.addMonitor(
                TutorialActivity.class.getName(), null, false);
        Activity opened = null;
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> assertWelcome(activity, heading));
            scenario.recreate();
            scenario.onActivity(activity -> assertWelcome(activity, heading));
            instrumentation.waitForIdleSync();
            assertEquals("The practice must wait for the user's start tap", 0, tutorial.getHits());
            CountDownLatch revealed = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                View content = activity.findViewById(android.R.id.content);
                content.postOnAnimation(new Runnable() {
                    @Override public void run() {
                        if (content.getAlpha() < 1f) content.postOnAnimation(this);
                        else content.postOnAnimation(revealed::countDown);
                    }
                });
            });
            assertTrue("The welcome screen must finish fading in", revealed.await(5, TimeUnit.SECONDS));
            // Capture this window so another app's floating overlay cannot obscure the preview.
            Bitmap[] frame = {null};
            int[] copyResult = {-1};
            CountDownLatch copied = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                View decor = activity.getWindow().getDecorView();
                frame[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                PixelCopy.request(activity.getWindow(), frame[0], result -> {
                    copyResult[0] = result;
                    copied.countDown();
                }, new Handler(Looper.getMainLooper()));
            });
            assertTrue(copied.await(3, TimeUnit.SECONDS));
            assertEquals(PixelCopy.SUCCESS, copyResult[0]);
            Bitmap screenshot = frame[0];
            try (FileOutputStream output = new FileOutputStream(new File(
                    instrumentation.getTargetContext().getCacheDir(), screenshotName))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
            } finally { screenshot.recycle(); }
            scenario.onActivity(activity -> activity.findViewById(R.id.tutorial_ready_start).performClick());
            opened = instrumentation.waitForMonitorWithTimeout(tutorial, 3000);
            assertNotNull("Start must open the existing usage tutorial", opened);
            Activity practice = opened;
            instrumentation.runOnMainSync(() -> {
                assertNotNull(practice.findViewById(R.id.tutorial_floating_control));
                assertEquals(practice.getString(R.string.tour_welcome),
                        ((TextView) practice.findViewById(R.id.tutorial_title)).getText().toString());
            });
        } finally {
            instrumentation.removeMonitor(tutorial);
            if (opened != null) {
                Activity practice = opened;
                instrumentation.runOnMainSync(practice::finish);
            }
        }
    }

    private void assertWelcome(Activity activity, String heading) {
        assertEquals(heading,
                ((TextView) activity.findViewById(R.id.tutorial_ready_title)).getText().toString());
        ClueArtworkView clue = activity.findViewById(R.id.permission_ready_clue);
        assertNotNull("The completion penguin must be on the welcome screen", clue);
        assertNotNull(clue.getDrawable());
        Rect visible = new Rect();
        assertTrue(clue.getLocalVisibleRect(visible));
        assertEquals(clue.getWidth(), visible.width());
        assertEquals(clue.getHeight(), visible.height());
        TextView start = activity.findViewById(R.id.tutorial_ready_start);
        assertEquals(activity.getString(R.string.tutorial_ready_start), start.getText().toString());
        assertTrue(start.isEnabled());
        assertEquals(View.VISIBLE, start.getVisibility());
        assertNull(activity.findViewById(R.id.tutorial_floating_control));
    }
}
