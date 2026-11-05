package com.click.ui.overlay;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import com.click.domain.goal.GoalPreparationState;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34, application = android.app.Application.class)
public class FloatingHeaderConsistencyTest {
    @Test public void everyExpandedPanelKeepsTheReviewHeaderGeometryAtLargeFonts() throws Exception {
        for (float scale : new float[]{1f, 1.3f, 2f}) {
            Context app = RuntimeEnvironment.getApplication();
            Configuration config = new Configuration(app.getResources().getConfiguration());
            config.fontScale = scale;
            Context context = app.createConfigurationContext(config);
            int width = Math.round(340 * context.getResources().getDisplayMetrics().density);
            TaskFlowOverlayView flow = new TaskFlowOverlayView(context, mock(TaskFlowOverlayView.Listener.class));
            flow.preparation(new GoalPreparationState(new JSONObject("{\"version\":\"goal-inputs.v1\",\"id\":\"ready\",\"source_text\":\"글자 크기 키워줘\",\"status\":\"ready\",\"values\":{},\"missing_fields\":[],\"graph_reference\":{}}"),
                    new JSONObject("{\"goal_type\":\"settings.font_size.increase\"}"), "글자를 키울게요."));
            Rect reference = geometry(flow, width);
            android.view.ViewGroup card = flow.findViewWithTag("review_summary_card");
            View icon = card.findViewWithTag("review_summary_icon");
            View label = card.findViewWithTag("review_summary_text");
            assertEquals(icon.getTop() + icon.getHeight() / 2f,
                    label.getTop() + label.getHeight() / 2f, 1f);
            flow.progress("글자 크기", null, "디스플레이를 눌러주세요.", false);
            assertEquals(reference, geometry(flow, width));
            flow.result(new JSONObject(), true);
            assertEquals(reference, geometry(flow, width));
            flow.observationRecovery("화면을 확인해 주세요.", () -> {});
            assertEquals(reference, geometry(flow, width));
            flow.completed("완료했어요.", () -> {});
            assertEquals(reference, geometry(flow, width));
            ClickOverlayLayout input = new ClickOverlayLayout(context);
            for (ClickOverlayLayout.Mode mode : new ClickOverlayLayout.Mode[]{ClickOverlayLayout.Mode.MENU,
                    ClickOverlayLayout.Mode.VOICE, ClickOverlayLayout.Mode.TEXT}) {
                if (mode == ClickOverlayLayout.Mode.MENU) input.openMenu();
                if (mode == ClickOverlayLayout.Mode.VOICE) input.openVoice();
                if (mode == ClickOverlayLayout.Mode.TEXT) input.openText();
                assertEquals(flow.getWindowWidth(width + 100), input.getWindowWidth(mode, width + 100));
                assertEquals(reference, geometry(input, width));
                FloatingPanelHeader header = input.findViewWithTag("floating_header");
                Rect close = new Rect(); header.collapse.getDrawingRect(close);
                input.offsetDescendantRectToMyCoords(header.collapse, close);
                int headerLeft = header.getLeft(), headerTop = header.getTop();
                assertEquals(close.exactCenterX(), input.getClickCenterX(mode, width), 1f);
                assertEquals(close.exactCenterY(), input.getClickCenterY(mode), 1f);
                assertEquals(headerLeft, header.getLeft());
                assertEquals(headerTop, header.getTop());
                assertTrue(header.collapse.isClickable());
            }
        }
    }

    private Rect geometry(View panel, int width) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        FloatingPanelHeader header = panel.findViewWithTag("floating_header");
        assertNotNull(header);
        View logo = header.findViewWithTag("floating_logo");
        Rect logoBounds = new Rect(); logo.getDrawingRect(logoBounds);
        header.offsetDescendantRectToMyCoords(logo, logoBounds);
        assertEquals(0, logoBounds.left);
        assertNull(findDescription(header, "이전 단계"));
        assertTrue(header.collapse.getMeasuredWidth() > 0);
        assertTrue(header.replay.getMeasuredWidth() > header.collapse.getMeasuredWidth());
        assertEquals(header.replay.getMeasuredHeight(), header.collapse.getMeasuredHeight());
        return new Rect(header.getWidth(), header.getHeight(), header.divider.getTop(), header.divider.getHeight());
    }
    private View findDescription(View view, String description) {
        if (description.equals(view.getContentDescription())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
