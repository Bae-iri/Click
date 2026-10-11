package com.click.service.overlay;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.click.data.remote.TranscriptionGateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Records short PCM WAV in memory and sends it to our backend/private Qwen STT. */
public final class CloudSpeechRecognizer implements SpeechInput {
    private static final int SAMPLE_RATE = 16_000;
    private static final int SPEECH_RMS_THRESHOLD = 300;
    private static final long INITIAL_SILENCE_MS = 8_000L;
    private static final long END_SILENCE_MS = 1_800L;
    private static final long MAX_RECORDING_MS = 16_000L;

    private final ExecutorService recorderExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TranscriptionGateway gateway;
    private volatile AudioRecord activeRecorder;
    private volatile int generation;

    public CloudSpeechRecognizer(Context context) {
        gateway = new TranscriptionGateway(context.getApplicationContext());
    }

    public void start(Callback callback) {
        int token = ++generation;
        recorderExecutor.execute(() -> capture(token, callback));
    }

    public void cancel() {
        generation++;
        gateway.cancel();
        AudioRecord recorder = activeRecorder;
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
        }
    }

    public void close() {
        cancel();
        gateway.close();
        recorderExecutor.shutdownNow();
    }

    private void capture(int token, Callback callback) {
        if (token != generation) return;
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
            activeRecorder = recorder;
            if (token != generation) return;
            recorder.startRecording();
            main.post(() -> { if (token == generation) callback.onReady(); });
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(SAMPLE_RATE * 2 * 8);
            byte[] buffer = new byte[bufferSize];
            long startedAt = System.currentTimeMillis();
            long lastSpeechAt = startedAt;
            boolean heardSpeech = false;
            while (token == generation) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) throw new IOException("AUDIO_READ_FAILED");
                pcm.write(buffer, 0, read);
                long now = System.currentTimeMillis();
                if (rms(buffer, read) >= SPEECH_RMS_THRESHOLD) {
                    heardSpeech = true;
                    lastSpeechAt = now;
                }
                if (!heardSpeech && now - startedAt >= INITIAL_SILENCE_MS) {
                    throw new IOException("NO_SPEECH");
                }
                if (heardSpeech && now - lastSpeechAt >= END_SILENCE_MS) break;
                if (now - startedAt >= MAX_RECORDING_MS) break;
            }
            if (token != generation) return;
            // Release the microphone before the network transcription begins.
            recorder.stop();
            recorder.release();
            if (activeRecorder == recorder) activeRecorder = null;
            recorder = null;
            main.post(() -> {
                if (token == generation) callback.onRecordingFinished();
            });
            byte[] wav = toWav(pcm.toByteArray());
            gateway.transcribe(wav, new TranscriptionGateway.Callback() {
                @Override public void onSuccess(String text) {
                    if (token == generation) callback.onSuccess(text);
                }

                @Override public void onFailure(Throwable error) {
                    if (token == generation) callback.onFailure(error);
                }
            });
        } catch (Throwable error) {
            postFailure(token, callback, error);
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) { }
                recorder.release();
            }
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
