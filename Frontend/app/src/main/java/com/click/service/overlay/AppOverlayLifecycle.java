package com.click.service.overlay;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Returning to any Click activity ends external guidance. Overlay windows are not activities. */
public final class AppOverlayLifecycle implements Application.ActivityLifecycleCallbacks {
    @Override public void onActivityResumed(Activity activity) {
        ClickOverlayService.stopForAppEntry();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
