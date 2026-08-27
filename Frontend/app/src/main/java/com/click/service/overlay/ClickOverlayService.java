package com.click.service.overlay;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.click.ui.overlay.ClickOverlayLayout;

public final class ClickOverlayService extends Service implements ClickOverlayLayout.Callback {
    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private ClickOverlayLayout overlayView;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager = getSystemService(WindowManager.class);
        overlayView = new ClickOverlayLayout(this);
        overlayView.setCallback(this);

        windowParams = new WindowManager.LayoutParams(
                dp(64),
                dp(64),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        windowParams.gravity = Gravity.END | Gravity.BOTTOM;
        windowParams.x = dp(12);
        windowParams.y = dp(24);
        windowManager.addView(overlayView, windowParams);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onModeChanged(ClickOverlayLayout.Mode mode) {
        if (windowManager == null || windowParams == null) {
            return;
        }

        if (mode == ClickOverlayLayout.Mode.COLLAPSED) {
            windowParams.width = dp(64);
            windowParams.height = dp(64);
            windowParams.gravity = Gravity.END | Gravity.BOTTOM;
            windowParams.x = dp(12);
        } else if (mode == ClickOverlayLayout.Mode.VOICE) {
            windowParams.width = dp(218);
            windowParams.height = dp(82);
            windowParams.gravity = Gravity.END | Gravity.BOTTOM;
            windowParams.x = dp(12);
        } else {
            windowParams.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(360));
            windowParams.height = dp(68);
            windowParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            windowParams.x = 0;
        }
        windowParams.y = dp(24);
        updateFocusableFlag(mode == ClickOverlayLayout.Mode.TEXT);
        windowManager.updateViewLayout(overlayView, windowParams);
    }

    @Override
    public void onDragBy(int deltaX, int deltaY) {
        if (windowParams == null || overlayView.getMode() != ClickOverlayLayout.Mode.COLLAPSED) {
            return;
        }
        windowParams.x = Math.max(0, windowParams.x - deltaX);
        windowParams.y = Math.max(0, windowParams.y - deltaY);
        windowManager.updateViewLayout(overlayView, windowParams);
    }

    @Override
    public void onCloseRequested() {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (windowManager != null && overlayView != null && overlayView.isAttachedToWindow()) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        windowManager = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateFocusableFlag(boolean focusable) {
        if (focusable) {
            windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
