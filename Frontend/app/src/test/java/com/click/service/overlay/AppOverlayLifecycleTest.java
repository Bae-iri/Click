package com.click.service.overlay;

import android.app.Activity;
import android.content.Intent;
import com.click.ClickApplication;
import com.click.HomeActivity;
import com.click.MenuActivity;
import com.click.data.local.AuthTokenStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.mockito.Mockito.*;

/** Exercises the callbacks registered by the production Application. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=com.click.testing.ClickTestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppOverlayLifecycleTest {
    @Test public void openingHomeEndsFloatingGuidance() {
        try (var tokens=mockConstruction(AuthTokenStore.class);
             var overlay=mockStatic(ClickOverlayService.class);
             var home=Robolectric.buildActivity(HomeActivity.class).setup()) {
            overlay.verify(ClickOverlayService::stopForAppEntry,times(1));
        }
    }
    @Test public void returningToAnExistingHomeEndsFloatingGuidanceAgain() {
        try (var tokens=mockConstruction(AuthTokenStore.class);
             var overlay=mockStatic(ClickOverlayService.class);
             var home=Robolectric.buildActivity(HomeActivity.class).setup()) {
            home.pause().stop();overlay.clearInvocations();
            home.start().resume();
            overlay.verify(ClickOverlayService::stopForAppEntry,times(1));
        }
    }
    @Test public void enteringTheAppThroughItsMenuAlsoEndsFloatingGuidance() {
        try (var tokens=mockConstruction(AuthTokenStore.class);
             var overlay=mockStatic(ClickOverlayService.class);
             var menu=Robolectric.buildActivity(MenuActivity.class).setup()) {
            overlay.verify(ClickOverlayService::stopForAppEntry,times(1));
        }
    }
    @Test public void leavingTheAppToStartGuidanceDoesNotImmediatelyStopIt() {
        try (var overlay=mockStatic(ClickOverlayService.class);
             var activity=Robolectric.buildActivity(Activity.class).setup()) {
            overlay.clearInvocations();
            activity.get().startService(new Intent(activity.get(),ClickOverlayService.class));
            activity.pause().stop();
            overlay.verify(ClickOverlayService::stopForAppEntry,never());
        }
    }
}
