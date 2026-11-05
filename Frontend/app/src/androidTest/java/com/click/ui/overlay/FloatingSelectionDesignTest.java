package com.click.ui.overlay;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import com.click.R;
import com.click.domain.goal.GoalPreparationState;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingSelectionDesignTest {
    @Test public void attachedSelectionRendersHeaderAndChecksBeforeCapture() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            for (float scale : new float[]{1f, 1.5f, 2f}) {
                for (int width : new int[]{340, 300}) {
                    TaskFlowOverlayView[] panel = {null};
                    scenario.onActivity(activity -> {
                        Configuration config = new Configuration(activity.getResources().getConfiguration());
                        config.fontScale = scale;
                        Context context = activity.createConfigurationContext(config);
                        panel[0] = new TaskFlowOverlayView(context, new Responses());
                        FrameLayout host = new FrameLayout(activity);
                        host.setBackgroundColor(0xFFF4F7FC);
                        panel[0].setAvailableHeight(Math.round(680 * context.getResources().getDisplayMetrics().density));
                        host.addView(panel[0], new FrameLayout.LayoutParams(Math.round(width * context.getResources().getDisplayMetrics().density), -2, android.view.Gravity.CENTER));
                        activity.setContentView(host);
                        try {
                            panel[0].preparation(new GoalPreparationState(new JSONObject("{\"version\":\"goal-inputs.v1\",\"id\":\"q\","
                                    + "\"source_text\":\"글자 크기 키워줘\",\"status\":\"needs_input\",\"values\":{},"
                                    + "\"question\":{\"id\":\"scope\",\"kind\":\"choice\",\"message\":\"어디의 글자를 키울까요?\","
                                    + "\"choices\":[{\"value\":\"system\",\"label\":\"휴대폰 전체\"},"
                                    + "{\"value\":\"app\",\"label\":\"특정 앱 안에서만\"}]}}"), null, "범위를 선택해 주세요."));
                        } catch (Exception e) { throw new AssertionError(e); }
                        find(panel[0], "휴대폰 전체").performClick();
                    });
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    android.os.SystemClock.sleep(200);
                    scenario.onActivity(activity -> {
                        for (String label : new String[]{"다시 듣기", "줄이기", "휴대폰 전체", "특정 앱 안에서만", "직접 입력하기"}) {
                            TextView view = find(panel[0], label);
                            assertReadable(view);
                            assertTrue(label + " needs a rendered text line", view.getLineCount() > 0);
                        }
                        assertNotNull(description(panel[0], "CLICK"));
                        assertNull(find(panel[0], "클릭 안내"));
                        if (scale == 1f) {
                            float density = panel[0].getResources().getDisplayMetrics().density;
                            assertTrue("Selection card should stay compact", find(panel[0], "휴대폰 전체").getHeight() <= Math.round(50 * density));
                            assertTrue("The guide must use the narrower window", panel[0].getWindowWidth(2000) <= Math.round(340 * density));
                        }
                        assertNotNull(find(panel[0], "휴대폰 전체").getCompoundDrawables()[0]);
                        assertNull(find(panel[0], "휴대폰 전체").getCompoundDrawables()[2]);
                        save(panel[0], "floating-rendered-" + width + "-" + scale);
                    });
                }
            }
        }
    }

    @Test public void selectionEditingAndHeaderReplayWorkAtLargeFonts() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                for (float scale : new float[]{1f, 1.5f, 2f}) {
                    Configuration config = new Configuration(activity.getResources().getConfiguration());
                    config.fontScale = scale;
                    Context context = activity.createConfigurationContext(config);
                    Responses responses = new Responses();
                    TaskFlowOverlayView panel = new TaskFlowOverlayView(context, responses);
                    FrameLayout host = new FrameLayout(activity);
                    host.addView(panel, new FrameLayout.LayoutParams(-1, -2));
                    activity.setContentView(host);
                    try {
                        panel.preparation(new GoalPreparationState(new JSONObject("{\"version\":\"goal-inputs.v1\","
                                + "\"id\":\"scope-test\",\"source_text\":\"글자 크기 키워줘\",\"status\":\"needs_input\","
                                + "\"values\":{},\"question\":{\"id\":\"scope\",\"kind\":\"choice\","
                                + "\"message\":\"어디의 글자를 키울까요?\",\"choices\":["
                                + "{\"label\":\"휴대폰 설정에서 기본 글자 크기 변경\",\"value\":\"system\"},"
                                + "{\"label\":\"특정 앱 안에서만 변경\",\"value\":\"app\"}]}}"), null, "범위를 선택해 주세요."));
                    } catch (Exception e) { throw new AssertionError(e); }
                    measure(panel, 340, 650);
                    assertNull(panel.findViewById(R.id.task_flow_input));
                    assertFalse(find(panel, "입력하기").isEnabled());
                    RadioButton phone = (RadioButton) find(panel, "휴대폰 전체");
                    RadioButton app = (RadioButton) find(panel, "특정 앱 안에서만");
                    phone.performClick(); app.performClick();
                    assertFalse(phone.isChecked()); assertTrue(app.isChecked());
                    assertNull(responses.answer);
                    phone.performClick();
                    measure(panel, 340, 650);
                    assertNotNull(description(panel, "CLICK"));
                    assertNull(find(panel, "클릭 안내"));
                    assertReadable(find(panel, "다시 듣기"));
                    assertReadable(find(panel, "줄이기"));
                    assertReadable(phone); assertReadable(app);
                    save(panel, "floating-selection-" + scale);
                    // Also exercise a narrow screen while enlarged: actions must reflow.
                    measure(panel, 300, 650);
                    assertNotNull(description(panel, "CLICK"));
                    assertNull(find(panel, "클릭 안내"));
                    assertReadable(find(panel, "다시 듣기"));
                    assertReadable(find(panel, "줄이기"));
                    save(panel, "floating-selection-narrow-" + scale);
                    panel.findViewById(R.id.task_flow_replay).performClick();
                    assertEquals("어디의 글자를 키울까요?", responses.replay);
                    panel.setNarrationState(true, false);
                    find(panel, "음성 멈춤").performClick(); assertTrue(responses.stopped);
                    panel.setNarrationState(false, false);
                    find(panel, "직접 입력하기").performClick();
                    assertFalse(phone.isChecked()); assertFalse(app.isChecked());
                    assertFalse(find(panel, "입력하기").isShown());
                    assertTrue(find(panel, "수정한 내용 확인").isShown());
                    phone.performClick();
                    assertFalse(find(panel, "수정한 내용 확인").isShown());
                    assertTrue(find(panel, "입력하기").isShown());
                    find(panel, "입력하기").performClick(); assertEquals("system", responses.answer);
                    find(panel, "직접 입력하기").performClick();
                    input(panel).setText("화면 밝기를 낮춰줘");
                    find(panel, "수정한 내용 확인").performClick();
                    assertEquals("화면 밝기를 낮춰줘", responses.rephrased);
                    panel.progress("밝기 낮추기", null, "슬라이더를 왼쪽으로 움직여 주세요.");
                    panel.findViewById(R.id.task_flow_replay).performClick();
                    assertEquals("슬라이더를 왼쪽으로 움직여 주세요.", responses.replay);
                    assertEquals(1, count(panel, "다시 듣기"));
                }
                TrashDropView trash = new TrashDropView(activity);
                for (boolean active : new boolean[]{false, true}) {
                    trash.setActive(active); measure(trash, 112, 112);
                    save(trash, "floating-trash-" + active);
                }
            });
        }
    }

    @Test public void backgroundStatusNeverAppearsInAnyFloatingState() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, new Responses());
                activity.setContentView(panel);
                String loading = "이동한 화면을 확인하고 있어요…";
                panel.progress("글자 키우기", null, "디스플레이를 눌러주세요.");
                panel.setLoadingStatus(loading);
                assertNull(find(panel, loading));
                panel.goals("선택해 주세요.", new org.json.JSONArray());
                assertNull(find(panel, loading));
                panel.setLoadingStatus(loading);
                panel.setAutomationStatus("늦게 도착한 진행 상태");
                assertNull(find(panel, loading));
                assertNull(find(panel, "늦게 도착한 진행 상태"));
                panel.progress("글자 키우기", null, "디스플레이를 눌러주세요.");
                panel.setLoadingStatus(loading);
                panel.setMinimizedProgress(true);
                assertNull(find(panel, "클릭 · 펼치기"));
                assertNull(find(panel, loading));
                panel.setLoadingStatus(loading);
                assertNull(find(panel, loading));
                panel.setAutomationStatus("화면을 다시 확인 중");
                assertNull(find(panel, "화면을 다시 확인 중"));
                panel.setMinimizedProgress(false);
                assertNull(find(panel, loading));
                assertNotNull(description(panel, "CLICK"));
                assertTrue(find(panel, "디스플레이를 눌러주세요.").isShown());
                assertNull(find(panel, "현재 진행"));
                assertNoStatusViews(panel);
            });
        }
    }

    @Test public void attachedProgressAndTextInputHaveNoStatusPanelAfterLateUpdates() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            View[] surface = {null};
            for (boolean inputMode : new boolean[]{false, true}) {
                scenario.onActivity(activity -> {
                    FrameLayout host = new FrameLayout(activity);
                    host.setBackgroundColor(0xFFF4F7FC);
                    if (inputMode) {
                        ClickOverlayLayout inputPanel = new ClickOverlayLayout(activity);
                        inputPanel.openText();
                        input(inputPanel).setText("화면 밝기 낮춰줘");
                        inputPanel.setRequestStatus("요청을 처리하고 있어요", true);
                        inputPanel.setLoadingStatus("화면을 분석하고 있어요…");
                        surface[0] = inputPanel;
                    } else {
                        TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, new Responses());
                        panel.progress("글자 크기 키워줘", null, "글자 크기 슬라이더를 오른쪽으로 옮겨주세요.");
                        panel.setGuidanceProgress(2, 4);
                        panel.setMinimizedProgress(true);
                        panel.setLoadingStatus("화면을 분석하고 있어요…");
                        panel.setAutomationStatus("다음 화면을 확인 중");
                        panel.setMinimizedProgress(false);
                        surface[0] = panel;
                    }
                    int height = inputMode ? ((ClickOverlayLayout) surface[0]).getWindowHeight(ClickOverlayLayout.Mode.TEXT) : -2;
                    host.addView(surface[0], new FrameLayout.LayoutParams(Math.round(340 * activity.getResources().getDisplayMetrics().density), height, android.view.Gravity.CENTER));
                    activity.setContentView(host);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                android.os.SystemClock.sleep(200);
                scenario.onActivity(activity -> {
                    assertNoStatusViews(surface[0]);
                    for (String status : new String[]{"화면을 분석하고 있어요…", "다음 화면을 확인 중", "요청을 처리하고 있어요", "현재 진행", "2/4"})
                        assertNull(find(surface[0], status));
                    assertReadable(find(surface[0], "끝내기"));
                    if (inputMode) {
                        assertEquals("화면 밝기 낮춰줘", input(surface[0]).getText().toString());
                    } else {
                        assertReadable(find(surface[0], "글자 크기 슬라이더를 오른쪽으로 옮겨주세요."));
                        assertReadable(find(surface[0], "다시 듣기"));
                    }
                    save(surface[0], inputMode ? "floating-input-no-status" : "floating-progress-no-status");
                });
            }
        }
    }

    private static void assertNoStatusViews(View view) {
        assertFalse(view instanceof LoadingStatusView);
        assertFalse(view instanceof android.widget.ProgressBar);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertNoStatusViews(((ViewGroup) view).getChildAt(i));
    }

    private static View description(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = description(((ViewGroup) view).getChildAt(i), label); if (result != null) return result;
        }
        return null;
    }

    private static void measure(View view, int width, int height) {
        float density = view.getResources().getDisplayMetrics().density;
        if (view instanceof TaskFlowOverlayView) ((TaskFlowOverlayView) view).setAvailableHeight(Math.round(height * density));
        view.measure(View.MeasureSpec.makeMeasureSpec(Math.round(width * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(height * density), View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    private static void assertReadable(TextView view) {
        assertNotNull(view); assertTrue(view.getWidth() > 0); assertTrue(view.isShown());
        for (int i = 0; i < view.getLineCount(); i++) {
            assertEquals(0, view.getLayout().getEllipsisCount(i));
            assertTrue(view.getText() + " clips horizontally", view.getLayout().getLineMax(i)
                    <= view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight() + 1);
        }
        assertTrue(view.getText() + " clips vertically", view.getLayout().getHeight()
                <= view.getHeight() - view.getCompoundPaddingTop() - view.getCompoundPaddingBottom());
    }
    private static TextView find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = find(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private static EditText input(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText found = input(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private static int count(View view, String label) {
        int count = view instanceof TextView && label.contentEquals(((TextView) view).getText()) ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            count += count(((ViewGroup) view).getChildAt(i), label);
        return count;
    }
    private static void save(View view, String name) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(view.getContext().getCacheDir(), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) { throw new AssertionError(e); }
        bitmap.recycle();
    }
    private static final class Responses implements TaskFlowOverlayView.Listener {
        String answer, rephrased, replay; boolean stopped;
        public void chooseGoal(JSONObject goal) {} public void clarify(String choice) {}
        public void rephrase(String value) { rephrased = value; }
        public void result(String answer) {} public void adjust(String reason) {} public void control(String command) {}
        public void answerGoalInput(String value) { answer = value; }
        public void replayGuidance(String value) { replay = value; }
        public void stopGuidance() { stopped = true; }
    }
}
