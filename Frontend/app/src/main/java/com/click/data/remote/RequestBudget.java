package com.click.data.remote;

import android.os.SystemClock;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.*;

/** One monotonic deadline covers queueing, contract/auth, and the actual request. */
final class RequestBudget implements AutoCloseable {
    static final long TOTAL_MS=35000L;
    // One public-guide operation includes contract discovery/authentication plus
    // the existing 45s model response allowance. Queueing spends this deadline too.
    static final long PUBLIC_GUIDE_TOTAL_MS=60000L;
    static final int PUBLIC_GUIDE_READ_MS=45000;
    private static final ThreadLocal<Long> END=new ThreadLocal<>();
    private static final ThreadLocal<RequestLifecycle> OWNER=new ThreadLocal<>();
    private static final ScheduledExecutorService WATCHDOG=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"click-http-deadline");t.setDaemon(true);return t;
    });
    private final Long previous;
    private final RequestLifecycle previousOwner;
    RequestBudget(long end){this(end,OWNER.get());}
    RequestBudget(long end,RequestLifecycle owner){
        previous=END.get();previousOwner=OWNER.get();
        END.set(previous==null?end:Math.min(previous,end));
        if(owner!=null)OWNER.set(owner);
    }
    static RequestBudget ifAbsent() {
        Long end=END.get();
        return new RequestBudget(end==null?SystemClock.elapsedRealtime()+TOTAL_MS:end);
    }
    static int remaining(int maximum) throws IOException {
        if(OWNER.get()!=null)OWNER.get().check();
        Long end=END.get();long remaining=end==null?maximum:end-SystemClock.elapsedRealtime();
        if(remaining<=0)throw new IOException("REQUEST_DEADLINE_EXCEEDED");
        return (int)Math.max(1,Math.min(maximum,remaining));
    }
    static ScheduledFuture<?> watch(HttpURLConnection connection) throws IOException {
        int left=remaining(END.get()==null?(int)TOTAL_MS:Integer.MAX_VALUE);
        if(OWNER.get()!=null)OWNER.get().attach(connection);
        return WATCHDOG.schedule(connection::disconnect,left,TimeUnit.MILLISECONDS);
    }
    static void finished(HttpURLConnection connection) {
        if(OWNER.get()!=null)OWNER.get().detach(connection);
    }
    static void configure(HttpURLConnection connection,int readTimeout) throws IOException {
        connection.setConnectTimeout(remaining(6000));
        connection.setReadTimeout(remaining(readTimeout));
        if(AgentTurnContract.PATH.equals(connection.getURL().getPath()) || "/api/v1/agent/turn".equals(connection.getURL().getPath())) {
            connection.setRequestProperty("X-Click-Execution-State",ExecutionState.version());
            connection.setRequestProperty("X-Click-Turn-Budget-Ms",String.valueOf(Math.max(100,remaining(22000)-2000)));
        }
    }
    @Override public void close(){
        if(previous==null)END.remove();else END.set(previous);
        if(previousOwner==null)OWNER.remove();else OWNER.set(previousOwner);
    }
}
