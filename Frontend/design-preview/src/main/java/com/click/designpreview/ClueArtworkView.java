package com.click.designpreview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Keeps the original PNG intact and draws a soft contact shadow beneath Clue's feet. */
public final class ClueArtworkView extends ImageView {
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF artworkBounds = new RectF();

    public ClueArtworkView(Context context) { this(context, null); }
    public ClueArtworkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.FIT_CENTER);
        setPadding(0, 0, 0, Math.round(8 * getResources().getDisplayMetrics().density));
        shadow.setShader(new RadialGradient(0, 0, 1,
                new int[]{0x384A6D9B, 0x164A6D9B, 0x004A6D9B},
                new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getDrawable() != null) {
            artworkBounds.set(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
            getImageMatrix().mapRect(artworkBounds);
            artworkBounds.offset(getPaddingLeft(), getPaddingTop());
            canvas.save();
            canvas.translate(artworkBounds.centerX(), artworkBounds.top + artworkBounds.height() * .96f);
            canvas.scale(artworkBounds.width() * .29f, artworkBounds.height() * .04f);
            canvas.drawCircle(0, 0, 1, shadow);
            canvas.restore();
        }
        super.onDraw(canvas);
    }
}
