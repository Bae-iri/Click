package com.click.designpreview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

/** Resolution-independent phone mockup and Click badge; all readable copy is in TextViews. */
public final class OnboardingArtworkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Drawable cursor;
    private int scene = 1;

    public OnboardingArtworkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cursor = context.getDrawable(R.drawable.ic_click_cursor).mutate();
        cursor.setTint(0xFF427CFA);
    }

    void setScene(int scene) {
        this.scene = scene;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float designHeight = scene == 3 ? 210f : 380f;
        float scale = Math.min(getWidth() / 340f, getHeight() / designHeight);
        canvas.save();
        canvas.translate((getWidth() - 340f * scale) / 2f,
                (getHeight() - designHeight * scale) / 2f);
        canvas.scale(scale, scale);
        if (scene == 3) {
            drawReady(canvas);
        } else {
            drawPhone(canvas);
            drawBadge(canvas, scene == 1 ? 216 : 260, scene == 1 ? 251 : 304,
                    scene == 1 ? 58 : 49);
        }
        canvas.restore();
    }

    private void drawPhone(Canvas canvas) {
        canvas.save();
        canvas.rotate(-9, 170, 238);
        fill(0xFFF0F5FF);
        canvas.drawOval(12, 133, 328, 374, paint);
        canvas.restore();

        // An oval lower edge makes the phone and its pale backdrop a single illustration.
        canvas.save();
        path.reset();
        path.moveTo(0, 0);
        path.lineTo(340, 0);
        path.lineTo(340, 264);
        path.cubicTo(320, 406, 20, 406, 0, 264);
        path.close();
        canvas.clipPath(path);
        paint.setShader(new LinearGradient(40, 30, 250, 340,
                new int[]{0xFFD8DFE9, 0xFFAEB8C8, 0xFFD5DDEA}, null, Shader.TileMode.CLAMP));
        round(canvas, 42, 18, 251, 390, 34);
        paint.setShader(null);
        fill(0xFFCAD3E0);
        round(canvas, 47, 22, 247, 390, 31);
        paint.setShader(new LinearGradient(70, 25, 220, 370,
                0xFFF9FBFF, 0xFFE8EEF8, Shader.TileMode.CLAMP));
        round(canvas, 55, 31, 237, 392, 25);
        paint.setShader(null);

        fill(0xFFE0E6F0);
        round(canvas, 126, 41, 167, 46, 3);
        canvas.drawCircle(200, 44, 3, paint);
        canvas.drawCircle(211, 44, 3, paint);
        for (int row = 0; row < 5; row++) {
            float y = 76 + row * 55;
            fill(row == 0 ? 0xFFE4EAF3 : 0xFFDEE6F1);
            round(canvas, 73, y, 99, y + 26, 8);
            fill(0xFFE2E8F2);
            round(canvas, 111, y + 4, 209 - (row % 2) * 19, y + 12, 4);
            fill(0xFFE7EDF6);
            round(canvas, 111, y + 19, 184, y + 25, 3);
        }
        paint.setShader(new LinearGradient(0, 230, 0, 390,
                0x00EEF3FC, 0xC9EEF3FC, Shader.TileMode.CLAMP));
        canvas.drawRect(40, 230, 253, 392, paint);
        paint.setShader(null);
        canvas.restore();
    }

    private void drawReady(Canvas canvas) {
        fill(0xFF5086FA);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(9);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(60, 66, 79, 81, paint);
        canvas.drawLine(44, 108, 72, 108, paint);
        canvas.drawLine(61, 148, 80, 132, paint);
        paint.setStyle(Paint.Style.FILL);
        drawBadge(canvas, 190, 115, 77);
    }

    private void drawBadge(Canvas canvas, float x, float y, float radius) {
        paint.setShader(new RadialGradient(x, y + radius * .22f, radius * 1.48f,
                new int[]{0x414677C7, 0x224C85EE, 0x004C85EE},
                new float[]{0f, .65f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y + radius * .22f, radius * 1.48f, paint);
        paint.setShader(null);
        fill(Color.WHITE);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(0xFFF0F4FC);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        float size = radius * 1.63f;
        cursor.setBounds(Math.round(x - size / 2), Math.round(y - size / 2),
                Math.round(x + size / 2), Math.round(y + size / 2));
        cursor.draw(canvas);
    }

    private void fill(int color) {
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }

    private void round(Canvas canvas, float left, float top, float right, float bottom, float radius) {
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
}
