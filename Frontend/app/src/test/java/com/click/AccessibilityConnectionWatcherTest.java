package com.click;
import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import com.click.designpreview.AccessibilityConnectionWatcher;
import com.click.designpreview.ClickHost;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AccessibilityConnectionWatcherTest {
 @Test public void permissionAloneDoesNotCompleteAndTimeoutRunsOnce() {
  exercise((watcher, host, ready, timeout) -> {
   watcher.start(ready, timeout);
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
   verify(ready, never()).run(); verify(timeout).run();
   when(host.accessibilityConnected()).thenReturn(true);
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
   verify(ready, never()).run(); verifyNoMoreInteractions(timeout);
  });
 }
 @Test public void stoppingTheScreenCancelsThePendingLaunch() {
  exercise((watcher, host, ready, timeout) -> {
   watcher.start(ready, timeout); watcher.stop();
   when(host.accessibilityConnected()).thenReturn(true);
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
   verifyNoInteractions(ready, timeout);
  });
 }
 @Test public void actualConnectionCompletesOnlyOnce() {
  exercise((watcher, host, ready, timeout) -> {
   watcher.start(ready, timeout);
   when(host.accessibilityConnected()).thenReturn(true);
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
   verify(ready).run(); verifyNoMoreInteractions(ready); verifyNoInteractions(timeout);
  });
 }
 @Test public void permissionRevokedWhileWaitingDoesNotLaunch() {
  exercise((watcher, host, ready, timeout) -> {
   watcher.start(ready, timeout);
   when(host.accessibilityEnabled()).thenReturn(false);
   when(host.accessibilityConnected()).thenReturn(true);
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
   verify(ready, never()).run(); verify(timeout).run();
  });
 }
 private interface Check { void run(AccessibilityConnectionWatcher watcher, ClickHost host, Runnable ready, Runnable timeout); }
 private void exercise(Check check) {
  try(var controller=Robolectric.buildActivity(Activity.class).setup(); MockedStatic<ClickHost> bridge=mockStatic(ClickHost.class)) {
   Activity activity=controller.get();ClickHost host=mock(ClickHost.class);
   bridge.when(()->ClickHost.from(activity)).thenReturn(host);
   when(host.accessibilityEnabled()).thenReturn(true);
   AccessibilityConnectionWatcher watcher=new AccessibilityConnectionWatcher(activity);
   try {check.run(watcher,host,mock(Runnable.class),mock(Runnable.class));} finally {watcher.stop();}
  }
 }
}
