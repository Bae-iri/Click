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
import com.click.service.overlay.SpeechInput;
import com.click.ui.overlay.TrashDropView;
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
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "click 튜토리얼을\n시작해볼게요!"));
                assertChecklist(a, false);
                assertFalse(control(a).isShown());
            });
            screenshot("click-tutorial-checklist-start");
            assertHighlightOn(scenario, null, R.id.tutorial_next);
            scenario.recreate();
            tapId(scenario, R.id.tutorial_next);
            scenario.onActivity(a -> assertNotNull(findType(a.getWindow().getDecorView(), PermissionPhoneView.class)));
            screenshot("click-feature-1");
            // The cue must still pulse while the user takes time to read the instructions.
            SystemClock.sleep(6000);
            assertClickPulseContinues(scenario);
            tapId(scenario, R.id.tutorial_floating_control);
            assertMode(scenario, ClickOverlayLayout.Mode.MENU);
            assertHighlightOn(scenario, "글로 입력", 0);
            scenario.onActivity(a -> control(a).setTranslationY(control(a).getTranslationY() - 18 * a.getResources().getDisplayMetrics().density));
            assertHighlightOn(scenario, "글로 입력", 0);
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
                // Global bounds round a fractional parent translation outward by one pixel.
                assertTrue("Send must remain fully visible above keyboard", visible.height() >= send.getHeight());
                ((EditText) input[0]).setText("이 화면은 어떻게 사용해요?");
            });
            assertHighlightOn(scenario, "↑", 0);
            screenshot("click-feature-keyboard");
            tapText(scenario, "↑");
            scenario.onActivity(a -> assertNotNull(findText(a.getWindow().getDecorView(), "이 화면은 어떻게 사용해요?")));
            screenshot("click-feature-4");
            tapText(scenario, "이 내용으로 시작");
            assertHighlightOn(scenario, null, R.id.tutorial_next);
            tapId(scenario, R.id.tutorial_next);
            tapText(scenario, "말로 시작");
            assertMode(scenario, ClickOverlayLayout.Mode.VOICE);
            scenario.onActivity(a -> {
                assertFalse(control(a).isVoiceInputActive());
                assertFalse(a.findViewById(R.id.tutorial_next).isEnabled());
            });
            assertHighlightOn(scenario, null, R.id.overlay_microphone);
            screenshot("click-feature-microphone-highlight");
            FakeSpeech speech = new FakeSpeech();
            instrumentation.getUiAutomation().grantRuntimePermission("com.click", android.Manifest.permission.RECORD_AUDIO);
            scenario.onActivity(a -> {
                try {
                    java.lang.reflect.Field field = TutorialActivity.class.getDeclaredField("speech"); field.setAccessible(true);
                    ((SpeechInput) field.get(a)).close(); field.set(a, speech);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            tapId(scenario, R.id.overlay_microphone);
            scenario.onActivity(a -> { assertTrue(control(a).isVoiceInputActive()); assertNotNull(speech.callback); });
            tapId(scenario, R.id.overlay_microphone);
            scenario.onActivity(a -> { assertFalse(control(a).isVoiceInputActive()); assertFalse(a.findViewById(R.id.tutorial_next).isEnabled()); });
            tapId(scenario, R.id.overlay_microphone);
            scenario.onActivity(a -> {
                speech.callback.onPartialText("휴대폰 글자");
                assertNotNull(findText(a.getWindow().getDecorView(), "휴대폰 글자"));
                speech.callback.onRecordingFinished(); speech.callback.onSuccess("휴대폰 글자 크기 키워줘");
            });
            scenario.onActivity(a -> { assertFalse(control(a).isVoiceInputActive()); assertTrue(a.findViewById(R.id.tutorial_next).isEnabled()); });
            screenshot("click-feature-5");
            tapId(scenario, R.id.tutorial_next);
            scenario.recreate();
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "사용자 요청을 처리하고 있어요"));
                assertFalse(a.findViewById(R.id.tutorial_next).isEnabled());
                assertFalse(control(a).isVoiceInputActive());
            });
            assertHighlightOn(scenario, null, R.id.overlay_stop_request);
            screenshot("click-feature-stop");
            tapId(scenario, R.id.overlay_stop_request);
            scenario.recreate();
            scenario.onActivity(a -> assertNotNull(findText(a.getWindow().getDecorView(), "요청을 멈췄어요")));
            tapId(scenario, R.id.tutorial_next);
            assertMode(scenario, ClickOverlayLayout.Mode.MENU);
            scenario.onActivity(a -> assertFalse(a.findViewById(R.id.tutorial_next).isEnabled()));
            assertHighlightOn(scenario, null, R.id.overlay_collapse);
            screenshot("click-feature-fold-highlight");
            tapId(scenario, R.id.overlay_collapse);
            tapId(scenario, R.id.tutorial_next);
            assertMode(scenario, ClickOverlayLayout.Mode.COLLAPSED);
            scenario.onActivity(a -> {
                TutorialFloatingHost host = a.findViewById(R.id.tutorial_floating_host);
                host.onDragStarted(); host.onDragBy(-50, -40); host.onDragEnded();
                assertTrue(a.findViewById(R.id.tutorial_next).isEnabled());
            });
            tapId(scenario, R.id.tutorial_next);
            screenshot("click-feature-6");
            scenario.onActivity(a -> {
                control(a).performLongClick();
                TutorialFloatingHost host = a.findViewById(R.id.tutorial_floating_host);
                assertFalse(host.isHidden());
                TrashDropView trash = findType(host, TrashDropView.class);
                assertEquals(View.VISIBLE, trash.getVisibility());
            });
            screenshot("click-feature-trash");
            scenario.onActivity(a -> {
                TutorialFloatingHost host = a.findViewById(R.id.tutorial_floating_host);
                TrashDropView trash = findType(host, TrashDropView.class);
                host.onDragBy(trash.getX() + trash.getWidth() / 2f - control(a).getX() - control(a).getWidth() / 2f,
                        trash.getY() + trash.getHeight() / 2f - control(a).getY() - control(a).getHeight() / 2f);
                host.onDragEnded();
                assertTrue(host.isHidden()); assertFalse(a.findViewById(R.id.tutorial_next).isEnabled());
            });
            assertHighlightOn(scenario, null, R.id.tutorial_restore);
            tapId(scenario, R.id.tutorial_restore);
            scenario.onActivity(a -> assertFalse(((TutorialFloatingHost) a.findViewById(R.id.tutorial_floating_host)).isHidden()));
            tapId(scenario, R.id.tutorial_next);
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "이제 준비됐어요!"));
                assertChecklist(a, true);
                assertFalse(control(a).isShown());
            });
            scenario.recreate();
            scenario.onActivity(a -> assertChecklist(a, true));
            screenshot("click-tutorial-checklist-complete");
            Instrumentation.ActivityMonitor home = instrumentation.addMonitor(HomeActivity.class.getName(), null, false);
            try {
                tapId(scenario, R.id.tutorial_next);
                assertNotNull(instrumentation.waitForMonitorWithTimeout(home, 3000));
            } finally { instrumentation.removeMonitor(home); }
        }
    }

    @Test public void realInputDraftAndConversationSurviveRecreation() {
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            tapId(scenario, R.id.tutorial_next);
            tapId(scenario, R.id.tutorial_floating_control);
            tapText(scenario, "글로 입력");
            scenario.onActivity(a -> {
                EditText input = findType(a.getWindow().getDecorView(), EditText.class);
                assertEquals("글자 크기 키워줘", input.getHint().toString());
                assertEquals("", input.getText().toString());
            });
            tapText(scenario, "↑");
            scenario.onActivity(a -> assertFalse("Empty input must not complete the practice", a.findViewById(R.id.tutorial_next).isEnabled()));
            scenario.onActivity(a -> control(a).setDraftText("글자 크기 키워줘"));
            scenario.recreate();
            assertMode(scenario, ClickOverlayLayout.Mode.TEXT);
            scenario.onActivity(a -> assertEquals("글자 크기 키워줘", control(a).getDraftText()));
            tapText(scenario, "↑");
            tapText(scenario, "휴대폰 기본 글자");
            scenario.recreate();
            scenario.onActivity(a -> {
                assertNotNull("Chosen scope must survive recreation", findText(a.getWindow().getDecorView(), "이 내용으로 시작"));
                assertEquals("", control(a).getDraftText());
                assertNotNull(findText(a.getWindow().getDecorView(), "글자 크기 키워줘"));
                assertFalse(a.findViewById(R.id.tutorial_next).isEnabled());
            });
            tapId(scenario, R.id.tutorial_back);
            assertMode(scenario, ClickOverlayLayout.Mode.MENU);
        }
    }

    private void assertChecklist(TutorialActivity activity, boolean completed) {
        String[] labels = {"click 버튼 열기", "글로 입력하고 요청 확인하기", "마이크로 말하기", "진행 중인 요청 멈추기", "창 접고 버튼 옮기기", "버튼 숨기고 다시 꺼내기"};
        for (String label : labels) {
            TextView text = findText(activity.getWindow().getDecorView(), label);
            assertNotNull(label, text);
            assertEquals(label + (completed ? ", 완료" : ", 연습 전"),
                    text.getContentDescription().toString());
        }
    }

    // Deterministic STT callback checks; live microphone quality is verified separately.
    private static final class FakeSpeech implements SpeechInput {
        Callback callback;
        public void start(Callback callback) { this.callback = callback; callback.onReady(); }
        public void cancel() { callback = null; }
        public void close() { cancel(); }
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
    private void assertHighlightOn(ActivityScenario<TutorialActivity> scenario, String label, int id) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(150);
        int[] position = new int[2]; int[] size = new int[2]; float[] density = new float[1];
        View[] highlighted = new View[1];
        scenario.onActivity(a -> {
            View target = id == 0 ? findText(a.getWindow().getDecorView(), label) : a.findViewById(id);
            assertNotNull(target);
            while (!target.isClickable() && target.getParent() instanceof View) target = (View) target.getParent();
            highlighted[0] = target;
            target.getLocationOnScreen(position); size[0] = target.getWidth(); size[1] = target.getHeight();
            density[0] = a.getResources().getDisplayMetrics().density;
        });
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        int x = position[0] - Math.round(6 * density[0]);
        int y = position[1] + size[1] / 2;
        int pixel = bitmap.getPixel(x, y);
        int topPixel = bitmap.getPixel(position[0] + size[0] / 2, position[1] - Math.round(6 * density[0]));
        assertTrue("Highlight must stay outside the target's left edge " + label + " at " + x + "," + y
                + ", actual pixel=" + Integer.toHexString(pixel), isHighlightBlue(pixel));
        assertTrue("Highlight must stay outside the target's top edge " + label
                + ", actual pixel=" + Integer.toHexString(topPixel), isHighlightBlue(topPixel));
        scenario.onActivity(a -> ((TutorialFloatingHost) a.findViewById(R.id.tutorial_floating_host)).highlight(null, 0));
        instrumentation.waitForIdleSync(); SystemClock.sleep(100);
        Bitmap withoutHighlight = instrumentation.getUiAutomation().takeScreenshot();
        try {
            int inset = Math.round(2 * density[0]);
            int[][] inside = {{position[0] + inset, y}, {position[0] + size[0] - inset, y},
                    {position[0] + size[0] / 2, position[1] + inset},
                    {position[0] + size[0] / 2, position[1] + size[1] - inset}};
            for (int[] point : inside) {
                assertEquals("Highlight must not recolor the button interior " + label,
                        withoutHighlight.getPixel(point[0], point[1]), bitmap.getPixel(point[0], point[1]));
            }
        } finally {
            bitmap.recycle(); withoutHighlight.recycle();
            scenario.onActivity(a -> ((TutorialFloatingHost) a.findViewById(R.id.tutorial_floating_host)).highlight(highlighted[0]));
        }
    }
    private void assertClickPulseContinues(ActivityScenario<TutorialActivity> scenario) {
        int[] sample = new int[2];
        scenario.onActivity(a -> {
            control(a).getLocationOnScreen(sample);
            float density = a.getResources().getDisplayMetrics().density;
            sample[0] += Math.round(32 * density);
            sample[1] -= Math.round(3 * density);
        });
        int darkest = 255, lightest = 0;
        for (int frame = 0; frame < 4; frame++) {
            Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
            int pixel = bitmap.getPixel(sample[0], sample[1]); bitmap.recycle();
            assertTrue("The click button cue must remain blue", isHighlightBlue(pixel));
            int red = android.graphics.Color.red(pixel);
            darkest = Math.min(darkest, red); lightest = Math.max(lightest, red);
            SystemClock.sleep(250);
        }
        assertTrue("The click button cue must keep shimmering after six seconds", lightest - darkest > 12);
    }
    private boolean isHighlightBlue(int pixel) {
        int red = android.graphics.Color.red(pixel), green = android.graphics.Color.green(pixel);
        int blue = android.graphics.Color.blue(pixel);
        // The pulsing blue outline stays visible over its white contrast border.
        return red >= 35 && red < 180 && green >= 100 && green < 205
                && blue > 225 && blue - red > 55 && blue - green > 35;
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
