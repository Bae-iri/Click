package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import com.click.R;

/** Three distinct visual states for the same press-and-hold target. */
public final class VoiceCaptureIndicator extends View {
    public enum State { READY, LISTENING, PROCESSING }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Drawable microphone;
    private State state = State.READY;
    private long startedAt;

    public VoiceCaptureIndicator(Context context) {
        super(context);
        microphone = context.getDrawable(R.drawable.ic_overlay_microphone).mutate();
        microphone.setTint(0xFF287CFF);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public State getState() { return state; }
    public void setState(State next) {
        if (state == next) return;
        state = next;
        startedAt = SystemClock.uptimeMillis();
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        float size = Math.min(getWidth(), getHeight());
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 160f, size / 160f);
        float time = (SystemClock.uptimeMillis() - startedAt) / 1000f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF2F7FF); canvas.drawCircle(80, 80, 77, paint);
        paint.setColor(0xFFE7F1FF); canvas.drawCircle(80, 80, 60, paint);
        paint.setColor(0xFFD8E9FF); canvas.drawCircle(80, 80, 43, paint);
        paint.setColor(0xFF287CFF);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (state == State.READY) {
            microphone.setBounds(58, 51, 102, 109);
            microphone.draw(canvas);
        } else if (state == State.LISTENING) {
            paint.setStrokeWidth(5.5f);
            for (int i = 0; i < 5; i++) {
                float base = new float[]{10, 20, 27, 20, 10}[i];
                float half = base * (.78f + .22f * (float) Math.sin(time * 5f + i * 1.15f));
                canvas.drawLine(56 + i * 12, 80 - half, 56 + i * 12, 80 + half, paint);
            }
        } else {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5.5f);
            arc.set(58, 58, 102, 102);
            paint.setColor(0xFFB6D2FF); canvas.drawOval(arc, paint);
            paint.setColor(0xFF287CFF); canvas.drawArc(arc, time * 240f - 90, 245, false, paint);
        }
        canvas.restore();
        if (state != State.READY && isShown()) postInvalidateOnAnimation();
    }
}
