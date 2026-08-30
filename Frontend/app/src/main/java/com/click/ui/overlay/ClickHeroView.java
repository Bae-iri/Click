package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import androidx.annotation.NonNull;

import com.click.ui.common.ClickUi;

public final class ClickHeroView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public ClickHeroView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        paint.setColor(ClickUi.SECONDARY_RED);
        canvas.drawCircle(cx, cy, dp(58), paint);
        ClickIconRenderer.draw(this, canvas, paint, path, cx, cy, dp(42), 0f, false);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
