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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.click.domain.goal.GoalCommandInterpreter;
import com.click.domain.goal.RequirementReview;
import com.click.service.overlay.DeviceSpeechInput;
import com.click.service.overlay.SpeechErrorMessages;
import com.click.service.overlay.SpeechInput;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.tutorial.ClueCoachView;

/** Runs the guided practice on the currently displayed home and its actual controls. */
final class HomeTutorial implements ClickOverlayLayout.Callback {
    static final String SAVED_STATE = "home_clue_tutorial";
    private final HomeActivity home;
    private final FrameLayout root;
    private final OnBackPressedCallback backCallback;
    private final String originalDraft;
    private final int originalSelection;
    private enum Step { WELCOME, INPUT, VOICE, TEXT, REVIEW, ADJUST, RESULT, SETTINGS, LIBRARY, SAVED, RECENT }
    private Step step, inputMethod = Step.VOICE;
    private final ClueCoachView coach;
    private final EditText input;
    private final View inputCard, mic, send, settings, library;
    private LinearLayout sample;
    private TextView sentence;
    private Button increase;
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
        createSample();
        coach = new ClueCoachView(root, this::back);
        backCallback = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        };
        home.getOnBackPressedDispatcher().addCallback(home, backCallback);
        if (state != null && state.getBoolean("typed")) inputMethod = Step.TEXT;
        show(state == null ? Step.WELCOME : Step.valueOf(state.getString("step", Step.WELCOME.name())));
    }

    void onTextEdited() { if (step == Step.TEXT) coach.onTextEdited(); }
    void chooseVoice() { if (step == Step.INPUT || step == Step.TEXT) show(Step.VOICE); }
    void openSaved() { if (step == Step.LIBRARY) show(Step.SAVED); }
    void openRecent() { if (step == Step.SAVED) show(Step.RECENT); }
    void openSettings() { if (step == Step.SETTINGS) home.openTourSettings(); }
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

    private void createSample() {
        sample = new LinearLayout(home);
        sample.setOrientation(LinearLayout.VERTICAL);
        sample.setGravity(Gravity.CENTER);
        sample.setPadding(dp(18), dp(12), dp(18), dp(12));
        sample.setBackground(ClickUi.outlined(Color.WHITE, ClickUi.BORDER, dp(22), dp(1)));
        TextView label = ClickUi.body(home, home.getString(R.string.tour_preview));
        label.setTextSize(ClickTextScale.size(home, 14));
        sample.addView(label);
        sentence = ClickUi.sectionTitle(home, home.getString(R.string.tour_preview_sentence));
        sentence.setId(R.id.tutorial_font_preview);
        sentence.setGravity(Gravity.CENTER);
        sentence.setMinimumHeight(dp(54));
        sample.addView(sentence, new LinearLayout.LayoutParams(-1, -2));
        increase = ClickUi.secondaryButton(home, home.getString(R.string.tour_increase));
        increase.setId(R.id.tutorial_increase);
        increase.setTextSize(ClickTextScale.size(home, 17));
        increase.setStateListAnimator(null);
        increase.setMinHeight(dp(48));
        increase.setOnClickListener(v -> { if (step == Step.ADJUST) show(Step.RESULT); });
        sample.addView(increase);
        int width = Math.min(dp(324), home.getResources().getDisplayMetrics().widthPixels - dp(32));
        addPracticePanel(sample, width, FrameLayout.LayoutParams.WRAP_CONTENT);
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
        voice = ClickOverlayLayout.forVoicePractice(home);
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
        boolean preview = step == Step.ADJUST || step == Step.RESULT;
        sample.setVisibility(preview ? View.VISIBLE : View.GONE);
        sentence.setTextSize(ClickTextScale.size(home, step == Step.RESULT ? 26 : 17));
        increase.setVisibility(step == Step.RESULT ? View.GONE : View.VISIBLE);
        if (step == Step.SAVED || step == Step.RECENT) home.openLibraryTab(step == Step.RECENT);
        else if (home.findViewById(R.id.home_favorites_scroll).getVisibility() == View.VISIBLE)
            home.showTutorialHome();

        switch (step) {
            case WELCOME:
                describe(R.string.tour_welcome, null);
                coach.action(R.string.tour_start, true, () -> show(Step.INPUT));
                break;
            case INPUT:
                describe(R.string.tour_pick, inputCard, input, mic);
                break;
            case VOICE:
                inputMethod = Step.VOICE;
                prepareVoice();
                View hold = voice.findViewById(R.id.overlay_microphone);
                describe(R.string.tour_voice, hold, hold);
                coach.keepPanelVisible(voice);
                coach.action(R.string.tour_try_text, false, this::write);
                break;
            case TEXT:
                inputMethod = Step.TEXT;
                describe(R.string.tour_text, inputCard, input, send, mic);
                coach.action(R.string.tour_sample, false, this::useExample);
                break;
            case REVIEW:
                coach.showReview(input.getText().toString().trim(), guideArt());
                coach.action(R.string.tour_begin_guide, true, () -> show(Step.ADJUST));
                break;
            case ADJUST:
                describe(R.string.tour_adjust, increase, increase);
                coach.keepPanelVisible(sample);
                break;
            case RESULT:
                describe(R.string.tour_result, sample);
                coach.setBackLabel(R.string.tour_again);
                coach.action(R.string.tour_satisfied, true, () -> show(Step.SETTINGS));
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
                describe(R.string.tour_recent, home.findViewById(R.id.home_library_tabs));
                coach.action(R.string.tour_finish, true, home::closeTutorial);
                break;
        }
    }

    private void describe(int words, View anchor, View... controls) {
        coach.show(home.getString(words), guideArt(), step != Step.WELCOME, anchor, controls);
    }

    private int guideArt() {
        if (step == Step.WELCOME) return com.click.designpreview.R.drawable.clue2_signup;
        if (step == Step.RESULT || step == Step.RECENT) return com.click.designpreview.R.drawable.clue4_complete;
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

    private void useExample() {
        input.setText(R.string.tour_phrase);
        show(Step.REVIEW);
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
        show(Step.REVIEW);
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

    // The coach exposes only the production panel's press-and-hold microphone.
    @Override public void onModeChanged(ClickOverlayLayout.Mode from, ClickOverlayLayout.Mode to) {}
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
            case REVIEW:
                if (inputMethod == Step.TEXT) write(); else show(Step.VOICE);
                break;
            case ADJUST: show(Step.REVIEW); break;
            case RESULT: show(Step.ADJUST); break;
            case SETTINGS: show(Step.RESULT); break;
            case LIBRARY: show(Step.SETTINGS); break;
            case SAVED: show(Step.LIBRARY); break;
            case RECENT: show(Step.SAVED); break;
        }
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
        root.removeView(sample);
        if (voice != null) root.removeView(voice);
        input.setOnClickListener(null);
        input.setOnTouchListener(null);
        input.setText(originalDraft);
        input.setSelection(Math.max(0, Math.min(originalSelection, input.length())));
    }

    private int dp(int value) { return ClickUi.dp(home, value); }
}
