package com.click;
import android.app.Application;
import android.content.Intent;
import android.accessibilityservice.AccessibilityService;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AccessibilityServiceConnectionTest {
 @Test public void connectionKeepsReturnPendingUntilDestinationAcknowledges() throws Exception {
  for(int entry:new int[]{0,1,2}) {
   var app=org.robolectric.RuntimeEnvironment.getApplication();
   var state=new com.click.data.local.AccessibilityOnboardingState(app);
   if(entry==0) state.beginOnboarding(); else state.beginReview(entry==2);
   try(var overlays=mockStatic(ClickOverlayService.class)) {
    var controller=Robolectric.buildService(ClickAccessibilityService.class).create();
    try {
     var connected=ClickAccessibilityService.class.getDeclaredMethod("onServiceConnected");connected.setAccessible(true);
     connected.invoke(controller.get());
     Intent destination=org.robolectric.Shadows.shadowOf(app).getNextStartedActivity();
     assertEquals((entry==0?TutorialReadyActivity.class:entry==2?MenuActivity.class:HomeActivity.class).getName(),destination.getComponent().getClassName());
     assertTrue(state.isPending());
     org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
       .idleFor(java.time.Duration.ofSeconds(1));
     assertNotNull(org.robolectric.Shadows.shadowOf(app).getNextStartedActivity());
     if(entry==0) state.markTutorialLaunched(); else if(entry==2) state.markSettingsReturned(); else state.markHomeReturned();
     org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
       .idleFor(java.time.Duration.ofSeconds(2));
     assertNull(org.robolectric.Shadows.shadowOf(app).getNextStartedActivity());
    } finally {controller.destroy();}
   }
  }
 }
 @Test public void unacknowledgedReturnRetriesBrieflyAndRemainsRecoverable() throws Exception {
  var app=org.robolectric.RuntimeEnvironment.getApplication();
  var state=new com.click.data.local.AccessibilityOnboardingState(app);
  state.begin();
  try(var overlays=mockStatic(ClickOverlayService.class)) {
   var controller=Robolectric.buildService(ClickAccessibilityService.class).create();
   try {
    var connected=ClickAccessibilityService.class.getDeclaredMethod("onServiceConnected");
    connected.setAccessible(true);connected.invoke(controller.get());
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
      .idleFor(java.time.Duration.ofSeconds(10));
    int launches=0;
    while(org.robolectric.Shadows.shadowOf(app).getNextStartedActivity()!=null) launches++;
    assertEquals(4,launches);
    assertTrue(state.isPending());
    assertFalse(state.wasTutorialLaunched());
   } finally {controller.destroy();}
  }
 }
 @Test public void resumedTutorialAcknowledgesReturn() {
  var state=new com.click.data.local.AccessibilityOnboardingState(
    org.robolectric.RuntimeEnvironment.getApplication());
  state.begin();
  try(var activity=Robolectric.buildActivity(TutorialReadyActivity.class)) {
   activity.create().start();
   assertTrue(state.isPending());
   activity.resume();
   assertFalse(state.isPending());
   assertTrue(state.wasTutorialLaunched());
  }
 }
 @Test public void unbindingImmediatelyClearsConnectionBeforeDestroy() throws Exception {
  try (MockedStatic<ClickOverlayService> overlays=mockStatic(ClickOverlayService.class)) {
   var controller=Robolectric.buildService(ClickAccessibilityService.class).create();
   ClickAccessibilityService service=controller.get();
   try {
    var connected=ClickAccessibilityService.class.getDeclaredMethod("onServiceConnected");
    connected.setAccessible(true);connected.invoke(service);
    assertNotNull(ClickAccessibilityService.accessibilityWindowManager());
    service.onUnbind(new Intent(AccessibilityService.SERVICE_INTERFACE));
    assertNull(ClickAccessibilityService.accessibilityWindowManager());
    overlays.verify(ClickOverlayService::onAccessibilityOverlayLost);
   } finally {controller.destroy();}
   overlays.verify(ClickOverlayService::onAccessibilityOverlayLost, times(1));
  }
 }
}
