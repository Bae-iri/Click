package com.click;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.app.Application;
import android.view.View;
import android.widget.TextView;
import android.widget.SeekBar;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.ui.common.ClickTextScale;

import com.click.designpreview.AccountStartActivity;
import com.click.designpreview.TextSizeSetupActivity;
import com.click.designpreview.OnboardingActivity;
import com.click.designpreview.ClickHost;
import com.click.designpreview.LoginActivity;
import com.click.designpreview.SignupActivity;
import com.click.designpreview.R;
import java.util.ArrayList;

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
    @Test public void oldDisplaySpeedPreferenceLeavesOnlyTextSizingOnBothScreens() {
        Context app = org.robolectric.RuntimeEnvironment.getApplication();
        app.getSharedPreferences("click_display_settings", Context.MODE_PRIVATE)
                .edit().putInt("ui_speed_level", 0).apply();
        try (var setup = Robolectric.buildActivity(TextSizeSetupActivity.class).setup();
             var settings = Robolectric.buildActivity(DisplaySettingsActivity.class).setup()) {
            assertTextSizeWithoutSpeedControl(setup.get());
            assertTextSizeWithoutSpeedControl(settings.get());
        }
    }

    private static void assertTextSizeWithoutSpeedControl(Activity activity) {
        assertNotNull(activity.findViewById(R.id.text_size_scale));
        var matches = new ArrayList<View>();
        activity.findViewById(android.R.id.content).findViewsWithText(matches, "화면 표시 속도",
                View.FIND_VIEWS_WITH_TEXT | View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        assertTrue("The old speed setting is still visible", matches.isEmpty());
    }

    @Test public void textSizingPrecedesOnboardingAndRegistrationDoesNotRepeatIt() {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        int original = ClickTextScale.level(app);
        ClickTextScale.saveLevel(app, 1);
        try (ActivityController<TextSizeSetupActivity> controller =
                     Robolectric.buildActivity(TextSizeSetupActivity.class).setup()) {
            TextSizeSetupActivity activity = controller.get();
            assertNull(activity.findViewById(R.id.account_start_kakao));
            TextView title = activity.findViewById(R.id.text_size_setup_title);
            assertEquals("이 정도 글씨 괜찮으세요?", title.getText().toString());
            float baseSize = title.getTextSize();
            setLevel(activity.findViewById(R.id.text_size_scale), 3);
            assertEquals(baseSize * 1.3f, title.getTextSize(), .1f);
            ClickTextScale.applyTree(activity.findViewById(R.id.text_size_setup_screen));
            assertEquals("Repeated updates must not compound the selected size", baseSize * 1.3f, title.getTextSize(), .1f);
            controller.recreate();
            activity = controller.get();
            assertEquals(3, ((SeekBar) activity.findViewById(R.id.text_size_scale)).getProgress());
            activity.findViewById(R.id.text_size_setup_continue).performClick();
            Intent introduction = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(OnboardingActivity.class.getName(), introduction.getComponent().getClassName());
            try (var onboarding = Robolectric.buildActivity(OnboardingActivity.class, introduction).setup()) {
                onboarding.get().findViewById(R.id.onboarding_start).performClick();
                TextView exampleTitle = onboarding.get().findViewById(R.id.onboarding_title);
                assertEquals(24 * 1.3f * exampleTitle.getResources().getDisplayMetrics().scaledDensity,
                        exampleTitle.getTextSize(), .1f);
                onboarding.get().findViewById(R.id.onboarding_start).performClick();
                Intent account = Shadows.shadowOf(onboarding.get()).getNextStartedActivity();
                assertEquals(AccountStartActivity.class.getName(), account.getComponent().getClassName());
                try (var choices = Robolectric.buildActivity(AccountStartActivity.class, account).setup()) {
                    assertNull(choices.get().findViewById(R.id.text_size_setup_screen));
                    assertNotNull(choices.get().findViewById(R.id.account_start_kakao));
                    choices.recreate();
                    assertNull(choices.get().findViewById(R.id.text_size_setup_screen));
                    choices.get().findViewById(R.id.account_start_back).performClick();
                    assertTrue(choices.get().isFinishing());
                }
            }
            try (var settings = Robolectric.buildActivity(DisplaySettingsActivity.class).setup()) {
                assertEquals(3, ((SeekBar) settings.get().findViewById(R.id.text_size_scale)).getProgress());
                setLevel(settings.get().findViewById(R.id.text_size_scale), 0);
                assertEquals(0, ClickTextScale.level(app));
            }
        } finally { ClickTextScale.saveLevel(app, original); }
    }

    private static void setLevel(SeekBar bar, int level) {
        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, level);
        assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args));
    }

    @Test public void transparencyAndTextSizeHaveIndependentLivePreviewsAndPersist() {
        try (var settings = Robolectric.buildActivity(DisplaySettingsActivity.class).setup()) {
            var activity = settings.get();
            int sliderId = com.click.R.id.floating_transparency_scale;
            int previewId = com.click.R.id.floating_transparency_preview;
            TextView bubble = activity.findViewById(previewId);
            int originalAlpha = bubble.getBackground().getAlpha();
            setLevel(activity.findViewById(sliderId), 60);
            assertEquals(102, bubble.getBackground().getAlpha());
            assertTrue(bubble.getBackground().getAlpha() < originalAlpha);
            float originalSize = bubble.getTextSize();
            setLevel(activity.findViewById(R.id.text_size_scale), 3);
            assertEquals(originalSize * 1.3f, bubble.getTextSize(), .1f);
            assertEquals(102, bubble.getBackground().getAlpha());
            settings.recreate();
            activity = settings.get();
            assertEquals(60, ((SeekBar) activity.findViewById(sliderId)).getProgress());
            assertEquals(3, ((SeekBar) activity.findViewById(R.id.text_size_scale)).getProgress());
            assertEquals(102, activity.findViewById(previewId).getBackground().getAlpha());
        }
    }

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
