package com.click.designpreview;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Wait for the actual connection after returning from Settings; stop when the screen leaves. */
public final class AccessibilityConnectionWatcher {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;

    public AccessibilityConnectionWatcher(Activity activity) { this.activity = activity; }

    public void start(Runnable connected, Runnable unavailable) {
        stop();
        ClickHost host = ClickHost.from(activity);
        if (!host.accessibilityEnabled()) { unavailable.run(); return; }
        if (host.accessibilityConnected()) { connected.run(); return; }
        long deadline = SystemClock.elapsedRealtime() + 8000L;
        pending = new Runnable() {
            @Override public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) { stop(); return; }
                if (host.accessibilityEnabled() && host.accessibilityConnected()) {
                    stop(); connected.run();
                } else if (!host.accessibilityEnabled() || SystemClock.elapsedRealtime() >= deadline) {
                    stop(); unavailable.run();
                } else handler.postDelayed(this, 250L);
            }
        };
        handler.post(pending);
    }

    public void stop() {
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
    }
}
