package com.click.ui.overlay;

import android.annotation.SuppressLint;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import com.click.R;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import com.click.domain.goal.RequirementReview;
import com.click.ui.common.ClickUi;
import org.json.JSONArray;
import org.json.JSONObject;


/** Compact request box, spoken guidance, and explicit user choices. */
@SuppressLint("ViewConstructor")
public final class TaskFlowOverlayView extends LinearLayout {
    public static final String AUTOMATIC_PROGRESS_MESSAGE = "지금 진행 중이에요.";
    public static final String FONT_SIZE_QUESTION = "글자 크기를 어떻게 조절할까요?";
    private static final int BLUE = 0xFF3478F6;
    private static final int BLUE_DARK = 0xFF2868E8;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF6F7F9E;
    private static final int PALE_BLUE = 0xFFF1F6FF;
    private static final int BORDER = 0xFFDCE6F5;
    public interface Listener {
        void chooseGoal(JSONObject goal);
        void clarify(String choice);
        void rephrase(String text);
        void result(String answer);
        void adjust(String reason);
        void control(String command);
        default void requirementChoice(String choice) {}
        default void requirementAnswer(String answer) {}
        default void confirmRequirements() {}
        default void answerGoalInput(String value) {}
        default void resetGoalInputs() {}
        default void onReviewChoiceSelected() {}
        default void replayGuidance(String message) {}
        default void stopGuidance() {}
        default void collapse() {}
        default void expand() {}
        default void openInput() {}
    }
    private final LoadingStatusView loadingStatus;
    private final TextView automationStatus;
    private final LinearLayout statusBox, progressSummary;
    private final Listener listener;
    private final LinearLayout content;
    private final Button pause;
    private final Button reviewConfirm;
    private final LinearLayout reviewNavigation, stepHeader;
    private final android.widget.ProgressBar stepProgress;
    private final TextView stepLabel;
    private final Button headerInput;
    private final View divider;
    private final LinearLayout controls;
    private final LinearLayout recoveryActions;
    private TextView narrationStatus, narrationText;
    private LinearLayout requestCaptionRow;
    private Button narrationPlayback, narrationCaptions, reviewReplay;
    private String narrationMessage;
    private boolean narrationSpeaking, narrationUnavailable;
    private final TextView heading;
    private final TextView brand;
    private final View close;
    private final LinearLayout header;
    private final ScrollView scroll;
    private int maxHeight;
    private boolean resetScrollPending;
    private final ScrollView compactScroll;
    private final TextView compactMessage;
    private String progressMessage;
    private boolean minimizedProgress;
    private boolean automaticProgress;
    private boolean reviewScreen;
    private int reviewStep;
    private int[] expandedVisibility;
    private String requestSummary;
    private final CompactBubbleBackground compactBackground = new CompactBubbleBackground();

    public TaskFlowOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * .78f);
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dp(24)); bg.setStroke(dp(1), BORDER);
        setBackground(bg); setElevation(dp(10));
        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(VERTICAL);
        TextView bubbleLabel = text("click · 펼치기", 13);
        bubbleLabel.setTextColor(BLUE_DARK);
        bubble.addView(bubbleLabel);
        compactMessage = text("", 16);
        compactMessage.setOnClickListener(view -> listener.expand());
        bubbleLabel.setOnClickListener(view -> listener.expand());
        bubble.addView(compactMessage);
        compactScroll = new ScrollView(context);
        compactScroll.setFillViewport(false);
        compactScroll.addView(bubble, new ScrollView.LayoutParams(-1, -2));
        compactScroll.setVisibility(GONE);
        addView(compactScroll, new LayoutParams(-1, -2));
        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(VERTICAL);
        LinearLayout identity = new LinearLayout(context);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cursor = new ImageView(context);
        cursor.setImageResource(com.click.designpreview.R.drawable.ic_click_cursor);
        cursor.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(cursor, new LayoutParams(dp(23), dp(23)));
        brand = text("click 안내", 14);
        brand.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        brand.setTextColor(INK);
        brand.setPadding(dp(6), 0, 0, 0);
        identity.addView(brand);
        titles.addView(identity);
        heading = ClickUi.title(context, "");
        heading.setTextSize(20);
        heading.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        heading.setLetterSpacing(0);
        heading.setPadding(0, 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(titles, new LayoutParams(0, -2, 1));
        Button input = quietButton("입력", listener::openInput);
        headerInput = input;
        input.setContentDescription("말이나 글로 입력하기");
        input.setTextSize(12); input.setMinimumWidth(dp(48));
        input.setPadding(dp(8), dp(8), dp(8), dp(8));
        input.setBackground(new InsetDrawable(ripple(PALE_BLUE, 0x183478F6, 12), 0, dp(8), 0, dp(8)));
        input.setId(R.id.task_flow_input);
        LayoutParams inputParams = new LayoutParams(-2, -2); inputParams.leftMargin = dp(4);
        header.addView(input, inputParams);
        Button minimize = quietButton("줄이기", listener::collapse);
        close = minimize;
        close.setId(R.id.task_flow_collapse);
        minimize.setTextSize(12); minimize.setMinimumWidth(dp(54));
        close.setContentDescription("안내 창 줄이기");
        close.setPadding(dp(10), dp(8), dp(10), dp(8));
        close.setFocusable(true);
        close.setBackground(new InsetDrawable(ripple(PALE_BLUE, 0x183478F6, 12), 0, dp(8), 0, dp(8)));
        close.setOnClickListener(view -> listener.collapse());
        LayoutParams closeParams = new LayoutParams(-2, -2);
        closeParams.leftMargin = dp(4);
        header.addView(close, closeParams);
        LayoutParams headerParams = new LayoutParams(-1, -2);
        headerParams.bottomMargin = dp(12);
        addView(header, headerParams);
        stepHeader = new LinearLayout(context);
        stepHeader.setGravity(Gravity.CENTER_VERTICAL);
        stepProgress = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        stepProgress.setMax(2);
        android.graphics.drawable.ClipDrawable fill = new android.graphics.drawable.ClipDrawable(round(BLUE, 3), Gravity.LEFT,
                android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable track = new android.graphics.drawable.LayerDrawable(new Drawable[]{round(0xFFE8F0FF, 3), fill});
        track.setId(0, android.R.id.background); track.setId(1, android.R.id.progress);
        stepProgress.setProgressDrawable(track);
        stepHeader.addView(stepProgress, new LayoutParams(0, dp(3), 1));
        stepLabel = text("", 11); stepLabel.setTextColor(SECONDARY);
        stepLabel.setPadding(dp(10), 0, 0, 0);
        stepHeader.addView(stepLabel);
        stepHeader.setVisibility(GONE);
        LayoutParams stageParams = spaced(0); stageParams.bottomMargin = dp(16);
        addView(stepHeader, stageParams);
        statusBox = new LinearLayout(context);
        statusBox.setOrientation(VERTICAL);
        statusBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusBox.setBackground(round(PALE_BLUE, 16));
        progressSummary = new LinearLayout(context);
        progressSummary.setOrientation(VERTICAL);
        progressSummary.setVisibility(GONE);
        statusBox.addView(progressSummary, spaced(0));
        loadingStatus = new LoadingStatusView(context, true);
        statusBox.addView(loadingStatus, spaced(4));
        automationStatus = text("", 14);
        automationStatus.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        automationStatus.setVisibility(GONE);
        statusBox.addView(automationStatus, spaced(4));
        statusBox.setVisibility(GONE);
        LayoutParams statusParams = spaced(0); statusParams.bottomMargin = dp(8);
        addView(statusBox, statusParams);
        content = new LinearLayout(context); content.setOrientation(VERTICAL);
        content.setPadding(0, 0, 0, dp(10));
        scroll = new ScrollView(context); scroll.setFillViewport(false); scroll.addView(content);
        scroll.setVerticalScrollBarEnabled(true);
        content.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!resetScrollPending) return;
            resetScrollPending = false;
            scroll.scrollTo(0, 0);
        });
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        reviewConfirm = primaryButton("이대로 시작하기", () -> listener.confirmRequirements());
        styleReviewButton(reviewConfirm, true);
        reviewConfirm.setVisibility(GONE);
        LayoutParams reviewParams = spaced(0); reviewParams.bottomMargin = dp(8);
        addView(reviewConfirm, reviewParams);
        reviewNavigation = new LinearLayout(context);
        reviewNavigation.setVisibility(GONE);
        addView(reviewNavigation, spaced(0));

        recoveryActions = new LinearLayout(context);
        recoveryActions.setOrientation(VERTICAL);
        recoveryActions.setVisibility(GONE);
        addView(recoveryActions, spaced(0));

        divider = new View(context);
        divider.setBackgroundColor(BORDER);
        LayoutParams dividerParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.bottomMargin = dp(8);
        addView(divider, dividerParams);

        controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER);
        pause = quietButton("일시정지", () -> listener.control("pause_task"));
        LayoutParams pauseParams = new LayoutParams(0, -2, 1f);
        pauseParams.rightMargin = dp(6);
        controls.addView(pause, pauseParams);
        Button stop = secondaryButton("요청 중지", () -> listener.control("cancel_task"));
        stop.setContentDescription("처리 중인 요청 중지");
        stop.setTextColor(0xFF536885);
        LayoutParams stopParams = new LayoutParams(0, -2, 1f);
        stopParams.leftMargin = dp(6);
        controls.addView(stop, stopParams);
        addView(controls);
    }

    public void setAutomationStatus(String message) {
        automationStatus.setText(message == null ? "" : message);
        automationStatus.setVisibility(message == null || message.trim().isEmpty() ? GONE : VISIBLE);
        updateStatusBox();
    }
    public void setLoadingStatus(String message) { loadingStatus.setStatus(message); updateStatusBox(); }
    private void updateStatusBox() {
        statusBox.setVisibility(!(minimizedProgress && automaticProgress) && (progressSummary.getVisibility() == VISIBLE
                || loadingStatus.isActive() || automationStatus.getVisibility() == VISIBLE
                ) ? VISIBLE : GONE);
    }

    public void setAutomaticProgress(boolean automatic) {
        automaticProgress = automatic && hasProgress();
        if (minimizedProgress) compactMessage.setText(compactGuidance());
        updateStatusBox();
    }

    private String compactGuidance() {
        return automaticProgress ? AUTOMATIC_PROGRESS_MESSAGE : guidanceMessage();
    }

    public int getWindowWidth(int screenWidth) { return Math.min(screenWidth - dp(24), dp(minimizedProgress ? 260 : 340)); }
    public void setAvailableHeight(int height) { if (maxHeight != height) { maxHeight = height; requestLayout(); } }
    public boolean hasProgress() { return progressMessage != null; }
    public String guidanceMessage() {
        return narrationMessage != null ? narrationMessage : progressMessage != null ? progressMessage
                : heading.getText().toString();
    }
    public boolean hasCompactContent() { return !guidanceMessage().trim().isEmpty(); }
    public boolean isMinimizedProgress() { return minimizedProgress; }
    private View[] expandedSections() {
        return new View[]{header, scroll, controls, divider, reviewConfirm, recoveryActions,
                progressSummary, stepHeader, reviewNavigation};
    }
    public void setMinimizedProgress(boolean minimized) {
        minimized = minimized && hasCompactContent();
        if (minimizedProgress == minimized) return;
        minimizedProgress = minimized;
        compactMessage.setText(minimized ? compactGuidance() : "");
        compactScroll.setVisibility(minimized ? VISIBLE : GONE);
        View[] sections = expandedSections();
        if (minimized) expandedVisibility = new int[sections.length];
        for (int i = 0; i < sections.length; i++) {
            if (minimized) { expandedVisibility[i] = sections[i].getVisibility(); sections[i].setVisibility(GONE); }
            else if (expandedVisibility != null) sections[i].setVisibility(expandedVisibility[i]);
        }
        updateStatusBox();
        updateBubblePadding();
        setBackground(minimized ? compactBackground : outline(Color.WHITE, BORDER, 24));
        requestLayout();
    }
    public void setRequestSummary(String request) {
        if (request != null && !request.trim().isEmpty() && !"선택한 작업".equals(request)) requestSummary = request;
    }
    public String phaseLabel() {
        return reviewScreen ? (reviewStep == 1 ? "상세정보 확인" : "요청 확인")
                : hasProgress() ? "안내 중" : heading.getText().toString();
    }
    public void setGuidanceProgress(int completed, int total) {
        boolean visible = hasProgress() && total > 0;
        stepHeader.setVisibility(visible && !minimizedProgress ? VISIBLE : GONE);
        if (minimizedProgress && expandedVisibility != null) {
            View[] sections = expandedSections();
            for (int i = 0; i < sections.length; i++)
                if (sections[i] == stepHeader) expandedVisibility[i] = visible ? VISIBLE : GONE;
        }
        if (!visible) return;
        int current = Math.max(0, Math.min(completed, total));
        stepProgress.setMax(total); stepProgress.setProgress(current);
        stepLabel.setText(current + "/" + total);
        stepHeader.setContentDescription("전체 " + total + "단계 중 " + current + "단계를 진행했어요");
    }
    public void actionRequired(String message) {
        if (hasProgress()) progress(requestSummary, null, message, false);
        else { clear("함께 확인해 주세요"); addQuestionNarration(message); }
        setAutomaticProgress(false);
    }
    public void setCompactAnchor(float centerX, boolean above) {
        compactBackground.anchorX = centerX;
        boolean changed = compactBackground.above != above;
        compactBackground.above = above; compactBackground.invalidateSelf();
        if (changed && minimizedProgress) { updateBubblePadding(); invalidateOutline(); }
    }
    private void updateBubblePadding() {
        if (minimizedProgress) setPadding(dp(14), dp(compactBackground.above ? 22 : 10), dp(14), dp(compactBackground.above ? 10 : 22));
        else if (reviewScreen) setPadding(dp(20), dp(14), dp(20), dp(16));
        else setPadding(dp(14), dp(12), dp(14), dp(12));
    }
    @Override protected void onMeasure(int width, int height) {
        int available = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED ? maxHeight
                : Math.min(maxHeight, MeasureSpec.getSize(height));
        boolean compact = available < dp(260) * Math.max(1f, getResources().getConfiguration().fontScale);
        brand.setVisibility(compact ? GONE : VISIBLE);
        heading.setTextSize(reviewScreen ? 17 : compact ? 18 : 20);
        LayoutParams headerParams = (LayoutParams) header.getLayoutParams();
        headerParams.bottomMargin = dp(compact ? 6 : 8);
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
    }
    private void clear(String title) {
        reviewScreen = false;
        reviewStep = 0;
        setMinimizedProgress(false);
        updateBubblePadding();
        if (reviewReplay != null) { header.removeView(reviewReplay); reviewReplay = null; }
        automaticProgress = false;
        progressMessage = null;
        progressSummary.removeAllViews();
        progressSummary.setVisibility(GONE);
        setAutomationStatus(null);
        content.removeAllViews();
        content.setPadding(0, 0, 0, dp(10));
        if (title != null && !title.isEmpty()) content.addView(heading, spaced(0));
        reviewConfirm.setVisibility(GONE);
        stepHeader.setVisibility(GONE);
        stepLabel.setText("");
        reviewNavigation.removeAllViews(); reviewNavigation.setVisibility(GONE);
        recoveryActions.removeAllViews();
        recoveryActions.setVisibility(GONE);
        headerInput.setVisibility(VISIBLE);
        divider.setVisibility(VISIBLE);
        controls.setVisibility(VISIBLE);
        narrationStatus = narrationText = null;
        narrationPlayback = narrationCaptions = null;
        narrationMessage = null; narrationSpeaking = false; requestCaptionRow = null;
        resetScrollPending = true;
        scroll.scrollTo(0, 0);
        scroll.post(() -> scroll.scrollTo(0, 0));
        heading.setText(title);
        pause.setText("일시정지"); pause.setOnClickListener(v -> listener.control("pause_task"));
    }

    /** A transport or contract failure is not a request for the user to rephrase. */
    public void resolutionUnavailable(String request, String message, Runnable retry) {
        clear("요청을 확인하지 못했어요");
        headerInput.setVisibility(GONE);
        divider.setVisibility(GONE);
        controls.setVisibility(GONE);
        recoveryActions.setVisibility(VISIBLE);
        addRequestBox(request, null);
        content.addView(text(message, 16), spaced(12));
        recoveryActions.addView(primaryButton("다시 확인", retry), spaced(0));
        recoveryActions.addView(quietButton("요청 중지", () -> listener.control("cancel_task")), spaced(8));
    }

    /** An unresolved request has no selectable goal or running task to pause. */
    public void unresolvedGoal(String request) {
        clear("조금 더 알려주세요");
        headerInput.setVisibility(GONE);
        divider.setVisibility(GONE);
        controls.setVisibility(GONE);
        recoveryActions.setVisibility(VISIBLE);

        TextView help = text(getContext().getString(R.string.unresolved_goal_guidance), 16);
        content.addView(help, spaced(0));
        if (request != null && !request.trim().isEmpty()) {
            addRequestBox(request, null);
            ((LayoutParams) content.getChildAt(content.getChildCount() - 1).getLayoutParams()).topMargin = dp(12);
        }
        LinearLayout example = new LinearLayout(getContext());
        example.setOrientation(VERTICAL);
        example.setPadding(dp(14), dp(10), dp(14), dp(10));
        example.setBackground(round(PALE_BLUE, 14));
        TextView exampleLabel = text("이렇게 말해 보세요", 13);
        exampleLabel.setTextColor(BLUE_DARK);
        example.addView(exampleLabel);
        example.addView(text("“휴대폰 글자를 더 크게 해 줘”", 16));
        content.addView(example, spaced(12));

        LinearLayout editor = new LinearLayout(getContext());
        editor.setOrientation(VERTICAL);
        TextView label = text("요청 내용", 14);
        label.setTextColor(SECONDARY);
        editor.addView(label);
        EditText input = new EditText(getContext());
        input.setTextSize(17); input.setTextColor(INK); input.setHintTextColor(SECONDARY);
        input.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        input.setContentDescription("요청 내용 수정");
        input.setHint("예) 휴대폰 글자를 더 크게 해 줘");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(2); input.setMaxLines(4);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(outline(Color.WHITE, BORDER, 14));
        input.setText(request == null ? "" : request);
        editor.addView(input, spaced(4));
        editor.setVisibility(GONE);
        content.addView(editor, spaced(12));

        Runnable voice = () -> {
            hideKeyboard(input);
            listener.stopGuidance();
            listener.openInput();
        };
        Button retry = primaryButton("다시 말하기", voice);
        recoveryActions.addView(retry, spaced(0));
        LinearLayout alternatives = new LinearLayout(getContext());
        Button edit = quietButton("글로 수정하기", () -> {});
        edit.setOnClickListener(v -> {
            if (editor.getVisibility() == VISIBLE) { voice.run(); return; }
            listener.stopGuidance();
            heading.setText("요청을 수정해 주세요");
            for (int i = 1; i < content.getChildCount(); i++) content.getChildAt(i).setVisibility(GONE);
            editor.setVisibility(VISIBLE);
            retry.setText("이 내용으로 다시 요청");
            retry.setOnClickListener(submit -> {
                String revised = input.getText().toString().trim();
                if (revised.isEmpty()) return;
                hideKeyboard(input);
                listener.rephrase(revised);
            });
            retry.setEnabled(!input.getText().toString().trim().isEmpty());
            retry.setAlpha(retry.isEnabled() ? 1f : .48f);
            edit.setText("다시 말하기");
            input.requestFocus(); input.setSelection(input.length());
            showKeyboardWhenFocused(input);
        });
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (editor.getVisibility() != VISIBLE) return;
                retry.setEnabled(!s.toString().trim().isEmpty());
                retry.setAlpha(retry.isEnabled() ? 1f : .48f);
            }
            @Override public void afterTextChanged(android.text.Editable value) {}
        });
        edit.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 12));
        alternatives.addView(edit, new LayoutParams(0, -2, 1));
        Button cancel = quietButton("요청 취소", () -> {
            hideKeyboard(input);
            listener.stopGuidance();
            listener.control("cancel_task");
        });
        cancel.setTextColor(SECONDARY);
        cancel.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 12));
        alternatives.addView(cancel, new LayoutParams(0, -2, 1));
        recoveryActions.addView(alternatives, spaced(4));
    }

    private void hideKeyboard(EditText input) {
        android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void reviewStage(int step, String title, String description) {
        clear(title);
        reviewScreen = true;
        reviewStep = step;
        updateBubblePadding();
        divider.setVisibility(GONE); controls.setVisibility(GONE);
        TextView subtitle = text(description, 12); subtitle.setTextColor(SECONDARY);
        subtitle.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
        subtitle.setPadding(0, 0, 0, 0);
        content.addView(subtitle, spaced(6));
        content.setPadding(0, 0, 0, dp(step == 2 ? 4 : 12));
        reviewReplay = quietButton("", () -> {
            if (narrationSpeaking) listener.stopGuidance();
            else listener.replayGuidance(narrationMessage);
        });
        Drawable speaker = getContext().getDrawable(R.drawable.ic_overlay_speaker);
        speaker.setBounds(0, 0, dp(18), dp(18));
        reviewReplay.setCompoundDrawables(speaker, null, null, null);
        reviewReplay.setPadding(dp(11), dp(10), dp(11), dp(10));
        reviewReplay.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 12));
        reviewReplay.setContentDescription("Click 음성 안내 다시 듣기");
        header.addView(reviewReplay, 1, new LayoutParams(dp(40), -2));
        narrationPlayback = reviewReplay;
    }

    private Button reviewNext(Runnable back, Runnable next) {
        reviewNavigation.removeAllViews(); reviewNavigation.setOrientation(HORIZONTAL);
        reviewNavigation.setVisibility(VISIBLE);
        Button previous = quietButton("이전 단계", back);
        styleReviewButton(previous, false);
        reviewNavigation.addView(previous, new LayoutParams(0, -2, 1));
        Button forward = primaryButton("다음 단계", next);
        styleReviewButton(forward, true);
        LayoutParams params = new LayoutParams(0, -2, 1); params.leftMargin = dp(8);
        reviewNavigation.addView(forward, params);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, round(0xFFF0F3F8, 14));
        states.addState(new int[]{}, forward.getBackground());
        forward.setBackground(states);
        forward.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xFFA4B0C2, Color.WHITE}));
        forward.setEnabled(false); forward.setAlpha(1f);
        return forward;
    }

    private void readyActions(Runnable back) {
        reviewConfirm.setText("이대로 시작하기");
        reviewConfirm.setVisibility(VISIBLE); reviewConfirm.setEnabled(true); reviewConfirm.setAlpha(1f);
        reviewNavigation.removeAllViews(); reviewNavigation.setOrientation(VERTICAL);
        reviewNavigation.setVisibility(VISIBLE);
        Button previous = secondaryButton("이전 단계", back);
        styleReviewButton(previous, false);
        reviewNavigation.addView(previous, new LayoutParams(-1, -2));
    }

    private RadioGroup choiceCards(JSONArray choices, java.util.function.Consumer<JSONObject> selected) {
        RadioGroup group = new RadioGroup(getContext()); group.setOrientation(VERTICAL);
        if (choices != null) for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i); if (choice == null) continue;
            RadioButton card = new RadioButton(getContext()); card.setId(View.generateViewId());
            String label = choice.optString("label");
            if ("휴대폰 설정에서 기본 글자 크기 변경".equals(label)) label = "휴대폰 기본 글자 크기";
            else if ("특정 앱 안에서만 변경".equals(label)) label = "특정 앱 안에서만";
            card.setText(label); card.setTextSize(14); card.setTextColor(INK);
            card.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
            card.setButtonDrawable((Drawable) null); card.setIncludeFontPadding(false);
            card.setGravity(Gravity.CENTER_VERTICAL); card.setMinHeight(dp(48));
            card.setPadding(dp(14), dp(11), dp(14), dp(11));
            StateListDrawable mark = new StateListDrawable();
            mark.addState(new int[]{android.R.attr.state_checked}, new StatusMark(true, false));
            mark.addState(new int[]{}, new StatusMark(false, false));
            mark.setBounds(0, 0, dp(20), dp(20));
            card.setCompoundDrawables(null, null, mark, null); card.setCompoundDrawablePadding(dp(12));
            card.setBackground(cardBackground(false));
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(-1, -2); params.bottomMargin = dp(8);
            group.addView(card, params);
            card.setOnCheckedChangeListener((view, checked) -> {
                card.setBackground(cardBackground(checked));
                card.setTextColor(checked ? BLUE_DARK : INK);
                if (checked) { selected.accept(choice); listener.onReviewChoiceSelected(); }
            });
        }
        return group;
    }

    private void requestEditor(String request, Runnable reset) {
        LinearLayout editor = new LinearLayout(getContext()); editor.setOrientation(VERTICAL);
        editor.setVisibility(GONE);
        EditText input = new EditText(getContext()); input.setTextSize(14); input.setTextColor(INK);
        input.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
        input.setContentDescription("요청 내용 수정"); input.setHint("원하시는 내용을 적어 주세요");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2); input.setMaxLines(4); input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(12), dp(12), dp(12)); input.setBackground(outline(Color.WHITE, BORDER, 14));
        input.setText(request);
        editor.addView(input, spaced(0));
        Button apply = primaryButton("수정한 요청 확인", () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            hideKeyboard(input); listener.rephrase(value);
        });
        styleReviewButton(apply, true);
        editor.addView(apply, spaced(8));
        if (reset != null) editor.addView(quietButton("답변을 다시 선택", reset), spaced(6));
        boolean confirmation = reviewStep == 2;
        Button edit = quietButton(confirmation ? "내용 수정하기" : "다른 내용 입력하기", () -> {
            listener.stopGuidance(); editor.setVisibility(VISIBLE); reviewConfirm.setVisibility(GONE);
            input.requestFocus(); input.setSelection(input.length());
            editor.post(() -> scroll.smoothScrollTo(0, editor.getTop()));
            showKeyboardWhenFocused(input);
        });
        edit.setTextSize(13);
        edit.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        edit.setBackground(confirmation ? ripple(Color.TRANSPARENT, 0x183478F6, 12)
                : ripple(0xFFF5F8FD, 0x183478F6, 14));
        if (confirmation) {
            edit.setMinHeight(dp(40)); edit.setMinimumHeight(dp(40));
            edit.setPadding(dp(8), dp(6), 0, dp(6));
            edit.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LayoutParams editParams = new LayoutParams(-2, -2);
            editParams.gravity = Gravity.END; editParams.topMargin = dp(2);
            content.addView(edit, editParams);
        } else content.addView(edit, spaced(8));
        content.addView(editor, spaced(8));
    }

    private void reviewSummary(String message) {
        TextView summary = text(message, 15);
        summary.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
        summary.setLineSpacing(dp(2), 1.15f);
        summary.setPadding(dp(16), dp(14), dp(16), dp(14));
        summary.setBackground(round(0xFFF4F7FC, 16));
        content.addView(summary, spaced(16));
    }

    private String preparedSummary(com.click.domain.goal.GoalPreparationState state) {
        String type = state.goal == null ? "" : state.goal.optString("goal_type");
        boolean increase = type.endsWith(".increase");
        if (type.startsWith("settings.font_size."))
            return "휴대폰의 기본 글자 크기를\n" + (increase ? "더 크게" : "더 작게") + " 변경할게요.";
        if (type.startsWith("settings.brightness."))
            return "휴대폰 화면을\n" + (increase ? "더 밝게" : "더 어둡게") + " 조절할게요.";
        if (type.startsWith("settings.volume."))
            return "동영상·음악 소리를\n" + (increase ? "더 크게" : "더 작게") + " 조절할게요.";
        return state.message == null || state.message.trim().isEmpty() ? state.data.optString("source_text") : state.message;
    }

    public void goals(String message, JSONArray candidates) {
        reviewStage(1, "어떤 작업을 도와드릴까요?", "원하시는 작업을 선택해 주세요.");
        narrationMessage = message;
        final JSONObject[] selected = {null};
        Button next = reviewNext(listener::openInput, () -> {
            if (selected[0] != null) listener.chooseGoal(selected[0]);
        });
        content.addView(choiceCards(candidates, choice -> {
            selected[0] = choice.optJSONObject("goal");
            next.setEnabled(selected[0] != null); next.setAlpha(1f);
        }), spaced(14));
        requestEditor("", null);
    }

    public void preparation(com.click.domain.goal.GoalPreparationState state) {
        if (state.ready()) {
            reviewStage(2, "이렇게 진행할게요", "요청하신 내용을 확인해 주세요.");
            narrationMessage = state.guidance();
            reviewSummary(preparedSummary(state));
            requestEditor(state.data.optString("source_text"), listener::resetGoalInputs);
            readyActions(listener::resetGoalInputs);
            return;
        }
        JSONObject question = state.question();
        reviewStage(1, question == null ? "조금 더 확인할게요" : question.optString("message", "어떤 작업을 도와드릴까요?"),
                question != null && "choice".equals(question.optString("kind"))
                        ? "원하시는 항목을 선택해 주세요." : "필요한 내용을 알려주세요.");
        narrationMessage = state.guidance();
        if (state.needsInput()) {
            if ("choice".equals(question.optString("kind"))) {
                final String[] answer = {null};
                Button next = reviewNext(listener::resetGoalInputs, () -> {
                    if (answer[0] != null) listener.answerGoalInput(answer[0]);
                });
                content.addView(choiceCards(question.optJSONArray("choices"), choice -> {
                    answer[0] = choice.optString("value"); next.setEnabled(true); next.setAlpha(1f);
                }), spaced(14));
            } else {
                EditText input = new EditText(getContext()); input.setTextSize(14);
                input.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
                input.setHint(question.optString("placeholder", "답변을 입력해 주세요"));
                input.setSingleLine(true); input.setMinHeight(dp(48));
                input.setPadding(dp(12), dp(10), dp(12), dp(10));
                input.setBackground(outline(Color.WHITE, BORDER, 14));
                input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(question.optInt("max_length", 100))});
                content.addView(input, spaced(14));
                Button next = reviewNext(listener::openInput, () -> {
                    String answer = input.getText().toString().trim();
                    if (!answer.isEmpty()) { hideKeyboard(input); listener.answerGoalInput(answer); }
                });
                input.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
                    public void onTextChanged(CharSequence value, int start, int before, int count) {
                        next.setEnabled(!value.toString().trim().isEmpty()); next.setAlpha(1f);
                    }
                    public void afterTextChanged(android.text.Editable value) {}
                });
            }
        } else {
            TextView help = text(state.guidance(), 15); help.setTextColor(SECONDARY);
            content.addView(help, spaced(14));
        }
        requestEditor(state.data.optString("source_text"), null);
    }

    public void requirements(RequirementReview review, boolean executable) {
        requirements(review, executable, review.prompt(executable));
    }
    public void requirementsForPreparation(RequirementReview review, boolean executable, String message, Runnable retry) {
        requirements(review, executable);
        reviewConfirm.setText("선택한 내용 확인");
        content.addView(text(message, 15), spaced(12));
        content.addView(quietButton("다시 확인", retry), spaced(6));
    }
    public void requirements(RequirementReview review, boolean executable, String spokenGuidance) {
        reviewStage(executable ? 2 : 1, executable ? "이렇게 진행할게요" : "어떤 작업을 도와드릴까요?",
                executable ? "요청하신 내용을 확인해 주세요." : "원하시는 항목을 선택해 주세요.");
        narrationMessage = spokenGuidance;
        if (executable) {
            reviewSummary(review.summary());
            readyActions(listener::resetGoalInputs);
        } else if (review.needsTarget() || review.needsDirection()) {
            JSONArray choices = new JSONArray();
            try {
                if (review.needsTarget()) {
                    choices.put(new JSONObject().put("label", "휴대폰 기본 글자").put("value", "phone"));
                    if (!review.appOnly()) choices.put(new JSONObject().put("label", "특정 앱 안에서만").put("value", "app"));
                } else {
                    choices.put(new JSONObject().put("label", "더 크게").put("value", "increase"));
                    choices.put(new JSONObject().put("label", "더 작게").put("value", "decrease"));
                }
            } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            final String[] choice = {null};
            Button next = reviewNext(listener::openInput, () -> {
                if (choice[0] != null) listener.requirementChoice(choice[0]);
            });
            content.addView(choiceCards(choices, selected -> {
                choice[0] = selected.optString("value"); next.setEnabled(true); next.setAlpha(1f);
            }), spaced(14));
        } else {
            content.addView(text(spokenGuidance, 15), spaced(12));
        }
        requestEditor(review.request().split("\n추가 확인:", 2)[0], null);
    }
    private void showKeyboardWhenFocused(EditText input) {
        Runnable show = () -> input.post(() -> {
            if (!input.isAttachedToWindow() || !input.hasFocus()) return;
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        if (input.hasWindowFocus()) show.run();
        else input.getViewTreeObserver().addOnWindowFocusChangeListener(new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override public void onWindowFocusChanged(boolean focused) {
                if (!focused) return;
                if (input.getViewTreeObserver().isAlive()) input.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                show.run();
            }
        });
    }

    // Compatibility with runtime metadata: requirements and execution evidence stay internal.
    public void setRequirements(RequirementReview review) {}
    public void setChecklist(JSONArray checklist) {}

    private void addRequestBox(String request, String resolved) {
        LinearLayout box = new LinearLayout(getContext());
        box.setGravity(Gravity.TOP);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        box.setBackground(round(0xFFF5F6F8, 14));
        LinearLayout copy = new LinearLayout(getContext()); copy.setOrientation(VERTICAL);
        requestCaptionRow = new LinearLayout(getContext()); requestCaptionRow.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(requestCaptionRow);
        TextView value = text(request, 14); value.setPadding(0, dp(2), 0, dp(2));
        requestCaptionRow.addView(value, new LayoutParams(0, -2, 1));
        if (request.length() > 80) {
            value.setMaxLines(3); value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Button expand = quietButton("요청 더 보기", () -> {});
            expand.setTextSize(13); expand.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 10));
            expand.setOnClickListener(v -> {
                boolean more = value.getMaxLines() == 3;
                value.setMaxLines(more ? Integer.MAX_VALUE : 3);
                expand.setText(more ? "요청 접기" : "요청 더 보기");
            });
            copy.addView(expand);
        }
        if (resolved != null && !resolved.equals(request)) {
            TextView summary = text(resolved, 13); summary.setTextColor(BLUE_DARK);
            summary.setPadding(0, dp(8), 0, 0); copy.addView(summary);
        }
        box.addView(copy, new LayoutParams(0, -2, 1));
        content.addView(box, spaced(0));
    }

    private void addQuestionNarration(String message) {
        if (message == null || message.trim().isEmpty()) return;
        narrationMessage = message;
        LinearLayout question = new LinearLayout(getContext());
        question.setGravity(Gravity.CENTER_VERTICAL);
        question.setPadding(dp(12), dp(10), dp(8), dp(10));
        question.setBackground(outline(PALE_BLUE, 0xFFD5E4FF, 16));
        narrationText = text(message, 17);
        narrationText.setTextColor(INK);
        narrationText.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        if (android.os.Build.VERSION.SDK_INT >= 28) narrationText.setAccessibilityHeading(true);
        question.addView(narrationText, new LayoutParams(0, -2, 1));
        narrationPlayback = quietButton("다시 듣기", () -> {
            if (narrationSpeaking) listener.stopGuidance();
            else listener.replayGuidance(narrationMessage);
        });
        narrationPlayback.setTextSize(13);
        narrationPlayback.setMaxLines(1);
        narrationPlayback.setPadding(dp(8), dp(6), dp(8), dp(6));
        narrationPlayback.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 10));
        LayoutParams playbackParams = new LayoutParams(-2, -2);
        playbackParams.leftMargin = dp(8);
        question.addView(narrationPlayback, playbackParams);
        content.addView(question, spaced(10));
        setNarrationState(false, narrationUnavailable);
    }

    private void addNarration(String message) {
        if (message == null || message.trim().isEmpty()) return;
        narrationMessage = message;
        LinearLayout section = new LinearLayout(getContext()); section.setOrientation(VERTICAL);
        LinearLayout player = new LinearLayout(getContext()); player.setGravity(Gravity.CENTER_VERTICAL);
        boolean largeText = getResources().getConfiguration().fontScale > 1.2f;
        narrationStatus = text("Click의 안내", 13); narrationStatus.setTextColor(SECONDARY);
        narrationStatus.setVisibility(largeText ? GONE : VISIBLE);
        player.addView(narrationStatus, new LayoutParams(0, -2, 1));
        narrationPlayback = quietButton("다시 듣기", () -> {
            if (narrationSpeaking) listener.stopGuidance();
            else listener.replayGuidance(narrationMessage);
        });
        narrationPlayback.setTextSize(13); narrationPlayback.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 10));
        player.addView(narrationPlayback, largeText ? new LayoutParams(0, -2, 1) : new LayoutParams(dp(80), -2));
        narrationCaptions = quietButton("글로 보기", () -> {
            boolean show = narrationText.getVisibility() != VISIBLE;
            narrationText.setVisibility(show ? VISIBLE : GONE);
            narrationCaptions.setText(show ? "글 접기" : "글로 보기");
        });
        narrationCaptions.setTextSize(13); narrationCaptions.setTextColor(SECONDARY);
        narrationCaptions.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 10));
        player.addView(narrationCaptions, largeText ? new LayoutParams(0, -2, 1) : new LayoutParams(dp(80), -2));
        section.addView(player);
        narrationText = text(message, 15); narrationText.setPadding(dp(12), dp(10), dp(12), dp(10));
        narrationText.setBackground(round(PALE_BLUE, 12)); narrationText.setVisibility(GONE);
        section.addView(narrationText);
        content.addView(section, spaced(8));
        setNarrationState(false, narrationUnavailable);
    }

    /** TTS lifecycle updates never rebuild the card or discard a user's draft. */
    public void setNarrationState(boolean speaking, boolean unavailable) {
        narrationSpeaking = speaking; narrationUnavailable = unavailable;
        if (narrationPlayback == null) return;
        if (narrationStatus != null) narrationStatus.setText(progressMessage != null ? "현재 진행"
                : unavailable ? "글로 안내할게요" : speaking ? "말하고 있어요" : "Click의 안내");
        narrationPlayback.setText(reviewReplay == narrationPlayback ? "" : speaking ? "음성 멈춤" : "다시 듣기");
        narrationPlayback.setContentDescription(speaking ? "음성 안내 멈추기" : "Click 음성 안내 다시 듣기");
        if (unavailable && narrationText != null) {
            narrationText.setVisibility(VISIBLE);
            if (narrationCaptions != null) narrationCaptions.setText("글 접기");
        }
    }
    public void progress(String goal, JSONArray checklist) {
        progress(goal, checklist, null);
    }
    public void progress(String goal, JSONArray checklist, String instruction) {
        progress(goal, checklist, instruction, true);
    }
    public void progress(String goal, JSONArray checklist, String instruction, boolean canRequestResult) {
        clear(null);
        progressMessage = instruction == null || instruction.trim().isEmpty() ? "작업을 진행하고 있어요." : instruction;
        narrationMessage = progressMessage;
        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        narrationStatus = text("현재 진행", 13); narrationStatus.setTextColor(BLUE_DARK);
        titleRow.addView(narrationStatus, new LayoutParams(0, -2, 1));
        narrationPlayback = quietButton("다시 듣기", () -> {
            if (narrationSpeaking) listener.stopGuidance();
            else listener.replayGuidance(narrationMessage);
        });
        narrationPlayback.setTextSize(13);
        narrationPlayback.setPadding(dp(8), dp(4), dp(8), dp(4));
        narrationPlayback.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 10));
        titleRow.addView(narrationPlayback, new LayoutParams(-2, -2));
        progressSummary.addView(titleRow, spaced(0));
        narrationText = text(progressMessage, 15);
        narrationText.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        progressSummary.addView(narrationText, spaced(2));
        if (goal != null && !goal.isEmpty()) {
            TextView request = text(goal, 13); request.setTextColor(SECONDARY);
            request.setMaxLines(1); request.setEllipsize(android.text.TextUtils.TruncateAt.END);
            progressSummary.addView(request, spaced(8));
        }
        progressSummary.setVisibility(VISIBLE);
        updateStatusBox();
        setNarrationState(false, narrationUnavailable);
        if (canRequestResult)
            content.addView(secondaryButton("결과 확인하기", () -> listener.control("request_result")), spaced(0));
    }
    public void result(JSONObject prompt) {
        clear("결과를 확인해 주세요");
        addNarration(prompt.optString("message"));
        content.addView(primaryButton("네, 잘 보여요", () -> listener.result("satisfied")), spaced(12));
        content.addView(secondaryButton("아직 불편해요", () -> listener.result("needs_help")), spaced(8));
        TextView note = text("답하지 않으면 완료로 기록하지 않아요.", 13);
        note.setTextColor(SECONDARY); note.setGravity(Gravity.CENTER); content.addView(note, spaced(6));
    }

    public void confirmation(String message, java.util.function.Consumer<Boolean> answer) {
        clear(null);
        addQuestionNarration(message);
        LinearLayout actions = new LinearLayout(getContext());
        actions.addView(secondaryButton("아니요", () -> answer.accept(false)), new LayoutParams(0, -2, 1));
        LayoutParams yes = new LayoutParams(0, -2, 1.4f); yes.leftMargin = dp(8);
        actions.addView(primaryButton("네, 계속할게요", () -> answer.accept(true)), yes);
        content.addView(actions, spaced(8));
    }

    public void fontSizeConfirmation(java.util.function.Consumer<String> answer) {
        clear(null);
        addQuestionNarration(FONT_SIZE_QUESTION);
        LinearLayout actions = new LinearLayout(getContext());
        actions.addView(secondaryButton("작게", () -> answer.accept("decrease")), new LayoutParams(0, -2, 1));
        LayoutParams larger = new LayoutParams(0, -2, 1); larger.leftMargin = dp(8);
        actions.addView(secondaryButton("크게", () -> answer.accept("increase")), larger);
        content.addView(actions, spaced(8));
    }

    /** Report the user outcome separately from internal execution evidence. */
    public void completed(String message, Runnable onExit) {
        clear("결과 확인을 마쳤어요");
        setLoadingStatus(null);
        headerInput.setVisibility(GONE);
        controls.setVisibility(GONE);
        divider.setVisibility(GONE);
        TextView completion = text(message == null || message.trim().isEmpty()
                ? "원하는 결과를 얻었다고 알려주셨어요." : message, 17);
        completion.setGravity(Gravity.CENTER);
        completion.setTextColor(BLUE);
        completion.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        completion.setPadding(dp(14), dp(14), dp(14), dp(14));
        completion.setBackground(round(PALE_BLUE, 18));
        content.addView(completion, spaced(10));
        announceForAccessibility(completion.getText());
        Button exit = primaryButton("클릭 종료하기", () -> {});
        exit.setOnClickListener(view -> {
            if (!exit.isEnabled()) return;
            exit.setEnabled(false);
            onExit.run();
        });
        recoveryActions.addView(exit, spaced(0));
        recoveryActions.setVisibility(VISIBLE);
    }
    public void followUp(JSONObject followUp) {
        clear("조금 더 도와드릴게요"); addNarration(followUp.optString("message"));
        JSONArray choices = followUp.optJSONArray("choices");
        if (choices != null) for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i); if (choice == null) continue;
            content.addView(secondaryButton(choice.optString("label"),
                    () -> listener.adjust(choice.optString("id"))), spaced(8));
        }
    }
    public void paused() {
        clear("작업을 잠시 멈췄어요"); content.addView(text("이어서 진행하거나 그만할 수 있어요.", 16));
        pause.setText("이어서 진행"); pause.setOnClickListener(v -> listener.control("resume_task"));
    }
    public void navigationRecovery(boolean back, String message, Runnable navigate) {
        clear("원래 작업으로 돌아가기");
        content.addView(text(message, 17), spaced(8));
        Button action = secondaryButton(back ? "뒤로가기" : "홈으로 이동", () -> {});
        action.setOnClickListener(view -> {
            if (!action.isEnabled()) return;
            action.setEnabled(false);
            navigate.run();
        });
        action.setContentDescription(back ? "뒤로가기. 직전 화면으로 돌아가기" : "홈으로 이동. 목표를 유지하고 처음부터 안내");
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(0xFFF1F6FF); outline.setCornerRadius(dp(16));
        outline.setStroke(dp(3), 0xFFE94467);
        action.setBackground(outline);
        recoveryActions.addView(action, spaced(10));
        recoveryActions.setVisibility(VISIBLE);
        content.addView(text("강조한 버튼을 누르거나 휴대폰의 내비게이션 버튼·제스처를 사용해 주세요.", 14), spaced(10));
    }

    public void waiting(String message) { clear(message); }
    public void waitingProgress(String message) {
        progress(null, null, message, false);
    }
public void observationRecovery(String message, Runnable retry) {
        observationRecovery(null, message, retry);
    }
    public void observationRecovery(String goal, String message, Runnable retry) {
        clear("화면을 함께 확인해요");
        if (goal != null && !goal.isEmpty()) addRequestBox(goal, null);
        content.addView(text(message, 16), spaced(0));
        content.addView(primaryButton("화면 다시 확인", retry), spaced(12));
        content.addView(secondaryButton("직접 이동하기", listener::collapse), spaced(8));
    }

    public void scrollRecovery(String goal, String message, Runnable retry, Runnable down, Runnable up) {
        observationRecovery(goal, message, retry);
        content.addView(primaryButton("아래 항목 찾기", down), spaced(8));
        content.addView(secondaryButton("위 항목 찾기", up), spaced(8));
    }

    /** One continuous outline keeps the curved tail seamless with the message card. */
    private final class CompactBubbleBackground extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path(), tail = new Path();
        private float anchorX = Float.NaN;
        private boolean above;

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float inset = dp(1) / 2f;
            float top = bounds.top + inset + (above ? dp(12) : 0);
            float bottom = bounds.bottom - inset - (above ? 0 : dp(12));
            float tipX = Float.isNaN(anchorX) ? bounds.right - dp(32) : anchorX;
            tipX = Math.max(bounds.left + dp(10), Math.min(bounds.right - dp(10), tipX));
            float baseX = Math.max(bounds.left + dp(28), Math.min(bounds.right - dp(28), tipX));
            float edge = above ? top : bottom;
            float tipY = above ? bounds.top + inset : bounds.bottom - inset;
            float bendY = edge + (above ? -dp(6) : dp(6));
            shape.reset();
            shape.addRoundRect(bounds.left + inset, top, bounds.right - inset, bottom,
                    dp(18), dp(18), Path.Direction.CW);
            tail.reset();
            tail.moveTo(baseX - dp(10), edge);
            tail.quadTo(baseX - dp(3), bendY, tipX, tipY);
            tail.quadTo(baseX + dp(5), bendY, baseX + dp(10), edge);
            tail.close();
            shape.op(tail, Path.Op.UNION);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
            canvas.drawPath(shape, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(BORDER);
            canvas.drawPath(shape, paint);
        }

        @Override public void getOutline(android.graphics.Outline outline) {
            android.graphics.Rect bounds = getBounds();
            outline.setRoundRect(bounds.left, bounds.top + (above ? dp(12) : 0),
                    bounds.right, bounds.bottom - (above ? 0 : dp(12)), dp(18));
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Fixed geometry avoids font-dependent checkmark baselines and optical left drift. */
    private final class StatusMark extends Drawable {
        private final boolean checked, current;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        StatusMark(boolean checked, boolean current) { this.checked = checked; this.current = current; }
        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setStyle(checked ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f);
            paint.setColor(checked || current ? BLUE : 0xFFB7C5D8);
            canvas.drawCircle(12, 12, 9.5f, paint);
            if (checked) {
                paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.1f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
                tick.reset(); tick.moveTo(7.5f, 12); tick.lineTo(10.5f, 15); tick.lineTo(16.5f, 9);
                canvas.drawPath(tick, paint);
            } else if (current) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12, 12, 3.5f, paint);
            }
            canvas.restore();
        }
        @Override public int getIntrinsicWidth() { return dp(24); }
        @Override public int getIntrinsicHeight() { return dp(24); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(getContext()); text.setText(value); text.setTextSize(size);
        text.setTextColor(INK); text.setPadding(0, dp(4), 0, dp(4));
        text.setIncludeFontPadding(false); text.setLineSpacing(dp(2), 1.05f);
        text.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        return text;
    }
    private Button quietButton(String label, Runnable action) {
        Button button = new Button(getContext()); button.setText(label); button.setTextSize(16);
        button.setTextColor(BLUE); button.setAllCaps(false); button.setMinHeight(dp(48));
        button.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        button.setMinimumHeight(dp(48)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(10), dp(10), dp(10), dp(10)); button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setBackground(ripple(0xFFEAF2FF, 0x223478F6, 16));
        button.setOnClickListener(v -> action.run()); return button;
    }
    private Button secondaryButton(String label, Runnable action) {
        Button button = quietButton(label, action);
        button.setBackground(rippleOutlined(Color.WHITE, BORDER, 0x183478F6, 17));
        return button;
    }
    private Button primaryButton(String label, Runnable action) {
        Button button = quietButton(label, action);
        button.setTextColor(Color.WHITE);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF376FF7, 0xFF4385FA});
        gradient.setCornerRadius(dp(18));
        GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(dp(18));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), gradient, mask));
        button.setMinHeight(dp(50));
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private void styleReviewButton(Button button, boolean primary) {
        button.setTextSize(14);
        button.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setTextColor(primary ? Color.WHITE : SECONDARY);
        button.setBackground(primary ? ripple(BLUE, 0x33FFFFFF, 14)
                : rippleOutlined(Color.WHITE, 0xFFE4EBF5, 0x183478F6, 14));
    }
    private LayoutParams spaced(int top) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private Drawable cardBackground(boolean checked) {
        return rippleOutlined(checked ? 0xFFF0F5FF : Color.WHITE,
                checked ? 0xFF95B8F6 : 0xFFE4EBF5, 0x183478F6, 14);
    }
    private GradientDrawable outline(int fill, int stroke, int radius) {
        return outline(fill, stroke, radius, 1);
    }
    private GradientDrawable outline(int fill, int stroke, int radius, int width) {
        GradientDrawable drawable = round(fill, radius); drawable.setStroke(dp(width), stroke); return drawable;
    }
    private Drawable ripple(int fill, int ripple, int radius) {
        GradientDrawable content = round(fill, radius);
        GradientDrawable mask = round(Color.WHITE, radius);
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
    }
    private Drawable rippleOutlined(int fill, int stroke, int ripple, int radius) {
        GradientDrawable content = outline(fill, stroke, radius);
        GradientDrawable mask = round(Color.WHITE, radius);
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
