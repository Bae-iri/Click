package com.click;

import android.app.Application;
import android.content.Intent;
import android.widget.EditText;
import android.widget.TextView;

import com.click.designpreview.ClickHost;
import com.click.designpreview.LoginActivity;
import com.click.designpreview.PermissionGuideActivity;
import com.click.designpreview.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class LoginActivityTest {
    @Test public void kakaoNeedsNoEmailAndBlocksConcurrentLoginUntilFailure() {
        ClickHost host = mock(ClickHost.class);
        ClickHost.Callback[] pending = new ClickHost.Callback[1];
        doAnswer(call -> { pending[0] = call.getArgument(1); return null; })
                .when(host).loginWithKakao(any(), any());
        try (MockedStatic<ClickHost> bridge = mockStatic(ClickHost.class);
             ActivityController<LoginActivity> controller =
                     Robolectric.buildActivity(LoginActivity.class).setup()) {
            LoginActivity activity = controller.get();
            bridge.when(() -> ClickHost.from(activity)).thenReturn(host);
            activity.findViewById(R.id.login_kakao).performClick();
            assertNotNull(pending[0]);
            assertFalse(activity.findViewById(R.id.login_action).isEnabled());
            activity.findViewById(R.id.login_kakao).performClick();
            verify(host, times(1)).loginWithKakao(eq(activity), any());
            verify(host, never()).login(any(), any(), any(), any());
            pending[0].complete(false, false, "로그인을 취소했어요.");
            assertTrue(activity.findViewById(R.id.login_action).isEnabled());
            assertTrue(activity.findViewById(R.id.login_kakao).isEnabled());
            assertEquals("로그인을 취소했어요.",
                    ((TextView) activity.findViewById(R.id.login_api_error)).getText().toString());
        }
    }

    @Test public void phoneLoginUsesPhoneHostAndContinuesToPermissionGuide() {
        ClickHost host = mock(ClickHost.class);
        doAnswer(call -> {
            ((ClickHost.Callback) call.getArgument(3)).complete(true, false, "");
            return null;
        }).when(host).login(any(), eq("01012345678"), eq("0123"), any());
        try (MockedStatic<ClickHost> bridge = mockStatic(ClickHost.class);
             ActivityController<LoginActivity> controller =
                     Robolectric.buildActivity(LoginActivity.class).setup()) {
            LoginActivity activity = controller.get();
            bridge.when(() -> ClickHost.from(activity)).thenReturn(host);
            ((EditText) activity.findViewById(R.id.login_email)).setText("01012345678");
            ((EditText) activity.findViewById(R.id.login_password)).setText("0123");
            activity.findViewById(R.id.login_action).performClick();
            verify(host).login(eq(activity), eq("01012345678"), eq("0123"), any());
            verify(host, never()).loginWithKakao(any(), any());
            Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(PermissionGuideActivity.class.getName(), next.getComponent().getClassName());
        }
    }
}
