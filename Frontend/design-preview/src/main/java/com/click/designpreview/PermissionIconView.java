package com.click.designpreview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Small vector illustrations. Their adjacent TextViews provide the accessible labels. */
@SuppressLint("ViewConstructor") // Created programmatically with a specific illustration kind.
final class PermissionIconView extends View {
    enum Kind { SEARCH, READ, ALERT, CONTROL, ACCESSIBILITY, SETTINGS, CHECK, APPS, DOWNLOAD, CLICK }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Kind kind;
    private final int color;
    private final Drawable cursor;

    PermissionIconView(Context context, Kind kind, int color) {
        super(context);
        this.kind = kind;
        this.color = color;
        cursor = context.getDrawable(R.drawable.ic_click_cursor).mutate();
        cursor.setTint(color);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 56f, size / 56f);
        fill(0xFFE6F0FF);
        canvas.drawCircle(28, 28, 27, paint);
        fill(0xFFF5F9FF);
        canvas.drawCircle(28, 26, 21, paint);
        switch (kind) {
            case SEARCH:
                stroke(color, 3.5f);
                canvas.drawCircle(25, 25, 10, paint);
                canvas.drawLine(33, 33, 42, 42, paint);
                break;
            case READ:
                fill(color);
                canvas.drawRoundRect(16, 12, 40, 44, 5, 5, paint);
                stroke(Color.WHITE, 2);
                canvas.drawLine(22, 21, 34, 21, paint);
                canvas.drawLine(22, 27, 34, 27, paint);
                canvas.drawLine(22, 33, 30, 33, paint);
                break;
            case ALERT:
                fill(color);
                path.reset();
                path.moveTo(24, 12);
                path.quadTo(28, 6, 32, 12);
                path.lineTo(46, 38);
                path.quadTo(48, 43, 42, 44);
                path.lineTo(14, 44);
                path.quadTo(8, 43, 11, 38);
                path.close();
                canvas.drawPath(path, paint);
                stroke(Color.WHITE, 3);
                canvas.drawLine(28, 20, 28, 29, paint);
                fill(Color.WHITE);
                canvas.drawCircle(28, 36, 1.9f, paint);
                break;
            case CONTROL:
                fill(color);
                path.reset();
                path.moveTo(28, 10); path.lineTo(43, 16); path.lineTo(41, 30);
                path.quadTo(39, 41, 28, 47);
                path.quadTo(17, 41, 15, 30); path.lineTo(13, 16); path.close();
                canvas.drawPath(path, paint);
                stroke(Color.WHITE, 2.8f);
                canvas.drawLine(21, 27, 26, 32, paint);
                canvas.drawLine(26, 32, 35, 22, paint);
                break;
            case ACCESSIBILITY:
                fill(color);
                canvas.drawCircle(28, 28, 21, paint);
                fill(Color.WHITE);
                canvas.drawCircle(28, 17, 4, paint);
                stroke(Color.WHITE, 2.8f);
                canvas.drawLine(17, 24, 39, 24, paint);
                canvas.drawLine(28, 23, 28, 32, paint);
                canvas.drawLine(28, 30, 22, 41, paint);
                canvas.drawLine(28, 30, 34, 41, paint);
                break;
            case SETTINGS:
                fill(color);
                canvas.drawRoundRect(3, 3, 53, 53, 13, 13, paint);
                fill(Color.WHITE);
                for (int tooth = 0; tooth < 8; tooth++) {
                    canvas.save();
                    canvas.rotate(tooth * 45, 28, 28);
                    canvas.drawRoundRect(24, 9, 32, 22, 2, 2, paint);
                    canvas.restore();
                }
                canvas.drawCircle(28, 28, 16, paint);
                fill(color);
                canvas.drawCircle(28, 28, 8, paint);
                break;
            case CHECK:
                fill(color);
                canvas.drawCircle(28, 28, 22, paint);
                stroke(Color.WHITE, 3.2f);
                canvas.drawLine(18, 28, 25, 35, paint);
                canvas.drawLine(25, 35, 39, 21, paint);
                break;
            case APPS:
                fill(color);
                canvas.drawRoundRect(7, 7, 49, 49, 12, 12, paint);
                fill(Color.WHITE);
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) {
                    canvas.drawCircle(21 + x * 14, 21 + y * 14, 3, paint);
                }
                break;
            case DOWNLOAD:
                fill(color);
                canvas.drawRoundRect(7, 7, 49, 49, 12, 12, paint);
                stroke(Color.WHITE, 2.8f);
                canvas.drawLine(28, 15, 28, 32, paint);
                canvas.drawLine(21, 26, 28, 33, paint);
                canvas.drawLine(28, 33, 35, 26, paint);
                canvas.drawLine(18, 39, 38, 39, paint);
                break;
            case CLICK:
                cursor.setBounds(-2, -2, 58, 58);
                cursor.draw(canvas);
                break;
        }
        canvas.restore();
    }

    private void fill(int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }

    private void stroke(int color, float width) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
}
