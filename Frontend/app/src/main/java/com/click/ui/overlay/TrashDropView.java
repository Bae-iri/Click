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
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        animate().scaleX(active ? 1.16f : 1f).scaleY(active ? 1.16f : 1f).setDuration(130L).start();
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = dp(active ? 43 : 39);
        paint.setColor(active ? Color.rgb(255, 59, 77) : Color.argb(235, 49, 54, 64));
        paint.setShadowLayer(dp(10), 0, dp(4), Color.argb(90, 20, 24, 32));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.clearShadowLayer();

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawRoundRect(new RectF(cx - dp(10), cy - dp(8), cx + dp(10), cy + dp(15)), dp(2), dp(2), paint);
        canvas.drawLine(cx - dp(14), cy - dp(13), cx + dp(14), cy - dp(13), paint);
        canvas.drawLine(cx - dp(5), cy - dp(19), cx + dp(5), cy - dp(19), paint);
        canvas.drawLine(cx - dp(4), cy - dp(4), cx - dp(4), cy + dp(10), paint);
        canvas.drawLine(cx + dp(4), cy - dp(4), cx + dp(4), cy + dp(10), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
