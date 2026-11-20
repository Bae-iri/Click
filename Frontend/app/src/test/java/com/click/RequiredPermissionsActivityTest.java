package com.click;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import com.click.core.permission.PermissionStatus;
import com.click.designpreview.ClickHost;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSettings;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RequiredPermissionsActivityTest {
    private MockedStatic<ClickHost> bridge;
    private MockedStatic<PermissionStatus> permissions;
    private ClickHost host;
    @Before public void setup() {
        host=mock(ClickHost.class);
        when(host.accessibilityEnabled()).thenReturn(true);
        when(host.accessibilityConnected()).thenReturn(true);
        bridge=mockStatic(ClickHost.class);
        bridge.when(()->ClickHost.from(any(Activity.class))).thenReturn(host);
        permissions=mockStatic(PermissionStatus.class);
        permissions.when(()->PermissionStatus.isAccessibilityEnabled(any())).thenReturn(true);
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        ShadowSettings.setCanDrawOverlays(false);
    }
    @After public void close() { permissions.close(); bridge.close(); }
    private Intent intent(String destination) {
        return new Intent(RuntimeEnvironment.getApplication(),RequiredPermissionsActivity.class)
            .putExtra(RequiredPermissionsActivity.EXTRA_DESTINATION,destination);
    }
    private Button button(View view) {
        if(view instanceof Button)return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=button(((ViewGroup)view).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
    private boolean contains(View view,String text) {
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(text))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            if(contains(((ViewGroup)view).getChildAt(i),text))return true;
        return false;
    }
    private View root(Activity activity) {return activity.findViewById(android.R.id.content);}

    @Test public void grantsAreCheckedInOrderAndDeniedOrUnchangedReturnsCannotFinish() {
        try(var controller=Robolectric.buildActivity(RequiredPermissionsActivity.class,intent("tutorial")).setup()) {
            var activity=controller.get();
            assertTrue(contains(root(activity),"필수 권한 2 / 3"));
            button(root(activity)).performClick();
            assertArrayEquals(new String[]{Manifest.permission.RECORD_AUDIO},shadowOf(activity).getLastRequestedPermission().requestedPermissions);
            activity.onRequestPermissionsResult(41,new String[]{Manifest.permission.RECORD_AUDIO},new int[]{PackageManager.PERMISSION_GRANTED});
            assertTrue(contains(root(activity),"필수 권한 2 / 3")); // callback alone is not a live grant
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
            controller.pause().resume();
            assertTrue(contains(root(activity),"필수 권한 3 / 3"));
            button(root(activity)).performClick();
            Intent settings=shadowOf(activity).getNextStartedActivity();
            assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,settings.getAction());
            assertEquals("package:"+activity.getPackageName(),settings.getDataString());
            controller.pause().resume();
            assertFalse(contains(root(activity),"이제 클릭을 사용할"));
            controller.recreate(); activity=controller.get();
            assertTrue(contains(root(activity),"필수 권한 3 / 3"));
            ShadowSettings.setCanDrawOverlays(true);
            controller.pause().resume();
            assertTrue(contains(root(activity),"이제 클릭을 사용할 수 있어요!"));
            button(root(activity)).performClick();
            assertEquals(TutorialActivity.class.getName(),shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            assertTrue(activity.isFinishing());
        }
    }

    @Test public void permanentlyDeniedMicrophoneOffersAppSettingsInsteadOfRepeatingDialog() {
        try(var controller=Robolectric.buildActivity(RequiredPermissionsActivity.class,intent("home")).setup()) {
            var activity=controller.get();
            activity.getPreferences(Activity.MODE_PRIVATE).edit().putBoolean("microphone_requested",true).commit();
            button(root(activity)).performClick();
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,shadowOf(activity).getNextStartedActivity().getAction());
            assertNull(shadowOf(activity).getLastRequestedPermission());
            assertFalse(activity.isFinishing());
        }
    }

    @Test public void existingGrantsAreSkippedAndReviewDestinationSurvives() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ShadowSettings.setCanDrawOverlays(true);
        for(String target:new String[]{"settings","home"}) {
            try(var controller=Robolectric.buildActivity(RequiredPermissionsActivity.class,intent(target)).setup()) {
                var activity=controller.get();
                assertTrue(contains(root(activity),"이제 클릭을 사용할 수 있어요!"));
                assertNull(shadowOf(activity).getLastRequestedPermission());
                button(root(activity)).performClick();
                assertEquals((target.equals("settings")?MenuActivity.class:HomeActivity.class).getName(),
                    shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            }
        }
    }

    @Test public void lostAccessibilityConnectionPreventsReadyAndKeepsOnboardingDestination() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ShadowSettings.setCanDrawOverlays(true);
        try(var controller=Robolectric.buildActivity(RequiredPermissionsActivity.class,intent("tutorial")).setup()) {
            when(host.accessibilityConnected()).thenReturn(false);
            controller.pause().resume();
            assertTrue(contains(root(controller.get()),"필수 권한 1 / 3"));
            button(root(controller.get())).performClick();
            Intent guide=shadowOf(controller.get()).getNextStartedActivity();
            assertTrue(guide.getBooleanExtra(com.click.designpreview.PermissionGuideActivity.EXTRA_ONBOARDING,false));
            assertFalse(controller.get().isFinishing());
        }
    }

    @Test public void readyScreenCannotBeOpenedBeforeAllPermissionsAreGranted() {
        try(var controller=Robolectric.buildActivity(TutorialReadyActivity.class).setup()) {
            assertTrue(controller.get().isFinishing());
            assertEquals(RequiredPermissionsActivity.class.getName(),
                shadowOf(controller.get()).getNextStartedActivity().getComponent().getClassName());
        }
    }

    @Test public void delayedAccessibilityBindingAdvancesWithoutAnotherSettingsTrip() {
        when(host.accessibilityConnected()).thenReturn(false);
        try(var controller=Robolectric.buildActivity(RequiredPermissionsActivity.class,intent("tutorial")).setup()) {
            assertTrue(contains(root(controller.get()),"필수 권한 1 / 3"));
            when(host.accessibilityConnected()).thenReturn(true);
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));
            assertTrue(contains(root(controller.get()),"필수 권한 2 / 3"));
        }
    }

    @Test public void reusedPermissionScreenUsesTheLatestReturnDestination() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ShadowSettings.setCanDrawOverlays(true);
        try(var controller=Robolectric.buildActivity(RequiredPermissionsActivity.class,intent("home")).setup()) {
            controller.get().onNewIntent(intent("settings"));
            button(root(controller.get())).performClick();
            assertEquals(MenuActivity.class.getName(),
                shadowOf(controller.get()).getNextStartedActivity().getComponent().getClassName());
        }
    }
}
