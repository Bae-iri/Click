package com.click;

import android.content.Intent;
import android.app.Application;
import android.view.View;
import android.widget.TextView;

import com.click.designpreview.AccountStartActivity;
import com.click.designpreview.ClickHost;
import com.click.designpreview.LoginActivity;
import com.click.designpreview.SignupActivity;
import com.click.designpreview.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

import static org.junit.Assert.*;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AccountStartActivityTest {
    @Test public void kakaoFailureKeepsPhoneRoutesAvailable() {
        ClickHost host = mock(ClickHost.class);
        doAnswer(call -> {
            ClickHost.Callback callback = call.getArgument(1);
            callback.complete(false, false, "카카오 로그인을 완료하지 못했어요. 전화번호로 시작해 주세요.");
            return null;
        }).when(host).loginWithKakao(any(), any());
        // Exercise the screen's failure recovery without requiring a real SDK signing certificate.
        try (MockedStatic<ClickHost> bridge = mockStatic(ClickHost.class);
             ActivityController<AccountStartActivity> controller =
                     Robolectric.buildActivity(AccountStartActivity.class).setup()) {
            AccountStartActivity activity = controller.get();
            bridge.when(() -> ClickHost.from(activity)).thenReturn(host);
            activity.findViewById(R.id.account_start_kakao).performClick();
            TextView error = activity.findViewById(R.id.account_start_error);
            assertEquals(View.VISIBLE, error.getVisibility());
            assertTrue(error.getText().toString().contains("전화번호"));
            assertTrue(activity.findViewById(R.id.account_start_kakao).isEnabled());
            assertTrue(activity.findViewById(R.id.account_start_email).isEnabled());
            assertEquals(View.GONE, activity.findViewById(R.id.account_start_progress).getVisibility());
            activity.findViewById(R.id.account_start_email).performClick();
            Intent signup = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(SignupActivity.class.getName(), signup.getComponent().getClassName());
        }
    }

    @Test public void returningPhoneUsersHaveAnExplicitLoginRoute() {
        try (ActivityController<AccountStartActivity> controller =
                     Robolectric.buildActivity(AccountStartActivity.class).setup()) {
            AccountStartActivity activity = controller.get();
            activity.findViewById(R.id.account_start_login).performClick();
            Intent login = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(LoginActivity.class.getName(), login.getComponent().getClassName());
        }
    }
}
