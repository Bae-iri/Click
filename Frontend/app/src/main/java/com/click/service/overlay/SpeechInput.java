package com.click.service.overlay;

/** A cancellable single-utterance input shared by production and tutorial UI. */
public interface SpeechInput {
    interface Callback {
        default void onReady() {}
        default void onPartialText(String text) {}
        void onRecordingFinished();
        void onSuccess(String text);
        void onFailure(Throwable error);
    }
    void start(Callback callback);
    /** Finish the current recording and transcribe it; cancel discards it instead. */
    void finish();
    void cancel();
    void close();
}
