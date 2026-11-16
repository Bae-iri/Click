package com.click.designpreview;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** User speech on the right; a settings example and Clue explain the result side by side. */
public final class OnboardingFeatureLayout extends FrameLayout {
    private int phoneTop;
    private int companionHeight;
    private int sceneHeight;

    public OnboardingFeatureLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        View request = findViewById(R.id.onboarding_feature_bubble);
        request.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - dp(16)), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int gap = dp(10);
        int phoneWidth = Math.min(dp(220), Math.round(Math.max(0, width - gap) * .64f));
        int companionWidth = Math.max(0, width - phoneWidth - gap);
        View phone = findViewById(R.id.onboarding_phone);
        phone.measure(MeasureSpec.makeMeasureSpec(phoneWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        View guide = findViewById(R.id.onboarding_guide_bubble);
        guide.measure(MeasureSpec.makeMeasureSpec(companionWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int characterSize = Math.min(dp(150), companionWidth);
        findViewById(R.id.onboarding_guide_character).measure(
                MeasureSpec.makeMeasureSpec(characterSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(characterSize, MeasureSpec.EXACTLY));
        companionHeight = guide.getMeasuredHeight() + dp(8) + characterSize;
        phoneTop = request.getMeasuredHeight() + dp(20);
        sceneHeight = phoneTop + Math.max(phone.getMeasuredHeight(), companionHeight);
        setMeasuredDimension(width, resolveSize(sceneHeight, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int offset = 0;
        View request = findViewById(R.id.onboarding_feature_bubble);
        place(request, Math.max(0, width - request.getMeasuredWidth()), offset);
        View phone = findViewById(R.id.onboarding_phone);
        place(phone, 0, offset + phoneTop);
        View guide = findViewById(R.id.onboarding_guide_bubble);
        View character = findViewById(R.id.onboarding_guide_character);
        int companionLeft = phone.getRight() + dp(10);
        int companionWidth = Math.max(0, width - companionLeft);
        // Align the explanation with the highlighted row, keeping it clear of the phone.
        Rect target = new Rect();
        View highlight = phone.findViewWithTag("onboarding_font_size_highlight");
        highlight.getDrawingRect(target);
        offsetDescendantRectToMyCoords(highlight, target);
        int companionTop = Math.max(phone.getTop(), Math.min(target.centerY() - guide.getMeasuredHeight() / 2,
                offset + sceneHeight - companionHeight));
        place(guide, companionLeft + Math.max(0, (companionWidth - guide.getMeasuredWidth()) / 2), companionTop);
        place(character, companionLeft + Math.max(0, (companionWidth - character.getMeasuredWidth()) / 2),
                guide.getBottom() + dp(8));
    }

    private void place(View view, int left, int top) {
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
