package com.click.ui.overlay;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Draws the Click cursor mark inside the shared white floating control. */
public final class ClickIconRenderer {
    private static final int CLICK_BLUE = 0xFF3478F6;
    private static final int BORDER_BLUE = 0xFFD8E6FF;
    private static final float CURSOR_BASE_ANGLE = -121f;

    private ClickIconRenderer() {}

    public static void draw(View owner, Canvas canvas, Paint paint, Path path,
                            float cx, float cy, float radius,
                            float cursorDirectionDegrees, boolean rotateCursor) {
        // Changing the layer paint during every draw schedules another frame indefinitely.
        if (owner.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
            owner.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(dp(owner, 7), 0, dp(owner, 3), 0x453478F6);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.clearShadowLayer();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(owner, 1), radius * .045f));
        paint.setColor(BORDER_BLUE);
        canvas.drawCircle(cx, cy, radius - paint.getStrokeWidth() / 2f, paint);

        canvas.save();
        if (rotateCursor) canvas.rotate(cursorDirectionDegrees - CURSOR_BASE_ANGLE, cx, cy);
        float scale = radius * 1.48f / 108f;
        canvas.translate(cx - 54f * scale, cy - 54f * scale);
        canvas.scale(scale, scale);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(CLICK_BLUE);
        path.reset();
        path.moveTo(45, 37);
        path.cubicTo(40, 34, 36, 38, 38, 43);
        path.lineTo(48, 82);
        path.cubicTo(50, 88, 56, 89, 60, 83);
        path.lineTo(68, 67);
        path.lineTo(85, 62);
        path.cubicTo(92, 60, 93, 54, 86, 50);
        path.close();
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(7);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawLine(32, 26, 26, 19, paint);
        canvas.drawLine(49, 22, 51, 13, paint);
        canvas.drawLine(26, 42, 18, 42, paint);
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
    }

    private static float dp(View owner, int value) {
        return value * owner.getResources().getDisplayMetrics().density;
    }
}
