package com.click.designpreview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Decorative phone and fingertip, drawn at any density without embedding instructions in an image. */
final class PermissionTouchArtworkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Drawable cursor;
    private final Shader phoneShell = new LinearGradient(48, 20, 224, 300,
            0xFFACC0DB, 0xFF607C9E, Shader.TileMode.CLAMP);
    private final Shader phoneScreen = new LinearGradient(60, 30, 204, 280,
            0xFFFFFFFF, 0xFFE6EFFF, Shader.TileMode.CLAMP);
    private final Shader tile = new LinearGradient(100, 108, 181, 182,
            0xFF66A8FF, 0xFF3478F6, Shader.TileMode.CLAMP);
    private final Shader hand = new LinearGradient(160, 142, 274, 284,
            0xFFFFE5CC, 0xFFF1B58F, Shader.TileMode.CLAMP);

    PermissionTouchArtworkView(Context context) {
        super(context);
        cursor = context.getDrawable(R.drawable.ic_click_cursor).mutate();
        cursor.setTint(Color.WHITE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        // Keep the complete phone, fingertips and cuff inside the illustration bounds.
        float scale = Math.min(getWidth() / 340f, getHeight() / 330f);
        canvas.save();
        canvas.translate((getWidth() - 340 * scale) / 2f, (getHeight() - 330 * scale) / 2f);
        canvas.scale(scale, scale);
        fill(0xFFF1F7FF);
        canvas.drawOval(12, 29, 321, 312, paint);
        fill(0xFFE5EFFF);
        canvas.drawOval(44, 281, 307, 315, paint);

        canvas.save();
        canvas.rotate(-9, 136, 158);
        shader(phoneShell);
        canvas.drawRoundRect(51, 17, 224, 302, 29, 29, paint);
        shader(phoneScreen);
        canvas.drawRoundRect(59, 25, 216, 294, 23, 23, paint);
        fill(0xFFAEBFD7);
        canvas.drawRoundRect(114, 36, 161, 42, 3, 3, paint);
        fill(0xFFD7E6FA);
        for (int x = 0; x < 3; x++) {
            canvas.drawRoundRect(77 + x * 43, 235, 109 + x * 43, 267, 10, 10, paint);
        }
        fill(0xFFDCEAFF);
        canvas.drawCircle(140, 146, 59, paint);
        shader(tile);
        canvas.drawRoundRect(102, 108, 178, 184, 21, 21, paint);
        cursor.setBounds(107, 111, 172, 176);
        cursor.draw(canvas);
        fill(0xFF9AB3D5);
        canvas.drawRoundRect(113, 281, 162, 285, 2, 2, paint);
        canvas.restore();

        // One extended index finger, three folded fingers and a separate thumb.
        path.reset();
        path.moveTo(286, 294);
        path.lineTo(226, 294);
        path.cubicTo(216, 277, 201, 265, 191, 253);
        path.lineTo(171, 231);
        path.cubicTo(163, 222, 171, 211, 181, 218);
        path.lineTo(201, 233);
        path.lineTo(159, 163);
        path.cubicTo(151, 150, 154, 140, 163, 139);
        path.cubicTo(169, 138, 174, 143, 178, 150);
        path.lineTo(213, 204);
        path.cubicTo(207, 189, 215, 180, 223, 185);
        path.cubicTo(229, 188, 232, 199, 237, 208);
        path.cubicTo(230, 194, 240, 188, 248, 195);
        path.cubicTo(253, 199, 256, 209, 260, 217);
        path.cubicTo(255, 206, 263, 202, 270, 209);
        path.cubicTo(281, 220, 283, 242, 289, 259);
        path.cubicTo(293, 271, 291, 284, 286, 294);
        path.close();
        shader(hand);
        canvas.drawPath(path, paint);
        stroke(0xFFD99D7B, 1.6f);
        canvas.drawPath(path, paint);

        canvas.save();
        canvas.rotate(-33, 168, 155);
        fill(0xFFFFF1E3);
        canvas.drawRoundRect(162, 145, 175, 162, 6, 6, paint);
        canvas.restore();
        stroke(0xFFD99D7B, 1.6f);
        path.reset();
        path.moveTo(202, 234);
        path.quadTo(214, 239, 217, 251);
        canvas.drawPath(path, paint);
        canvas.drawLine(214, 204, 222, 217, paint);
        canvas.drawLine(238, 209, 243, 220, paint);
        canvas.drawLine(260, 218, 264, 227, paint);

        fill(0xFF3478F6);
        canvas.drawRoundRect(222, 286, 294, 315, 8, 8, paint);
        fill(0xFF74AAFF);
        canvas.drawRoundRect(222, 286, 294, 293, 3, 3, paint);
        fill(Color.WHITE);
        canvas.drawCircle(282, 303, 3, paint);

        stroke(0xFF3478F6, 3.5f);
        canvas.drawLine(181, 111, 186, 101, paint);
        canvas.drawLine(192, 125, 204, 120, paint);
        canvas.drawLine(148, 122, 142, 114, paint);
        canvas.restore();
    }

    private void stroke(int color, float width) {
        fill(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void shader(Shader shader) {
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shader);
    }

    private void fill(int color) {
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }
}
