package com.click.ui.overlay;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AdjustmentFlowUiTest {
    @Test public void settingsShareProgressAndResultActionsAtBothFontScales() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            for (float scale : new float[]{1f, 1.5f, 2f}) for (String task : new String[]{"글자 크기", "화면 밝기", "미디어 음량"}) {
                TaskFlowOverlayView[] panel = {null}; Responses response = new Responses();
                scenario.onActivity(activity -> {
                    Configuration config = new Configuration(activity.getResources().getConfiguration()); config.fontScale = scale;
                    Context context = activity.createConfigurationContext(config);
                    panel[0] = new TaskFlowOverlayView(context, response);
                    panel[0].setAvailableHeight(Math.round(540 * context.getResources().getDisplayMetrics().density));
                    panel[0].progress(task, null, "조절 막대를 오른쪽으로 조금 움직여주세요.", false);
                    panel[0].setGuidanceProgress(3, 3);
                    FrameLayout host = new FrameLayout(activity); host.setBackgroundColor(0xFFF4F7FC);
                    host.addView(panel[0], new FrameLayout.LayoutParams(panel[0].getWindowWidth(context.getResources().getDisplayMetrics().widthPixels), -2, android.view.Gravity.CENTER));
                    activity.setContentView(host);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertNull(find(panel[0], "결과 확인하기")); assertNull(find(panel[0], "다 했어요"));
                    assertTrue(find(panel[0], "조절 막대를 오른쪽으로 조금 움직여주세요.").isShown());
                    panel[0].progress(task, null, "조절을 마치셨으면 알려주세요.", true);
                    find(panel[0], "다 했어요").performClick(); assertEquals("request_result", response.command);
                    try { panel[0].result(new JSONObject().put("message", task + " 조절이 편하신가요?"), "글자 크기".equals(task)); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertEquals("원하시는 대로 되었나요?", panel[0].phaseLabel().replace('\n', ' '));
                    assertTrue(find(panel[0], task + " 조절이 편하신가요?").isShown());
                    assertNull(find(panel[0], "글로 보기"));
                    assertNull(find(panel[0], "답하지 않으면 완료로 기록하지 않아요."));
                    String positive = "글자 크기".equals(task) ? "네, 글씨가 잘 보여요" : "네, 좋아요";
                    String help = "글자 크기".equals(task) ? "더 커졌으면 좋겠어" : "조금 더 도와주세요";
                    for (String label : new String[]{positive, help, "줄이기", "끝내기"}) {
                        TextView button = find(panel[0], label); assertNotNull(button); assertTrue(button.isShown());
                        android.graphics.Rect bounds = new android.graphics.Rect(); button.getDrawingRect(bounds);
                        panel[0].offsetDescendantRectToMyCoords(button, bounds);
                        assertTrue(label + " must fit", bounds.top >= 0 && bounds.bottom <= panel[0].getHeight());
                    }
                    find(panel[0], positive).performClick(); assertEquals("satisfied", response.answer);
                    find(panel[0], help).performClick(); assertEquals("needs_help", response.answer);
                    Bitmap bitmap = Bitmap.createBitmap(panel[0].getWidth(), panel[0].getHeight(), Bitmap.Config.ARGB_8888);
                    panel[0].draw(new Canvas(bitmap));
                    try (FileOutputStream out = new FileOutputStream(new File(activity.getCacheDir(), "adjustment-" + task + "-" + scale + ".png"))) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (Exception error) { throw new AssertionError(error); }
                    bitmap.recycle();
                });
            }
        }
    }
    private static TextView find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            TextView result = find(((ViewGroup)view).getChildAt(i), label); if (result != null) return result;
        }
        return null;
    }
    private static class Responses implements TaskFlowOverlayView.Listener {
        String command, answer;
        public void chooseGoal(JSONObject goal) {} public void clarify(String choice) {} public void rephrase(String text) {}
        public void result(String answer) { this.answer = answer; } public void adjust(String reason) {}
        public void control(String command) { this.command = command; }
    }
}
