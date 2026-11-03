package com.click.ui.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.click.R;
import com.click.ui.common.ClickUi;

/**
 * 플로팅 UI 수정의 시작점. 앱 내부와 다른 앱 위에 띄우는 버튼/입력창이 이 View를 공유한다.
 * XML 레이아웃 대신 아래 Java 코드에서 화면을 직접 만든다.
 *
 * 수정 위치 안내 (파일에서 "[UI 수정]"을 검색):
 * - 접힌 원형 버튼: dispatchDraw(). 커서 모양/색/그림자는 ClickIconRenderer.java.
 * - 창 크기: getWindowWidth(), getWindowHeight().
 * - 펼친 창의 배치: renderPanel()의 공통 헤더와 MENU/VOICE/TEXT 분기.
 * - 공통 버튼/글꼴: action(), feature(), label(). 입력창 스타일은 생성자.
 * - 기본 색상: ui/common/ClickUi.java. 다른 화면에서도 쓰므로 변경 시 함께 영향을 받는다.
 * - 요청 확인/진행 안내창: 이 파일이 아니라 TaskFlowOverlayView.java.
 * - 화면 위 위치/키보드 회피: service/overlay/ClickOverlayService.java,
 *   앱 내부 위치: FloatingClickHost.java. Callback은 이 호스트들에 동작을 전달한다.
 */
public final class ClickOverlayLayout extends ViewGroup {
    // COLLAPSED: 작은 버튼, MENU: 입력 방식 선택, VOICE: 음성 입력, TEXT: 글 입력.
    public enum Mode { COLLAPSED, MENU, VOICE, TEXT }

    public interface Callback {
        void onModeChanged(Mode oldMode, Mode newMode);
        void onDragStarted();
        void onDragBy(float deltaX, float deltaY);
        void onDragEnded();
        default void onDragCancelled() {}
        void onTextSubmitted(String text);
        void onCloseRequested();
        default boolean onOpenRequested() { return false; }
        default void onMicrophoneClicked() {}
        default void onMicrophonePressed() {}
        default void onMicrophoneReleased() {}
        default void onMicrophoneCancelled() {}
        default void onStopRequested() {}
        default void onContentSizeChanged() {}
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final EditText goalInput;
    private LinearLayout panel;
    private TextView transcript;
    private LinearLayout textEntry;
    private TextView voiceSwitch;
    private TextView inputFeedback;
    private VoiceCaptureIndicator microphone;
    private TextView voiceHelp;
    private LinearLayout voiceCaptureArea;
    private TextView microphoneLabel, stopRequest;
    private LinearLayout requestRow;
    private boolean requestControlsVisible = true;
    private String taskPhase;
    private boolean voiceInputActive, voiceInputProcessing, requestStoppable;
    private View microphoneTouchTarget;
    private String requestStatus;
    private Mode mode = Mode.COLLAPSED;
    private Callback callback;
    private float downRawX, downRawY, lastRawX, lastRawY;
    private boolean holding, dragging, holdConsumed;
    private final int dragSlop;
    private String recognizedSpeech = "어떤 도움이 필요하세요?";
    private final Runnable hideAfterHold = () -> {
        if (holding && !dragging) performLongClick();
    };

    public ClickOverlayLayout(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
        setClickable(true);
        setLongClickable(true);
        setFocusable(true);
        dragSlop = Math.max(ViewConfiguration.get(context).getScaledTouchSlop(), dp(12));
        // [UI 수정] 글 입력칸의 글자 크기(sp), 안내 문구, 여백(dp), 높이, 테두리/둥근 모서리.
        goalInput = new EditText(context);
        goalInput.setTextSize(18);
        goalInput.setTextColor(ClickUi.TEXT);
        goalInput.setHintTextColor(ClickUi.SUBTEXT);
        goalInput.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        goalInput.setHint("무엇이 궁금하세요?");
        goalInput.setContentDescription("도움이 필요한 내용 입력");
        goalInput.setSingleLine(true);
        goalInput.setInputType(InputType.TYPE_CLASS_TEXT);
        goalInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        goalInput.setPadding(dp(12), dp(10), dp(10), dp(10));
        goalInput.setMinimumHeight(dp(48));
        goalInput.setBackground(ClickUi.outlined(Color.WHITE, ClickUi.BORDER, dp(16), dp(1)));
        goalInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEND) return false;
            submitText();
            return true;
        });
        updateDescription();
    }

    public void setTaskPhase(String phase) {
        taskPhase = phase;
        if (panel != null) panel.setContentDescription(phase == null ? null : phase + " 안내 창");
    }
    public void showVoiceInputError(String message) {
        if (mode != Mode.VOICE || transcript == null) return;
        transcript.setText(message); transcript.setVisibility(VISIBLE); resizeToContent();
    }
    public void setRequestControlsVisible(boolean visible) {
        requestControlsVisible = visible;
        setRequestStatus(requestStatus, requestStoppable);
    }
    public void setCallback(Callback callback) { this.callback = callback; }
    public Mode getMode() { return mode; }
    public void openMenu() { changeMode(Mode.MENU); }
    public void openText() { changeMode(Mode.TEXT); }
    public void openVoice() { changeMode(Mode.VOICE); }
    public void collapse() { changeMode(Mode.COLLAPSED); }
    public String getDraftText() { return goalInput.getText().toString(); }
    public void setInputHint(String hint) { goalInput.setHint(hint); }
    public void setDraftText(String text) { goalInput.setText(text); }
    public void setRecognizedSpeech(String text) {
        recognizedSpeech = text == null || text.trim().isEmpty() ? "어떤 도움이 필요하세요?" : text.trim();
    }
    public void showRecognizedSpeechResult(String text) {
        setRecognizedSpeech(text);
        TextView result = transcript;
        if (result != null) {
            result.setText(recognizedSpeech); result.setVisibility(VISIBLE); resizeToContent();
        }
        if (result != null) result.post(() -> {
            if (transcript == result && mode == Mode.VOICE)
                result.requestRectangleOnScreen(new android.graphics.Rect(0, 0, result.getWidth(), result.getHeight()), true);
        });
    }
    public void showInputFeedback(String text) {
        if (inputFeedback == null || text == null || text.trim().isEmpty()) return;
        inputFeedback.setText(text.trim());
        inputFeedback.setVisibility(VISIBLE);
        resizeToContent();
    }
    // Processing feedback stays in the input/microphone; no floating status banner.
    public void setLoadingStatus(String message) { }
    public boolean isVoiceInputActive() { return voiceInputActive; }
    public void setVoiceInputActive(boolean active) {
        voiceInputActive = active;
        if (active && transcript != null) transcript.setVisibility(GONE);
        if (!active) clearMicrophoneTouch();
        updateMicrophone();
    }
    public void setVoiceInputProcessing(boolean processing) {
        voiceInputProcessing = processing;
        if (processing && transcript != null) transcript.setVisibility(GONE);
        updateMicrophone();
    }
    private void updateMicrophone() {
        if (microphone == null) return;
        microphone.setState(voiceInputProcessing ? VoiceCaptureIndicator.State.PROCESSING
                : voiceInputActive ? VoiceCaptureIndicator.State.LISTENING : VoiceCaptureIndicator.State.READY);
        voiceCaptureArea.setContentDescription(voiceInputProcessing ? "말씀하신 내용을 확인하고 있어요"
                : voiceInputActive ? "지금 듣고 있어요. 손을 떼면 입력이 끝나요"
                : "마이크를 꾹 누르고 말씀해 주세요");
        voiceCaptureArea.setBackgroundColor(Color.TRANSPARENT);
        microphoneLabel.setText(voiceInputProcessing ? "말씀을 확인하고 있어요" : voiceInputActive ? "지금 듣고 있어요" : "꾹 누르고 말씀해 주세요");
        voiceHelp.setText(voiceInputProcessing ? "잠시만 기다려 주세요." : voiceInputActive
                ? "말씀을 마치면 손을 떼어 주세요." : "손을 떼면 말씀하신 내용을 확인할게요.");
        voiceCaptureArea.setEnabled(!voiceInputProcessing);
        resizeToContent();
    }
    private final Runnable notifyContentSize = () -> {
        if (callback != null && mode != Mode.COLLAPSED) callback.onContentSizeChanged();
    };
    private void resizeToContent() {
        requestLayout();
        removeCallbacks(notifyContentSize);
        post(notifyContentSize);
    }
    public void setRequestStatus(String status, boolean canStop) {
        requestStatus = status;
        requestStoppable = canStop;
        if (requestRow != null) {
            requestRow.setVisibility(requestControlsVisible && canStop ? VISIBLE : GONE);
            stopRequest.setVisibility(canStop ? VISIBLE : GONE);
            resizeToContent();
        }
    }

    // [UI 수정] 모드별 창 너비: 접힘 64dp, 음성 300dp, 글 340dp, 메뉴 320dp.
    // 화면 폭에서는 24dp를 비운다. 실제 화면 좌표는 호스트가 결정한다.
    public int getWindowWidth(Mode target, int screenWidth) {
        return target == Mode.COLLAPSED ? dp(64) : Math.min(screenWidth - dp(24), dp(target == Mode.VOICE ? 300 : target == Mode.TEXT ? 340 : 320));
    }
    // [UI 수정] 펼친 창은 내용 높이를 측정하고 화면 높이 - 96dp로 제한한다.
    // 마지막 줄의 216/350/340dp는 패널을 아직 측정할 수 없을 때만 쓰는 초기 높이이다.
    public int getWindowHeight(Mode target) {
        if (target == Mode.COLLAPSED) return dp(64);
        int limit = getResources().getDisplayMetrics().heightPixels - dp(96);
        if (target == mode && panel != null) {
            int width = getWindowWidth(target, getResources().getDisplayMetrics().widthPixels);
            panel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            return Math.min(limit, panel.getMeasuredHeight());
        }
        return Math.min(limit, dp(target == Mode.TEXT ? 216 : target == Mode.VOICE ? 350 : 340));
    }
    // 접힘/펼침 전환 시 유지할 기준점. 버튼 크기를 바꾸면 중심과 hitsHandle()도 맞춰야 한다.
    public float getClickCenterX(Mode target) {
        return getClickCenterX(target, getWindowWidth(target, getResources().getDisplayMetrics().widthPixels));
    }
    public float getClickCenterX(Mode target, int width) {
        return target == Mode.COLLAPSED ? dp(32) : width - dp(FloatingPanelStyle.HORIZONTAL_PADDING_DP) - handleWidth() / 2f;
    }
    public float getClickCenterY(Mode target) {
        return target == Mode.COLLAPSED ? dp(32) : dp(FloatingPanelStyle.TOP_PADDING_DP) + headerHeight() / 2f;
    }

    // [UI 수정] 헤더/줄이기 버튼 크기. 시스템 글자 확대 설정을 반영한 최소 높이/너비이다.
    private int headerHeight() {
        return dp(Math.max(48, Math.round(20 * getResources().getConfiguration().fontScale) + 12));
    }
    private int handleWidth() {
        return dp(Math.max(56, Math.round(36 * getResources().getConfiguration().fontScale) + 20));
    }

    private void changeMode(Mode next) {
        if (mode == next) return;
        finishMicrophoneTouch(true);
        Mode old = mode;
        if (old == Mode.TEXT) hideKeyboard();
        mode = next;
        renderPanel();
        updateDescription();
        if (callback != null) callback.onModeChanged(old, next);
        requestLayout();
        invalidate();
    }

    // [UI 수정] 모드를 바꿀 때 패널을 다시 구성한다. addView 순서가 위→아래 배치 순서이다.
    private void renderPanel() {
        detach(goalInput);
        removeAllViews();
        transcript = null;
        textEntry = null;
        voiceSwitch = null;
        inputFeedback = null;
        microphone = null;
        voiceHelp = null;
        voiceCaptureArea = null;
        microphoneLabel = stopRequest = null;
        requestRow = null;
        if (mode == Mode.COLLAPSED) return;
        // 펼친 창 공통 외형: 내부 여백, 흰 배경, 테두리, 28dp 둥근 모서리.
        panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setContentDescription(taskPhase == null ? null : taskPhase + " 안내 창");
        panel.setPadding(dp(FloatingPanelStyle.HORIZONTAL_PADDING_DP), dp(FloatingPanelStyle.TOP_PADDING_DP),
                dp(FloatingPanelStyle.HORIZONTAL_PADDING_DP), dp(FloatingPanelStyle.BOTTOM_PADDING_DP));
        panel.setBackground(FloatingPanelStyle.surface(getResources().getDisplayMetrics().density));
        addView(panel);

        // 공통 헤더: 왼쪽 커서/"클릭 안내", 오른쪽 "줄이기" 버튼.
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = new LinearLayout(getContext());
        identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cursor = new ImageView(getContext());
        cursor.setImageResource(com.click.designpreview.R.drawable.ic_click_cursor);
        cursor.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(cursor, new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView title = label("클릭 안내", 16, ClickUi.TEXT, true);
        title.setPadding(dp(6), 0, 0, 0);
        identity.addView(title);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = FloatingPanelStyle.compactButton(getContext(), "줄이기", this::collapse);
        close.setTextSize(12);
        close.setId(R.id.overlay_collapse);
        close.setGravity(Gravity.CENTER);
        close.setMaxLines(1);
        close.setContentDescription("줄이기. 작은 클릭 버튼으로 접기");
        close.setFocusable(true);
        close.setOnClickListener(view -> collapse());
        header.addView(close, new LinearLayout.LayoutParams(handleWidth(), headerHeight()));
        panel.addView(header, new LinearLayout.LayoutParams(-1, headerHeight()));

        // Keep cancellation available without showing transport or task-state copy.
        requestRow = new LinearLayout(getContext());
        requestRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        stopRequest = FloatingPanelStyle.compactButton(getContext(), "요청 중지",
                () -> { if (callback != null) callback.onStopRequested(); });
        stopRequest.setId(R.id.overlay_stop_request);
        requestRow.addView(stopRequest, new LinearLayout.LayoutParams(-2, -2));
        setRequestStatus(requestStatus, requestStoppable);

        if (mode == Mode.MENU) {
            // 앱과 튜토리얼이 함께 사용하는 입력 메뉴. 예시는 튜토리얼 바깥 설명에만 둔다.
            LinearLayout choices = new LinearLayout(getContext());
            choices.setOrientation(LinearLayout.VERTICAL);
            TextView welcome = label("무엇을 도와드릴까요?", 22, ClickUi.TEXT, true);
            welcome.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
            choices.addView(welcome, spaced(0));
            choices.addView(feature("말로 입력", true, () -> changeMode(Mode.VOICE)), spaced(12));
            choices.addView(feature("글로 입력", false, () -> changeMode(Mode.TEXT)), spaced(8));
            addScrollableBody(choices, FloatingPanelStyle.HEADER_GAP_DP);
            // 진행 중인 요청에서 입력 메뉴를 다시 열어도 중지 버튼에 접근할 수 있다.
            panel.addView(requestRow, spaced(4));
        } else if (mode == Mode.VOICE) {
            // [UI 수정] 음성 입력: 마이크 크기(104dp), 설명, 인식 결과, 취소 버튼 배치.
            // 마이크 그림/애니메이션은 VoiceCaptureIndicator.java, 누르기 동작은 onMicrophoneTouch().
            LinearLayout listening = new LinearLayout(getContext());
            voiceCaptureArea = listening;
            listening.setId(R.id.overlay_microphone);
            listening.setOrientation(LinearLayout.VERTICAL);
            listening.setGravity(Gravity.CENTER);
            listening.setPadding(dp(8), dp(8), dp(8), dp(10));
            listening.setFocusable(true);
            listening.setOnClickListener(view -> { if (callback != null) callback.onMicrophoneClicked(); });
            listening.setOnTouchListener(this::onMicrophoneTouch);
            microphone = new VoiceCaptureIndicator(getContext());
            listening.addView(microphone, new LinearLayout.LayoutParams(dp(104), dp(104)));
            microphoneLabel = label("", 18, ClickUi.TEXT, true);
            microphoneLabel.setGravity(Gravity.CENTER);
            microphoneLabel.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            listening.addView(microphoneLabel, spaced(10));
            voiceHelp = label("", 13, ClickUi.SUBTEXT, false);
            voiceHelp.setGravity(Gravity.CENTER);
            voiceHelp.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            listening.addView(voiceHelp, spaced(6));
            updateMicrophone();
            transcript = label("", 14, ClickUi.SUBTEXT, false);
            transcript.setGravity(Gravity.CENTER);
            transcript.setMaxLines(Integer.MAX_VALUE);
            transcript.setEllipsize(null);
            transcript.setVisibility(GONE);
            transcript.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            listening.addView(transcript, spaced(8));
            addScrollableBody(listening, FloatingPanelStyle.HEADER_GAP_DP);
            panel.addView(requestRow, spaced(0));
            LinearLayout actions = new LinearLayout(getContext());
            actions.setGravity(Gravity.CENTER_VERTICAL);
            TextView stop = action("취소", false, () -> changeMode(Mode.MENU));
            stop.setContentDescription("음성 입력을 취소하고 메뉴로 돌아가기");
            actions.addView(stop, new LinearLayout.LayoutParams(-1, -2));
            panel.addView(actions, spaced(8));
        } else {
            // [UI 수정] 글 입력: 입력칸+전송 버튼을 가로 배치하고 아래에 피드백/음성 전환을 배치.
            LinearLayout composer = new LinearLayout(getContext());
            composer.setOrientation(LinearLayout.VERTICAL);
            LinearLayout entry = new LinearLayout(getContext());
            textEntry = entry;
            entry.setGravity(Gravity.CENTER_VERTICAL);
            entry.addView(goalInput, new LinearLayout.LayoutParams(0, -2, 1));
            TextView send = action("↑", true, this::submitText);
            send.setContentDescription("입력한 요청 보내기");
            send.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 26);
            send.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), -2);
            sendParams.leftMargin = dp(8);
            entry.addView(send, sendParams);
            composer.addView(entry, new LinearLayout.LayoutParams(-1, -2));
            inputFeedback = label("", 15, ClickUi.SUBTEXT, false);
            inputFeedback.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            inputFeedback.setVisibility(GONE);
            composer.addView(inputFeedback, spaced(8));
            composer.addView(requestRow, spaced(4));
            TextView voice = action("말로 입력하기", false, () -> changeMode(Mode.VOICE));
            voiceSwitch = voice;
            voice.setContentDescription("음성으로 입력하기");
            composer.addView(voice, buttonParams());
            addScrollableBody(composer, FloatingPanelStyle.HEADER_GAP_DP);
        }
    }

    private void addScrollableBody(View content, int margin) {
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1);
        params.topMargin = dp(margin);
        panel.addView(scroll, params);
    }

    // [UI 수정] 전송/취소/전환 버튼 공통 스타일. filled는 파란 채움 여부, 최소 터치 높이는 48dp.
    private TextView action(String title, boolean filled, Runnable onClick) {
        TextView button = label(title, 17, filled ? Color.WHITE : ClickUi.BLUE_DARK, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x203478F6),
                ClickUi.rounded(filled ? ClickUi.BLUE : ClickUi.PALE_BLUE, dp(16)),
                ClickUi.rounded(Color.WHITE, dp(16))));
        button.setFocusable(true);
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    // [UI 수정] 메뉴의 음성/글 입력 카드: 배경, 아이콘, 글자, 카드 안 여백.
    private View feature(String title, boolean microphone, Runnable action) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setMinimumHeight(dp(52));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x223478F6),
                ClickUi.rounded(Color.WHITE, dp(18)),
                ClickUi.rounded(Color.WHITE, dp(20))));
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(microphone ? R.drawable.ic_overlay_microphone : R.drawable.ic_overlay_edit);
        icon.setImageTintList(ColorStateList.valueOf(ClickUi.BLUE));
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        row.setElevation(0);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView copy = label(title, 17, ClickUi.TEXT, true);
        copy.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = dp(8);
        row.addView(copy, copyParams);
        ImageView arrow = new ImageView(getContext());
        arrow.setImageResource(R.drawable.ic_review_chevron);
        arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(32)));
        row.setContentDescription(microphone ? "말로 시작. 마이크로 요청하기" : "글로 입력. 키보드로 요청하기");
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    // [UI 수정] 공통 텍스트 스타일. size는 sp로 시스템 글자 확대를 따르고, 여백은 dp를 쓴다.
    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setTypeface(getResources().getFont(bold
                ? com.click.designpreview.R.font.pretendard_medium
                : com.click.designpreview.R.font.pretendard_regular));
        text.setLineSpacing(dp(2), 1.1f);
        return text;
    }
    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        return params;
    }
    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        return params;
    }
    private void detach(View child) {
        if (child.getParent() instanceof ViewGroup) ((ViewGroup) child.getParent()).removeView(child);
    }
    // 좁은 높이/키보드 표시 때 글 입력창의 음성 전환 버튼을 숨기는 반응형 배치.
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        if (mode != Mode.COLLAPSED && panel != null) {
            if (mode == Mode.TEXT && textEntry != null) {
                boolean compact = height < dp(180) * Math.max(1f, getResources().getConfiguration().fontScale);
                voiceSwitch.setVisibility(compact ? GONE : VISIBLE);
            }
            panel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (mode != Mode.COLLAPSED && panel != null) panel.layout(0, 0, getWidth(), getHeight());
    }
    // [UI 수정] 접힌 원형 버튼의 그림 반지름은 28dp, 창/터치 영역은 64dp이다.
    // 창 크기도 바꿀 때는 getWindowWidth/Height, 중심 좌표, hitsHandle과 두 호스트의 초기 64dp를 맞춘다.
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mode == Mode.COLLAPSED) {
            ClickIconRenderer.draw(this, canvas, paint, path, getWidth() / 2f,
                    getClickCenterY(mode), dp(28), 0f, false);
        }
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return event.getActionMasked() == MotionEvent.ACTION_DOWN && hitsHandle(event);
    }
    // Touch uses press/release; performClick remains available to accessibility and keyboards.
    @SuppressLint("ClickableViewAccessibility")
    private boolean onMicrophoneTouch(View target, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!target.isEnabled() || microphoneTouchTarget != null) return true;
                microphoneTouchTarget = target;
                target.setPressed(true);
                if (target.getParent() != null) target.getParent().requestDisallowInterceptTouchEvent(true);
                if (callback != null) callback.onMicrophonePressed();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (microphoneTouchTarget == target && (event.getX() < -dragSlop || event.getY() < -dragSlop
                        || event.getX() > target.getWidth() + dragSlop || event.getY() > target.getHeight() + dragSlop))
                    finishMicrophoneTouch(true);
                return true;
            case MotionEvent.ACTION_UP:
                if (microphoneTouchTarget == target) finishMicrophoneTouch(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                finishMicrophoneTouch(true);
                return true;
            default: return true;
        }
    }
    private void finishMicrophoneTouch(boolean cancelled) {
        if (microphoneTouchTarget == null) return;
        clearMicrophoneTouch();
        if (callback != null) {
            if (cancelled) callback.onMicrophoneCancelled();
            else callback.onMicrophoneReleased();
        }
    }
    private void clearMicrophoneTouch() {
        View target = microphoneTouchTarget;
        if (target == null) return;
        microphoneTouchTarget = null;
        target.setPressed(false);
        if (target.getParent() != null) target.getParent().requestDisallowInterceptTouchEvent(false);
    }
    private boolean hitsHandle(MotionEvent event) {
        float cx = getClickCenterX(mode, getWidth());
        if (mode == Mode.COLLAPSED)
            return Math.hypot(event.getX() - cx, event.getY() - getClickCenterY(mode)) <= dp(32);
        return Math.abs(event.getX() - cx) <= handleWidth() / 2f
                && Math.abs(event.getY() - getClickCenterY(mode)) <= headerHeight() / 2f;
    }
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        float rawX = event.getRawX(), rawY = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hitsHandle(event)) return false;
                downRawX = lastRawX = rawX;
                downRawY = lastRawY = rawY;
                holding = true;
                dragging = holdConsumed = false;
                postDelayed(hideAfterHold, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!holding || holdConsumed) return true;
                if (!dragging && Math.hypot(rawX - downRawX, rawY - downRawY) > dragSlop) {
                    removeCallbacks(hideAfterHold);
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    if (callback != null) callback.onDragStarted();
                }
                if (dragging && callback != null) callback.onDragBy(rawX - lastRawX, rawY - lastRawY);
                lastRawX = rawX;
                lastRawY = rawY;
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(hideAfterHold);
                holding = false;
                if (holdConsumed) return true;
                if (dragging) {
                    dragging = false;
                    if (callback != null) {
                        callback.onDragBy(rawX - lastRawX, rawY - lastRawY);
                        callback.onDragEnded();
                    }
                } else performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(hideAfterHold);
                holding = false;
                holdConsumed = true;
                if (dragging && callback != null) callback.onDragCancelled();
                dragging = false;
                return true;
            default: return holding;
        }
    }
    @Override public boolean performClick() {
        super.performClick();
        if (mode == Mode.COLLAPSED && callback != null && callback.onOpenRequested()) return true;
        changeMode(mode == Mode.COLLAPSED ? Mode.MENU : Mode.COLLAPSED);
        return true;
    }
    @Override public boolean performLongClick() {
        removeCallbacks(hideAfterHold);
        dragging = true;
        holdConsumed = false;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        hideKeyboard();
        if (callback != null) callback.onDragStarted();
        return true;
    }
    private void updateDescription() {
        setContentDescription(mode == Mode.COLLAPSED
                ? "클릭 기능 열기. 꾹 누르면 휴지통이 나타납니다. 끌어서 이동하거나 휴지통에 놓아 숨깁니다."
                : "클릭 메뉴 접기. 꾹 누르면 휴지통이 나타납니다. 끌어서 이동하거나 휴지통에 놓아 숨깁니다.");
    }
    private void submitText() {
        String text = goalInput.getText().toString().trim();
        if (text.isEmpty()) return;
        hideKeyboard();
        if (callback != null) callback.onTextSubmitted(text);
        goalInput.setText("");
    }
    private void hideKeyboard() {
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(goalInput.getWindowToken(), 0);
        goalInput.clearFocus();
    }
    @Override protected void onDetachedFromWindow() {
        finishMicrophoneTouch(true);
        removeCallbacks(hideAfterHold);
        removeCallbacks(notifyContentSize);
        holding = dragging = false;
        super.onDetachedFromWindow();
    }
    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
