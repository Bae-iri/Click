package com.click.ui.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.click.ui.common.ClickUi;

/** The permission guide's hand gently tapping the home start card. */
public final class HomeStartArtworkView extends FrameLayout {
    private final ImageView hand;
    private ValueAnimator tapAnimation;
    private boolean animationActive;

    public HomeStartArtworkView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        hand = new ImageView(context);
        hand.setImageResource(com.click.designpreview.R.drawable.permission_touch_hand);
        hand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(hand, position(168, 168, 34, -8));
    }

    public void setAnimationActive(boolean active) {
        animationActive = active;
        syncAnimation();
    }

    private void syncAnimation() {
        if (!animationActive || !isAttachedToWindow() || !isShown() || !hasWindowFocus()
                || getWindowVisibility() != VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
            stopAnimation();
            return;
        }
        if (tapAnimation != null) return;
        // Approach, press, release, then rest before the next tap.
        tapAnimation = ValueAnimator.ofFloat(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f);
        tapAnimation.setDuration(2800L);
        tapAnimation.setInterpolator(new LinearInterpolator());
        tapAnimation.setRepeatCount(ValueAnimator.INFINITE);
        tapAnimation.addUpdateListener(animation -> {
            float press = (float) animation.getAnimatedValue();
            hand.setTranslationX(-dp(8) * press);
            hand.setTranslationY(-dp(7) * press);
        });
        tapAnimation.start();
    }

    private void stopAnimation() {
        if (tapAnimation != null) {
            tapAnimation.cancel();
            tapAnimation = null;
        }
        if (hand == null) return;
        hand.setTranslationX(0f);
        hand.setTranslationY(0f);
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        syncAnimation();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    private LayoutParams position(int width, int height, int left, int top) {
        LayoutParams params = new LayoutParams(dp(width), dp(height));
        params.leftMargin = dp(left);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
