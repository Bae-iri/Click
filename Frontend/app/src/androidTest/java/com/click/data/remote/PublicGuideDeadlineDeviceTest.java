package com.click.data.remote;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real native HTTP/watchdog regression. Loopback only; no account or provider request. */
@RunWith(AndroidJUnit4.class)
public class PublicGuideDeadlineDeviceTest {
    @Test public void responseAfterTheOld35SecondCutoffStillArrivesWithinThePublicGuideBudget() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("deadlineProbe")));
        ExecutorService peer=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            Future<?> served=peer.submit(()->{
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    for(String line;(line=input.readLine())!=null&&!line.isEmpty();){}
                    Thread.sleep(36000);
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch(Exception error){throw new RuntimeException(error);}
            });
            long started=SystemClock.elapsedRealtime();
            HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+server.getLocalPort()+"/probe").openConnection();
            try(RequestBudget budget=new RequestBudget(started+RequestBudget.PUBLIC_GUIDE_TOTAL_MS)) {
                RequestBudget.configure(connection,RequestBudget.PUBLIC_GUIDE_READ_MS);
                ScheduledFuture<?> watchdog=RequestBudget.watch(connection);
                try {
                    assertEquals(200,connection.getResponseCode());
                    assertEquals("OK",new String(connection.getInputStream().readAllBytes(),StandardCharsets.US_ASCII));
                    long elapsed=SystemClock.elapsedRealtime()-started;
                    assertTrue(elapsed>=36000 && elapsed<RequestBudget.PUBLIC_GUIDE_TOTAL_MS);
                    Bundle evidence=new Bundle();evidence.putLong("public_guide_http_elapsed_ms",elapsed);
                    evidence.putString("scope","loopback_no_auth_no_model");
                    InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
                } finally {watchdog.cancel(false);RequestBudget.finished(connection);connection.disconnect();}
            }
            served.get(5,TimeUnit.SECONDS);
        } finally {peer.shutdownNow();}
    }
}
