package com.click.designpreview;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** A screen/service's narration handle. All handles use one process-wide Korean TTS engine. */
public final class TutorialNarrator {
    public interface Listener { void changed(boolean speaking, boolean unavailable); }
    private static Engine shared;
    private final Engine engine;
    private final boolean foregroundScreen;
    private boolean active = true, closed, speaking, unavailable;
    private String lastMessage, suppression;
    private Listener listener;

    public TutorialNarrator(Context context) { this(context, true); }
    public TutorialNarrator(Context context, boolean foregroundScreen) {
        this.foregroundScreen = foregroundScreen;
        if (shared == null) shared = new Engine(context.getApplicationContext());
        engine = shared;
        engine.clients.add(this);
        if (foregroundScreen && engine.owner != null && !engine.owner.foregroundScreen) engine.cancel();
    }
    public void setListener(Listener listener) { this.listener = listener; notifyState(); }
    public boolean isSpeaking() { return speaking; }
    public boolean isUnavailable() { return unavailable; }
    /** Re-observing the same instruction never restarts a completed or stopped sentence. */
    public void speak(String message) { engine.submit(this, message, false); }
    /** Replay is an explicit user action, so the same sentence may be heard again. */
    public void replay(String message) { engine.submit(this, message, true); }
    public void stop() { if (engine.owner == this) engine.cancel(); }
    public void reset() { stop(); lastMessage = null; }
    public void pause() { active = false; stop(); }
    public void resume() {
        if (closed) return;
        active = true;
        if (foregroundScreen && engine.owner != null && !engine.owner.foregroundScreen) engine.cancel();
    }
    public void close() {
        if (closed) return;
        closed = true; active = false; stop(); listener = null;
        engine.clients.remove(this);
        if (engine.clients.isEmpty()) { engine.close(); if (shared == engine) shared = null; }
    }
    private void noteSuppression(String reason) {
        if (!reason.equals(suppression)) Log.i("ClickNarration", "skip " + reason);
        suppression = reason;
    }
    private void state(boolean speaking, boolean unavailable) {
        this.speaking = speaking; this.unavailable = unavailable; notifyState();
    }
    private void notifyState() { if (listener != null) listener.changed(speaking, unavailable); }

    private static final class Engine {
        private static final long COALESCE_MS = 220L;
        private final Context context;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Set<TutorialNarrator> clients = new HashSet<>();
        private final AudioManager audio;
        private final AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        private AudioFocusRequest focus;
        private TextToSpeech tts;
        private TutorialNarrator owner;
        private String message, utterance;
        private Runnable pendingPlay, timeout;
        private boolean ready, failed, closed, focused;
        private long sequence, engineGeneration;

        Engine(Context context) {
            this.context = context;
            audio = context.getSystemService(AudioManager.class);
            initialize();
        }
        private void initialize() {
            long generation = ++engineGeneration;
            ready = failed = false;
            tts = new TextToSpeech(context, status -> main.post(() -> {
                if (closed || generation != engineGeneration || tts == null) return;
                ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.KOREA) >= TextToSpeech.LANG_AVAILABLE;
                failed = !ready;
                if (ready) { tts.setSpeechRate(.92f); tts.setAudioAttributes(attributes); }
                if (failed) fail("initialization");
                else if (owner != null) schedule();
            }));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { main.post(() -> {
                    if (!current(id)) return;
                    Log.i("ClickNarration", "start " + id);
                    owner.state(true, false);
                    armTimeout(id, Math.min(90000L, 10000L + message.length() * 220L));
                }); }
                @Override public void onDone(String id) { main.post(() -> { if (current(id)) finish(false, "done"); }); }
                @Override public void onError(String id) { main.post(() -> { if (current(id)) fail("playback"); }); }
                @Override public void onStop(String id, boolean interrupted) {
                    main.post(() -> { if (current(id)) finish(false, "stopped"); });
                }
            });
        }
        void submit(TutorialNarrator client, String text, boolean replay) {
            if (closed || client.closed || !client.active || text == null || text.trim().isEmpty()) return;
            if (!client.foregroundScreen && clients.stream().anyMatch(c -> c.active && !c.closed && c.foregroundScreen)) {
                client.noteSuppression("foreground_screen_owner"); return;
            }
            String next = text.trim();
            if (!replay && next.equals(client.lastMessage)) {
                client.noteSuppression("duplicate_sentence"); client.notifyState(); return;
            }
            client.suppression = null;
            cancel();
            if (replay && failed) { if (tts != null) tts.shutdown(); initialize(); }
            owner = client; message = next;
            client.lastMessage = next;
            client.state(true, false);
            Log.i("ClickNarration", "queue " + (client.foregroundScreen ? "screen" : "guide") + " length=" + next.length());
            if (failed) fail("unavailable");
            else {
                // Initialization itself can stall; it must not leave the button saying 'speaking' forever.
                armTimeout(null, 10000L);
                if (ready) schedule();
            }
        }
        private void schedule() {
            if (pendingPlay != null) main.removeCallbacks(pendingPlay);
            pendingPlay = () -> { pendingPlay = null; play(); };
            main.postDelayed(pendingPlay, COALESCE_MS);
        }
        private void play() {
            if (closed || owner == null || !owner.active || !ready) return;
            if (audio != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) { fail("muted"); return; }
            utterance = "click-speech-" + (++sequence);
            String id = utterance;
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change < 0 && current(id)) cancel();
                    }, main).build();
            if (audio != null && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                fail("audio_focus"); return;
            }
            focused = audio != null;
            if (tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, utterance) == TextToSpeech.ERROR) fail("submit");
            else armTimeout(utterance, 10000L);
        }
        private boolean current(String id) { return !closed && owner != null && id != null && id.equals(utterance); }
        private void armTimeout(String id, long delay) {
            if (timeout != null) main.removeCallbacks(timeout);
            TutorialNarrator expected = owner;
            timeout = () -> {
                if (owner == expected && (id == null || current(id))) fail("timeout");
            };
            main.postDelayed(timeout, delay);
        }
        private void fail(String reason) {
            // Keep deduplication even on failure: only an explicit replay retries the same instruction.
            finish(true, reason);
        }
        private void finish(boolean unavailable, String reason) {
            TutorialNarrator previous = owner;
            String id = utterance;
            owner = null; utterance = message = null;
            if (pendingPlay != null) main.removeCallbacks(pendingPlay);
            if (timeout != null) main.removeCallbacks(timeout);
            pendingPlay = timeout = null;
            if (unavailable && tts != null) tts.stop();
            if (focused && audio != null) audio.abandonAudioFocusRequest(focus);
            focused = false; focus = null;
            if (previous != null) {
                Log.i("ClickNarration", reason + (id == null ? " pending" : " " + id));
                previous.state(false, unavailable);
            }
        }
        void cancel() {
            if (owner == null) return;
            // Invalidate the ID before stop(), whose callback can arrive after the next utterance starts.
            finish(false, "cancel");
            if (tts != null) tts.stop();
        }
        void close() {
            cancel(); closed = true; engineGeneration++;
            main.removeCallbacksAndMessages(null);
            if (tts != null) { tts.shutdown(); tts = null; }
        }
    }
}
