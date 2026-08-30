package com.click.ui.overlay;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public final class ClickIconRenderer {
    private static final int CLICK_RED = Color.rgb(255, 59, 77);
    private static final float CURSOR_BASE_ANGLE = -121f;

    private ClickIconRenderer() {}

    public static void draw(
            View owner,
            Canvas canvas,
            Paint paint,
            Path path,
            float cx,
            float cy,
            float radius,
            float cursorDirectionDegrees,
            boolean rotateCursor
    ) {
        paint.setColor(CLICK_RED);
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(dp(owner, 6), 0, dp(owner, 3), Color.argb(65, 30, 35, 50));
        owner.setLayerType(View.LAYER_TYPE_SOFTWARE, paint);
        canvas.drawCircle(cx, cy - radius * 0.06f, radius, paint);

        path.reset();
        path.moveTo(cx - radius * 0.56f, cy + radius * 0.62f);
        path.lineTo(cx - radius * 0.76f, cy + radius * 1.02f);
        path.lineTo(cx - radius * 0.18f, cy + radius * 0.78f);
        path.close();
        canvas.drawPath(path, paint);
        paint.clearShadowLayer();

        canvas.save();
        if (rotateCursor) canvas.rotate(cursorDirectionDegrees - CURSOR_BASE_ANGLE, cx, cy);
        paint.setColor(Color.WHITE);
        path.reset();
        path.moveTo(cx - radius * 0.30f, cy - radius * 0.50f);
        path.lineTo(cx + radius * 0.42f, cy - radius * 0.02f);
        path.lineTo(cx + radius * 0.08f, cy + radius * 0.08f);
        path.lineTo(cx + radius * 0.30f, cy + radius * 0.50f);
        path.lineTo(cx + radius * 0.06f, cy + radius * 0.62f);
        path.lineTo(cx - radius * 0.14f, cy + radius * 0.18f);
        path.lineTo(cx - radius * 0.42f, cy + radius * 0.42f);
        path.close();
        canvas.drawPath(path, paint);

        paint.setStrokeWidth(dp(owner, 2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx + radius * .25f, cy - radius * .63f, cx + radius * .36f, cy - radius * .88f, paint);
        canvas.drawLine(cx + radius * .47f, cy - radius * .48f, cx + radius * .68f, cy - radius * .65f, paint);
        canvas.drawLine(cx + radius * .55f, cy - radius * .20f, cx + radius * .80f, cy - radius * .22f, paint);
        canvas.restore();
    }

    private static float dp(View owner, int value) {
        return value * owner.getResources().getDisplayMetrics().density;
    }
}
