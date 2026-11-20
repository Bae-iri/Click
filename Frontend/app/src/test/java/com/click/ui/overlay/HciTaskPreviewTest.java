package com.click.ui.overlay;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Native rendering of the actual task panel; no device, account or model calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class,
        qualifiers = "ko-rKR-w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HciTaskPreviewTest {
    @Test public void normalTextOnSmallScreen() throws Exception { renderStates(1f); }
    @Test public void largeTextOnSmallScreen() throws Exception { renderStates(1.5f); }
    @Test public void largestTextOnSmallScreen() throws Exception { renderStates(2f); }

    private void renderStates(float fontScale) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        try {
            render("tap", fontScale, proposal("tap", true,
                    "클릭이 ‘검색’ 항목을 대신 눌러요. 아래 버튼을 눌러 진행해 주세요."),
                    "클릭이 대신 누르기", true);
            render("manual", fontScale, proposal("tap", false,
                    "현재 앱에서 강조한 ‘검색’ 항목을 직접 눌러주세요."), null, true);
            render("search", fontScale, proposal("set_text", true,
                    "검색어를 확인한 뒤 검색창에 입력해 주세요.")
                    .put("suggested_query", "서울행 기차"), "검색창에 입력", true);
            render("protected", fontScale, state("protected",
                    "입력·인증은 현재 앱에서 직접 진행해 주세요. 마치면 안내를 이어갈 수 있어요.")
                    .put("available_tools", new JSONArray()).put("can_read_labels", false),
                    "입력 후 안내 이어가기", true);
            render("planning", fontScale, state("planning", "다음 단계를 찾고 있어요.")
                    .put("verifying", true), null, true);
            render("verifying", fontScale, proposal("tap", true,
                    "동작이 반영됐는지 확인하고 있어요.")
                    .put("status", "verifying").put("verifying", true), null, true);
            render("result", fontScale, state("confirm_result",
                    "요청하신 결과가 화면에 반영된 것을 확인했어요. 원하신 결과가 맞나요?"),
                    "결과가 맞아요 · 끝내기", false);
            render("recovery", fontScale, state("needs_input",
                    "안내할 항목을 찾지 못했어요. 화면을 다시 읽거나 다른 방법으로 이어갈 수 있어요.")
                    .put("can_read_labels", true), null, true);
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    private JSONObject state(String status, String message) throws Exception {
        return new JSONObject().put("status", status).put("message", message)
                .put("available_tools", new JSONArray().put("back").put("open_app"))
                .put("can_read_labels", false);
    }

    private JSONObject proposal(String tool, boolean executable, String message) throws Exception {
        return state("proposed", message).put("target_label", "검색")
                .put("execute_allowed", executable)
                .put("action", new JSONObject().put("id", "preview-action").put("tool", tool));
    }

    private void render(String name, float fontScale, JSONObject result,
            String primaryLabel, boolean hasOngoingControls) throws Exception {
        var activityController=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup();
        var context = activityController.get();
        var panel = new TaskFlowOverlayView(context, mock(TaskFlowOverlayView.Listener.class));
        panel.appTask(result, value -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {});
        context.setContentView(panel);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = panel.getWindowWidth(screenWidth);
        int availableHeight = Math.round(context.getResources().getDisplayMetrics().heightPixels * .78f);
        panel.setAvailableHeight(availableHeight);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, width, panel.getMeasuredHeight());
        panel.getViewTreeObserver().dispatchOnPreDraw();
        finishAnimations(panel);

        String caseName = name + "-font-" + fontScale;
        assertTrue(caseName + ": panel needs visible content", panel.getHeight() > 0);
        assertTrue(caseName + ": panel exceeds small screen budget", panel.getHeight() <= availableHeight);
        if (primaryLabel != null) {
            TextView primary = find(panel, primaryLabel);
            assertNotNull(caseName + ": missing " + primaryLabel, primary);
            assertTrue(caseName + ": primary action hidden", visible(primary));
        }
        if (hasOngoingControls) {
            assertNotNull(caseName + ": pause unavailable", find(panel, "일시정지"));
            assertTrue(caseName + ": pause hidden", visible(find(panel, "일시정지")));
            assertNotNull(caseName + ": stop unavailable", find(panel, "안내 끝내기"));
            assertTrue(caseName + ": stop hidden", visible(find(panel, "안내 끝내기")));
        } else {
            assertNotNull(caseName + ": no negative result choice", find(panel, "아직 안 됐어요"));
            assertTrue(caseName + ": negative result choice hidden", visible(find(panel, "아직 안 됐어요")));
            assertNotNull(caseName + ": no independent exit", find(panel, "안내만 끝내기"));
        }

        Bitmap bitmap = Bitmap.createBitmap(width, panel.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            panel.draw(canvas);
            Path path = Path.of(System.getProperty("click.contractOutput"), "hci-task-ui", caseName + ".png");
            Files.createDirectories(path.getParent());
            try (var output = Files.newOutputStream(path)) {
                assertTrue(caseName + ": PNG not written", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            bitmap.recycle();
        }
        verifyControls(panel, panel, caseName);
        activityController.pause().stop().destroy();
    }

    private void finishAnimations(View view) {
        com.click.ui.common.ClickUiSpeed.finishReveal(view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)finishAnimations(((ViewGroup)view).getChildAt(i));
    }

    private void verifyControls(TaskFlowOverlayView panel, View view, String caseName) {
        if (!visible(view)) return;
        if (view instanceof Button) {
            Button button = (Button) view;
            String label = caseName + ": " + button.getText();
            float density = button.getResources().getDisplayMetrics().density;
            assertTrue(label + " touch height below 48dp", button.getHeight() >= Math.round(48 * density));
            assertTrue(label + " touch width below 48dp", button.getWidth() >= Math.round(48 * density));
            Layout layout = button.getLayout();
            assertNotNull(label + " has no rendered text", layout);
            assertTrue(label + " has no text lines", layout.getLineCount() > 0);
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals(label + " is ellipsized", 0, layout.getEllipsisCount(line));
                assertTrue(label + " text exceeds its width",
                        layout.getLineWidth(line) <= button.getWidth()
                                - button.getCompoundPaddingLeft() - button.getCompoundPaddingRight() + 2);
            }
            assertTrue(label + " text clipped vertically",
                    layout.getLineBottom(layout.getLineCount() - 1) <= button.getHeight()
                            - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom() + 2);
            Rect bounds = new Rect(0, 0, button.getWidth(), button.getHeight());
            panel.offsetDescendantRectToMyCoords(button, bounds);
            assertTrue(label + " extends outside panel", bounds.left >= 0 && bounds.top >= 0
                    && bounds.right <= panel.getWidth() && bounds.bottom <= panel.getHeight());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) verifyControls(panel, group.getChildAt(i), caseName);
        }
    }

    private TextView find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean visible(View view) {
        if (view == null) return false;
        while (view != null) {
            if (view.getVisibility() != View.VISIBLE) return false;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return true;
    }
}
