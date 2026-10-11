package com.click.ui.tutorial;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import com.click.ui.overlay.TaskFlowOverlayView;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.click.R;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.TrashDropView;

/** Hosts the production floating UI locally so tutorial input never starts the agent service. */
public final class TutorialFloatingHost extends FrameLayout implements ClickOverlayLayout.Callback {
    public static final int HIGHLIGHT = 0xFF3478F6;
    private static final int HINT_BACKGROUND = 0xFF2868E8;
    public interface Listener {
        void onModeChanged(ClickOverlayLayout.Mode mode);
        void onTextSubmitted(String text);
        void onMicrophoneClicked();
        void onStopRequested();
        void onHidden();
        void onMoved();
    }
    private final ClickOverlayLayout control;
    private final TrashDropView trash;
    private final Listener listener;
    private final TextView tapHint;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hintPointer = new Path();
    private ValueAnimator highlightPulse, outlinePulse;
    private boolean clickHighlighted, dragging;
    private float pulseProgress;
    private boolean positioned;
    private float dragStartX, dragStartY;
    private boolean hidden;
    private String highlightedLabel;
    private int highlightedId;
    private TaskFlowOverlayView review;
    private View outlinedTarget;
    private final GradientDrawable contrastOutline = new GradientDrawable();
    private final GradientDrawable accentOutline = new GradientDrawable();
    private final LayerDrawable targetOutline = new LayerDrawable(new Drawable[]{contrastOutline, accentOutline});
    private final OnLayoutChangeListener outlineLayout = (v, l, t, r, b, ol, ot, or, ob) -> updateOutlineBounds();

    public TutorialFloatingHost(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClipChildren(false);
        trash = new TrashDropView(context);
        trash.setVisibility(GONE);
        trash.setContentDescription("여기에 놓으면 click 숨기기");
        LayoutParams trashParams = new LayoutParams(dp(96), dp(96), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        trashParams.bottomMargin = dp(12);
        addView(trash, trashParams);
        tapHint = ClickUi.body(context, context.getString(R.string.tutorial_click_hint));
        tapHint.setTextColor(Color.WHITE);
        tapHint.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        tapHint.setGravity(Gravity.CENTER);
        tapHint.setPadding(dp(16), dp(11), dp(16), dp(11));
        tapHint.setBackground(ClickUi.rounded(HINT_BACKGROUND, dp(18)));
        tapHint.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        tapHint.setVisibility(GONE);
        addView(tapHint, new LayoutParams(-2, -2));
        control = new ClickOverlayLayout(context);
        control.setConversationPlaceholder("궁금한 내용을 아래에 적어보세요.");
        control.setCallback(this);
        addView(control, new LayoutParams(dp(64), dp(64)));
    }

    public ClickOverlayLayout getControl() { return control; }
    public boolean isHidden() { return hidden; }
    public void setHint(String hint) { tapHint.setText(hint); }
    public void setClickHighlighted(boolean highlighted) {
        if (clickHighlighted == highlighted) return;
        clickHighlighted = highlighted;
        updateHighlight();
    }

    public void highlight(String label, int id) {
        highlightedLabel = label; highlightedId = id; refreshTargetHighlight();
        post(this::refreshTargetHighlight);
    }
    public void showReview(TaskFlowOverlayView view) {
        if (review != null) removeView(review);
        review = view; review.setVisibility(VISIBLE); control.setVisibility(GONE);
        addView(view, new LayoutParams(-1, -2, Gravity.CENTER));
        updateHighlight(); requestLayout();
    }
    public void collapseReview() {
        if (review == null) return;
        review.setVisibility(GONE);
        control.collapse(); control.setVisibility(VISIBLE);
        updateHighlight(); requestLayout();
    }
    @Override public boolean onOpenRequested() {
        if (review == null || review.getVisibility() != GONE) return false;
        review.setVisibility(VISIBLE); control.setVisibility(GONE);
        updateHighlight(); requestLayout();
        return true;
    }
    public void hideReview() {
        if (review != null) removeView(review);
        review = null; control.setVisibility(hidden ? GONE : VISIBLE);
        updateHighlight();
    }
    private View highlightTarget(View root) {
        if (root.getVisibility() != VISIBLE) return null;
        if (highlightedId != 0 && root.getId() == highlightedId) return root;
        if (highlightedLabel != null && root instanceof TextView
                && highlightedLabel.contentEquals(((TextView) root).getText())) {
            return root.isClickable() ? root : root.getParent() instanceof View ? (View) root.getParent() : root;
        }
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = highlightTarget(((ViewGroup) root).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private void refreshTargetHighlight() {
        View target = dragging || hidden ? null : highlightTarget(review == null ? control : review);
        if (target != null && (!target.isShown() || !target.isEnabled())) target = null;
        if (outlinedTarget != target) {
            if (outlinedTarget != null) {
                outlinedTarget.getOverlay().remove(targetOutline);
                outlinedTarget.removeOnLayoutChangeListener(outlineLayout);
            }
            outlinedTarget = target;
            if (target != null) {
                contrastOutline.setColor(Color.TRANSPARENT);
                contrastOutline.setStroke(dp(7), Color.WHITE);
                accentOutline.setColor(Color.TRANSPARENT);
                accentOutline.setStroke(dp(4), HIGHLIGHT);
                target.getOverlay().add(targetOutline);
                target.addOnLayoutChangeListener(outlineLayout);
            }
        }
        updateOutlineBounds();
        updateOutlinePulse();
    }
    private void updateOutlinePulse() {
        boolean animate = outlinedTarget != null && isAttachedToWindow() && hasWindowFocus()
                && !dragging && ValueAnimator.areAnimatorsEnabled();
        if (!animate) { stopOutlinePulse(); return; }
        if (outlinePulse != null) return;
        // Keep the outline visible at all times; gently brighten it without flashing.
        outlinePulse = ValueAnimator.ofInt(255, 185);
        outlinePulse.setDuration(1200);
        outlinePulse.setRepeatMode(ValueAnimator.REVERSE);
        outlinePulse.setRepeatCount(ValueAnimator.INFINITE);
        outlinePulse.addUpdateListener(animation -> accentOutline.setAlpha((int) animation.getAnimatedValue()));
        outlinePulse.start();
    }
    private void stopOutlinePulse() {
        if (outlinePulse != null) outlinePulse.cancel();
        outlinePulse = null;
        accentOutline.setAlpha(255);
    }
    private void updateOutlineBounds() {
        if (outlinedTarget == null) return;
        // Draw on the button itself: Android moves and clips its outline together with
        // the button, including translations, keyboard relayout and ScrollView scrolling.
        targetOutline.setBounds(dp(1), dp(1), outlinedTarget.getWidth() - dp(1), outlinedTarget.getHeight() - dp(1));
        float radius = outlinedTarget.getId() == R.id.overlay_microphone
                ? outlinedTarget.getHeight() / 2f : dp(18);
        contrastOutline.setCornerRadius(radius);
        accentOutline.setCornerRadius(radius);
    }

    private boolean highlightVisible() {
        return review == null && clickHighlighted && !hidden && !dragging
                && control.getMode() == ClickOverlayLayout.Mode.COLLAPSED;
    }

    private void updateHighlight() {
        refreshTargetHighlight();
        boolean visible = highlightVisible();
        tapHint.setVisibility(visible ? VISIBLE : GONE);
        positionHint();
        if (visible && isAttachedToWindow() && hasWindowFocus()
                && ValueAnimator.areAnimatorsEnabled()) {
            if (highlightPulse == null) {
                highlightPulse = ValueAnimator.ofFloat(0f, 1f);
                highlightPulse.setDuration(1800);
                highlightPulse.setRepeatCount(2);
                highlightPulse.addUpdateListener(animation -> {
                    pulseProgress = (float) animation.getAnimatedValue();
                    invalidate();
                });
                highlightPulse.start();
            }
        } else stopPulse();
        invalidate();
    }

    private void stopPulse() {
        if (highlightPulse != null) highlightPulse.cancel();
        highlightPulse = null;
        pulseProgress = 0f;
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateHighlight();
    }

    @Override protected void onDetachedFromWindow() {
        stopOutlinePulse();
        stopPulse();
        super.onDetachedFromWindow();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        refreshTargetHighlight();
        positionHint();
    }

    private void positionHint() {
        if (tapHint.getVisibility() != VISIBLE) return;
        float cx = control.getX() + dp(32);
        tapHint.setX(Math.max(0, Math.min(cx + dp(24) - tapHint.getMeasuredWidth(),
                getWidth() - tapHint.getMeasuredWidth())));
        tapHint.setY(Math.max(0, control.getY() - dp(24) - tapHint.getMeasuredHeight()));
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (highlightVisible()) {
            float cx = control.getX() + dp(32), cy = control.getY() + dp(32);
            highlightPaint.setStyle(Paint.Style.FILL);
            highlightPaint.setColor(0xFFE5EFFF);
            canvas.drawCircle(cx, cy, dp(42), highlightPaint);
            highlightPaint.setStyle(Paint.Style.STROKE);
            highlightPaint.setStrokeWidth(dp(4));
            highlightPaint.setColor(HIGHLIGHT);
            highlightPaint.setAlpha(Math.round(110 * (1f - pulseProgress)));
            canvas.drawCircle(cx, cy, dp(42) + dp(12) * pulseProgress, highlightPaint);
            highlightPaint.setColor(Color.WHITE);
            highlightPaint.setStrokeWidth(dp(8));
            canvas.drawCircle(cx, cy, dp(35), highlightPaint);
            highlightPaint.setColor(HIGHLIGHT);
            highlightPaint.setStrokeWidth(dp(4));
            canvas.drawCircle(cx, cy, dp(35), highlightPaint);
            highlightPaint.setStyle(Paint.Style.FILL);
            highlightPaint.setColor(HINT_BACKGROUND);
            float tailX = Math.max(tapHint.getX() + dp(14),
                    Math.min(cx, tapHint.getX() + tapHint.getWidth() - dp(14)));
            float bottom = tapHint.getY() + tapHint.getHeight();
            hintPointer.reset();
            hintPointer.moveTo(tailX - dp(8), bottom - dp(1));
            hintPointer.lineTo(tailX, bottom + dp(9));
            hintPointer.lineTo(tailX + dp(8), bottom - dp(1));
            hintPointer.close();
            canvas.drawPath(hintPointer, highlightPaint);
        }
        super.dispatchDraw(canvas);
    }

    public void showAgain() {
        hidden = false;
        control.setVisibility(VISIBLE);
        control.collapse();
        updateHighlight();
    }
    public void restoreHidden(boolean value) {
        hidden = value;
        control.setVisibility(value ? GONE : VISIBLE);
        updateHighlight();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // A restored TEXT/VOICE mode can be set before the first layout. Size it before
        // measuring the child so it is not left with the collapsed button's 64dp bounds.
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        LayoutParams params = (LayoutParams) control.getLayoutParams();
        params.width = Math.min(width, control.getWindowWidth(control.getMode(), width));
        params.height = Math.min(height, control.getWindowHeight(control.getMode()));
        if (review != null) review.setAvailableHeight(height);
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (!positioned && w > 0 && h > 0) {
            control.setX(Math.max(0, w - control.getLayoutParams().width - dp(24)));
            control.setY(Math.max(0, h - control.getLayoutParams().height - dp(28)));
            positioned = true;
        } else constrain(control.getX(), control.getY());
    }

    private void resize() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        LayoutParams params = (LayoutParams) control.getLayoutParams();
        params.width = Math.min(getWidth(), control.getWindowWidth(control.getMode(), getWidth()));
        params.height = Math.min(getHeight(), control.getWindowHeight(control.getMode()));
        control.setLayoutParams(params);
    }

    @Override public void onModeChanged(ClickOverlayLayout.Mode oldMode, ClickOverlayLayout.Mode next) {
        control.animate().cancel();
        float x = control.getX() + control.getClickCenterX(oldMode, control.getLayoutParams().width);
        float y = control.getY() + control.getClickCenterY(oldMode);
        resize();
        constrain(x - control.getClickCenterX(next, control.getLayoutParams().width), y - control.getClickCenterY(next));
        control.setVoiceInputActive(false);
        listener.onModeChanged(next);
        updateHighlight();
    }

    @Override public void onTextSubmitted(String text) { listener.onTextSubmitted(text); }
    @Override public void onMicrophoneClicked() { listener.onMicrophoneClicked(); }
    @Override public void onStopRequested() { listener.onStopRequested(); }
    @Override public void onDragStarted() {
        dragging = true;
        updateHighlight();
        control.animate().cancel();
        control.collapse();
        dragStartX = control.getX(); dragStartY = control.getY();
        trash.setActive(false);
        trash.setVisibility(VISIBLE);
    }
    @Override public void onDragBy(float x, float y) {
        constrain(control.getX() + x, control.getY() + y);
        trash.setActive(overTrash());
    }
    @Override public void onDragEnded() {
        dragging = false;
        boolean removed = overTrash();
        trash.setVisibility(GONE);
        if (removed) onCloseRequested();
        else {
            boolean moved = Math.hypot(control.getX() - dragStartX, control.getY() - dragStartY) > dp(12);
            snap(); if (moved) listener.onMoved();
        }
        updateHighlight();
    }
    @Override public void onDragCancelled() {
        dragging = false;
        trash.setVisibility(GONE);
        snap();
        updateHighlight();
    }
    @Override public void onCloseRequested() {
        hidden = true;
        control.setVisibility(GONE);
        trash.setVisibility(GONE);
        listener.onHidden();
        updateHighlight();
    }
    private boolean overTrash() {
        if (trash.getVisibility() != VISIBLE) return false;
        float cx = control.getX() + control.getClickCenterX(control.getMode(), control.getLayoutParams().width);
        float cy = control.getY() + control.getClickCenterY(control.getMode());
        return Math.hypot(cx - (trash.getX() + trash.getWidth() / 2f),
                cy - (trash.getY() + trash.getHeight() / 2f)) < dp(62);
    }
    private void constrain(float x, float y) {
        control.setX(Math.max(0, Math.min(x, getWidth() - control.getLayoutParams().width)));
        control.setY(Math.max(0, Math.min(y, getHeight() - control.getLayoutParams().height)));
        positionHint(); invalidate();
    }
    private void snap() {
        float right = Math.max(0, getWidth() - control.getLayoutParams().width);
        control.animate().x(control.getX() < right / 2f ? 0 : right).setDuration(180)
                .setUpdateListener(animation -> { positionHint(); invalidate(); })
                .withEndAction(this::positionHint).start();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
