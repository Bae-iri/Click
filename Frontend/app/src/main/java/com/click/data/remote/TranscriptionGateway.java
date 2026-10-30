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
    private final ExecutorService executor;
    private java.util.concurrent.FutureTask<Void> contractPreparation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AuthenticatedHttpClient http;
    private volatile int generation;

    public TranscriptionGateway(Context context) {
        this(new AuthenticatedHttpClient(context), Executors.newSingleThreadExecutor());
    }

    TranscriptionGateway(AuthenticatedHttpClient http, ExecutorService executor) {
        this.http = http;
        this.executor = executor;
    }

    /** One fresh contract check per utterance; no cross-utterance trust cache. */
    public synchronized void prepare() {
        if (contractPreparation != null) contractPreparation.cancel(false);
        generation++;
        contractPreparation = new java.util.concurrent.FutureTask<>(() -> { requirePrivateContract(); return null; });
        executor.execute(contractPreparation);
    }

    private void requirePrivateContract() throws Exception {
        HttpResult contract = AuthGateway.get("/openapi.json", null);
        if (contract.status < 200 || contract.status >= 300)
            throw new java.io.IOException("PRIVATE_STT_UNAVAILABLE");
        try { PrivateSpeechContract.requireSelfHosted(new JSONObject(contract.body)); }
        catch (org.json.JSONException error) { throw new java.io.IOException("PRIVATE_STT_UNAVAILABLE"); }
    }

    public synchronized void transcribe(byte[] wav, Callback callback) {
        final int token = ++generation;
        final java.util.concurrent.FutureTask<Void> preparation = contractPreparation;
        contractPreparation = null;
        final long contractStarted = android.os.SystemClock.elapsedRealtime();
        executor.execute(() -> {
            try {
                if (token != generation) return;
                // The check may have completed during recording. It still must succeed before audio upload.
                if (preparation == null) requirePrivateContract();
                else {
                    try { preparation.get(); }
                    catch (java.util.concurrent.ExecutionException error) {
                        Throwable cause = error.getCause();
                        if (cause instanceof Exception) throw (Exception) cause;
                        throw new java.io.IOException("PRIVATE_STT_UNAVAILABLE");
                    }
                }
                if (token != generation) return;
                android.util.Log.d("ClickSTT", "contract_wait_ms=" + (android.os.SystemClock.elapsedRealtime() - contractStarted));
                JSONObject body = new JSONObject()
                        .put("audio_base64", Base64.encodeToString(wav, Base64.NO_WRAP))
                        .put("mime_type", "audio/wav")
                        .put("language", "ko");
                long requestStarted = android.os.SystemClock.elapsedRealtime();
                HttpResult result = http.post(PATH, body.toString(), 40000);
                android.util.Log.d("ClickSTT", "request_ms=" + (android.os.SystemClock.elapsedRealtime() - requestStarted));
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

    public synchronized void cancel() {
        generation++;
        if (contractPreparation != null) contractPreparation.cancel(false);
        contractPreparation = null;
    }
    public void close() { cancel(); executor.shutdownNow(); }
}
