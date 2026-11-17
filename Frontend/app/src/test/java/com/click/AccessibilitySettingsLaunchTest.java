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
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = ClickApplication.class)
public class AccessibilitySettingsLaunchTest {
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
}
