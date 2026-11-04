package com.click;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.widget.EditText;
import android.widget.TextView;
import com.click.designpreview.ClickHost;
import com.click.designpreview.SignupActivity;
import com.click.designpreview.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SignupActivityTest {
    @Test public void phoneReviewAndFinalSignupSubmitNormalizedPhoneAndFourDigitPin() {
        ClickHost host = mock(ClickHost.class);
        doAnswer(call -> { ((ClickHost.PasswordRulesCallback) call.getArgument(1)).complete(4, 4); return null; })
                .when(host).loadRegistrationRules(any(), any());
        try (MockedStatic<ClickHost> bridge = mockStatic(ClickHost.class)) {
            bridge.when(() -> ClickHost.from(any(Activity.class))).thenReturn(host);
            try (ActivityController<SignupActivity> controller = Robolectric.buildActivity(SignupActivity.class).setup()) {
                SignupActivity a = controller.get();
                ((EditText) a.findViewById(R.id.signup_email)).setText("010-1234-5678");
                ((EditText) a.findViewById(R.id.signup_password)).setText("0123");
                ((EditText) a.findViewById(R.id.signup_password_confirm)).setText("0123");
                a.findViewById(R.id.signup_action).performClick();
                Dialog review = ShadowDialog.getLatestDialog();
                assertNotNull(review);
                assertEquals("01012345678", ((TextView) review.findViewById(R.id.email_confirmation_address)).getText().toString());
                review.findViewById(R.id.email_confirmation_continue).performClick();
                a.findViewById(R.id.signup_action).performClick();
                verify(host).register(eq(a), eq(""), eq("01012345678"), eq("0123"), any());
            }
        }
    }
}
