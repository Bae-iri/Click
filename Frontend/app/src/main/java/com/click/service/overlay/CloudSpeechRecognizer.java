package com.click.service.overlay;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.click.data.remote.TranscriptionGateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Records short PCM WAV in memory and sends it to our backend/private Whisper STT. */
public final class CloudSpeechRecognizer implements SpeechInput {
    private static final int SAMPLE_RATE = 16_000;
    private static final int SPEECH_RMS_THRESHOLD = 300;
    private static final long MAX_RECORDING_MS = 16_000L;

    private final ExecutorService recorderExecutor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TranscriptionGateway gateway;
    private volatile AudioRecord activeRecorder;
    private volatile int generation;
    private volatile int finishedGeneration = -1;
    private final Object recorderLock = new Object();

    public CloudSpeechRecognizer(Context context) {
        this(new TranscriptionGateway(context.getApplicationContext()), Executors.newSingleThreadExecutor());
    }

    CloudSpeechRecognizer(TranscriptionGateway gateway, ExecutorService recorderExecutor) {
        this.gateway = gateway;
        this.recorderExecutor = recorderExecutor;
    }

    public void start(Callback callback) {
        cancel();
        int token = ++generation;
        gateway.prepare(); // Validate the private endpoint while the user is still speaking.
        recorderExecutor.execute(() -> capture(token, callback));
    }

    @Override public void finish() {
        finishedGeneration = generation;
        stopRecorder();
    }

    public void cancel() {
        generation++;
        gateway.cancel();
        stopRecorder();
    }

    private void stopRecorder() {
        synchronized (recorderLock) {
            if (activeRecorder != null) {
                try { activeRecorder.stop(); } catch (RuntimeException ignored) { }
            }
        }
    }

    public void close() {
        cancel();
        gateway.close();
        recorderExecutor.shutdownNow();
    }

    private void capture(int token, Callback callback) {
        if (token != generation) return;
        if (token == finishedGeneration) {
            postFailure(token, callback, new IOException("NO_SPEECH"));
            return;
        }
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            postFailure(token, callback, new IOException("AUDIO_BUFFER_UNAVAILABLE"));
            return;
        }
        int bufferSize = Math.max(minimum, 4_096);
        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AUDIO_RECORDER_UNAVAILABLE");
            }
            synchronized (recorderLock) {
                if (token != generation) return;
                if (token == finishedGeneration) throw new IOException("NO_SPEECH");
                activeRecorder = recorder;
                recorder.startRecording();
            }
            main.post(() -> { if (token == generation && token != finishedGeneration) callback.onReady(); });
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(SAMPLE_RATE * 2 * 8);
            byte[] buffer = new byte[bufferSize];
            long startedAt = SystemClock.elapsedRealtime();
            boolean heardSpeech = false;
            while (token == generation && token != finishedGeneration) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    if (token != generation || token == finishedGeneration) break;
                    throw new IOException("AUDIO_READ_FAILED");
                }
                pcm.write(buffer, 0, read);
                if (rms(buffer, read) >= SPEECH_RMS_THRESHOLD) {
                    heardSpeech = true;
                }
                // Pauses while holding the button do not finish the utterance.
                if (SystemClock.elapsedRealtime() - startedAt >= MAX_RECORDING_MS) break;
            }
            if (token != generation) return;
            // Release the microphone before the network transcription begins.
            releaseRecorder(recorder);
            recorder = null;
            android.util.Log.d("ClickSTT", "recording_ms=" + (SystemClock.elapsedRealtime() - startedAt));
            if (!heardSpeech) throw new IOException("NO_SPEECH");
            byte[] wav = toWav(pcm.toByteArray());
            main.post(() -> {
                if (token != generation) return;
                callback.onRecordingFinished();
                if (token != generation) return;
                gateway.transcribe(wav, new TranscriptionGateway.Callback() {
                    @Override public void onSuccess(String text) {
                        if (token == generation) callback.onSuccess(text);
                    }

                    @Override public void onFailure(Throwable error) {
                        if (token == generation) callback.onFailure(error);
                    }
                });
            });
        } catch (Throwable error) {
            postFailure(token, callback, error);
        } finally {
            if (recorder != null) releaseRecorder(recorder);
        }
    }

    private void releaseRecorder(AudioRecord recorder) {
        synchronized (recorderLock) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
            recorder.release();
            if (activeRecorder == recorder) activeRecorder = null;
        }
    }

    private void postFailure(int token, Callback callback, Throwable error) {
        main.post(() -> {
            if (token == generation) callback.onFailure(error);
        });
    }

    private static int rms(byte[] pcm, int length) {
        long squares = 0;
        int samples = 0;
        for (int index = 0; index + 1 < length; index += 2) {
            int sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
            squares += (long) sample * sample;
            samples++;
        }
        return samples == 0 ? 0 : (int) Math.sqrt((double) squares / samples);
    }

    private static byte[] toWav(byte[] pcm) throws IOException {
        ByteArrayOutputStream wav = new ByteArrayOutputStream(pcm.length + 44);
        writeAscii(wav, "RIFF");
        writeInt(wav, pcm.length + 36);
        writeAscii(wav, "WAVEfmt ");
        writeInt(wav, 16);
        writeShort(wav, 1);
        writeShort(wav, 1);
        writeInt(wav, SAMPLE_RATE);
        writeInt(wav, SAMPLE_RATE * 2);
        writeShort(wav, 2);
        writeShort(wav, 16);
        writeAscii(wav, "data");
        writeInt(wav, pcm.length);
        wav.write(pcm);
        return wav.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }
}
