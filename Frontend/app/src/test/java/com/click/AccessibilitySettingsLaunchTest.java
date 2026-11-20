package com.click;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import com.click.data.local.AccessibilityOnboardingState;
import com.click.designpreview.PermissionGuideActivity;
import com.click.service.accessibility.ClickAccessibilityService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = com.click.testing.ClickTestApplication.class)
public class AccessibilitySettingsLaunchTest {
    @Test public void allManufacturersUseTheAccessibilityListAndPreserveDestination() {
        for (String manufacturer : new String[]{"samsung", "Google", "Xiaomi"}) {
            ShadowBuild.setManufacturer(manufacturer);
            ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
            Activity activity = mock(Activity.class);
            when(activity.getIntent()).thenReturn(new Intent()
                    .putExtra(PermissionGuideActivity.EXTRA_RETURN_TO_SETTINGS, true));
            app.openAccessibilitySettings(activity);
            ArgumentCaptor<Intent> launch = ArgumentCaptor.forClass(Intent.class);
            verify(activity).startActivity(launch.capture());
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, launch.getValue().getAction());
            assertNull(launch.getValue().getPackage());
            assertFalse(launch.getValue().hasExtra(Intent.EXTRA_COMPONENT_NAME));
            assertTrue(new AccessibilityOnboardingState(app).returnsToSettings());
        }
    }

    @Test public void homeReviewPreservesTheSeenTutorialFlagAndReturnsHome() {
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        AccessibilityOnboardingState state = new AccessibilityOnboardingState(app);
        state.markTutorialLaunched();
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(new Intent());
        app.openAccessibilitySettings(activity);
        assertTrue(state.isPending());
        assertTrue(state.returnsToHome());
        assertFalse(state.opensTutorial());
        assertTrue(state.wasTutorialLaunched());
    }

    @Test public void onlyAnExplicitOnboardingEntryRequestsTheTutorial() {
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        AccessibilityOnboardingState state = new AccessibilityOnboardingState(app);
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(new Intent().putExtra(PermissionGuideActivity.EXTRA_ONBOARDING, true));
        app.openAccessibilitySettings(activity);
        assertTrue(state.isPending());
        assertTrue(state.opensTutorial());
        assertFalse(state.returnsToHome());
        assertFalse(state.returnsToSettings());
    }

    @Test public void legacyPendingStateWithNoEntryMarkerDoesNotImplyOnboarding() {
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        app.getSharedPreferences("click_accessibility_onboarding", android.content.Context.MODE_PRIVATE)
            .edit().clear().putBoolean("pending", true).apply();
        AccessibilityOnboardingState state = new AccessibilityOnboardingState(app);
        assertTrue(state.isPending());
        assertFalse(state.opensTutorial());
        assertTrue(state.returnsToHome());
    }

}
