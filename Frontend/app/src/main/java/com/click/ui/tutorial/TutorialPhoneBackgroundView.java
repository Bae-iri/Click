package com.click.ui.tutorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/** A plain practice phone with a continuous bezel and bottom gesture bar. */
public final class TutorialPhoneBackgroundView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    public TutorialPhoneBackgroundView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF shell = new RectF(2 * density, 2 * density, getWidth() - 2 * density, getHeight() - 2 * density);
        paint.setStyle(Paint.Style.FILL); paint.setShader(null); paint.setColor(0xFFC2D2E6);
        canvas.drawRoundRect(shell, 32 * density, 32 * density, paint);
        RectF screen = new RectF(shell); screen.inset(4 * density, 4 * density);
        paint.setColor(android.graphics.Color.WHITE);
        canvas.drawRoundRect(screen, 28 * density, 28 * density, paint);
        paint.setShader(null);
        float x = screen.centerX();
        paint.setColor(0xFFB2C5DF);
        canvas.drawRoundRect(x - 17 * density, screen.top + 11 * density,
                x + 17 * density, screen.top + 15 * density, 2 * density, 2 * density, paint);
        canvas.drawRoundRect(x - 30 * density, screen.bottom - 15 * density,
                x + 30 * density, screen.bottom - 11 * density, 2 * density, 2 * density, paint);
    }
}
