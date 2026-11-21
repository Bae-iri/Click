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

    @Test public void realHomeTouchesLeadThroughFontPracticeSettingsAndLibrary() throws Exception {
        TouchAutomationSettings automation = new TouchAutomationSettings(device.getTargetContext());
        boolean initialMode = automation.isEnabled();
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.tutorialIntent(device.getTargetContext()))) {
            words(R.string.tour_welcome);
            int[] dock = new int[1];
            scenario.onActivity(home -> {
                View bubble = home.findViewById(R.id.tutorial_explanation_scroll);
                assertEquals(0f, bubble.getElevation(), 0f);
                assertNull(home.findViewById(R.id.tutorial_explanation_replay));
                assertNull(find(bubble, v -> v instanceof android.widget.Button));
                assertNull(find(home.findViewById(R.id.tutorial_floating_host),
                        v -> v instanceof TextView && "닫기".contentEquals(((TextView) v).getText())));
                Rect words = new Rect(), button = new Rect(), host = new Rect();
                assertTrue(bubble.getGlobalVisibleRect(words));
                assertTrue(home.findViewById(R.id.tutorial_next).getGlobalVisibleRect(button));
                View coach = home.findViewById(R.id.tutorial_floating_host);
                assertTrue(coach.getGlobalVisibleRect(host));
                int safeBottom = host.bottom - ViewCompat.getRootWindowInsets(coach)
                        .getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).bottom;
                assertTrue(button.top > words.bottom);
                assertTrue(button.bottom <= safeBottom);
                assertTrue(safeBottom - button.bottom <= 12 * home.getResources().getDisplayMetrics().density);
                dock[0] = ((View) bubble.getParent()).getBottom();
            });
            capture(scenario, "welcome");
            tap(R.id.home_settings);
            words(R.string.tour_welcome);
            tap(R.id.tutorial_next);
            scenario.onActivity(a -> assertEquals("The guide stays at its dock when input is highlighted",
                    dock[0], ((View) a.findViewById(R.id.tutorial_explanation_scroll).getParent()).getBottom()));
            capture(scenario, "input");
            tap(R.id.home_nav_favorites);
            words(R.string.tour_pick);
            tap(R.id.home_request_input);
            words(R.string.tour_text);
            scenario.onActivity(a -> {
                EditText editor = a.findViewById(R.id.home_request_input);
                assertTrue(editor.hasFocus());
                editor.onCreateInputConnection(new EditorInfo()).commitText("글자 크기 키워줘", 1);
                a.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(editor.getWindowToken(), 0);
            });
            visible(v -> v.getId() == R.id.home_request_send &&
                    !ViewCompat.getRootWindowInsets(v).isVisible(WindowInsetsCompat.Type.ime()));
            tap(R.id.home_request_send);
            words(R.string.tour_review);
            visible(v -> "review_summary_text".equals(v.getTag()) && "글자 크기 키워줘".contentEquals(((TextView) v).getText()));
            capture(scenario, "review");
            tap(R.id.tutorial_back);
            words(R.string.tour_text);
            scenario.onActivity(a -> ((EditText) a.findViewById(R.id.home_request_input)).onEditorAction(EditorInfo.IME_ACTION_SEND));
            words(R.string.tour_review);
            tap(R.id.tutorial_next);
            words(R.string.tour_adjust);
            scenario.onActivity(a -> {
                View penguin = a.findViewById(R.id.tutorial_floating_control);
                assertEquals("The penguin stays docked during the font exercise", dock[0],
                        ((View) penguin.getParent()).getBottom());
                Rect sample = new Rect(), guide = new Rect();
                assertTrue(a.findViewById(R.id.tutorial_increase).getGlobalVisibleRect(sample));
                assertTrue(penguin.getGlobalVisibleRect(guide));
                assertTrue(sample.bottom < guide.top);
            });
            capture(scenario, "highlight");
            tap(R.id.tutorial_increase);
            words(R.string.tour_result);
            scenario.recreate();
            words(R.string.tour_result);
            capture(scenario, "result");
            tap(R.id.tutorial_next);
            tap(R.id.home_settings);
            words(R.string.tour_automation);
            tap(v -> v.getContentDescription() != null && v.getContentDescription().toString().startsWith("자동으로 도와주기,"));
            words(R.string.tour_settings_detail);
            tap(R.id.tutorial_next);
            words(R.string.tour_library);
            tap(R.id.home_nav_favorites);
            words(R.string.tour_saved);
            tap(R.id.home_library_recent);
            words(R.string.tour_recent);
            capture(scenario, "library");
            tap(R.id.tutorial_next);
            visible(v -> v.getId() == R.id.home_request_input && v.getContext().getClass() == HomeActivity.class);
            scenario.onActivity(a -> assertNull(a.findViewById(R.id.tutorial_floating_host)));
            assertEquals(initialMode, automation.isEnabled());
        }
    }

    @Test public void microphoneOpensRealVoiceControlAndCanSwitchToTyping() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.tutorialIntent(device.getTargetContext()))) {
            tap(R.id.tutorial_next);
            tap(R.id.home_microphone);
            words(R.string.tour_voice);
            visible(v -> v.getId() == R.id.overlay_microphone);
            scenario.onActivity(a -> {
                View collapse = a.findViewById(R.id.overlay_collapse);
                assertTrue(collapse == null || !collapse.isShown());
                assertNull(find(a.getWindow().getDecorView(), v -> v instanceof TextView
                        && "취소".contentEquals(((TextView) v).getText())));
            });
            capture(scenario, "voice");
            tap(v -> v instanceof TextView && ((TextView) v).getText().equals(device.getTargetContext().getString(R.string.tour_try_text)));
            words(R.string.tour_text);
            visible(v -> v.getId() == R.id.home_request_input && v.hasFocus());
        }
    }

    @Test public void replayUsesTheOpenHomeAndRestoresItsDraftAfterRecreation() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.tutorialIntent(device.getTargetContext()))) {
            scenario.onActivity(home -> home.getOnBackPressedDispatcher().onBackPressed());
            scenario.onActivity(home -> {
                EditText editor = home.findViewById(R.id.home_request_input);
                editor.setText("나중에 보낼 요청");
                editor.setSelection(3);
                home.onNewIntent(HomeActivity.tutorialIntent(home));
                assertSame(editor, home.findViewById(R.id.home_request_input));
                assertEquals("", editor.getText().toString());
            });
            tap(R.id.tutorial_next);
            tap(R.id.home_request_input);
            scenario.recreate();
            words(R.string.tour_text);
            tap(R.id.tutorial_back);
            tap(R.id.tutorial_back);
            scenario.onActivity(home -> home.getOnBackPressedDispatcher().onBackPressed());
            scenario.onActivity(home -> {
                EditText editor = home.findViewById(R.id.home_request_input);
                assertNull(home.findViewById(R.id.tutorial_floating_host));
                assertEquals("나중에 보낼 요청", editor.getText().toString());
                assertEquals(3, editor.getSelectionEnd());
            });
        }
        try (ActivityScenario<HomeActivity> next = ActivityScenario.launch(HomeActivity.class)) {
            next.onActivity(home -> assertNull(home.findViewById(R.id.tutorial_floating_host)));
        }
    }

    @Test public void waitingStrengthensTheInputOutlineWhileOtherHomeActionsStayBlocked() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.tutorialIntent(device.getTargetContext()))) {
            tap(R.id.tutorial_next);
            words(R.string.tour_pick);
            int[] edge = new int[2];
            scenario.onActivity(a -> {
                View card = a.findViewById(R.id.home_start);
                card.getLocationInWindow(edge);
                edge[0] += Math.round(17 * a.getResources().getDisplayMetrics().density);
            });
            int original = blueStroke(snapshot(scenario), edge);
            assertTrue(original > 0);
            int[] center = {edge[0], edge[1]};
            scenario.onActivity(a -> {
                View card = a.findViewById(R.id.home_start);
                int[] corner = new int[2];
                card.getLocationInWindow(corner);
                center[0] = corner[0] + card.getWidth() / 2;
            });
            assertEquals("Only the corners frame the target", 0, blueStroke(snapshot(scenario), center));
            tap(R.id.home_settings);
            tap(R.id.home_nav_favorites);
            SystemClock.sleep(8250);
            words(R.string.tour_pick);
            assertTrue("The reminder must visibly strengthen the same corner",
                    blueStroke(snapshot(scenario), edge) > original + 1);
        }
    }

    private int blueStroke(Bitmap image, int[] point) {
        int pixels = 0;
        for (int y = Math.max(0, point[1] - 40); y <= Math.min(point[1] + 6, image.getHeight() - 1); y++) {
            int color = image.getPixel(point[0], y);
            if (android.graphics.Color.blue(color) - android.graphics.Color.red(color) > 80) pixels++;
        }
        image.recycle();
        return pixels;
    }

    private void tap(int id) { tap(v -> v.getId() == id); }
    private void tap(Predicate<View> match) {
        View view = visible(match);
        int[] p = new int[2];
        device.runOnMainSync(() -> {
            Rect rect = new Rect();
            assertTrue(view.getLocalVisibleRect(rect));
            view.getLocationOnScreen(p);
            p[0] += rect.centerX(); p[1] += rect.centerY();
        });
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent touch = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p[0], p[1], 0);
            touch.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            device.sendPointerSync(touch);
            touch.recycle();
        }
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
