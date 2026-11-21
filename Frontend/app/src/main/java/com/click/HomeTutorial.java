package com.click;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.click.domain.goal.GoalCommandInterpreter;
import com.click.domain.goal.RequirementReview;
import com.click.service.overlay.DeviceSpeechInput;
import com.click.service.overlay.SpeechErrorMessages;
import com.click.service.overlay.SpeechInput;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.tutorial.ClueCoachView;

/** Teaches input on Home, then hands the request to the real cross-app task and floating coach. */
final class HomeTutorial implements ClickOverlayLayout.Callback {
    static final String SAVED_STATE = "home_clue_tutorial";
    private final HomeActivity home;
    private final FrameLayout root;
    private final OnBackPressedCallback backCallback;
    private final String originalDraft;
    private final int originalSelection;
    private enum Step { WELCOME, INPUT, VOICE, TEXT, SETTINGS, LIBRARY, SAVED, RECENT }
    private Step step, inputMethod = Step.VOICE;
    private final ClueCoachView coach;
    private final EditText input;
    private final View inputCard, mic, send, settings, library;
    private ClickOverlayLayout voice;
    private SpeechInput speech;
    private boolean recording, processing;
    private int captureGeneration;

    HomeTutorial(HomeActivity home, Bundle state) {
        this.home = home;
        root = home.findViewById(R.id.home_root);
        inputCard = home.findViewById(R.id.home_start);
        input = home.findViewById(R.id.home_request_input);
        mic = home.findViewById(R.id.home_microphone);
        send = home.findViewById(R.id.home_request_send);
        settings = home.findViewById(R.id.home_settings);
        library = home.findViewById(R.id.home_nav_favorites);
        originalDraft = state == null ? input.getText().toString() : state.getString("home_draft", "");
        originalSelection = state == null ? input.getSelectionEnd() : state.getInt("home_cursor");
        home.showTutorialHome();
        input.setText(state == null ? "" : state.getString("practice_draft", ""));
        bindEditor();
        coach = new ClueCoachView(root);
        backCallback = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        };
        home.getOnBackPressedDispatcher().addCallback(home, backCallback);
        if (state != null && state.getBoolean("typed")) inputMethod = Step.TEXT;
        show(state == null ? Step.WELCOME : restoredStep(state));
    }

    void onTextEdited() { if (step == Step.TEXT) coach.onTextEdited(); }
    void chooseVoice() { if (step == Step.INPUT || step == Step.TEXT) show(Step.VOICE); }
    void openSaved() { if (step == Step.LIBRARY) show(Step.SAVED); }
    void openRecent() { if (step == Step.SAVED) show(Step.RECENT); }
    void openSettings() { if (step == Step.SETTINGS) home.openTourSettings(); }
    boolean finishFromHome() {
        if (step != Step.RECENT) return false;
        home.closeTutorial();
        return true;
    }
    void onSettingsResult(boolean continued) { show(continued ? Step.LIBRARY : Step.SETTINGS); }
    void onMicrophonePermission(boolean granted) {
        if (step == Step.VOICE) coach.status(home.getString(granted ? R.string.tour_voice : R.string.tour_permission), true);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindEditor() {
        input.setOnClickListener(v -> { if (step == Step.INPUT) write(); });
        input.setOnTouchListener((v, event) -> {
            // An EditText can take focus without emitting a click on its first tap. Leave the
            // actual gesture to the editor. TalkBack and keyboard clicks use the listener above.
            if (step == Step.INPUT && event.getActionMasked() == MotionEvent.ACTION_DOWN) show(Step.TEXT);
            return false;
        });
    }

    private void addPracticePanel(View view, int width, int height) {
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ViewCompat.setOnApplyWindowInsetsListener(view, (child, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).top + dp(24);
            if (position.topMargin != top) { position.topMargin = top; child.requestLayout(); }
            return insets;
        });
        root.addView(view, position);
        ViewCompat.requestApplyInsets(view);
    }

    private void prepareVoice() {
        if (voice != null) return;
        voice = new ClickOverlayLayout(home);
        voice.setRequestControlsVisible(false);
        voice.openVoice();
        int width = voice.getWindowWidth(ClickOverlayLayout.Mode.VOICE, home.getResources().getDisplayMetrics().widthPixels);
        int height = voice.getWindowHeight(ClickOverlayLayout.Mode.VOICE, width, home.getResources().getDisplayMetrics().heightPixels / 2);
        voice.setBackground(ClickUi.rounded(Color.WHITE, dp(22)));
        addPracticePanel(voice, width, height);
        voice.setCallback(this);
    }

    private void show(Step next) {
        cancelCapture();
        step = next;
        if (step != Step.TEXT) home.hideKeyboard();
        if (voice != null) voice.setVisibility(step == Step.VOICE ? View.VISIBLE : View.GONE);
        if (step == Step.SAVED || step == Step.RECENT) home.openLibraryTab(step == Step.RECENT);
        else if (home.findViewById(R.id.home_favorites_scroll).getVisibility() == View.VISIBLE)
            home.showTutorialHome();
        switch (step) {
            case WELCOME:
                describe(R.string.tour_welcome, null);
                coach.onContinue(() -> show(Step.INPUT));
                break;
            case INPUT:
                describe(R.string.tour_pick, inputCard, input, mic);
                break;
            case VOICE:
                inputMethod = Step.VOICE;
                prepareVoice();
                View hold = voice.findViewById(R.id.overlay_microphone);
                View cancel = findText(voice, "취소");
                View collapse = voice.findViewById(R.id.overlay_collapse);
                describe(R.string.tour_voice, hold, hold, cancel, collapse);
                coach.keepPanelVisible(voice);
                break;
            case TEXT:
                inputMethod = Step.TEXT;
                describe(R.string.tour_text, inputCard, input, send, mic);
                break;
            case SETTINGS:
                describe(R.string.tour_settings, settings, settings);
                break;
            case LIBRARY:
                describe(R.string.tour_library, library, library);
                break;
            case SAVED:
                View recent = home.findViewById(R.id.home_library_recent);
                describe(R.string.tour_saved, recent, recent);
                break;
            case RECENT:
                View homeTab = home.findViewById(R.id.home_nav_home);
                describe(R.string.tour_recent, homeTab, homeTab);
                break;
        }
        coach.bringToFront();
    }

    private View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof android.view.ViewGroup) {
            var group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void describe(int words, View anchor, View... controls) {
        coach.show(home.getString(words), guideArt(), anchor, controls);
    }

    private int guideArt() {
        if (step == Step.WELCOME) return com.click.designpreview.R.drawable.clue2_signup;
        if (step == Step.RECENT) return com.click.designpreview.R.drawable.clue4_complete;
        return com.click.designpreview.R.drawable.clue3_tutorial;
    }

    private void write() {
        show(Step.TEXT);
        // The voice button that opened the editor is removed during this click. Give the
        // editor focus after the old button and its scroll container finish the gesture.
        input.post(() -> {
            if (step == Step.TEXT && input.requestFocus())
                home.getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    void submitHomeRequest() {
        if (step == Step.TEXT) review(input.getText().toString());
    }

    private void review(String words) {
        RequirementReview request = new RequirementReview(words);
        if (!request.requestsLargerText() || GoalCommandInterpreter.requiresContextualInterpretation(words)) {
            coach.status(home.getString(R.string.tour_retry), true);
            return;
        }
        input.setText(words.trim());
        cancelCapture();
        coach.status(home.getString(R.string.tour_live_wait), false);
        home.startLiveTutorial(words.trim());
    }

    @Override public void onMicrophonePressed() {
        if (step != Step.VOICE || recording || processing) return;
        if (home.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            home.requestTutorialMicrophone();
            return;
        }
        if (speech == null) speech = new DeviceSpeechInput(home);
        int generation = ++captureGeneration;
        recording = true;
        coach.setAudioAvailable(false);
        voice.setVoiceInputActive(true);
        coach.status(home.getString(R.string.tour_listening), false);
        speech.start(new SpeechInput.Callback() {
            @Override public void onRecordingFinished() {
                if (generation != captureGeneration) return;
                recording = false;
                processing = true;
                voice.setVoiceInputActive(false);
                voice.setVoiceInputProcessing(true);
                coach.status(home.getString(R.string.tour_processing), false);
            }
            @Override public void onSuccess(String words) {
                if (generation != captureGeneration) return;
                cancelCapture();
                review(words);
            }
            @Override public void onFailure(Throwable error) {
                if (generation != captureGeneration) return;
                cancelCapture();
                coach.status(SpeechErrorMessages.message(error), true);
            }
        });
    }
    @Override public void onMicrophoneReleased() { if (recording) speech.finish(); }
    @Override public void onMicrophoneClicked() {
        if (recording) onMicrophoneReleased();
        else onMicrophonePressed();
    }
    @Override public void onMicrophoneCancelled() {
        cancelCapture();
        if (step == Step.VOICE) coach.status(home.getString(R.string.tour_voice), false);
    }

    private void cancelCapture() {
        captureGeneration++;
        if (speech != null) speech.cancel();
        recording = processing = false;
        if (voice != null) {
            voice.setVoiceInputActive(false);
            voice.setVoiceInputProcessing(false);
        }
        if (coach != null) coach.setAudioAvailable(true);
    }

    // This local microphone teaches input; a successful transcript starts the real request.
    @Override public void onModeChanged(ClickOverlayLayout.Mode from, ClickOverlayLayout.Mode to) {
        if (step == Step.VOICE && (to == ClickOverlayLayout.Mode.COLLAPSED || to == ClickOverlayLayout.Mode.MENU))
            show(Step.INPUT);
    }
    @Override public void onDragStarted() {}
    @Override public void onDragBy(float x, float y) {}
    @Override public void onDragEnded() {}
    @Override public void onTextSubmitted(String text) {}
    @Override public void onCloseRequested() { show(Step.INPUT); }

    private void back() {
        switch (step) {
            case WELCOME: home.closeTutorial(); break;
            case INPUT: show(Step.WELCOME); break;
            case VOICE: case TEXT: show(Step.INPUT); break;
            case SETTINGS: home.closeTutorial(); break;
            case LIBRARY: show(Step.SETTINGS); break;
            case SAVED: show(Step.LIBRARY); break;
            case RECENT: show(Step.SAVED); break;
        }
    }

    private static Step restoredStep(Bundle state) {
        try { return Step.valueOf(state.getString("step", Step.WELCOME.name())); }
        catch (IllegalArgumentException oldPhotoPractice) { return Step.TEXT; }
    }

    static Bundle afterRealTask(String draft, int selection) {
        Bundle state = new Bundle();
        state.putString("step", Step.SETTINGS.name());
        state.putString("home_draft", draft);
        state.putInt("home_cursor", selection);
        return state;
    }

    Bundle save() {
        Bundle state = new Bundle();
        state.putString("step", step.name());
        state.putBoolean("typed", inputMethod == Step.TEXT);
        state.putString("home_draft", originalDraft);
        state.putInt("home_cursor", originalSelection);
        state.putString("practice_draft", input.getText().toString());
        return state;
    }

    void resume() { coach.resume(); }

    void pause() {
        if (recording || processing) onMicrophoneCancelled();
        coach.pause();
    }

    void close() {
        cancelCapture();
        if (speech != null) speech.close();
        backCallback.remove();
        coach.close();
        root.removeView(coach);
        View scroll = root.findViewById(R.id.home_scroll);
        if (scroll != null) scroll.setVisibility(View.VISIBLE);
        ((View) library.getParent()).setVisibility(View.VISIBLE);
        if (voice != null) root.removeView(voice);
        input.setOnClickListener(null);
        input.setOnTouchListener(null);
        input.setText(originalDraft);
        input.setSelection(Math.max(0, Math.min(originalSelection, input.length())));
    }

    private int dp(int value) { return ClickUi.dp(home, value); }
}
