package com.click;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.widget.ScrollView;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.click.designpreview.PermissionPhoneView;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.TaskFlowOverlayView;
import com.click.domain.goal.RequirementReview;
import com.click.service.overlay.CloudSpeechRecognizer;
import com.click.designpreview.TutorialNarrator;
import android.Manifest;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import com.click.ui.tutorial.TutorialArtworkView;
import com.click.ui.tutorial.TutorialFloatingHost;
import java.util.ArrayList;

/** A feature tour on the same phone backdrop as the accessibility guide, using the real controls. */
public final class TutorialActivity extends AppCompatActivity implements TutorialFloatingHost.Listener {
    public static final String EXTRA_FROM_SIGN_UP = "from_sign_up";
    private static final int INTRO = -1, COMPLETE = 9;
    private static final int OPEN = 0, WRITE = 1, REPLY = 2, VOICE = 3, STOP = 4, FOLD = 5, MOVE = 6, HIDE = 7, RESTORE = 8, STEP_COUNT = 9;
    private final ArrayList<String> messages = new ArrayList<>();
    private final ArrayList<String> reviewActions = new ArrayList<>();
    private TutorialFloatingHost floating;
    private LinearLayout toolbar, progress, footer, checklist;
    private ScrollView overview;
    private View phone;
    private TextView title, description, counter, feedback;
    private ImageView grandmother;
    private Button action, restore;
    private int step = INTRO, completedSteps;
    private TutorialNarrator narrator;
    private com.click.ui.tutorial.ReviewedTutorialScripts reviewedScripts;
    private com.click.service.overlay.SpeechInput speech;
    private boolean transcribing, reviewConfirmed, folded, moved, restored;
    private String lastCue = "", voiceText = "";
    private boolean cuePending;
    private RequirementReview practiceReview;
    private TaskFlowOverlayView reviewView;
    private boolean voiceTried, stopTried, restoring, keyboardVisible;
    private String moveFeedback = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        narrator = new TutorialNarrator(this);
        reviewedScripts = new com.click.ui.tutorial.ReviewedTutorialScripts(this);
        speech = new com.click.service.overlay.DeviceSpeechInput(this);
        if (savedInstanceState != null) {
            step = Math.max(INTRO, Math.min(COMPLETE, savedInstanceState.getInt("feature_step", INTRO)));
            completedSteps = savedInstanceState.getInt("completed_steps");
            voiceTried = savedInstanceState.getBoolean("voice_tried");
            voiceText = savedInstanceState.getString("voice_text", "");
            reviewConfirmed = savedInstanceState.getBoolean("review_confirmed");
            folded = savedInstanceState.getBoolean("folded"); moved = savedInstanceState.getBoolean("moved");
            restored = savedInstanceState.getBoolean("restored");
            stopTried = savedInstanceState.getBoolean("stop_tried");
            moveFeedback = savedInstanceState.getString("move_feedback", "");
            ArrayList<String> savedMessages = savedInstanceState.getStringArrayList("practice_messages");
            if (savedMessages != null) messages.addAll(savedMessages);
            ArrayList<String> savedReview = savedInstanceState.getStringArrayList("review_actions");
            if (savedReview != null) reviewActions.addAll(savedReview);
        }
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        FrameLayout root = new FrameLayout(this);
        root.addView(new TutorialArtworkView(this, TutorialArtworkView.Kind.BACKGROUND), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout column = vertical();
        root.addView(column, new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels, dp(480)), -1, Gravity.CENTER_HORIZONTAL));
        toolbar = toolbar();
        column.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(50)));
        progress = new LinearLayout(this);
        progress.setGravity(Gravity.CENTER);
        progress.setPadding(dp(5), dp(6), dp(5), dp(9));
        for (int i = 0; i < STEP_COUNT; i++) {
            View bar = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(4), 1);
            params.setMargins(dp(3), 0, dp(3), 0);
            progress.addView(bar, params);
        }
        column.addView(progress, new LinearLayout.LayoutParams(-1, dp(24)));
        column.addView(coach(), new LinearLayout.LayoutParams(-1, -2));

        FrameLayout stage = new FrameLayout(this);
        stage.setId(R.id.tutorial_phone_stage);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(-1, 0, 1);
        stageParams.setMargins(0, dp(12), 0, dp(10));
        column.addView(stage, stageParams);
        PermissionPhoneView phoneView = new PermissionPhoneView(this, true, true);
        phoneView.fillAvailableHeight();
        phone = phoneView;
        phone.setAlpha(.55f);
        FrameLayout.LayoutParams phoneParams = new FrameLayout.LayoutParams(-1, -1);
        phoneParams.setMargins(dp(5), 0, dp(5), 0);
        stage.addView(phone, phoneParams);

        restore = ClickUi.secondaryButton(this, "클릭 버튼 다시 보기");
        restore.setId(R.id.tutorial_restore);
        restore.setBackground(ClickUi.outlined(0xFFF1F6FF, TutorialFloatingHost.HIGHLIGHT, dp(18), dp(4)));
        restore.setTextColor(ClickUi.BLUE);
        restore.setVisibility(View.GONE);
        FrameLayout.LayoutParams restoreParams = new FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM);
        restoreParams.setMargins(dp(30), 0, dp(30), dp(28));
        stage.addView(restore, restoreParams);
        floating = new TutorialFloatingHost(this, this);
        floating.setId(R.id.tutorial_floating_host);
        floating.getControl().setId(R.id.tutorial_floating_control);
        floating.getControl().setInputHint("글자 크기 키워줘");
        FrameLayout.LayoutParams floatingParams = new FrameLayout.LayoutParams(-1, -1);
        floatingParams.setMargins(dp(14), dp(34), dp(14), dp(16));
        stage.addView(floating, floatingParams);
        overview = new ScrollView(this);
        overview.setFillViewport(false);
        overview.setClipToPadding(false);
        overview.setPadding(dp(4), dp(4), dp(4), dp(12));
        checklist = vertical();
        overview.addView(checklist, new ScrollView.LayoutParams(-1, -2));
        stage.addView(overview, new FrameLayout.LayoutParams(-1, -1));
        restore.setOnClickListener(view -> {
            floating.showAgain(); restore.setVisibility(View.GONE);
            restored = true; completeStep(RESTORE);
            moveFeedback = "버튼을 다시 꺼냈어요.";
            updateCopy();
        });

        footer = vertical();
        footer.setPadding(0, 0, 0, dp(20));
        feedback = text("", 13, ClickUi.SUBTEXT, false);
        feedback.setGravity(Gravity.CENTER);
        feedback.setPadding(dp(4), dp(4), dp(4), dp(8));
        footer.addView(feedback, new LinearLayout.LayoutParams(-1, -2));
        TextView replay = text("안내 다시 듣기", 14, ClickUi.BLUE_DARK, false);
        replay.setGravity(Gravity.CENTER);
        replay.setPaintFlags(replay.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        replay.setFocusable(true);
        replay.setOnClickListener(v -> {
            if (narrator.isSpeaking()) narrator.stop();
            else { cancelRecording(); narrator.replay(lastCue); }
        });
        narrator.setListener((speaking, unavailable) -> {
            replay.setText(speaking ? "음성 멈춤" : "안내 다시 듣기");
            if (reviewView != null) reviewView.setNarrationState(speaking, unavailable);
        });
        footer.addView(replay, new LinearLayout.LayoutParams(-1, dp(48)));
        action = ClickUi.primaryButton(this, "");
        action.setId(R.id.tutorial_next);
        action.setTextSize(18);
        action.setOnClickListener(view -> advance());
        footer.addView(action, new LinearLayout.LayoutParams(-1, -2));
        column.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            column.setPadding(dp(18) + system.left, system.top, dp(18) + system.right, Math.max(system.bottom, ime.bottom));
            boolean visible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (keyboardVisible != visible) {
                keyboardVisible = visible;
                toolbar.setVisibility(visible ? View.GONE : View.VISIBLE);
                progress.setVisibility(visible ? View.GONE : View.VISIBLE);
                grandmother.setVisibility(visible ? View.GONE : View.VISIBLE);
                description.setVisibility(visible ? View.GONE : View.VISIBLE);
                footer.setVisibility(visible ? View.GONE : View.VISIBLE);
                title.setTextSize(visible ? 19 : 24);
                updateCopy();
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { previous(); }
        });
        restoring = true;
        if (savedInstanceState != null) {
            floating.getControl().setDraftText(savedInstanceState.getString("draft", ""));
            String mode = savedInstanceState.getString("floating_mode", "COLLAPSED");
            try { showMode(ClickOverlayLayout.Mode.valueOf(mode)); }
            catch (IllegalArgumentException ignored) { showMode(ClickOverlayLayout.Mode.COLLAPSED); }
            boolean hidden = savedInstanceState.getBoolean("floating_hidden");
            floating.restoreHidden(hidden);
            restore.setVisibility(hidden ? View.VISIBLE : View.GONE);
        }
        restoring = false;
        if (step == STOP) showStopPractice();
        if (step == REPLY && !messages.isEmpty()) showRequestReview(messages.get(messages.size() - 1));
        updateCopy();
    }

    private LinearLayout toolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setId(R.id.tutorial_back);
        back.setContentDescription("이전 설명");
        back.setImageResource(com.click.designpreview.R.drawable.onboarding_back);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(view -> previous());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView label = text("클릭 사용 방법", 16, ClickUi.BLUE, true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        counter = text("", 14, ClickUi.SUBTEXT, false);
        row.addView(counter, new LinearLayout.LayoutParams(-2, -2));
        Button skip = new Button(this);
        skip.setText("나중에"); skip.setAllCaps(false); skip.setTextColor(ClickUi.SUBTEXT);
        skip.setTextSize(14); skip.setBackgroundColor(Color.TRANSPARENT);
        skip.setOnClickListener(view -> finishToHome());
        row.addView(skip, new LinearLayout.LayoutParams(dp(72), dp(48)));
        return row;
    }

    private View coach() {
        LinearLayout coach = new LinearLayout(this);
        coach.setGravity(Gravity.CENTER_VERTICAL);
        coach.setPadding(dp(3), dp(8), dp(3), dp(10));
        grandmother = new ImageView(this);
        grandmother.setImageResource(com.click.designpreview.R.drawable.welcome);
        grandmother.setScaleType(ImageView.ScaleType.FIT_CENTER);
        grandmother.setContentDescription("클릭 사용법을 안내하는 할머니");
        coach.addView(grandmother, new LinearLayout.LayoutParams(dp(65), dp(86)));
        LinearLayout copy = vertical();
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.setMarginStart(dp(12));
        coach.addView(copy, copyParams);
        title = text("", 24, ClickUi.TEXT, true);
        title.setId(R.id.tutorial_title);
        title.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
        description = text("", 15, ClickUi.SUBTEXT, false);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(9);
        copy.addView(description, descriptionParams);
        return coach;
    }

    @Override public void onModeChanged(ClickOverlayLayout.Mode mode) {
        if (restoring || title == null || action == null) return;
        cancelRecording();
        if (step >= STOP) {
            if (step == FOLD && mode == ClickOverlayLayout.Mode.COLLAPSED) { folded = true; completeStep(FOLD); }
            updateCopy(); return;
        }
        if (mode == ClickOverlayLayout.Mode.MENU && step == OPEN) { completeStep(OPEN); step = WRITE; }
        if (mode == ClickOverlayLayout.Mode.TEXT && step != VOICE) step = WRITE;
        if (mode == ClickOverlayLayout.Mode.VOICE) {
            step = VOICE;
            floating.getControl().setRecognizedSpeech(voiceText.isEmpty()
                    ? "마이크를 누르고 말씀해 주세요." : voiceText);
        }
        updateCopy();
    }

    @Override public void onMicrophoneClicked() {
        if (floating.getControl().isVoiceInputActive()) onMicrophoneReleased();
        else onMicrophonePressed();
    }

    @Override public void onMicrophonePressed() {
        if (floating.getControl().isVoiceInputActive() || transcribing) return;
        narrator.stop();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 81);
            return;
        }
        startRecording();
    }

    @Override public void onMicrophoneReleased() {
        if (!floating.getControl().isVoiceInputActive() || transcribing) return;
        transcribing = true;
        floating.getControl().setVoiceInputActive(false);
        floating.getControl().setVoiceInputProcessing(true);
        floating.getControl().setRecognizedSpeech("말씀을 글자로 바꾸고 있어요…");
        speech.finish();
    }

    @Override public void onMicrophoneCancelled() {
        cancelRecording();
        floating.getControl().showVoiceInputError("입력을 취소했어요. 마이크를 꾹 눌러 다시 말씀해 주세요.");
        updateCopy();
    }

    private void startRecording() {
        narrator.stop();
        floating.getControl().setVoiceInputActive(true);
        floating.highlight(null, 0);
        floating.getControl().setRecognizedSpeech("마이크를 준비하고 있어요…");
        speech.start(new CloudSpeechRecognizer.Callback() {
            @Override public void onReady() {
                if (!transcribing) floating.getControl().setRecognizedSpeech("듣고 있어요. 말씀을 마치면 손을 떼세요.");
            }
            @Override public void onPartialText(String value) {
                floating.getControl().showRecognizedSpeechResult(value);
            }
            @Override public void onRecordingFinished() {
                floating.getControl().setVoiceInputActive(false); transcribing = true;
                floating.getControl().setVoiceInputProcessing(true);
                floating.getControl().setRecognizedSpeech("말씀을 글자로 바꾸고 있어요…");
            }
            @Override public void onSuccess(String value) {
                transcribing = false; floating.getControl().setVoiceInputActive(false);
                floating.getControl().setVoiceInputProcessing(false);
                if (value == null || value.trim().isEmpty()) { onFailure(new IllegalStateException()); return; }
                voiceText = value.trim(); voiceTried = true; completeStep(VOICE);
                updateCopy();
                floating.getControl().showRecognizedSpeechResult(voiceText);
            }
            @Override public void onFailure(Throwable error) {
                transcribing = false; floating.getControl().setVoiceInputActive(false);
                floating.getControl().setVoiceInputProcessing(false);
                com.click.service.overlay.SpeechErrorMessages.log(error);
                String message = com.click.service.overlay.SpeechErrorMessages.message(error);
                updateCopy();
                floating.getControl().showVoiceInputError(message); narrator.speak(message);
            }
        });
    }
    private void cancelRecording() {
        if (speech != null) speech.cancel(); transcribing = false;
        if (floating != null) {
            floating.getControl().setVoiceInputActive(false);
            floating.getControl().setVoiceInputProcessing(false);
        }
    }
    @Override public void onRequestPermissionsResult(int code, @NonNull String[] permissions, @NonNull int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        // Permission confirmation does not open the microphone automatically.
        if (code == 81) {
            String message = getString(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED
                    ? R.string.tutorial_speech_microphone_ready : R.string.tutorial_speech_microphone_denied);
            floating.getControl().showVoiceInputError(message); lastCue = message; narrator.speak(message);
        }
    }

    @Override public void onStopRequested() {
        if (step != STOP) return;
        stopTried = true; completeStep(STOP);
        floating.getControl().setRequestStatus("요청을 멈췄어요", false);
        floating.getControl().setRecognizedSpeech("작업을 중지했어요.\n새 입력은 마이크를 눌러 시작해요.");
        updateCopy();
    }

    private void showStopPractice() {
        cancelRecording();
        floating.getControl().setRequestControlsVisible(true);
        floating.getControl().setRequestStatus(stopTried ? "요청을 멈췄어요" : "사용자 요청을 처리하고 있어요", !stopTried);
        floating.getControl().setRecognizedSpeech(stopTried
                ? "작업을 중지했어요.\n새 입력은 마이크를 눌러 시작해요."
                : "처리 중에도 ‘요청 중지’를 누르면\n작업을 멈출 수 있어요.");
    }

    @Override public void onTextSubmitted(String value) {
        if (value.trim().isEmpty()) return;
        completeStep(WRITE);
        messages.add(value);
        while (messages.size() > 3) messages.remove(0);
        reviewConfirmed = false; reviewActions.clear(); step = REPLY;
        showRequestReview(value);
        updateCopy();
    }
    private boolean reviewChoicePending;

    private void showRequestReview(String value) {
        reviewChoicePending = false;
        practiceReview = new RequirementReview(value);
        if (!practiceReview.isFont()) practiceReview = RequirementReview.forGoal(value, new JSONObject(), value);
        reviewView = new TaskFlowOverlayView(this, new TaskFlowOverlayView.Listener() {
            public void collapse() { floating.collapseReview(); updateCopy(); }
            public void openInput() { control("edit"); }
            public void chooseGoal(JSONObject goal) {} public void clarify(String choice) {}
            public void rephrase(String text) { requirementAnswer(text); }
            public void result(String answer) {} public void adjust(String reason) {}
            public void control(String command) {
                narrator.stop(); floating.hideReview(); reviewView = null; step = WRITE;
                showMode(ClickOverlayLayout.Mode.MENU); updateCopy();
            }
            public void resetGoalInputs() { control("edit"); }
            public void onReviewChoiceSelected() { reviewChoicePending = true; updateCopy(); }
            public void requirementChoice(String choice) {
                reviewChoicePending = false;
                reviewConfirmed = false; reviewActions.add("choice:" + choice);
                practiceReview.choose(choice); renderReview(); updateCopy();
            }
            public void requirementAnswer(String answer) {
                reviewConfirmed = false; reviewActions.add("answer:" + answer);
                practiceReview.update(answer); renderReview(); updateCopy();
            }
            public void confirmRequirements() {
                reviewConfirmed = true; completeStep(REPLY); renderReview(); updateCopy();
            }
            public void replayGuidance(String message) { narrator.replay(message); }
            public void stopGuidance() { narrator.stop(); }
        });
        for (String answer : reviewActions) {
            if (answer.startsWith("choice:")) practiceReview.choose(answer.substring(7));
            else if (answer.startsWith("answer:")) practiceReview.update(answer.substring(7));
        }
        floating.showReview(reviewView); renderReview();
    }
    private void renderReview() {
        boolean ready = !practiceReview.isFont() || (!practiceReview.needsTarget() && !practiceReview.needsDirection() && practiceReview.isPhone());
        reviewView.requirements(practiceReview, ready, getString(reviewSpeechResource()));
        reviewView.setNarrationState(false, false);
        floating.highlight(reviewChoicePending ? "다음 단계" : ready ? "이대로 시작하기"
                : practiceReview.needsTarget() ? "휴대폰 기본 글자" : "더 크게", 0);
    }
    private int reviewSpeechResource() {
        if (reviewConfirmed) return R.string.tutorial_speech_review_confirmed;
        if (practiceReview == null) return R.string.tutorial_speech_review_intro;
        if (practiceReview.needsTarget()) return practiceReview.appOnly()
                ? R.string.tutorial_speech_review_app : R.string.tutorial_speech_review_target;
        if (practiceReview.needsDirection()) return R.string.tutorial_speech_review_direction;
        if (practiceReview.isFont() && !practiceReview.isPhone()) return R.string.tutorial_speech_review_unsupported;
        return R.string.tutorial_speech_review_ready;
    }
    @Override public void onHidden() {
        cancelRecording();
        restore.setVisibility(View.VISIBLE);
        if (step == HIDE) { completeStep(HIDE); step = RESTORE; }
        moveFeedback = "버튼을 숨겼어요. 다시 보기 버튼을 눌러 꺼내보세요.";
        updateCopy();
    }
    @Override public void onMoved() {
        if (step == MOVE) { moved = true; completeStep(MOVE); }
        moveFeedback = "잘했어요! 버튼을 편한 곳으로 옮겼어요.";
        updateCopy();
    }

    private void updateCopy() {
        boolean overviewPage = step == INTRO || step == COMPLETE;
        overview.setVisibility(overviewPage ? View.VISIBLE : View.GONE);
        phone.setVisibility(overviewPage ? View.GONE : View.VISIBLE);
        floating.setVisibility(overviewPage ? View.GONE : View.VISIBLE);
        restore.setVisibility(!overviewPage && floating.isHidden() ? View.VISIBLE : View.GONE);
        progress.setVisibility(overviewPage || keyboardVisible ? View.GONE : View.VISIBLE);
        counter.setText(overviewPage ? (step == COMPLETE ? "완료" : "시작 안내")
                : getString(R.string.click_tutorial_progress, step + 1, STEP_COUNT));
        if (overviewPage) renderChecklist();
        progress.setContentDescription("클릭 기능 설명 " + (step + 1) + "단계, 전체 " + STEP_COUNT + "단계");
        for (int i = 0; i < STEP_COUNT; i++) progress.getChildAt(i).setBackground(ClickUi.rounded(i <= step ? ClickUi.BLUE : ClickUi.BORDER, dp(3)));
        String heading, detail, button;
        int speechResource;
        boolean enabled = false;
        ClickOverlayLayout.Mode mode = floating.getControl().getMode();
        floating.setHint(step == MOVE ? "누른 채 옮겨보세요" : step == HIDE ? "꾹 누르고 휴지통으로" : "여기를 눌러보세요");
        floating.setClickHighlighted(step == OPEN || (step == MOVE && !moved) || step == HIDE);
        floating.highlight(null, 0);
        switch (step) {
            case INTRO:
                speechResource = R.string.tutorial_speech_intro;
                heading = "클릭 튜토리얼을\n시작해볼게요!";
                detail = "클릭의 기능은 다음과 같아요.";
                button = "시작하기"; enabled = true; break;
            case COMPLETE:
                speechResource = R.string.tutorial_speech_complete;
                heading = "이제 준비됐어요!";
                detail = "모든 연습을 마쳤어요.\n이제 클릭에게 편하게 요청해 보세요.";
                button = "클릭 시작하기"; enabled = completedSteps == (1 << STEP_COUNT) - 1; break;
            case OPEN:
                speechResource = R.string.tutorial_speech_open;
                heading = "클릭과 함께\n시작해 볼까요?"; detail = "아래 클릭 버튼을 눌러보세요."; button = "다음"; break;
            case WRITE:
                boolean input = mode == ClickOverlayLayout.Mode.TEXT;
                speechResource = input ? R.string.tutorial_speech_write_input : R.string.tutorial_speech_write_open;
                heading = input ? "질문을 적고\n↑ 버튼을 눌러보세요" : "‘글로 입력하기’를\n눌러보세요";
                detail = input ? "‘글자 크기 키워줘’를 입력해 보세요.\n입력한 뒤 화살표를 눌러 보내요." : "아래 ‘글로 입력하기’ 버튼을 눌러보세요.";
                floating.highlight(input ? "↑" : "글로 입력하기", 0); button = "다음"; break;
            case REPLY:
                speechResource = reviewSpeechResource();
                heading = reviewConfirmed ? "요청을 확인했어요" : "내 요청을 확인해요";
                detail = reviewConfirmed ? "다음은 마이크로 말하는 연습이에요." : "필요한 범위를 고르고 내용을 확인해요.\n수정이 필요하면 ‘내용 수정하기’를 눌러요.";
                button = "다음"; enabled = reviewConfirmed;
                if (!reviewConfirmed && practiceReview != null) floating.highlight(reviewChoicePending ? "다음 단계" : practiceReview.needsTarget() ? "휴대폰 기본 글자" : practiceReview.needsDirection() ? "더 크게" : "이대로 시작하기", 0); break;
            case VOICE:
                speechResource = voiceTried ? R.string.tutorial_speech_voice_received
                        : mode == ClickOverlayLayout.Mode.VOICE ? R.string.tutorial_speech_voice_record : R.string.tutorial_speech_voice_open;
                heading = voiceTried ? "말씀이 글자로 나타나요" : mode == ClickOverlayLayout.Mode.VOICE ? "마이크를 꾹 누르고\n직접 말해보세요" : "‘말로 입력하기’를\n눌러보세요";
                detail = voiceTried ? "인식한 말을 확인한 뒤 다음을 눌러요." : "누른 채 말하고, 끝나면 손을 떼세요.\n예: 휴대폰 글자 크기 키워줘";
                if (!voiceTried && !transcribing && !floating.getControl().isVoiceInputActive())
                    floating.highlight(mode == ClickOverlayLayout.Mode.VOICE ? null : "말로 입력하기",
                            mode == ClickOverlayLayout.Mode.VOICE ? R.id.overlay_microphone : 0);
                button = "다음"; enabled = voiceTried; break;
            case STOP:
                speechResource = stopTried ? R.string.tutorial_speech_stopped : R.string.tutorial_speech_stop;
                heading = stopTried ? "요청을 멈췄어요" : "‘요청 중지’를\n눌러보세요";
                detail = stopTried ? "이제 창을 접는 연습을 해볼게요." : "처리 중에도 바로 멈출 수 있어요.";
                floating.highlight(null, stopTried ? 0 : R.id.overlay_stop_request);
                button = "다음"; enabled = stopTried; break;
            case FOLD:
                speechResource = folded ? R.string.tutorial_speech_folded : R.string.tutorial_speech_fold;
                heading = folded ? "작은 버튼으로 줄였어요" : "오른쪽 위 ‘줄이기’를 눌러보세요";
                detail = folded ? "필요할 때 버튼을 누르면 다시 열려요." : "‘줄이기’를 누르면 작은 클릭 버튼으로 접혀요.";
                if (!folded) floating.highlight(null, R.id.overlay_collapse); button = "다음"; enabled = folded; break;
            case MOVE:
                speechResource = moved ? R.string.tutorial_speech_moved : R.string.tutorial_speech_move;
                heading = moved ? "원하는 곳으로 옮겼어요" : "버튼을 끌어서\n옮겨보세요";
                detail = moved ? "다음은 휴지통으로 숨기는 연습이에요." : "클릭 버튼을 누른 채 다른 곳으로 움직여요.";
                button = "다음"; enabled = moved; break;
            case HIDE:
                speechResource = R.string.tutorial_speech_hide;
                heading = "꾹 누른 뒤\n휴지통에 놓아보세요";
                detail = "클릭 버튼을 꾹 누르면 휴지통이 나타나요.\n손을 떼지 말고 휴지통까지 끌어 놓아요.";
                button = "다음"; break;
            default:
                speechResource = restored ? R.string.tutorial_speech_restored : R.string.tutorial_speech_restore;
                heading = restored ? "직접 사용해 볼 준비가 됐어요" : "숨긴 버튼을\n다시 꺼내보세요";
                detail = restored ? "글과 말로 요청하고, 접고, 숨겨봤어요." : "아래 ‘클릭 버튼 다시 보기’를 눌러요.\n실제 앱에서는 홈에서 다시 켤 수 있어요.";
                button = "배운 내용 확인하기"; enabled = restored; break;
        }
        boolean collapsedReview = step == REPLY && reviewView != null && reviewView.getVisibility() != View.VISIBLE;
        if (mode == ClickOverlayLayout.Mode.COLLAPSED && step > OPEN && step < FOLD
                && (step != REPLY || collapsedReview)) {
            floating.highlight(null, 0); floating.setClickHighlighted(true);
            heading = "클릭 버튼을 다시 누르면\n메뉴가 열려요";
            detail = "‘줄이기’는 창을 작게 접는 버튼이에요.\n클릭 버튼을 눌러 다시 이어서 해보세요.";
            speechResource = R.string.tutorial_speech_reopen;
        }
        title.setText(keyboardVisible ? "질문을 적고 ↑를 눌러주세요" : heading);
        description.setText(detail);
        action.setText(button);
        action.setEnabled(enabled);
        action.setAlpha(enabled ? 1f : .52f);
        if (restore.getVisibility() == View.VISIBLE && !restored) {
            floating.setClickHighlighted(false);
            floating.highlight(restore);
        } else if (enabled) {
            floating.setClickHighlighted(false);
            floating.highlight(action);
        }
        feedback.setVisibility(overviewPage ? View.GONE : View.VISIBLE);
        feedback.setText("연습 화면 · 휴대폰 설정은 바뀌지 않아요");
        // Keep the upstream speech resources independent from visual copy.
        String cue = speechResource == R.string.tutorial_speech_voice_received
                ? getString(speechResource, voiceText) : getString(speechResource);
        cue = reviewedScripts.forSource(cue);
        if (!cue.equals(lastCue)) {
            lastCue = cue;
            cuePending = true;
        }
        if (cuePending && !keyboardVisible && !floating.getControl().isVoiceInputActive() && !transcribing) {
            cuePending = false;
            if (cue.trim().isEmpty()) narrator.stop();
            else narrator.speak(cue);
        }
    }

    private void completeStep(int completed) { completedSteps |= 1 << completed; }

    private void renderChecklist() {
        checklist.removeAllViews();
        checklist.setBackgroundResource(com.click.designpreview.R.drawable.onboarding_feature_card);
        checklist.setPadding(dp(22), dp(22), dp(22), dp(22));
        String[] labels = {"클릭 버튼 열기", "글로 입력하고 요청 확인하기", "마이크로 말하기", "진행 중인 요청 멈추기", "창 접고 버튼 옮기기", "버튼 숨기고 다시 꺼내기"};
        int[] masks = {1 << OPEN, (1 << WRITE) | (1 << REPLY), 1 << VOICE, 1 << STOP,
                (1 << FOLD) | (1 << MOVE), (1 << HIDE) | (1 << RESTORE)};
        for (int i = 0; i < labels.length; i++) {
            boolean done = (completedSteps & masks[i]) == masks[i];
            TextView label = new TextView(this, null, 0,
                    com.click.designpreview.R.style.OnboardingFeatureLabel);
            label.setText(labels[i]);
            label.setContentDescription(labels[i] + (done ? ", 완료" : ", 연습 전"));
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    com.click.designpreview.OnboardingDrawables.checklistMark(getResources(), done),
                    null, null, null);
            checklist.addView(label, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void advance() {
        if (!action.isEnabled()) return;
        cancelRecording(); narrator.stop(); floating.hideReview(); reviewView = null;
        restoring = true;
        if (step == INTRO) { step = OPEN; showMode(ClickOverlayLayout.Mode.COLLAPSED); }
        else if (step == COMPLETE) { finishToHome(); return; }
        else if (step == REPLY) { step = VOICE; voiceTried = false; voiceText = ""; showMode(ClickOverlayLayout.Mode.MENU); }
        else if (step == VOICE) { step = STOP; stopTried = false; showMode(ClickOverlayLayout.Mode.VOICE); showStopPractice(); }
        else if (step == STOP) { step = FOLD; folded = false; floating.getControl().setRequestControlsVisible(false); floating.getControl().setRequestStatus(null, false); showMode(ClickOverlayLayout.Mode.MENU); }
        else if (step == FOLD) { step = MOVE; moved = false; }
        else if (step == MOVE) { step = HIDE; }
        else if (step == RESTORE) {
            step = COMPLETE;
            // Only completed interactions earn a check; revisit any practice opened out of order.
            for (int i = OPEN; i <= RESTORE; i++) if ((completedSteps & (1 << i)) == 0) { step = i; break; }
            if (step == OPEN || step >= MOVE && step < COMPLETE) showMode(ClickOverlayLayout.Mode.COLLAPSED);
            else if (step == WRITE || step == VOICE || step == FOLD) showMode(ClickOverlayLayout.Mode.MENU);
            else if (step == REPLY && !messages.isEmpty()) showRequestReview(messages.get(messages.size() - 1));
            else if (step == STOP) { showMode(ClickOverlayLayout.Mode.VOICE); showStopPractice(); }
        }
        restoring = false; updateCopy();
    }

    private void previous() {
        if (step == INTRO) { finishToHome(); return; }
        cancelRecording(); narrator.stop(); floating.hideReview(); reviewView = null;
        restoring = true; floating.getControl().setRequestControlsVisible(false); floating.getControl().setRequestStatus(null, false);
        step--; floating.showAgain(); restore.setVisibility(View.GONE);
        if (step == OPEN || step >= MOVE) showMode(ClickOverlayLayout.Mode.COLLAPSED);
        else if (step == WRITE || step == FOLD || step == VOICE) showMode(ClickOverlayLayout.Mode.MENU);
        else if (step == REPLY && !messages.isEmpty()) showRequestReview(messages.get(messages.size() - 1));
        else if (step == STOP) { showMode(ClickOverlayLayout.Mode.VOICE); showStopPractice(); }
        restoring = false; updateCopy();
    }

    private void showMode(ClickOverlayLayout.Mode mode) {
        switch (mode) {
            case TEXT: floating.getControl().openText(); break;
            case MENU: floating.getControl().openMenu(); break;
            case VOICE:
                floating.getControl().setRecognizedSpeech("마이크를 누르고 말씀해 주세요.");
                floating.getControl().openVoice(); break;
            default: floating.getControl().collapse(); break;
        }
    }

    private void finishToHome() {
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putInt("feature_step", step);
        state.putInt("completed_steps", completedSteps);
        state.putBoolean("voice_tried", voiceTried);
        state.putStringArrayList("review_actions", new ArrayList<>(reviewActions));
        state.putString("voice_text", voiceText); state.putBoolean("review_confirmed", reviewConfirmed);
        state.putBoolean("folded", folded); state.putBoolean("moved", moved); state.putBoolean("restored", restored);
        state.putBoolean("stop_tried", stopTried);
        state.putString("move_feedback", moveFeedback);
        state.putStringArrayList("practice_messages", new ArrayList<>(messages));
        state.putString("draft", floating.getControl().getDraftText());
        state.putString("floating_mode", floating.getControl().getMode().name());
        state.putBoolean("floating_hidden", floating.isHidden());
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume(); narrator.resume();
    }
    @Override protected void onPause() {
        cancelRecording(); narrator.pause(); super.onPause();
    }
    @Override protected void onDestroy() {
        speech.close(); narrator.close(); super.onDestroy();
    }
    private TextView text(String value, int size, int color, boolean strong) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setTypeface(getResources().getFont(strong ? com.click.designpreview.R.font.pretendard_bold : com.click.designpreview.R.font.pretendard_medium));
        text.setIncludeFontPadding(false); text.setLineSpacing(dp(3), 1.08f);
        return text;
    }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private int dp(int value) { return ClickUi.dp(this, value); }
}
