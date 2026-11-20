package com.click.data.remote;

import android.app.Application;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppGuideQueueBudgetTest {
    @Test public void aRequestThatAlreadySpentItsBudgetInTheQueueNeverStartsAnotherHttpWait() throws Exception {
        try(var tokens=mockConstruction(com.click.data.local.AuthTokenStore.class)) {
            var gateway=new AppGuideGateway(RuntimeEnvironment.getApplication());
            var http=mock(AuthenticatedHttpClient.class);var executor=mock(ExecutorService.class);
            var queued=new ArrayList<Runnable>();doAnswer(call->{queued.add(call.getArgument(0));return null;}).when(executor).execute(any());
            for(var entry:java.util.Map.of("http",http,"executor",executor).entrySet()) {
                var field=AppGuideGateway.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(gateway,entry.getValue());
            }
            var callback=mock(AppGuideGateway.Callback.class);
            gateway.send(new JSONObject().put("protocol_version","app-task.v2"),callback);
            ShadowSystemClock.advanceBy(Duration.ofSeconds(16));queued.get(0).run();shadowOf(Looper.getMainLooper()).idle();
            verifyNoInteractions(http);verify(callback).failure(any(java.io.IOException.class));verify(callback,never()).success(any());
            gateway.close();
        }
    }
}
