package com.click.designpreview;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Button;
import android.widget.LinearLayout;

/** A single accessible button containing a centered label and decorative vector arrow. */
public final class OnboardingStartButton extends LinearLayout {
    public OnboardingStartButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return Button.class.getName();
    }
}
