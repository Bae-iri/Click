package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;

public final class TrashDropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active;

    public TrashDropView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setContentDescription("여기에 놓으면 클릭 숨기기");
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        animate().scaleX(active ? 1.08f : 1f).scaleY(active ? 1.08f : 1f).setDuration(160L).start();
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(dp(32), Math.min(getWidth(), getHeight()) / 2f - dp(10));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0xFFE34D59 : 0xFFFFF2F3);
        paint.setShadowLayer(dp(6), 0, dp(2), 0x24B73445);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.clearShadowLayer();
        if (!active) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xFFF5C6CC);
            canvas.drawCircle(cx, cy, radius, paint);
        }
        // Use the same 48-unit, rounded-line trash glyph as Settings.
        canvas.save();
        canvas.translate(cx - dp(24), cy - dp(24));
        canvas.scale(dp(1), dp(1));
        paint.setColor(active ? Color.WHITE : 0xFFD74452);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.1f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawRoundRect(new RectF(16, 18, 32, 36), 3, 3, paint);
        canvas.drawLine(12, 16, 36, 16, paint);
        canvas.drawRoundRect(new RectF(20, 11, 28, 16), 2, 2, paint);
        canvas.drawLine(21, 23, 21, 30, paint);
        canvas.drawLine(27, 23, 27, 30, paint);
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
