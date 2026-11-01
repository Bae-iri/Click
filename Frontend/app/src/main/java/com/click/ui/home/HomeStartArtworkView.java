package com.click.ui.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.click.ui.common.ClickUi;

/** A single Click mark with the permission guide's hand gently pressing it. */
public final class HomeStartArtworkView extends FrameLayout {
    private final ImageView logo;
    private final ImageView hand;
    private ValueAnimator tapAnimation;
    private boolean animationActive;

    public HomeStartArtworkView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        logo = new ImageView(context);
        logo.setImageResource(com.click.designpreview.R.drawable.ic_click_cursor);
        logo.setBackground(ClickUi.rounded(Color.WHITE, dp(52)));
        logo.setPadding(dp(11), dp(11), dp(11), dp(11));
        logo.setElevation(dp(3));
        addView(logo, position(104, 104, 36, 4));

        hand = new ImageView(context);
        hand.setImageResource(com.click.designpreview.R.drawable.permission_touch_hand);
        hand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        hand.setTranslationZ(dp(4));
        addView(hand, position(124, 124, 96, 32));
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
            logo.setScaleX(1f - .045f * press);
            logo.setScaleY(1f - .045f * press);
        });
        tapAnimation.start();
    }

    private void stopAnimation() {
        if (tapAnimation != null) {
            tapAnimation.cancel();
            tapAnimation = null;
        }
        if (hand == null || logo == null) return;
        hand.setTranslationX(0f);
        hand.setTranslationY(0f);
        logo.setScaleX(1f);
        logo.setScaleY(1f);
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
