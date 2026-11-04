package com.click;

import android.content.Intent;
import android.graphics.Color;
import android.widget.ScrollView;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
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
import com.click.ui.common.ClickTextScale;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.TaskFlowOverlayView;
import com.click.domain.goal.RequirementReview;
import com.click.designpreview.TutorialNarrator;
import com.click.designpreview.ClickBrandView;
import org.json.JSONObject;
import com.click.ui.tutorial.TutorialArtworkView;
import com.click.ui.tutorial.TutorialFloatingHost;
import java.util.ArrayList;

/** A feature tour on the same phone backdrop as the accessibility guide, using the real controls. */
public final class TutorialActivity extends AppCompatActivity implements TutorialFloatingHost.Listener {
    public static final String EXTRA_FROM_SIGN_UP = "from_sign_up";
    private static final int INTRO = -1, INPUT = 0, REVIEW = 1, RUNNING = 2, RESULT = 3, COMPLETE = 4, STEP_COUNT = 4;
    private static final String PRACTICE_REQUEST = "휴대폰 전체 글자 크기 키워줘";
    private final ArrayList<String> messages = new ArrayList<>();
    private final ArrayList<String> reviewActions = new ArrayList<>();
    private TutorialFloatingHost floating;
    private LinearLayout column, lesson, toolbar, progress, footer, checklist;
    private ScrollView lessonScroll;
    private FrameLayout stage;
    private View phone;
    private TextView title, description;
    private Button action, restore;
    private int step = INTRO, completedSteps, displayedStep = Integer.MIN_VALUE;
    private TutorialNarrator narrator;
    private com.click.ui.tutorial.ReviewedTutorialScripts reviewedScripts;
    private boolean paused;
    private String lastCue = "";
    private boolean cuePending;
    private RequirementReview practiceReview;
    private TaskFlowOverlayView reviewView;
    private boolean restoring, keyboardVisible;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        narrator = new TutorialNarrator(this);
        reviewedScripts = new com.click.ui.tutorial.ReviewedTutorialScripts(this);
        if (savedInstanceState != null) {
            step = Math.max(INTRO, Math.min(COMPLETE, savedInstanceState.getInt("feature_step", INTRO)));
            completedSteps = savedInstanceState.getInt("completed_steps");
            paused = savedInstanceState.getBoolean("paused");
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
        column = vertical();
        root.addView(column, new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels, dp(500)), -1, Gravity.CENTER_HORIZONTAL));
        toolbar = toolbar();
        column.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        // 제목과 연습창을 한 흐름으로 스크롤한다. 큰 글씨가 연습창 높이를 빼앗지 않게 한다.
        lessonScroll = new ScrollView(this);
        lessonScroll.setFillViewport(false);
        lessonScroll.setClipToPadding(false);
        lessonScroll.setVerticalScrollBarEnabled(false);
        lesson = vertical();
        lesson.setPadding(0, dp(12), 0, dp(12));
        lessonScroll.addView(lesson, new ScrollView.LayoutParams(-1, -2));
        column.addView(lessonScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        progress = new LinearLayout(this);
        progress.setGravity(Gravity.CENTER);
        progress.setPadding(dp(5), dp(6), dp(5), dp(9));
        for (int i = 0; i < STEP_COUNT; i++) {
            View bar = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(4), 1);
            params.setMargins(dp(3), 0, dp(3), 0);
            progress.addView(bar, params);
        }
        lesson.addView(progress, new LinearLayout.LayoutParams(-1, dp(20)));
        lesson.addView(coach(), new LinearLayout.LayoutParams(-1, -2));

        stage = new FrameLayout(this);
        stage.setId(R.id.tutorial_phone_stage);
        stage.setMinimumHeight(dp(320));
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(-1, -2);
        stageParams.setMargins(0, dp(16), 0, dp(8));
        lesson.addView(stage, stageParams);
        PermissionPhoneView phoneView = new PermissionPhoneView(this, true, true);
        phoneView.fillAvailableHeight();
        phone = phoneView;
        phone.setAlpha(.32f);
        FrameLayout.LayoutParams phoneParams = new FrameLayout.LayoutParams(-1, -1);
        stage.addView(phone, phoneParams);

        restore = ClickUi.secondaryButton(this, "클릭 버튼 다시 보기");
        restore.setId(R.id.tutorial_restore);
        restore.setBackground(ClickUi.outlined(0xFFF1F6FF, TutorialFloatingHost.HIGHLIGHT, dp(18), dp(4)));
        restore.setTextColor(ClickUi.BLUE);
        restore.setVisibility(View.GONE);
        restore.setMinHeight(dp(56));
        restore.setPadding(dp(12), dp(14), dp(12), dp(14));
        FrameLayout.LayoutParams restoreParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        restoreParams.setMargins(dp(12), 0, dp(12), dp(20));
        stage.addView(restore, restoreParams);
        floating = new TutorialFloatingHost(this, this);
        floating.setId(R.id.tutorial_floating_host);
        floating.getControl().setId(R.id.tutorial_floating_control);
        // Match the phone stage so dragging reaches its top as well as its bottom.
        FrameLayout.LayoutParams floatingParams = new FrameLayout.LayoutParams(-1, -1,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        stage.addView(floating, floatingParams);
        checklist = vertical();
        lesson.addView(checklist, new LinearLayout.LayoutParams(-1, -2));
        restore.setOnClickListener(view -> {
            floating.showAgain(); restore.setVisibility(View.GONE);
            updateCopy();
        });

        footer = vertical();
        footer.setPadding(0, dp(10), 0, dp(6));
        TextView replay = text("안내 다시 듣기", 15, ClickUi.BLUE_DARK, false);
        replay.setGravity(Gravity.CENTER);
        replay.setMinHeight(dp(48));
        replay.setPadding(dp(12), dp(12), dp(12), dp(12));
        replay.setFocusable(true);
        replay.setOnClickListener(v -> {
            if (narrator.isSpeaking()) narrator.stop();
            else { cancelVoicePractice(); narrator.replay(lastCue); }
        });
        narrator.setListener((speaking, unavailable) -> {
            replay.setText(speaking ? "음성 멈춤" : "안내 다시 듣기");
            if (reviewView != null) reviewView.setNarrationState(speaking, unavailable);
        });
        action = ClickUi.primaryButton(this, "");
        action.setId(R.id.tutorial_next);
        action.setMinHeight(dp(64));
        action.setPadding(dp(18), dp(15), dp(18), dp(15));
        action.setOnClickListener(view -> advance());
        footer.addView(replay, new LinearLayout.LayoutParams(-1, -2));
        footer.addView(action, new LinearLayout.LayoutParams(-1, -2));
        column.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            column.setPadding(dp(22) + system.left, dp(12) + system.top,
                    dp(22) + system.right, dp(12) + Math.max(system.bottom, ime.bottom));
            boolean visible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (keyboardVisible != visible) {
                keyboardVisible = visible;
                // 키보드가 열려도 뒤로 가기/나중에 버튼은 같은 자리에 유지한다.
                progress.setVisibility(visible ? View.GONE : View.VISIBLE);
                description.setVisibility(visible ? View.GONE : View.VISIBLE);
                footer.setVisibility(visible ? View.GONE : View.VISIBLE);
                title.setTextSize(ClickTextScale.size(this, visible ? 19 : 24));
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
        if (step == REVIEW && !messages.isEmpty()) showRequestReview(lastRequest());
        if (step == RUNNING) showProgressPractice();
        if (step == RESULT) showResultPractice();
        if (paused && reviewView != null) reviewView.paused();
        if (savedInstanceState != null && savedInstanceState.getBoolean("panel_collapsed") && reviewView != null)
            floating.collapseReview();
        updateCopy();
    }

    private LinearLayout toolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setMinimumHeight(dp(56));
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setId(R.id.tutorial_back);
        back.setContentDescription("이전 설명");
        back.setImageResource(com.click.designpreview.R.drawable.onboarding_back);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(view -> previous());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ClickBrandView brand = new ClickBrandView(this, null);
        brand.setContentDescription("클릭 사용 방법");
        row.addView(brand, new LinearLayout.LayoutParams(dp(96), dp(34)));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        Button skip = new Button(this);
        skip.setText("나중에"); skip.setAllCaps(false); skip.setTextColor(ClickUi.SUBTEXT);
        skip.setTextSize(ClickTextScale.size(this, 15)); skip.setBackgroundColor(Color.TRANSPARENT);
        skip.setMinWidth(0); skip.setMinimumWidth(0); skip.setMinHeight(dp(48));
        skip.setPadding(dp(10), dp(10), dp(10), dp(10));
        skip.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        skip.setOnClickListener(view -> finishToHome());
        row.addView(skip, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private View coach() {
        LinearLayout coach = vertical();
        coach.setPadding(dp(2), dp(6), dp(2), dp(8));
        title = text("", 24, ClickUi.TEXT, true);
        title.setId(R.id.tutorial_title);
        title.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        coach.addView(title, new LinearLayout.LayoutParams(-1, -2));
        description = text("", 16, ClickUi.SUBTEXT, false);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(9);
        coach.addView(description, descriptionParams);
        return coach;
    }

    @Override public void onModeChanged(ClickOverlayLayout.Mode mode) {
        if (restoring || title == null || action == null) return;
        cancelVoicePractice();
        updateCopy();
    }

    @Override public void onMicrophoneClicked() {
        if (floating.getControl().isVoiceInputActive()) onMicrophoneReleased();
        else onMicrophonePressed();
    }

    @Override public void onMicrophonePressed() {
        if (step != INPUT || floating.getControl().getMode() != ClickOverlayLayout.Mode.VOICE
                || floating.getControl().isVoiceInputActive()) return;
        narrator.stop();
        floating.getControl().setVoiceInputActive(true);
        floating.highlight(null, 0);
        floating.getControl().setRecognizedSpeech("마이크를 누른 채 요청을 말하고, 끝나면 손을 떼세요.");
        // 연습은 실제 녹음·마이크 권한·서버 연결 없이 진행한다.
    }

    @Override public void onMicrophoneReleased() {
        if (!floating.getControl().isVoiceInputActive()) return;
        cancelVoicePractice();
        onTextSubmitted(PRACTICE_REQUEST);
    }

    @Override public void onMicrophoneCancelled() {
        if (step != INPUT || !floating.getControl().isVoiceInputActive()) return;
        cancelVoicePractice();
        floating.getControl().setRecognizedSpeech("입력을 멈췄어요. 마이크를 다시 누른 채 말해 주세요.");
    }

    private void cancelVoicePractice() {
        if (floating != null) {
            floating.getControl().setVoiceInputActive(false);
            floating.getControl().setVoiceInputProcessing(false);
        }
    }

    @Override public void onStopRequested() { returnToInput(false); }

    @Override public void onTextSubmitted(String value) {
        if (value == null || value.trim().isEmpty()) return;
        cancelVoicePractice();
        messages.clear(); messages.add(value.trim());
        completeStep(INPUT); reviewActions.clear(); step = REVIEW;
        showRequestReview(lastRequest()); updateCopy();
    }
    private String lastRequest() { return messages.isEmpty() ? PRACTICE_REQUEST : messages.get(messages.size() - 1); }

    private TaskFlowOverlayView newPanel() {
        reviewView = new TaskFlowOverlayView(this, new TaskFlowOverlayView.Listener() {
            public void collapse() { floating.collapseReview(); updateCopy(); }
            public void expand() { reviewView.setMinimizedProgress(false); updateCopy(); }
            public void openInput() { returnToInput(true); }
            public void chooseGoal(JSONObject goal) {} public void clarify(String choice) {}
            public void rephrase(String text) { requirementAnswer(text); }
            public void result(String answer) {
                if ("satisfied".equals(answer)) {
                    completeStep(RESULT); step = COMPLETE; floating.hideReview(); reviewView = null; updateCopy();
                } else {
                    paused = false; step = RUNNING; showProgressPractice(); updateCopy();
                }
            }
            public void adjust(String reason) {}
            public void control(String command) {
                narrator.stop();
                if ("pause_task".equals(command)) { paused = true; reviewView.paused(); updateCopy(); }
                else if ("resume_task".equals(command)) {
                    paused = false;
                    if (step == REVIEW) showRequestReview(lastRequest());
                    else if (step == RESULT) showResultPractice();
                    else showProgressPractice();
                    updateCopy();
                }
                else returnToInput(false);
            }
            public void resetGoalInputs() { returnToInput(true); }
            public void requirementChoice(String choice) {
                reviewActions.add("choice:" + choice); practiceReview.choose(choice); renderReview(); updateCopy();
            }
            public void requirementAnswer(String answer) {
                reviewActions.add("answer:" + answer); practiceReview.update(answer); renderReview(); updateCopy();
            }
            public void confirmRequirements() {
                completeStep(REVIEW); step = RUNNING; paused = false; showProgressPractice(); updateCopy();
            }
            public void replayGuidance(String message) { narrator.replay(message); }
            public void stopGuidance() { narrator.stop(); }
        });
        floating.showReview(reviewView);
        return reviewView;
    }

    private void showRequestReview(String value) {
        practiceReview = new RequirementReview(value);
        if (!practiceReview.isFont()) practiceReview = RequirementReview.forGoal(value, new JSONObject(), value);
        for (String answer : reviewActions) {
            if (answer.startsWith("choice:")) practiceReview.choose(answer.substring(7));
            else if (answer.startsWith("answer:")) practiceReview.update(answer.substring(7));
        }
        newPanel(); renderReview();
    }
    private void renderReview() {
        boolean ready = !practiceReview.isFont() || (!practiceReview.needsTarget() && !practiceReview.needsDirection() && practiceReview.isPhone());
        reviewView.requirements(practiceReview, ready, getString(reviewSpeechResource()));
    }
    private int reviewSpeechResource() {
        if (practiceReview == null) return R.string.tutorial_speech_review_intro;
        if (practiceReview.needsTarget()) return practiceReview.appOnly()
                ? R.string.tutorial_speech_review_app : R.string.tutorial_speech_review_target;
        if (practiceReview.needsDirection()) return R.string.tutorial_speech_review_direction;
        if (practiceReview.isFont() && !practiceReview.isPhone()) return R.string.tutorial_speech_review_unsupported;
        return R.string.tutorial_speech_review_ready;
    }
    private void showProgressPractice() {
        TaskFlowOverlayView panel = newPanel();
        if (paused) panel.paused();
        else {
            panel.progress(lastRequest(), null, "설정 앱을 열어주세요.", false);
            panel.setAutomaticProgress(true);
        }
    }
    private void showResultPractice() {
        JSONObject prompt = new JSONObject();
        try { prompt.put("message", "글씨가 잘 보이나요?"); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        newPanel().result(prompt, true);
    }
    private void returnToInput(boolean edit) {
        cancelVoicePractice(); narrator.stop(); floating.hideReview(); reviewView = null;
        step = INPUT; paused = false;
        restoring = true; floating.showAgain();
        floating.getControl().setDraftText(edit ? lastRequest() : "");
        showMode(edit ? ClickOverlayLayout.Mode.TEXT : ClickOverlayLayout.Mode.MENU);
        restoring = false; updateCopy();
    }
    @Override public void onHidden() { cancelVoicePractice(); updateCopy(); }
    @Override public void onMoved() { updateCopy(); }

    private void updateCopy() {
        if (displayedStep != step) {
            displayedStep = step;
            lessonScroll.post(() -> lessonScroll.scrollTo(0, 0));
        }
        boolean overviewPage = step == INTRO || step == COMPLETE;
        // 하단 버튼은 스크롤 본문 밖에 고정한다. 긴 설명만 스크롤하고 시작 버튼은 아래에 유지한다.
        checklist.setVisibility(overviewPage ? View.VISIBLE : View.GONE);
        stage.setVisibility(overviewPage ? View.GONE : View.VISIBLE);
        stage.setMinimumHeight(dp(step == INPUT ? 440 : 320));
        phone.setVisibility(step == INPUT ? View.VISIBLE : View.GONE);
        floating.setVisibility(overviewPage ? View.GONE : View.VISIBLE);
        restore.setVisibility(!overviewPage && floating.isHidden() ? View.VISIBLE : View.GONE);
        progress.setVisibility(overviewPage || keyboardVisible ? View.GONE : View.VISIBLE);
        if (overviewPage) renderChecklist();
        progress.setContentDescription("클릭 사용법 " + (step + 1) + "단계, 전체 " + STEP_COUNT + "단계");
        for (int i = 0; i < STEP_COUNT; i++) progress.getChildAt(i).setBackground(ClickUi.rounded(i <= step ? ClickUi.BLUE : ClickUi.BORDER, dp(3)));
        String heading, detail, button = "다음";
        int speechResource;
        boolean enabled = false;
        ClickOverlayLayout.Mode mode = floating.getControl().getMode();
        floating.setHint("클릭 버튼을 눌러보세요");
        floating.setClickHighlighted(false); floating.highlight(null, 0);
        switch (step) {
            case INTRO:
                heading = "클릭을 함께 연습해요";
                detail = "요청하고 확인하는 방법을 알아봐요.";
                speechResource = R.string.tutorial_speech_intro;
                button = "연습 시작하기"; enabled = true; break;
            case COMPLETE:
                heading = "이제 사용할 준비가 됐어요!";
                detail = "도움이 필요할 때 클릭을 불러주세요.";
                speechResource = R.string.tutorial_speech_complete;
                button = "홈으로 가기"; enabled = true; break;
            case INPUT:
                heading = "편한 방법으로 요청해 보세요";
                speechResource = R.string.tutorial_speech_input;
                detail = "말이나 글 중 하나를 골라주세요.";
                if (mode == ClickOverlayLayout.Mode.COLLAPSED) {
                    detail = "아래 클릭 버튼을 눌러 시작해요.";
                    speechResource = R.string.tutorial_speech_open;
                    floating.setClickHighlighted(true);
                } else if (mode == ClickOverlayLayout.Mode.TEXT) {
                    heading = "요청을 적어주세요";
                    detail = "‘휴대폰 전체 글자 크기 키워줘’를 적고 ↑ 버튼을 눌러보세요.";
                    speechResource = R.string.tutorial_speech_write_input;
                    floating.highlight("↑", 0);
                } else if (mode == ClickOverlayLayout.Mode.VOICE) {
                    heading = "마이크를 꾹 눌러보세요";
                    detail = "마이크를 누른 채 말하고, 끝나면 손을 떼세요.";
                    speechResource = R.string.tutorial_speech_voice_record;
                    if (!floating.getControl().isVoiceInputActive()) floating.highlight(null, R.id.overlay_microphone);
                }
                break;
            case REVIEW:
                heading = "요청이 맞는지 확인해요";
                detail = "요청을 확인한 뒤 안내를 시작해요.";
                speechResource = reviewSpeechResource();
                if (practiceReview != null && (practiceReview.needsTarget() || practiceReview.needsDirection()))
                    detail = "알맞은 항목을 고르고 ‘다음 단계’를 눌러주세요.";
                break;
            case RUNNING:
                heading = paused ? "잠시 쉬어도 괜찮아요" : "자동으로 진행해요";
                detail = paused ? "‘이어서 진행’을 누르면 계속해요."
                        : "안내창을 줄이거나 잠시 멈춰보세요.";
                speechResource = paused ? R.string.tutorial_speech_paused : R.string.tutorial_speech_running;
                button = "결과 확인하기"; enabled = !paused; break;
            default:
                heading = "결과를 알려주세요";
                detail = "아래 버튼으로 결과를 알려주세요.";
                speechResource = R.string.tutorial_speech_result;
                break;
        }
        if (paused) {
            heading = "잠시 쉬어도 괜찮아요";
            detail = "‘이어서 진행’을 누르면 계속해요.";
            speechResource = R.string.tutorial_speech_paused;
            enabled = false;
        }
        if (reviewView != null && (reviewView.getVisibility() != View.VISIBLE || reviewView.isMinimizedProgress())) {
            detail = "작아진 안내를 다시 누르면 펼쳐져요.";
            speechResource = R.string.tutorial_speech_reopen;
            floating.setClickHighlighted(reviewView.getVisibility() != View.VISIBLE);
        }
        title.setText(heading); description.setText(detail);
        action.setText(button); action.setEnabled(enabled);
        action.setVisibility(enabled || step == RUNNING ? View.VISIBLE : View.GONE);
        action.setAlpha(enabled ? 1f : .52f);
        if (restore.getVisibility() == View.VISIBLE) floating.highlight(restore);
        String cue = reviewedScripts.forSource(getString(speechResource));
        if (!cue.equals(lastCue)) { lastCue = cue; cuePending = true; }
        if (cuePending && !keyboardVisible && !floating.getControl().isVoiceInputActive()) {
            cuePending = false;
            if (cue.trim().isEmpty()) narrator.stop(); else narrator.speak(cue);
        }
    }
    private void completeStep(int completed) { completedSteps |= 1 << completed; }

    private void renderChecklist() {
        checklist.removeAllViews();
        checklist.setPadding(dp(16), dp(10), dp(16), dp(10));
        checklist.setBackground(ClickUi.rounded(ClickUi.PALE_BLUE, dp(24)));
        LinearLayout.LayoutParams cardParams = (LinearLayout.LayoutParams) checklist.getLayoutParams();
        cardParams.topMargin = dp(20);
        String[] labels = {"말이나 글로 요청하기", "요청 내용 확인하기", "안내창 줄이고 멈추기", "결과 확인하기"};
        String[] hints = {"편한 방법 하나만 고르면 돼요", "맞는지 보고 안내를 시작해요", "창을 줄이거나 잠시 멈출 수 있어요", "잘 되었는지 클릭에게 알려주세요"};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(12), 0, dp(12));
            boolean done = (completedSteps & (1 << i)) != 0;
            TextView number = text(done ? "✓" : String.valueOf(i + 1), 18, ClickUi.BLUE, true);
            number.setGravity(Gravity.CENTER);
            number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
            number.setBackground(ClickUi.rounded(Color.WHITE, dp(22)));
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(number, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout copy = vertical();
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(14);
            row.addView(copy, params);
            TextView label = text(labels[i], 17, ClickUi.TEXT, true);
            label.setContentDescription(labels[i] + (done ? ", 완료" : ", 연습 전"));
            copy.addView(label);
            // 큰 글씨에서는 같은 내용을 반복하는 보조 설명을 덜어 시작 버튼까지 쉽게 닿게 한다.
            if (getResources().getConfiguration().fontScale < 1.5f) {
                TextView hint = text(hints[i], 14, ClickUi.SUBTEXT, false);
                hint.setPadding(0, dp(5), 0, 0); copy.addView(hint);
            }
            checklist.addView(row);
        }
        if (step == COMPLETE) {
            TextView tip = text("버튼이 가리면 누른 채 옮겨보세요. 홈의 ‘클릭 튜토리얼’에서 다시 연습할 수 있어요.", 14, ClickUi.SUBTEXT, false);
            tip.setPadding(0, dp(16), 0, 0); checklist.addView(tip);
        }
    }
    private void advance() {
        if (!action.isEnabled()) return;
        cancelVoicePractice(); narrator.stop();
        if (step == COMPLETE) { finishToHome(); return; }
        if (step == INTRO) { step = INPUT; showMode(ClickOverlayLayout.Mode.COLLAPSED); }
        else if (step == RUNNING) { completeStep(RUNNING); step = RESULT; showResultPractice(); }
        updateCopy();
    }
    private void previous() {
        if (step == INTRO) { finishToHome(); return; }
        cancelVoicePractice(); narrator.stop();
        paused = false;
        if (step == INPUT) { step = INTRO; floating.hideReview(); reviewView = null; }
        else if (step == REVIEW) { returnToInput(true); return; }
        else if (step == RUNNING) { step = REVIEW; showRequestReview(lastRequest()); }
        else if (step == RESULT) { step = RUNNING; paused = false; showProgressPractice(); }
        else { step = RESULT; showResultPractice(); }
        updateCopy();
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
        state.putStringArrayList("review_actions", new ArrayList<>(reviewActions));
        state.putBoolean("paused", paused);
        state.putBoolean("panel_collapsed", reviewView != null
                && (reviewView.getVisibility() != View.VISIBLE || reviewView.isMinimizedProgress()));
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
        cancelVoicePractice(); narrator.pause(); super.onPause();
    }
    @Override protected void onDestroy() {
        cancelVoicePractice(); narrator.close(); super.onDestroy();
    }
    private TextView text(String value, int size, int color, boolean strong) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(ClickTextScale.size(this, size)); text.setTextColor(color);
        text.setTypeface(getResources().getFont(strong
                ? com.click.designpreview.R.font.pretendard_semibold
                : com.click.designpreview.R.font.pretendard_regular));
        text.setIncludeFontPadding(false); text.setLineSpacing(dp(3), 1.08f);
        text.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        text.setTextLocale(java.util.Locale.KOREAN);
        if (Build.VERSION.SDK_INT >= 33)
            text.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        return text;
    }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private int dp(int value) { return ClickUi.dp(this, value); }
}
