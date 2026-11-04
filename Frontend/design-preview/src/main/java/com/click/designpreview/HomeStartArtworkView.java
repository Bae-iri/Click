package com.click.designpreview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** The existing soft hand taps an instruction card, as in the home reference. */
public final class HomeStartArtworkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable hand;
    private final LinearGradient cardFill = new LinearGradient(10, 40, 115, 185,
            Color.WHITE, 0xFFF4F8FF, Shader.TileMode.CLAMP);
    private final LinearGradient lineFill = new LinearGradient(20, 60, 90, 120,
            0xFFC6DDFF, 0xFFA7CAFF, Shader.TileMode.CLAMP);
    private ValueAnimator animation;
    private boolean animationActive;
    private float press;

    public HomeStartArtworkView(Context context) {
        super(context);
        hand = context.getDrawable(R.drawable.permission_touch_hand);
        if (hand instanceof BitmapDrawable) ((BitmapDrawable) hand).setFilterBitmap(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public HomeStartArtworkView(Context context, AttributeSet attrs) { this(context); }

    public void setAnimationActive(boolean active) { animationActive = active; syncAnimation(); }

    private void syncAnimation() {
        if (!animationActive || !isAttachedToWindow() || !isShown() || !hasWindowFocus()
                || getWindowVisibility() != VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
            stopAnimation(); return;
        }
        if (animation != null) return;
        animation = ValueAnimator.ofFloat(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f);
        animation.setDuration(2800L);
        animation.setInterpolator(new LinearInterpolator());
        animation.setRepeatCount(ValueAnimator.INFINITE);
        animation.addUpdateListener(value -> { press = (float) value.getAnimatedValue(); invalidate(); });
        animation.start();
    }

    private void stopAnimation() {
        if (animation != null) { animation.cancel(); animation = null; }
        press = 0f; invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 180f, getHeight() / 210f);
        canvas.save();
        canvas.translate((getWidth() - 180 * scale) / 2, (getHeight() - 210 * scale) / 2);
        canvas.scale(scale, scale);
        canvas.save();
        canvas.rotate(-12, 62, 117);
        canvas.scale(1f - .02f * press, 1f - .02f * press, 62, 117);
        paint.setShader(cardFill); paint.setShadowLayer(8, 1, 6, 0x253B7AD7);
        canvas.drawRoundRect(10, 44, 112, 184, 22, 22, paint);
        paint.clearShadowLayer(); paint.setShader(lineFill);
        paint.setShadowLayer(1.5f, 0, 1, 0x243478F6);
        canvas.drawRoundRect(28, 67, 88, 80, 6.5f, 6.5f, paint);
        canvas.drawRoundRect(28, 91, 88, 102, 5.5f, 5.5f, paint);
        canvas.drawRoundRect(28, 112, 78, 123, 5.5f, 5.5f, paint);
        paint.clearShadowLayer(); paint.setShader(null); canvas.restore();
        paint.setColor(0xFF3478FF); paint.setAlpha(205 + Math.round(50 * press));
        paint.setStrokeWidth(6); paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(115, 48, 122, 62, paint);
        canvas.drawLine(141, 43, 138, 59, paint);
        canvas.drawLine(161, 58, 150, 68, paint);
        paint.setAlpha(255);
        canvas.save();
        canvas.translate(78 - 4 * press, 84 - 4 * press);
        canvas.scale(100f / 904f, 100f / 904f);
        canvas.clipRect(0, 0, 904, 1003); canvas.translate(-216, -138);
        hand.setBounds(0, 0, 1254, 1254); hand.draw(canvas);
        canvas.restore(); canvas.restore();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); syncAnimation();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); syncAnimation();
    }
    @Override protected void onDetachedFromWindow() {
        stopAnimation(); super.onDetachedFromWindow();
    }
}
