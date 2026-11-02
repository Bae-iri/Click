package com.click.designpreview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** A light home-style backdrop with an unframed logo and comfortably spaced copy. */
public final class SplashLayout extends FrameLayout {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SplashLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        background.setShader(new RadialGradient(width + dp(4), height * .30f, dp(260),
                new int[]{0xFFDFF1FF, 0xFFECF7FF, 0xFFFCFDFF},
                new float[]{0, .67f, 1}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), background);
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
        int gap = dp(contentHeight < dp(400) ? 20 : 30);
        int availableHeight = Math.max(0, contentHeight - tagline.getMeasuredHeight() - gap - dp(32));
        int logoWidth = Math.min(dp(222), Math.max(0, contentWidth - dp(64)));
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
        int gap = dp(contentHeight < dp(400) ? 20 : 30);
        int groupHeight = logo.getMeasuredHeight() + gap + tagline.getMeasuredHeight();
        int desiredTop = getPaddingTop() + Math.round(contentHeight * .45f) - groupHeight / 2;
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
