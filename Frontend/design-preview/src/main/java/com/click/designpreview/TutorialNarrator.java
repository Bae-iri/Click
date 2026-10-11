package com.click.designpreview;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

/** Lifecycle-owned speech. Stale completions cannot stop a newer instruction. */
public final class TutorialNarrator {
    public interface Listener { void changed(boolean speaking, boolean unavailable); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready, closed, speaking, unavailable;
    private String pending, active;
    private int sequence;
    private Listener listener;
    public TutorialNarrator(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (closed || tts == null) return;
            if (status == TextToSpeech.SUCCESS) {
                int language = tts.setLanguage(Locale.KOREAN);
                ready = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setSpeechRate(.94f);
            }
            unavailable = !ready; notifyState();
            if (ready && pending != null) speak(pending);
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) { update(id, true, false); }
            public void onDone(String id) { update(id, false, false); }
            public void onError(String id) { update(id, false, true); }
        });
    }
    public void setListener(Listener listener) { this.listener = listener; notifyState(); }
    public boolean isSpeaking() { return speaking || pending != null; }
    private void update(String id, boolean value, boolean failed) {
        main.post(() -> {
            if (closed || !id.equals(active)) return;
            speaking = value; unavailable = failed; notifyState();
        });
    }
    private void notifyState() { if (listener != null) listener.changed(isSpeaking(), unavailable); }
    public void speak(String message) {
        if (closed || message == null || message.isEmpty()) return;
        pending = message;
        if (ready) {
            active = "tutorial-" + (++sequence); pending = null;
            speaking = tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, active) != TextToSpeech.ERROR;
            unavailable = !speaking;
        } else if (unavailable) pending = null;
        notifyState();
    }
    public void stop() { pending = active = null; speaking = false; if (tts != null) tts.stop(); notifyState(); }
    public void close() { closed = true; stop(); listener = null; if (tts != null) tts.shutdown(); }
}
