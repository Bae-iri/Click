package com.click.designpreview;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** Spaces the supplied cursor and wordmark, with a blue accent on the i dot. */
public final class SpacedLogoView extends View {
    // The transparent row between the cursor and lettering in the 1254px source image.
    private static final float WORDMARK_START = 784f / 1254f;
    private static final float WORDMARK_SCALE = 1f;
    // Isolates the dot in the 1254px artwork, including its transparent edge pixels.
    private static final float I_DOT_LEFT = 532f / 1254f;
    private static final float I_DOT_TOP = 784f / 1254f;
    private static final float I_DOT_RIGHT = 620f / 1254f;
    private static final float I_DOT_BOTTOM = 868f / 1254f;
    private final Drawable logo;
    private final Drawable blueDotLogo;
    private final int wordmarkGap;
    private AnimatorSet clickAnimation;

    public SpacedLogoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        logo = context.getDrawable(R.drawable.logo).mutate();
        blueDotLogo = context.getDrawable(R.drawable.logo).mutate();
        blueDotLogo.setTint(context.getColor(R.color.click_blue));
        wordmarkGap = getResources().getDimensionPixelSize(R.dimen.splash_logo_wordmark_gap);
    }

    /** A single grow, press, and release after the splash entrance finishes. */
    public void playClickAnimation() {
        stopClickAnimation();
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()
                || !isShown() || !hasWindowFocus() || getWindowVisibility() != VISIBLE) {
            return;
        }
        clickAnimation = new AnimatorSet();
        clickAnimation.playSequentially(
                animateScale(1f, 1.07f, 220L),
                animateScale(1.07f, .95f, 130L),
                animateScale(.95f, 1f, 250L));
        clickAnimation.setStartDelay(120L);
        clickAnimation.start();
    }

    private ObjectAnimator animateScale(float from, float to, long duration) {
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to));
        animator.setDuration(duration);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        return animator;
    }

    private void stopClickAnimation() {
        if (clickAnimation != null) {
            clickAnimation.cancel();
            clickAnimation = null;
        }
        setScaleX(1f);
        setScaleY(1f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            stopClickAnimation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            stopClickAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopClickAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int size = Math.max(0, Math.min(getWidth(), getHeight() - wordmarkGap));
        int left = (getWidth() - size) / 2;
        int top = (getHeight() - size - wordmarkGap) / 2;
        float split = top + size * WORDMARK_START;
        logo.setBounds(left, top, left + size, top + size);
        blueDotLogo.setBounds(logo.getBounds());

        int cursorSave = canvas.save();
        canvas.clipRect(left, top, left + size, split);
        logo.draw(canvas);
        canvas.restoreToCount(cursorSave);

        int wordmarkSave = canvas.save();
        canvas.translate(0, wordmarkGap);
        // Scale the lettering from its top edge to preserve the gap below the cursor.
        canvas.scale(WORDMARK_SCALE, WORDMARK_SCALE, getWidth() / 2f, split);
        canvas.clipRect(left, split, left + size, top + size);
        float dotLeft = left + size * I_DOT_LEFT;
        float dotTop = top + size * I_DOT_TOP;
        float dotRight = left + size * I_DOT_RIGHT;
        float dotBottom = top + size * I_DOT_BOTTOM;
        int letteringSave = canvas.save();
        // Replace the original dot instead of layering over its antialiased navy edges.
        canvas.clipOutRect(dotLeft, dotTop, dotRight, dotBottom);
        logo.draw(canvas);
        canvas.restoreToCount(letteringSave);
        canvas.clipRect(dotLeft, dotTop, dotRight, dotBottom);
        blueDotLogo.draw(canvas);
        canvas.restoreToCount(wordmarkSave);
    }
}
