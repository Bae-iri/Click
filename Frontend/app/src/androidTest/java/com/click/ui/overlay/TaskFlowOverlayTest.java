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
    @Test public void compactVoiceAndProgressShowStatusWithoutScrolling() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            for (float scale : new float[]{1f, 1.5f}) {
                ClickOverlayLayout[] voice = {null};
                TaskFlowOverlayView[] progress = {null};
                scenario.onActivity(activity -> {
                    android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration());
                    config.fontScale = scale;
                    Context context = activity.createConfigurationContext(config);
                    voice[0] = new ClickOverlayLayout(context);
                    voice[0].openVoice();
                    voice[0].showRecognizedSpeechResult("휴대폰 글자를 조금 더 크게 해 줘");
                    voice[0].setRequestStatus("요청을 진행하고 있어요", true);
                    voice[0].setLoadingStatus("현재 화면을 확인하고 있어요…");
                    int width = voice[0].getWindowWidth(ClickOverlayLayout.Mode.VOICE, context.getResources().getDisplayMetrics().widthPixels);
                    int height = voice[0].getWindowHeight(ClickOverlayLayout.Mode.VOICE);
                    android.widget.FrameLayout host = new android.widget.FrameLayout(activity);
                    host.addView(voice[0], new android.widget.FrameLayout.LayoutParams(width, height, android.view.Gravity.CENTER));
                    activity.setContentView(host);
                });
                // Let Android lay out and animate the real attached view before checking/capturing it.
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(250);
                saveDisplay("voice-screen-" + scale);
                scenario.onActivity(activity -> {
                    ViewGroup card = voice[0].findViewById(com.click.R.id.overlay_microphone);
                    assertFalse(find(voice[0], "현재 화면을 확인하고 있어요…") != null
                            && find(voice[0], "현재 화면을 확인하고 있어요…").isShown());
                    assertVisibleWithin(voice[0], find(voice[0], "휴대폰 글자를 조금 더 크게 해 줘"));
                    assertVisibleWithin(voice[0], find(voice[0], "요청 중지"));
                    assertVisibleWithin(voice[0], find(voice[0], "줄이기"));
                    assertNull(find(voice[0], "떼면 입력 · 밖으로 옮기면 취소\n최대 16초"));
                    saveImage(voice[0], "voice-compact-" + scale);
                    Context context = voice[0].getContext();
                    progress[0] = new TaskFlowOverlayView(context, new Answers());
                    progress[0].setAvailableHeight(Math.round(440 * context.getResources().getDisplayMetrics().density));
                    progress[0].progress("휴대폰 글자를 크게 보기", null, "설정 목록을 내려서 디스플레이 항목을 찾고 있어요.");
                    progress[0].setLoadingStatus("현재 화면을 확인하고 있어요…");
                    android.widget.FrameLayout host = new android.widget.FrameLayout(activity);
                    int width = progress[0].getWindowWidth(context.getResources().getDisplayMetrics().widthPixels);
                    host.addView(progress[0], new android.widget.FrameLayout.LayoutParams(width, -2, android.view.Gravity.CENTER));
                    activity.setContentView(host);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(250);
                saveDisplay("progress-screen-" + scale);
                scenario.onActivity(activity -> {
                    assertVisibleWithin(progress[0], find(progress[0], "설정 목록을 내려서 디스플레이 항목을 찾고 있어요."));
                    assertVisibleWithin(progress[0], find(progress[0], "현재 화면을 확인하고 있어요…"));
                    assertVisibleWithin(progress[0], find(progress[0], "결과 확인하기"));
                    assertFullyInside(progress[0], find(progress[0], "요청 중지"));
                    assertNull(find(progress[0], "글로 보기"));
                    saveImage(progress[0], "task-flow-compact-progress-" + scale);
                });
            }
        }
    }

    private static void assertVisibleWithin(ViewGroup parent, View child) {
        assertNotNull(child);
        assertTrue(child.isShown());
        android.graphics.Rect bounds = new android.graphics.Rect();
        child.getDrawingRect(bounds);
        parent.offsetDescendantRectToMyCoords(child, bounds);
        assertTrue("Top is clipped", bounds.top >= 0);
        assertTrue("Bottom is clipped: " + bounds + " in " + parent.getHeight(), bounds.bottom <= parent.getHeight());
        assertTrue(bounds.left >= 0 && bounds.right <= parent.getWidth());
    }

    private static void saveImage(View view, String name) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(view.getContext().getCacheDir(), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception error) { throw new AssertionError(error); }
        bitmap.recycle();
    }

    private static void saveDisplay(String name) {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Bitmap bitmap = instrumentation.getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(
                instrumentation.getTargetContext().getCacheDir(), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception error) { throw new AssertionError(error); }
        bitmap.recycle();
    }

    @Test public void additionalQuestionFitsItsChoicesAndKeepsEditingOptional() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                for (float scale : new float[]{1f, 1.5f}) {
                    android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration());
                    config.fontScale = scale;
                    Context context = activity.createConfigurationContext(config);
                    String[] selected = {null}; boolean[] confirmed = {false};
                    Answers answers = new Answers() {
                        @Override public void answerGoalInput(String value) { selected[0] = value; }
                        @Override public void confirmRequirements() { confirmed[0] = true; }
                    };
                    TaskFlowOverlayView panel = new TaskFlowOverlayView(context, answers);
                    activity.setContentView(panel);
                    panel.setAvailableHeight(Math.round(650 * context.getResources().getDisplayMetrics().density));
                    try {
                        JSONObject data = json("{\"version\":\"goal-inputs.v1\",\"id\":\"q1\",\"source_text\":\"글자 크기 키워줘\",\"status\":\"needs_input\",\"values\":{},\"question\":{\"id\":\"scope\",\"kind\":\"choice\",\"message\":\"어디의 글자를 키울까요?\",\"choices\":[{\"label\":\"휴대폰 전체\",\"value\":\"system\"},{\"label\":\"특정 앱\",\"value\":\"app\"}]}}");
                        panel.preparation(new com.click.domain.goal.GoalPreparationState(data, null, "추가로 확인할게요"));
                        snapshot(panel, "preparation-" + scale);
                        assertNull(find(panel, "1/2"));
                        assertFalse(find(panel, "수정한 요청 확인").isShown());
                        assertFullyInside(panel, find(panel, "휴대폰 전체"));
                        assertFullyInside(panel, find(panel, "특정 앱"));
                        assertFalse(find(panel, "다음 단계").isEnabled());
                        find(panel, "휴대폰 전체").performClick();
                        assertNull(selected[0]);
                        assertTrue(find(panel, "다음 단계").isEnabled());
                        find(panel, "다음 단계").performClick();
                        assertEquals("system", selected[0]);
                        find(panel, "다른 내용 입력하기").performClick();
                        assertTrue(find(panel, "수정한 요청 확인").isShown());
                        JSONObject ready = json("{\"version\":\"goal-inputs.v1\",\"id\":\"q1\",\"source_text\":\"글자 크기 키워줘\",\"status\":\"ready\",\"values\":{\"direction\":\"increase\"},\"missing_fields\":[],\"graph_reference\":{}}");
                        panel.preparation(new com.click.domain.goal.GoalPreparationState(ready,
                                json("{\"goal_type\":\"settings.font_size.increase\"}"), "휴대폰 전체의 글자를 더 크게 바꿀까요?"));
                        snapshot(panel, "preparation-ready-" + scale);
                        assertNull(find(panel, "2/2"));
                        assertNotNull(find(panel, "이렇게 진행할게요"));
                        assertNotNull(find(panel, "휴대폰의 기본 글자 크기를\n더 크게 변경할게요."));
                        assertFullyInside(panel, find(panel, "이대로 시작하기"));
                        View edit = find(panel, "내용 수정하기");
                        View start = find(panel, "이대로 시작하기");
                        android.graphics.Rect editBounds = new android.graphics.Rect();
                        android.graphics.Rect startBounds = new android.graphics.Rect();
                        edit.getDrawingRect(editBounds); panel.offsetDescendantRectToMyCoords(edit, editBounds);
                        start.getDrawingRect(startBounds); panel.offsetDescendantRectToMyCoords(start, startBounds);
                        assertEquals("Edit link aligns with the summary and action edge", startBounds.right, editBounds.right);
                        assertTrue("Edit link stays compact", editBounds.width() < startBounds.width() / 2);
                        assertFalse(confirmed[0]);
                        find(panel, "이대로 시작하기").performClick();
                        assertTrue(confirmed[0]); confirmed[0] = false;
                    } catch (org.json.JSONException error) { throw new AssertionError(error); }
                }
            });
        }
    }

    @Test public void voiceStatesUseDistinctArtworkAndWindowsFitTheirContent() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                ClickOverlayLayout voice = new ClickOverlayLayout(activity);
                activity.setContentView(voice);
                voice.openVoice();
                int width = voice.getWindowWidth(ClickOverlayLayout.Mode.VOICE, activity.getResources().getDisplayMetrics().widthPixels);
                int readyHeight = voice.getWindowHeight(ClickOverlayLayout.Mode.VOICE);
                voice.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(readyHeight, View.MeasureSpec.EXACTLY));
                voice.layout(0, 0, width, readyHeight);
                VoiceCaptureIndicator indicator = indicator(voice);
                assertEquals(VoiceCaptureIndicator.State.READY, indicator.getState());
                saveImage(voice, "reference-voice-ready");
                voice.setVoiceInputActive(true);
                assertEquals(VoiceCaptureIndicator.State.LISTENING, indicator.getState());
                saveImage(voice, "reference-voice-listening");
                voice.setVoiceInputActive(false); voice.setVoiceInputProcessing(true);
                assertEquals(VoiceCaptureIndicator.State.PROCESSING, indicator.getState());
                assertFalse(voice.findViewById(com.click.R.id.overlay_microphone).isEnabled());
                saveImage(voice, "reference-voice-processing");
                voice.setVoiceInputProcessing(false);
                voice.openText();
                assertTrue(voice.getWindowHeight(ClickOverlayLayout.Mode.TEXT) < readyHeight);
                voice.openMenu();
                voice.setRequestStatus("요청을 멈췄어요", false);
                assertNull(find(voice, "요청을 멈췄어요"));
                assertNull(voice.findViewById(com.click.R.id.overlay_stop_request));
                assertNotNull(find(voice, "무엇을 도와드릴까요?"));
                assertNotNull(find(voice, "말로 입력하기"));
                assertNotNull(find(voice, "글로 입력하기"));
                width = voice.getWindowWidth(ClickOverlayLayout.Mode.MENU, activity.getResources().getDisplayMetrics().widthPixels);
                int height = voice.getWindowHeight(ClickOverlayLayout.Mode.MENU);
                voice.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                voice.layout(0, 0, width, height); saveImage(voice, "reference-input-menu");
            });
        }
    }
    private static VoiceCaptureIndicator indicator(View view) {
        if (view instanceof VoiceCaptureIndicator) return (VoiceCaptureIndicator) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            VoiceCaptureIndicator found = indicator(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
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
                assertFalse(find(panel, "다음 단계").isEnabled());
                first.performClick(); second.performClick();
                assertFalse(((RadioButton) first).isChecked());
                assertTrue(((RadioButton) second).isChecked());
                assertNull(answers.goal);
                find(panel, "다음 단계").performClick();
                assertEquals("settings.font_size.decrease", answers.goal);
                assertNotNull(find(panel, "다른 내용 입력하기"));
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
                assertNull(find(panel, "다음 단계"));
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

    @Test public void fontDirectionAndAutomaticBubbleKeepOnlyRelevantControls() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                for (float scale : new float[]{1f, 1.5f}) {
                    android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration());
                    config.fontScale = scale;
                    Answers answers = new Answers();
                    TaskFlowOverlayView panel = new TaskFlowOverlayView(activity.createConfigurationContext(config), answers);
                    activity.setContentView(panel);
                    String[] selected = {null};
                    panel.fontSizeConfirmation(direction -> selected[0] = direction);
                    snapshot(panel, "font-direction-" + scale);
                    assertNull(find(panel, "글로 보기"));
                    assertNull(find(panel, "아니요"));
                    assertNull(find(panel, "네, 계속할게요"));
                    assertFullyInside(panel, find(panel, "작게"));
                    assertFullyInside(panel, find(panel, "크게"));
                    find(panel, "작게").performClick(); assertEquals("decrease", selected[0]);
                    find(panel, "크게").performClick(); assertEquals("increase", selected[0]);
                    find(panel, "다시 듣기").performClick();
                    assertEquals(TaskFlowOverlayView.FONT_SIZE_QUESTION, answers.replay);
                    panel.progress("글자 크기 키우기", null, "디스플레이를 눌러주세요.");
                    panel.setAutomaticProgress(true);
                    panel.setMinimizedProgress(true);
                    panel.setLoadingStatus("화면을 확인하고 있어요…");
                    panel.setAutomationStatus("다음 버튼을 눌렀어요.");
                    snapshot(panel, "automatic-bubble-" + scale);
                    assertTrue(find(panel, TaskFlowOverlayView.AUTOMATIC_PROGRESS_MESSAGE).isShown());
                    assertFalse(find(panel, "디스플레이를 눌러주세요.").isShown());
                    assertFalse(find(panel, "화면을 확인하고 있어요…").isShown());
                    assertFalse(find(panel, "다음 버튼을 눌렀어요.").isShown());
                    panel.result(json("{\"message\":\"글자가 편하게 보이나요?\"}"));
                    panel.setMinimizedProgress(true);
                    snapshot(panel, "result-after-automatic-" + scale);
                    assertTrue(find(panel, "글자가 편하게 보이나요?").isShown());
                }
            });
        }
    }

    @Test public void completionStaysOpenUntilTheExplicitExitButton() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            TaskFlowOverlayView[] panel = {null};
            int[] exits = {0};
            scenario.onActivity(activity -> {
                panel[0] = new TaskFlowOverlayView(activity, new Answers());
                activity.setContentView(panel[0]);
                panel[0].completed("원하는 크기로 바뀌었어요.", () -> exits[0]++);
                snapshot(panel[0], "completion-with-exit");
            });
            SystemClock.sleep(1400);
            scenario.onActivity(activity -> {
                assertEquals(0, exits[0]);
                assertFullyInside(panel[0], find(panel[0], "클릭 종료하기"));
                assertFalse(find(panel[0], "일시정지").isShown());
                find(panel[0], "클릭 종료하기").performClick();
                find(panel[0], "클릭 종료하기").performClick();
                assertEquals(1, exits[0]);
            });
        }
    }

    @Test public void progressShowsCurrentInstructionImmediatelyAndKeepsAudioControls() throws Exception {
        JSONArray rows = new JSONArray("[{\"label\":\"디스플레이 메뉴 확인\",\"status\":\"completed\"},"
            + "{\"label\":\"글자 크기 조절하기\",\"status\":\"current\"},{\"label\":\"변경 결과 확인\",\"status\":\"pending\"},"
            + "{\"label\":\"Graph의 추가 단계\",\"status\":\"pending\"}]");
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                Answers answers = new Answers();
                TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, answers);
                activity.setContentView(panel); panel.progress("글자를 크게 보기", rows, "글자 크기 버튼을 눌러 주세요.");
                panel.setGuidanceProgress(1, 4);
                assertTrue(find(panel, "1/4").isShown());
                panel.setMinimizedProgress(true);
                assertFalse(find(panel, "1/4").isShown());
                panel.setMinimizedProgress(false);
                assertTrue(find(panel, "1/4").isShown());
                assertNotNull(find(panel, "글자 크기 버튼을 눌러 주세요."));
                assertNull(find(panel, "디스플레이 메뉴 확인"));
                assertNull(find(panel, "진행 단계"));
                assertEquals(View.VISIBLE, find(panel, "글자 크기 버튼을 눌러 주세요.").getVisibility());
                find(panel, "다시 듣기").performClick(); assertEquals("글자 크기 버튼을 눌러 주세요.", answers.replay);
                panel.setNarrationState(true, false);
                find(panel, "음성 멈춤").performClick(); assertTrue(answers.audioStopped);
                panel.setNarrationState(false, false);
                assertNull(find(panel, "글로 보기"));
                panel.setNarrationState(false, true);
                assertEquals(View.VISIBLE, find(panel, "글자 크기 버튼을 눌러 주세요.").getVisibility());
                assertNotNull(findDescription(panel, "안내 창 줄이기"));
                assertFalse(containsCheckBox(panel)); snapshot(panel, "progress");
                panel.completed("사용자 보고상 해결, 기술적 검증 불가", () -> {});
                assertNull(find(panel, "1/4"));
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
                assertNull(find(panel[0], "1/2"));
                assertFalse(find(panel[0], "다음 단계").isEnabled());
                find(panel[0], "휴대폰 기본 글자").performClick();
                assertFalse(review.isPhone());
                find(panel[0], "다음 단계").performClick();
                assertTrue(review.isPhone());
                assertNull(find(panel[0], "2/2"));
                assertNotNull(find(panel[0], review.summary()));
                assertFalse(confirmed[0]);
                snapshot(panel[0], "requirements-ready");
                find(panel[0], "이대로 시작하기").performClick(); assertTrue(confirmed[0]);
                panel[0].requirements(new RequirementReview("카카오톡 전체 글자를 크게"), false);
                assertFalse(find(panel[0], "이대로 시작하기").isShown());
                snapshot(panel[0], "requirements-app");
            });
        }
    }
    @Test public void cardHeightFitsContentAndLongRequestsStayScrollable() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, new Answers());
                activity.setContentView(panel);
                float density = activity.getResources().getDisplayMetrics().density;
                int width = panel.getWindowWidth(activity.getResources().getDisplayMetrics().widthPixels);
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
                assertEquals(View.GONE, find(panel, "이대로 시작하기").getVisibility());
                assertTrue(find(panel, "이전 단계").getHeight() > 0);
                find(panel, "다른 내용 입력하기").performClick();
                assertTrue(find(panel, "수정한 요청 확인").isShown());
                assertEquals(activity.getResources().getFont(com.click.designpreview.R.font.pretendard_semibold),
                        find(panel, "어떤 작업을 도와드릴까요?").getTypeface());
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
        int width = ((TaskFlowOverlayView) view).getWindowWidth(view.getResources().getDisplayMetrics().widthPixels);
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
