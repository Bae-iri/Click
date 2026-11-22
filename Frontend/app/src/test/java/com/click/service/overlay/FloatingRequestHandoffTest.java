package com.click.service.overlay;

import android.app.Application;
import android.view.View;
import android.view.WindowManager;
import com.click.data.local.AuthTokenStore;
import com.click.data.remote.VoiceQueueGateway;
import com.click.domain.goal.RequirementReview;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.TaskFlowOverlayView;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class FloatingRequestHandoffTest {
    private ClickOverlayService service;
    private ClickOverlayLayout input;
    private TaskFlowOverlayView panel;
    private org.mockito.MockedConstruction<AuthTokenStore> auth;

    @Before public void setup() {
        auth = mockConstruction(AuthTokenStore.class);
        service = Robolectric.buildService(ClickOverlayService.class).get();
        input = new ClickOverlayLayout(service);
        panel = new TaskFlowOverlayView(service, mock(TaskFlowOverlayView.Listener.class));
        ReflectionHelpers.setField(service, "windowManager", mock(WindowManager.class));
        ReflectionHelpers.setField(service, "windowParams", new WindowManager.LayoutParams(64, 64));
        ReflectionHelpers.setField(service, "overlayView", input);
        ReflectionHelpers.setField(service, "taskFlowView", panel);
        ReflectionHelpers.setField(service, "taskFlowParams", new WindowManager.LayoutParams());
        input.setCallback(service);
    }

    @After public void cleanup() { auth.close(); }

    @Test public void preparationRevealsTheReusedPanelAfterInputWasClosed() throws Exception {
        input.openText();
        assertEquals(View.GONE, panel.getVisibility());
        input.collapse();
        // Reproduce a response received while the reused window was still hidden.
        panel.setVisibility(View.GONE);
        panel.requirements(new RequirementReview("글자 크기를 키워줘"), true);
        place();
        assertEquals(View.VISIBLE, panel.getVisibility());
        assertEquals(View.GONE, input.getVisibility());
        assertTrue(panel.findViewWithTag("review_summary_text") instanceof android.widget.TextView);
    }

    @Test public void queuedRequestShowsItsProgressNextToTheIconAndCanBeOpened() throws Exception {
        {
            VoiceQueueGateway queue = mock(VoiceQueueGateway.class);
            ReflectionHelpers.setField(service, "queueGateway", queue);
            ReflectionHelpers.setField(service, "taskFlowCollapsed", true);
            panel.setVisibility(View.GONE);
            input.openText();
            Method enqueue = ClickOverlayService.class.getDeclaredMethod("enqueueConfirmedRequest", String.class, String.class);
            enqueue.setAccessible(true);
            enqueue.invoke(service, "화면 밝기를 높여줘", null);
            assertFalse((boolean) ReflectionHelpers.getField(service, "taskFlowCollapsed"));
            assertEquals(ClickOverlayLayout.Mode.COLLAPSED, input.getMode());
            assertTrue(panel.isPassiveProgress());
            assertBubble("부탁하신 내용을 확인하고 있어요.");
            assertTrue(service.onOpenRequested());
            assertFalse(panel.isMinimizedProgress());
            assertEquals(View.VISIBLE, panel.getVisibility());
            assertEquals(View.GONE, input.getVisibility());
            verify(queue).enqueue(anyString(), eq("화면 밝기를 높여줘"), any());
        }
    }

    @Test public void backgroundPreparationDoesNotCloseAnotherInputDraft() throws Exception {
        input.openText();
        input.setDraftText("다음에 물어볼 내용");
        panel.requirements(new RequirementReview("글자 크기를 키워줘"), true);
        place();
        assertEquals(ClickOverlayLayout.Mode.TEXT, input.getMode());
        assertEquals("다음에 물어볼 내용", input.getDraftText());
        assertEquals(View.VISIBLE, input.getVisibility());
        assertEquals(View.GONE, panel.getVisibility());
    }

    @Test public void waitingAndAutomaticStepsUpdateTheSameBubbleAndKeepTheUsersPanelChoice() throws Exception {
        panel.waitingProgress("캘린더 앱을 열고 있어요.");
        panel.setAutomaticProgress(true);
        place();
        assertBubble("캘린더 앱을 열고 있어요.");
        android.widget.TextView caption = panel.findViewById(com.click.R.id.task_flow_compact_message);
        panel.appTask(new org.json.JSONObject().put("status", "planning").put("message", "요청하신 결과가 나왔는지 확인하고 있어요."),
                value -> fail(), () -> fail(), () -> fail(), () -> fail(), () -> fail());
        place();
        assertSame(caption, panel.findViewById(com.click.R.id.task_flow_compact_message));
        assertBubble("요청하신 결과가 나왔는지 확인하고 있어요.");

        assertTrue(service.onOpenRequested());
        panel.actionRequired("날짜를 골라 주세요.");
        place();
        panel.waitingProgress("선택한 날짜를 확인하고 있어요.");
        place();
        assertFalse(panel.isMinimizedProgress());
        assertEquals(View.VISIBLE, panel.getVisibility());
        assertEquals(View.GONE, input.getVisibility());

        ReflectionHelpers.callInstanceMethod(service, "collapseTaskFlow");
        assertBubble("선택한 날짜를 확인하고 있어요.");
        panel.result(new org.json.JSONObject().put("message", "원하신 결과인가요?"));
        place();
        assertBubble(panel.guidanceMessage());
        input.openText();
        assertEquals(View.GONE, panel.getVisibility());
        input.collapse();
        assertBubble(panel.guidanceMessage());
    }

    private void assertBubble(String words) {
        assertTrue(panel.isMinimizedProgress());
        assertEquals(View.VISIBLE, input.getVisibility());
        assertEquals(View.VISIBLE, panel.getVisibility());
        android.widget.TextView caption = panel.findViewById(com.click.R.id.task_flow_compact_message);
        assertEquals(words, caption.getText().toString());
        assertEquals(words + " 눌러서 안내 펼치기", caption.getContentDescription().toString());
    }

    private void place() throws Exception {
        Method place = ClickOverlayService.class.getDeclaredMethod("placeTaskFlow", int[].class, boolean.class);
        place.setAccessible(true);
        place.invoke(service, null, true);
    }
}
