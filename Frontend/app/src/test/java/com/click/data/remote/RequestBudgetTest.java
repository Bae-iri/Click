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
}
