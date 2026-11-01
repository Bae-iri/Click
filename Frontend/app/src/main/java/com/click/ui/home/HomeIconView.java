package com.click.ui.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.click.R;

/** Consistent icons for the home cards and navigation. */
public final class HomeIconView extends View {
    public enum Kind { WORDMARK, CLICK, SETTINGS, FONT, KAKAO, BRIGHTNESS, MEDIA, PHONE,
        STAR, SEARCH, CHEVRON, HOME, HELP, BOOKMARK, ACCESSIBILITY }
    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Drawable artwork;
    private final Typeface bold;
    private int accent = 0xFF8993A4;

    public HomeIconView(Context context, Kind kind) {
        super(context);
        this.kind = kind;
        artwork = kind == Kind.WORDMARK ? context.getDrawable(com.click.designpreview.R.drawable.click)
                : kind == Kind.CLICK ? context.getDrawable(com.click.designpreview.R.drawable.ic_click_cursor)
                : kind == Kind.PHONE ? context.getDrawable(R.drawable.ic_home_phone) : null;
        if (artwork instanceof BitmapDrawable) ((BitmapDrawable) artwork).setFilterBitmap(true);
        bold = getResources().getFont(com.click.designpreview.R.font.pretendard_bold);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setAccentColor(int color) { accent = color; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (kind == Kind.WORDMARK) {
            float scale = Math.min(getWidth() / 1741f, getHeight() / 611f);
            canvas.save();
            canvas.translate((getWidth() - 1741 * scale) / 2, (getHeight() - 611 * scale) / 2);
            canvas.scale(scale, scale); canvas.translate(-211, -71);
            artwork.setBounds(0, 0, 2172, 724); artwork.draw(canvas);
            canvas.restore(); return;
        }
        float size = Math.min(getWidth(), getHeight());
        canvas.save();
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 48f, size / 48f);
        paint.setShader(null); paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case CLICK: artwork.setBounds(2, 2, 46, 46); artwork.draw(canvas); break;
            case PHONE: artwork.setBounds(0, 0, 48, 48); artwork.draw(canvas); break;
            case SETTINGS: gear(canvas, 14, 0xFF315CBC); break;
            case FONT:
                tile(canvas, 0xFFE7F1FF, 0xFFD8E8FF); gear(canvas, 16, 0xFF416FC5); break;
            case KAKAO:
                tile(canvas, 0xFFFFEF67, 0xFFFFE532);
                paint.setColor(0xFF3A2630); canvas.drawOval(9, 10, 39, 34, paint);
                path.reset(); path.moveTo(17, 30); path.lineTo(14, 39); path.lineTo(25, 32);
                path.close(); canvas.drawPath(path, paint);
                paint.setColor(0xFFFFE735); paint.setTypeface(bold); paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(9.5f); canvas.drawText("TALK", 24, 26, paint); break;
            case BRIGHTNESS:
                tile(canvas, 0xFFFFF4E1, 0xFFFFEFD4);
                paint.setColor(0xFFFF9F0B); canvas.drawCircle(24, 24, 9, paint);
                paint.setStrokeWidth(2.8f);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * i / 4;
                    canvas.drawLine(24 + (float) Math.cos(angle) * 13, 24 + (float) Math.sin(angle) * 13,
                            24 + (float) Math.cos(angle) * 17, 24 + (float) Math.sin(angle) * 17, paint);
                }
                break;
            case MEDIA:
                tile(canvas, 0xFFFFE8E9, 0xFFFFDDDF);
                paint.setShader(new LinearGradient(8, 12, 40, 36, 0xFFFF696D, 0xFFFF4D58, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(8, 11, 40, 37, 8, 8, paint);
                paint.setShader(null); paint.setColor(Color.WHITE);
                path.reset(); path.moveTo(21, 17); path.lineTo(31, 24); path.lineTo(21, 31);
                path.close(); canvas.drawPath(path, paint); break;
            case STAR:
                paint.setColor(0xFFFFF7E2); canvas.drawCircle(24, 24, 23, paint);
                paint.setShader(new LinearGradient(12, 10, 32, 37, 0xFFFFD42C, 0xFFFFB800, Shader.TileMode.CLAMP));
                paint.setPathEffect(new CornerPathEffect(1.3f)); path.reset();
                for (int i = 0; i < 10; i++) {
                    double angle = -Math.PI / 2 + i * Math.PI / 5;
                    float r = i % 2 == 0 ? 13 : 6.6f;
                    float x = 24 + (float) Math.cos(angle) * r, y = 24 + (float) Math.sin(angle) * r;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close(); canvas.drawPath(path, paint); break;
            case SEARCH:
                stroke(0xFF3478FF, 3.8f); canvas.drawCircle(21, 21, 13, paint);
                canvas.drawLine(31, 31, 41, 41, paint); break;
            case CHEVRON:
                stroke(accent, 3.4f); path.reset(); path.moveTo(18, 12);
                path.lineTo(29, 24); path.lineTo(18, 36); canvas.drawPath(path, paint); break;
            case HOME:
                paint.setColor(accent); path.reset(); path.moveTo(5, 22); path.lineTo(24, 6);
                path.lineTo(43, 22); path.lineTo(38, 22); path.lineTo(38, 42);
                path.lineTo(28, 42); path.lineTo(28, 29); path.lineTo(20, 29);
                path.lineTo(20, 42); path.lineTo(10, 42); path.lineTo(10, 22);
                path.close(); canvas.drawPath(path, paint); break;
            case HELP:
                stroke(accent, 3.3f); path.reset(); path.moveTo(36, 33);
                path.cubicTo(46, 16, 35, 6, 24, 6); path.cubicTo(1, 6, 0, 37, 23, 38);
                path.lineTo(40, 42); path.close(); canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) canvas.drawCircle(15 + i * 8, 23, 2, paint);
                break;
            case BOOKMARK:
                stroke(accent, 3.5f); path.reset(); path.moveTo(12, 42); path.lineTo(12, 11);
                path.quadTo(12, 7, 16, 7); path.lineTo(32, 7); path.quadTo(36, 7, 36, 11);
                path.lineTo(36, 42); path.lineTo(24, 33); path.close(); canvas.drawPath(path, paint); break;
            case ACCESSIBILITY:
                tile(canvas, 0xFFE7F1FF, 0xFFD8E8FF);
                paint.setColor(0xFF3478F6); canvas.drawCircle(24, 12, 4, paint);
                stroke(0xFF3478F6, 3.4f); canvas.drawLine(12, 20, 36, 20, paint);
                canvas.drawLine(24, 20, 24, 29, paint);
                canvas.drawLine(24, 29, 17, 40, paint); canvas.drawLine(24, 29, 31, 40, paint); break;
            default: break;
        }
        paint.setShader(null); paint.setPathEffect(null); canvas.restore();
    }

    private void tile(Canvas canvas, int top, int bottom) {
        paint.setShader(new LinearGradient(0, 0, 48, 48, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0, 0, 48, 48, 13, 13, paint); paint.setShader(null);
    }
    private void stroke(int color, float width) {
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width);
    }
    private void gear(Canvas canvas, float radius, int color) {
        paint.setColor(color); path.reset(); path.setFillType(Path.FillType.EVEN_ODD);
        for (int i = 0; i < 48; i++) {
            double angle = Math.PI * 2 * i / 48;
            float r = radius * (i % 6 == 2 || i % 6 == 3 ? 1f : .77f);
            float px = 24 + (float) Math.cos(angle) * r, py = 24 + (float) Math.sin(angle) * r;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close(); path.addCircle(24, 24, radius * .38f, Path.Direction.CW);
        canvas.drawPath(path, paint); path.setFillType(Path.FillType.WINDING);
    }
}
