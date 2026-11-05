package com.click.ui.overlay;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ClickOverlayGestureTest {
    @Test public void releaseWithoutPressDoesNotOpenClick() {
        ClickOverlayLayout view = view();
        touch(view, MotionEvent.ACTION_UP);
        assertEquals(ClickOverlayLayout.Mode.COLLAPSED, view.getMode());
    }

    @Test public void changingPanelsCancelsPreviousPressBeforeItsLateRelease() {
        ClickOverlayLayout view = view();
        ClickOverlayLayout.Callback callback = mock(ClickOverlayLayout.Callback.class);
        view.setCallback(callback);
        touch(view, MotionEvent.ACTION_DOWN);
        view.openText();
        view.collapse();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        touch(view, MotionEvent.ACTION_UP);
        verify(callback, never()).onDragStarted();
        verify(callback, never()).onDragEnded();
        assertEquals(ClickOverlayLayout.Mode.COLLAPSED, view.getMode());
        // A fresh tap still works after the cancelled gesture.
        touch(view, MotionEvent.ACTION_DOWN);
        touch(view, MotionEvent.ACTION_UP);
        assertEquals(ClickOverlayLayout.Mode.MENU, view.getMode());
    }

    private ClickOverlayLayout view() {
        ClickOverlayLayout view = new ClickOverlayLayout(RuntimeEnvironment.getApplication());
        int size = Math.round(64 * view.getResources().getDisplayMetrics().density);
        view.layout(0, 0, size, size);
        return view;
    }

    private void touch(ClickOverlayLayout view, int action) {
        float center = 32 * view.getResources().getDisplayMetrics().density;
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, center, center, 0);
        view.onTouchEvent(event);
        event.recycle();
    }
}
