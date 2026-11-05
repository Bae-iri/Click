package com.click.ui.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Original Clue artwork with a soft contact shadow beneath its feet. */
public final class ClueHomeArtworkView extends View {
    private final Drawable clue;
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    public ClueHomeArtworkView(Context context) {
        super(context);
        clue = context.getDrawable(com.click.designpreview.R.drawable.clue1_home);
        shadow.setShader(new RadialGradient(0, 0, 1,
                new int[]{0x304A6D9B, 0x124A6D9B, 0x004A6D9B},
                new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2f, top = getHeight() - size;
        canvas.save();
        canvas.translate(left + size * .52f, top + size * .94f);
        canvas.scale(size * .29f, size * .045f);
        canvas.drawCircle(0, 0, 1, shadow);
        canvas.restore();
        clue.setBounds(Math.round(left), Math.round(top), Math.round(left + size), Math.round(top + size));
        clue.draw(canvas);
    }
}
