package com.click;

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

/** The usage guide is read-only; next/back never require practice or recording. */
@RunWith(AndroidJUnit4.class)
public class ClickTutorialFlowTest {
    @Test public void allExplanationCardsAreReadableAndAdvanceWithTheSameButton() throws Exception {
        int[] titles = {R.string.tutorial_explain_open_title, R.string.tutorial_explain_input_title,
            R.string.tutorial_explain_review_title, R.string.tutorial_explain_guidance_title,
            R.string.tutorial_explain_result_title};
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            for (int page = 0; page < titles.length; page++) {
                final int resource = titles[page];
                scenario.onActivity(activity -> {
                    assertEquals(activity.getString(resource),
                        ((TextView) activity.findViewById(R.id.tutorial_title)).getText().toString());
                    assertTrue(activity.findViewById(R.id.tutorial_next).isEnabled());
                    assertTrue(activity.findViewById(R.id.tutorial_next).isShown());
                    assertFalse(activity.getWindow().getDecorView().findFocus() instanceof android.widget.EditText);
                });
                capture(scenario, "tutorial-explanation-" + (page + 1) + ".png");
                if (page < titles.length - 1)
                    scenario.onActivity(a -> a.findViewById(R.id.tutorial_next).performClick());
            }
            scenario.recreate();
            scenario.onActivity(a -> assertEquals(a.getString(R.string.tutorial_explain_result_title),
                ((TextView) a.findViewById(R.id.tutorial_title)).getText().toString()));
            scenario.onActivity(a -> a.findViewById(R.id.tutorial_back).performClick());
            scenario.onActivity(a -> assertEquals(a.getString(R.string.tutorial_explain_guidance_title),
                ((TextView) a.findViewById(R.id.tutorial_title)).getText().toString()));
        }
    }

    private void capture(ActivityScenario<TutorialActivity> scenario, String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap[] frame = {null};
        int[] result = {-1};
        CountDownLatch copied = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            frame[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(activity.getWindow(), frame[0], status -> {
                result[0] = status;
                copied.countDown();
            }, new Handler(Looper.getMainLooper()));
        });
        assertTrue(copied.await(3, TimeUnit.SECONDS));
        assertEquals(PixelCopy.SUCCESS, result[0]);
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            frame[0].compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { frame[0].recycle(); }
    }
}
