package com.click.ui.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import com.click.domain.goal.RequirementReview;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TaskFlowOverlayTest {
    @Test public void voiceEntryOpensTheFloatingControlInVoiceMode() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ClickOverlayLayout[] overlay = new ClickOverlayLayout[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            overlay[0] = new ClickOverlayLayout(app);
            overlay[0].setCallback(new ClickOverlayLayout.Callback() {
                public void onModeChanged(ClickOverlayLayout.Mode oldMode, ClickOverlayLayout.Mode newMode) {}
                public void onDragStarted() {}
                public void onDragBy(float deltaX, float deltaY) {}
                public void onDragEnded() {}
                public void onTextSubmitted(String text) {}
                public void onCloseRequested() {}
            });
            overlay[0].openVoice();
            assertFalse("Opening voice input must leave the microphone off", overlay[0].isVoiceInputActive());
        });
        assertEquals(ClickOverlayLayout.Mode.VOICE, overlay[0].getMode());
    }

    @Test public void floatingButtonRendersCurrentClickMark() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ClickOverlayLayout button = new ClickOverlayLayout(app);
            int size = Math.round(64 * app.getResources().getDisplayMetrics().density);
            button.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            button.layout(0, 0, size, size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            button.draw(new Canvas(bitmap));
            try (FileOutputStream out = new FileOutputStream(
                    new File(app.getCacheDir(), "floating-button-current.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            bitmap.recycle();
        });
    }

    private static class Answers implements TaskFlowOverlayView.Listener {
        String goal, answer, control, clarification;
        public void chooseGoal(JSONObject value) { goal = value.optString("goal_type"); }
        public void clarify(String value) { clarification = value; }
        public void rephrase(String value) { clarification = value; }
        public void result(String value) { answer = value; }
        public void adjust(String value) { answer = value; }
        public void control(String value) { control = value; }
    }
    @Test public void touchOnlySingleChoiceAndResultAndStop() throws Exception {
        JSONArray goals = new JSONArray("[{\"label\":\"글자를 크게 보기\",\"goal\":{\"goal_type\":\"settings.font_size.increase\"}},"
                + "{\"label\":\"글자를 작게 보기\",\"goal\":{\"goal_type\":\"settings.font_size.decrease\"}}]");
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                Answers answers = new Answers();
                TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, answers);
                activity.setContentView(panel);
                panel.goals("원하는 목표를 하나 골라주세요.", goals);
                TextView first = find(panel, "글자를 크게 보기"), second = find(panel, "글자를 작게 보기");
                assertTrue(first instanceof RadioButton);
                assertFalse(find(panel, "이 목표로 시작").isEnabled());
                first.performClick(); second.performClick();
                assertFalse(((RadioButton) first).isChecked());
                assertTrue(((RadioButton) second).isChecked());
                assertNull(answers.goal);
                find(panel, "이 목표로 시작").performClick();
                assertEquals("settings.font_size.decrease", answers.goal);
                find(panel, "잘 모르겠어요").performClick(); assertEquals("unsure", answers.clarification);
                snapshot(panel, "goals");
                panel.result(json("{\"message\":\"글자 크기를 키웠어요. 이제 글자가 보기 편한가요?\"}"));
                find(panel, "네, 잘 보여요").performClick(); assertEquals("satisfied", answers.answer);
                find(panel, "아직 불편해요").performClick(); assertEquals("needs_help", answers.answer);
                snapshot(panel, "result");
                find(panel, "일시정지").performClick(); assertEquals("pause_task", answers.control);
                panel.paused(); find(panel, "이어서 진행").performClick(); assertEquals("resume_task", answers.control);
                find(panel, "요청 중지").performClick(); assertEquals("cancel_task", answers.control);
            });
        }
    }

    @Test public void progressIsReadOnlyAndSupportsDynamicRouteLength() throws Exception {
        JSONArray rows = new JSONArray("[{\"label\":\"디스플레이 메뉴 확인\",\"status\":\"completed\"},"
            + "{\"label\":\"글자 크기 조절하기\",\"status\":\"current\"},{\"label\":\"변경 결과 확인\",\"status\":\"pending\"},"
            + "{\"label\":\"Graph의 추가 단계\",\"status\":\"pending\"}]");
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                Answers answers = new Answers();
                TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, answers);
                activity.setContentView(panel); panel.progress("글자를 크게 보기", rows, "글자 크기 버튼을 눌러 주세요.");
                assertNotNull(find(panel, "글자 크기 버튼을 눌러 주세요."));
                assertNotNull(find(panel, "디스플레이 메뉴 확인"));
                assertNotNull(findDescription(panel, "완료: 디스플레이 메뉴 확인"));
                assertNotNull(findDescription(panel, "지금 할 일: 글자 크기 조절하기"));
                assertNotNull(findDescription(panel, "대기: Graph의 추가 단계"));
                assertNotNull(findDescription(panel, "Click 로고"));
                assertFalse(containsCheckBox(panel)); snapshot(panel, "progress");
                panel.completed("사용자 보고상 해결, 기술적 검증 불가", () -> {});
                assertNotNull(findDescription(panel, "지금 할 일: 글자 크기 조절하기"));
                assertNull(findDescription(panel, "완료: 글자 크기 조절하기"));
                panel.progress("글자를 크게 보기", rows);
                find(panel, "결과 확인하기").performClick(); assertEquals("request_result", answers.control);
                find(panel, "요청 중지").performClick(); assertEquals("cancel_task", answers.control);
            });
        }
    }
    @Test public void requirementsAskForScopeAndKeepExecutionStepsOutOfChecklist() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                RequirementReview review = new RequirementReview("글자 크기 키워줘");
                final TaskFlowOverlayView[] panel = new TaskFlowOverlayView[1];
                final boolean[] confirmed = {false};
                Answers answers = new Answers() {
                    @Override public void requirementChoice(String choice) {
                        review.choose(choice); panel[0].requirements(review, review.isPhone());
                    }
                    @Override public void confirmRequirements() { confirmed[0] = true; }
                };
                panel[0] = new TaskFlowOverlayView(activity, answers);
                activity.setContentView(panel[0]); panel[0].requirements(review, false);
                assertNotNull(find(panel[0], "대상 · 확인 필요"));
                assertFalse(find(panel[0], "이 내용으로 시작").isEnabled());
                assertFalse(confirmed[0]);
                snapshot(panel[0], "requirements-missing");
                find(panel[0], "휴대폰 기본 글자").performClick();
                assertNotNull(find(panel[0], "적용 범위 · 휴대폰 기본 글자 크기"));
                assertTrue(find(panel[0], "이 내용으로 시작").isEnabled());
                assertFalse(confirmed[0]);
                snapshot(panel[0], "requirements-ready");
                find(panel[0], "이 내용으로 시작").performClick(); assertTrue(confirmed[0]);
                panel[0].progress(review.summary(), new JSONArray().put(json("{\"label\":\"디스플레이 메뉴 확인\",\"status\":\"completed\"}")), "디스플레이를 눌러 주세요", false);
                assertNull(find(panel[0], "디스플레이 메뉴 확인"));
                assertNotNull(find(panel[0], "요청 확인 체크리스트"));
                assertNotNull(find(panel[0], "대상 · 휴대폰 설정"));
                RequirementReview app = new RequirementReview("카카오톡 전체 글자를 크게");
                panel[0].requirements(app, false);
                assertNotNull(find(panel[0], "대상 · 카카오톡"));
                assertNotNull(find(panel[0], "적용 범위 · 앱 전체 글자"));
                assertFalse(find(panel[0], "이 내용으로 시작").isEnabled());
                snapshot(panel[0], "requirements-app");
                find(panel[0], "요청 중지").performClick(); assertEquals("cancel_task", answers.control);
            });
        }
    }
    private static JSONObject json(String value) {
        try { return new JSONObject(value); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static TextView find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = find(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private static View findDescription(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
    private static boolean containsCheckBox(View view) {
        if (view instanceof CheckBox) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsCheckBox(((ViewGroup) view).getChildAt(i))) return true;
        return false;
    }
    private static void snapshot(View view, String name) {
        int width = view.getResources().getDisplayMetrics().widthPixels
                - Math.round(24 * view.getResources().getDisplayMetrics().density);
        view.forceLayout();
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, width, view.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(view.getContext().getCacheDir(), "task-flow-" + name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) { throw new AssertionError(e); }
        bitmap.recycle();
    }
}
