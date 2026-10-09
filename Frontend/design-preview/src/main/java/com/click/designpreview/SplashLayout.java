package com.click.designpreview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Keeps the brand above center and the signature above the system navigation area. */
public final class SplashLayout extends FrameLayout {
    public SplashLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int contentHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
        boolean compact = contentHeight < dp(520);

        View waves = findViewById(R.id.splash_waves);
        measureExactly(waves, width, Math.round(Math.min(height * .39f, width * .88f)));

        int textWidth = MeasureSpec.makeMeasureSpec(Math.max(0, contentWidth - dp(40)), MeasureSpec.AT_MOST);
        int textHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        findViewById(R.id.splash_tagline).measure(textWidth, textHeight);
        findViewById(R.id.splash_signature).measure(textWidth, textHeight);

        int logoWidth = Math.round(Math.min(dp(compact ? 132 : 248), contentWidth * .64f));
        int wordmarkGap = getResources().getDimensionPixelSize(R.dimen.splash_logo_wordmark_gap);
        // Reserve the text, lifted signature, and gaps before sizing the logo on short screens.
        int availableLogoHeight = Math.max(0, contentHeight
                - findViewById(R.id.splash_tagline).getMeasuredHeight()
                - findViewById(R.id.splash_signature).getMeasuredHeight()
                - dp(compact ? 12 : 34) - dp(compact ? 8 : 24)
                - dp(compact ? 12 : 26) - dp(8 + 28));
        logoWidth = Math.min(logoWidth,
                Math.max(0, (int) Math.floor((availableLogoHeight - wordmarkGap) * 224f / 194f)));
        measureExactly(findViewById(R.id.splash_logo), logoWidth,
                Math.min(availableLogoHeight, Math.round(logoWidth * 194f / 224f) + wordmarkGap));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int contentHeight = height - getPaddingTop() - getPaddingBottom();
        int centerX = getPaddingLeft() + (width - getPaddingLeft() - getPaddingRight()) / 2;
        boolean compact = contentHeight < dp(520);
        int contentLift = dp(compact ? 8 : 24);

        View waves = findViewById(R.id.splash_waves);
        waves.layout(0, height - waves.getMeasuredHeight(), width, height);

        View signature = findViewById(R.id.splash_signature);
        int signatureTop = height - getPaddingBottom() - dp(compact ? 12 : 34)
                - signature.getMeasuredHeight() - contentLift;
        placeCentered(signature, centerX, signatureTop);

        View logo = findViewById(R.id.splash_logo);
        View tagline = findViewById(R.id.splash_tagline);
        int gap = dp(compact ? 12 : 26);
        int desiredTop = getPaddingTop() + Math.round(contentHeight * (compact ? .06f : .25f))
                - contentLift;
        int latestTop = signatureTop - dp(28) - tagline.getMeasuredHeight() - gap - logo.getMeasuredHeight();
        int logoTop = Math.max(getPaddingTop() + dp(8), Math.min(desiredTop, latestTop));
        placeCentered(logo, centerX, logoTop);
        placeCentered(tagline, centerX, logoTop + logo.getMeasuredHeight() + gap);
    }

    private void measureExactly(View view, int width, int height) {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    private void placeCentered(View view, int centerX, int top) {
        int left = centerX - view.getMeasuredWidth() / 2;
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
