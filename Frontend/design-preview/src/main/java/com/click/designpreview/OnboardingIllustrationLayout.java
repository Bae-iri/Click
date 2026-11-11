package com.click.designpreview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Keeps the speech bubble clear of the face, including with enlarged system text. */
public final class OnboardingIllustrationLayout extends FrameLayout {
    private int characterTop;
    private int sceneHeight;

    public OnboardingIllustrationLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        View bubble = findViewById(R.id.onboarding_bubble);
        bubble.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - dp(24)), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        characterTop = bubble.getMeasuredHeight() + dp(8);
        int available = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : Math.max(0, MeasureSpec.getSize(heightMeasureSpec) - characterTop - dp(8));
        int characterWidth = Math.min(available, Math.min(dp(250), Math.round(width * .76f)));
        int characterHeight = characterWidth;
        measureExactly(findViewById(R.id.onboarding_character), characterWidth, characterHeight);
        int blobWidth = Math.min(width, dp(350));
        measureExactly(findViewById(R.id.onboarding_blob), blobWidth,
                Math.round(blobWidth * .9f));
        sceneHeight = characterTop + characterHeight + dp(8);
        setMeasuredDimension(width, resolveSize(sceneHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int offset = Math.max(0, (bottom - top - sceneHeight) / 2);
        View character = findViewById(R.id.onboarding_character);
        View blob = findViewById(R.id.onboarding_blob);
        // Keep the combined character and right-hand bubble visually balanced.
        int characterLeft = Math.max(0, (width - character.getMeasuredWidth()) / 2);
        place(character, characterLeft, offset + characterTop);
        place(blob, (width - blob.getMeasuredWidth()) / 2,
                offset + characterTop + Math.round(character.getMeasuredHeight() * .08f));
        View bubble = findViewById(R.id.onboarding_bubble);
        int bubbleTop = 0;
        place(bubble, Math.max(0, (width - bubble.getMeasuredWidth()) / 2), offset + bubbleTop);
    }

    private void measureExactly(View view, int width, int height) {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    private void place(View view, int left, int top) {
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
