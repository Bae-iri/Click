package com.click.ui.overlay;

import android.app.Application;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import com.click.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class HoldToTalkTest {
    private ClickOverlayLayout control;
    private ClickOverlayLayout.Callback callback;
    private View microphone;

    @Before public void setup() {
        control = new ClickOverlayLayout(RuntimeEnvironment.getApplication());
        callback = mock(ClickOverlayLayout.Callback.class);
        control.setCallback(callback);
        control.openVoice();
        control.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY));
        control.layout(0, 0, 960, 1500);
        microphone = control.findViewById(R.id.overlay_microphone);
    }

    @Test public void openingDoesNotRecordAndHoldingOnlyFinishesOnRelease() {
        verify(callback, never()).onMicrophonePressed();
        touch(MotionEvent.ACTION_DOWN, 10, 10);
        verify(callback).onMicrophonePressed();
        touch(MotionEvent.ACTION_MOVE, 12, 12);
        verify(callback, never()).onMicrophoneReleased();
        touch(MotionEvent.ACTION_UP, 12, 12);
        verify(callback).onMicrophoneReleased();
        verify(callback, never()).onMicrophoneClicked();
        verify(callback, never()).onMicrophoneCancelled();
        assertFalse(microphone.isPressed());
    }

    @Test public void movingOutsideCancelsAndComingBackDoesNotSubmit() {
        touch(MotionEvent.ACTION_DOWN, 10, 10);
        touch(MotionEvent.ACTION_MOVE, -200, 10);
        touch(MotionEvent.ACTION_MOVE, 10, 10);
        touch(MotionEvent.ACTION_UP, 10, 10);
        verify(callback).onMicrophoneCancelled();
        verify(callback, never()).onMicrophoneReleased();
    }

    @Test public void interruptedTouchAndSecondFingerCancelWithoutSubmitting() {
        for (int event : new int[]{MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN}) {
            clearInvocations(callback);
            touch(MotionEvent.ACTION_DOWN, 10, 10);
            touch(event, 10, 10);
            touch(MotionEvent.ACTION_UP, 10, 10);
            verify(callback).onMicrophoneCancelled();
            verify(callback, never()).onMicrophoneReleased();
        }
    }

    @Test public void closingPanelCancelsHeldRecording() {
        touch(MotionEvent.ACTION_DOWN, 10, 10);
        control.openText();
        verify(callback).onMicrophoneCancelled();
        touch(MotionEvent.ACTION_UP, 10, 10);
        verify(callback, never()).onMicrophoneReleased();
    }

    @Test public void conversionDisablesAnotherRecordingAndCanRecoverAfterFailure() {
        touch(MotionEvent.ACTION_DOWN, 10, 10);
        control.setVoiceInputActive(true);
        // The duration limit may finish recording before the finger is lifted.
        control.setVoiceInputActive(false);
        control.setVoiceInputProcessing(true);
        assertFalse(microphone.isEnabled());
        touch(MotionEvent.ACTION_UP, 10, 10);
        touch(MotionEvent.ACTION_DOWN, 10, 10);
        verify(callback, times(1)).onMicrophonePressed();
        control.setVoiceInputProcessing(false);
        touch(MotionEvent.ACTION_DOWN, 10, 10);
        touch(MotionEvent.ACTION_UP, 10, 10);
        verify(callback, times(2)).onMicrophonePressed();
        verify(callback).onMicrophoneReleased();
    }

    @Test public void accessibilityClickRemainsAvailableWithoutHolding() {
        microphone.performClick();
        verify(callback).onMicrophoneClicked();
        verify(callback, never()).onMicrophonePressed();
    }

    @Test public void holdingTheCardAwayFromTheIconAndMovingWithinItStillSubmits() {
        assertTrue("The whole blue card owns press/release", microphone instanceof ViewGroup);
        float x = microphone.getWidth() - 12;
        float y = microphone.getHeight() - 12;
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_MOVE, 12, 12);
        touch(MotionEvent.ACTION_UP, 12, 12);
        verify(callback).onMicrophonePressed();
        verify(callback).onMicrophoneReleased();
        verify(callback, never()).onMicrophoneCancelled();
    }

    @Test public void stopButtonDoesNotStartRecordingEvenThoughItIsInsideTheCard() {
        control.setRequestStatus("진행 중", true);
        control.findViewById(R.id.overlay_stop_request).performClick();
        verify(callback).onStopRequested();
        verify(callback, never()).onMicrophonePressed();
    }

    private void touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        microphone.dispatchTouchEvent(event);
        event.recycle();
    }
}
