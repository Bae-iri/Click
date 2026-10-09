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
import android.widget.ImageView;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import com.click.domain.goal.RequirementReview;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Large touch targets; radio selection and read-only progress have different semantics. */
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
    }
    private final Listener listener;
    private final LinearLayout content;
    private final Button pause;
    private final Button reviewConfirm;
    private boolean requirements;
    private final TextView heading;
    private final TextView brand;
    private final View logo;
    private final LinearLayout header;
    private final ScrollView scroll;
    private final List<String> checklistLabels = new ArrayList<>();
    private final List<String> checklistStates = new ArrayList<>();
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
        heading = text("", 20);
        heading.setPadding(0, 0, 0, 0);
        heading.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        titles.addView(heading);
        header.addView(titles, new LayoutParams(0, -2, 1));
        logo = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            @Override protected void onDraw(Canvas canvas) {
                ClickIconRenderer.draw(this, canvas, paint, path, getWidth() / 2f,
                        getHeight() / 2f, dp(17), 0f, false);
            }
        };
        logo.setContentDescription("Click 로고");
        logo.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams logoParams = new LayoutParams(dp(44), dp(44));
        logoParams.leftMargin = dp(12);
        header.addView(logo, logoParams);
        LayoutParams headerParams = new LayoutParams(-1, -2);
        headerParams.bottomMargin = dp(12);
        addView(header, headerParams);
        content = new LinearLayout(context); content.setOrientation(VERTICAL);
        content.setPadding(0, 0, 0, dp(10));
        scroll = new ScrollView(context); scroll.setFillViewport(false); scroll.addView(content);
        scroll.setVerticalScrollBarEnabled(false);
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

        View divider = new View(context);
        divider.setBackgroundColor(BORDER);
        LayoutParams dividerParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.bottomMargin = dp(8);
        addView(divider, dividerParams);

        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER);
        pause = quietButton("일시정지", () -> listener.control("pause_task"));
        LayoutParams pauseParams = new LayoutParams(0, dp(48), 1f);
        pauseParams.rightMargin = dp(6);
        controls.addView(pause, pauseParams);
        Button stop = secondaryButton("요청 중지", () -> listener.control("cancel_task"));
        stop.setContentDescription("처리 중인 요청 중지");
        stop.setTextColor(0xFF536885);
        LayoutParams stopParams = new LayoutParams(0, dp(48), 1f);
        stopParams.leftMargin = dp(6);
        controls.addView(stop, stopParams);
        addView(controls);
    }

    public void setAvailableHeight(int height) { maxHeight = height; requestLayout(); }
    @Override protected void onMeasure(int width, int height) {
        boolean compact = maxHeight < dp(260) * Math.max(1f, getResources().getConfiguration().fontScale);
        brand.setVisibility(compact ? GONE : VISIBLE);
        heading.setTextSize(compact ? 18 : 20);
        LayoutParams headerParams = (LayoutParams) header.getLayoutParams();
        headerParams.bottomMargin = dp(compact ? 6 : 12);
        LayoutParams logoParams = (LayoutParams) logo.getLayoutParams();
        logoParams.width = logoParams.height = dp(compact ? 36 : 44);
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY));
    }
    private void clear(String title) {
        content.removeAllViews();
        reviewConfirm.setVisibility(GONE);
        resetScrollPending = true;
        scroll.scrollTo(0, 0);
        scroll.post(() -> scroll.scrollTo(0, 0));
        heading.setText(title);
        pause.setText("일시정지"); pause.setOnClickListener(v -> listener.control("pause_task"));
    }
    public void goals(String message, JSONArray candidates) {
        clear("어떤 도움을 원하세요?");
        TextView explanation = text(message, 16);
        explanation.setTextColor(SECONDARY);
        content.addView(explanation);
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
    public void requirements(RequirementReview review, boolean executable) {
        clear("요청을 확인해 주세요");
        setRequirements(review);
        TextView original = text("요청: " + review.request(), 14);
        original.setMaxLines(3); original.setTextColor(SECONDARY);
        content.addView(original);
        renderChecklist();
        TextView question = text(review.prompt(executable), 16);
        content.addView(question, spaced(8));
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
        content.addView(quietButton("내용 수정", () -> { editor.setVisibility(VISIBLE); input.requestFocus(); }), spaced(8));
        content.addView(editor, spaced(6));
        reviewConfirm.setVisibility(VISIBLE); reviewConfirm.setEnabled(executable);
        reviewConfirm.setAlpha(executable ? 1f : .48f);
    }
    public void setRequirements(RequirementReview review) {
        requirements = false;
        try { setChecklist(review.rows()); } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        requirements = true;
    }
    public void setChecklist(JSONArray checklist) {
        if (requirements || checklist == null) return;
        checklistLabels.clear(); checklistStates.clear();
        for (int i = 0; i < checklist.length(); i++) {
            JSONObject step = checklist.optJSONObject(i); if (step == null) continue;
            checklistLabels.add(step.optString("label")); checklistStates.add(step.optString("status", "pending"));
        }
    }
    public void progress(String goal, JSONArray checklist) {
        progress(goal, checklist, null);
    }
    public void progress(String goal, JSONArray checklist, String instruction) {
        progress(goal, checklist, instruction, true);
    }
    public void progress(String goal, JSONArray checklist, String instruction, boolean canRequestResult) {
        clear("안내 중"); setChecklist(checklist);
        TextView goalLabel = text(goal, 15);
        goalLabel.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        goalLabel.setTextColor(BLUE_DARK);
        goalLabel.setPadding(0, 0, 0, dp(4));
        content.addView(goalLabel, spaced(2));
        if (instruction != null && !instruction.trim().isEmpty()) {
            TextView guide = text(instruction, 18);
            guide.setPadding(dp(12), dp(12), dp(12), dp(12));
            guide.setBackground(round(PALE_BLUE, 18));
            content.addView(guide, spaced(6));
        }
        renderChecklist();
        if (canRequestResult)
            content.addView(primaryButton("결과 확인하기", () -> listener.control("request_result")), spaced(12));
    }
    public void result(JSONObject prompt) {
        clear("결과를 확인해 주세요");
        renderChecklist();
        TextView message = text(prompt.optString("message"), 17);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(14), dp(14), dp(14), dp(14));
        message.setBackground(round(PALE_BLUE, 18));
        content.addView(message, spaced(10));
        content.addView(primaryButton("네, 잘 보여요", () -> listener.result("satisfied")), spaced(12));
        content.addView(secondaryButton("아직 불편해요", () -> listener.result("needs_help")), spaced(8));
        TextView note = text("답하지 않으면 완료로 기록하지 않아요.", 13);
        note.setTextColor(SECONDARY); note.setGravity(Gravity.CENTER); content.addView(note, spaced(6));
    }

    /** A user answer never replaces observed technical evidence in checklist rows. */
    public void completed(String message, Runnable afterAnimation) {
        clear("결과 확인을 마쳤어요");
        renderChecklist();
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
        clear("어떤 점이 불편한가요?"); content.addView(text(followUp.optString("message"), 17));
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
    public void waiting(String message) { clear(message); }

    private void renderChecklist() {
        if (checklistLabels.isEmpty()) return;
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        int completed = 0;
        for (String state : checklistStates) if ("completed".equals(state)) completed++;
        LinearLayout summary = new LinearLayout(getContext());
        summary.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = text(requirements ? "요청 확인 체크리스트" : "진행 단계", 13);
        caption.setTextColor(SECONDARY);
        summary.addView(caption, new LayoutParams(0, -2, 1));
        TextView count = text(requirements ? (completed == checklistLabels.size() ? "확인함" : "검토 중") : completed + " / " + checklistLabels.size() + " 완료", 12);
        count.setTextColor(SECONDARY);
        summary.addView(count);
        card.addView(summary, spaced(0));
        for (int index = 0; index < checklistLabels.size(); index++) {
            boolean checked = "completed".equals(checklistStates.get(index));
            boolean current = "current".equals(checklistStates.get(index));
            boolean identified = requirements && "identified".equals(checklistStates.get(index));
            String state = requirements ? (checked ? "요청 확인함" : "identified".equals(checklistStates.get(index)) ? "입력에서 파악함" : "확인 필요") : checked ? "완료" : current ? "지금 할 일" : "대기";
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(40));
            row.setPadding(dp(10), dp(6), dp(10), dp(6));
            row.setBackground(round(current ? PALE_BLUE : Color.WHITE, 12));
            row.setContentDescription(state + ": " + checklistLabels.get(index));
            row.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            ImageView badge = new ImageView(getContext());
            badge.setImageDrawable(new StatusMark(checked, current || identified));
            badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(badge, new LayoutParams(dp(24), dp(24)));
            TextView label = text(checklistLabels.get(index), 15);
            label.setPadding(0, 0, 0, 0);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            label.setTextColor(current || identified ? INK : checked ? 0xFF536885 : SECONDARY);
            if (current) label.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
            LayoutParams labelParams = new LayoutParams(0, -2, 1f);
            labelParams.leftMargin = dp(10);
            row.addView(label, labelParams);
            if (current) {
                TextView status = text("진행 중", 11);
                status.setPadding(dp(7), dp(4), dp(7), dp(4));
                status.setTextColor(BLUE_DARK);
                status.setBackground(round(0xFFE1ECFF, 8));
                status.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                LayoutParams statusParams = new LayoutParams(-2, -2);
                statusParams.leftMargin = dp(8);
                row.addView(status, statusParams);
            }
            card.addView(row, spaced(2));
        }
        content.addView(card, spaced(10));
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
