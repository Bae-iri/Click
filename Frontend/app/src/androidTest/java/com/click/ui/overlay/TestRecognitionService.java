package com.click.ui.overlay;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Collections;

/** Deterministic platform recognizer: no microphone capture or external server. */
public class TestRecognitionService extends RecognitionService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Override protected void onStartListening(Intent intent, Callback callback) {
        android.util.Log.i("ClickTestSpeech", "start");
        try { callback.readyForSpeech(new Bundle()); } catch (RemoteException e) { return; }
        handler.postDelayed(() -> {
            try { callback.partialResults(result("글자 크기")); } catch (RemoteException ignored) {}
        }, 700);
        handler.postDelayed(() -> {
            try { callback.results(result("글자 크기를 크게 해줘")); } catch (RemoteException ignored) {}
        }, 2600);
    }
    @Override protected void onCancel(Callback callback) { android.util.Log.i("ClickTestSpeech", "cancel"); handler.removeCallbacksAndMessages(null); }
    @Override protected void onStopListening(Callback callback) { onCancel(callback); }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    private Bundle result(String text) {
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,
                new ArrayList<>(Collections.singletonList(text)));
        return bundle;
    }
}
