package com.click;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TutorialReadyFlowTest {
    @Test public void welcomeWaitsForStartEvenAfterRecreation() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor tutorial = instrumentation.addMonitor(
                TutorialActivity.class.getName(), null, false);
        Activity opened = null;
        try (ActivityScenario<TutorialReadyActivity> scenario = ActivityScenario.launch(TutorialReadyActivity.class)) {
            scenario.onActivity(this::assertWelcome);
            scenario.recreate();
            scenario.onActivity(this::assertWelcome);
            instrumentation.waitForIdleSync();
            assertEquals("The practice must wait for the user's start tap", 0, tutorial.getHits());
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
                    instrumentation.getTargetContext().getCacheDir(), "click-tutorial-ready.png"))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
            } finally { screenshot.recycle(); }
            scenario.onActivity(activity -> activity.findViewById(R.id.tutorial_ready_start).performClick());
            opened = instrumentation.waitForMonitorWithTimeout(tutorial, 3000);
            assertNotNull("Start must open the existing usage tutorial", opened);
            Activity practice = opened;
            instrumentation.runOnMainSync(() -> {
                assertNotNull(practice.findViewById(R.id.tutorial_floating_control));
                assertEquals("핵심만 함께\n연습해 볼까요?",
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

    private void assertWelcome(TutorialReadyActivity activity) {
        assertEquals("이제 클릭을 사용할 수\n있게 되었어요!",
                ((TextView) activity.findViewById(R.id.tutorial_ready_title)).getText().toString());
        TextView start = activity.findViewById(R.id.tutorial_ready_start);
        assertEquals("시작하기", start.getText().toString());
        assertTrue(start.isEnabled());
        assertEquals(View.VISIBLE, start.getVisibility());
        assertNull(activity.findViewById(R.id.tutorial_floating_control));
    }
}
