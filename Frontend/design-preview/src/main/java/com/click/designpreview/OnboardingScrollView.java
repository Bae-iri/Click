package com.click.designpreview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/** The welcome page fits its viewport; the detailed example retains its natural height. */
public final class OnboardingScrollView extends ScrollView {
    public OnboardingScrollView(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void measureChildWithMargins(View child, int widthSpec, int widthUsed,
                                                     int heightSpec, int heightUsed) {
        View welcome = child.findViewById(R.id.onboarding_welcome_scene);
        if (welcome == null || welcome.getVisibility() != VISIBLE
                || MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) {
            super.measureChildWithMargins(child, widthSpec, widthUsed, heightSpec, heightUsed);
            return;
        }
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
        int childWidth = getChildMeasureSpec(widthSpec, getPaddingLeft() + getPaddingRight()
                + params.leftMargin + params.rightMargin + widthUsed, params.width);
        int height = Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom()
                - params.topMargin - params.bottomMargin - heightUsed);
        child.measure(childWidth, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
