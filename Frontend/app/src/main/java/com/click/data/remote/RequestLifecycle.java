package com.click.data.remote;

import android.os.Handler;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** A gateway owns its connections and callbacks; closing it cannot cancel another gateway. */
final class RequestLifecycle implements AutoCloseable {
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();

    void check() throws IOException {
        if(closed.get() || Thread.currentThread().isInterrupted())throw new IOException("REQUEST_CANCELLED");
    }
    void attach(HttpURLConnection connection) throws IOException {
        check();connections.add(connection);
        if(closed.get()) {connections.remove(connection);connection.disconnect();check();}
    }
    void detach(HttpURLConnection connection) {connections.remove(connection);}
    void post(Handler handler,Runnable callback) {
        if(!closed.get())handler.post(()->{if(!closed.get())callback.run();});
    }
    @Override public void close() {
        closed.set(true);
        for(HttpURLConnection connection:connections)connection.disconnect();
        connections.clear();
    }
}
