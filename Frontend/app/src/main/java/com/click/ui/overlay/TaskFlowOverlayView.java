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
        default void replayGuidance(String message) {}
        default void stopGuidance() {}
        default void collapse() {}
        default void openInput() {}
    }
    private final LoadingStatusView loadingStatus;
    private final TextView automationStatus;
    private final Listener listener;
    private final LinearLayout content;
    private final Button pause;
    private final Button reviewConfirm;
    private final Button headerInput;
    private final View divider;
    private final LinearLayout controls;
    private final LinearLayout recoveryActions;
    private TextView narrationStatus, narrationText;
    private LinearLayout requestCaptionRow;
    private Button narrationPlayback, narrationCaptions;
    private String narrationMessage;
    private boolean narrationSpeaking, narrationUnavailable;
    private final TextView heading;
    private final TextView brand;
    private final View close;
    private final LinearLayout header;
    private final ScrollView scroll;
    private int maxHeight;
    private boolean resetScrollPending;

    public TaskFlowOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * .65f);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dp(24)); bg.setStroke(dp(1), BORDER);
        setBackground(bg); setElevation(dp(10));
        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(VERTICAL);
        brand = text("click 안내", 12);
        brand.setTextColor(BLUE_DARK);
        brand.setPadding(0, 0, 0, dp(3));
        titles.addView(brand);
        heading = ClickUi.title(context, "");
        heading.setTextSize(20);
        heading.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        heading.setLetterSpacing(0);
        heading.setPadding(0, 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        titles.addView(heading);
        header.addView(titles, new LayoutParams(0, -2, 1));
        Button input = quietButton("입력", listener::openInput);
        headerInput = input;
        input.setContentDescription("말이나 글로 입력하기");
        input.setTextSize(14);
        input.setPadding(dp(6), 0, dp(6), 0);
        input.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 16));
        input.setId(R.id.task_flow_input);
        header.addView(input, new LayoutParams(dp(48), dp(48)));
        ImageView closeIcon = new ImageView(context);
        close = closeIcon;
        close.setId(R.id.task_flow_collapse);
        closeIcon.setImageResource(R.drawable.ic_overlay_close);
        close.setContentDescription("안내 창 접기");
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setFocusable(true);
        close.setBackground(ripple(0xFFF1F4F9, 0x183478F6, 16));
        close.setOnClickListener(view -> listener.collapse());
        LayoutParams closeParams = new LayoutParams(dp(48), dp(48));
        closeParams.leftMargin = dp(4);
        header.addView(close, closeParams);
        LayoutParams headerParams = new LayoutParams(-1, -2);
        headerParams.bottomMargin = dp(12);
        addView(header, headerParams);
        loadingStatus = new LoadingStatusView(context);
        addView(loadingStatus, spaced(8));
        automationStatus = text("", 14);
        automationStatus.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        automationStatus.setVisibility(GONE);
        addView(automationStatus, spaced(6));
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

        reviewConfirm = primaryButton("이 내용으로 시작", () -> listener.confirmRequirements());
        reviewConfirm.setVisibility(GONE);
        LayoutParams reviewParams = spaced(0); reviewParams.bottomMargin = dp(10);
        addView(reviewConfirm, reviewParams);

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
        automationStatus.setVisibility(message == null ? GONE : VISIBLE);
    }
    public void setLoadingStatus(String message) { loadingStatus.setStatus(message); }

    public void setAvailableHeight(int height) { if (maxHeight != height) { maxHeight = height; requestLayout(); } }
    @Override protected void onMeasure(int width, int height) {
        boolean compact = maxHeight < dp(260) * Math.max(1f, getResources().getConfiguration().fontScale);
        brand.setVisibility(compact ? GONE : VISIBLE);
        heading.setTextSize(compact ? 18 : 20);
        LayoutParams headerParams = (LayoutParams) header.getLayoutParams();
        headerParams.bottomMargin = dp(compact ? 6 : 12);
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
    }
    private void clear(String title) {
        setAutomationStatus(null);
        content.removeAllViews();
        reviewConfirm.setVisibility(GONE);
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

    public void goals(String message, JSONArray candidates) {
        clear("어떤 도움을 원하세요?");
        addNarration(message);
        RadioGroup group = new RadioGroup(getContext()); group.setOrientation(VERTICAL);
        final JSONObject[] selected = {null};
        Button confirm = primaryButton("이 목표로 시작", () -> {
            if (selected[0] != null) listener.chooseGoal(selected[0]);
        });
        confirm.setEnabled(false); confirm.setAlpha(.48f);
        if (candidates != null) for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) continue;
            String label = candidate.optString("label");
            RadioButton card = new RadioButton(getContext()); card.setId(View.generateViewId());
            card.setText(label); card.setTextSize(17); card.setTextColor(INK);
            card.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
            StateListDrawable selection = new StateListDrawable();
            selection.addState(new int[]{android.R.attr.state_checked}, new StatusMark(true, false));
            selection.addState(new int[]{}, new StatusMark(false, false));
            // CompoundButton positions its button at x=0, independently of text padding.
            card.setButtonDrawable(new InsetDrawable(selection, dp(12), 0, 0, 0));
            card.setCompoundDrawablePadding(dp(10));
            card.setIncludeFontPadding(false);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setMinHeight(dp(56)); card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(cardBackground(false));
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(-1, -2); params.bottomMargin = dp(8);
            group.addView(card, params);
            card.setOnCheckedChangeListener((v, checked) -> {
                card.setBackground(cardBackground(checked));
                card.setText(label);
                if (checked) {
                    selected[0] = candidate.optJSONObject("goal");
                    confirm.setEnabled(true); confirm.setAlpha(1f);
                }
            });
        }
        content.addView(group, spaced(6));
        content.addView(confirm, spaced(4));

        LinearLayout clarification = new LinearLayout(getContext());
        Button unsure = quietButton("잘 모르겠어요", () -> listener.clarify("unsure"));
        Button different = quietButton("다른 뜻이에요", () -> listener.clarify("different"));
        LayoutParams halfLeft = new LayoutParams(0, dp(48), 1f); halfLeft.rightMargin = dp(5);
        LayoutParams halfRight = new LayoutParams(0, dp(48), 1f); halfRight.leftMargin = dp(5);
        clarification.addView(unsure, halfLeft); clarification.addView(different, halfRight);
        content.addView(clarification, spaced(8));

        TextView direct = text("직접 입력해도 돼요", 14);
        direct.setTextColor(SECONDARY); content.addView(direct, spaced(8));
        EditText input = new EditText(getContext()); input.setTextSize(16); input.setTextColor(INK);
        input.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        input.setHintTextColor(0xFF8A9AB4); input.setHint("예) 글자가 너무 작아요");
        input.setSingleLine(false); input.setMinHeight(dp(48));
        input.setPadding(dp(16), dp(10), dp(16), dp(10)); input.setBackground(outline(0xFFFFFFFF, BORDER, 16));
        content.addView(input);
        content.addView(quietButton("입력한 내용으로 다시 확인", () -> {
            if (!input.getText().toString().trim().isEmpty()) listener.rephrase(input.getText().toString());
        }), spaced(8));
    }
    public void preparation(com.click.domain.goal.GoalPreparationState state) {
        clear(state.ready() ? "요청을 확인해 주세요" : "추가로 확인할게요");
        addRequestBox(state.data.optString("source_text"), null);
        JSONObject values = state.data.optJSONObject("values");
        JSONArray fields = state.data.optJSONArray("required_fields");
        if (fields != null && values != null) for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null || !values.has(field.optString("id"))) continue;
            String value = values.optString(field.optString("id"));
            JSONArray choices = field.optJSONArray("choices");
            if (choices != null) for (int j = 0; j < choices.length(); j++) {
                JSONObject choice = choices.optJSONObject(j);
                if (choice != null && value.equals(choice.optString("value"))) { value = choice.optString("label"); break; }
            }
            content.addView(text(field.optString("label") + " · " + value, 15), spaced(6));
        }
        JSONObject question = state.question();
        String message = question == null ? state.message : question.optString("message");
        content.addView(text(message == null ? "요청을 확인해 주세요." : message, 18), spaced(12));
        addNarration(message);
        if (state.needsInput()) {
            if ("choice".equals(question.optString("kind"))) {
                JSONArray choices = question.optJSONArray("choices");
                if (choices != null) for (int i = 0; i < choices.length(); i++) {
                    JSONObject choice = choices.optJSONObject(i);
                    if (choice != null) content.addView(secondaryButton(choice.optString("label"),
                            () -> listener.answerGoalInput(choice.optString("value"))), spaced(8));
                }
            } else {
                EditText input = new EditText(getContext()); input.setTextSize(17);
                input.setHint(question.optString("placeholder", "답변을 입력해 주세요"));
                input.setSingleLine(true); input.setMinHeight(dp(48));
                input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(
                        question.optInt("max_length", 100))});
                content.addView(input, spaced(8));
                content.addView(secondaryButton("이 답변으로 확인", () -> {
                    if (!input.getText().toString().trim().isEmpty()) listener.answerGoalInput(input.getText().toString());
                }), spaced(8));
            }
        }
        reviewConfirm.setVisibility(state.ready() ? VISIBLE : GONE);
        reviewConfirm.setEnabled(state.ready());
        reviewConfirm.setAlpha(1f);
        reviewConfirm.setText("이 내용으로 시작");
        content.addView(quietButton("답변을 다시 선택", listener::resetGoalInputs), spaced(8));
        EditText rephrase = new EditText(getContext()); rephrase.setHint("요청을 다르게 입력하기");
        rephrase.setTextSize(16); rephrase.setMinHeight(dp(48));
        content.addView(rephrase, spaced(8));
        content.addView(quietButton("수정한 요청 확인", () -> {
            if (!rephrase.getText().toString().trim().isEmpty()) listener.rephrase(rephrase.getText().toString());
        }), spaced(6));
    }

    public void requirements(RequirementReview review, boolean executable) {
        clear("요청을 확인해 주세요");
        addRequestBox(review.request().split("\n추가 확인:", 2)[0],
                executable ? review.summary() : null);
        addNarration(review.prompt(executable));
        if (review.needsTarget()) {
            content.addView(secondaryButton("휴대폰 기본 글자", () -> listener.requirementChoice("phone")), spaced(6));
            if (!review.appOnly()) content.addView(secondaryButton("특정 앱 안에서만", () -> listener.requirementChoice("app")), spaced(6));
        }
        if (review.needsDirection()) {
            LinearLayout directions = new LinearLayout(getContext());
            directions.addView(quietButton("더 크게", () -> listener.requirementChoice("increase")), new LayoutParams(0, dp(48), 1));
            LayoutParams right = new LayoutParams(0, dp(48), 1); right.leftMargin = dp(8);
            directions.addView(quietButton("더 작게", () -> listener.requirementChoice("decrease")), right);
            content.addView(directions, spaced(8));
        }
        LinearLayout editor = new LinearLayout(getContext()); editor.setOrientation(VERTICAL);
        EditText input = new EditText(getContext()); input.setTextSize(16); input.setTextColor(INK);
        input.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        input.setHint(review.needsTarget() && review.appOnly() ? "앱 이름을 입력해 주세요" : "예) 카카오톡 앱 전체 글자를 크게");
        input.setContentDescription("요청 내용 수정"); input.setMinHeight(dp(48));
        input.setPadding(dp(12), dp(8), dp(12), dp(8)); input.setBackground(outline(Color.WHITE, BORDER, 12));
        editor.addView(input);
        editor.addView(quietButton("수정한 내용 반영", () -> {
            String answer = input.getText().toString().trim();
            if (!answer.isEmpty()) listener.requirementAnswer(answer);
        }), spaced(6));
        boolean showEditor = review.needsTarget() && review.appOnly();
        editor.setVisibility(showEditor ? VISIBLE : GONE);
        Button edit = quietButton("내용 수정", () -> {
            listener.stopGuidance(); editor.setVisibility(VISIBLE); input.requestFocus();
            input.post(() -> scroll.smoothScrollTo(0, editor.getTop()));
            showKeyboardWhenFocused(input);
        });
        edit.setTextSize(13); edit.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 10));
        requestCaptionRow.addView(edit, new LayoutParams(-2, dp(48)));
        content.addView(editor, spaced(6));
        reviewConfirm.setVisibility(executable ? VISIBLE : GONE); reviewConfirm.setEnabled(executable);
        reviewConfirm.setAlpha(executable ? 1f : .48f);
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
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(round(0xFFF5F6F8, 14));
        LinearLayout copy = new LinearLayout(getContext()); copy.setOrientation(VERTICAL);
        TextView label = text("내 요청", 16); label.setTextColor(INK); label.setPadding(0, 0, 0, 0);
        label.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        requestCaptionRow = new LinearLayout(getContext()); requestCaptionRow.setGravity(Gravity.CENTER_VERTICAL);
        requestCaptionRow.addView(label, new LayoutParams(0, -2, 1));
        copy.addView(requestCaptionRow);
        TextView value = text(request, 18); value.setPadding(0, dp(4), 0, dp(2));
        copy.addView(value);
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

    private void addNarration(String message) {
        if (message == null || message.trim().isEmpty()) return;
        narrationMessage = message;
        LinearLayout section = new LinearLayout(getContext()); section.setOrientation(VERTICAL);
        LinearLayout player = new LinearLayout(getContext()); player.setGravity(Gravity.CENTER_VERTICAL);
        narrationStatus = text("Click의 안내", 13); narrationStatus.setTextColor(SECONDARY);
        player.addView(narrationStatus, new LayoutParams(0, -2, 1));
        narrationPlayback = quietButton("다시 듣기", () -> {
            if (narrationSpeaking) listener.stopGuidance();
            else listener.replayGuidance(narrationMessage);
        });
        narrationPlayback.setTextSize(13); narrationPlayback.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 10));
        player.addView(narrationPlayback, new LayoutParams(dp(80), -2));
        narrationCaptions = quietButton("글로 보기", () -> {
            boolean show = narrationText.getVisibility() != VISIBLE;
            narrationText.setVisibility(show ? VISIBLE : GONE);
            narrationCaptions.setText(show ? "글 접기" : "글로 보기");
        });
        narrationCaptions.setTextSize(13); narrationCaptions.setTextColor(SECONDARY);
        narrationCaptions.setBackground(ripple(Color.TRANSPARENT, 0x183478F6, 10));
        player.addView(narrationCaptions, new LayoutParams(dp(80), -2));
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
        if (narrationStatus == null) return;
        narrationStatus.setText(unavailable ? "글로 안내할게요" : speaking ? "말하고 있어요" : "Click의 안내");
        narrationPlayback.setText(speaking ? "음성 멈춤" : "다시 듣기");
        narrationPlayback.setContentDescription(speaking ? "음성 안내 멈추기" : "Click 음성 안내 다시 듣기");
        if (unavailable) { narrationText.setVisibility(VISIBLE); narrationCaptions.setText("글 접기"); }
    }
    public void progress(String goal, JSONArray checklist) {
        progress(goal, checklist, null);
    }
    public void progress(String goal, JSONArray checklist, String instruction) {
        progress(goal, checklist, instruction, true);
    }
    public void progress(String goal, JSONArray checklist, String instruction, boolean canRequestResult) {
        clear("안내 중"); setChecklist(checklist);
        addRequestBox(goal, null);
        addNarration(instruction);
        if (canRequestResult)
            content.addView(primaryButton("결과 확인하기", () -> listener.control("request_result")), spaced(12));
    }
    public void result(JSONObject prompt) {
        clear("결과를 확인해 주세요");
        addNarration(prompt.optString("message"));
        content.addView(primaryButton("네, 잘 보여요", () -> listener.result("satisfied")), spaced(12));
        content.addView(secondaryButton("아직 불편해요", () -> listener.result("needs_help")), spaced(8));
        TextView note = text("답하지 않으면 완료로 기록하지 않아요.", 13);
        note.setTextColor(SECONDARY); note.setGravity(Gravity.CENTER); content.addView(note, spaced(6));
    }

    /** Report the user outcome separately from internal execution evidence. */
    public void completed(String message, Runnable afterAnimation) {
        clear("결과 확인을 마쳤어요");
        TextView completion = text(message == null || message.trim().isEmpty()
                ? "원하는 결과를 얻었다고 알려주셨어요." : message, 17);
        completion.setGravity(Gravity.CENTER);
        completion.setTextColor(BLUE);
        completion.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        completion.setPadding(dp(14), dp(14), dp(14), dp(14));
        completion.setBackground(round(PALE_BLUE, 18));
        content.addView(completion, spaced(10));
        announceForAccessibility(completion.getText());
        postDelayed(afterAnimation, 1100L);
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
        return outline(checked ? 0xFFEAF2FF : Color.WHITE, checked ? BLUE : BORDER, 18,
                checked ? 2 : 1);
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
