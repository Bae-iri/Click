package com.click.service.accessibility;
import android.app.Application;
import android.os.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SnapshotCaptureSchedulerTest {
 @Test public void continuousEventsCannotDeferObservationBeyondMaximumWait() {
  var count=new AtomicInteger();var scheduler=new SnapshotCaptureScheduler(new Handler(Looper.getMainLooper()),count::incrementAndGet);
  for(int i=0;i<10;i++){scheduler.request();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));}
  assertEquals(1,count.get());
  scheduler.request();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));assertEquals(2,count.get());
 }
 @Test public void immediateReadAndDisconnectCancelPendingDebouncedWork() {
  var count=new AtomicInteger();var scheduler=new SnapshotCaptureScheduler(new Handler(Looper.getMainLooper()),count::incrementAndGet);
  scheduler.request();scheduler.cancel();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(0,count.get());
  scheduler.request();scheduler.immediate();shadowOf(Looper.getMainLooper()).idle();assertEquals(1,count.get());
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(1,count.get());
 }
}
