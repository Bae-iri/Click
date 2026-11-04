package com.click.designpreview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** A light document mark echoes the request card on the home screen. */
public final class SplashDocumentArtworkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private ValueAnimator animator;
    private boolean animationActive;
    private float phase;

    public SplashDocumentArtworkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setAnimationActive(boolean active) {
        animationActive = active;
        syncAnimation();
    }

    private void syncAnimation() {
        if (!animationActive || !isAttachedToWindow() || !isShown() || !hasWindowFocus()
                || !ValueAnimator.areAnimatorsEnabled()) {
            if (animator != null) animator.cancel();
            animator = null;
            phase = 0f;
            invalidate();
            return;
        }
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2400L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> { phase = (float) value.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); syncAnimation(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); syncAnimation(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); syncAnimation(); }
    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        animator = null;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 160f, getHeight() / 112f);
        float bob = (float) Math.sin(phase * Math.PI * 2) * 2f;
        canvas.save();
        canvas.translate((getWidth() - 160 * scale) / 2f, (getHeight() - 112 * scale) / 2f + bob * scale);
        canvas.scale(scale, scale);

        paint.setColor(0x1A3478F6);
        canvas.drawOval(35, 96, 126, 105, paint);
        paint.setColor(0xFFE6EFFF);
        canvas.drawRoundRect(48, 8, 114, 94, 15, 15, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(40, 14, 106, 92, 14, 14, paint);

        paint.setColor(0xFF17264D);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(25);
        canvas.drawText("가", 51, 57, paint);
        paint.setTextSize(17);
        paint.setColor(0xFF8495B4);
        canvas.drawText("가", 73, 56, paint);

        paint.setColor(0xFFD8E2F2);
        canvas.drawRoundRect(51, 70, 94, 76, 3, 3, paint);
        paint.setColor(0xFF3478F6);
        canvas.drawRoundRect(51, 70, 80 + 2 * (float) Math.sin(phase * Math.PI), 76, 3, 3, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(81 + 2 * (float) Math.sin(phase * Math.PI), 73, 6, paint);
        paint.setColor(0xFF3478F6);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f);
        canvas.drawCircle(81 + 2 * (float) Math.sin(phase * Math.PI), 73, 5.5f, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(0xFF3478F6);
        paint.setStrokeWidth(3.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(117, 38, 18, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(12);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("가+", 117, 42, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.restore();
    }
}
