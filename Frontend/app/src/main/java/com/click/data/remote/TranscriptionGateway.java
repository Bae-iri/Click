package com.click.data.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends a short recording to our authenticated backend; private STT credentials stay server-side. */
public final class TranscriptionGateway {
    public interface Callback {
        void onSuccess(String text);
        void onFailure(Throwable error);
    }

    private static final String PATH = "/api/v1/speech/transcriptions";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AuthenticatedHttpClient http;
    private volatile int generation;

    public TranscriptionGateway(Context context) {
        http = new AuthenticatedHttpClient(context);
    }

    public void transcribe(byte[] wav, Callback callback) {
        final int token = ++generation;
        executor.execute(() -> {
            try {
                if (token != generation) return;
                // Check before sending any audio: the previously deployed API used external STT.
                HttpResult contract = AuthGateway.get("/openapi.json", null);
                if (contract.status < 200 || contract.status >= 300)
                    throw new java.io.IOException("PRIVATE_STT_UNAVAILABLE");
                try { PrivateSpeechContract.requireSelfHosted(new JSONObject(contract.body)); }
                catch (org.json.JSONException error) { throw new java.io.IOException("PRIVATE_STT_UNAVAILABLE"); }
                if (token != generation) return;
                JSONObject body = new JSONObject()
                        .put("audio_base64", Base64.encodeToString(wav, Base64.NO_WRAP))
                        .put("mime_type", "audio/wav")
                        .put("language", "ko");
                HttpResult result = http.post(PATH, body.toString(), 40000);
                JSONObject response = new JSONObject(result.body);
                if (!"self_hosted".equals(response.optString("provider"))
                        || !PrivateSpeechContract.MODEL.equals(response.optString("model")))
                    throw new java.io.IOException("PRIVATE_STT_UNAVAILABLE");
                String text = response.getString("text").trim();
                if (text.isEmpty()) throw new java.io.IOException("EMPTY_TRANSCRIPTION");
                main.post(() -> { if (token == generation) callback.onSuccess(text); });
            } catch (Throwable error) {
                main.post(() -> { if (token == generation) callback.onFailure(error); });
            }
        });
    }

    public void cancel() { generation++; }
    public void close() { cancel(); executor.shutdownNow(); }
}
