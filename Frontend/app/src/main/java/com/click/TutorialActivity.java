package com.click;

import android.content.Intent;
import android.graphics.Color;
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
import com.click.ui.tutorial.TutorialArtworkView;
import com.click.ui.tutorial.TutorialFloatingHost;
import java.util.ArrayList;

/** A feature tour on the same phone backdrop as the accessibility guide, using the real controls. */
public final class TutorialActivity extends AppCompatActivity implements TutorialFloatingHost.Listener {
    public static final String EXTRA_FROM_SIGN_UP = "from_sign_up";
    private static final int OPEN = 0, WRITE = 1, REPLY = 2, VOICE = 3, STOP = 4, MOVE = 5, STEP_COUNT = 6;
    private static final String PRACTICE_REPLY = "잘 입력하셨어요! 이렇게 click과 글로 대화할 수 있어요.";
    private final ArrayList<String> messages = new ArrayList<>();
    private TutorialFloatingHost floating;
    private LinearLayout toolbar, progress, footer;
    private TextView title, description, counter, feedback;
    private ImageView grandmother;
    private Button action, restore;
    private int step;
    private boolean voiceTried, stopTried, restoring, keyboardVisible;
    private String moveFeedback = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            step = Math.max(OPEN, Math.min(MOVE, savedInstanceState.getInt("feature_step", OPEN)));
            voiceTried = savedInstanceState.getBoolean("voice_tried");
            stopTried = savedInstanceState.getBoolean("stop_tried");
            moveFeedback = savedInstanceState.getString("move_feedback", "");
            ArrayList<String> savedMessages = savedInstanceState.getStringArrayList("practice_messages");
            if (savedMessages != null) messages.addAll(savedMessages);
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
        PermissionPhoneView phone = new PermissionPhoneView(this, true, false);
        phone.fillAvailableHeight();
        phone.setAlpha(.55f);
        FrameLayout.LayoutParams phoneParams = new FrameLayout.LayoutParams(-1, -1);
        phoneParams.setMargins(dp(5), 0, dp(5), 0);
        stage.addView(phone, phoneParams);

        restore = ClickUi.secondaryButton(this, "click 버튼 다시 보기");
        restore.setId(R.id.tutorial_restore);
        restore.setVisibility(View.GONE);
        FrameLayout.LayoutParams restoreParams = new FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM);
        restoreParams.setMargins(dp(30), 0, dp(30), dp(28));
        stage.addView(restore, restoreParams);
        floating = new TutorialFloatingHost(this, this);
        floating.setId(R.id.tutorial_floating_host);
        floating.getControl().setId(R.id.tutorial_floating_control);
        FrameLayout.LayoutParams floatingParams = new FrameLayout.LayoutParams(-1, -1);
        floatingParams.setMargins(dp(14), dp(34), dp(14), dp(16));
        stage.addView(floating, floatingParams);
        restore.setOnClickListener(view -> {
            floating.showAgain(); restore.setVisibility(View.GONE);
            moveFeedback = "버튼을 다시 꺼냈어요. 편하게 연습해 보세요.";
            updateCopy();
        });

        footer = vertical();
        footer.setPadding(0, 0, 0, dp(8));
        feedback = text("", 13, ClickUi.SUBTEXT, false);
        feedback.setGravity(Gravity.CENTER);
        feedback.setPadding(dp(4), dp(4), dp(4), dp(8));
        footer.addView(feedback, new LinearLayout.LayoutParams(-1, -2));
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
            for (String message : messages) addConversation(message);
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
        TextView label = text("click 사용 방법", 16, ClickUi.BLUE, true);
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
        grandmother.setContentDescription("click 사용법을 안내하는 할머니");
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
        if (step == STOP || step == MOVE) { updateCopy(); return; }
        if (mode == ClickOverlayLayout.Mode.MENU && step == OPEN) step = WRITE;
        if (mode == ClickOverlayLayout.Mode.TEXT) step = messages.isEmpty() ? WRITE : REPLY;
        if (mode == ClickOverlayLayout.Mode.VOICE) {
            step = VOICE;
            floating.getControl().setRecognizedSpeech("마이크 버튼을 눌러보세요.\n연습 중에는 녹음하지 않아요.");
        }
        updateCopy();
    }

    @Override public void onMicrophoneClicked() {
        boolean active = !floating.getControl().isVoiceInputActive();
        floating.getControl().setVoiceInputActive(active);
        if (active) voiceTried = true;
        floating.getControl().setRecognizedSpeech(active
                ? "이때만 음성을 입력해요.\n다시 누르면 입력을 취소해요."
                : "마이크를 껐어요.\n다시 누르기 전에는 듣지 않아요.");
        updateCopy();
    }

    @Override public void onStopRequested() {
        if (step != STOP) return;
        stopTried = true;
        floating.getControl().setRequestStatus("요청을 멈췄어요", false);
        floating.getControl().setRecognizedSpeech("작업을 중지했어요.\n새 입력은 마이크를 눌러 시작해요.");
        updateCopy();
    }

    private void showStopPractice() {
        floating.getControl().setVoiceInputActive(false);
        floating.getControl().setRequestStatus(stopTried ? "요청을 멈췄어요" : "사용자 요청을 처리하고 있어요", !stopTried);
        floating.getControl().setRecognizedSpeech(stopTried
                ? "작업을 중지했어요.\n새 입력은 마이크를 눌러 시작해요."
                : "처리 중에도 ‘요청 중지’를 누르면\n작업을 멈출 수 있어요.");
    }

    @Override public void onTextSubmitted(String value) {
        messages.add(value);
        while (messages.size() > 3) messages.remove(0);
        addConversation(value);
        step = REPLY;
        updateCopy();
        title.announceForAccessibility("글로 질문했어요. 대화창에서 입력한 내용을 확인해 보세요.");
    }

    private void addConversation(String value) {
        floating.getControl().addUserMessage(value);
        floating.getControl().addAssistantMessage(PRACTICE_REPLY);
    }

    @Override public void onHidden() {
        restore.setVisibility(View.VISIBLE);
        moveFeedback = "버튼을 숨겼어요. 아래에서 다시 꺼낼 수 있어요.";
        if (step < MOVE) step = MOVE;
        updateCopy();
    }

    @Override public void onMoved() {
        moveFeedback = "잘했어요! 버튼을 편한 곳으로 옮겼어요.";
        updateCopy();
    }

    private void updateCopy() {
        counter.setText(getString(R.string.click_tutorial_progress, step + 1, STEP_COUNT));
        progress.setContentDescription("click 기능 설명 " + (step + 1) + "단계, 전체 " + STEP_COUNT + "단계");
        for (int i = 0; i < STEP_COUNT; i++) progress.getChildAt(i).setBackground(ClickUi.rounded(i == step ? ClickUi.BLUE : ClickUi.BORDER, dp(3)));
        String heading, detail, button;
        boolean enabled = false;
        ClickOverlayLayout.Mode mode = floating.getControl().getMode();
        floating.setClickHighlighted(step == OPEN);
        switch (step) {
            case OPEN:
                heading = "화면 위 click 버튼을\n눌러보세요";
                detail = "어떤 화면을 보고 있어도\n이 버튼으로 도움을 요청해요.";
                button = "휴대폰 위 click 버튼을 눌러보세요";
                break;
            case WRITE:
                boolean input = mode == ClickOverlayLayout.Mode.TEXT;
                heading = input ? "질문을 적고\n↑ 버튼을 눌러보세요" : "‘글로 입력’을\n눌러보세요";
                detail = input ? "아래 입력칸을 누르면 키보드가 열려요.\n궁금한 내용을 직접 적어보세요." : "말하기 어려울 땐 글로 물어봐요.\n펼쳐진 메뉴에서 선택해 보세요.";
                button = "입력창에서 질문을 보내보세요";
                break;
            case REPLY:
                heading = "입력한 내용이\n대화창에 나타나요";
                detail = "이렇게 click과 글로 대화해요.\n입력칸에서 이어서 물어볼 수 있어요.";
                button = "말로 입력하는 방법도 보기  →";
                enabled = true;
                break;
            case VOICE:
                heading = mode == ClickOverlayLayout.Mode.VOICE ? "마이크 버튼을 눌러야\n음성을 입력해요" : "‘말로 시작’을\n눌러보세요";
                detail = mode == ClickOverlayLayout.Mode.VOICE ? "입력 전에는 Click도 말하지 않아요.\n말을 마치면 마이크가 꺼져요." : "입력창을 열어도 녹음하지 않아요.\n마이크 버튼을 눌렀을 때만 들어요.";
                button = voiceTried ? "요청을 멈추는 방법 보기  →" : "마이크 버튼을 직접 눌러보세요";
                enabled = voiceTried;
                break;
            case STOP:
                heading = stopTried ? "원할 때 바로\n멈출 수 있어요" : "처리 중에 멈추려면\n‘요청 중지’를 눌러요";
                detail = stopTried ? "중지한 요청은 다시 진행하지 않아요.\n새로운 요청을 입력할 수 있어요." : "‘사용자 요청을 처리하고 있어요’가\n보일 때도 중지할 수 있어요.";
                button = stopTried ? "접기 · 이동 · 숨기기 알아보기  →" : "‘요청 중지’를 직접 눌러보세요";
                enabled = stopTried;
                break;
            default:
                heading = "편한 곳으로 옮기고\n필요할 때 숨겨요";
                detail = "누른 채 움직이면 이동, 꾹 누르면 숨겨요.\n열린 창은 ‘접기’를 눌러 작게 만들어요.";
                button = "튜토리얼 마치기  →";
                enabled = true;
                break;
        }
        if (mode == ClickOverlayLayout.Mode.COLLAPSED && step > OPEN && step < MOVE) {
            heading = "click 버튼을 다시 누르면\n메뉴가 열려요";
            detail = "‘접기’는 창을 작게 만드는 기능이에요.\n버튼을 눌러 다시 이어서 해보세요.";
        }
        title.setText(keyboardVisible ? "질문을 적고 ↑를 눌러주세요" : heading);
        description.setText(detail);
        action.setText(button);
        action.setEnabled(enabled);
        action.setAlpha(enabled ? 1f : .52f);
        feedback.setText(step == MOVE && !moveFeedback.isEmpty() ? moveFeedback : "기능을 익히는 연습 화면이에요. 실제 요청은 보내지 않아요.");
    }

    private void advance() {
        if (step == REPLY) {
            step = VOICE; voiceTried = false;
            if (floating.getControl().getMode() != ClickOverlayLayout.Mode.TEXT) showMode(ClickOverlayLayout.Mode.MENU);
        } else if (step == VOICE && voiceTried) {
            step = STOP; stopTried = false;
            showMode(ClickOverlayLayout.Mode.VOICE);
            showStopPractice();
        } else if (step == STOP && stopTried) {
            step = MOVE;
            floating.getControl().setRequestStatus(null, false);
            showMode(ClickOverlayLayout.Mode.COLLAPSED);
        } else if (step == MOVE) { finishToHome(); return; }
        updateCopy();
    }

    private void previous() {
        if (step == OPEN) { finishToHome(); return; }
        restoring = true;
        floating.getControl().setRequestStatus(null, false);
        step--;
        if (step == REPLY && messages.isEmpty()) step = WRITE;
        floating.showAgain();
        restore.setVisibility(View.GONE);
        if (step == OPEN) showMode(ClickOverlayLayout.Mode.COLLAPSED);
        else if (step == WRITE) showMode(ClickOverlayLayout.Mode.MENU);
        else if (step == REPLY) showMode(ClickOverlayLayout.Mode.TEXT);
        else if (step == STOP) { showMode(ClickOverlayLayout.Mode.VOICE); showStopPractice(); }
        else showMode(ClickOverlayLayout.Mode.MENU);
        restoring = false;
        updateCopy();
    }

    private void showMode(ClickOverlayLayout.Mode mode) {
        switch (mode) {
            case TEXT: floating.getControl().openText(); break;
            case MENU: floating.getControl().openMenu(); break;
            case VOICE:
                floating.getControl().setRecognizedSpeech("마이크 버튼을 눌러보세요.\n연습 중에는 녹음하지 않아요.");
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
        state.putBoolean("voice_tried", voiceTried);
        state.putBoolean("stop_tried", stopTried);
        state.putString("move_feedback", moveFeedback);
        state.putStringArrayList("practice_messages", new ArrayList<>(messages));
        state.putString("draft", floating.getControl().getDraftText());
        state.putString("floating_mode", floating.getControl().getMode().name());
        state.putBoolean("floating_hidden", floating.isHidden());
        super.onSaveInstanceState(state);
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
