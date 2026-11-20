package com.click.service.overlay;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.click.data.snapshot.ScreenSnapshot;

/** One local app launch followed by foreground verification, shared by guidance modes. */
public final class AppEntryController implements AutoCloseable {
    public enum Launch { STARTED, UNAVAILABLE, FAILED, LOCKED }
    public interface Listener {
        boolean canProceed();
        ScreenSnapshot freshScreen();
        Launch launch();
        void waiting();
        void recovery(String message, Runnable retry);
        void entered();
    }

    private static final long TIMEOUT_MS = 5000L;
    private final String packageName, label;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recheck = this::check;
    private boolean closed, completed, opening, paused;
    private long deadline, remaining;

    public AppEntryController(String packageName, String label, Listener listener) {
        if (packageName == null || packageName.isBlank()) throw new IllegalArgumentException("Missing app package");
        this.packageName = packageName;
        this.label = label;
        this.listener = listener;
    }

    /** Resolve a launchable installed package locally; no model or network request is needed. */
    public static Launch launch(Context context, String packageName) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isDeviceLocked()) return Launch.LOCKED;
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) return Launch.UNAVAILABLE;
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return Launch.STARTED;
        } catch (RuntimeException unavailable) {
            return Launch.FAILED;
        }
    }

    public void start() { open(); }

    private boolean active() { return !closed && !completed && !paused && listener.canProceed(); }

    private boolean reached(ScreenSnapshot screen) {
        return screen != null && screen.activeWindowId != null && packageName.equals(screen.packageName);
    }

    private void open() {
        if (!active() || opening) return;
        if (reached(listener.freshScreen())) { complete(); return; }
        Launch result = listener.launch();
        if (result != Launch.STARTED) {
            recover(result == Launch.LOCKED ? "화면 잠금을 해제한 뒤 다시 시도해 주세요."
                    : result == Launch.UNAVAILABLE ? label + " 앱의 실행 경로를 찾지 못했어요. 앱이 설치되어 있는지 확인해 주세요."
                    : label + " 앱을 열지 못했어요. 다시 시도해 주세요.");
            return;
        }
        opening = true;
        deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        listener.waiting();
        check();
    }

    public void screenChanged(ScreenSnapshot screen) {
        if (!active()) return;
        if (reached(screen)) complete();
        else if (opening && SystemClock.uptimeMillis() >= deadline)
            recover(label + " 앱으로 이동한 것을 확인하지 못했어요. 다시 열거나 직접 이동해 주세요.");
    }

    private void check() {
        handler.removeCallbacks(recheck);
        if (!active() || !opening) return;
        screenChanged(listener.freshScreen());
        if (active() && opening) handler.postDelayed(recheck, 250L);
    }

    private void complete() {
        completed = true;
        opening = false;
        handler.removeCallbacks(recheck);
        listener.entered();
    }

    private void recover(String message) {
        opening = false;
        handler.removeCallbacks(recheck);
        listener.recovery(message, this::open);
    }

    public void pause() {
        paused = true;
        remaining = Math.max(0, deadline - SystemClock.uptimeMillis());
        handler.removeCallbacks(recheck);
    }

    public void resume() {
        if (closed || completed) return;
        paused = false;
        if (opening) {
            deadline = SystemClock.uptimeMillis() + remaining;
            listener.waiting();
            check();
        } else if (active()) {
            if (reached(listener.freshScreen())) complete();
            else recover(label + " 앱을 열고 안내를 이어갈게요.");
        }
    }

    @Override public void close() {
        closed = true;
        handler.removeCallbacks(recheck);
    }
}
