package com.click.designpreview;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

final class OnboardingDrawables {
    private OnboardingDrawables() { }

    static Drawable startButton(Resources resources) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(18f * resources.getDisplayMetrics().density);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                new ButtonGradient(resources), mask);
    }

    private abstract static class PaintedDrawable extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density;
        final RectF bounds = new RectF();

        PaintedDrawable(Resources resources) {
            density = resources.getDisplayMetrics().density;
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class ButtonGradient extends PaintedDrawable {
        ButtonGradient(Resources resources) { super(resources); }

        @Override
        protected void onBoundsChange(Rect rect) {
            bounds.set(rect);
            // CSS 100deg: mostly left-to-right, with a slight downward slope.
            double angle = Math.toRadians(100);
            float dx = (float) Math.sin(angle);
            float dy = (float) -Math.cos(angle);
            float span = Math.abs(bounds.width() * dx) + Math.abs(bounds.height() * dy);
            paint.setShader(new LinearGradient(bounds.centerX() - dx * span / 2f,
                    bounds.centerY() - dy * span / 2f, bounds.centerX() + dx * span / 2f,
                    bounds.centerY() + dy * span / 2f, 0xFF376FF7, 0xFF4385FA,
                    Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas canvas) {
            canvas.drawRoundRect(bounds, 18f * density, 18f * density, paint);
        }

        @Override public void getOutline(Outline outline) {
            outline.setRoundRect(getBounds(), 18f * density);
        }
    }

    /** The circle fills first, followed by the two strokes of the check mark. */
    static final class Check extends PaintedDrawable {
        private final Path mark = new Path();
        private final Path visibleMark = new Path();
        private final PathMeasure measure;
        private float progress = 1f;

        Check(Resources resources) {
            super(resources);
            mark.moveTo(8.5f, 14f);
            mark.lineTo(12.2f, 17.7f);
            mark.lineTo(19.5f, 10f);
            measure = new PathMeasure(mark, false);
        }

        void setProgress(float progress) {
            this.progress = Math.max(0f, Math.min(1f, progress));
            invalidateSelf();
        }

        @Override public int getIntrinsicWidth() { return Math.round(28f * density); }
        @Override public int getIntrinsicHeight() { return getIntrinsicWidth(); }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 28f, getBounds().height() / 28f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFDCE6F9);
            canvas.drawCircle(14, 14, 14, paint);
            if (progress > 0f) {
                paint.setColor(0xFF3F78F7);
                canvas.drawCircle(14, 14, 14 * Math.min(1f, progress / .3f), paint);
            }
            float strokeProgress = Math.max(0f, (progress - .2f) / .8f);
            if (strokeProgress > 0f) {
                visibleMark.reset();
                measure.getSegment(0, measure.getLength() * strokeProgress, visibleMark, true);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(Color.WHITE);
                canvas.drawPath(visibleMark, paint);
            }
            canvas.restore();
        }
    }

    static final class Bubble extends PaintedDrawable {
        private final Path tail = new Path();
        private final int color;
        private final float tailPosition;
        private final float radius;

        Bubble(Resources resources) {
            this(resources, Color.WHITE, .4f, 28f);
        }

        Bubble(Resources resources, int color, float tailPosition, float radius) {
            super(resources);
            this.color = color;
            this.tailPosition = tailPosition;
            this.radius = radius * density;
            paint.setColor(color);
        }

        @Override protected void onBoundsChange(Rect rect) {
            bounds.set(rect.left, rect.top, rect.right, rect.bottom - 7f * density);
            float x = rect.left + bounds.width() * tailPosition;
            float y = bounds.bottom;
            tail.reset();
            tail.moveTo(x, y - 2f * density);
            tail.lineTo(x - 3f * density, y + 7f * density);
            tail.quadTo(x - 4f * density, y + 12f * density, x + 2f * density, y + 8f * density);
            tail.lineTo(x + 18f * density, y - 2f * density);
            tail.close();
            if (color == 0xFF3F78F7) {
                paint.setShader(new LinearGradient(bounds.left, bounds.top,
                        bounds.right, bounds.bottom, 0xFF548BFF, color, Shader.TileMode.CLAMP));
            }
        }

        @Override public void draw(Canvas canvas) {
            canvas.drawRoundRect(bounds, radius, radius, paint);
            canvas.drawPath(tail, paint);
        }

        @Override public void getOutline(Outline outline) {
            outline.setRoundRect(Math.round(bounds.left), Math.round(bounds.top),
                    Math.round(bounds.right), Math.round(bounds.bottom), radius);
        }
    }
}
