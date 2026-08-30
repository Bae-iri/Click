package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;

import com.click.ui.common.ClickUi;

public final class GuideOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int[] bounds;
    private String actionType;

    public GuideOverlayView(Context context) { super(context); }

    public void showTarget(int[] targetBounds, String action) {
        bounds = targetBounds == null ? null : targetBounds.clone();
        actionType = action;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (bounds == null || bounds.length != 4) return;
        RectF target = new RectF(bounds[0], bounds[1], bounds[2], bounds[3]);
        target.inset(-dp(8), -dp(8));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(42, 255, 59, 77));
        canvas.drawRoundRect(target, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setColor(ClickUi.RED);
        canvas.drawRoundRect(target, dp(12), dp(12), paint);

        float startX = target.right + dp(34);
        float endX = target.right + dp(6);
        float y = target.centerY();
        if (startX > getWidth() - dp(12)) {
            startX = target.left - dp(34);
            endX = target.left - dp(6);
        }
        paint.setStrokeWidth(dp(5));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(startX, y, endX, y, paint);
        path.reset();
        float direction = endX > startX ? 1f : -1f;
        path.moveTo(endX, y);
        path.lineTo(endX - direction * dp(12), y - dp(9));
        path.lineTo(endX - direction * dp(12), y + dp(9));
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);

        if ("drag".equals(actionType)) {
            paint.setTextSize(dp(18));
            paint.setFakeBoldText(true);
            canvas.drawText("오른쪽으로 움직이세요  →", Math.max(dp(16), target.left),
                    Math.min(getHeight() - dp(20), target.bottom + dp(38)), paint);
            paint.setFakeBoldText(false);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
