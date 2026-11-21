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

    @Test public void aNewRequestReopensAPreviouslyMinimizedPanelWhileItIsQueued() throws Exception {
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

    private void place() throws Exception {
        Method place = ClickOverlayService.class.getDeclaredMethod("placeTaskFlow", int[].class, boolean.class);
        place.setAccessible(true);
        place.invoke(service, null, true);
    }
}
