package com.click.ui.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
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
    @Test public void additionalQuestionFitsItsChoicesAndKeepsEditingOptional() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                for (float scale : new float[]{1f, 1.5f}) {
                    android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration());
                    config.fontScale = scale;
                    Context context = activity.createConfigurationContext(config);
                    TaskFlowOverlayView panel = new TaskFlowOverlayView(context, new Answers());
                    activity.setContentView(panel);
                    panel.setAvailableHeight(Math.round(650 * context.getResources().getDisplayMetrics().density));
                    try {
                        JSONObject data = json("{\"version\":\"goal-inputs.v1\",\"id\":\"q1\",\"source_text\":\"글자 크기 키워줘\",\"status\":\"needs_input\",\"values\":{},\"question\":{\"id\":\"scope\",\"kind\":\"choice\",\"message\":\"어디의 글자를 키울까요?\",\"choices\":[{\"label\":\"휴대폰 전체\",\"value\":\"system\"},{\"label\":\"특정 앱\",\"value\":\"app\"}]}}");
                        panel.preparation(new com.click.domain.goal.GoalPreparationState(data, null, "추가로 확인할게요"));
                    } catch (org.json.JSONException error) { throw new AssertionError(error); }
                    snapshot(panel, "preparation-" + scale);
                    assertNull(find(panel, "추가로 확인할게요"));
                    assertTrue(find(panel, "내 요청:").getTypeface().isBold());
                    assertTrue(find(panel, "글자 크기 키워줘").getTextSize() < find(panel, "어디의 글자를 키울까요?").getTextSize());
                    assertFalse(find(panel, "수정한 요청 확인").isShown());
                    assertFullyInside(panel, find(panel, "휴대폰 전체"));
                    assertFullyInside(panel, find(panel, "특정 앱"));
                    assertEquals(1, find(panel, "다시 듣기").getLineCount());
                    assertEquals(1, find(panel, "글로 보기").getLineCount());
                    find(panel, "내용 수정").performClick();
                    assertTrue(find(panel, "수정한 요청 확인").isShown());
                }
            });
        }
    }
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
        String goal, answer, control, clarification, replay;
        boolean audioStopped;
        public void replayGuidance(String message) { replay = message; }
        public void stopGuidance() { audioStopped = true; }
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

    @Test public void unresolvedRequestSupportsVoiceEditingAndCancellationWithoutGoalSelection() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                final int[] voiceEntries = {0};
                Answers answers = new Answers() {
                    @Override public void openInput() { voiceEntries[0]++; }
                };
                TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, answers);
                activity.setContentView(panel);
                panel.unresolvedGoal("이거 좀 바꿔줘");
                snapshot(panel, "unresolved");
                assertTrue(find(panel, "어디에서 무엇을 바꾸고 싶은지 알려주세요.").isShown());
                assertTrue(find(panel, "이거 좀 바꿔줘").isShown());
                assertNull(find(panel, "이 목표로 시작"));
                assertFalse(find(panel, "일시정지").isShown());
                assertFalse(findDescription(panel, "말이나 글로 입력하기").isShown());
                find(panel, "다시 말하기").performClick();
                assertEquals(1, voiceEntries[0]);
                assertTrue(answers.audioStopped);
                assertNull(answers.goal);

                find(panel, "글로 수정하기").performClick();
                EditText editor = (EditText) findDescription(panel, "요청 내용 수정");
                assertEquals("이거 좀 바꿔줘", editor.getText().toString());
                assertTrue(editor.isShown());
                editor.setText("   ");
                assertFalse(find(panel, "이 내용으로 다시 요청").isEnabled());
                find(panel, "이 내용으로 다시 요청").performClick();
                assertNull(answers.clarification);
                editor.setText(" 휴대폰 글자를 크게 해줘 ");
                panel.setNarrationState(false, true);
                assertEquals(" 휴대폰 글자를 크게 해줘 ", editor.getText().toString());
                snapshot(panel, "unresolved-edit");
                find(panel, "이 내용으로 다시 요청").performClick();
                assertEquals("휴대폰 글자를 크게 해줘", answers.clarification);
                assertNull(answers.goal);
                find(panel, "다시 말하기").performClick();
                assertEquals(2, voiceEntries[0]);
                find(panel, "요청 취소").performClick();
                assertEquals("cancel_task", answers.control);

                panel.progress("글자를 크게 보기", new JSONArray(), "디스플레이를 눌러 주세요");
                assertNull(find(panel, "이 내용으로 다시 요청"));
                assertTrue(find(panel, "일시정지").isShown());
                assertTrue(findDescription(panel, "말이나 글로 입력하기").isShown());
            });
        }
    }

    @Test public void unresolvedRequestKeepsActionsVisibleWithLargeTextAndKeyboardHeight() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                android.content.res.Configuration config = new android.content.res.Configuration(
                        activity.getResources().getConfiguration());
                config.fontScale = 1.5f;
                Context context = activity.createConfigurationContext(config);
                TaskFlowOverlayView panel = new TaskFlowOverlayView(context, new Answers());
                activity.setContentView(panel);
                float density = context.getResources().getDisplayMetrics().density;
                panel.setAvailableHeight(Math.round(440 * density));
                panel.unresolvedGoal("이 화면에서 바꾸고 싶은 게 있는데 어떻게 하는지 모르겠어요. ".repeat(4));
                snapshot(panel, "unresolved-large-text");
                TextView retry = find(panel, "다시 말하기");
                assertFullyInside(panel, retry);
                assertFullyInside(panel, find(panel, "요청 취소"));
                find(panel, "글로 수정하기").performClick();
                panel.setAvailableHeight(Math.round(320 * density));
                snapshot(panel, "unresolved-edit-large-text");
                assertFullyInside(panel, find(panel, "이 내용으로 다시 요청"));
                assertFullyInside(panel, find(panel, "요청 취소"));
            });
        }
    }

    private static void assertFullyInside(ViewGroup panel, View child) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        child.getDrawingRect(bounds);
        panel.offsetDescendantRectToMyCoords(child, bounds);
        assertTrue(child.isShown());
        assertTrue(bounds.top >= 0);
        assertTrue(bounds.bottom <= panel.getHeight());
        assertTrue(child.getHeight() >= Math.round(48 * panel.getResources().getDisplayMetrics().density));
    }

    @Test public void spokenProgressHidesInternalChecklistAndSupportsCaptions() throws Exception {
        JSONArray rows = new JSONArray("[{\"label\":\"디스플레이 메뉴 확인\",\"status\":\"completed\"},"
            + "{\"label\":\"글자 크기 조절하기\",\"status\":\"current\"},{\"label\":\"변경 결과 확인\",\"status\":\"pending\"},"
            + "{\"label\":\"Graph의 추가 단계\",\"status\":\"pending\"}]");
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                Answers answers = new Answers();
                TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, answers);
                activity.setContentView(panel); panel.progress("글자를 크게 보기", rows, "글자 크기 버튼을 눌러 주세요.");
                assertNotNull(find(panel, "글자 크기 버튼을 눌러 주세요."));
                assertNull(find(panel, "디스플레이 메뉴 확인"));
                assertNull(find(panel, "진행 단계"));
                assertEquals(View.GONE, find(panel, "글자 크기 버튼을 눌러 주세요.").getVisibility());
                find(panel, "다시 듣기").performClick(); assertEquals("글자 크기 버튼을 눌러 주세요.", answers.replay);
                panel.setNarrationState(true, false);
                find(panel, "음성 멈춤").performClick(); assertTrue(answers.audioStopped);
                panel.setNarrationState(false, false);
                find(panel, "글로 보기").performClick();
                assertEquals(View.VISIBLE, find(panel, "글자 크기 버튼을 눌러 주세요.").getVisibility());
                find(panel, "글 접기").performClick();
                panel.setNarrationState(false, true);
                assertEquals(View.VISIBLE, find(panel, "글자 크기 버튼을 눌러 주세요.").getVisibility());
                assertNotNull(findDescription(panel, "안내 창 줄이기"));
                assertFalse(containsCheckBox(panel)); snapshot(panel, "progress");
                panel.completed("사용자 보고상 해결, 기술적 검증 불가", () -> {});
                assertNull(findDescription(panel, "지금 할 일: 글자 크기 조절하기"));
                assertNull(findDescription(panel, "완료: 글자 크기 조절하기"));
                panel.progress("글자를 크게 보기", rows);
                find(panel, "결과 확인하기").performClick(); assertEquals("request_result", answers.control);
                find(panel, "요청 중지").performClick(); assertEquals("cancel_task", answers.control);
            });
        }
    }
    @Test public void requirementsUseRequestBoxAndSpeechWithExplicitConfirmation() {
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
                assertNull(find(panel[0], "요청 확인 체크리스트"));
                assertNotNull(find(panel[0], "내 요청:"));
                assertNotNull(find(panel[0], "글자 크기 키워줘"));
                assertEquals(View.GONE, find(panel[0], review.prompt(false)).getVisibility());
                assertFalse(find(panel[0], "이 내용으로 시작").isEnabled());
                assertFalse(confirmed[0]);
                snapshot(panel[0], "requirements-missing");
                find(panel[0], "휴대폰 기본 글자").performClick();
                assertNotNull(find(panel[0], review.summary()));
                assertTrue(find(panel[0], "이 내용으로 시작").isEnabled());
                assertFalse(confirmed[0]);
                snapshot(panel[0], "requirements-ready");
                find(panel[0], "이 내용으로 시작").performClick(); assertTrue(confirmed[0]);
                panel[0].progress(review.summary(), new JSONArray().put(json("{\"label\":\"디스플레이 메뉴 확인\",\"status\":\"completed\"}")), "디스플레이를 눌러 주세요", false);
                assertNull(find(panel[0], "디스플레이 메뉴 확인"));
                assertNull(find(panel[0], "요청 확인 체크리스트"));
                assertNotNull(find(panel[0], review.summary()));
                RequirementReview app = new RequirementReview("카카오톡 전체 글자를 크게");
                panel[0].requirements(app, false);
                assertNotNull(find(panel[0], "카카오톡 전체 글자를 크게"));
                assertFalse(find(panel[0], "이 내용으로 시작").isEnabled());
                snapshot(panel[0], "requirements-app");
                find(panel[0], "요청 중지").performClick(); assertEquals("cancel_task", answers.control);
            });
        }
    }
    @Test public void cardHeightFitsContentAndLongRequestsStayScrollable() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, new Answers());
                activity.setContentView(panel);
                float density = activity.getResources().getDisplayMetrics().density;
                int width = new ClickOverlayLayout(activity).getWindowWidth(ClickOverlayLayout.Mode.MENU,
                        activity.getResources().getDisplayMetrics().widthPixels);
                int cap = Math.round(440 * density);
                panel.setAvailableHeight(cap);
                panel.waiting("요청을 살펴보고 있어요");
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
                int shortHeight = panel.getMeasuredHeight();
                RequirementReview review = new RequirementReview("글자 크기를 키우고 싶어요. ".repeat(20));
                panel.requirements(review, false);
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
                panel.layout(0, 0, width, panel.getMeasuredHeight());
                assertTrue(panel.getMeasuredHeight() > shortHeight);
                assertTrue(panel.getMeasuredHeight() <= cap);
                assertEquals(width, panel.getMeasuredWidth());
                assertEquals(View.GONE, find(panel, "이 내용으로 시작").getVisibility());
                assertTrue(find(panel, "요청 중지").getHeight() > 0);
                find(panel, "요청 더 보기").performClick();
                assertNotNull(find(panel, "요청 접기"));
                assertEquals(activity.getResources().getFont(com.click.designpreview.R.font.pretendard_semibold),
                        find(panel, "요청을 확인해 주세요").getTypeface());
                snapshot(panel, "requirements-long");
            });
        }
    }
    @Test public void minimizedProgressScrollsAtLargeFontAndResultRestoresChoices() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                android.content.res.Configuration config = new android.content.res.Configuration(
                        activity.getResources().getConfiguration());
                config.fontScale = 2f;
                TaskFlowOverlayView panel = new TaskFlowOverlayView(activity.createConfigurationContext(config), new Answers());
                activity.setContentView(panel);
                int cap = Math.round(220 * panel.getResources().getDisplayMetrics().density);
                panel.setAvailableHeight(cap);
                panel.progress("글자 크기 키우기", null, "파란색으로 표시된 글자 크기 버튼을 눌러주세요. ".repeat(8));
                panel.setMinimizedProgress(true);
                snapshot(panel, "progress-bubble-large-text");
                assertTrue(panel.isMinimizedProgress());
                assertTrue(panel.getHeight() <= cap);
                assertFalse(panel.findViewById(com.click.R.id.task_flow_collapse).isShown());
                TextView expand = find(panel, "click · 펼치기");
                assertTrue(expand.isShown());
                android.widget.ScrollView scroll = (android.widget.ScrollView) expand.getParent().getParent();
                assertTrue(scroll.getChildAt(0).getHeight() > scroll.getHeight());
                panel.result(json("{\"message\":\"글자가 편하게 보이나요?\"}"));
                panel.setAvailableHeight(cap * 3);
                snapshot(panel, "result-large-text");
                assertFalse(panel.isMinimizedProgress());
                assertFalse(panel.hasProgress());
                assertTrue(find(panel, "네, 잘 보여요").isShown());
                assertTrue(panel.findViewById(com.click.R.id.task_flow_collapse).isShown());
                panel.setMinimizedProgress(true);
                snapshot(panel, "result-question-bubble");
                assertTrue(panel.isMinimizedProgress());
                assertEquals("글자가 편하게 보이나요?", panel.guidanceMessage());
                assertTrue(find(panel, "click · 펼치기").isShown());
                panel.setMinimizedProgress(false);
                snapshot(panel, "result-question-restored");
                assertTrue(find(panel, "네, 잘 보여요").isShown());
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
        int width = Math.min(view.getResources().getDisplayMetrics().widthPixels
                - Math.round(24 * view.getResources().getDisplayMetrics().density),
                Math.round(320 * view.getResources().getDisplayMetrics().density));
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
