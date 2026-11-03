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
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.service.overlay.SpeechInput;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ClickTutorialFlowTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void fourStepsUseCurrentPanelsAndReturnHome() throws Exception {
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "4단계 연습"));
                assertFalse(control(a).isShown());
            });
            screenshot("tutorial-short-intro");
            tapId(scenario, R.id.tutorial_next);
            tapId(scenario, R.id.tutorial_floating_control);
            scenario.onActivity(a -> {
                assertNotNull(findText(control(a), "무엇을 도와드릴까요?"));
                assertNull(findText(control(a), "예) 글자 크기 키워줘"));
                assertNull(findText(control(a), "말하거나 글로 입력해 주세요."));
            });
            screenshot("tutorial-current-input-menu");
            tapText(scenario, "글로 입력");
            scenario.onActivity(a -> assertEquals("무엇이 궁금하세요?",
                    findType(control(a), EditText.class).getHint().toString()));
            scenario.onActivity(a -> control(a).setDraftText("휴대폰 전체 글자 크기 키워줘"));
            tapText(scenario, "↑");
            scenario.onActivity(a -> assertNotNull(findText(a.getWindow().getDecorView(), "2/4")));
            screenshot("tutorial-short-review");
            tapText(scenario, "안내 시작하기");
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "3/4"));
                assertNotNull(findText(a.getWindow().getDecorView(), "진행중이에요!"));
                assertNull(findText(a.getWindow().getDecorView(), "설정 앱을 열어주세요."));
            });
            screenshot("tutorial-short-running");
            tapText(scenario, "줄이기");
            scenario.recreate();
            scenario.onActivity(a -> assertTrue(findType(a.getWindow().getDecorView(), com.click.ui.overlay.TaskFlowOverlayView.class).isMinimizedProgress()));
            tapText(scenario, "진행중이에요!");
            tapText(scenario, "일시정지");
            scenario.recreate();
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "이어서 진행"));
                assertFalse(a.findViewById(R.id.tutorial_next).isEnabled());
            });
            tapText(scenario, "이어서 진행");
            tapId(scenario, R.id.tutorial_next);
            screenshot("tutorial-short-result");
            tapText(scenario, "조금 더 도와주세요");
            scenario.onActivity(a -> assertNotNull(findText(a.getWindow().getDecorView(), "진행중이에요!")));
            tapId(scenario, R.id.tutorial_next);
            tapText(scenario, "네, 좋아요");
            scenario.recreate();
            scenario.onActivity(a -> {
                for (String label : new String[]{"말이나 글로 요청하기", "요청 내용 확인하기", "안내창 줄이고 멈추기", "결과 확인하기"}) {
                    TextView item = findText(a.getWindow().getDecorView(), label);
                    assertNotNull(label, item);
                    assertEquals(label + ", 완료", item.getContentDescription());
                }
            });
            screenshot("tutorial-short-complete");
            Instrumentation.ActivityMonitor home = instrumentation.addMonitor(HomeActivity.class.getName(), null, false);
            try {
                tapId(scenario, R.id.tutorial_next);
                assertNotNull(instrumentation.waitForMonitorWithTimeout(home, 3000));
            } finally { instrumentation.removeMonitor(home); }
        }
    }

    @Test public void draftReviewEditingAndStopStayInsidePractice() throws Exception {
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            tapId(scenario, R.id.tutorial_next);
            tapId(scenario, R.id.tutorial_floating_control);
            tapText(scenario, "글로 입력");
            tapText(scenario, "↑");
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "1/4"));
                control(a).setDraftText("글자 크기 키워줘");
            });
            scenario.recreate();
            assertMode(scenario, ClickOverlayLayout.Mode.TEXT);
            scenario.onActivity(a -> assertEquals("글자 크기 키워줘", control(a).getDraftText()));
            tapText(scenario, "↑");
            tapText(scenario, "휴대폰 기본 글자");
            tapText(scenario, "다음 단계");
            scenario.recreate();
            scenario.onActivity(a -> assertNotNull(findText(a.getWindow().getDecorView(), "안내 시작하기")));
            tapText(scenario, "내용 수정");
            scenario.onActivity(a -> assertNotNull(findType(a.getWindow().getDecorView(), EditText.class)));
            tapId(scenario, R.id.tutorial_back);
            assertMode(scenario, ClickOverlayLayout.Mode.TEXT);
            scenario.onActivity(a -> control(a).setDraftText("휴대폰 전체 글자 크기 키워줘"));
            tapText(scenario, "↑");
            tapText(scenario, "안내 시작하기");
            tapText(scenario, "요청 중지");
            assertMode(scenario, ClickOverlayLayout.Mode.MENU);
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "1/4"));
                assertNull(findText(a.getWindow().getDecorView(), "요청을 멈췄어요"));
            });
        }
    }

    @Test public void holdAndReleaseVoiceGoesStraightToRequestReview() throws Exception {
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .grantRuntimePermission("com.click", android.Manifest.permission.RECORD_AUDIO);
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            tapId(scenario, R.id.tutorial_next);
            tapId(scenario, R.id.tutorial_floating_control);
            tapText(scenario, "말로 입력");
            FakeSpeech speech = new FakeSpeech();
            scenario.onActivity(a -> {
                try {
                    java.lang.reflect.Field field = TutorialActivity.class.getDeclaredField("speech"); field.setAccessible(true);
                    ((SpeechInput) field.get(a)).close(); field.set(a, speech);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            microphoneTouch(scenario, MotionEvent.ACTION_DOWN);
            scenario.onActivity(a -> assertTrue(control(a).isVoiceInputActive()));
            microphoneTouch(scenario, MotionEvent.ACTION_CANCEL);
            scenario.onActivity(a -> assertFalse(control(a).isVoiceInputActive()));
            microphoneTouch(scenario, MotionEvent.ACTION_DOWN);
            microphoneTouch(scenario, MotionEvent.ACTION_UP);
            scenario.onActivity(a -> {
                assertTrue(speech.finished);
                assertFalse(control(a).isVoiceInputActive());
                speech.callback.onSuccess("휴대폰 전체 글자 크기 키워줘");
                assertNotNull(findText(a.getWindow().getDecorView(), "2/4"));
                assertNotNull(findText(a.getWindow().getDecorView(), "안내 시작하기"));
            });
        }
    }

    // Deterministic STT callback checks; live microphone quality is verified separately.
    private static final class FakeSpeech implements SpeechInput {
        Callback callback;
        boolean finished;
        public void start(Callback callback) { this.callback = callback; finished = false; callback.onReady(); }
        public void finish() { finished = true; callback.onRecordingFinished(); }
        public void cancel() { callback = null; }
        public void close() { cancel(); }
    }

    private ClickOverlayLayout control(TutorialActivity a) { return a.findViewById(R.id.tutorial_floating_control); }
    private void microphoneTouch(ActivityScenario<TutorialActivity> scenario, int action) {
        scenario.onActivity(a -> {
            View mic = a.findViewById(R.id.overlay_microphone);
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now, action,
                    mic.getWidth() / 2f, mic.getHeight() / 2f, 0);
            mic.dispatchTouchEvent(event); event.recycle();
        });
    }
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
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(down, true);
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(up, true);
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
        Bitmap bitmap = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
