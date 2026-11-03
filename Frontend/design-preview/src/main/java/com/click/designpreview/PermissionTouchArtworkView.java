package com.click.designpreview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Decorative phone and soft 3D hand; all instructions remain accessible native text. */
public final class PermissionTouchArtworkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable cursor;
    private final Drawable hand;
    private ValueAnimator animation;
    private boolean animationActive;
    private float press;
    private final Shader phoneShell = new LinearGradient(48, 20, 224, 300,
            0xFFACC0DB, 0xFF607C9E, Shader.TileMode.CLAMP);
    private final Shader phoneScreen = new LinearGradient(60, 30, 204, 280,
            0xFFFFFFFF, 0xFFE6EFFF, Shader.TileMode.CLAMP);
    private final Shader tile = new LinearGradient(100, 108, 181, 182,
            0xFF66A8FF, 0xFF3478F6, Shader.TileMode.CLAMP);

    public PermissionTouchArtworkView(Context context) { this(context, null); }

    public PermissionTouchArtworkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cursor = context.getDrawable(R.drawable.ic_click_cursor).mutate();
        cursor.setTint(Color.WHITE);
        hand = context.getDrawable(R.drawable.permission_touch_hand);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
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
        if (animation != null) return;
        animation = ValueAnimator.ofFloat(0f, 0f, 1f, 0f, 0f);
        animation.setDuration(1800L);
        animation.setRepeatCount(ValueAnimator.INFINITE);
        animation.setInterpolator(new LinearInterpolator());
        animation.addUpdateListener(value -> { press = (float) value.getAnimatedValue(); invalidate(); });
        animation.start();
    }

    private void stopAnimation() {
        if (animation != null) { animation.cancel(); animation = null; }
        press = 0f;
        invalidate();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); syncAnimation(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); syncAnimation(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); syncAnimation(); }
    @Override protected void onDetachedFromWindow() { stopAnimation(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas canvas) {
        // Keep the complete phone, fingertips and cuff inside the illustration bounds.
        float scale = Math.min(getWidth() / 340f, getHeight() / 330f);
        canvas.save();
        canvas.translate((getWidth() - 340 * scale) / 2f, (getHeight() - 330 * scale) / 2f);
        canvas.scale(scale, scale);
        fill(0xFFF1F7FF);
        canvas.drawOval(12, 29, 321, 312, paint);
        fill(0xFFE5EFFF);
        canvas.drawOval(44, 281, 307, 315, paint);

        canvas.save();
        canvas.rotate(-9, 136, 158);
        shader(phoneShell);
        canvas.drawRoundRect(51, 17, 224, 302, 29, 29, paint);
        shader(phoneScreen);
        canvas.drawRoundRect(59, 25, 216, 294, 23, 23, paint);
        fill(0xFFAEBFD7);
        canvas.drawRoundRect(114, 36, 161, 42, 3, 3, paint);
        fill(0xFFD7E6FA);
        for (int x = 0; x < 3; x++) {
            canvas.drawRoundRect(77 + x * 43, 235, 109 + x * 43, 267, 10, 10, paint);
        }
        fill(0xFFDCEAFF);
        canvas.drawCircle(140, 146, 59, paint);
        canvas.save();
        canvas.scale(1f - .025f * press, 1f - .025f * press, 140, 146);
        shader(tile);
        canvas.drawRoundRect(102, 108, 178, 184, 21, 21, paint);
        cursor.setBounds(107, 111, 172, 176);
        cursor.draw(canvas);
        canvas.restore();
        fill(0xFF9AB3D5);
        canvas.drawRoundRect(113, 281, 162, 285, 2, 2, paint);
        canvas.restore();

        // Preserve the original alpha and aspect ratio. The fingertip meets the phone tile,
        // while the full cuff stays inside the illustration's 340 x 330 design bounds.
        hand.setBounds(110, 111, 333, 334);
        canvas.save();
        canvas.translate(-4 * press, -5 * press);
        hand.draw(canvas);
        canvas.restore();

        stroke(0xFF3478F6, 3.5f);
        canvas.drawLine(181, 111, 186, 101, paint);
        canvas.drawLine(192, 125, 204, 120, paint);
        canvas.drawLine(148, 122, 142, 114, paint);
        canvas.restore();
    }

    private void stroke(int color, float width) {
        fill(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void shader(Shader shader) {
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shader);
    }

    private void fill(int color) {
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }
}
