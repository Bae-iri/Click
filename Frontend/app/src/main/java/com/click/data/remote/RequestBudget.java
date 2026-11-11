package com.click.data.remote;

import android.os.SystemClock;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.*;

/** One monotonic deadline covers queueing, contract/auth, and the actual request. */
final class RequestBudget implements AutoCloseable {
    static final long TOTAL_MS=35000L;
    private static final ThreadLocal<Long> END=new ThreadLocal<>();
    private static final ScheduledExecutorService WATCHDOG=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"click-http-deadline");t.setDaemon(true);return t;
    });
    private final Long previous;
    RequestBudget(long end){previous=END.get();END.set(end);}
    static int remaining(int maximum) throws IOException {
        Long end=END.get();long remaining=end==null?maximum:end-SystemClock.elapsedRealtime();
        if(remaining<=0)throw new IOException("REQUEST_DEADLINE_EXCEEDED");
        return (int)Math.max(1,Math.min(maximum,remaining));
    }
    static ScheduledFuture<?> watch(HttpURLConnection connection) throws IOException {
        int left=remaining((int)TOTAL_MS);
        return WATCHDOG.schedule(connection::disconnect,left,TimeUnit.MILLISECONDS);
    }
    static void configure(HttpURLConnection connection,int readTimeout) throws IOException {
        connection.setConnectTimeout(remaining(6000));
        connection.setReadTimeout(remaining(readTimeout));
        if(AgentTurnContract.PATH.equals(connection.getURL().getPath()) || "/api/v1/agent/turn".equals(connection.getURL().getPath())) {
            connection.setRequestProperty("X-Click-Execution-State",ExecutionState.version());
            connection.setRequestProperty("X-Click-Turn-Budget-Ms",String.valueOf(Math.max(100,remaining(22000)-2000)));
        }
    }
    @Override public void close(){if(previous==null)END.remove();else END.set(previous);}
}
