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
@Config(sdk = 34, application = ClickApplication.class)
public class AccessibilitySettingsLaunchTest {
    @Test public void samsungUsesItsOwnDetailsScreenInsteadOfAospHomepage() {
        ShadowBuild.setManufacturer("samsung");
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(new Intent());

        app.openAccessibilitySettings(activity);

        ArgumentCaptor<Intent> launch = ArgumentCaptor.forClass(Intent.class);
        verify(activity).startActivity(launch.capture());
        assertEquals("com.samsung.accessibility.ACCESSIBILITY_DETAILS_SETTINGS", launch.getValue().getAction());
        assertEquals("com.samsung.accessibility", launch.getValue().getPackage());
        assertEquals(new ComponentName(app, ClickAccessibilityService.class).flattenToString(),
                launch.getValue().getStringExtra(Intent.EXTRA_COMPONENT_NAME));
    }

    @Test public void missingSamsungScreenTriesAospDetailsBeforeList() {
        assertSamsungFallback(new ActivityNotFoundException());
    }

    @Test public void restrictedSamsungScreenTriesAospDetailsBeforeList() {
        assertSamsungFallback(new SecurityException());
    }

    private void assertSamsungFallback(RuntimeException failure) {
        ShadowBuild.setManufacturer("samsung");
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(new Intent());
        doThrow(failure).doThrow(new ActivityNotFoundException()).doNothing()
                .when(activity).startActivity(any(Intent.class));

        app.openAccessibilitySettings(activity);

        ArgumentCaptor<Intent> launches = ArgumentCaptor.forClass(Intent.class);
        verify(activity, times(3)).startActivity(launches.capture());
        assertEquals("com.samsung.accessibility.ACCESSIBILITY_DETAILS_SETTINGS", launches.getAllValues().get(0).getAction());
        assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", launches.getAllValues().get(1).getAction());
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, launches.getAllValues().get(2).getAction());
    }

    @Test public void opensOwnServiceDetailsAndPreservesReturnDestination() {
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(new Intent()
                .putExtra(PermissionGuideActivity.EXTRA_RETURN_TO_SETTINGS, true));

        app.openAccessibilitySettings(activity);

        ArgumentCaptor<Intent> launch = ArgumentCaptor.forClass(Intent.class);
        verify(activity).startActivity(launch.capture());
        assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", launch.getValue().getAction());
        assertEquals(new ComponentName(app, ClickAccessibilityService.class).flattenToString(),
                launch.getValue().getStringExtra(Intent.EXTRA_COMPONENT_NAME));
        AccessibilityOnboardingState state = new AccessibilityOnboardingState(app);
        assertTrue(state.isPending());
        assertTrue(state.returnsToSettings());
    }

    @Test public void missingDetailsScreenFallsBackToAccessibilityList() {
        assertFallback(new ActivityNotFoundException());
    }

    @Test public void restrictedDetailsScreenFallsBackToAccessibilityList() {
        assertFallback(new SecurityException());
    }

    private void assertFallback(RuntimeException failure) {
        ClickApplication app = (ClickApplication) RuntimeEnvironment.getApplication();
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(new Intent());
        doThrow(failure).doNothing().when(activity).startActivity(any(Intent.class));

        app.openAccessibilitySettings(activity);

        ArgumentCaptor<Intent> launches = ArgumentCaptor.forClass(Intent.class);
        verify(activity, times(2)).startActivity(launches.capture());
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, launches.getAllValues().get(1).getAction());
        assertTrue(new AccessibilityOnboardingState(app).isPending());
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
