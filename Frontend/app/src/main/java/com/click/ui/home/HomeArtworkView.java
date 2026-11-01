package com.click.ui.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Reuses the original Click artwork without adding duplicate bitmap assets. */
public final class HomeArtworkView extends View {
    public enum Kind { WORDMARK, START, CURSOR, SETTINGS, STAR }
    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Drawable logo;
    private final Drawable hand;

    public HomeArtworkView(Context context, Kind kind) {
        super(context);
        this.kind = kind;
        logo = kind == Kind.WORDMARK
                ? context.getDrawable(com.click.designpreview.R.drawable.click)
                : kind == Kind.START || kind == Kind.CURSOR
                ? context.getDrawable(com.click.designpreview.R.drawable.logo) : null;
        hand = kind == Kind.START
                ? context.getDrawable(com.click.designpreview.R.drawable.permission_touch_hand) : null;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (isClickable()) info.setClassName("android.widget.Button");
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (kind == Kind.WORDMARK) {
            crop(canvas, logo, 2172, 724, 211, 71, 1952, 682, 0, 0, getWidth(), getHeight());
            return;
        }
        float width = kind == Kind.START ? 320 : 48;
        float height = kind == Kind.START ? 210 : 48;
        float scale = Math.min(getWidth() / width, getHeight() / height);
        canvas.save();
        canvas.translate((getWidth() - width * scale) / 2, (getHeight() - height * scale) / 2);
        canvas.scale(scale, scale);
        if (kind == Kind.START) {
            paint.setColor(0xFFD9D9D9);
            canvas.drawCircle(160, 70, 64, paint);
            cursor(canvas, 116, 24, 202, 112);
            crop(canvas, hand, 1254, 1254, 216, 138, 1120, 1141, 214, 70, 314, 182);
        } else if (kind == Kind.CURSOR) {
            cursor(canvas, 1, 0, 47, 48);
        } else if (kind == Kind.STAR) {
            paint.setColor(0xFFD9D9D9);
            canvas.drawCircle(24, 24, 24, paint);
            paint.setColor(0xFFFFEA70);
            shape.reset();
            for (int i = 0; i < 10; i++) {
                double angle = -Math.PI / 2 + Math.PI * i / 5;
                float radius = i % 2 == 0 ? 23 : 9;
                float x = 24 + (float) Math.cos(angle) * radius;
                float y = 26 + (float) Math.sin(angle) * radius;
                if (i == 0) shape.moveTo(x, y); else shape.lineTo(x, y);
            }
            shape.close();
            canvas.drawPath(shape, paint);
        } else {
            paint.setColor(0xFFD9ECFF);
            canvas.drawCircle(24, 24, 16, paint);
            paint.setColor(0xFF3478F6);
            for (int i = 0; i < 8; i++) {
                canvas.save();
                canvas.rotate(45 * i, 24, 24);
                canvas.drawRoundRect(21.7f, 13.4f, 26.3f, 20, 1, 1, paint);
                canvas.restore();
            }
            shape.reset();
            shape.setFillType(Path.FillType.EVEN_ODD);
            shape.addCircle(24, 24, 8.2f, Path.Direction.CW);
            shape.addCircle(24, 24, 3.5f, Path.Direction.CW);
            canvas.drawPath(shape, paint);
        }
        canvas.restore();
    }

    private void cursor(Canvas canvas, float left, float top, float right, float bottom) {
        crop(canvas, logo, 1254, 1254, 355, 198, 928, 782, left, top, right, bottom);
    }

    private void crop(Canvas canvas, Drawable drawable, int width, int height,
                      float sourceLeft, float sourceTop, float sourceRight, float sourceBottom,
                      float left, float top, float right, float bottom) {
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        float scale = Math.min((right - left) / (sourceRight - sourceLeft),
                (bottom - top) / (sourceBottom - sourceTop));
        canvas.translate(left + ((right - left) - (sourceRight - sourceLeft) * scale) / 2,
                top + ((bottom - top) - (sourceBottom - sourceTop) * scale) / 2);
        canvas.scale(scale, scale);
        canvas.translate(-sourceLeft, -sourceTop);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        canvas.restore();
    }
}
