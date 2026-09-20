package com.click.data.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends a short recording to the backend; the OpenAI key never enters the Android app. */
public final class TranscriptionGateway {
    public interface Callback {
        void onSuccess(String text);
        void onFailure(Throwable error);
    }

    private static final String PATH = "/api/v1/speech/transcriptions";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AuthenticatedHttpClient http;

    public TranscriptionGateway(Context context) {
        http = new AuthenticatedHttpClient(context);
    }

    public void transcribe(byte[] wav, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("audio_base64", Base64.encodeToString(wav, Base64.NO_WRAP))
                        .put("mime_type", "audio/wav")
                        .put("language", "ko");
                HttpResult result = http.post(PATH, body.toString());
                String text = new JSONObject(result.body).getString("text").trim();
                if (text.isEmpty()) throw new java.io.IOException("EMPTY_TRANSCRIPTION");
                main.post(() -> callback.onSuccess(text));
            } catch (Throwable error) {
                main.post(() -> callback.onFailure(error));
            }
        });
    }

    public void close() { executor.shutdownNow(); }
}
