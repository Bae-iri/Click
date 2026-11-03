package com.click;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.click.designpreview.AccountStartActivity;
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
import static org.junit.Assume.assumeTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = ClickApplication.class)
public class AccountStartActivityTest {
    @Test public void missingKakaoConfigurationKeepsEmailRoutesAvailable() {
        assumeTrue(BuildConfig.KAKAO_NATIVE_APP_KEY.isEmpty());
        try (ActivityController<AccountStartActivity> controller =
                     Robolectric.buildActivity(AccountStartActivity.class).setup()) {
            AccountStartActivity activity = controller.get();
            activity.findViewById(R.id.account_start_kakao).performClick();
            TextView error = activity.findViewById(R.id.account_start_error);
            assertEquals(View.VISIBLE, error.getVisibility());
            assertTrue(error.getText().toString().contains("이메일"));
            assertTrue(activity.findViewById(R.id.account_start_kakao).isEnabled());
            assertTrue(activity.findViewById(R.id.account_start_email).isEnabled());
            assertEquals(View.GONE, activity.findViewById(R.id.account_start_progress).getVisibility());
            activity.findViewById(R.id.account_start_email).performClick();
            Intent signup = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(SignupActivity.class.getName(), signup.getComponent().getClassName());
        }
    }

    @Test public void returningEmailUsersHaveAnExplicitLoginRoute() {
        try (ActivityController<AccountStartActivity> controller =
                     Robolectric.buildActivity(AccountStartActivity.class).setup()) {
            AccountStartActivity activity = controller.get();
            activity.findViewById(R.id.account_start_login).performClick();
            Intent login = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(LoginActivity.class.getName(), login.getComponent().getClassName());
        }
    }
}
