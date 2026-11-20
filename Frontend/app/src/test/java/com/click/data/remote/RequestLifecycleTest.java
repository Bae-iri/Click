package com.click.data.remote;

import android.app.Application;
import android.os.*;
import java.net.HttpURLConnection;
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
public class RequestLifecycleTest {
    @Test public void closeDisconnectsOnlyOwnedConnectionsAndDropsQueuedCallbacks() throws Exception {
        var first=new RequestLifecycle();var second=new RequestLifecycle();
        var a=mock(HttpURLConnection.class);var b=mock(HttpURLConnection.class);
        first.attach(a);second.attach(b);
        Runnable callback=mock(Runnable.class);
        first.post(new Handler(Looper.getMainLooper()),callback);
        first.close();shadowOf(Looper.getMainLooper()).idle();
        verify(a).disconnect();verify(b,never()).disconnect();verifyNoInteractions(callback);
        try{first.attach(mock(HttpURLConnection.class));fail();}catch(java.io.IOException expected){}
        second.close();verify(b).disconnect();
    }
}
