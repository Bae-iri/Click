package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;

import com.click.ui.common.ClickUi;

public final class GuideOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int[] screenOrigin = new int[2];
    private int[] bounds;
    private String actionType;
    private int dragDirection;
    private long firstTargetFrameElapsedMs;
    private long targetFrameElapsedMs;

    /** Diagnostics record a drawn target, rather than an HTTP response or queued invalidation. */
    public long firstTargetFrameElapsedMs() { return firstTargetFrameElapsedMs; }
    public long targetFrameElapsedMs() { return targetFrameElapsedMs; }
    public boolean targetMatches(int[] expected) { return expected != null && java.util.Arrays.equals(bounds, expected); }

    public GuideOverlayView(Context context) { super(context); }

    /** @param direction {@link GuideDragDirection} value; only used for drag hints. */
    public void showTarget(int[] targetBounds, String action, int direction) {
        if (!java.util.Arrays.equals(bounds, targetBounds) || !java.util.Objects.equals(actionType, action)) targetFrameElapsedMs = 0;
        bounds = targetBounds == null ? null : targetBounds.clone();
        actionType = action;
        dragDirection = "drag".equals(action)
                && (direction == GuideDragDirection.LEFT || direction == GuideDragDirection.RIGHT)
                ? direction : GuideDragDirection.UNKNOWN;
        setContentDescription("drag".equals(action) ? dragInstruction() : null);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        // Measure after attachment/layout, on every draw; system bars and window origins vary.
        getLocationOnScreen(screenOrigin);
        float[] local = GuideTargetGeometry.toLocal(bounds, screenOrigin[0], screenOrigin[1],
                getWidth(), getHeight());
        if (local == null) return;
        if (com.click.BuildConfig.CANDIDATE_DIAGNOSTICS && firstTargetFrameElapsedMs == 0)
            firstTargetFrameElapsedMs = android.os.SystemClock.elapsedRealtime();
        if (com.click.BuildConfig.CANDIDATE_DIAGNOSTICS && targetFrameElapsedMs == 0)
            targetFrameElapsedMs = android.os.SystemClock.elapsedRealtime();
        RectF target = new RectF(local[0], local[1], local[2], local[3]);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(42, 52, 120, 246));
        canvas.drawRoundRect(target, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.STROKE);
        float strokeWidth = Math.min(dp(4), Math.min(target.width(), target.height()));
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(ClickUi.RED);
        RectF outline = new RectF(target);
        outline.inset(strokeWidth / 2f, strokeWidth / 2f);
        canvas.drawRoundRect(outline, dp(12), dp(12), paint);

        if (!"drag".equals(actionType)) {
            // Point at the target from whichever side has room.
            float startX = target.right + dp(34);
            float endX = target.right + dp(6);
            if (startX > getWidth() - dp(12)) {
                startX = target.left - dp(34);
                endX = target.left - dp(6);
            }
            drawArrow(canvas, startX, endX, target.centerY());
            return;
        }
        boolean below = target.bottom + dp(48) <= getHeight();
        float y = below ? target.bottom + dp(16) : target.top - dp(16);
        float half = Math.min(target.width() * 0.3f, dp(72));
        if (dragDirection != GuideDragDirection.UNKNOWN) {
            drawArrow(canvas, target.centerX() - dragDirection * half, target.centerX() + dragDirection * half, y);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(18));
        paint.setFakeBoldText(true);
        canvas.drawText(dragInstruction(),
                Math.max(dp(16), target.left), below ? y + dp(30) : y - dp(14), paint);
        paint.setFakeBoldText(false);
    }

    private String dragInstruction() {
        if (dragDirection == GuideDragDirection.LEFT) return "←  왼쪽으로 움직이세요";
        if (dragDirection == GuideDragDirection.RIGHT) return "오른쪽으로 움직이세요  →";
        return "안내에 따라 움직여 주세요";
    }

    private void drawArrow(Canvas canvas, float startX, float endX, float y) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(startX, y, endX, y, paint);
        path.reset();
        float direction = endX > startX ? 1f : -1f;
        path.moveTo(endX, y);
        path.lineTo(endX - direction * dp(12), y - dp(9));
        path.lineTo(endX - direction * dp(12), y + dp(9));
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
