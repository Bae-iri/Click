package com.click;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.click.data.local.TouchAutomationSettings;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ClickTutorialFlowTest {
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();

    @Test public void automationPracticeReadsTheWholeNoticeBeforeContinuingToTheLibrary() throws Exception {
        TouchAutomationSettings automation = new TouchAutomationSettings(device.getTargetContext());
        boolean initialMode = automation.isEnabled();
        var course = new com.click.data.local.LiveTutorialStore(device.getTargetContext());
        course.beginDisplay("com.android.settings");
        course.endDisplay();
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            words(R.string.tour_settings);
            scenario.recreate();
            words(R.string.tour_settings);
            tap(R.id.home_settings);
            words(R.string.tour_automation);
            assertNoCoachButtons();
            assertBox(R.id.menu_automation_toggle, false);
            tap(R.id.menu_automation_toggle);
            View confirm = visible(v -> v.getId() == android.R.id.button1);
            var scroll = (android.widget.ScrollView) visible(v -> "click_dialog_body_scroll".equals(v.getTag()));
            device.runOnMainSync(() -> {
                assertFalse(confirm.isEnabled());
                TextView bubble = confirm.getRootView().findViewById(R.id.tutorial_title);
                Rect before = new Rect();
                assertTrue(bubble.getGlobalVisibleRect(before));
                int end = scroll.getChildAt(0).getHeight() - scroll.getHeight();
                scroll.scrollTo(0, end / 2);
                Rect during = new Rect();
                assertTrue(bubble.getGlobalVisibleRect(during));
                assertEquals(before, during);
                assertFalse(confirm.isEnabled());
                scroll.scrollTo(0, end);
                assertTrue(confirm.isEnabled());
                assertEquals(bubble.getResources().getString(initialMode ? R.string.tour_auto_ready_enabled
                        : R.string.tour_auto_ready), bubble.getText().toString());
            });
            tap(android.R.id.button2);
            words(R.string.tour_library);
            assertBox(R.id.home_nav_favorites, true);
            tap(R.id.home_nav_favorites); words(R.string.tour_saved);
            tap(R.id.home_library_recent); words(R.string.tour_recent);
            tap(R.id.home_nav_home);
            scenario.onActivity(a -> assertNull(a.findViewById(R.id.tutorial_floating_host)));
            assertFalse(course.awaitingHomeTour());
            assertEquals(initialMode, automation.isEnabled());
        }
    }

    @Test public void choosingEnableInTheLessonRequiresTheLastLineAndAnExplicitTap() {
        var context = device.getTargetContext();
        TouchAutomationSettings mode = new TouchAutomationSettings(context);
        boolean original = mode.isEnabled();
        mode.setEnabled(false);
        try (ActivityScenario<MenuActivity> scenario = ActivityScenario.launch(
                new android.content.Intent(context, MenuActivity.class).putExtra(MenuActivity.EXTRA_TOUR, true))) {
            tap(R.id.menu_automation_toggle);
            View enable = visible(v -> v.getId() == android.R.id.button1);
            var scroll = (android.widget.ScrollView) visible(v -> "click_dialog_body_scroll".equals(v.getTag()));
            device.runOnMainSync(() -> {
                assertFalse(enable.isEnabled());
                int end = scroll.getChildAt(0).getHeight() - scroll.getHeight();
                scroll.scrollTo(0, end - 1);
                assertFalse(enable.isEnabled());
                scroll.scrollTo(0, end);
                assertTrue(enable.isEnabled());
                assertFalse(mode.isEnabled());
            });
            tap(android.R.id.button1);
            assertTrue(mode.isEnabled());
        } finally { mode.setEnabled(original); }
    }

    @Test public void replayStartsAtHomeAndSendingPracticeOpensDisplayWithAContinuousBlueBox() {
        var context = device.getTargetContext();
        var automation = device.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        org.junit.Assume.assumeTrue("Requires the test app's enabled accessibility service",
                com.click.core.permission.PermissionStatus.isAccessibilityEnabled(context));
        TutorialReadyFlowTest.reconnectIfNeeded(context, automation);
        new com.click.data.local.GuidancePreferences(context).markHomeTutorialSeen();
        new com.click.data.local.LiveTutorialStore(context).clear();
        float font = android.provider.Settings.System.getFloat(context.getContentResolver(),
                android.provider.Settings.System.FONT_SCALE, 1f);
        HomeActivity home = (HomeActivity) device.startActivitySync(new android.content.Intent(context, HomeActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            device.runOnMainSync(() -> {
                EditText editor = home.findViewById(R.id.home_request_input);
                editor.setText("나중에 보낼 요청");
                home.onNewIntent(HomeActivity.tutorialIntent(home));
            });
            words(R.string.tour_input);
            assertBox(R.id.home_start, false);
            tap(R.id.home_microphone);
            words(R.string.tour_voice);
            device.runOnMainSync(() -> home.getOnBackPressedDispatcher().onBackPressed());
            words(R.string.tour_input);
            tap(R.id.home_request_input);
            device.runOnMainSync(() -> {
                EditText editor = home.findViewById(R.id.home_request_input);
                assertTrue(editor.hasFocus());
                assertEquals("", editor.getText().toString());
                editor.setText("글자 크기 키워줘");
            });
            words(R.string.tour_send);
            tap(R.id.home_request_send);
            words(R.string.tour_display);
            android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            assertNotNull(root);
            String settings = new android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                    .resolveActivity(context.getPackageManager()).getPackageName();
            assertEquals(settings, root.getPackageName().toString());
            root.recycle();
            View layer = visible(v -> v.getId() == R.id.live_tutorial_window);
            Rect box = new Rect();
            device.runOnMainSync(() -> {
                try {
                    View coach = layer.findViewById(R.id.tutorial_floating_host);
                    var field = coach.getClass().getDeclaredField("hole"); field.setAccessible(true);
                    ((android.graphics.RectF) field.get(coach)).round(box);
                    int[] origin = new int[2]; coach.getLocationOnScreen(origin);
                    box.offset(origin[0], origin[1]);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            Bitmap image = automation.takeScreenshot();
            assertNotNull(image);
            try {
                assertBlue(image, box.centerX(), box.top);
                assertBlue(image, box.centerX(), box.bottom);
                assertBlue(image, Math.max(1, box.left), box.centerY());
                assertBlue(image, Math.min(image.getWidth() - 2, box.right), box.centerY());
            } finally { image.recycle(); }
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            words(R.string.tour_settings);
            device.runOnMainSync(() -> assertEquals("나중에 보낼 요청",
                    ((EditText) home.findViewById(R.id.home_request_input)).getText().toString()));
            assertEquals(font, android.provider.Settings.System.getFloat(context.getContentResolver(),
                    android.provider.Settings.System.FONT_SCALE, 1f), 0f);
        } finally { device.runOnMainSync(home::finish); }
    }

    private void assertBlue(Bitmap image, int x, int y) {
        for (int i = Math.max(0, x - 3); i <= Math.min(image.getWidth() - 1, x + 3); i++)
            for (int j = Math.max(0, y - 3); j <= Math.min(image.getHeight() - 1, y + 3); j++) {
                int pixel = image.getPixel(i, j);
                if (android.graphics.Color.blue(pixel) > 200 && android.graphics.Color.red(pixel) < 100
                        && android.graphics.Color.green(pixel) > 75 && android.graphics.Color.green(pixel) < 160) return;
            }
        fail("Blue box edge missing at " + x + ", " + y);
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

    private void assertBox(int targetId, boolean childrenOnly) {
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
                for (View window : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    found[0] = find(window, match);
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

}
