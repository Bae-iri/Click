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
public class AccessibilityDisconnectTest {
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
