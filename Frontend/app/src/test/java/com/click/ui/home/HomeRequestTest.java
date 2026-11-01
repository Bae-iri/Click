package com.click.ui.home;

import android.app.Application;
import android.content.Intent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import com.click.HomeActivity;
import com.click.R;
import com.click.core.permission.PermissionStatus;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class HomeRequestTest {
    @Test public void microphoneOpensVoiceWithoutSubmittingTheDraft() {
        withHome(activity -> {
            ((EditText) activity.findViewById(R.id.home_request_input)).setText("작성 중인 요청");
            activity.findViewById(R.id.home_microphone).performClick();
            Intent intent = shadowOf(activity).getNextStartedService();
            assertEquals(ClickOverlayService.class.getName(), intent.getComponent().getClassName());
            assertTrue(intent.getBooleanExtra(ClickOverlayService.EXTRA_OPEN_VOICE, false));
            assertFalse(intent.hasExtra(ClickOverlayService.EXTRA_INITIAL_GOAL));
            assertFalse(intent.getBooleanExtra(ClickOverlayService.EXTRA_OPEN_MENU, false));
        });
    }

    @Test public void keyboardSendSubmitsTrimmedTextDirectlyAndPreventsDuplicateSubmission() {
        withHome(activity -> {
            EditText input = activity.findViewById(R.id.home_request_input);
            input.setText("  화면 밝기를 높여줘  ");
            input.onEditorAction(EditorInfo.IME_ACTION_SEND);
            Intent intent = shadowOf(activity).getNextStartedService();
            assertEquals("화면 밝기를 높여줘", intent.getStringExtra(ClickOverlayService.EXTRA_INITIAL_GOAL));
            assertFalse(intent.getBooleanExtra(ClickOverlayService.EXTRA_OPEN_VOICE, false));
            assertFalse(intent.getBooleanExtra(ClickOverlayService.EXTRA_OPEN_MENU, false));
            assertEquals("", input.getText().toString());
            input.setText("두 번 보내지 않기");
            activity.findViewById(R.id.home_request_send).performClick();
            assertNull(shadowOf(activity).getNextStartedService());
        });
    }

    @Test public void blankInputDoesNotLaunchAndVisibleSendButtonUsesSameRequestPath() {
        withHome(activity -> {
            EditText input = activity.findViewById(R.id.home_request_input);
            input.setText("   "); input.onEditorAction(EditorInfo.IME_ACTION_SEND);
            assertNull(shadowOf(activity).getNextStartedService());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.home_request_send).getVisibility());
            input.setText("글씨 크기를 키워줘");
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.home_request_send).getVisibility());
            activity.findViewById(R.id.home_request_send).performClick();
            assertEquals("글씨 크기를 키워줘", shadowOf(activity).getNextStartedService()
                    .getStringExtra(ClickOverlayService.EXTRA_INITIAL_GOAL));
        });
    }

    @Test public void submittedHomeRequestClosesAnExistingInputPanelAndQueuesWithoutAnotherInputStep() {
        ClickOverlayService service = Robolectric.buildService(ClickOverlayService.class).get();
        com.click.ui.overlay.ClickOverlayLayout panel = mock(com.click.ui.overlay.ClickOverlayLayout.class);
        com.click.data.remote.VoiceQueueGateway queue = mock(com.click.data.remote.VoiceQueueGateway.class);
        org.robolectric.util.ReflectionHelpers.setField(service, "overlayView", panel);
        org.robolectric.util.ReflectionHelpers.setField(service, "queueGateway", queue);
        org.robolectric.util.ReflectionHelpers.setField(service, "agentSessionId", "existing-session");
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return true; }).when(panel).post(any());
        service.onStartCommand(new Intent().putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL,
                "  화면 밝기를 높여줘  "), 0, 1);
        org.mockito.InOrder order = inOrder(panel, queue);
        order.verify(panel).collapse();
        order.verify(queue).enqueue(anyString(), eq("화면 밝기를 높여줘"), any());
        verify(panel, never()).openText();
        verify(panel, never()).openMenu();
    }

    private void withHome(java.util.function.Consumer<HomeActivity> test) {
        try (MockedStatic<PermissionStatus> permissions = mockStatic(PermissionStatus.class);
             MockedStatic<ClickAccessibilityService> accessibility = mockStatic(ClickAccessibilityService.class)) {
            permissions.when(() -> PermissionStatus.isAccessibilityEnabled(any())).thenReturn(true);
            accessibility.when(ClickAccessibilityService::accessibilityWindowManager).thenReturn(mock(WindowManager.class));
            try (ActivityController<HomeActivity> controller = Robolectric.buildActivity(HomeActivity.class).setup()) {
                test.accept(controller.get());
            }
        }
    }
}
