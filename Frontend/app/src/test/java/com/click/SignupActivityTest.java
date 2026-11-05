package com.click;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.view.View;
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
    @Test public void phoneSignupRequiresValidNameAndSubmitsTrimmedNameWithNormalizedCredentials() {
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
                EditText name = a.findViewById(R.id.signup_name);
                TextView error = a.findViewById(R.id.signup_name_error);
                for (String invalid : new String[] {"", "   ", "\u3000", "김".repeat(51)}) {
                    name.setText(invalid);
                    a.findViewById(R.id.signup_action).performClick();
                    assertEquals(View.VISIBLE, error.getVisibility());
                    assertEquals("이름을 1~50글자로 입력해 주세요.", error.getText().toString());
                    assertEquals(View.VISIBLE, a.findViewById(R.id.signup_profile_content).getVisibility());
                    assertTrue(a.findViewById(R.id.signup_action).isEnabled());
                    verify(host, never()).register(any(), anyString(), anyString(), anyString(), any());
                }
                name.setText("  김클릭  ");
                a.findViewById(R.id.signup_action).performClick();
                assertEquals(View.GONE, error.getVisibility());
                verify(host).register(eq(a), eq("김클릭"), eq("01012345678"), eq("0123"), any());
            }
        }
    }
}
