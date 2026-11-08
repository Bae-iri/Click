package com.click.service.overlay;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.IOException;
import java.util.ArrayList;

/** One Korean utterance through Android's on-device recognizer; no remote fallback. */
public final class DeviceSpeechInput implements SpeechInput {
    private static final long MAX_LISTENING_MS = 16_000L;
    private static final long RESULT_TIMEOUT_MS = 15_000L;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private boolean closed;

    public DeviceSpeechInput(Context context) { this.context = context.getApplicationContext(); }

    @Override public void start(Callback callback) {
        onMain(() -> {
            if (closed) return;
            cancelCurrent();
            Session next = new Session(callback);
            session = next;
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    next.fail(new DeviceError(DeviceError.ON_DEVICE_UNAVAILABLE));
                    return;
                }
                next.recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                next.recognizer.setRecognitionListener(next);
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, MAX_LISTENING_MS)
                        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, MAX_LISTENING_MS);
                main.postDelayed(next.listeningTimeout, MAX_LISTENING_MS);
                next.recognizer.startListening(intent);
            } catch (RuntimeException error) {
                next.fail(error);
            }
        });
    }

    @Override public void finish() { onMain(() -> { if (session != null) session.finish(); }); }
    @Override public void cancel() { onMain(this::cancelCurrent); }
    @Override public void close() { onMain(() -> { closed = true; cancelCurrent(); }); }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private void cancelCurrent() {
        Session previous = session;
        session = null;
        if (previous != null) previous.release();
    }

    private final class Session implements RecognitionListener {
        private final Callback callback;
        private SpeechRecognizer recognizer;
        private boolean recordingFinished;
        private final Runnable listeningTimeout = this::finish;
        private final Runnable resultTimeout = () -> fail(new DeviceError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT));

        Session(Callback callback) { this.callback = callback; }
        private boolean active() { return session == this && !closed; }

        private void recordingFinished() {
            if (!active() || recordingFinished) return;
            recordingFinished = true;
            main.removeCallbacks(listeningTimeout);
            main.postDelayed(resultTimeout, RESULT_TIMEOUT_MS);
            callback.onRecordingFinished();
        }

        private void finish() {
            if (!active() || recordingFinished) return;
            recordingFinished();
            if (!active()) return;
            try { recognizer.stopListening(); }
            catch (RuntimeException error) { fail(error); }
        }

        private void fail(Throwable error) {
            if (!active()) return;
            cancelCurrent();
            callback.onFailure(error);
        }

        private void release() {
            main.removeCallbacks(listeningTimeout);
            main.removeCallbacks(resultTimeout);
            if (recognizer == null) return;
            try { recognizer.cancel(); } catch (RuntimeException ignored) { }
            try { recognizer.destroy(); } catch (RuntimeException ignored) { }
            recognizer = null;
        }

        @Override public void onReadyForSpeech(Bundle params) {
            if (active() && !recordingFinished) callback.onReady();
        }
        @Override public void onEndOfSpeech() { recordingFinished(); }
        @Override public void onError(int error) { fail(new DeviceError(error)); }
        @Override public void onResults(Bundle results) {
            if (!active()) return;
            String text = firstText(results);
            if (text.isEmpty()) { fail(new DeviceError(SpeechRecognizer.ERROR_NO_MATCH)); return; }
            recordingFinished();
            if (!active()) return;
            cancelCurrent();
            callback.onSuccess(text);
        }
        @Override public void onPartialResults(Bundle results) {
            if (!active() || recordingFinished) return;
            String text = firstText(results);
            if (!text.isEmpty()) callback.onPartialText(text);
        }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEvent(int eventType, Bundle params) { }
    }

    private static String firstText(Bundle results) {
        ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null) for (String match : matches)
            if (match != null && !match.trim().isEmpty()) return match.trim();
        return "";
    }

    public static final class DeviceError extends IOException {
        static final int ON_DEVICE_UNAVAILABLE = -1;
        public final int code;
        DeviceError(int code) { super("DEVICE_SPEECH_" + code); this.code = code; }
    }
}
