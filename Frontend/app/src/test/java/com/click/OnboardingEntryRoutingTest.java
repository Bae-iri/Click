package com.click;

import android.app.Application;
import android.content.Intent;
import com.click.designpreview.AccountStartActivity;
import com.click.designpreview.LoginActivity;
import com.click.designpreview.OnboardingActivity;
import com.click.designpreview.PermissionGuideActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class OnboardingEntryRoutingTest {
    @Test public void initialWelcomeCarriesItsEntryToAccountSelection() {
        try (var controller = Robolectric.buildActivity(OnboardingActivity.class).setup()) {
            var activity = controller.get();
            activity.findViewById(com.click.designpreview.R.id.onboarding_start).performClick();
            activity.findViewById(com.click.designpreview.R.id.onboarding_start).performClick();
            Intent next = shadowOf(activity).getNextStartedActivity();
            assertEquals(AccountStartActivity.class.getName(), next.getComponent().getClassName());
            assertTrue(next.getBooleanExtra(PermissionGuideActivity.EXTRA_ONBOARDING, false));
        }
    }

    @Test public void welcomeLoginShortcutCarriesItsOnboardingEntry() {
        try (var controller = Robolectric.buildActivity(OnboardingActivity.class).setup()) {
            controller.get().findViewById(com.click.designpreview.R.id.guide_entry).performClick();
            Intent next = shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(LoginActivity.class.getName(), next.getComponent().getClassName());
            assertTrue(next.getBooleanExtra(PermissionGuideActivity.EXTRA_ONBOARDING, false));
        }
    }

    @Test public void accountLoginPreservesTheEntryInsteadOfAssumingFirstUse() {
        for (boolean onboarding : new boolean[]{true, false}) {
            Intent entry = new Intent(RuntimeEnvironment.getApplication(), AccountStartActivity.class)
                    .putExtra(PermissionGuideActivity.EXTRA_ONBOARDING, onboarding);
            try (var controller = Robolectric.buildActivity(AccountStartActivity.class, entry).setup()) {
                controller.get().findViewById(com.click.designpreview.R.id.account_start_login).performClick();
                Intent next = shadowOf(controller.get()).getNextStartedActivity();
                assertEquals(LoginActivity.class.getName(), next.getComponent().getClassName());
                assertEquals(onboarding, next.getBooleanExtra(PermissionGuideActivity.EXTRA_ONBOARDING, false));
            }
        }
    }
}
