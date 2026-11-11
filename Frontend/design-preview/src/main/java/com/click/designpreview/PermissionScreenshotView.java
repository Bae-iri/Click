package com.click.designpreview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Static viewport into the original screenshot; no animation or modified image files. */
public final class PermissionScreenshotView extends View {
    private final Drawable screenshot;
    private final RectF crop, target;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean highlightFilled;

    /** The same image, viewport and highlighted row used by accessibility page one. */
    public static PermissionScreenshotView accessibilityFirstPage(Context context) {
        return new PermissionScreenshotView(context, R.drawable.permission_01_accessibility,
                new RectF(0, 1280, 1080, 2160), new RectF(24, 1680, 1056, 1890));
    }

    PermissionScreenshotView(Context context, int image, RectF crop, RectF target) {
        super(context);
        screenshot = context.getDrawable(image);
        this.crop = crop;
        this.target = target;
        outline.setColor(0xFF3478F6);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        highlightFill.setColor(0x2A3478F6);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setHighlightFilled(boolean filled) {
        highlightFilled = filled;
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, resolveSize(Math.round(width * crop.height() / crop.width()), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = getWidth() / crop.width();
        int save = canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        canvas.scale(scale, scale);
        canvas.translate(-crop.left, -crop.top);
        screenshot.setBounds(0, 0, 1080, 2400);
        screenshot.draw(canvas);
        canvas.restoreToCount(save);
        RectF highlight = getTargetBounds();
        if (highlightFilled) canvas.drawRoundRect(highlight, 12 * getResources().getDisplayMetrics().density,
                12 * getResources().getDisplayMetrics().density, highlightFill);
        canvas.drawRoundRect(highlight, 12 * getResources().getDisplayMetrics().density,
                12 * getResources().getDisplayMetrics().density, outline);
    }

    public RectF getTargetBounds() {
        float scale = getWidth() / crop.width();
        return new RectF((target.left - crop.left) * scale, (target.top - crop.top) * scale,
                (target.right - crop.left) * scale, (target.bottom - crop.top) * scale);
    }
}
