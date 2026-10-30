package com.click.service.overlay;

import android.content.Context;
import java.io.IOException;

/** Compatibility entry point: tutorial audio uses only our backend's private STT. */
public final class DeviceSpeechInput implements SpeechInput {
    private final SpeechInput backend;
    private boolean closed;
    public DeviceSpeechInput(Context context) { backend = new CloudSpeechRecognizer(context); }
    @Override public void start(Callback callback) { if (!closed) backend.start(callback); }
    @Override public void finish() { if (!closed) backend.finish(); }
    @Override public void cancel() { backend.cancel(); }
    @Override public void close() { closed = true; backend.close(); }
    public static final class DeviceError extends IOException {
        public final int code;
        DeviceError(int code) { super("DEVICE_SPEECH_" + code); this.code = code; }
    }
}
