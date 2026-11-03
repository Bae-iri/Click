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
import com.click.domain.goal.GoalPreparationState;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingReferenceFlowTest {
    private static GoalPreparationState question() throws Exception {
        return new GoalPreparationState(new JSONObject("{\"version\":\"goal-inputs.v1\",\"id\":\"scope\",\"source_text\":\"글자 크기 키워줘\",\"status\":\"needs_input\",\"values\":{},\"question\":{\"id\":\"scope\",\"kind\":\"choice\",\"message\":\"어디의 글자를 키울까요?\",\"choices\":[{\"label\":\"휴대폰 전체\",\"value\":\"system\"},{\"label\":\"특정 앱 안에서만\",\"value\":\"app\"}]}}"), null, "범위를 선택해 주세요.");
    }
    private static GoalPreparationState ready() throws Exception {
        return new GoalPreparationState(new JSONObject("{\"version\":\"goal-inputs.v1\",\"id\":\"ready\",\"source_text\":\"글자 크기 키워줘\",\"status\":\"ready\",\"values\":{},\"missing_fields\":[],\"graph_reference\":{}}"),
                new JSONObject("{\"goal_type\":\"settings.font_size.increase\"}"), "글자를 키울게요.");
    }

    @Test public void reviewKeepsSelectionConfirmationAndEditingSeparateAtLargeFonts() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            for (float scale : new float[]{1f, 1.5f, 2f}) {
                scenario.onActivity(activity -> {
                    try {
                        Configuration config = new Configuration(activity.getResources().getConfiguration()); config.fontScale = scale;
                        Context context = activity.createConfigurationContext(config);
                        Replies replies = new Replies();
                        TaskFlowOverlayView panel = new TaskFlowOverlayView(context, replies);
                        host(activity, panel, 340);
                        panel.preparation(question()); measure(panel, 340, 650);
                        assertFalse(find(panel, "다음 단계").isEnabled());
                        RadioButton phone = (RadioButton) find(panel, "휴대폰 전체");
                        RadioButton app = (RadioButton) find(panel, "특정 앱 안에서만");
                        phone.performClick(); app.performClick();
                        assertFalse(phone.isChecked()); assertTrue(app.isChecked()); assertNull(replies.answer);
                        find(panel, "다음 단계").performClick(); assertEquals("app", replies.answer);
                        readable(phone); readable(app);
                        save(panel, "reference-question-" + scale);
                        find(panel, "직접 입력하기").performClick();
                        assertFalse(app.isChecked()); assertFalse(find(panel, "다음 단계").isShown());
                        ((EditText) description(panel, "요청 내용 수정")).setText("밝기를 높여줘");
                        find(panel, "수정한 요청 확인").performClick(); assertEquals("밝기를 높여줘", replies.rephrased);
                        panel.preparation(ready()); measure(panel, 340, 650);
                        assertNull(find(panel, "1/2")); assertNull(find(panel, "2/2"));
                        readable(find(panel, "안내 시작하기")); readable(find(panel, "내용 수정"));
                        assertTrue(top(panel, find(panel, "내용 수정")) > top(panel, find(panel, "안내 시작하기")));
                        assertFalse(replies.confirmed);
                        save(panel, "reference-confirm-" + scale);
                        find(panel, "안내 시작하기").performClick(); assertTrue(replies.confirmed);
                        find(panel, "내용 수정").performClick();
                        assertFalse(find(panel, "안내 시작하기").isShown());
                        assertTrue(find(panel, "수정한 요청 확인").isShown());
                    } catch (Exception error) { throw new AssertionError(error); }
                });
            }
        }
    }

    @Test public void executionUsesRealProgressAndTargetThenClearsThemForReview() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Replies replies = new Replies(); TaskFlowOverlayView panel = new TaskFlowOverlayView(activity, replies);
                    host(activity, panel, 340);
                    panel.progress("글자 크기", null, "디스플레이 메뉴를 눌러주세요.", false);
                    panel.setGuidanceProgress(2, 3); panel.setGuidanceTarget("디스플레이"); measure(panel, 340, 650);
                    readable(find(panel, "2/3")); readable(find(panel, "디스플레이"));
                    save(panel, "reference-guidance");
                    description(panel, "디스플레이. 안내를 접고 화면에서 찾기").performClick(); assertTrue(replies.collapsed);
                    panel.setMinimizedProgress(true); assertFalse(find(panel, "2/3").isShown());
                    panel.setMinimizedProgress(false); assertTrue(find(panel, "2/3").isShown());
                    panel.setGuidanceProgress(0, 0); assertFalse(find(panel, "2/3").isShown());
                    panel.preparation(ready()); panel.setGuidanceProgress(2, 3);
                    assertNull(find(panel, "2/3")); assertNull(find(panel, "디스플레이"));
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }
    }

    @Test public void inputCardsOpenTheExistingVoiceAndTextModes() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                ClickOverlayLayout panel = new ClickOverlayLayout(activity);
                host(activity, panel, 320); panel.openMenu();
                int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(panel.getWindowHeight(ClickOverlayLayout.Mode.MENU), View.MeasureSpec.EXACTLY));
                panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight()); save(panel, "reference-input");
                ((View) find(panel, "말로 입력").getParent()).performClick();
                assertEquals(ClickOverlayLayout.Mode.VOICE, panel.getMode()); assertFalse(panel.isVoiceInputActive());
                panel.openMenu(); ((View) find(panel, "글로 입력").getParent()).performClick();
                assertEquals(ClickOverlayLayout.Mode.TEXT, panel.getMode());
            });
        }
    }
    @Test public void attachedPanelsRenderAfterLayoutAtNormalAndLargeFonts() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            for (float scale : new float[]{1f, 2f}) for (int stage = 0; stage < 3; stage++) {
                final int screen = stage; final TaskFlowOverlayView[] shown = {null};
                scenario.onActivity(activity -> {
                    try {
                        Configuration config = new Configuration(activity.getResources().getConfiguration()); config.fontScale = scale;
                        Context context = activity.createConfigurationContext(config);
                        TaskFlowOverlayView panel = new TaskFlowOverlayView(context, new Replies()); shown[0] = panel;
                        panel.setAvailableHeight(Math.round(650 * context.getResources().getDisplayMetrics().density));
                        if (screen == 0) { panel.preparation(question()); find(panel, "휴대폰 전체").performClick(); }
                        else if (screen == 1) panel.preparation(ready());
                        else { panel.progress("글자 크기", null, "디스플레이 메뉴를 눌러주세요.", false); panel.setGuidanceProgress(2, 3); panel.setGuidanceTarget("디스플레이"); }
                        host(activity, panel, 340);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    readable(find(shown[0], "다시 듣기")); readable(find(shown[0], "줄이기"));
                    if (screen == 0) readable(find(shown[0], "휴대폰 전체"));
                    if (screen == 1) readable(find(shown[0], "안내 시작하기"));
                    save(shown[0], "reference-attached-" + screen + "-" + scale);
                });
            }
        }
    }
    private static void host(HomeActivity activity, View panel, int width) {
        FrameLayout host = new FrameLayout(activity); host.setBackgroundColor(0xFFEAF0FA);
        host.addView(panel, new FrameLayout.LayoutParams(Math.round(width * panel.getResources().getDisplayMetrics().density), -2, android.view.Gravity.CENTER));
        activity.setContentView(host);
    }
    private static void measure(TaskFlowOverlayView panel, int width, int height) {
        float d = panel.getResources().getDisplayMetrics().density;
        panel.setAvailableHeight(Math.round(height * d));
        panel.measure(View.MeasureSpec.makeMeasureSpec(Math.round(width * d), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Math.round(height * d), View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
    }
    private static int top(ViewGroup root, View view) {
        android.graphics.Rect bounds = new android.graphics.Rect(); view.getDrawingRect(bounds); root.offsetDescendantRectToMyCoords(view, bounds); return bounds.top;
    }
    private static void readable(TextView view) {
        assertNotNull(view); assertTrue(view.isShown()); assertTrue(view.getWidth() > 0);
        assertTrue(view.getText() + " clips vertically", view.getLayout().getHeight() <= view.getHeight() - view.getCompoundPaddingTop() - view.getCompoundPaddingBottom());
        for (int line = 0; line < view.getLineCount(); line++) {
            assertEquals(0, view.getLayout().getEllipsisCount(line));
            assertTrue(view.getText() + " clips horizontally", view.getLayout().getLineMax(line) <= view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight() + 1);
        }
    }
    private static TextView find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = find(((ViewGroup) view).getChildAt(i), label); if (found != null) return found; } return null;
    }
    private static View description(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = description(((ViewGroup) view).getChildAt(i), label); if (found != null) return found; } return null;
    }
    private static void save(View view, String name) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } catch (Exception error) { throw new AssertionError(error); } bitmap.recycle();
    }
    private static final class Replies implements TaskFlowOverlayView.Listener {
        String answer, rephrased; boolean confirmed, collapsed;
        public void chooseGoal(JSONObject goal) {} public void clarify(String choice) {} public void result(String answer) {} public void adjust(String reason) {} public void control(String command) {}
        public void rephrase(String value) { rephrased = value; } public void answerGoalInput(String value) { answer = value; }
        public void confirmRequirements() { confirmed = true; } public void collapse() { collapsed = true; }
    }
}
