package com.click;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.click.ui.common.ClickUi;
import com.click.ui.tutorial.GalaxyTutorialView;

import java.util.ArrayList;
import java.util.Locale;

public final class TutorialActivity extends AppCompatActivity implements GalaxyTutorialView.Listener {
    public static final String EXTRA_FROM_SIGN_UP = "from_sign_up";
    private GalaxyTutorialView galaxyView;
    private TextView instruction;
    private Button exitButton;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private final Handler speechHandler = new Handler(android.os.Looper.getMainLooper());
    private boolean listening;
    private boolean errorPromptShown;
    private boolean speechStartedInAttempt;
    private int recoverableRecognitionErrors;
    private long attemptWindowStartedAt;
    private static final long LISTENING_GRACE_MS = 8000L;
    private final ActivityResultLauncher<String> microphonePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    attemptWindowStartedAt = SystemClock.elapsedRealtime();
                    startListening();
                }
                else Toast.makeText(this, "튜토리얼 음성 연습을 위해 마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = ClickUi.page(this);
        page.setPadding(ClickUi.dp(this, 10), ClickUi.dp(this, 14), ClickUi.dp(this, 10), ClickUi.dp(this, 10));
        page.addView(ClickUi.title(this, "Galaxy 글자 크기 튜토리얼"));

        instruction = ClickUi.body(this, messageFor(GalaxyTutorialView.Step.VOICE_REQUEST));
        instruction.setTextSize(20);
        instruction.setGravity(Gravity.CENTER);
        page.addView(instruction, ClickUi.fullWidth(this, 76, 8));

        galaxyView = new GalaxyTutorialView(this);
        galaxyView.setListener(this);
        LinearLayout.LayoutParams galaxyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        galaxyParams.topMargin = ClickUi.dp(this, 6);
        page.addView(galaxyView, galaxyParams);

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = ClickUi.secondaryButton(this, "← 이전 단계");
        exitButton = ClickUi.secondaryButton(this, "튜토리얼 종료");
        previous.setOnClickListener(view -> {
            galaxyView.goBackOneStep();
        });
        exitButton.setOnClickListener(view -> {
            if (galaxyView.getStep() == GalaxyTutorialView.Step.CONFIRM) {
                galaxyView.finishTutorial();
                speak("튜토리얼을 마쳤어요. 언제든 홈 화면에서 다시 연습할 수 있어요.");
                exitToHome();
                return;
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("튜토리얼을 종료할까요?")
                    .setMessage("홈 화면에서 언제든 다시 시작할 수 있어요.")
                    .setNegativeButton("계속하기", null)
                    .setPositiveButton("종료", (dialog, which) -> exitToHome())
                    .show();
        });
        navigation.addView(previous, new LinearLayout.LayoutParams(0, ClickUi.dp(this, 54), 1f));
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(0, ClickUi.dp(this, 54), 1f);
        exitParams.leftMargin = ClickUi.dp(this, 8);
        navigation.addView(exitButton, exitParams);
        page.addView(navigation, ClickUi.fullWidth(this, 54, 10));
        setContentView(page);

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.KOREAN);
                textToSpeech.setSpeechRate(.88f);
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}
                    @Override public void onError(String utteranceId) {
                        if ("voice_prompt".equals(utteranceId) || "voice_retry".equals(utteranceId)) {
                            speechHandler.post(TutorialActivity.this::beginInitialListening);
                        }
                    }
                    @Override public void onDone(String utteranceId) {
                        if ("voice_prompt".equals(utteranceId) || "voice_retry".equals(utteranceId)) {
                            speechHandler.post(() -> {
                                attemptWindowStartedAt = SystemClock.elapsedRealtime();
                                beginInitialListening();
                            });
                        }
                    }
                });
                speakThenListen(messageFor(GalaxyTutorialView.Step.VOICE_REQUEST), "voice_prompt");
            }
        });
        prepareSpeechRecognizer();
        galaxyView.postDelayed(() -> {
            if (!listening && attemptWindowStartedAt == 0L) beginInitialListening();
        }, 3500L);
    }

    @Override
    public void onMicrophoneRequested() {
        errorPromptShown = false;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startListening();
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
    }

    @Override
    public void onStepChanged(GalaxyTutorialView.Step step) {
        if (step != GalaxyTutorialView.Step.VOICE_REQUEST) stopTutorialListening();
        instruction.setText(messageFor(step));
        exitButton.setText(step == GalaxyTutorialView.Step.CONFIRM ? "예, 종료할게요" : "튜토리얼 종료");
        exitButton.setTextColor(step == GalaxyTutorialView.Step.CONFIRM ? android.graphics.Color.WHITE : ClickUi.TEXT);
        exitButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                step == GalaxyTutorialView.Step.CONFIRM ? ClickUi.RED : ClickUi.SECONDARY_RED));
        if (step != GalaxyTutorialView.Step.VOICE_REQUEST) speak(messageFor(step));
    }

    @Override
    public void onSliderCompleted() {
        // The persistent exit button becomes the single confirmation action.
    }

    private void prepareSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (galaxyView.getStep() != GalaxyTutorialView.Step.VOICE_REQUEST) return;
                listening = true;
            }
            @Override public void onBeginningOfSpeech() { speechStartedInAttempt = true; }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { listening = false; }
            @Override public void onError(int error) {
                listening = false;
                if (galaxyView.getStep() != GalaxyTutorialView.Step.VOICE_REQUEST) return;
                long elapsed = attemptWindowStartedAt == 0L ? 0L
                        : SystemClock.elapsedRealtime() - attemptWindowStartedAt;
                boolean recoverable = error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
                if (recoverable && recoverableRecognitionErrors < 3) {
                    recoverableRecognitionErrors++;
                    speechHandler.postDelayed(TutorialActivity.this::startListening,
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 700L : 300L);
                } else if (!speechStartedInAttempt && elapsed < LISTENING_GRACE_MS) {
                    speechHandler.postDelayed(TutorialActivity.this::startListening, 350L);
                } else {
                    showSingleRecognitionError();
                }
            }
            @Override public void onResults(Bundle results) { showSpeechResult(results, true); }
            @Override public void onPartialResults(Bundle partialResults) { showSpeechResult(partialResults, false); }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void beginInitialListening() {
        if (galaxyView.getStep() != GalaxyTutorialView.Step.VOICE_REQUEST) return;
        recoverableRecognitionErrors = 0;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startListening() {
        speechHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer == null) {
            Toast.makeText(this, "이 기기에서는 음성 인식을 사용할 수 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        if (galaxyView.getStep() != GalaxyTutorialView.Step.VOICE_REQUEST || listening) return;
        errorPromptShown = false;
        speechStartedInAttempt = false;
        if (attemptWindowStartedAt == 0L) attemptWindowStartedAt = SystemClock.elapsedRealtime();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        listening = true;
        speechRecognizer.startListening(intent);
    }

    private void showSpeechResult(Bundle bundle, boolean isFinal) {
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        String text = bestRecognitionCandidate(bundle, matches);
        if (isFinal) listening = false;
        if (galaxyView.getStep() != GalaxyTutorialView.Step.VOICE_REQUEST) return;
        galaxyView.setVoiceTranscript(text);
        if (isFinal && galaxyView.getStep() == GalaxyTutorialView.Step.VOICE_REQUEST) {
            if (isFontSizeRequest(text)) {
                recoverableRecognitionErrors = 0;
                speechHandler.postDelayed(galaxyView::showRecognizedRequest, 450L);
            } else {
                showSingleRecognitionError();
            }
        }
    }

    private void showSingleRecognitionError() {
        if (galaxyView.getStep() != GalaxyTutorialView.Step.VOICE_REQUEST) return;
        if (!errorPromptShown) {
            errorPromptShown = true;
            if (speechRecognizer != null) speechRecognizer.cancel();
            listening = false;
            attemptWindowStartedAt = 0L;
            speakThenListen("잘 듣지 못했어요. 다시 한 번 말씀해주세요.", "voice_retry");
        }
    }

    private void stopTutorialListening() {
        speechHandler.removeCallbacksAndMessages(null);
        listening = false;
        attemptWindowStartedAt = 0L;
        speechStartedInAttempt = false;
        if (speechRecognizer != null) speechRecognizer.cancel();
    }

    private boolean isFontSizeRequest(String text) {
        String normalized = text.toLowerCase(Locale.KOREAN).replaceAll("[\\s,.!?]", "");
        boolean hasFont = normalized.contains("글자") || normalized.contains("글씨")
                || normalized.contains("글꼴") || normalized.contains("폰트")
                || normalized.contains("텍스트") || normalized.contains("font");
        boolean hasIncrease = normalized.contains("크게") || normalized.contains("키워")
                || normalized.contains("키우") || normalized.contains("늘려")
                || normalized.contains("확대") || normalized.contains("커지게");
        return hasFont && hasIncrease;
    }

    private String bestRecognitionCandidate(Bundle bundle, ArrayList<String> matches) {
        float[] confidence = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        String best = matches.get(0);
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < matches.size(); index++) {
            String candidate = matches.get(index);
            float score = confidence != null && index < confidence.length ? confidence[index] : 0f;
            if (isFontSizeRequest(candidate)) score += 2f;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private String messageFor(GalaxyTutorialView.Step step) {
        switch (step) {
            case VOICE_REQUEST: return "“글자 크기를 키워줘”라고\n말해보세요.";
            case SETTINGS: return "좋아요. 설정 앱이 보이는 화면으로 이동했어요.\n화살표가 가리키는 설정을 눌러주세요.";
            case SETTINGS_SCROLL: return "‘일반’ 메뉴가 보이도록\n설정 화면을 아래로 이동할게요.";
            case GENERAL: return "좋아요. 이제 ‘일반’ 버튼을 눌러주세요.";
            case GENERAL_SCROLL: return "‘삼성 키보드 설정’이 보이도록\n일반 화면을 아래로 이동할게요.";
            case KEYBOARD: return "‘삼성 키보드 설정’을 눌러주세요.";
            case KEYBOARD_SCROLL: return "‘글자 크기’가 보이도록\n삼성 키보드 화면을 아래로 이동할게요.";
            case FONT_SIZE: return "‘글자 크기’를 눌러주세요.";
            case SLIDER: return "표시된 부분을 오른쪽으로 움직이면\n글자 크기가 커져요.";
            case CONFIRM: return "글자 크기 요청을 완료한 것 같아요.\nClick AI를 종료할까요?";
            case DONE: return "튜토리얼을 완료했어요.";
            default: return "";
        }
    }

    private void speak(String text) {
        if (textToSpeech != null) textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "click_tutorial");
    }

    private void speakThenListen(String text, String utteranceId) {
        speechHandler.removeCallbacksAndMessages(null);
        if (textToSpeech == null) {
            beginInitialListening();
            return;
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        speechHandler.postDelayed(() -> {
            if (galaxyView.getStep() == GalaxyTutorialView.Step.VOICE_REQUEST && !listening) {
                attemptWindowStartedAt = SystemClock.elapsedRealtime();
                beginInitialListening();
            }
        }, 4500L);
    }

    private void exitToHome() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override
    protected void onDestroy() {
        speechHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
}
