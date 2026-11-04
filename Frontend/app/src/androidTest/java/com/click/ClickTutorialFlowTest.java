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
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ClickTutorialFlowTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void clickButtonCanDragAcrossTheWholePhoneWithoutScrollingTheLesson() throws Exception {
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            tapId(scenario, R.id.tutorial_next);
            Rect phone = new Rect(), button = new Rect();
            scenario.onActivity(a -> {
                View stage = a.findViewById(R.id.tutorial_phone_stage);
                View host = a.findViewById(R.id.tutorial_floating_host);
                assertEquals("The drag area must fill the phone", stage.getHeight(), host.getHeight());
                assertEquals(0, host.getTop());
                assertTrue(stage.getGlobalVisibleRect(phone));
                assertTrue(control(a).getGlobalVisibleRect(button));
            });
            int topCenter = phone.top + button.height() / 2 + 16;
            drag(button.centerX(), button.centerY(), button.centerX(), topCenter);
            scenario.onActivity(a -> {
                Rect after = new Rect(), currentPhone = new Rect();
                control(a).getGlobalVisibleRect(after);
                a.findViewById(R.id.tutorial_phone_stage).getGlobalVisibleRect(currentPhone);
                assertEquals("Dragging must not scroll the lesson", phone.top, currentPhone.top);
                assertTrue("Button must reach above the accessibility rows", Math.abs(after.centerY() - topCenter) < 8);
                Rect hint = new Rect();
                findText(a.getWindow().getDecorView(), "클릭 버튼을 눌러보세요").getGlobalVisibleRect(hint);
                assertFalse("The hint must not cover the button at the top", Rect.intersects(after, hint));
                assertEquals(ClickOverlayLayout.Mode.COLLAPSED, control(a).getMode());
                button.set(after);
            });
            screenshot("tutorial-drag-phone-top");
            int bottomCenter = phone.bottom - button.height() / 2 - 16;
            drag(button.centerX(), button.centerY(), button.centerX(), bottomCenter);
            scenario.onActivity(a -> {
                Rect after = new Rect(); control(a).getGlobalVisibleRect(after);
                assertTrue("Button must also reach the bottom", Math.abs(after.centerY() - bottomCenter) < 8);
            });
            tapId(scenario, R.id.tutorial_floating_control);
            assertMode(scenario, ClickOverlayLayout.Mode.MENU);
        }
    }

    private void drag(float fromX, float fromY, float toX, float toY) {
        android.app.UiAutomation automation = instrumentation.getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY, 0);
        automation.injectInputEvent(down, true); down.recycle();
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 100);
        for (int i = 1; i <= 12; i++) {
            float fraction = i / 12f;
            MotionEvent move = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction, 0);
            automation.injectInputEvent(move, true); move.recycle(); SystemClock.sleep(16);
        }
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, toX, toY, 0);
        automation.injectInputEvent(up, true); up.recycle();
        instrumentation.waitForIdleSync(); SystemClock.sleep(250);
    }

    @Test public void fourStepsUseCurrentPanelsAndReturnHome() throws Exception {
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            scenario.onActivity(a -> {
                assertNull(findText(a.getWindow().getDecorView(), "4단계 연습"));
                assertNotNull(findText(a.getWindow().getDecorView(), "클릭을 함께 연습해요"));
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
            scenario.onActivity(a -> assertNotNull(findText(a.getWindow().getDecorView(), "요청이 맞는지 확인해요")));
            screenshot("tutorial-short-review");
            tapText(scenario, "안내 시작하기");
            scenario.onActivity(a -> {
                assertNotNull(findText(a.getWindow().getDecorView(), "자동으로 진행해요"));
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
            tapText(scenario, "더 커졌으면 좋겠어");
            scenario.onActivity(a -> assertNotNull(findText(a.getWindow().getDecorView(), "진행중이에요!")));
            tapId(scenario, R.id.tutorial_next);
            tapText(scenario, "네, 글씨가 잘 보여요");
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
                assertNotNull(findText(a.getWindow().getDecorView(), "요청을 적어주세요"));
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
                assertNotNull(findText(a.getWindow().getDecorView(), "편한 방법으로 요청해 보세요"));
                assertNull(findText(a.getWindow().getDecorView(), "요청을 멈췄어요"));
            });
        }
    }

    @Test public void voicePracticeAdvancesOnReleaseWithoutTimerPermissionOrServer() throws Exception {
        try (ActivityScenario<TutorialActivity> scenario = ActivityScenario.launch(TutorialActivity.class)) {
            tapId(scenario, R.id.tutorial_next);
            tapId(scenario, R.id.tutorial_floating_control);
            tapText(scenario, "말로 입력");
            microphoneTouch(scenario, MotionEvent.ACTION_DOWN);
            scenario.onActivity(a -> assertTrue(control(a).isVoiceInputActive()));
            microphoneTouch(scenario, MotionEvent.ACTION_CANCEL);
            SystemClock.sleep(1200);
            scenario.onActivity(a -> {
                assertFalse(control(a).isVoiceInputActive());
                assertNotNull(findText(a.getWindow().getDecorView(), "마이크를 꾹 눌러보세요"));
            });
            microphoneTouch(scenario, MotionEvent.ACTION_DOWN);
            scenario.onActivity(a -> assertTrue(control(a).isVoiceInputActive()));
            microphoneTouch(scenario, MotionEvent.ACTION_UP);
            scenario.onActivity(a -> {
                assertFalse(control(a).isVoiceInputActive());
                assertNotNull(findText(a.getWindow().getDecorView(), "요청이 맞는지 확인해요"));
                assertNotNull(findText(a.getWindow().getDecorView(), "안내 시작하기"));
            });
            screenshot("tutorial-offline-voice-review");
        }
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
        String[] current = {""};
        for (int attempt = 0; attempt < 30 && view[0] == null; attempt++) {
            instrumentation.waitForIdleSync();
            s.onActivity(a -> {
                current[0] = control(a).getMode() + ": " + ((TextView) a.findViewById(R.id.tutorial_title)).getText();
                view[0] = findText(a.getWindow().getDecorView(), label);
                if (view[0] != null) while (!view[0].isClickable() && view[0].getParent() instanceof View)
                    view[0] = (View) view[0].getParent();
            });
            if (view[0] == null) SystemClock.sleep(100);
        }
        assertNotNull("Missing " + label + " in " + current[0], view[0]);
        tap(view[0]);
    }
    private void tap(View view) {
        instrumentation.runOnMainSync(() -> view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true));
        Rect bounds = new Rect();
        boolean[] visible = {false};
        for (int attempt = 0; attempt < 30 && !visible[0]; attempt++) {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> visible[0] = view.isShown() && view.getGlobalVisibleRect(bounds));
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
