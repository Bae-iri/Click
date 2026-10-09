package com.click;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.click.ui.overlay.ClickIconRenderer;

/** Interactive follow-up tutorial for the floating Click control and completion checklist. */
public final class OverlayTutorialActivity extends AppCompatActivity {
    private static final String PAGE_STATE = "overlay_tutorial_page";
    private static final String CONFIRMED_STATE = "overlay_tutorial_confirmed";
    private static final int BLUE = 0xFF3478F6;
    private static final int BLUE_DARK = 0xFF2469EA;
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF6F7F9E;
    private static final int PALE_BLUE = 0xFFF1F6FF;

    private LinearLayout body;
    private LinearLayout progress;
    private TextView counter;
    private TextView actionLabel;
    private Button later;
    private LinearLayout action;
    private int page;
    private boolean checklistConfirmed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        page = savedInstanceState == null ? 0 : savedInstanceState.getInt(PAGE_STATE, 0);
        checklistConfirmed = savedInstanceState != null
                && savedInstanceState.getBoolean(CONFIRMED_STATE, false);

        FrameLayout canvas = new FrameLayout(this);
        canvas.setBackgroundColor(Color.WHITE);
        LinearLayout column = vertical();
        int maxWidth = Math.min(getResources().getDisplayMetrics().widthPixels, dp(480));
        canvas.addView(column, new FrameLayout.LayoutParams(maxWidth, -1, Gravity.CENTER_HORIZONTAL));
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(22) + insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    dp(22) + insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        column.addView(createHeader(), new LinearLayout.LayoutParams(-1, dp(54)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = vertical();
        body.setPadding(dp(2), dp(18), dp(2), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        column.addView(createFooter(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(canvas);
        column.requestApplyInsets();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        showPage(page, false);
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(com.click.designpreview.R.drawable.onboarding_back);
        back.setContentDescription("이전");
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x183478F6), null,
                rounded(Color.WHITE, 24)));
        back.setOnClickListener(view -> goBack());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        progress = new LinearLayout(this);
        progress.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(0, dp(32), 1);
        progressParams.setMargins(dp(12), 0, dp(12), 0);
        header.addView(progress, progressParams);
        for (int index = 0; index < 3; index++) {
            View segment = new View(this);
            LinearLayout.LayoutParams segmentParams = new LinearLayout.LayoutParams(0, dp(5), 1);
            if (index > 0) segmentParams.setMarginStart(dp(6));
            progress.addView(segment, segmentParams);
        }

        counter = text("", 14, BLUE, Typeface.BOLD);
        counter.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(counter, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return header;
    }

    private View createFooter() {
        LinearLayout footer = vertical();
        footer.setPadding(0, dp(10), 0, dp(10));
        action = new LinearLayout(this);
        action.setGravity(Gravity.CENTER);
        action.setMinimumHeight(dp(64));
        action.setClickable(true);
        action.setFocusable(true);
        action.setBackground(primaryBackground());
        action.setOnClickListener(view -> advance());
        actionLabel = text("", 21, Color.WHITE, Typeface.BOLD);
        action.addView(actionLabel, new LinearLayout.LayoutParams(-2, -2));
        TextView arrow = text("  →", 26, Color.WHITE, Typeface.NORMAL);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        footer.addView(action, new LinearLayout.LayoutParams(-1, -2));

        later = new Button(this);
        later.setText("나중에 연습하기");
        later.setTextSize(16);
        later.setTextColor(BLUE);
        later.setAllCaps(false);
        later.setMinHeight(dp(48));
        later.setBackgroundColor(Color.TRANSPARENT);
        later.setOnClickListener(view -> finishToHome());
        footer.addView(later, new LinearLayout.LayoutParams(-1, -2));
        return footer;
    }

    private void showPage(int requested, boolean animate) {
        page = Math.max(0, Math.min(2, requested));
        body.animate().cancel();
        body.removeAllViews();
        counter.setText(getString(R.string.overlay_tutorial_progress, page + 1));
        for (int index = 0; index < progress.getChildCount(); index++) {
            progress.getChildAt(index).setBackground(rounded(index <= page ? BLUE : 0xFFDCE5F3, 4));
        }
        action.setAlpha(1f);
        action.setEnabled(true);
        later.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        if (page == 0) floatingPage();
        else if (page == 1) movePage();
        else checklistPage();
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            body.setAlpha(.2f);
            body.setTranslationY(dp(8));
            body.animate().alpha(1f).translationY(0).setDuration(190L).start();
        }
        body.requestFocus();
    }

    private void floatingPage() {
        addTitle("화면 위에\nClick이 떠 있어요");
        addDescription("다른 앱을 사용하면서도 파란 Click 버튼을 눌러 바로 도움을 요청할 수 있어요.");
        FloatingPracticeView artwork = new FloatingPracticeView(this, false);
        artwork.setContentDescription("화면 오른쪽에 떠 있는 Click 버튼. 버튼을 누르면 도움 메뉴가 펼쳐집니다.");
        add(body, artwork, 24, 420);
        TextView hint = hint("파란 버튼을 눌러 펼쳐지는 모습을 확인해 보세요");
        hint.setGravity(Gravity.CENTER);
        add(body, hint, 14);
        actionLabel.setText("다음");
        action.setContentDescription("다음");
    }

    private void movePage() {
        addTitle("꾹 누르면 숨기고\n드래그하면 옮겨요");
        addDescription("버튼을 길게 누르면 바로 사라져요. 위치를 바꾸고 싶을 때는 누른 채 바로 움직여 주세요.");
        TextView status = hint("버튼을 꾹 눌러 숨겨 보세요");
        status.setGravity(Gravity.CENTER);
        FloatingPracticeView artwork = new FloatingPracticeView(this, true);
        artwork.setContentDescription("길게 눌러 숨기거나 드래그해서 옮기는 Click 버튼 연습 영역");
        artwork.setDragListener(completed -> status.setText(completed
                ? "잘했어요! 버튼을 숨겼어요. 다시 꺼내 연습할 수 있어요."
                : "버튼을 옮겼어요. 꾹 누르면 바로 숨길 수 있어요."));
        add(body, artwork, 24, 430);
        add(body, status, 12);
        actionLabel.setText("다음");
        action.setContentDescription("다음");
    }

    private void checklistPage() {
        addTitle("시작 전에\n요청을 확인해 주세요");
        addDescription("체크리스트에서 대상, 적용 범위, 변경 내용을 확인해요. 빠진 내용은 먼저 물어볼게요.");

        LinearLayout card = vertical();
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setBackground(rounded(PALE_BLUE, 24));
        CheckCircleDrawable targetCheck = addChecklistRow(card, "대상 · 휴대폰 설정", checklistConfirmed);
        CheckCircleDrawable scopeCheck = addChecklistRow(card, "적용 범위 · 휴대폰 기본 글자", checklistConfirmed);
        CheckCircleDrawable finalCheck = addChecklistRow(card, "변경 내용 · 글자를 더 크게", checklistConfirmed);
        add(body, card, 24);

        LinearLayout prompt = vertical();
        prompt.setPadding(dp(18), dp(18), dp(18), dp(18));
        prompt.setBackground(outlined(Color.WHITE, 0xFFD9E5F7, 22));
        TextView question = text(checklistConfirmed
                        ? getString(R.string.overlay_tutorial_confirmed_title)
                        : "요청하신 내용이 맞나요?",
                20, INK, Typeface.BOLD);
        question.setGravity(Gravity.CENTER);
        prompt.addView(question, new LinearLayout.LayoutParams(-1, -2));
        TextView feedback = text(checklistConfirmed
                ? "확인한 요청으로 안내를 시작할 수 있어요."
                : "내용이 다르면 수정한 뒤 시작해요.", 15, SECONDARY, Typeface.NORMAL);
        feedback.setGravity(Gravity.CENTER);
        add(prompt, feedback, 8);

        LinearLayout decisions = new LinearLayout(this);
        decisions.setGravity(Gravity.CENTER);
        Button retry = choiceButton("내용 수정", false);
        Button accept = choiceButton("이 내용이 맞아요", true);
        decisions.addView(retry, new LinearLayout.LayoutParams(0, dp(56), 1));
        LinearLayout.LayoutParams acceptParams = new LinearLayout.LayoutParams(0, dp(56), 1);
        acceptParams.setMarginStart(dp(8));
        decisions.addView(accept, acceptParams);
        add(prompt, decisions, 16);
        add(body, prompt, 14);

        if (!checklistConfirmed) {
            action.setEnabled(false);
            action.setAlpha(.42f);
            actionLabel.setText("확인 후 시작하기");
            accept.setOnClickListener(view -> {
                checklistConfirmed = true;
                accept.setEnabled(false);
                retry.setEnabled(false);
                ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(420L);
                animator.addUpdateListener(value -> {
                    float progress = (float) value.getAnimatedValue();
                    targetCheck.setProgress(progress); scopeCheck.setProgress(progress); finalCheck.setProgress(progress);
                });
                animator.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        question.setText(R.string.overlay_tutorial_confirmed_title);
                        feedback.setText(R.string.overlay_tutorial_confirmed_description);
                        action.setEnabled(true);
                        action.animate().alpha(1f).setDuration(180L).start();
                        actionLabel.setText(R.string.overlay_tutorial_start);
                        card.announceForAccessibility("요청 내용을 확인했어요");
                    }
                });
                animator.start();
            });
            retry.setOnClickListener(view -> feedback.setText(R.string.overlay_tutorial_retry_description));
        } else {
            actionLabel.setText(R.string.overlay_tutorial_start);
            retry.setEnabled(false);
            accept.setEnabled(false);
        }
        action.setContentDescription(getString(R.string.overlay_tutorial_start));
    }

    private CheckCircleDrawable addChecklistRow(LinearLayout card, String label, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        CheckCircleDrawable check = new CheckCircleDrawable();
        check.setProgress(checked ? 1f : 0f);
        TextView icon = new TextView(this);
        icon.setBackground(check);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView text = text(label, 18, checked ? INK : SECONDARY,
                checked ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.setMarginStart(dp(13));
        row.addView(text, textParams);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return check;
    }

    private Button choiceButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(getResources().getFont(
                primary ? com.click.designpreview.R.font.pretendard_semibold
                        : com.click.designpreview.R.font.pretendard_medium));
        button.setTextColor(primary ? Color.WHITE : BLUE_DARK);
        button.setAllCaps(false);
        button.setMinHeight(dp(54));
        button.setBackground(primary ? primaryBackground() : outlined(Color.WHITE, BLUE, 16));
        return button;
    }

    private void advance() {
        if (page < 2) showPage(page + 1, true);
        else if (checklistConfirmed) finishToHome();
    }

    private void goBack() {
        if (page > 0) showPage(page - 1, true);
        else finish();
    }

    private void finishToHome() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(PAGE_STATE, page);
        outState.putBoolean(CONFIRMED_STATE, checklistConfirmed);
        super.onSaveInstanceState(outState);
    }

    private void addTitle(String value) {
        TextView title = text(value, 30, INK, Typeface.BOLD);
        title.setLineSpacing(dp(3), 1.05f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addDescription(String value) {
        TextView description = text(value, 17, SECONDARY, Typeface.NORMAL);
        description.setLineSpacing(dp(3), 1.15f);
        add(body, description, 15);
    }

    private TextView hint(String value) {
        TextView hint = text(value, 15, SECONDARY, Typeface.NORMAL);
        hint.setPadding(dp(14), dp(12), dp(14), dp(12));
        hint.setBackground(rounded(PALE_BLUE, 16));
        return hint;
    }

    private TextView text(String value, int size, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        int font = style == Typeface.BOLD
                ? com.click.designpreview.R.font.pretendard_bold
                : com.click.designpreview.R.font.pretendard_medium;
        text.setTypeface(getResources().getFont(font));
        text.setIncludeFontPadding(false);
        return text;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private void add(LinearLayout parent, View child, int topMargin, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private Drawable primaryBackground() {
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF3979F6, 0xFF4B89FA});
        fill.setCornerRadius(dp(20));
        return new RippleDrawable(ColorStateList.valueOf(0x32FFFFFF), fill, rounded(Color.WHITE, 20));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private GradientDrawable outlined(int color, int stroke, int radius) {
        GradientDrawable shape = rounded(color, radius);
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class CheckCircleDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mark = new Path();
        private float progress;

        void setProgress(float value) {
            progress = Math.max(0f, Math.min(1f, value));
            invalidateSelf();
        }

        @Override public void draw(@NonNull Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float radius = Math.min(bounds.width(), bounds.height()) / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(progress > 0f ? BLUE : 0xFFDCE5F3);
            canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, paint);
            if (progress <= .15f) return;
            float visible = Math.min(1f, (progress - .15f) / .85f);
            mark.reset();
            float left = bounds.left + bounds.width() * .28f;
            float middleX = bounds.left + bounds.width() * .44f;
            float middleY = bounds.top + bounds.height() * .62f;
            float right = bounds.left + bounds.width() * .74f;
            float rightY = bounds.top + bounds.height() * .36f;
            mark.moveTo(left, bounds.top + bounds.height() * .52f);
            mark.lineTo(middleX, middleY);
            if (visible > .55f) mark.lineTo(right, rightY);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, bounds.width() * .09f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.WHITE);
            canvas.drawPath(mark, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static final class FloatingPracticeView extends FrameLayout {
        interface DragListener { void onDragState(boolean hidden); }
        private DragListener dragListener;

        FloatingPracticeView(android.content.Context context, boolean dragPractice) {
            super(context);
            setBackground(com.click.ui.common.ClickUi.rounded(PALE_BLUE, dp(28)));
            TextView backdrop = new TextView(context);
            backdrop.setText("다른 앱을 사용하는 화면");
            backdrop.setTextSize(16);
            backdrop.setTextColor(SECONDARY);
            backdrop.setGravity(Gravity.CENTER);
            backdrop.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
            backdrop.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

            Button restore = com.click.ui.common.ClickUi.secondaryButton(context, "버튼 다시 보기");
            restore.setVisibility(GONE);
            FrameLayout.LayoutParams restoreParams = new FrameLayout.LayoutParams(dp(190), dp(56), Gravity.CENTER);
            addView(restore, restoreParams);
            com.click.ui.overlay.FloatingClickHost host = new com.click.ui.overlay.FloatingClickHost(context,
                    new com.click.ui.overlay.FloatingClickHost.Listener() {
                @Override public void onVoiceRequested() {
                    android.widget.Toast.makeText(context, "실제 화면에서는 마이크로 도움을 요청할 수 있어요.",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                @Override public void onTextSubmitted(String text) {
                    android.widget.Toast.makeText(context, "이렇게 글로 도움을 요청할 수 있어요.",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                @Override public void onHidden() {
                    restore.setVisibility(VISIBLE);
                    if (dragListener != null) dragListener.onDragState(true);
                }
                @Override public void onMoved() {
                    if (dragListener != null) dragListener.onDragState(false);
                }
            });
            addView(host, new FrameLayout.LayoutParams(-1, -1));
            restore.setOnClickListener(view -> { restore.setVisibility(GONE); host.showAgain(); });
        }
        void setDragListener(DragListener listener) { dragListener = listener; }
        private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    }
}
