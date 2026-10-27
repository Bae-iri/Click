package com.click.ui.overlay;

import android.app.Application;
import android.graphics.Canvas;
import android.graphics.Paint;
import com.click.data.remote.AgentTurnContract;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class GuideOverlayViewTest {
    @Test public void serverDirectionControlsBothVisibleTextAndArrow() throws Exception {
        for (String direction : new String[]{"left", "right"}) {
            GuideOverlayView view = view();
            AgentTurnContract.GuideCommand guide = command(direction);
            view.showTarget(new int[]{100, 200, 900, 300}, guide.actionType, GuideDragDirection.resolve(guide.renderPolicy, null));
            Canvas canvas = mock(Canvas.class);
            view.onDraw(canvas);
            ArgumentCaptor<Float> start = ArgumentCaptor.forClass(Float.class);
            ArgumentCaptor<Float> end = ArgumentCaptor.forClass(Float.class);
            verify(canvas).drawLine(start.capture(), anyFloat(), end.capture(), anyFloat(), any(Paint.class));
            assertEquals("right".equals(direction), end.getValue() > start.getValue());
            String expected = "left".equals(direction) ? "←  왼쪽으로 움직이세요" : "오른쪽으로 움직이세요  →";
            verify(canvas).drawText(eq(expected), anyFloat(), anyFloat(), any(Paint.class));
            assertEquals(expected, view.getContentDescription());
        }
    }

    @Test public void missingOrInvalidDirectionClearsPreviousArrowInsteadOfDefaultingRight() throws Exception {
        for (String direction : new String[]{null, "up", "invalid"}) {
            GuideOverlayView view = view();
            view.showTarget(new int[]{100, 200, 900, 300}, "drag", GuideDragDirection.RIGHT);
            AgentTurnContract.GuideCommand guide = command(direction);
            view.showTarget(new int[]{100, 200, 900, 300}, guide.actionType, GuideDragDirection.resolve(guide.renderPolicy, null));
            Canvas canvas = mock(Canvas.class);
            view.onDraw(canvas);
            verify(canvas, never()).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
            verify(canvas).drawText(eq("안내에 따라 움직여 주세요"), anyFloat(), anyFloat(), any(Paint.class));
        }
    }

    private GuideOverlayView view() {
        GuideOverlayView view = new GuideOverlayView(RuntimeEnvironment.getApplication());
        view.layout(0, 0, 1080, 2400);
        return view;
    }

    private AgentTurnContract.GuideCommand command(String direction) throws Exception {
        JSONObject policy = new JSONObject();
        if (direction != null) policy.put("direction", direction);
        JSONObject guide = new JSONObject().put("command_type", "highlight").put("action_type", "drag")
                .put("render_policy", policy);
        return new AgentTurnContract.Response(new JSONObject().put("request_id", "r").put("session_id", "s")
                .put("task_status", "IN_PROGRESS").put("guide_command", guide).toString()).guideCommand;
    }
}
