package com.click;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.click.data.local.TouchAutomationSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ClickTutorialFlowTest {
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();

    @Test public void actualFloatingPanelsAndPhotosLeadThroughSettingsAndLibraryWithoutCoachButtons() throws Exception {
        assertEquals("tour_text", device.getTargetContext().getResources().getResourceEntryName(R.string.tour_text));
        assertEquals("task_flow_edit", device.getTargetContext().getResources().getResourceEntryName(R.id.task_flow_edit));
        TouchAutomationSettings automation = new TouchAutomationSettings(device.getTargetContext());
        boolean initialMode = automation.isEnabled();
        new com.click.data.local.LocalAccountStore(device.getTargetContext()).saveProfile("테스트", "");
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.tutorialIntent(device.getTargetContext()))) {
            words(R.string.tour_welcome);
            assertNoCoachButtons();
            capture(scenario, "welcome");
            tap(R.id.home_settings); words(R.string.tour_welcome);
            tap(R.id.tutorial_title); words(R.string.tour_pick);
            tap(R.id.home_request_input); words(R.string.tour_text);
            scenario.onActivity(a -> {
                EditText editor = a.findViewById(R.id.home_request_input);
                editor.setText("글자 크기 키워줘");
                a.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(editor.getWindowToken(), 0);
            });
            visible(v -> v.getId() == R.id.home_request_send
                    && !ViewCompat.getRootWindowInsets(v).isVisible(WindowInsetsCompat.Type.ime()));
            tap(R.id.home_request_send); words(R.string.tour_review);
            visible(v -> v.getId() == R.id.tutorial_practice_panel && v instanceof com.click.ui.overlay.TaskFlowOverlayView);
            capture(scenario, "review");
            assertNoCoachButtons();
            tap(v -> v instanceof TextView && "다시 입력하기".contentEquals(((TextView) v).getText())); words(R.string.tour_text);
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.home_request_input).isShown());
                assertFalse(a.findViewById(R.id.tutorial_practice_panel).isShown());
            });
            scenario.onActivity(a -> ((EditText) a.findViewById(R.id.home_request_input)).onEditorAction(EditorInfo.IME_ACTION_SEND));
            words(R.string.tour_review); tap(v -> v instanceof TextView && "안내 시작하기".contentEquals(((TextView) v).getText()));
            words(R.string.tour_floating); capture(scenario, "floating-guide");
            tap(R.id.tutorial_title); words(R.string.tour_actual_screen);
            assertPhoto(scenario); capture(scenario, "real-display-photo");
            tap(R.id.tutorial_settings_photo_target); words(R.string.tour_font_photo);
            assertPhoto(scenario); capture(scenario, "real-font-photo");
            tap(R.id.tutorial_settings_photo_target); words(R.string.tour_result);
            scenario.recreate(); words(R.string.tour_result); capture(scenario, "result");
            tap(R.id.task_flow_result_satisfied); words(R.string.tour_settings);
            tap(R.id.home_settings);
            visible(v -> v.getId() == R.id.tutorial_title && ((TextView) v).getText().toString().contains("테스트님께서 직접"));
            visible(v -> v.getId() == R.id.menu_automation_toggle);
            assertNoCoachButtons(); assertSpotlight(R.id.menu_automation_toggle, false);
            captureCurrent("automation");
            tap(R.id.tutorial_title); words(R.string.tour_library);
            assertSpotlight(R.id.home_nav_favorites, true); capture(scenario, "library-highlight");
            tap(R.id.home_nav_favorites); words(R.string.tour_saved);
            tap(R.id.home_library_recent); words(R.string.tour_recent);
            assertSpotlight(R.id.home_nav_home, true);
            capture(scenario, "recent");
            tap(R.id.home_nav_home);
            scenario.onActivity(a -> assertNull(a.findViewById(R.id.tutorial_floating_host)));
            assertEquals(initialMode, automation.isEnabled());
        }
    }

    @Test public void realVoiceControlCanBeCancelledToUseTheHomeEditor() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.tutorialIntent(device.getTargetContext()))) {
            tap(R.id.tutorial_title); tap(R.id.home_microphone); words(R.string.tour_voice);
            visible(v -> v.getId() == R.id.overlay_microphone);
            visible(v -> v.getId() == R.id.overlay_collapse);
            assertNoCoachButtons(); capture(scenario, "voice");
            tap(v -> v instanceof TextView && "취소".contentEquals(((TextView) v).getText()));
            words(R.string.tour_pick); tap(R.id.home_request_input); words(R.string.tour_text);
        }
    }

    @Test public void replayUsesTheOpenHomeAndRestoresItsDraftAfterRecreation() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.tutorialIntent(device.getTargetContext()))) {
            scenario.onActivity(home -> home.getOnBackPressedDispatcher().onBackPressed());
            scenario.onActivity(home -> {
                EditText editor = home.findViewById(R.id.home_request_input);
                editor.setText("나중에 보낼 요청"); editor.setSelection(3);
                home.onNewIntent(HomeActivity.tutorialIntent(home));
                assertEquals("", editor.getText().toString());
            });
            tap(R.id.tutorial_title); tap(R.id.home_request_input);
            scenario.recreate(); words(R.string.tour_text);
            for (int i = 0; i < 3; i++) scenario.onActivity(home -> home.getOnBackPressedDispatcher().onBackPressed());
            scenario.onActivity(home -> {
                EditText editor = home.findViewById(R.id.home_request_input);
                assertNull(home.findViewById(R.id.tutorial_floating_host));
                assertEquals("나중에 보낼 요청", editor.getText().toString()); assertEquals(3, editor.getSelectionEnd());
            });
        }
    }

    @Test public void penguinMovesAndRegularBubbleTextReplacesTheOldButtons() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.tutorialIntent(device.getTargetContext()))) {
            assertNoCoachButtons();
            float[] rotation = {0};
            scenario.onActivity(a -> {
                TextView message = a.findViewById(R.id.tutorial_title);
                assertEquals(a.getResources().getFont(com.click.designpreview.R.font.pretendard_regular), message.getTypeface());
                rotation[0] = a.findViewById(R.id.tutorial_floating_control).getRotation();
            });
            SystemClock.sleep(450);
            scenario.onActivity(a -> {
                if (android.animation.ValueAnimator.areAnimatorsEnabled())
                    assertTrue(Math.abs(a.findViewById(R.id.tutorial_floating_control).getRotation() - rotation[0]) > .05f);
            });
            tap(R.id.home_settings); words(R.string.tour_welcome);
            tap(R.id.tutorial_title); words(R.string.tour_pick);
        }
    }

    private void assertNoCoachButtons() {
        device.runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                View coach = a.findViewById(R.id.tutorial_floating_host);
                assertNotNull(coach); assertNull(find(coach, v -> v instanceof android.widget.Button));
                assertNull(a.findViewById(R.id.tutorial_next)); assertNull(a.findViewById(R.id.tutorial_back));
            }
        });
    }

    private void assertPhoto(ActivityScenario<HomeActivity> scenario) {
        scenario.onActivity(a -> {
            ViewGroup photo = a.findViewById(R.id.tutorial_settings_photo);
            assertTrue(photo instanceof com.click.ui.tutorial.SettingsScreenshotPracticeView);
            var image = (android.widget.ImageView) photo.getChildAt(0);
            assertTrue(image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
            assertEquals(1080, image.getDrawable().getIntrinsicWidth());
        });
    }

    private void assertSpotlight(int targetId, boolean childrenOnly) {
        device.runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                try {
                    View coach = a.findViewById(R.id.tutorial_floating_host), target = a.findViewById(targetId);
                    java.lang.reflect.Field field = coach.getClass().getDeclaredField("hole"); field.setAccessible(true);
                    android.graphics.RectF hole = new android.graphics.RectF((android.graphics.RectF) field.get(coach));
                    int[] origin = new int[2]; coach.getLocationOnScreen(origin);
                    Rect expected = new Rect(), child = new Rect();
                    if (childrenOnly) {
                        var group = (ViewGroup) target;
                        for (int i = 0; i < group.getChildCount(); i++) { screenBounds(group.getChildAt(i), child); expected.union(child); }
                    } else screenBounds(target, expected);
                    expected.offset(-origin[0], -origin[1]);
                    int pad = Math.round(5 * a.getResources().getDisplayMetrics().density); expected.inset(-pad, -pad);
                    assertEquals(expected.left, hole.left, 2); assertEquals(expected.top, hole.top, 2);
                    assertEquals(expected.right, hole.right, 2); assertEquals(expected.bottom, hole.bottom, 2);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            }
        });
    }

    private void screenBounds(View view, Rect bounds) {
        int[] position = new int[2]; view.getLocationOnScreen(position);
        assertTrue(view.getLocalVisibleRect(bounds));
        bounds.offset(position[0], position[1]);
    }

    private void captureCurrent(String name) throws Exception {
        device.waitForIdleSync();
        Bitmap[] frame = {null}; int[] result = {-1};
        CountDownLatch copied = new CountDownLatch(1);
        device.runOnMainSync(() -> {
            Activity current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).iterator().next();
            View decor = current.getWindow().getDecorView();
            frame[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(current.getWindow(), frame[0], status -> { result[0] = status; copied.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        assertTrue(copied.await(3, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result[0]);
        try (var output = new FileOutputStream(new File(device.getTargetContext().getCacheDir(), "clue-tour-" + name + ".png"))) {
            frame[0].compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { frame[0].recycle(); }
    }

    private void tap(int id) { tap(v -> v.getId() == id); }
    private void tap(Predicate<View> match) {
        View view = visible(match);
        int[] p = new int[2];
        device.runOnMainSync(() -> {
            Rect rect = new Rect();
            assertTrue(view.getLocalVisibleRect(rect));
            view.getLocationOnScreen(p);
            // Local visible rectangles include a TextView's internal scroll offset.
            // Single-line header labels can have a very wide virtual text layout.
            p[0] += rect.centerX() - view.getScrollX(); p[1] += rect.centerY() - view.getScrollY();
        });
        device.runOnMainSync(() -> {
            // Dispatch real touch coordinates through this app's root, including the coach's
            // spotlight gate. Unrelated system/experiment overlays must not receive test taps.
            View root = view.getRootView(); int[] origin = new int[2]; root.getLocationOnScreen(origin);
            long down = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent touch = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        p[0] - origin[0], p[1] - origin[1], 0);
                touch.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                root.dispatchTouchEvent(touch); touch.recycle();
            }
        });
        device.waitForIdleSync();
    }

    private void words(int id) {
        String word = device.getTargetContext().getString(id);
        visible(v -> v.getId() == R.id.tutorial_title && word.contentEquals(((TextView) v).getText()));
    }

    private View visible(Predicate<View> match) {
        long end = SystemClock.uptimeMillis() + 8000;
        View[] found = {null};
        while (SystemClock.uptimeMillis() < end) {
            device.waitForIdleSync();
            device.runOnMainSync(() -> {
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    found[0] = find(a.getWindow().getDecorView(), match);
                    if (found[0] != null) break;
                }
            });
            if (found[0] != null) return found[0];
            SystemClock.sleep(45);
        }
        StringBuilder context = new StringBuilder("Expected tutorial control did not appear");
        device.runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                View focus = a.getCurrentFocus();
                TextView message = a.findViewById(R.id.tutorial_title);
                context.append(" in ").append(a.getLocalClassName()).append(", focus=")
                        .append(focus == null ? "none" : focus.getId()).append(", step=")
                        .append(message == null ? "" : message.getText());
            }
        });
        throw new AssertionError(context.toString());
    }

    private View find(View v, Predicate<View> match) {
        if (!v.isShown()) return null;
        if (match.test(v) && v.getGlobalVisibleRect(new Rect())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), match);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void capture(ActivityScenario<HomeActivity> scenario, String name) throws Exception {
        Bitmap frame = snapshot(scenario);
        File file = new File(device.getTargetContext().getCacheDir(), "clue-tour-" + name + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            frame.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { frame.recycle(); }
    }

    private Bitmap snapshot(ActivityScenario<HomeActivity> scenario) throws Exception {
        device.waitForIdleSync();
        Bitmap[] frame = {null};
        int[] result = {-1};
        CountDownLatch copied = new CountDownLatch(1);
        scenario.onActivity(a -> {
            View decor = a.getWindow().getDecorView();
            frame[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(a.getWindow(), frame[0], status -> {
                result[0] = status;
                copied.countDown();
            }, new Handler(Looper.getMainLooper()));
        });
        assertTrue(copied.await(3, TimeUnit.SECONDS));
        assertEquals(PixelCopy.SUCCESS, result[0]);
        return frame[0];
    }
}
