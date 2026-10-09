package com.click;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.designpreview.PermissionPhoneView;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.tutorial.TutorialFloatingHost;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ClickTutorialFlowTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void actualFloatingMenuKeyboardConversationVoiceAndHome() throws Exception {
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            scenario.onActivity(a -> assertNotNull(findType(a.getWindow().getDecorView(), PermissionPhoneView.class)));
            screenshot("click-feature-1");
            tapId(scenario, R.id.tutorial_floating_control);
            assertMode(scenario, ClickOverlayLayout.Mode.MENU);
            screenshot("click-feature-2");
            tapText(scenario, "글로 입력");
            assertMode(scenario, ClickOverlayLayout.Mode.TEXT);
            screenshot("click-feature-3");
            final View[] input = {null};
            scenario.onActivity(a -> input[0] = findType(a.getWindow().getDecorView(), EditText.class));
            tap(input[0]);
            boolean[] imeVisible = {false};
            for (int i = 0; i < 30 && !imeVisible[0]; i++) {
                SystemClock.sleep(100);
                scenario.onActivity(a -> imeVisible[0] = ViewCompat.getRootWindowInsets(a.getWindow().getDecorView())
                        .isVisible(WindowInsetsCompat.Type.ime()));
            }
            assertTrue("Tapping the actual input must open the keyboard", imeVisible[0]);
            scenario.onActivity(a -> {
                View send = findText(a.getWindow().getDecorView(), "↑");
                Rect visible = new Rect();
                assertTrue(send.getGlobalVisibleRect(visible));
                assertEquals("Send must remain fully visible above keyboard", send.getHeight(), visible.height());
                ((EditText) input[0]).setText("이 화면은 어떻게 사용해요?");
            });
            screenshot("click-feature-keyboard");
            tapText(scenario, "↑");
            scenario.onActivity(a -> assertNotNull(findText(a.getWindow().getDecorView(), "이 화면은 어떻게 사용해요?")));
            screenshot("click-feature-4");
            tapId(scenario, R.id.tutorial_next);
            tapText(scenario, "말로 시작");
            assertMode(scenario, ClickOverlayLayout.Mode.VOICE);
            scenario.onActivity(a -> {
                assertFalse(control(a).isVoiceInputActive());
                assertFalse(a.findViewById(R.id.tutorial_next).isEnabled());
            });
            tapId(scenario, R.id.overlay_microphone);
            scenario.onActivity(a -> assertTrue(control(a).isVoiceInputActive()));
            tapId(scenario, R.id.overlay_microphone);
            scenario.onActivity(a -> assertFalse(control(a).isVoiceInputActive()));
            screenshot("click-feature-5");
            tapId(scenario, R.id.tutorial_next);
            scenario.recreate();
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "사용자 요청을 처리하고 있어요"));
                assertFalse(a.findViewById(R.id.tutorial_next).isEnabled());
                assertFalse(control(a).isVoiceInputActive());
            });
            screenshot("click-feature-stop");
            tapId(scenario, R.id.overlay_stop_request);
            scenario.recreate();
            scenario.onActivity(a -> assertNotNull(findText(a.getWindow().getDecorView(), "요청을 멈췄어요")));
            tapId(scenario, R.id.tutorial_next);
            assertMode(scenario, ClickOverlayLayout.Mode.COLLAPSED);
            screenshot("click-feature-6");
            scenario.onActivity(a -> control(a).performLongClick());
            scenario.onActivity(a -> {
                assertTrue(((TutorialFloatingHost) a.findViewById(R.id.tutorial_floating_host)).isHidden());
                assertEquals(View.VISIBLE, a.findViewById(R.id.tutorial_restore).getVisibility());
            });
            tapId(scenario, R.id.tutorial_restore);
            scenario.onActivity(a -> assertFalse(((TutorialFloatingHost) a.findViewById(R.id.tutorial_floating_host)).isHidden()));
            Instrumentation.ActivityMonitor home = instrumentation.addMonitor(HomeActivity.class.getName(), null, false);
            try {
                tapId(scenario, R.id.tutorial_next);
                assertNotNull(instrumentation.waitForMonitorWithTimeout(home, 3000));
            } finally { instrumentation.removeMonitor(home); }
        }
    }

    @Test public void realInputDraftAndConversationSurviveRecreation() {
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            tapId(scenario, R.id.tutorial_floating_control);
            tapText(scenario, "글로 입력");
            tapText(scenario, "↑");
            scenario.onActivity(a -> assertFalse("Empty input must not complete the practice", a.findViewById(R.id.tutorial_next).isEnabled()));
            scenario.onActivity(a -> control(a).setDraftText("아직 보내지 않은 질문"));
            scenario.recreate();
            assertMode(scenario, ClickOverlayLayout.Mode.TEXT);
            scenario.onActivity(a -> assertEquals("아직 보내지 않은 질문", control(a).getDraftText()));
            tapText(scenario, "↑");
            scenario.recreate();
            scenario.onActivity(a -> {
                assertEquals("", control(a).getDraftText());
                assertNotNull(findText(a.getWindow().getDecorView(), "아직 보내지 않은 질문"));
                assertTrue(a.findViewById(R.id.tutorial_next).isEnabled());
            });
            tapId(scenario, R.id.tutorial_back);
            assertMode(scenario, ClickOverlayLayout.Mode.MENU);
        }
    }

    private ClickOverlayLayout control(TutorialActivity a) { return a.findViewById(R.id.tutorial_floating_control); }
    private void assertMode(ActivityScenario<TutorialActivity> s, ClickOverlayLayout.Mode mode) {
        s.onActivity(a -> assertEquals(mode, control(a).getMode()));
    }
    private void tapId(ActivityScenario<TutorialActivity> s, int id) {
        View[] view = {null}; s.onActivity(a -> view[0] = a.findViewById(id)); tap(view[0]);
    }
    private void tapText(ActivityScenario<TutorialActivity> s, String label) {
        View[] view = {null};
        s.onActivity(a -> {
            view[0] = findText(a.getWindow().getDecorView(), label);
            assertNotNull("Missing " + label, view[0]);
            while (!view[0].isClickable() && view[0].getParent() instanceof View) view[0] = (View) view[0].getParent();
        });
        tap(view[0]);
    }
    private void tap(View view) {
        Rect bounds = new Rect();
        boolean[] visible = {false};
        for (int attempt = 0; attempt < 30 && !visible[0]; attempt++) {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> visible[0] = view.getGlobalVisibleRect(bounds));
            if (!visible[0]) SystemClock.sleep(100);
        }
        assertTrue("Tap target must be on screen: " + view.getContentDescription() + " (" + view.getId() + ")", visible[0]);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
        MotionEvent up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
        instrumentation.getUiAutomation().injectInputEvent(down, true);
        instrumentation.getUiAutomation().injectInputEvent(up, true);
        down.recycle(); up.recycle(); instrumentation.waitForIdleSync(); SystemClock.sleep(250);
    }
    private TextView findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText()) && root.isShown()) return (TextView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            TextView result = findText(((ViewGroup) root).getChildAt(i), text); if (result != null) return result;
        }
        return null;
    }
    private <T extends View> T findType(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            T result = findType(((ViewGroup) root).getChildAt(i), type); if (result != null) return result;
        }
        return null;
    }
    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync(); SystemClock.sleep(350);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
