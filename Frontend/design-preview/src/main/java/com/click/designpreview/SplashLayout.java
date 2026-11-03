package com.click.designpreview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** The same responsive splash composition is rendered by the app and Studio preview. */
public final class SplashLayout extends FrameLayout {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path topWave = new Path(), bottomWave = new Path();
    private Shader topGradient, bottomGradient;

    public SplashLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width == 0 || height == 0) return;
        background.setShader(new RadialGradient(width * .44f, height * .48f,
                Math.max(width, height) * .7f, new int[]{0xFFFEFEFF, 0xFFF6F9FF},
                null, Shader.TileMode.CLAMP));
        topWave.reset();
        topWave.moveTo(0, 0); topWave.lineTo(width * .475f, 0);
        topWave.cubicTo(width * .39f, height * .13f, width * .18f, height * .24f,
                0, height * .27f);
        topWave.close();
        bottomWave.reset();
        bottomWave.moveTo(width * .46f, height);
        bottomWave.cubicTo(width * .60f, height * .87f, width * .78f, height * .79f,
                width, height * .737f);
        bottomWave.lineTo(width, height); bottomWave.close();
        topGradient = new LinearGradient(0, height * .27f, width * .35f, 0,
                0xFFF5F9FF, 0xFFD6E5FF, Shader.TileMode.CLAMP);
        bottomGradient = new LinearGradient(width * .60f, height * .80f, width, height,
                new int[]{0xFFE9F5FF, 0xFFD7E8FF, 0xFFB1BBFF},
                new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), background);
        wave.setShader(topGradient); canvas.drawPath(topWave, wave);
        wave.setShader(bottomGradient); canvas.drawPath(bottomWave, wave);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int contentHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
        View tagline = findViewById(R.id.splash_tagline);
        tagline.measure(MeasureSpec.makeMeasureSpec(Math.max(0, contentWidth - dp(48)), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int gap = dp(contentHeight < dp(400) ? 16 : 22);
        int availableHeight = Math.max(0, contentHeight - tagline.getMeasuredHeight() - gap - dp(32));
        int logoWidth = Math.min(dp(240), Math.round(contentWidth * .52f));
        logoWidth = Math.min(logoWidth, availableHeight * 222 / 72);
        findViewById(R.id.splash_logo).measure(
                MeasureSpec.makeMeasureSpec(logoWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(logoWidth * 72f / 222f), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int contentHeight = bottom - top - getPaddingTop() - getPaddingBottom();
        int centerX = getPaddingLeft() + (width - getPaddingLeft() - getPaddingRight()) / 2;
        View logo = findViewById(R.id.splash_logo);
        View tagline = findViewById(R.id.splash_tagline);
        int gap = dp(contentHeight < dp(400) ? 16 : 22);
        int groupHeight = logo.getMeasuredHeight() + gap + tagline.getMeasuredHeight();
        int desiredTop = Math.round((bottom - top) * .47f) - groupHeight / 2;
        int logoTop = Math.max(getPaddingTop() + dp(8), Math.min(desiredTop,
                bottom - top - getPaddingBottom() - dp(8) - groupHeight));
        placeCentered(logo, centerX, logoTop);
        placeCentered(tagline, centerX, logoTop + logo.getMeasuredHeight() + gap);
    }

    private void placeCentered(View view, int centerX, int top) {
        int left = centerX - view.getMeasuredWidth() / 2;
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
