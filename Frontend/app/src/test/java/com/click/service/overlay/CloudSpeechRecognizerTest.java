package com.click.service.overlay;

import android.app.Application;
import android.media.AudioRecord;
import android.os.Looper;
import com.click.data.remote.TranscriptionGateway;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CloudSpeechRecognizerTest {
    private TranscriptionGateway gateway;
    private SpeechInput.Callback callback;
    private CloudSpeechRecognizer speech;
    private Runnable capture;
    private MockedStatic<AudioRecord> audio;
    private MockedConstruction<AudioRecord> recorders;
    private java.util.function.IntSupplier nextRead;
    private int reads;
    private boolean silentSecondRead;

    @Before public void setup() {
        gateway = mock(TranscriptionGateway.class);
        callback = mock(SpeechInput.Callback.class);
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(call -> { capture = call.getArgument(0); return null; }).when(executor).execute(any());
        speech = new CloudSpeechRecognizer(gateway, executor);
        audio = mockStatic(AudioRecord.class);
        audio.when(() -> AudioRecord.getMinBufferSize(anyInt(), anyInt(), anyInt())).thenReturn(4096);
        recorders = mockConstruction(AudioRecord.class, (recorder, context) -> {
            when(recorder.getState()).thenReturn(AudioRecord.STATE_INITIALIZED);
            when(recorder.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(call -> {
                reads++;
                byte[] data = call.getArgument(0);
                data[0] = (byte) 0xe8; data[1] = 3; // Synthetic PCM sample, not microphone audio.
                if (silentSecondRead && reads == 2) { data[0] = 0; data[1] = 0; }
                return nextRead.getAsInt();
            });
        });
    }

    @After public void cleanup() {
        speech.close();
        recorders.close();
        audio.close();
    }

    @Test public void pausesWhileHeldDoNotSubmitAndReleaseTranscribesOnce() {
        silentSecondRead = true;
        nextRead = () -> {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3));
            if (reads == 3) speech.finish();
            verify(gateway, never()).transcribe(any(), any());
            return 2;
        };
        speech.start(callback); capture.run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(3, reads);
        verify(recorders.constructed().get(0)).release();
        ArgumentCaptor<byte[]> wav = ArgumentCaptor.forClass(byte[].class);
        verify(gateway).transcribe(wav.capture(), any());
        assertEquals(44 + 6, wav.getValue().length);
        verify(callback).onRecordingFinished();
        verify(callback, never()).onFailure(any());
        speech.finish();
        shadowOf(Looper.getMainLooper()).idle();
        verify(gateway, times(1)).transcribe(any(), any());
    }

    @Test public void releaseUnblockingReadPreservesAlreadyRecordedAudio() {
        nextRead = () -> {
            if (reads == 1) return 2;
            speech.finish(); return AudioRecord.ERROR_INVALID_OPERATION;
        };
        speech.start(callback); capture.run();
        shadowOf(Looper.getMainLooper()).idle();
        verify(gateway).transcribe(any(), any());
        verify(callback, never()).onFailure(any());
    }

    @Test public void quickReleaseBeforeRecorderStartsNeverOpensMicrophone() {
        speech.start(callback); speech.finish(); capture.run();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(recorders.constructed().isEmpty());
        verify(gateway, never()).transcribe(any(), any());
        verify(callback).onFailure(any());
    }

    @Test public void cancelWhileRecordingDiscardsAudio() {
        nextRead = () -> { speech.cancel(); return 2; };
        speech.start(callback); capture.run();
        shadowOf(Looper.getMainLooper()).idle();
        verify(recorders.constructed().get(0)).release();
        verify(gateway, never()).transcribe(any(), any());
        verifyNoInteractions(callback);
    }

    @Test public void cancelAfterReleaseBeforeUploadStillDiscardsAudio() {
        nextRead = () -> { speech.finish(); return 2; };
        speech.start(callback); capture.run(); speech.cancel();
        shadowOf(Looper.getMainLooper()).idle();
        verify(gateway, never()).transcribe(any(), any());
        verifyNoInteractions(callback);
    }

    @Test public void durationLimitClosesMicrophoneEvenIfFingerRemainsDown() {
        nextRead = () -> { ShadowSystemClock.advanceBy(Duration.ofSeconds(16)); return 2; };
        speech.start(callback); capture.run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, reads);
        verify(recorders.constructed().get(0)).release();
        verify(callback).onRecordingFinished();
        verify(gateway).transcribe(any(), any());
    }
}
