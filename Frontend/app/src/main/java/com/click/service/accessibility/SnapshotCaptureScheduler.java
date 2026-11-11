package com.click.service.accessibility;

import android.os.Handler;
import android.os.SystemClock;

/** Debounce bursts without starving observations during continuous UI events. */
final class SnapshotCaptureScheduler {
    static final long QUIET_MS=300,MAX_WAIT_MS=1000;
    private final Handler handler;
    private final Runnable capture;
    private long first=-1;
    private final Runnable dispatch=this::dispatch;
    private void dispatch(){first=-1;capture.run();}
    SnapshotCaptureScheduler(Handler handler,Runnable capture){this.handler=handler;this.capture=capture;}
    void request(){
        long now=SystemClock.uptimeMillis();if(first<0)first=now;
        handler.removeCallbacks(dispatch);
        handler.postAtTime(dispatch,Math.min(now+QUIET_MS,first+MAX_WAIT_MS));
    }
    void immediate(){cancel();handler.post(dispatch);}
    void cancel(){handler.removeCallbacks(dispatch);first=-1;}
}
