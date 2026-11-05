package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.annotation.NonNull;

public final class TrashDropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active;
    private final android.graphics.drawable.Drawable artwork;

    public TrashDropView(Context context) {
        super(context);
        artwork = context.getDrawable(com.click.designpreview.R.drawable.icon_trashcan_red);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setContentDescription("여기에 놓으면 클릭 숨기기");
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        animate().scaleX(active ? 1.08f : 1f).scaleY(active ? 1.08f : 1f).setDuration(160L).start();
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(dp(32), Math.min(getWidth(), getHeight()) / 2f - dp(10));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0xFFE34D59 : 0xFFFFF2F3);
        paint.setShadowLayer(dp(6), 0, dp(2), 0x24B73445);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.clearShadowLayer();
        if (!active) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xFFF5C6CC);
            canvas.drawCircle(cx, cy, radius, paint);
        }
        int half = Math.round(dp(24));
        artwork.setBounds(Math.round(cx) - half, Math.round(cy) - half,
                Math.round(cx) + half, Math.round(cy) + half);
        artwork.draw(canvas);
        paint.setStyle(Paint.Style.FILL);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
