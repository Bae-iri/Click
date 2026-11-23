package com.click;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.click.service.overlay.DeviceSpeechInput;
import com.click.service.overlay.SpeechErrorMessages;
import com.click.service.overlay.SpeechInput;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.tutorial.ClueCoachView;
import java.util.function.Consumer;

/** Uses the normal hold-to-speak panel and puts its transcript back in the home practice editor. */
final class HomeTutorialVoice implements ClickOverlayLayout.Callback {
    private final HomeActivity home;
    private final ClueCoachView coach;
    private final Runnable cancel;
    private final Consumer<String> transcript;
    private final ClickOverlayLayout panel;
    private final SpeechInput speech;
    private boolean recording, processing;
    private int generation;

    HomeTutorialVoice(HomeActivity home, ClueCoachView coach, Runnable cancel, Consumer<String> transcript) {
        this.home = home;
        this.coach = coach;
        this.cancel = cancel;
        this.transcript = transcript;
        speech = new DeviceSpeechInput(home);
        FrameLayout root = home.findViewById(R.id.home_root);
        panel = new ClickOverlayLayout(home);
        panel.setRequestControlsVisible(false);
        panel.openVoice();
        panel.setCallback(this);
        var size = home.getResources().getDisplayMetrics();
        int width = panel.getWindowWidth(ClickOverlayLayout.Mode.VOICE, size.widthPixels);
        int height = panel.getWindowHeight(ClickOverlayLayout.Mode.VOICE, width, size.heightPixels / 2);
        var position = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        panel.setBackground(ClickUi.rounded(Color.WHITE, ClickUi.dp(home, 22)));
        ViewCompat.setOnApplyWindowInsetsListener(panel, (view, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).top
                    + ClickUi.dp(home, 16);
            if (position.topMargin != top) { position.topMargin = top; panel.requestLayout(); }
            return insets;
        });
        root.addView(panel, position);
        ViewCompat.requestApplyInsets(panel);
        coach.show(home.getString(R.string.tour_voice), com.click.designpreview.R.drawable.clue3_tutorial,
                panel.findViewById(R.id.overlay_microphone), panel);
        coach.keepPanelVisible(panel);
        coach.bringToFront();
    }

    void onPermission(boolean granted) {
        coach.status(home.getString(granted ? R.string.tour_voice : R.string.tour_mic_permission), true);
    }

    @Override public void onMicrophonePressed() {
        if (recording || processing) return;
        if (home.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            home.requestTutorialMicrophone();
            return;
        }
        int current = ++generation;
        recording = true;
        coach.setListening(true);
        panel.setVoiceInputActive(true);
        coach.status(home.getString(R.string.tour_listening), false);
        speech.start(new SpeechInput.Callback() {
            @Override public void onRecordingFinished() {
                if (current != generation) return;
                recording = false;
                processing = true;
                panel.setVoiceInputActive(false);
                panel.setVoiceInputProcessing(true);
                coach.status(home.getString(R.string.tour_processing), false);
            }
            @Override public void onSuccess(String words) {
                if (current == generation) transcript.accept(words);
            }
            @Override public void onFailure(Throwable error) {
                if (current != generation) return;
                reset();
                coach.status(SpeechErrorMessages.message(error), true);
            }
        });
    }
    @Override public void onMicrophoneReleased() { if (recording) speech.finish(); }
    @Override public void onMicrophoneClicked() {
        if (recording) onMicrophoneReleased();
        else onMicrophonePressed();
    }
    @Override public void onMicrophoneCancelled() { pause(); }

    private void reset() {
        generation++;
        recording = processing = false;
        speech.cancel();
        panel.setVoiceInputActive(false);
        panel.setVoiceInputProcessing(false);
        coach.setListening(false);
    }

    void pause() {
        if (!recording && !processing) return;
        reset();
        coach.status(home.getString(R.string.tour_voice), false);
    }

    void close() {
        reset();
        speech.close();
        ((FrameLayout) panel.getParent()).removeView(panel);
    }
    @Override public void onModeChanged(ClickOverlayLayout.Mode from, ClickOverlayLayout.Mode to) {
        if (to != ClickOverlayLayout.Mode.VOICE) cancel.run();
    }
    @Override public void onCloseRequested() { cancel.run(); }
    @Override public void onTextSubmitted(String text) {}
    @Override public void onDragStarted() {}
    @Override public void onDragBy(float x, float y) {}
    @Override public void onDragEnded() {}
}
