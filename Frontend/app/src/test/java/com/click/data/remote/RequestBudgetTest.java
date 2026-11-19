package com.click.data.remote;
import android.app.Application;
import android.os.*;
import java.net.*;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RequestBudgetTest {
 @Test public void queueAndEarlierHttpCallsSpendTheSameMonotonicDeadline() throws Exception {
  try(var budget=new RequestBudget(SystemClock.elapsedRealtime()+1000)) {
   assertEquals(1000,RequestBudget.remaining(25000));
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));assertEquals(200,RequestBudget.remaining(25000));
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(201));
   try {RequestBudget.remaining(25000);fail();}catch(java.io.IOException expected){}
  }
  assertEquals(25000,RequestBudget.remaining(25000));
 }
 @Test public void serverBudgetLeavesTransportReserveAndCannotExceedServerLimit() throws Exception {
  var connection=mock(HttpURLConnection.class);when(connection.getURL()).thenReturn(new URL("https://example.test"+AgentTurnContract.PATH));
  try(var budget=new RequestBudget(SystemClock.elapsedRealtime()+35000)) {
   RequestBudget.configure(connection,25000);
   verify(connection).setReadTimeout(25000);verify(connection).setConnectTimeout(6000);
   verify(connection).setRequestProperty("X-Click-Turn-Budget-Ms","20000");
  }
 }
 @Test public void longGovernmentReadIsNotCutOffByTheDefaultWatchdog() throws Exception {
  var connection=mock(HttpURLConnection.class);
  when(connection.getURL()).thenReturn(new URL("https://example.test/api/v1/public-guide/commands"));
  when(connection.getConnectTimeout()).thenReturn(6000);
  when(connection.getReadTimeout()).thenReturn(RequestBudget.PUBLIC_GUIDE_READ_MS);
  try(var budget=new RequestBudget(SystemClock.elapsedRealtime()+RequestBudget.PUBLIC_GUIDE_TOTAL_MS)) {
   RequestBudget.configure(connection,RequestBudget.PUBLIC_GUIDE_READ_MS);
   var watch=RequestBudget.watch(connection);
   try {assertTrue(watch.getDelay(java.util.concurrent.TimeUnit.MILLISECONDS)>44000);}
   finally {watch.cancel(false);}
   verify(connection).setReadTimeout(45000);
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50000));
   assertEquals(40000,RequestBudget.remaining(45000));
  }
 }
 @Test public void nestedCallsCannotExtendTheOriginalDeadline() throws Exception {
  try(var outer=new RequestBudget(SystemClock.elapsedRealtime()+1000)) {
   try(var inner=new RequestBudget(SystemClock.elapsedRealtime()+90000)) {
    assertEquals(1000,RequestBudget.remaining(45000));
   }
   assertEquals(1000,RequestBudget.remaining(45000));
  }
 }
}
