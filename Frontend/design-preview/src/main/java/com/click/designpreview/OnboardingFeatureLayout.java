package com.click.designpreview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Places real, scalable speech text over the decorative phone illustration. */
public final class OnboardingFeatureLayout extends FrameLayout {
    private int artTop;
    private int artHeight;
    private int sceneHeight;
    private boolean stacked;
    private boolean task;

    public OnboardingFeatureLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
    }

    void setTask(boolean task) {
        this.task = task;
        ((android.widget.ImageView) findViewById(R.id.onboarding_phone)).setImageResource(
                task ? R.drawable.clue3_tutorial : R.drawable.clue1_home);
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        View bubble = findViewById(R.id.onboarding_feature_bubble);
        bubble.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - dp(16)), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        stacked = true;
        artTop = stacked ? bubble.getMeasuredHeight() + dp(8) : 0;
        // The final page also carries signup and login actions. Keep its phone and Click badge
        // compact enough to remain fully visible above the fixed footer.
        int artWidth = Math.min(width, dp(240));
        artHeight = artWidth;
        sceneHeight = artTop + artHeight;
        findViewById(R.id.onboarding_phone).measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(artHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, resolveSize(sceneHeight, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int offset = Math.max(0, (b - t - sceneHeight) / 2);
        findViewById(R.id.onboarding_phone).layout(0, offset + artTop,
                width, offset + artTop + artHeight);
        View bubble = findViewById(R.id.onboarding_feature_bubble);
        int left = Math.max(0, (width - bubble.getMeasuredWidth()) / 2);
        int top = offset + (stacked ? 0 : Math.round(artHeight * (task ? .21f : .09f)));
        bubble.layout(left, top, left + bubble.getMeasuredWidth(), top + bubble.getMeasuredHeight());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
