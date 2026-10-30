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

/** One floating control shared by the home screen and the system overlay. */
public final class ClickOverlayLayout extends ViewGroup {
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
        default void onStopRequested() {}
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final EditText goalInput;
    private LinearLayout panel;
    private TextView transcript;
    private LinearLayout textEntry;
    private TextView voiceSwitch;
    private TextView inputFeedback;
    private ImageView microphone;
    private TextView microphoneLabel, requestLabel, stopRequest;
    private LinearLayout requestRow;
    private boolean voiceInputActive, requestStoppable;
    private String requestStatus;
    private Mode mode = Mode.COLLAPSED;
    private Callback callback;
    private float downRawX, downRawY, lastRawX, lastRawY;
    private boolean holding, dragging, holdConsumed;
    private final int dragSlop;
    private String recognizedSpeech = "인식한 말이 여기에 표시돼요.";
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
        recognizedSpeech = text == null || text.trim().isEmpty() ? "인식한 말이 여기에 표시돼요." : text.trim();
        if (transcript != null) transcript.setText(recognizedSpeech);
    }
    public void showInputFeedback(String text) {
        if (inputFeedback == null || text == null || text.trim().isEmpty()) return;
        inputFeedback.setText(text.trim());
        inputFeedback.setVisibility(VISIBLE);
    }
    public boolean isVoiceInputActive() { return voiceInputActive; }
    public void setVoiceInputActive(boolean active) {
        voiceInputActive = active;
        if (microphone != null) {
            microphone.setContentDescription(active ? "음성 입력 취소" : "마이크 켜기");
            microphone.setBackground(ClickUi.rounded(active ? 0xFFFFE5E5 : Color.WHITE, dp(28)));
            microphoneLabel.setText(active ? "입력 취소" : "마이크 켜기");
        }
    }
    public void setRequestStatus(String status, boolean canStop) {
        requestStatus = status;
        requestStoppable = canStop;
        if (requestRow != null) {
            requestRow.setVisibility(status == null ? GONE : VISIBLE);
            requestLabel.setText(status);
            stopRequest.setVisibility(canStop ? VISIBLE : GONE);
        }
    }

    public int getWindowWidth(Mode target, int screenWidth) {
        return target == Mode.COLLAPSED ? dp(64) : Math.min(screenWidth - dp(24), dp(320));
    }
    public int getWindowHeight(Mode target) {
        if (target == Mode.COLLAPSED) return dp(64);
        int base = target == Mode.TEXT ? 204 : target == Mode.VOICE ? 340 : 232;
        float scale = Math.max(1f, getResources().getConfiguration().fontScale);
        return Math.min(Math.round(dp(base) + dp(100) * (scale - 1f)),
                getResources().getDisplayMetrics().heightPixels - dp(96));
    }
    public float getClickCenterX(Mode target) {
        return getClickCenterX(target, getWindowWidth(target, getResources().getDisplayMetrics().widthPixels));
    }
    public float getClickCenterX(Mode target, int width) {
        return target == Mode.COLLAPSED ? dp(32) : width - dp(16) - handleWidth() / 2f;
    }
    public float getClickCenterY(Mode target) {
        return target == Mode.COLLAPSED ? dp(32) : dp(12) + headerHeight() / 2f;
    }

    private int headerHeight() {
        return dp(Math.max(48, Math.round(30 * getResources().getConfiguration().fontScale) + 12));
    }
    private int handleWidth() {
        return dp(Math.max(64, Math.round(44 * getResources().getConfiguration().fontScale) + 20));
    }

    private void changeMode(Mode next) {
        if (mode == next) return;
        Mode old = mode;
        if (old == Mode.TEXT) hideKeyboard();
        mode = next;
        renderPanel();
        updateDescription();
        if (callback != null) callback.onModeChanged(old, next);
        requestLayout();
        invalidate();
    }

    private void renderPanel() {
        detach(goalInput);
        removeAllViews();
        transcript = null;
        textEntry = null;
        voiceSwitch = null;
        inputFeedback = null;
        microphone = null;
        microphoneLabel = requestLabel = stopRequest = null;
        requestRow = null;
        if (mode == Mode.COLLAPSED) return;
        panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        panel.setBackground(ClickUi.outlined(Color.WHITE, ClickUi.BORDER, dp(28), dp(1)));
        addView(panel);

        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("click", 22, ClickUi.TEXT, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = label("줄이기", 14, ClickUi.SUBTEXT, true);
        close.setId(R.id.overlay_collapse);
        close.setGravity(Gravity.CENTER);
        close.setSingleLine(true);
        close.setContentDescription("줄이기. 작은 click 버튼으로 접기");
        close.setPadding(dp(8), dp(8), dp(8), dp(8));
        close.setFocusable(true);
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(0x183478F6),
                ClickUi.rounded(0xFFF1F4F9, dp(16)), ClickUi.rounded(Color.WHITE, dp(16))));
        close.setOnClickListener(view -> collapse());
        header.addView(close, new LinearLayout.LayoutParams(handleWidth(), headerHeight()));
        panel.addView(header, new LinearLayout.LayoutParams(-1, headerHeight()));

        requestRow = new LinearLayout(getContext());
        requestRow.setGravity(Gravity.CENTER_VERTICAL);
        requestLabel = label("", 15, ClickUi.SUBTEXT, false);
        requestLabel.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        requestRow.addView(requestLabel, new LinearLayout.LayoutParams(0, -2, 1));
        stopRequest = action("요청 중지", false, () -> { if (callback != null) callback.onStopRequested(); });
        stopRequest.setId(R.id.overlay_stop_request);
        stopRequest.setTextSize(15);
        requestRow.addView(stopRequest, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(requestRow, spaced(8));
        setRequestStatus(requestStatus, requestStoppable);

        if (mode == Mode.MENU) {
            LinearLayout choices = new LinearLayout(getContext());
            choices.setOrientation(LinearLayout.VERTICAL);
            choices.addView(feature("말로 시작", true, () -> changeMode(Mode.VOICE)), spaced(0));
            choices.addView(feature("글로 입력", false, () -> changeMode(Mode.TEXT)), spaced(8));
            addScrollableBody(choices, 8);
        } else if (mode == Mode.VOICE) {
            LinearLayout listening = new LinearLayout(getContext());
            listening.setOrientation(LinearLayout.VERTICAL);
            listening.setGravity(Gravity.CENTER);
            listening.setPadding(dp(12), dp(12), dp(12), dp(12));
            listening.setBackground(ClickUi.rounded(ClickUi.PALE_BLUE, dp(22)));
            ImageView mic = new ImageView(getContext());
            microphone = mic;
            mic.setId(R.id.overlay_microphone);
            mic.setImageResource(R.drawable.ic_overlay_microphone);
            mic.setPadding(dp(12), dp(12), dp(12), dp(12));
            mic.setFocusable(true);
            mic.setOnClickListener(view -> { if (callback != null) callback.onMicrophoneClicked(); });
            listening.addView(mic, new LinearLayout.LayoutParams(dp(60), dp(60)));
            microphoneLabel = label("", 15, ClickUi.BLUE_DARK, true);
            microphoneLabel.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            listening.addView(microphoneLabel, spaced(6));
            microphoneLabel.setGravity(Gravity.CENTER);
            microphoneLabel.setOnClickListener(view -> mic.performClick());
            setVoiceInputActive(voiceInputActive);
            TextView microphoneHelp = label("마이크 버튼을 누른 뒤 말씀해 주세요.\n인식한 말이 아래에 표시돼요.", 16, ClickUi.SUBTEXT, false);
            microphoneHelp.setGravity(Gravity.CENTER);
            listening.addView(microphoneHelp, spaced(12));
            transcript = label(recognizedSpeech, 18, ClickUi.TEXT, false);
            transcript.setGravity(Gravity.CENTER);
            transcript.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            listening.addView(transcript, spaced(16));
            addScrollableBody(listening, 18);
            LinearLayout actions = new LinearLayout(getContext());
            actions.setGravity(Gravity.CENTER_VERTICAL);
            TextView write = action("글로 입력", true, () -> changeMode(Mode.TEXT));
            TextView stop = action("메뉴로", false, () -> changeMode(Mode.MENU));
            stop.setContentDescription("음성 입력을 닫고 메뉴로 돌아가기");
            actions.addView(write, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, -2, 1);
            stopParams.leftMargin = dp(10);
            actions.addView(stop, stopParams);
            panel.addView(actions, spaced(8));
        } else {
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
            TextView voice = action("말로 시작", false, () -> changeMode(Mode.VOICE));
            voiceSwitch = voice;
            voice.setContentDescription("음성으로 입력하기");
            composer.addView(voice, buttonParams());
            addScrollableBody(composer, 12);
        }
    }

    private void addScrollableBody(View content, int margin) {
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1);
        params.topMargin = dp(margin);
        panel.addView(scroll, params);
    }

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

    private View feature(String title, boolean microphone, Runnable action) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(16), dp(16), dp(16));
        row.setMinimumHeight(dp(64));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x223478F6),
                ClickUi.rounded(microphone ? 0xFFEAF2FF : 0xFFF6F8FC, dp(20)),
                ClickUi.rounded(Color.WHITE, dp(20))));
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(microphone ? R.drawable.ic_overlay_microphone : R.drawable.ic_overlay_edit);
        icon.setImageTintList(ColorStateList.valueOf(microphone ? Color.WHITE : ClickUi.BLUE));
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setBackground(ClickUi.rounded(microphone ? ClickUi.BLUE : 0xFFE6EFFF, dp(15)));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView copy = label(title, 19, ClickUi.TEXT, true);
        copy.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = dp(16);
        row.addView(copy, copyParams);
        TextView arrow = label("›", 26, ClickUi.SUBTEXT, false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(32)));
        row.setContentDescription(microphone ? "말로 시작. 마이크로 요청하기" : "글로 입력. 키보드로 요청하기");
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setTypeface(getResources().getFont(bold
                ? com.click.designpreview.R.font.pretendard_semibold
                : com.click.designpreview.R.font.pretendard_medium));
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
                ? "Click 기능 열기. 꾹 누르면 휴지통이 나타납니다. 끌어서 이동하거나 휴지통에 놓아 숨깁니다."
                : "Click 메뉴 접기. 꾹 누르면 휴지통이 나타납니다. 끌어서 이동하거나 휴지통에 놓아 숨깁니다.");
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
        removeCallbacks(hideAfterHold);
        holding = dragging = false;
        super.onDetachedFromWindow();
    }
    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
