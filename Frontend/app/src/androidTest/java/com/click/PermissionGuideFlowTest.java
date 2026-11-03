package com.click;

import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.designpreview.PermissionEnableAnimationView;
import com.click.designpreview.PermissionGuideActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PermissionGuideFlowTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private PermissionGuideActivity observedActivity;

    @Test public void fourPagesShowEnableAnimationThenConsentAndRetainNavigation() {
        try (ActivityScenario<PermissionGuideActivity> scenario = ActivityScenario.launch(PermissionGuideActivity.class)) {
            scenario.onActivity(activity -> {
                observedActivity = activity;
                assertPage(activity, 1);
                next(activity); assertPage(activity, 2);
                next(activity); assertPage(activity, 3);
                assertNotNull(activity.findViewById(com.click.designpreview.R.id.guide_enable_animation));
                assertNull(find(activity.getWindow().getDecorView(), "어떤 도움을\n드릴 수 있나요?"));
                assertNull(activity.findViewById(com.click.designpreview.R.id.guide_settings_shortcut));
            });
            awaitPhase(scenario, com.click.designpreview.R.string.guide_enable_step_apps, "apps");
            awaitPhase(scenario, com.click.designpreview.R.string.guide_enable_step_click, "click");
            awaitPhase(scenario, com.click.designpreview.R.string.guide_enable_step_switch, "off");
            awaitPhase(scenario, com.click.designpreview.R.string.guide_enable_step_done, "on");
            final ValueAnimator[] demo = {null};
            scenario.onActivity(activity -> {
                demo[0] = animator(activity);
                next(activity); assertPage(activity, 4);
                assertFalse("Leaving the animation page must cancel its loop", demo[0].isStarted());
                assertNotNull(activity.findViewById(com.click.designpreview.R.id.guide_consent_example));
                assertEquals("지금 설정하기", activity.findViewById(com.click.designpreview.R.id.guide_action).getContentDescription());
            });
            settle();
            scenario.onActivity(activity -> {
                readable(activity.getWindow().getDecorView());
                save(activity, "consent");
                ((ScrollView) activity.findViewById(com.click.designpreview.R.id.guide_scroll)).fullScroll(View.FOCUS_DOWN);
            });
            settle();
            scenario.onActivity(activity -> save(activity, "consent-bottom"));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertPage(activity, 4);
                assertNotNull(activity.findViewById(com.click.designpreview.R.id.guide_consent_example));
                activity.findViewById(com.click.designpreview.R.id.guide_back).performClick();
                assertPage(activity, 3);
                assertNotNull(activity.findViewById(com.click.designpreview.R.id.guide_enable_animation));
            });
        }
    }

    @Test public void animationStopsInBackgroundAndRestartsOnReturn() {
        try (ActivityScenario<PermissionGuideActivity> scenario = ActivityScenario.launch(PermissionGuideActivity.class)) {
            final ValueAnimator[] demo = {null};
            scenario.onActivity(activity -> { next(activity); next(activity); demo[0] = animator(activity); });
            settle();
            scenario.onActivity(activity -> assertTrue(demo[0].isStarted()));
            scenario.moveToState(Lifecycle.State.CREATED);
            instrumentation.runOnMainSync(() -> assertFalse(demo[0].isStarted()));
            scenario.moveToState(Lifecycle.State.RESUMED);
            settle();
            scenario.onActivity(activity -> assertTrue(demo[0].isStarted()));
        }
    }

    private void awaitPhase(ActivityScenario<PermissionGuideActivity> scenario, int label, String name) {
        long deadline = SystemClock.elapsedRealtime() + 11000;
        boolean[] found = {false};
        String[] last = {""};
        PermissionGuideActivity activity = observedActivity;
        instrumentation.runOnMainSync(() -> {
            if (activity.getResources().getConfiguration().fontScale > 1.2f) {
                ScrollView scroll = activity.findViewById(com.click.designpreview.R.id.guide_scroll);
                View preview = activity.findViewById(com.click.designpreview.R.id.guide_enable_animation);
                scroll.scrollTo(0, preview.getTop());
            }
        });
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> {
                last[0] = ((TextView) activity.findViewById(com.click.designpreview.R.id.guide_animation_step)).getText().toString();
                found[0] = activity.getString(label).equals(last[0]);
            });
            if (found[0]) {
                settle();
                instrumentation.runOnMainSync(() -> {
                    readable(activity.getWindow().getDecorView());
                    if (name.equals("off")) assertEquals(activity.getString(com.click.designpreview.R.string.guide_service_off),
                            ((TextView) activity.findViewById(com.click.designpreview.R.id.guide_demo_switch_state)).getText());
                    if (name.equals("on")) assertEquals(activity.getString(com.click.designpreview.R.string.guide_service_on),
                            ((TextView) activity.findViewById(com.click.designpreview.R.id.guide_demo_switch_state)).getText());
                    save(activity, name);
                });
                return;
            }
            SystemClock.sleep(80);
        }
        fail("Animation did not reach " + name + "; last observed: " + last[0]);
    }

    // A looping animation need not make the main queue idle. Allow its next layout frame.
    private void settle() { SystemClock.sleep(180); }
    private static void next(PermissionGuideActivity activity) {
        activity.findViewById(com.click.designpreview.R.id.guide_action).performClick();
    }
    private static void assertPage(PermissionGuideActivity activity, int page) {
        assertEquals(page + " / 4", ((TextView) activity.findViewById(com.click.designpreview.R.id.guide_step_counter)).getText());
    }
    private static ValueAnimator animator(PermissionGuideActivity activity) {
        try {
            Field field = PermissionEnableAnimationView.class.getDeclaredField("animation");
            field.setAccessible(true);
            return (ValueAnimator) field.get(activity.findViewById(com.click.designpreview.R.id.guide_enable_animation));
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static View find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = find(((ViewGroup) view).getChildAt(i), text); if (result != null) return result;
        }
        return null;
    }
    private static void readable(View view) {
        if (!view.isShown()) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() == null) return;
            for (int i = 0; i < text.getLineCount(); i++) {
                assertEquals(text.getText().toString(), 0, text.getLayout().getEllipsisCount(i));
                assertTrue("Horizontal clipping: " + text.getText(), text.getLayout().getLineMax(i)
                        <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
            }
            assertTrue("Vertical clipping: " + text.getText(), text.getLayout().getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            readable(((ViewGroup) view).getChildAt(i));
    }
    private static void save(PermissionGuideActivity activity, String stage) {
        View view = activity.getWindow().getDecorView();
        Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        String name = "permission-" + activity.getResources().getConfiguration().fontScale + "-" + stage + ".png";
        try (FileOutputStream out = new FileOutputStream(new File(activity.getCacheDir(), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) { throw new AssertionError(e); }
        image.recycle();
    }
}
