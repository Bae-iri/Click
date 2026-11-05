package com.click.service.overlay;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DeviceSpeechInputTest {
    private MockedStatic<SpeechRecognizer> platform;
    private SpeechRecognizer recognizer;
    private SpeechInput.Callback callback;
    private DeviceSpeechInput input;

    @Before public void setup() {
        recognizer = mock(SpeechRecognizer.class);
        callback = mock(SpeechInput.Callback.class);
        platform = mockStatic(SpeechRecognizer.class);
        platform.when(() -> SpeechRecognizer.isRecognitionAvailable(any())).thenReturn(true);
        platform.when(() -> SpeechRecognizer.createSpeechRecognizer(any())).thenReturn(recognizer);
        input = new DeviceSpeechInput(RuntimeEnvironment.getApplication());
    }

    @After public void cleanup() { input.close(); platform.close(); }

    private RecognitionListener start() {
        input.start(callback);
        ArgumentCaptor<RecognitionListener> listener = ArgumentCaptor.forClass(RecognitionListener.class);
        verify(recognizer).setRecognitionListener(listener.capture());
        return listener.getValue();
    }

    private Bundle words(String... texts) {
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>(Arrays.asList(texts)));
        return bundle;
    }

    @Test public void koreanPartialAndFinalResultsUseAndroidService() {
        RecognitionListener listener = start();
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        verify(recognizer).startListening(intent.capture());
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.getValue().getAction());
        assertEquals("ko-KR", intent.getValue().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        assertTrue(intent.getValue().getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false));
        listener.onReadyForSpeech(new Bundle());
        listener.onPartialResults(words(" 글자 크기 "));
        listener.onEndOfSpeech();
        listener.onResults(words(" 글자 크기를 키워줘 "));
        verify(callback).onReady();
        verify(callback).onPartialText("글자 크기");
        verify(callback).onRecordingFinished();
        verify(callback).onSuccess("글자 크기를 키워줘");
        verify(recognizer).destroy();
        listener.onResults(words("duplicate"));
        verify(callback, times(1)).onSuccess(anyString());
    }

    @Test public void releaseStopsOnceAndWaitsForFinalResultEvenBeforeReady() {
        RecognitionListener listener = start();
        input.finish(); input.finish();
        listener.onReadyForSpeech(new Bundle());
        listener.onPartialResults(words("late partial"));
        listener.onEndOfSpeech();
        verify(recognizer).stopListening();
        verify(recognizer, never()).destroy();
        verify(callback).onRecordingFinished();
        verify(callback, never()).onReady();
        verify(callback, never()).onPartialText(anyString());
        listener.onResults(words("최종 결과"));
        verify(callback).onSuccess("최종 결과");
    }

    @Test public void cancellationDropsAllLateCallbacksAndTimers() {
        RecognitionListener listener = start();
        input.cancel();
        listener.onReadyForSpeech(new Bundle());
        listener.onPartialResults(words("partial"));
        listener.onEndOfSpeech();
        listener.onResults(words("final"));
        listener.onError(SpeechRecognizer.ERROR_CLIENT);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40));
        verifyNoInteractions(callback);
        verify(recognizer).cancel();
        verify(recognizer).destroy();
    }

    @Test public void newSessionIgnoresPreviousSessionResults() {
        RecognitionListener old = start();
        SpeechRecognizer next = mock(SpeechRecognizer.class);
        platform.when(() -> SpeechRecognizer.createSpeechRecognizer(any())).thenReturn(next);
        input.start(callback);
        old.onResults(words("old"));
        old.onError(SpeechRecognizer.ERROR_CLIENT);
        verifyNoInteractions(callback);
        ArgumentCaptor<RecognitionListener> listener = ArgumentCaptor.forClass(RecognitionListener.class);
        verify(next).setRecognitionListener(listener.capture());
        listener.getValue().onResults(words("new"));
        verify(callback).onSuccess("new");
    }

    @Test public void errorsReleaseRecognizerAndDoNotAutomaticallyRestart() {
        RecognitionListener listener = start();
        listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(callback).onFailure(error.capture());
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, ((DeviceSpeechInput.DeviceError) error.getValue()).code);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40));
        verify(recognizer, times(1)).startListening(any());
        verify(recognizer).destroy();
    }

    @Test public void missingRecognitionServiceFailsWithoutStartingMicrophone() {
        platform.when(() -> SpeechRecognizer.isRecognitionAvailable(any())).thenReturn(false);
        input.start(callback);
        verify(callback).onFailure(isA(DeviceSpeechInput.DeviceError.class));
        verifyNoInteractions(recognizer);
    }

    @Test public void emptyResultIsNotSubmitted() {
        start().onResults(words(null, "  "));
        verify(callback).onFailure(isA(DeviceSpeechInput.DeviceError.class));
        verify(callback, never()).onSuccess(anyString());
    }

    @Test public void missingFinalResultTimesOutAndReleasesMicrophone() {
        start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        verify(recognizer).stopListening();
        verify(callback).onRecordingFinished();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15));
        verify(callback).onFailure(isA(DeviceSpeechInput.DeviceError.class));
        verify(recognizer).destroy();
    }

    @Test public void closeDropsCallbacksAndPreventsAnotherStart() {
        RecognitionListener listener = start();
        input.close(); input.start(callback);
        listener.onResults(words("late"));
        verifyNoInteractions(callback);
        verify(recognizer, times(1)).startListening(any());
        verify(recognizer).destroy();
    }

    @Test public void cancellationInsideRecordingFinishedDoesNotSubmitResult() {
        RecognitionListener listener = start();
        doAnswer(call -> { input.cancel(); return null; }).when(callback).onRecordingFinished();
        listener.onResults(words("취소된 요청"));
        verify(callback, never()).onSuccess(anyString());
    }
}
