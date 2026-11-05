package com.click.ui.tutorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Decorative notebook rings above the tutorial's practice checklist. */
public final class NotebookBindingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public NotebookBindingView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        int count = Math.max(4, Math.min(8, Math.round(getWidth() / (38 * density))));
        float spacing = getWidth() / (float) (count + 1);
        for (int i = 1; i <= count; i++) {
            float x = spacing * i;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE6EDF6);
            canvas.drawCircle(x, 25 * density, 4 * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xFF9EB5D5);
            canvas.drawRoundRect(x - 4 * density, 3 * density, x + 4 * density,
                    25 * density, 4 * density, 4 * density, paint);
            paint.setStrokeWidth(density);
            paint.setColor(0xFFF3F7FD);
            canvas.drawLine(x - density, 9 * density, x - density, 18 * density, paint);
        }
    }
}
