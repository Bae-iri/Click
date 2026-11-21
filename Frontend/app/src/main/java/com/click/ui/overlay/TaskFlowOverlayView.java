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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import com.click.R;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import com.click.domain.goal.RequirementReview;
import com.click.ui.common.ClickUi;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUiSpeed;
import org.json.JSONArray;
import org.json.JSONObject;


/**
 * 내용 확인, 단계별 안내, 결과 확인을 보여주는 플로팅 안내창. Java 코드로 레이아웃을 구성한다.
 * 작은 플로팅 버튼/음성·글 입력 메뉴는 ClickOverlayLayout.java에서 수정한다.
 *
 * 수정 위치 안내 (파일에서 "[UI 수정]"을 검색):
 * - 색상: 아래 BLUE/INK/PALE_BLUE 등의 상수. ClickUi의 공통 색상과 별도로 정의되어 있다.
 * - 공통 헤더/구분선: FloatingPanelHeader.java. 창 너비: FloatingPanelStyle.PANEL_WIDTH_DP.
 * - 모드별 안쪽 여백: updateBubblePadding().
 * - 내용 확인 화면: reviewStage(), addRequestBox(), addQuestionNarration(), choiceCards().
 * - 진행/결과 화면: progress(), result(), completed().
 * - 줄인 안내 말풍선: setMinimizedProgress(), CompactBubbleBackground.
 * - 버튼 스타일: quietButton(), primaryButton(), secondaryButton(), styleReviewButton().
 * 창 위치와 화면별 호출 시점은 service/overlay/ClickOverlayService.java에서 관리한다.
 */
@SuppressLint("ViewConstructor")
public final class TaskFlowOverlayView extends LinearLayout {
    public static final String AUTOMATIC_PROGRESS_MESSAGE = "자동 안내 중";
    // [UI 수정] 이 안내창의 기본 팔레트. 각 메서드의 직접 지정 색상/ClickUi 호출도 함께 확인한다.
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
    private final Listener listener;
    private final TextView queuedRequests;
    private final LinearLayout content;
    private final LinearLayout expandedContent;
    private final Button pause;
    private final Button stop;
    private final Button reviewConfirm;
    private final LinearLayout reviewNavigation;
    private final Button headerReplay;
    private final View divider;
    private final LinearLayout controls;
    private final LinearLayout recoveryActions;
    private TextView narrationStatus, narrationText;
    private LinearLayout requestCaptionRow;
    private Button narrationCaptions, requestEditorToggle;
    private LinearLayout reviewEditor;
    private LinearLayout reviewSummaryCard, reviewSummarySection;
    private EditText reviewRequestInput;
    private RadioGroup reviewChoices;
    private String narrationMessage;
    private boolean narrationSpeaking, narrationUnavailable;
    private final TextView heading;
    private LinearLayout guidanceProgress;
    private android.widget.ProgressBar progressBar;
    private TextView progressCount;
    private View guidanceTarget;
    private final FloatingPanelHeader header;
    private final ScrollView scroll;
    private int maxHeight;
    private boolean publicGuideInput;
    public boolean needsPublicGuideInput(){return publicGuideInput;}
    private boolean resetScrollPending;
    private final ScrollView compactScroll;
    private final TextView compactMessage;
    private String progressMessage;
    private String compactSummary;
    private static final String HELP_EXPLANATION = "누를 곳을 찾지 못했어요. 화면을 확인한 뒤 ‘다시 시도하기’를 눌러주세요.";
    private String targetHelpInstruction;
    private boolean targetHelpOpened;
    private boolean minimizedProgress;
    private boolean automaticProgress;
    private boolean waitingForScreen;
    public boolean isPassiveProgress() { return waitingForScreen || automaticProgress; }
    public int presentationRevision() { return presentationRevision; }
    private boolean guidanceProgressKnown;
    private Button progressCompletion;
    private boolean reviewScreen;
    private int reviewStep;
    private String requestSummary;
    private String preparationMessage;
    private String guidanceEvidence=GuidanceExplanation.DEFAULT;
    private TextView evidenceView;
    private boolean detailedExplanations;
    public String guidanceEvidence(){return guidanceEvidence;}
    public void setGuidanceEvidence(String evidence){guidanceEvidence=evidence==null?GuidanceExplanation.DEFAULT:evidence;if(evidenceView!=null)evidenceView.setText(guidanceEvidence);}
    public void setDetailedExplanations(boolean enabled){
        detailedExplanations=enabled;
        if(enabled&&hasProgress()&&evidenceView==null){evidenceView=text(guidanceEvidence,14);content.addView(evidenceView,spaced(8));}
        if(evidenceView!=null)evidenceView.setVisibility(enabled?VISIBLE:GONE);
    }
    private final CompactBubbleBackground compactBackground = new CompactBubbleBackground();

    // [UI 수정] 공통 틀을 만드는 곳: 배경/모서리 → 헤더 → 스크롤 본문 → 하단 버튼.
    // 화면 전환 때 reviewStage()/updateBubblePadding() 등에서 일부 스타일을 다시 적용한다.
    public TaskFlowOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * .78f);
        setOrientation(VERTICAL);
        setPadding(dp(FloatingPanelStyle.HORIZONTAL_PADDING_DP), dp(FloatingPanelStyle.TOP_PADDING_DP),
                dp(FloatingPanelStyle.HORIZONTAL_PADDING_DP), dp(FloatingPanelStyle.BOTTOM_PADDING_DP));
        setBackground(FloatingPanelStyle.surface(getResources().getDisplayMetrics().density));
        setElevation(0);
        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(VERTICAL);
        compactMessage = text("", 16);
        compactMessage.setOnClickListener(view -> listener.expand());
        bubble.setOnClickListener(view -> listener.expand());
        bubble.addView(compactMessage);
        compactScroll = new ScrollView(context);
        compactScroll.setFillViewport(false);
        compactScroll.addView(bubble, new ScrollView.LayoutParams(-1, -2));
        compactScroll.setVisibility(GONE);
        addView(compactScroll, new LayoutParams(-1, -2));
        expandedContent = new LinearLayout(context);
        expandedContent.setOrientation(VERTICAL);
        addView(expandedContent, new LayoutParams(-1, -2));
        header = new FloatingPanelHeader(context, () -> {
            if (narrationSpeaking) listener.stopGuidance();
            else listener.replayGuidance(guidanceMessage());
        }, listener::collapse);
        headerReplay = header.replay;
        headerReplay.setId(R.id.task_flow_replay);
        header.edit.setId(R.id.task_flow_edit);
        header.collapse.setId(R.id.task_flow_collapse);
        expandedContent.addView(header, new LayoutParams(-1, -2));
        heading = header.title;
        queuedRequests = text("", 13);
        queuedRequests.setTextColor(SECONDARY);
        queuedRequests.setMaxLines(5);
        queuedRequests.setEllipsize(android.text.TextUtils.TruncateAt.END);
        queuedRequests.setVisibility(GONE);
        expandedContent.addView(queuedRequests, spaced(6));
        content = new LinearLayout(context); content.setOrientation(VERTICAL);
        content.setPadding(0, 0, 0, dp(4));
        scroll = new ScrollView(context); scroll.setFillViewport(false); scroll.addView(content);
        scroll.setVerticalScrollBarEnabled(true);
        content.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!resetScrollPending) return;
            resetScrollPending = false;
            scroll.scrollTo(0, 0);
        });
        expandedContent.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        reviewConfirm = primaryButton("안내 시작하기", () -> listener.confirmRequirements());
        reviewConfirm.setId(R.id.task_flow_begin);
        styleReviewButton(reviewConfirm, true);
        reviewConfirm.setVisibility(GONE);
        LayoutParams reviewParams = spaced(12); reviewParams.bottomMargin = dp(12);
        expandedContent.addView(reviewConfirm, reviewParams);
        reviewNavigation = new LinearLayout(context);
        reviewNavigation.setVisibility(GONE);
        expandedContent.addView(reviewNavigation, spaced(0));

        recoveryActions = new LinearLayout(context);
        recoveryActions.setOrientation(VERTICAL);
        recoveryActions.setVisibility(GONE);
        expandedContent.addView(recoveryActions, spaced(0));

        divider = new View(context);
        divider.setBackgroundColor(BORDER);
        LayoutParams dividerParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(8);
        dividerParams.bottomMargin = dp(8);
        expandedContent.addView(divider, dividerParams);

        controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER);
        pause = FloatingPanelStyle.compactButton(context, "일시정지", () -> listener.control("pause_task"));
        LayoutParams pauseParams = new LayoutParams(0, -2, 1f);
        pauseParams.rightMargin = dp(6);
        controls.addView(pause, pauseParams);
        stop = FloatingPanelStyle.compactButton(context, "끝내기", () -> listener.control("cancel_task"));
        stop.setContentDescription("진행 중인 안내 끝내기");
        stop.setTextColor(0xFF536885);
        LayoutParams stopParams = new LayoutParams(0, -2, 1f);
        stopParams.leftMargin = dp(6);
        controls.addView(stop, stopParams);
        expandedContent.addView(controls);
    }

    // Background status callbacks must never add a banner to a floating guide.
    public void setAutomationStatus(String message) { }
    public void setLoadingStatus(String message) { }

    public void setAutomaticProgress(boolean automatic) {
        automaticProgress = automatic && hasProgress();
        if (hasProgress()) {
            narrationText.setAccessibilityLiveRegion(isPassiveProgress() ? ACCESSIBILITY_LIVE_REGION_NONE : ACCESSIBILITY_LIVE_REGION_POLITE);
            narrationText.setText(automaticProgress ? AUTOMATIC_PROGRESS_MESSAGE : progressMessage);
            if (guidanceTarget != null) guidanceTarget.setVisibility(automaticProgress ? GONE : VISIBLE);
            if (guidanceProgress != null) guidanceProgress.setVisibility(!automaticProgress && guidanceProgressKnown ? VISIBLE : GONE);
            if (progressCompletion != null) progressCompletion.setVisibility(automaticProgress ? GONE : VISIBLE);
            headerReplay.setVisibility(automaticProgress ? INVISIBLE : VISIBLE);
        }
        if (minimizedProgress) {
            compactMessage.setText(compactGuidance());
            compactMessage.setContentDescription(compactGuidance() + " 눌러서 안내 펼치기");
        }
    }

    private String compactGuidance() {
        if (compactSummary != null) return compactSummary;
        return automaticProgress ? AUTOMATIC_PROGRESS_MESSAGE : guidanceMessage();
    }

    // 화면 폭에 맞추되 큰 글자에서도 선택 카드가 충분한 너비를 갖도록 한다.
    public int getWindowWidth(int screenWidth) { return Math.min(screenWidth - dp(24), dp(minimizedProgress ? 260 : FloatingPanelStyle.PANEL_WIDTH_DP)); }
    public void setAvailableHeight(int height) { if (maxHeight != height) { maxHeight = height; requestLayout(); } }
    public boolean hasProgress() { return progressMessage != null; }
    public String guidanceMessage() {
        if (targetHelpInstruction != null) return targetHelpInstruction;
        if (automaticProgress) return AUTOMATIC_PROGRESS_MESSAGE;
        return narrationMessage != null ? narrationMessage : progressMessage != null ? progressMessage
                : heading.getText().toString();
    }
    public boolean hasCompactContent() { return !guidanceMessage().trim().isEmpty(); }
    public boolean isMinimizedProgress() { return minimizedProgress; }
    public boolean isTargetHelp() { return targetHelpInstruction != null; }
    public boolean hasOpenedTargetHelp() { return isTargetHelp() && targetHelpOpened; }
    private void setNarrationMessage(String message) {
        narrationMessage = message;
        refreshCompactMessage();
    }
    private void refreshCompactMessage() {
        if (!minimizedProgress) return;
        String message = compactGuidance();
        if (!message.contentEquals(compactMessage.getText())) compactMessage.setText(message);
        compactMessage.setContentDescription(message + " 눌러서 안내 펼치기");
    }
    // [UI 수정] 줄이기 상태의 표시 항목/배경. 말풍선 꼬리 모양은 CompactBubbleBackground에서 그린다.
    public void setMinimizedProgress(boolean minimized) {
        minimized = minimized && hasCompactContent();
        refreshCompactMessage();
        if (minimizedProgress == minimized) return;
        if (!minimized && isTargetHelp()) targetHelpOpened = true;
        minimizedProgress = minimized;
        compactMessage.setText(minimized ? compactGuidance() : "");
        compactMessage.setContentDescription(minimized ? compactGuidance() + " 눌러서 안내 펼치기" : null);
        compactScroll.setVisibility(minimized ? VISIBLE : GONE);
        if (minimized) ClickUiSpeed.reveal(compactMessage);
        else ClickUiSpeed.finishReveal(compactMessage);
        // Render new steps behind one hidden parent. Updating a child must never
        // reopen a minimized panel or restore the previous step's controls.
        expandedContent.setVisibility(minimized ? GONE : VISIBLE);
        updateBubblePadding();
        setBackground(minimized ? compactBackground : FloatingPanelStyle.surface(getResources().getDisplayMetrics().density));
        requestLayout();
    }
    public void setRequestSummary(String request) {
        if (request != null && !request.trim().isEmpty() && !"선택한 작업".equals(request)) requestSummary = request;
    }
    public String phaseLabel() {
        return reviewScreen ? (reviewStep == 1 ? "상세정보 확인" : "내용 확인")
                : hasProgress() ? "안내 중" : heading.getText().toString();
    }
    // Only execution has a counter. Unknown totals must never imply a made-up plan.
    public void setGuidanceProgress(int step, int total) {
        if (!hasProgress() || guidanceProgress == null) return;
        boolean known = total > 0 && step >= 0 && step <= total;
        guidanceProgressKnown = known;
        guidanceProgress.setVisibility(known && !automaticProgress ? VISIBLE : GONE);
        if (!known) return;
        progressBar.setMax(total); progressBar.setProgress(step);
        progressCount.setText(step + "/" + total);
        guidanceProgress.setContentDescription("전체 " + total + "단계 중 " + step + "단계");
    }

    /** The target comes from the current observed screen, never from guessed instructions. */
    public void setGuidanceTarget(String label) {
        if (!hasProgress()) return;
        if (guidanceTarget != null) content.removeView(guidanceTarget);
        guidanceTarget = null;
        if (label == null || label.trim().isEmpty()) return;
        String target = label.trim();
        setGuidanceEvidence("안내를 만들 때 읽은 화면의 ‘"+target.substring(0,Math.min(60,target.length()))+"’ 항목을 대상으로 사용했어요. 작업 완료 여부는 따로 확인해요.");
        if (target.equals("설정") || target.equals("설정 앱") || target.startsWith("설정,")
                || target.equalsIgnoreCase("settings") || target.equalsIgnoreCase("settings app")
                || target.toLowerCase(java.util.Locale.ROOT).startsWith("settings,")) return;
        LinearLayout section = new LinearLayout(getContext()); section.setOrientation(VERTICAL);
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(60)); row.setBackground(rippleOutlined(Color.WHITE, BORDER, 0x183478F6, 16));
        row.setElevation(0);
        android.widget.ImageView icon = new android.widget.ImageView(getContext());
        icon.setImageResource(target.contains("디스플레이") || target.contains("화면") ? R.drawable.ic_flow_display
                : R.drawable.ic_flow_document);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setBackground(FloatingPanelStyle.summary(getResources().getDisplayMetrics().density));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LayoutParams(dp(40), dp(40)));
        TextView name = text(target, 17);
        name.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        LayoutParams copy = new LayoutParams(0, -2, 1); copy.leftMargin = dp(10);
        row.addView(name, copy);
        row.setContentDescription(target);
        section.addView(row, spaced(10));
        LayoutParams params = spaced(0); params.bottomMargin = dp(8);
        content.addView(section, Math.min(2, content.getChildCount()), params);
        guidanceTarget = section;
        section.setVisibility(automaticProgress ? GONE : VISIBLE);
    }
    public void offlineResult(String message,Runnable reconcile) {
        clear("기기에 보관한 진행 기록");addQuestionNarration(message);
        content.addView(primaryButton("연결 후 기록 확인",reconcile),spaced(12));
        setAutomaticProgress(false);
    }
    public void disruptiveTaskNotice(boolean visible) {
        if(visible)content.addView(text("이 작업으로 인터넷 연결이나 화면 안내가 끊길 수 있어요. 진행 기록은 기기에 보관하고, 연결 후 결과를 다시 확인해요.",14),spaced(12));
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
    // [UI 수정] 줄인 말풍선/내용 확인/일반 안내마다 다른 내부 여백을 적용한다.
    private void updateBubblePadding() {
        if (minimizedProgress) setPadding(dp(14), dp(compactBackground.above ? 22 : 10), dp(14), dp(compactBackground.above ? 10 : 22));
        else setPadding(dp(FloatingPanelStyle.HORIZONTAL_PADDING_DP), dp(FloatingPanelStyle.TOP_PADDING_DP),
                dp(FloatingPanelStyle.HORIZONTAL_PADDING_DP), dp(FloatingPanelStyle.BOTTOM_PADDING_DP));
    }
    @Override protected void onMeasure(int width, int height) {
        refreshCompactMessage();
        int available = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED ? maxHeight
                : Math.min(maxHeight, MeasureSpec.getSize(height));
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
    }
    private void clear(String title) {
        presentationRevision++;
        guidanceEvidence=GuidanceExplanation.DEFAULT;
        evidenceView=null;
        publicGuideInput=false;
        compactSummary = null;
        targetHelpInstruction = null;
        targetHelpOpened = false;
        reviewScreen = false;
        reviewStep = 0;
        updateBubblePadding();
        reviewChoices = null; reviewEditor = null; reviewRequestInput = null; requestEditorToggle = null;
        reviewSummaryCard = reviewSummarySection = null;
        automaticProgress = false;
        waitingForScreen = false;
        guidanceProgressKnown = false;
        progressCompletion = null;
        progressMessage = null;
        guidanceProgress = null; progressBar = null; progressCount = null; guidanceTarget = null;
        content.removeAllViews();
        content.setPadding(0, 0, 0, dp(4));
        reviewConfirm.setVisibility(GONE);
        reviewNavigation.removeAllViews(); reviewNavigation.setVisibility(GONE);
        recoveryActions.removeAllViews();
        recoveryActions.setVisibility(GONE);
        headerReplay.setVisibility(VISIBLE);
        header.collapse.setVisibility(VISIBLE);
        header.edit.setText("내용 수정");
        header.edit.setContentDescription("내용 수정");
        header.edit.setVisibility(GONE);
        header.edit.setOnClickListener(null);
        divider.setVisibility(VISIBLE);
        controls.setVisibility(VISIBLE);
        narrationStatus = narrationText = null;
        narrationCaptions = null;
        narrationMessage = null; narrationSpeaking = false; requestCaptionRow = null;
        resetScrollPending = true;
        scroll.scrollTo(0, 0);
        scroll.post(() -> scroll.scrollTo(0, 0));
        heading.setText(title);
        FloatingPanelStyle.styleTitle(heading);
        setNarrationState(false, narrationUnavailable);
        pause.setText("일시정지"); pause.setOnClickListener(v -> listener.control("pause_task"));
        stop.setText("끝내기"); stop.setOnClickListener(v -> listener.control("cancel_task"));
        ClickUiSpeed.reveal(content);
        refreshCompactMessage();
    }

    @Override protected void onDetachedFromWindow() {
        presentationRevision++;
        ClickUiSpeed.finishReveal(content);
        ClickUiSpeed.finishReveal(compactMessage);
        super.onDetachedFromWindow();
    }

    private void addFailureClue() {
        addFailureClue(HELP_EXPLANATION);
    }

    private void addFailureClue(String explanation) {
        ImageView clue = new com.click.designpreview.ClueArtworkView(getContext());
        clue.setImageResource(com.click.designpreview.R.drawable.clue5_failure);
        clue.setTag("failure_clue");
        content.addView(clue, new LayoutParams(-1, dp(128)));
        TextView apology = text(explanation, 16);
        apology.setGravity(Gravity.CENTER);
        content.addView(apology, spaced(4));
    }

    /** Queue bookkeeping is kept out of the floating UI, including legacy callers. */
    public void setQueuedRequests(java.util.List<String> requests) {}

    public void setPreparationMessage(String message){preparationMessage=message;}

    /** Sensitive input stays local; the requested task can continue on the device. */
    public void privateSettingsStep(String message) {
        clear("설정을 직접 이어가 주세요");
        setNarrationMessage(message);
        controls.setVisibility(GONE);
        divider.setVisibility(GONE);
        content.addView(text(message, 16), spaced(0));
        recoveryActions.addView(primaryButton("설정에서 직접 진행하기", () -> listener.collapse()), spaced(0));
        recoveryActions.addView(quietButton("안내 끝내기", () -> listener.control("cancel_task")), spaced(8));
        recoveryActions.setVisibility(VISIBLE);
    }

    /** Consent denial is a normal choice: no failure artwork, apology or retry. */
    public void userDeclined(String message, Runnable end) {
        clear("안내를 마칠게요");
        setNarrationMessage(message);
        controls.setVisibility(GONE); divider.setVisibility(GONE);
        content.addView(text(message,16),spaced(0));
        Button finish=primaryButton("끝내기",end);
        finish.setTag("task_declined_end");
        finish.setOnClickListener(v->{if(!finish.isEnabled())return;finish.setEnabled(false);end.run();});
        recoveryActions.addView(finish,spaced(0)); recoveryActions.setVisibility(VISIBLE);
    }

    public void failed(String message) {
        clear(getResources().getConfiguration().fontScale >= 1.4f
                ? "작업을 마치지\n못했어요" : "작업을 마치지 못했어요");
        setNarrationMessage(message);
        setGuidanceEvidence(message);
        controls.setVisibility(GONE);
        divider.setVisibility(GONE);
        recoveryActions.addView(primaryButton("끝내기", () -> listener.control("cancel_task")), spaced(0));
        recoveryActions.setVisibility(VISIBLE);
        content.addView(text(message, 15), spaced(12));
    }

    /** A transport or contract failure is not a request for the user to rephrase. */
    public void resolutionUnavailable(String request, String message, Runnable retry) {
        clear("내용을 다시 확인할게요");
        setNarrationMessage(message);
        divider.setVisibility(GONE);
        controls.setVisibility(GONE);
        recoveryActions.setVisibility(VISIBLE);
        addRequestBox(request, null);
        content.addView(text(message, 16), spaced(12));
        recoveryActions.addView(primaryButton("다시 확인", retry), spaced(0));
        recoveryActions.addView(quietButton("끝내기", () -> listener.control("cancel_task")), spaced(8));
    }

    /** An unresolved request has no selectable goal or running task to pause. */
    public void unresolvedGoal(String request) {
        clear("조금 더 여쭤볼게요");
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
        TextView label = text("입력한 내용", 14);
        label.setTextColor(SECONDARY);
        editor.addView(label);
        EditText input = new EditText(getContext());
        input.setTextSize(ClickTextScale.size(getContext(), FloatingPanelStyle.INPUT_SIZE_SP)); input.setTextColor(INK); input.setHintTextColor(SECONDARY);
        input.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        input.setContentDescription("입력한 내용 수정");
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
            heading.setText("내용을 수정해 주세요");
            for (int i = 1; i < content.getChildCount(); i++) content.getChildAt(i).setVisibility(GONE);
            editor.setVisibility(VISIBLE);
            retry.setText("이 내용으로 시작하기");
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
        Button cancel = quietButton("끝내기", () -> {
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

    // [UI 수정] 내용 확인 단계에서 공통 헤더/버튼의 표시와 제목을 재설정하는 곳.
    private void reviewStage(int step, String title, String description) {
        clear(title);
        reviewScreen = true;
        reviewStep = step;
        updateBubblePadding();
        // 내용 확인 중에도 일시정지/중지를 본문 스크롤 밖의 하단에 유지한다.
        divider.setVisibility(VISIBLE); controls.setVisibility(VISIBLE);
        TextView subtitle = text(description, 15); subtitle.setTextColor(SECONDARY);
        subtitle.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);
        subtitle.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
        subtitle.setPadding(0, 0, 0, 0);
        if (description != null && !description.isEmpty()) content.addView(subtitle, spaced(8));
        content.setPadding(0, 0, 0, dp(step == 2 ? 0 : 6));
    }

    private Button reviewNext(Runnable next) {
        reviewNavigation.removeAllViews(); reviewNavigation.setOrientation(HORIZONTAL);
        reviewNavigation.setVisibility(VISIBLE);
        Button forward = primaryButton("입력하기", next);
        styleReviewButton(forward, true);
        LayoutParams params = new LayoutParams(0, -2, 1); params.topMargin = dp(8);
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

    private void readyActions() {
        reviewConfirm.setText("안내 시작하기");
        reviewConfirm.setVisibility(VISIBLE); reviewConfirm.setEnabled(true); reviewConfirm.setAlpha(1f);
    }

    /** The same request card and primary action, when the caller already owns the request. */
    public void requestConfirmation(String request, String editLabel, Runnable edit) {
        reviewStage(2, "", "");
        setNarrationMessage(request);
        reviewSummary(request);
        readyActions();
        headerReplay.setVisibility(GONE);
        header.edit.setText(editLabel);
        header.edit.setContentDescription(editLabel);
        header.edit.setVisibility(VISIBLE);
        header.edit.setOnClickListener(view -> edit.run());
    }

    /** Embedded guidance can leave navigation to its host, preserving the real content/actions. */
    public void hideAuxiliaryControls() {
        controls.setVisibility(GONE);
        divider.setVisibility(GONE);
        header.collapse.setVisibility(GONE);
        headerReplay.setVisibility(GONE);
    }

    // [UI 수정] 선택지 카드의 배치/선택 표시. 선택 결과 전달은 selected 콜백이 담당한다.
    private RadioGroup choiceCards(JSONArray choices, java.util.function.Consumer<JSONObject> selected) {
        RadioGroup group = new RadioGroup(getContext()); group.setOrientation(VERTICAL);
        reviewChoices = group;
        if (choices != null) for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i); if (choice == null) continue;
            RadioButton card = new RadioButton(getContext()); card.setId(View.generateViewId());
            String label = choice.optString("label");
            if ("휴대폰 설정에서 기본 글자 크기 변경".equals(label)) label = "휴대폰 전체";
            else if ("특정 앱 안에서만 변경".equals(label)) label = "특정 앱 안에서만";
            card.setText(label); card.setTextSize(ClickTextScale.size(getContext(), FloatingPanelStyle.ACTION_SIZE_SP)); card.setTextColor(INK);
            card.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
            card.setButtonDrawable((Drawable) null); card.setIncludeFontPadding(false);
            card.setGravity(Gravity.CENTER_VERTICAL); card.setMinHeight(dp(48));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            StateListDrawable mark = new StateListDrawable();
            mark.addState(new int[]{android.R.attr.state_checked}, new StatusMark(true, false));
            mark.addState(new int[]{}, new StatusMark(false, false));
            mark.setBounds(0, 0, dp(20), dp(20));
            card.setCompoundDrawablesRelative(mark, null, null, null); card.setCompoundDrawablePadding(dp(10));
            card.setBackground(cardBackground(false));
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(-1, -2); params.topMargin = dp(i == 0 ? 0 : 8);
            group.addView(card, params);
            card.setOnCheckedChangeListener((view, checked) -> {
                card.setBackground(cardBackground(checked));
                card.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
                if (checked) { closeRequestEditor(); selected.accept(choice); listener.onReviewChoiceSelected(); }
            });
        }
        return group;
    }

    private void requestEditor(String request, Runnable reset) {
        LinearLayout editor = new LinearLayout(getContext()); editor.setOrientation(VERTICAL);
        reviewEditor = editor;
        editor.setVisibility(GONE);
        EditText input = new EditText(getContext()); input.setTextSize(ClickTextScale.size(getContext(), FloatingPanelStyle.INPUT_SIZE_SP)); input.setTextColor(INK);
        reviewRequestInput = input;
        input.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
        input.setContentDescription("입력한 내용 수정"); input.setHint("원하시는 내용을 적어 주세요");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2); input.setMaxLines(4); input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(12), dp(12), dp(12)); input.setBackground(outline(Color.WHITE, BORDER, 14));
        input.setText(request);
        input.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout field = new LinearLayout(getContext());
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(outline(Color.WHITE, BORDER, 14));
        field.addView(input, new LayoutParams(0, -2, 1));
        ImageButton microphone = new ImageButton(getContext());
        microphone.setImageResource(com.click.designpreview.R.drawable.icon_mic);
        microphone.setScaleType(ImageView.ScaleType.FIT_CENTER);
        microphone.setTag("request_edit_microphone");
        microphone.setContentDescription("말로 내용 수정");
        microphone.setFocusable(true);
        microphone.setPadding(dp(10), dp(10), dp(10), dp(10));
        microphone.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 14));
        microphone.setOnClickListener(view -> {
            hideKeyboard(input); listener.stopGuidance(); listener.openInput();
        });
        field.addView(microphone, new LayoutParams(dp(48), dp(48)));
        editor.addView(field, spaced(0));
        Button apply = primaryButton("수정한 내용 확인", () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            hideKeyboard(input); listener.rephrase(value);
        });
        styleReviewButton(apply, true);
        editor.addView(apply, spaced(8));
        if (reset != null) editor.addView(quietButton("답변을 다시 선택", reset), spaced(6));
        boolean confirmation = reviewStep == 2;
        Runnable openEditor = () -> {
            listener.stopGuidance(); editor.setVisibility(VISIBLE); reviewConfirm.setVisibility(GONE);
            reviewNavigation.setVisibility(GONE);
            if (reviewChoices != null) reviewChoices.clearCheck();
            if (!confirmation) styleInputChoice(true);
            input.requestFocus(); input.setSelection(input.length());
            editor.post(() -> {
                if (reviewEditor != editor || !editor.isAttachedToWindow()) return;
                android.graphics.Rect bounds = new android.graphics.Rect();
                editor.getDrawingRect(bounds);
                content.offsetDescendantRectToMyCoords(editor, bounds);
                scroll.smoothScrollTo(0, bounds.top);
            });
            showKeyboardWhenFocused(input);
        };
        if (confirmation) {
            headerReplay.setVisibility(GONE);
            header.edit.setVisibility(VISIBLE);
            header.edit.setOnClickListener(view -> {
                header.edit.setVisibility(GONE);
                openEditor.run();
            });
            reviewSummaryCard.addView(editor, spaced(8));
        } else {
            Button edit = quietButton("직접 입력하기", openEditor);
            requestEditorToggle = edit;
            styleInputChoice(false);
            content.addView(edit, spaced(10));
            content.addView(editor, spaced(8));
        }
    }

    /** Binds this recording to this editor; closing/replacing it discards a late transcript. */
    public java.util.function.Function<String, Boolean> requestEditSpeechTarget() {
        final LinearLayout editor = reviewEditor;
        final EditText input = reviewRequestInput;
        if (!reviewScreen || editor == null || input == null || editor.getVisibility() != VISIBLE) return null;
        return value -> {
            if (reviewEditor != editor || reviewRequestInput != input || editor.getVisibility() != VISIBLE
                    || value == null || value.trim().isEmpty()) return false;
            input.setText(value.trim()); input.setSelection(input.length());
            return true;
        };
    }

    private void styleInputChoice(boolean selected) {
        if (requestEditorToggle == null) return;
        requestEditorToggle.setTextSize(ClickTextScale.size(getContext(), FloatingPanelStyle.ACTION_SIZE_SP)); requestEditorToggle.setTextColor(INK);
        requestEditorToggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        requestEditorToggle.setMinHeight(dp(48)); requestEditorToggle.setMinimumHeight(dp(48));
        requestEditorToggle.setPadding(dp(12), dp(10), dp(12), dp(10));
        Drawable mark = new StatusMark(selected, false); mark.setBounds(0, 0, dp(20), dp(20));
        requestEditorToggle.setCompoundDrawablesRelative(mark, null, null, null);
        requestEditorToggle.setCompoundDrawablePadding(dp(10));
        requestEditorToggle.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        requestEditorToggle.setBackground(cardBackground(selected));
    }

    private void closeRequestEditor() {
        if (reviewEditor == null || reviewEditor.getVisibility() != VISIBLE) return;
        hideKeyboard(reviewRequestInput);
        reviewEditor.setVisibility(GONE);
        reviewNavigation.setVisibility(VISIBLE);
        styleInputChoice(false);
    }

    private void reviewSummary(String message) {
        reviewSummary(message, false);
    }

    private void reviewSummary(String message, boolean singleLine) {
        LinearLayout section = new LinearLayout(getContext());
        reviewSummarySection = section;
        section.setTag("review_summary_section");
        section.setOrientation(VERTICAL);
        LinearLayout card = new RequestSummaryCard(getContext(), message, singleLine);
        reviewSummaryCard = card;
        section.addView(card, new LayoutParams(-1, -2));
        // The header already supplies the divider's lower gap. Do not add a
        // second gap when confirmation has no separate title.
        LayoutParams params = spaced(reviewStep == 2 && heading.length() == 0 ? 0 : 14);
        params.bottomMargin = 0;
        content.addView(section, params);
    }

    private String preparedSummary(com.click.domain.goal.GoalPreparationState state) {
        if (state.publicGuide()) return state.guidance();
        if (state.browsing()) return state.message;
        String type = state.goal == null ? "" : state.goal.optString("goal_type");
        boolean increase = type.endsWith(".increase");
        if (type.startsWith("settings.font_size."))
            return "휴대폰 글자를 " + (increase ? "더 크게" : "더 작게") + " 바꿔요.";
        if (type.startsWith("settings.brightness."))
            return "화면 밝기를 " + (increase ? "높여요." : "낮춰요.");
        if (type.startsWith("settings.volume."))
            return "미디어 소리를 " + (increase ? "키워요." : "줄여요.");
        return state.message == null || state.message.trim().isEmpty() ? state.data.optString("source_text") : state.message;
    }

    public void goals(String message, JSONArray candidates) {
        reviewStage(1, "어떤 작업을 도와드릴까요?", "원하시는 작업을 선택해 주세요.");
        setNarrationMessage(message);
        final JSONObject[] selected = {null};
        Button next = reviewNext(() -> {
            if (selected[0] != null) listener.chooseGoal(selected[0]);
        });
        content.addView(choiceCards(candidates, choice -> {
            selected[0] = choice.optJSONObject("goal");
            next.setEnabled(selected[0] != null); next.setAlpha(1f);
        }), spaced(12));
        requestEditor("", null);
    }

    public void preparation(com.click.domain.goal.GoalPreparationState state) {
        if (state.ready()) {
            reviewStage(2, "", "");
            setNarrationMessage(preparationMessage==null?state.guidance():preparationMessage);
            reviewSummary(preparationMessage==null?preparedSummary(state):preparationMessage);
            requestEditor(state.data.optString("source_text"), listener::resetGoalInputs);
            readyActions();
            return;
        }
        JSONObject question = state.question();
        reviewStage(1, question == null ? ("unsupported".equals(state.data.optString("status"))
                        ? "아직 지원하지 않아요" : "조금 더 확인할게요") : state.guidance(),
                question != null && "choice".equals(question.optString("kind"))
                        ? "필요한 정보를 먼저 확인할게요." : "");
        setNarrationMessage(state.guidance());
        if (state.needsInput()) {
            if ("choice".equals(question.optString("kind"))) {
                final String[] answer = {null};
                Button next = reviewNext(() -> {
                    if (answer[0] != null) listener.answerGoalInput(answer[0]);
                });
                content.addView(choiceCards(question.optJSONArray("choices"), choice -> {
                    answer[0] = choice.optString("value"); next.setEnabled(true); next.setAlpha(1f);
                }), spaced(12));
            } else {
                EditText input = new EditText(getContext()); input.setTextSize(ClickTextScale.size(getContext(), FloatingPanelStyle.INPUT_SIZE_SP));
                input.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
                input.setHint(question.optString("placeholder", "답변을 입력해 주세요"));
                input.setContentDescription("확인할 내용 입력");
                input.setTextColor(INK); input.setHintTextColor(SECONDARY);
                input.setSingleLine(true); input.setMinHeight(dp(48));
                input.setPadding(0, dp(8), dp(4), dp(8));
                input.setBackgroundColor(Color.TRANSPARENT);
                input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(question.optInt("max_length", 100))});
                LinearLayout field = new LinearLayout(getContext());
                field.setTag("goal_input_field");
                field.setGravity(Gravity.CENTER_VERTICAL);
                field.setPadding(dp(12), 0, dp(4), 0);
                field.setBackground(outline(Color.WHITE, BORDER, 14));
                field.addView(input, new LayoutParams(0, -2, 1));
                ImageButton microphone = new ImageButton(getContext());
                microphone.setImageResource(com.click.designpreview.R.drawable.icon_mic);
                microphone.setScaleType(ImageView.ScaleType.FIT_CENTER);
                microphone.setTag("goal_input_microphone");
                microphone.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
                microphone.setContentDescription("말로 입력");
                microphone.setFocusable(true);
                microphone.setPadding(dp(7), dp(7), dp(7), dp(7));
                microphone.setStateListAnimator(null);
                microphone.setBackground(new RippleDrawable(ColorStateList.valueOf(0x183478F6),
                        null, round(Color.WHITE, 14)));
                microphone.setOnClickListener(view -> {
                    hideKeyboard(input); listener.stopGuidance(); listener.openInput();
                });
                field.addView(microphone, new LayoutParams(dp(48), dp(48)));
                content.addView(field, spaced(10));
                content.setPadding(0, 0, 0, dp(4));
                Button next = reviewNext(() -> {
                    String answer = input.getText().toString().trim();
                    if (!answer.isEmpty()) { hideKeyboard(input); listener.answerGoalInput(answer); }
                });
                ((LayoutParams) next.getLayoutParams()).topMargin = dp(6);
                input.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
                    public void onTextChanged(CharSequence value, int start, int before, int count) {
                        next.setEnabled(!value.toString().trim().isEmpty()); next.setAlpha(1f);
                    }
                    public void afterTextChanged(android.text.Editable value) {}
                });
                // Text clarification already owns an editor and its submit action.
                return;
            }
        } else {
            TextView help = text(state.guidance(), 15); help.setTextColor(SECONDARY);
            content.addView(help, spaced(14));
        }
        requestEditor(state.data.optString("source_text"), null);
    }

    public void appGuidance(String message, Runnable refresh, Runnable finish) {
        progress(null, null, message, false);
        controls.setVisibility(GONE); divider.setVisibility(GONE);
        recoveryActions.addView(primaryButton("현재 화면 확인", refresh), spaced(0));
        recoveryActions.addView(secondaryButton("안내 끝내기", finish), spaced(8));
        recoveryActions.setVisibility(VISIBLE);
    }

    public void queueEndRetry(Runnable retry){
        clear("종료하지 못했어요");
        setGuidanceEvidence("안내 종료 결과를 확인하지 못했어요.");
        recoveryActions.addView(primaryButton("다시 시도",retry),spaced(8));
        recoveryActions.setVisibility(VISIBLE);
    }

    public static boolean appTaskWaitsForScreen(JSONObject result) {
        return result.optBoolean("verifying")
                || java.util.Set.of("planning","verifying","checking_result").contains(result.optString("status"));
    }

    public void appTask(JSONObject result,java.util.function.Consumer<String> execute,Runnable refresh,Runnable tools,Runnable visual,Runnable finish) {
        appTask(result,execute,refresh,tools,visual,finish,null);
    }
    public void appTask(JSONObject result,java.util.function.Consumer<String> execute,Runnable refresh,Runnable tools,Runnable visual,Runnable finish,Runnable confirmed) {
        String state=result.optString("status");
        progress(null,null,result.optString("message"),false);
        final int revision=presentationRevision;
        Runnable refreshCurrent=()->{if(revision==presentationRevision)refresh.run();};
        Runnable toolsCurrent=()->{if(revision==presentationRevision)tools.run();};
        Runnable visualCurrent=()->{if(revision==presentationRevision)visual.run();};
        Runnable finishCurrent=()->{if(revision==presentationRevision)finish.run();};
        stop.setText("안내 끝내기");stop.setOnClickListener(view->finishCurrent.run());
        // Keep actions inside the scrollable body so large text cannot push stop off-screen.
        LinearLayout actions=new LinearLayout(getContext());actions.setOrientation(VERTICAL);content.addView(actions,spaced(8));
        if(appTaskWaitsForScreen(result)){
            markWaitingForScreen();
            return;
        }
        if("protected".equals(state)){
            setGuidanceEvidence("비밀번호와 인증번호는 직접 입력해 주세요.");
            actions.addView(primaryButton("입력을 마쳤어요",refreshCurrent),spaced(0));return;
        }
        if("completed".equals(state)){
            controls.setVisibility(GONE);divider.setVisibility(GONE);
            actions.addView(primaryButton("안내 닫기",finishCurrent),spaced(0));return;
        }
        JSONArray available=result.optJSONArray("available_tools");
        boolean hasTools=available!=null&&available.length()>0;
        if("confirm_result".equals(state)){
            controls.setVisibility(GONE);divider.setVisibility(GONE);
            if(confirmed!=null)actions.addView(primaryButton("네, 됐어요",()->{if(revision==presentationRevision)confirmed.run();}),spaced(0));
            actions.addView(secondaryButton("아직 안 됐어요",hasTools?toolsCurrent:refreshCurrent),spaced(8));
            actions.addView(quietButton("안내만 끝내기",finishCurrent),spaced(8));return;
        }
        if("unavailable".equals(state))setGuidanceEvidence(result.optString("message"));
        JSONObject action="proposed".equals(state)?result.optJSONObject("action"):null;
        if(action!=null){
            String tool=action.optString("tool"),label=result.optString("target_label","");
            if(!label.isBlank())setGuidanceEvidence("open_app".equals(tool)?"요청하신 ‘"+label+"’ 앱이 설치되어 있어요.":"현재 화면에서 ‘"+label+"’ 항목을 찾았어요.");
            if(result.optBoolean("execute_allowed")){
                if("set_text".equals(tool)){
                    publicGuideInput=true;EditText input=new EditText(getContext());input.setHint("입력할 검색어를 적어 주세요");input.setSingleLine(true);input.setTextSize(ClickTextScale.size(getContext(),16));input.setMinHeight(dp(48));
                    input.setContentDescription("검색창에 넣을 검색어");
                    input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});input.setText(result.optString("suggested_query"));actions.addView(input,spaced(0));
                    actions.addView(text("검색어를 넣은 뒤, 키보드에서 검색을 눌러주세요.",14),spaced(4));
                    Button enter=primaryButton("검색창에 입력",()->{});
                    enter.setEnabled(!input.getText().toString().trim().isEmpty());
                    input.addTextChangedListener(new android.text.TextWatcher(){
                        public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                        public void onTextChanged(CharSequence s,int start,int before,int count){enter.setEnabled(!s.toString().trim().isEmpty());}
                        public void afterTextChanged(android.text.Editable value){}
                    });
                    enter.setOnClickListener(view->{String value=input.getText().toString().trim();if(value.isEmpty()||revision!=presentationRevision||!enter.isEnabled())return;
                        enter.setEnabled(false);input.setEnabled(false);hideKeyboard(input);
                        input.postDelayed(()->{if(revision==presentationRevision)execute.accept(value);},350);});
                    actions.addView(enter,spaced(8));
                }else{
                    String actionLabel=switch(tool){case "tap"->"클릭이 대신 누르기";case "scroll_forward"->"아래로 이동";case "scroll_backward"->"위로 이동";case "back"->"뒤로가기";default->label+" 열기";};
                    actions.addView(primaryButton(actionLabel,()->{if(revision==presentationRevision)execute.accept(null);}),spaced(0));
                }
                if("open_app".equals(tool))return;
            }
            Button more=quietButton("다른 방법",()->{});
            more.setOnClickListener(view->{
                if(revision!=presentationRevision||more.getVisibility()!=VISIBLE)return;more.setVisibility(GONE);
                actions.addView(secondaryButton("화면 다시 확인",refreshCurrent),spaced(8));
                if(hasTools)actions.addView(secondaryButton("직접 진행할 방법",toolsCurrent),spaced(8));
                if(result.optBoolean("can_read_labels"))actions.addView(quietButton("버튼 이름 찾기",visualCurrent),spaced(8));
                if(confirmed!=null)actions.addView(quietButton("결과를 확인했어요",()->{if(revision==presentationRevision)confirmed.run();}),spaced(8));
            });actions.addView(more,spaced(8));return;
        }
        actions.addView(primaryButton("화면 다시 확인",refreshCurrent),spaced(0));
        if(hasTools)actions.addView(secondaryButton(available.length()==1&&"open_app".equals(available.optString(0))?"앱 선택":"다른 방법",toolsCurrent),spaced(8));
        if(result.optBoolean("can_read_labels"))actions.addView(quietButton("버튼 이름 찾기",visualCurrent),spaced(8));
        JSONObject assessment=result.optJSONObject("result_assessment");
        if(confirmed!=null&&"needs_input".equals(state)&&assessment!=null
                &&"goal_postconditions_unconfirmed".equals(assessment.optString("reason")))
            actions.addView(quietButton("제가 확인했어요",()->{if(revision==presentationRevision)confirmed.run();}),spaced(8));
    }
    public void appTaskTools(java.util.function.Consumer<String> choice,Runnable back) {
        appTaskTools(new JSONArray(java.util.List.of("set_text","scroll_forward","scroll_backward","back","open_app")),choice,back);
    }
    public void appTaskTools(JSONArray available,java.util.function.Consumer<String> choice,Runnable back) {
        clear("직접 진행할 방법");final int revision=presentationRevision;
        String[] labels={"검색어 입력","아래로 이동","위로 이동","뒤로가기","앱 열기"};String[] values={"set_text","scroll_forward","scroll_backward","back","open_app"};
        java.util.Set<String> allowed=new java.util.HashSet<>();for(int i=0;available!=null&&i<available.length();i++)allowed.add(available.optString(i));
        for(int i=0;i<labels.length;i++)if(allowed.contains(values[i])){final String value=values[i];content.addView(primaryButton(labels[i],()->{if(revision==presentationRevision)choice.accept(value);}),spaced(8));}
        content.addView(secondaryButton("안내로 돌아가기",()->{if(revision==presentationRevision)back.run();}),spaced(8));
    }
    public void appTaskApps(JSONArray apps,java.util.function.BiConsumer<String,String> selected,Runnable back) {
        clear("어떤 앱을 열까요?");final int revision=presentationRevision;
        for(int i=0;i<apps.length();i++){JSONObject app=apps.optJSONObject(i);if(app==null)continue;content.addView(secondaryButton(app.optString("label"),()->{if(revision==presentationRevision)selected.accept(app.optString("package"),app.optString("label"));}),spaced(4));}
        content.addView(secondaryButton("안내로 돌아가기",()->{if(revision==presentationRevision)back.run();}),spaced(8));
    }
    public void browsing(String message, boolean terminal, Runnable observe, Runnable finish) {
        reviewStage(1, "쿠팡 상품 찾기", "검색과 상품 상세 화면을 안내해요.");
        setNarrationMessage(message);
        content.addView(text(message, 16), spaced(12));
        if (terminal) {
            content.addView(primaryButton("안내 끝내기", finish), spaced(12));
        } else {
            content.addView(primaryButton("현재 화면 확인", observe), spaced(12));
            content.addView(quietButton("원하는 상품을 확인했어요", finish), spaced(8));
        }
    }

    public void publicLogin(String message,boolean terminal,org.json.JSONObject question,
            java.util.function.BiConsumer<String,Boolean> answer,Runnable observe,Runnable openApp,Runnable finish) {
        publicGuide(message,terminal,question,false,answer,finish);
    }
    public void publicGuideRecovery(String message, Runnable refresh, Runnable openApp) {
        progress(null, null, message, false);
        setGuidanceEvidence(message);
        recoveryActions.addView(primaryButton("화면 다시 확인", refresh), spaced(0));
        recoveryActions.addView(secondaryButton("정부24 열기", openApp), spaced(8));
        recoveryActions.setVisibility(VISIBLE);
    }
    public void publicGuide(String message,boolean terminal,org.json.JSONObject question,boolean civil,
            java.util.function.BiConsumer<String,Boolean> answer,Runnable finish) {
        publicGuide(message,terminal,question,civil,"주민등록등본",answer,finish);
    }
    public void publicGuide(String message,boolean terminal,org.json.JSONObject question,boolean civil,String serviceLabel,
            java.util.function.BiConsumer<String,Boolean> answer,Runnable finish) {
        boolean loginChoice=question!=null&&"login_choice".equals(question.optString("field"));
        boolean civilChoice=civil&&!loginChoice;
        if(terminal) {
            progress(null,null,message,false);controls.setVisibility(GONE);divider.setVisibility(GONE);
            recoveryActions.addView(primaryButton("끝내기",finish),spaced(0));recoveryActions.setVisibility(VISIBLE);return;
        }
        if(question==null||"인증기관".equals(question.optString("label"))) {
            progress(null,null,message,false);return;
        }
        reviewStage(1,civilChoice?"정부24 "+serviceLabel+" 안내":"정부24 로그인 안내",
            civil?(loginChoice?"로그인 후 원래 민원 안내를 이어가요.":"발급 안내 화면까지 함께 이동해요. 신청서 작성과 제출은 직접 진행해요."):"");
        publicGuideInput=true;
        setNarrationMessage(message);content.addView(text(message,16),spaced(12));
        if(question!=null) {
            org.json.JSONArray choices=question.optJSONArray("choices");
            for(int i=0;choices!=null&&i<choices.length();i++) {
                org.json.JSONObject choice=choices.optJSONObject(i);if(choice==null)continue;
                String value=choice.optString("value");
                content.addView(secondaryButton((i+1)+". "+choice.optString("label"),()->answer.accept(value,true)),spaced(6));
            }
            EditText editor=new EditText(getContext());editor.setHint(civilChoice?"예: "+serviceLabel:"예: 카카오 인증서를 쓰고 싶어요");
            editor.setSingleLine(true);editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            editor.setTextSize(ClickTextScale.size(getContext(),14));editor.setTextColor(INK);editor.setMinHeight(dp(48));
            editor.setContentDescription(civilChoice?"이동할 공개 메뉴 이름 입력":"사용할 인증수단 이름 입력");
            editor.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
            content.addView(editor,spaced(10));
            content.addView(secondaryButton("말로 답하기",()->{hideKeyboard(editor);listener.stopGuidance();listener.openInput();}),spaced(8));
            content.addView(primaryButton("입력한 답변 보내기",()->{
                String value=editor.getText().toString().trim();if(!value.isEmpty()) {
                    ((android.view.inputmethod.InputMethodManager)getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editor.getWindowToken(),0);
                    answer.accept(value,false);
                }
            }),spaced(8));
        }
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
        reviewStage(executable ? 2 : 1, executable ? "" : "어떤 작업을 도와드릴까요?",
                executable ? "" : "원하시는 항목을 선택해 주세요.");
        setNarrationMessage(executable && preparationMessage!=null?preparationMessage:spokenGuidance);
        if (executable) {
            String summary = review.summary();
            if (review.isFont()) summary = summary.replace(" · ", " ");
            reviewSummary(preparationMessage==null?summary:preparationMessage,preparationMessage==null && review.isFont());
            readyActions();
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
            Button next = reviewNext(() -> {
                if (choice[0] != null) listener.requirementChoice(choice[0]);
            });
            content.addView(choiceCards(choices, selected -> {
                choice[0] = selected.optString("value"); next.setEnabled(true); next.setAlpha(1f);
            }), spaced(12));
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

    // [UI 수정] 사용자가 요청한 내용을 담는 박스: 배경, 글자, 여백과 요약 표시.
    private void pinRequestBox() {
        if(requestSummary==null)return;
        addRequestBox(requestSummary,null);
        View box=content.getChildAt(content.getChildCount()-1);content.removeView(box);content.addView(box,0);
    }
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
            Button expand = quietButton("내용 더 보기", () -> {});
            expand.setTextSize(ClickTextScale.size(getContext(), 13)); expand.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 10));
            expand.setOnClickListener(v -> {
                boolean more = value.getMaxLines() == 3;
                value.setMaxLines(more ? Integer.MAX_VALUE : 3);
                expand.setText(more ? "내용 접기" : "내용 더 보기");
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

    // 확인 질문은 본문에, 음성 재생은 공통 헤더 한 곳에 표시한다.
    private void addQuestionNarration(String message) {
        if (message == null || message.trim().isEmpty()) return;
        setNarrationMessage(message);
        LinearLayout question = new LinearLayout(getContext());
        question.setGravity(Gravity.CENTER_VERTICAL);
        question.setPadding(dp(12), dp(10), dp(8), dp(10));
        question.setBackground(outline(PALE_BLUE, 0xFFD5E4FF, 16));
        narrationText = text(message, 17);
        narrationText.setTextColor(INK);
        if (heading.length() == 0) FloatingPanelStyle.styleTitle(narrationText);
        else narrationText.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        if (android.os.Build.VERSION.SDK_INT >= 28) narrationText.setAccessibilityHeading(true);
        question.addView(narrationText, new LayoutParams(0, -2, 1));
        content.addView(question, spaced(10));
        setNarrationState(false, narrationUnavailable);
    }

    private void addOutcomeMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;
        setNarrationMessage(message);
        narrationText = text(message, 17);
        narrationText.setTextColor(SECONDARY);
        content.addView(narrationText, spaced(8));
        setNarrationState(false, narrationUnavailable);
    }

    /** TTS lifecycle updates never rebuild the card or discard a user's draft. */
    public void setNarrationState(boolean speaking, boolean unavailable) {
        narrationSpeaking = speaking; narrationUnavailable = unavailable;
        if (narrationStatus != null) narrationStatus.setText(unavailable ? "글로 안내할게요" : speaking ? "말하고 있어요" : "클릭의 안내");
        headerReplay.setText(speaking ? "음성 멈춤" : "다시 듣기");
        headerReplay.setContentDescription(speaking ? "음성 안내 멈추기" : "클릭 음성 안내 다시 듣기");
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
        setNarrationMessage(progressMessage);
        guidanceProgress = new LinearLayout(getContext());
        guidanceProgress.setGravity(Gravity.CENTER_VERTICAL);
        guidanceProgress.setVisibility(GONE);
        progressBar = new android.widget.ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        android.graphics.drawable.ClipDrawable fill = new android.graphics.drawable.ClipDrawable(
                FloatingPanelStyle.primary(getResources().getDisplayMetrics().density), Gravity.LEFT,
                android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable track = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{round(0xFFE6EAF3, 8), fill});
        track.setId(0, android.R.id.background); track.setId(1, android.R.id.progress);
        progressBar.setProgressDrawable(track);
        guidanceProgress.addView(progressBar, new LayoutParams(0, dp(8), 1));
        progressCount = text("", 14); progressCount.setTextColor(SECONDARY);
        LayoutParams countParams = new LayoutParams(-2, -2); countParams.leftMargin = dp(16);
        guidanceProgress.addView(progressCount, countParams);
        content.addView(guidanceProgress, spaced(0));
        narrationText = text(progressMessage, FloatingPanelStyle.TITLE_SIZE_SP);
        narrationText.setId(R.id.task_flow_instruction);
        FloatingPanelStyle.styleTitle(narrationText);
        narrationText.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(narrationText, spaced(12));
        divider.setVisibility(GONE);
        setNarrationState(false, narrationUnavailable);
        if (canRequestResult) {
            progressCompletion = primaryButton("다 했어요", () -> listener.control("request_result"));
            content.addView(progressCompletion, spaced(12));
        }
        setDetailedExplanations(detailedExplanations);
        // Keep the received request visible on every progress panel (app entry, launcher search,
        // guided steps), not only in the review stage.
        pinRequestBox();
    }
    public void result(JSONObject prompt) {
        result(prompt, false);
    }
    public void result(JSONObject prompt, boolean fontSize) {
        String question = resultQuestion(prompt, fontSize);
        clear(question);
        addOutcomeMessage(resultDetails(prompt, question));
        setNarrationMessage(resultNarration(prompt, fontSize));
        boolean verified = "CHANGED".equals(prompt.optString("technical_status"))
                || "ALREADY_SET".equals(prompt.optString("technical_status"));
        Button satisfied = primaryButton(!verified ? "네, 원하는 상태예요" : fontSize ? "네, 글씨가 잘 보여요" : "네, 좋아요",
                () -> listener.result("satisfied"));
        satisfied.setId(R.id.task_flow_result_satisfied);
        content.addView(satisfied, spaced(12));
        Button help = secondaryButton(fontSize ? "더 커졌으면 좋겠어" : "조금 더 도와주세요",
                () -> listener.result("needs_help"));
        help.setId(R.id.task_flow_result_help);
        content.addView(help, spaced(8));
    }

    private static String resultMessage(JSONObject prompt) {
        return prompt.optString("message").trim();
    }

    private static String resultQuestion(JSONObject prompt, boolean fontSize) {
        if (prompt.has("technical_status") && !"CHANGED".equals(prompt.optString("technical_status"))
                && !"ALREADY_SET".equals(prompt.optString("technical_status")))
            return "현재 설정이 원하시는 상태인가요?";
        if (fontSize) return "이제 글자가 보기 편한가요?";
        String[] sentences = resultMessage(prompt).split("(?<=[.!?])\\s+");
        for (int i = sentences.length - 1; i >= 0; i--)
            if (sentences[i].endsWith("?")) return sentences[i].trim();
        return "변경한 설정이 편하신가요?";
    }

    private static String resultDetails(JSONObject prompt, String question) {
        String message = resultMessage(prompt);
        int start = message.lastIndexOf(". ");
        if (message.endsWith("?") && start >= 0) return message.substring(0, start + 1).trim();
        return message.replace(question, "").trim();
    }

    public static String resultNarration(JSONObject prompt, boolean fontSize) {
        String question = resultQuestion(prompt, fontSize);
        String details = resultDetails(prompt, question);
        return details.isEmpty() ? question : details + " " + question;
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

    /** Report the user outcome separately from internal execution evidence. */
    public void completed(String message, Runnable onExit) {
        completed(message, false, onExit);
    }
    public void completed(String message, boolean verifiedChange, Runnable onExit) {
        String title = verifiedChange ? "변경을 확인했어요." : "요청을 마쳤어요.";
        clear(title);
        setNarrationMessage(title + (message == null || message.isBlank() ? "" : " " + message));
        if (message != null && !message.isBlank()) addOutcomeMessage(message);
        controls.setVisibility(GONE);
        divider.setVisibility(GONE);
        ImageView artwork = new com.click.designpreview.ClueArtworkView(getContext());
        artwork.setImageResource(com.click.designpreview.R.drawable.clue4_complete);
        artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams artworkParams = new LayoutParams(-1, dp(176));
        artworkParams.topMargin = dp(4);
        content.addView(artwork, artworkParams);
        announceForAccessibility(narrationMessage);
        Button exit = primaryButton("끝내기", () -> {});
        exit.setTag("task_completion_end");
        exit.setContentDescription("현재 작업 끝내기");
        exit.setOnClickListener(view -> {
            if (!exit.isEnabled()) return;
            exit.setEnabled(false);
            onExit.run();
        });
        recoveryActions.addView(exit, spaced(0));
        recoveryActions.setVisibility(VISIBLE);
    }
    public void followUp(JSONObject followUp) {
        clear("조금 더 도와드릴게요"); addOutcomeMessage(followUp.optString("message"));
        JSONArray choices = followUp.optJSONArray("choices");
        if (choices != null) for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i);
            if (choice == null || "unsure".equals(choice.optString("id"))) continue;
            String label = "other".equals(choice.optString("id"))
                    ? "다른 도움이 필요해요" : choice.optString("label");
            content.addView(secondaryButton(label,
                    () -> listener.adjust(choice.optString("id"))), spaced(8));
        }
    }
    public void paused() {
        clear("작업을 잠시 멈췄어요"); content.addView(text("이어서 진행하거나 그만할 수 있어요.", 16));
        pause.setText("이어서 진행"); pause.setOnClickListener(v -> listener.control("resume_task"));
    }
    public void expired(Runnable newRequest) {
        clear("안내를 잠시 멈췄어요");
        content.addView(text("응답을 기다리다가 안내 시간이 지났어요. 원하실 때 다시 시작해 주세요.", 16), spaced(8));
        divider.setVisibility(GONE);
        controls.setVisibility(GONE);
        recoveryActions.setVisibility(VISIBLE);
        recoveryActions.addView(primaryButton("다시 시작하기", newRequest), spaced(8));
    }
    public void settingsEntryRecovery(Runnable open) {
        settingsEntryRecovery("‘설정 열기’를 눌러주세요.", open);
    }

    public void settingsEntryRecovery(String message, Runnable open) {
        appEntryRecovery("설정", message, open, false);
    }

    public void appEntryRecovery(String appName, Runnable open) {
        setNarrationMessage("‘" + appName + " 열기’를 눌러주세요. 앱 화면에서 이어서 안내할게요.");
        appEntryRecovery(appName, narrationMessage, open, true);
    }

    public void appEntryRecovery(String appName, String message, Runnable open) {
        appEntryRecovery(appName, message, open, true);
    }

    private void appEntryRecovery(String appName, String message, Runnable open, boolean once) {
        clear(appName + " 앱에서 이어갈게요");
        setNarrationMessage(message);
        content.addView(text(narrationMessage, 17), spaced(8));
        pinRequestBox();
        Button action = primaryButton(appName + " 열기", () -> {});
        action.setOnClickListener(view -> {
            if (!action.isEnabled()) return;
            if (once) action.setEnabled(false);
            open.run();
        });
        recoveryActions.setVisibility(VISIBLE);
        recoveryActions.addView(action, spaced(8));
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
    }

    private int presentationRevision;
    public void waiting(String message) {
        clear(message);
        markWaitingForScreen();
    }
    public void waitingProgress(String message) {
        progress(null, null, message, false);
        markWaitingForScreen();
    }
    public void markWaitingForScreen() {
        waitingForScreen = true;
        if(narrationText!=null)narrationText.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);
    }
    /** Authored, cause-specific help. Diagnostic strings never become user instructions. */

    public void failureRecovery(String title,String message,String actionLabel,Runnable action) {
        clear(title);
        setGuidanceEvidence(message);
        setNarrationMessage(message);
        compactSummary=title;
        refreshCompactMessage();
        content.addView(text(message,17),spaced(8));
        if(actionLabel!=null && action!=null){
            recoveryActions.setVisibility(VISIBLE);
            recoveryActions.addView(primaryButton(actionLabel,action),spaced(8));
        }
    }

    public void observationRecovery(String message, Runnable retry) {
        observationRecovery(null, message, retry);
    }
    public void observationRecovery(String goal, String message, Runnable retry) {
        clear("");
        setGuidanceEvidence(message);
        compactSummary = "화면을 확인하고 다시 시도해 주세요.";
        setNarrationMessage("화면이 열리면 ‘다시 시도하기’를 눌러주세요.");
        content.addView(text(narrationMessage, 17), spaced(8));
        addRecoveryRetry(retry);
    }

    public void selectionRecovery(String message, String requiredChange, Runnable retry) {
        clear("");
        String label = "다시 시도하기";
        if ("connection_restored".equals(requiredChange)) {
            setNarrationMessage("인터넷 연결을 확인해 주세요.");
            label = "연결 후 다시 시도";
        } else if ("user_clarification".equals(requiredChange)) {
            setNarrationMessage(message==null || message.isBlank() ? "앱에서 원하시는 메뉴를 열어주세요." : message);
            label = "메뉴를 열었어요";
        }
        else if ("safe_screen".equals(requiredChange))
            setNarrationMessage("인증이나 입력을 마친 뒤 다시 시도해 주세요.");
        else if ("new_target_evidence".equals(requiredChange) || "narrow_observation".equals(requiredChange))
            setNarrationMessage("원하는 항목이 보이도록 화면을 움직인 뒤 다시 시도해 주세요.");
        else if ("new_observation".equals(requiredChange) || "settle_interval".equals(requiredChange))
            setNarrationMessage("화면이 열리면 다시 시도해 주세요.");
        else if ("provider_available".equals(requiredChange) || "fresh_time_budget".equals(requiredChange)
                || "provider_configuration".equals(requiredChange) || "valid_model_response".equals(requiredChange))
            setNarrationMessage("지금은 안내를 이어가기 어려워요. 잠시 뒤 다시 시도해 주세요.");
        else setNarrationMessage(message==null || message.isBlank() ? "화면을 확인한 뒤 다시 시도해 주세요." : message);
        setGuidanceEvidence(narrationMessage);
        compactSummary = narrationMessage;
        content.addView(text(narrationMessage, 17), spaced(8));
        recoveryActions.setVisibility(VISIBLE);
        recoveryActions.addView(primaryButton(label, retry), spaced(8));
    }

    public void recoveryApology(Runnable retry) {
        clear("");
        setGuidanceEvidence(HELP_EXPLANATION);
        compactSummary = "화면을 다시 확인해 주세요.";
        setNarrationMessage(HELP_EXPLANATION);
        addFailureClue(HELP_EXPLANATION);
        addRecoveryRetry(retry);
    }

    private void addRecoveryRetry(Runnable retry) {
        recoveryActions.setVisibility(VISIBLE);
        Button retryButton = primaryButton("다시 시도하기", retry);
        retryButton.setContentDescription("다시 시도하기. 안내 이어가기");
        recoveryActions.addView(retryButton, spaced(8));
    }

    public void accessibilityRecovery(String message, Runnable guide, Runnable retry) {
        clear("접근성 연결을 확인해 주세요");
        setGuidanceEvidence(message);
        setNarrationMessage(message);
        content.addView(text(message, 16), spaced(8));
        recoveryActions.setVisibility(VISIBLE);
        recoveryActions.addView(primaryButton("접근성 안내 보기", guide), spaced(8));
        recoveryActions.addView(quietButton("다시 연결 확인", retry), spaced(8));
    }

    /** Compatibility for runtime callers that still expose manual list navigation. */
    public void scrollRecovery(String goal, String message, Runnable retry, Runnable down, Runnable up) {
        observationRecovery(goal, message, retry);
        content.addView(primaryButton("아래 항목 찾기", down), spaced(8));
        content.addView(secondaryButton("위 항목 찾기", up), spaced(8));
    }

    public void targetHelp(String target, Runnable notFound) {
        clear("");
        targetHelpInstruction = "‘" + target + "’ 항목을 찾아주세요.";
        compactSummary = targetHelpInstruction;
        targetHelpOpened = !minimizedProgress;
        refreshCompactMessage();
        TextView instruction = text(targetHelpInstruction, FloatingPanelStyle.TITLE_SIZE_SP);
        FloatingPanelStyle.styleTitle(instruction);
        content.addView(instruction, spaced(8));
        controls.setVisibility(GONE);
        divider.setVisibility(GONE);
        Button button = primaryButton("못 찾겠어요", () -> {});
        button.setContentDescription("못 찾겠어요");
        button.setOnClickListener(view -> {
            if (!button.isEnabled()) return;
            button.setEnabled(false);
            notFound.run();
        });
        recoveryActions.addView(button, spaced(8));
        recoveryActions.setVisibility(VISIBLE);
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
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xE6FFFFFF);
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
                paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12, 12, 3.5f, paint);
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
        TextView text = new TextView(getContext()); text.setText(value);
        text.setTextSize(ClickTextScale.size(getContext(), Math.max(FloatingPanelStyle.BODY_SIZE_SP, size)));
        text.setTextColor(INK); text.setPadding(0, dp(2), 0, dp(2));
        text.setIncludeFontPadding(false); text.setLineSpacing(dp(2), 1.05f);
        text.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
        return text;
    }
    private Button quietButton(String label, Runnable action) {
        Button button = new Button(getContext()); button.setText(label);
        button.setTextSize(ClickTextScale.size(getContext(), FloatingPanelStyle.ACTION_SIZE_SP));
        button.setTextColor(BLUE); button.setAllCaps(false); button.setMinHeight(dp(48));
        button.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
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
                new int[]{0xFF3987FF, 0xFF1954F5});
        gradient.setCornerRadius(dp(18));
        GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(dp(18));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), gradient, mask));
        button.setMinHeight(dp(50));
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private void styleReviewButton(Button button, boolean primary) {
        button.setTextSize(ClickTextScale.size(getContext(), FloatingPanelStyle.ACTION_SIZE_SP));
        button.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setTextColor(primary ? Color.WHITE : SECONDARY);
        button.setBackground(primary ? FloatingPanelStyle.primary(getResources().getDisplayMetrics().density)
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
        if (!checked) return rippleOutlined(Color.WHITE, 0xFFF0F2F8, 0x183478F6, 16);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFEAF2FF, 0xFFDCE9FF});
        fill.setCornerRadius(dp(16));
        return new RippleDrawable(ColorStateList.valueOf(0x183478F6), fill, round(Color.WHITE, 16));
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
