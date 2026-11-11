package com.click.ui.tutorial;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import com.click.ui.overlay.TaskFlowOverlayView;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.TrashDropView;

/** Hosts the production floating UI locally so tutorial input never starts the agent service. */
public final class TutorialFloatingHost extends FrameLayout implements ClickOverlayLayout.Callback {
    public static final int HIGHLIGHT = 0xFF3478F6;
    public interface Listener {
        void onModeChanged(ClickOverlayLayout.Mode mode);
        void onTextSubmitted(String text);
        void onMicrophoneClicked();
        void onMicrophonePressed();
        void onMicrophoneReleased();
        void onMicrophoneCancelled();
        void onStopRequested();
        void onHidden();
        void onMoved();
    }
    private final ClickOverlayLayout control;
    private final TrashDropView trash;
    private final Listener listener;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator highlightPulse, outlinePulse;
    private boolean clickHighlighted, dragging;
    private float pulseProgress;
    private boolean positioned;
    private boolean exampleAtBottom;
    private float dragStartX, dragStartY;
    private boolean hidden;
    private String highlightedLabel;
    private int highlightedId;
    private View explicitHighlightTarget;
    private TaskFlowOverlayView review;
    private View outlinedTarget;
    private final GradientDrawable contrastOutline = new GradientDrawable();
    private final GradientDrawable accentOutline = new GradientDrawable();
    private final LayerDrawable targetOutline = new LayerDrawable(new Drawable[]{contrastOutline, accentOutline});
    private View outlineHost;
    private ViewTreeObserver outlineObserver;
    private final int[] hostPosition = new int[2];
    private final Outline buttonOutline = new Outline();
    private final Rect visibleTarget = new Rect();
    private final ViewTreeObserver.OnPreDrawListener outlinePosition = () -> {
        updateOutlineBounds();
        return true;
    };

    public TutorialFloatingHost(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClipChildren(true);
        setClipToPadding(true);
        setPadding(dp(12), dp(12), dp(12), dp(12));
        trash = new TrashDropView(context);
        trash.setVisibility(GONE);
        trash.setContentDescription("클루를 집으로 보내 안내 닫기");
        LayoutParams trashParams = new LayoutParams(dp(96), dp(96), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        trashParams.bottomMargin = dp(TrashDropView.BOTTOM_MARGIN_DP);
        addView(trash, trashParams);
        control = new ClickOverlayLayout(context);
        control.setCallback(this);
        addView(control, new LayoutParams(control.getWindowWidth(ClickOverlayLayout.Mode.COLLAPSED,
                getResources().getDisplayMetrics().widthPixels), dp(64)));
    }

    public ClickOverlayLayout getControl() { return control; }
    public boolean isHidden() { return hidden; }
    public void positionExampleAtBottom() { exampleAtBottom = true; requestLayout(); }
    public void setClickHighlighted(boolean highlighted) {
        if (clickHighlighted == highlighted) return;
        clickHighlighted = highlighted;
        updateHighlight();
    }

    public void highlight(String label, int id) {
        explicitHighlightTarget = null;
        highlightedLabel = label; highlightedId = id; refreshTargetHighlight();
        post(this::refreshTargetHighlight);
    }
    public void highlight(View target) {
        explicitHighlightTarget = target;
        highlightedLabel = null; highlightedId = 0;
        refreshTargetHighlight();
        post(this::refreshTargetHighlight);
    }
    public void showReview(TaskFlowOverlayView view) {
        if (review != null) removeView(review);
        review = view; review.setVisibility(VISIBLE); control.setVisibility(GONE);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int availableWidth = getWidth() > 0 ? contentWidth() : screenWidth - dp(68);
        int panelWidth = Math.min(availableWidth, view.getWindowWidth(screenWidth));
        addView(view, new LayoutParams(panelWidth, -2, Gravity.CENTER));
        updateHighlight(); requestLayout();
    }
    public void collapseReview() {
        if (review == null) return;
        if (review.hasCompactContent()) {
            review.setMinimizedProgress(true);
            control.collapse();
            control.setVisibility(VISIBLE);
            updateHighlight(); requestLayout();
            return;
        }
        review.setVisibility(GONE);
        control.collapse(); control.setVisibility(VISIBLE);
        updateHighlight(); requestLayout();
    }
    @Override public boolean onOpenRequested() {
        if (review == null || (review.getVisibility() != GONE && !review.isMinimizedProgress())) return false;
        expandReview();
        listener.onModeChanged(control.getMode());
        return true;
    }
    public void expandReview() {
        if (review == null) return;
        review.setMinimizedProgress(false);
        review.setVisibility(VISIBLE);
        control.setVisibility(GONE);
        updateHighlight(); requestLayout();
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
            View button = root;
            while (!button.isClickable() && button != control && button != review
                    && button.getParent() instanceof View) button = (View) button.getParent();
            return button.isClickable() ? button : null;
        }
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = highlightTarget(((ViewGroup) root).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private void refreshTargetHighlight() {
        View visiblePanel = review != null && review.getVisibility() == VISIBLE ? review : control;
        View target = explicitHighlightTarget != null ? explicitHighlightTarget
                : dragging || hidden ? null : highlightTarget(visiblePanel);
        if (target != null && (!target.isShown() || !target.isEnabled())) target = null;
        if (outlinedTarget != target) {
            stopOutlinePulse();
            removeTargetOutline();
            outlinedTarget = target;
            if (target != null) {
                contrastOutline.setColor(Color.TRANSPARENT);
                contrastOutline.setStroke(dp(9), Color.WHITE);
                accentOutline.setColor(Color.TRANSPARENT);
                accentOutline.setStroke(dp(4), HIGHLIGHT);
                targetOutline.setLayerInset(1, dp(2), dp(2), dp(2), dp(2));
                // Practice highlights stay inside the phone screen as panels move.
                outlineHost = this;
                outlineHost.getOverlay().add(targetOutline);
                outlineObserver = outlineHost.getViewTreeObserver();
                outlineObserver.addOnPreDrawListener(outlinePosition);
            }
        }
        updateOutlineBounds();
        updateOutlinePulse();
    }
    private void updateOutlinePulse() {
        boolean animate = outlinedTarget != null && isAttachedToWindow() && hasWindowFocus()
                && getWindowVisibility() == VISIBLE && outlinedTarget.isShown()
                && !dragging && ValueAnimator.areAnimatorsEnabled();
        if (!animate) { stopOutlinePulse(); return; }
        if (outlinePulse != null) return;
        // A visible blue border remains between the brighter beats.
        outlinePulse = ValueAnimator.ofFloat(1f, 0f);
        outlinePulse.setDuration(650);
        outlinePulse.setRepeatMode(ValueAnimator.REVERSE);
        outlinePulse.setRepeatCount(ValueAnimator.INFINITE);
        outlinePulse.addUpdateListener(animation -> {
            float brightness = (float) animation.getAnimatedValue();
            accentOutline.setStroke(dp(3) + Math.round(dp(2) * brightness), HIGHLIGHT);
            accentOutline.setAlpha(Math.round(120 + 135 * brightness));
        });
        outlinePulse.start();
    }
    private void stopOutlinePulse() {
        if (outlinePulse != null) outlinePulse.cancel();
        outlinePulse = null;
        accentOutline.setStroke(dp(4), HIGHLIGHT);
        accentOutline.setColor(Color.TRANSPARENT);
        accentOutline.setAlpha(255);
    }
    private void removeTargetOutline() {
        if (outlineHost != null) outlineHost.getOverlay().remove(targetOutline);
        if (outlineObserver != null && outlineObserver.isAlive()) {
            outlineObserver.removeOnPreDrawListener(outlinePosition);
        }
        outlineHost = null;
        outlineObserver = null;
    }
    private void updateOutlineBounds() {
        if (outlinedTarget == null || outlineHost == null) return;
        int width = outlinedTarget.getWidth(), height = outlinedTarget.getHeight();
        boolean visible = outlinedTarget.isShown() && outlinedTarget.isEnabled()
                && width > dp(2) && height > dp(2) && !dragging
                && outlinedTarget.getGlobalVisibleRect(visibleTarget);
        targetOutline.setVisible(visible, false);
        if (!visible) return;
        // Recompute before drawing so parent scrolling, translations and keyboard
        // relayout all move the external ring with the target in the same frame.
        outlineHost.getLocationOnScreen(hostPosition);
        int left = visibleTarget.left - hostPosition[0];
        int top = visibleTarget.top - hostPosition[1];
        width = visibleTarget.width(); height = visibleTarget.height();
        int outset = dp(10);
        targetOutline.setBounds(Math.max(0, left - outset), Math.max(0, top - outset),
                Math.min(getWidth(), left + width + outset), Math.min(getHeight(), top + height + outset));
        buttonOutline.setEmpty();
        if (outlinedTarget.getOutlineProvider() != null) {
            outlinedTarget.getOutlineProvider().getOutline(outlinedTarget, buttonOutline);
        }
        float radius = buttonOutline.getRadius() >= 0 ? buttonOutline.getRadius() : dp(18);
        if (outlinedTarget instanceof com.click.ui.overlay.VoiceCaptureIndicator)
            radius = Math.min(width, height) / 2f;
        // Transparent fills and an outer stroke leave the button's content intact.
        if (contrastOutline.getCornerRadius() != radius + outset) {
            contrastOutline.setCornerRadius(radius + outset);
            accentOutline.setCornerRadius(radius + outset - dp(2));
        }
    }

    private boolean highlightVisible() {
        return (review == null || review.getVisibility() != VISIBLE) && clickHighlighted && !hidden && !dragging
                && control.getMode() == ClickOverlayLayout.Mode.COLLAPSED;
    }

    private void updateHighlight() {
        refreshTargetHighlight();
        boolean visible = highlightVisible();
        if (visible && isShown() && isAttachedToWindow() && hasWindowFocus() && getWindowVisibility() == VISIBLE
                && ValueAnimator.areAnimatorsEnabled()) {
            if (highlightPulse == null) {
                highlightPulse = ValueAnimator.ofFloat(0f, 1f);
                highlightPulse.setDuration(1100);
                highlightPulse.setRepeatCount(ValueAnimator.INFINITE);
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

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::updateHighlight);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (control != null) updateHighlight();
    }

    @Override protected void onDetachedFromWindow() {
        stopOutlinePulse();
        stopPulse();
        removeTargetOutline();
        outlinedTarget = null;
        super.onDetachedFromWindow();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (exampleAtBottom && getHeight() > 0) {
            control.setX(Math.max(getPaddingLeft(), getWidth() - control.getWidth() - getPaddingRight()));
            control.setY(Math.max(getPaddingTop(), getHeight() - control.getHeight() - getPaddingBottom()));
            exampleAtBottom = false;
        }
        refreshTargetHighlight();
        constrain(control.getX(), control.getY());
        positionReview();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (highlightVisible()) {
            float cx = control.getX() + dp(32), cy = control.getY() + dp(32);
            float brightness = (float) Math.sin(Math.PI * pulseProgress);
            highlightPaint.setStyle(Paint.Style.FILL);
            highlightPaint.setColor(HIGHLIGHT);
            highlightPaint.setAlpha(Math.round(24 + 32 * brightness));
            canvas.drawCircle(cx, cy, dp(42), highlightPaint);
            highlightPaint.setStyle(Paint.Style.STROKE);
            highlightPaint.setStrokeWidth(dp(4));
            highlightPaint.setColor(HIGHLIGHT);
            highlightPaint.setAlpha(Math.round(200 * (1f - pulseProgress)));
            canvas.drawCircle(cx, cy, dp(37) + dp(9) * pulseProgress, highlightPaint);
            highlightPaint.setColor(Color.WHITE);
            highlightPaint.setStrokeWidth(dp(9));
            canvas.drawCircle(cx, cy, dp(35), highlightPaint);
            highlightPaint.setColor(HIGHLIGHT);
            highlightPaint.setAlpha(Math.round(180 + 75 * brightness));
            highlightPaint.setStrokeWidth(dp(3) + dp(2) * brightness);
            canvas.drawCircle(cx, cy, dp(35), highlightPaint);
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
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? 0 : MeasureSpec.getSize(heightSpec);
        int availableWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int availableHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
        LayoutParams params = (LayoutParams) control.getLayoutParams();
        params.width = Math.max(0, Math.min(availableWidth, control.getWindowWidth(control.getMode(), width)));
        params.height = Math.min(availableHeight,
                control.getWindowHeight(control.getMode(), params.width, availableHeight));
        if (review != null) {
            review.setAvailableHeight(availableHeight);
            if (review.getVisibility() == VISIBLE) {
                int panelWidth = Math.min(availableWidth, review.getWindowWidth(width));
                review.getLayoutParams().width = panelWidth;
                review.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST));
            }
        }
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    private void positionReview() {
        if (review == null || review.getVisibility() != VISIBLE) return;
        if (!review.isMinimizedProgress()) {
            review.setTranslationX(0); review.setTranslationY(0);
            return;
        }
        float centerX = control.getX() + control.getClickCenterX(control.getMode(), control.getWidth());
        float bubbleLeft = Math.max(getPaddingLeft(), Math.min(centerX - review.getWidth() / 2f,
                getWidth() - getPaddingRight() - review.getWidth()));
        float bubbleTop = control.getY() - review.getHeight() - dp(10);
        boolean below = bubbleTop < getPaddingTop();
        if (below) bubbleTop = control.getY() + control.getHeight() + dp(10);
        bubbleTop = Math.max(getPaddingTop(), Math.min(bubbleTop, getHeight() - getPaddingBottom() - review.getHeight()));
        review.setCompactAnchor(centerX - bubbleLeft, below);
        review.setX(bubbleLeft); review.setY(bubbleTop);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        setClipBounds(new Rect(0, 0, w, h));
        if (!positioned && w > 0 && h > 0) {
            control.setX(Math.max(getPaddingLeft(), w - control.getLayoutParams().width - getPaddingRight()));
            control.setY(Math.max(getPaddingTop(), h - control.getLayoutParams().height - getPaddingBottom()));
            positioned = true;
        } else constrain(control.getX(), control.getY());
    }

    private void resize() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        LayoutParams params = (LayoutParams) control.getLayoutParams();
        params.width = Math.max(0, Math.min(contentWidth(), control.getWindowWidth(control.getMode(), getWidth())));
        params.height = Math.min(contentHeight(), control.getWindowHeight(control.getMode(), params.width, contentHeight()));
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

    @Override public void onContentSizeChanged() {
        resize(); constrain(control.getX(), control.getY()); updateHighlight();
    }
    @Override public void onTextSubmitted(String text) { listener.onTextSubmitted(text); }
    @Override public void onMicrophoneClicked() { listener.onMicrophoneClicked(); }
    @Override public void onMicrophonePressed() { listener.onMicrophonePressed(); }
    @Override public void onMicrophoneReleased() { listener.onMicrophoneReleased(); }
    @Override public void onMicrophoneCancelled() { listener.onMicrophoneCancelled(); }
    @Override public void onStopRequested() { listener.onStopRequested(); }
    @Override public void onDragStarted() {
        dragging = true;
        updateHighlight();
        control.animate().cancel();
        control.collapse();
        dragStartX = control.getX(); dragStartY = control.getY();
        trash.setActive(false);
        trash.setVisibility(VISIBLE);
        control.bringToFront();
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
        if (review != null) review.setVisibility(GONE);
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
        control.setX(Math.max(getPaddingLeft(), Math.min(x, getWidth() - getPaddingRight() - control.getLayoutParams().width)));
        control.setY(Math.max(getPaddingTop(), Math.min(y, getHeight() - getPaddingBottom() - control.getLayoutParams().height)));
        positionReview(); invalidate();
    }
    private void snap() {
        float right = Math.max(getPaddingLeft(), getWidth() - getPaddingRight() - control.getLayoutParams().width);
        control.animate().x(control.getX() < getWidth() / 2f ? getPaddingLeft() : right).setDuration(180)
                .setUpdateListener(animation -> { positionReview(); invalidate(); }).start();
    }
    private int contentWidth() { return Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight()); }
    private int contentHeight() { return Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom()); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
