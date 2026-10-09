package com.click.ui.tutorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Decorative vectors; tutorial copy and interactive targets remain native views. */
public final class TutorialArtworkView extends View {
    public enum Kind { BACKGROUND, MICROPHONE, LIST, CHECK, SUCCESS }
    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private static final int BLUE = 0xFF2874FF;

    public TutorialArtworkView(Context context, Kind kind) {
        super(context);
        this.kind = kind;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (kind == Kind.BACKGROUND) { drawBackground(canvas); return; }
        canvas.save();
        float side = Math.min(getWidth(), getHeight());
        canvas.translate((getWidth() - side) / 2f, (getHeight() - side) / 2f);
        canvas.scale(side / 100f, side / 100f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        if (kind == Kind.MICROPHONE) {
            paint.setColor(0xFFEFF5FF); canvas.drawCircle(50, 50, 49, paint);
            paint.setColor(0xFFDCEAFF); canvas.drawCircle(50, 50, 41, paint);
            paint.setShader(new LinearGradient(20, 20, 80, 83, 0xFF438EFF, 0xFF2164FF, Shader.TileMode.CLAMP));
            canvas.drawCircle(50, 50, 32, paint);
            paint.setShader(null); paint.setColor(Color.WHITE);
            canvas.drawRoundRect(new RectF(45, 34, 55, 55), 5, 5, paint);
            stroke(Color.WHITE, 3);
            canvas.drawArc(new RectF(40, 41, 60, 64), 0, 180, false, paint);
            canvas.drawLine(50, 64, 50, 70, paint);
            stroke(BLUE, 3);
            canvas.drawLine(2, 45, 6, 47, paint); canvas.drawLine(2, 57, 6, 55, paint);
            canvas.drawLine(94, 47, 98, 45, paint); canvas.drawLine(94, 55, 98, 57, paint);
        } else if (kind == Kind.LIST) {
            paint.setColor(BLUE);
            canvas.drawRoundRect(new RectF(5, 5, 95, 95), 20, 20, paint);
            for (int i = 0; i < 3; i++) {
                float y = 28 + i * 22;
                stroke(Color.WHITE, 5);
                path.reset(); path.moveTo(23, y); path.lineTo(29, y + 6); path.lineTo(39, y - 6);
                canvas.drawPath(path, paint); canvas.drawLine(53, y, 76, y, paint);
            }
        } else {
            if (kind == Kind.SUCCESS) {
                paint.setColor(0xFFE2EDFF); canvas.drawCircle(50, 52, 35, paint);
                paint.setColor(Color.WHITE); canvas.drawCircle(50, 52, 30, paint);
                int[] colors = {0xFFFFCE66, 0xFFFF8FB3, 0xFF51C7CC, 0xFF72A9FF};
                for (int i = 0; i < 8; i++) {
                    canvas.save(); canvas.rotate(i * 45, 50, 52);
                    stroke(colors[i % 4], 4); canvas.drawLine(50, 5, 50, 10, paint); canvas.restore();
                }
                stroke(BLUE, 8); path.reset(); path.moveTo(37, 52); path.lineTo(47, 62); path.lineTo(66, 41);
            } else {
                paint.setColor(0xFFE5EFFF); canvas.drawCircle(50, 50, 46, paint);
                stroke(BLUE, 9); path.reset(); path.moveTo(28, 50); path.lineTo(44, 66); path.lineTo(73, 34);
            }
            canvas.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void drawBackground(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, w, h, 0xFFFFFFFF, 0xFFF3F8FF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint); paint.setShader(null); paint.setColor(0xFFEAF3FF);
        path.reset(); path.moveTo(0, h * .43f);
        path.cubicTo(w * .19f, h * .42f, w * .07f, h * .6f, w * .32f, h * .59f);
        path.cubicTo(w * .65f, h * .59f, w * .72f, h * .39f, w, h * .43f);
        path.lineTo(w, h); path.lineTo(0, h); path.close(); canvas.drawPath(path, paint);
        paint.setColor(0xFFE1EEFF); path.reset(); path.moveTo(0, h * .79f);
        path.cubicTo(w * .25f, h * .69f, w * .24f, h * .91f, w * .5f, h * .89f);
        path.cubicTo(w * .73f, h * .87f, w * .71f, h * .69f, w, h * .72f);
        path.lineTo(w, h); path.lineTo(0, h); path.close(); canvas.drawPath(path, paint);
    }

    private void stroke(int color, float width) {
        paint.setShader(null); paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public boolean performClick() { return super.performClick(); }
}
