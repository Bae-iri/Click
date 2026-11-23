package com.click.ui.overlay;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.click.R;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FloatingPanelStateTest {
    @Test public void transparencyUpdatesOpenPanelsAndSurvivesCollapseAndReattachment() {
        try (var controller = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup()) {
            var activity = controller.get();
            var host = new android.widget.FrameLayout(activity);
            activity.setContentView(host);
            var input = new ClickOverlayLayout(activity);
            var task = new TaskFlowOverlayView(activity, mock(TaskFlowOverlayView.Listener.class));
            host.addView(input);
            host.addView(task);
            input.openMenu();
            task.progress(null, null, "디스플레이를 눌러주세요.", false);
            FloatingTransparency.save(activity, 40);
            assertEquals(153, input.getChildAt(0).getBackground().getAlpha());
            assertEquals(153, task.getBackground().getAlpha());

            task.setMinimizedProgress(true);
            assertEquals(153, task.getBackground().getAlpha());
            assertEquals(1f, compact(task).getAlpha(), 0f);
            host.removeView(task);
            FloatingTransparency.save(activity, 0);
            host.addView(task);
            assertEquals(255, task.getBackground().getAlpha());
            assertTrue(task.isMinimizedProgress());
            task.setMinimizedProgress(false);
            assertEquals(255, task.getBackground().getAlpha());
        }
    }

    @Test public void minimizedPanelKeepsItsChoiceAndOnlyShowsTheLatestContentWhenOpened() throws Exception {
        var listener = mock(TaskFlowOverlayView.Listener.class);
        var panel = new TaskFlowOverlayView(RuntimeEnvironment.getApplication(), listener);
        panel.requestConfirmation("글자 크기 키워줘", "다시 입력하기", () -> {});
        panel.setMinimizedProgress(true);
        int compactWidth = panel.getWindowWidth(1080);
        assertCompact(panel, compactWidth);
        panel.progress(null, null, "디스플레이를 눌러주세요.", false);
        panel.setGuidanceTarget("디스플레이");
        assertCompact(panel, compactWidth);
        assertEquals("디스플레이를 눌러주세요.", compact(panel).getText().toString());
        panel.waitingProgress("다음 항목을 찾고 있어요.");
        panel.setAutomaticProgress(true);
        assertCompact(panel, compactWidth);
        panel.observationRecovery("항목을 확인하지 못했어요.", () -> {});
        assertCompact(panel, compactWidth);
        panel.result(new JSONObject(), true);
        assertCompact(panel, compactWidth);
        View answer = panel.findViewById(R.id.task_flow_result_satisfied);
        assertFalse(visible(answer));
        TextView bubble = compact(panel);
        assertEquals("이제 글자가 보기 편한가요?", bubble.getText().toString());
        bubble.performClick(); verify(listener).expand();
        panel.setMinimizedProgress(false);
        measure(panel);
        assertTrue(visible(answer));
        assertFalse(visible(panel.findViewById(R.id.task_flow_begin)));
        assertNull(text(panel, "다시 시도하기"));
        answer.performClick(); verify(listener).result("satisfied");
    }

    @Test public void contentRefreshCannotOpenAMinimizedPanelEvenWithoutAServiceLayoutCall() {
        var panel = new TaskFlowOverlayView(RuntimeEnvironment.getApplication(), mock(TaskFlowOverlayView.Listener.class));
        panel.progress(null, null, "첫 번째 안내", false);
        panel.setMinimizedProgress(true);
        panel.paused();
        assertTrue(panel.isMinimizedProgress());
        measure(panel);
        assertFalse(visible(text(panel, "이어서 진행")));
        panel.setMinimizedProgress(false);
        measure(panel);
        assertTrue(visible(text(panel, "이어서 진행")));
    }

    @Test public void targetCardDoesNotActAsTheMinimizeButton() {
        var listener = mock(TaskFlowOverlayView.Listener.class);
        var panel = new TaskFlowOverlayView(RuntimeEnvironment.getApplication(), listener);
        panel.progress(null, null, "디스플레이를 눌러주세요.", false);
        panel.setGuidanceTarget("디스플레이");
        View target = description(panel, "디스플레이");
        assertNotNull(target); assertFalse(target.performClick());
        verify(listener, never()).collapse();
        panel.findViewById(R.id.task_flow_collapse).performClick();
        verify(listener).collapse();
    }

    @Test public void targetHelpRespectsTheExistingChoiceWithoutAForcedCollapseAndOpen() {
        var panel = new TaskFlowOverlayView(RuntimeEnvironment.getApplication(), mock(TaskFlowOverlayView.Listener.class));
        panel.targetHelp("디스플레이", () -> {});
        assertFalse(panel.isMinimizedProgress()); assertTrue(panel.hasOpenedTargetHelp());
        panel.setMinimizedProgress(true);
        panel.targetHelp("글자 크기", () -> {});
        assertTrue(panel.isMinimizedProgress()); assertFalse(panel.hasOpenedTargetHelp());
        panel.setMinimizedProgress(false);
        assertTrue(panel.hasOpenedTargetHelp());
        assertTrue(panel.guidanceMessage().contains("글자 크기"));
    }

    private static void assertCompact(TaskFlowOverlayView panel, int width) {
        assertTrue("Rendering a new state must not expand the panel", panel.isMinimizedProgress());
        measure(panel);
        assertEquals(width, panel.getWindowWidth(1080));
        assertFalse(visible(panel.findViewById(R.id.task_flow_collapse)));
        assertFalse(visible(panel.findViewById(R.id.task_flow_begin)));
    }
    private static void measure(View panel) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
    }
    private static boolean visible(View view) {
        if (view == null) return false;
        while (view != null) {
            if (view.getVisibility() != View.VISIBLE) return false;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return true;
    }
    private static View description(View view, String value) {
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), value);
            if (found != null) return found;
        }
        return null;
    }
    private static TextView compact(View view) {
        if (view instanceof TextView && view.getContentDescription() != null
                && view.getContentDescription().toString().endsWith("눌러서 안내 펼치기")) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = compact(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private static View text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = text(((ViewGroup) view).getChildAt(i), value);
            if (found != null) return found;
        }
        return null;
    }
}
