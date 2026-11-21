package com.click;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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

/** Uses the home controls in a separate activity so practice has its own draft and back stack. */
public final class TutorialActivity extends HomeActivity implements ClickOverlayLayout.Callback {
    public static final String EXTRA_FROM_SIGN_UP = "from_sign_up";
    private enum Step { WELCOME, INPUT, VOICE, TEXT, REVIEW, ADJUST, RESULT, SETTINGS, LIBRARY, SAVED, RECENT }
    private Step step, inputMethod = Step.VOICE;
    private ClueCoachView coach;
    private EditText input;
    private View inputCard, mic, send, settings, library;
    private LinearLayout sample;
    private TextView sentence;
    private Button increase;
    private ClickOverlayLayout voice;
    private SpeechInput speech;
    private boolean recording, processing;
    private int captureGeneration;

    private final ActivityResultLauncher<String> microphonePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (step == Step.VOICE) coach.status(getString(granted ? R.string.tour_voice : R.string.tour_permission), true);
            });
    private final ActivityResultLauncher<Intent> settingsTour =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == MenuActivity.RESULT_CLOSE_TOUR) finishToHome();
                else if (result.getResultCode() == Activity.RESULT_OK) show(Step.LIBRARY);
                else show(Step.SETTINGS);
            });

    @Override protected boolean isTutorial() { return true; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = findViewById(R.id.home_root);
        inputCard = findViewById(R.id.home_start);
        input = findViewById(R.id.home_request_input);
        mic = findViewById(R.id.home_microphone);
        send = findViewById(R.id.home_request_send);
        settings = findViewById(R.id.home_settings);
        library = findViewById(R.id.home_nav_favorites);
        mic.setOnClickListener(v -> show(Step.VOICE));
        bindEditor();
        settings.setOnClickListener(v -> settingsTour.launch(new Intent(this, MenuActivity.class)
                .putExtra(MenuActivity.EXTRA_TOUR, true)));
        library.setOnClickListener(v -> show(Step.SAVED));
        createSample(root);
        coach = new ClueCoachView(root, this::back, this::finishToHome);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (step == Step.TEXT) coach.onTextEdited();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        if (state != null && state.getBoolean("tour_typed")) inputMethod = Step.TEXT;
        show(state == null ? Step.WELCOME : Step.valueOf(state.getString("clue_tour_step", Step.WELCOME.name())));
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

    private void createSample(FrameLayout root) {
        sample = new LinearLayout(this);
        sample.setOrientation(LinearLayout.VERTICAL);
        sample.setGravity(Gravity.CENTER);
        sample.setPadding(dp(18), dp(12), dp(18), dp(12));
        sample.setBackground(ClickUi.outlined(Color.WHITE, ClickUi.BORDER, dp(22), dp(1)));
        sample.setElevation(dp(9));
        TextView label = ClickUi.body(this, getString(R.string.tour_preview));
        label.setTextSize(ClickTextScale.size(this, 14));
        sample.addView(label);
        sentence = ClickUi.sectionTitle(this, getString(R.string.tour_preview_sentence));
        sentence.setId(R.id.tutorial_font_preview);
        sentence.setGravity(Gravity.CENTER);
        sentence.setMinimumHeight(dp(54));
        sample.addView(sentence, new LinearLayout.LayoutParams(-1, -2));
        increase = ClickUi.secondaryButton(this, getString(R.string.tour_increase));
        increase.setId(R.id.tutorial_increase);
        increase.setTextSize(ClickTextScale.size(this, 17));
        increase.setMinHeight(dp(48));
        increase.setOnClickListener(v -> { if (step == Step.ADJUST) show(Step.RESULT); });
        sample.addView(increase);
        int width = Math.min(dp(324), getResources().getDisplayMetrics().widthPixels - dp(32));
        root.addView(sample, new FrameLayout.LayoutParams(width, -2, Gravity.CENTER));
    }

    private void prepareVoice() {
        if (voice != null) return;
        voice = new ClickOverlayLayout(this);
        voice.setRequestControlsVisible(false);
        voice.openVoice();
        int width = voice.getWindowWidth(ClickOverlayLayout.Mode.VOICE, getResources().getDisplayMetrics().widthPixels);
        int height = voice.getWindowHeight(ClickOverlayLayout.Mode.VOICE, width, getResources().getDisplayMetrics().heightPixels / 2);
        voice.setElevation(dp(9));
        voice.setBackground(ClickUi.rounded(Color.WHITE, dp(22)));
        FrameLayout root = findViewById(R.id.home_root);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
        if (getResources().getConfiguration().screenHeightDp < 740 || getResources().getConfiguration().fontScale > 1.2f) {
            position.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            ViewCompat.setOnApplyWindowInsetsListener(voice, (view, insets) -> {
                int top = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).top + dp(8);
                if (position.topMargin != top) { position.topMargin = top; view.requestLayout(); }
                return insets;
            });
        }
        root.addView(voice, position);
        ViewCompat.requestApplyInsets(voice);
        voice.setCallback(this);
    }

    private void show(Step next) {
        cancelCapture();
        step = next;
        if (step != Step.TEXT) hideKeyboard();
        if (voice != null) voice.setVisibility(step == Step.VOICE ? View.VISIBLE : View.GONE);
        boolean preview = step == Step.ADJUST || step == Step.RESULT;
        sample.setVisibility(preview ? View.VISIBLE : View.GONE);
        sentence.setTextSize(ClickTextScale.size(this, step == Step.RESULT ? 26 : 17));
        increase.setVisibility(step == Step.RESULT ? View.GONE : View.VISIBLE);
        if (step == Step.SAVED || step == Step.RECENT) openLibraryTab(step == Step.RECENT);
        else if (findViewById(R.id.home_favorites_scroll).getVisibility() == View.VISIBLE)
            findViewById(R.id.home_nav_home).performClick();

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
                coach.show(getString(R.string.tour_review, input.getText().toString().trim()), guideArt(), true, inputCard);
                coach.action(R.string.tour_begin_guide, true, () -> show(Step.ADJUST));
                coach.action(R.string.tour_edit, false, () -> show(Step.INPUT));
                break;
            case ADJUST:
                describe(R.string.tour_adjust, increase, increase);
                coach.keepPanelVisible(sample);
                break;
            case RESULT:
                describe(R.string.tour_result, sample);
                coach.action(R.string.tour_satisfied, true, () -> show(Step.SETTINGS));
                coach.action(R.string.tour_again, false, () -> show(Step.ADJUST));
                break;
            case SETTINGS:
                describe(R.string.tour_settings, settings, settings);
                break;
            case LIBRARY:
                describe(R.string.tour_library, library, library);
                break;
            case SAVED:
                View recent = findViewById(R.id.home_library_recent);
                recent.setOnClickListener(v -> show(Step.RECENT));
                describe(R.string.tour_saved, recent, recent);
                break;
            case RECENT:
                describe(R.string.tour_recent, findViewById(R.id.home_library_tabs));
                coach.action(R.string.tour_finish, true, this::finishToHome);
                break;
        }
    }

    private void describe(int words, View anchor, View... controls) {
        coach.show(getString(words), guideArt(), step != Step.WELCOME, anchor, controls);
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
                getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void useExample() {
        input.setText(R.string.tour_phrase);
        show(Step.REVIEW);
    }

    @Override protected void submitHomeRequest() {
        if (step == Step.TEXT) review(input.getText().toString());
    }

    private void review(String words) {
        RequirementReview request = new RequirementReview(words);
        if (!request.requestsLargerText() || GoalCommandInterpreter.requiresContextualInterpretation(words)) {
            coach.status(getString(R.string.tour_retry), true);
            return;
        }
        input.setText(words.trim());
        show(Step.REVIEW);
    }

    @Override public void onMicrophonePressed() {
        if (step != Step.VOICE || recording || processing) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        if (speech == null) speech = new DeviceSpeechInput(this);
        int generation = ++captureGeneration;
        recording = true;
        coach.setAudioAvailable(false);
        voice.setVoiceInputActive(true);
        coach.status(getString(R.string.tour_listening), false);
        speech.start(new SpeechInput.Callback() {
            @Override public void onRecordingFinished() {
                if (generation != captureGeneration) return;
                recording = false;
                processing = true;
                voice.setVoiceInputActive(false);
                voice.setVoiceInputProcessing(true);
                coach.status(getString(R.string.tour_processing), false);
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
        if (step == Step.VOICE) coach.status(getString(R.string.tour_voice), false);
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
            case WELCOME: finishToHome(); break;
            case INPUT: show(Step.WELCOME); break;
            case VOICE: case TEXT: show(Step.INPUT); break;
            case REVIEW: show(inputMethod); break;
            case ADJUST: show(Step.REVIEW); break;
            case RESULT: show(Step.ADJUST); break;
            case SETTINGS: show(Step.RESULT); break;
            case LIBRARY: show(Step.SETTINGS); break;
            case SAVED: show(Step.LIBRARY); break;
            case RECENT: show(Step.SAVED); break;
        }
    }

    private void finishToHome() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("clue_tour_step", step.name());
        state.putBoolean("tour_typed", inputMethod == Step.TEXT);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); if (coach != null) coach.resume(); }
    @Override protected void onPause() {
        if (recording || processing) onMicrophoneCancelled();
        coach.pause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        cancelCapture();
        if (speech != null) speech.close();
        coach.close();
        super.onDestroy();
    }
    private int dp(int n) { return ClickUi.dp(this, n); }
}
