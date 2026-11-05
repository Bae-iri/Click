package com.click.designpreview;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Keeps the dialog header and actions reachable while the body scrolls. */
public final class AccountDialogLayout extends LinearLayout {
    public AccountDialogLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int width, int height) {
        int limit = Math.round(getResources().getDisplayMetrics().heightPixels * .82f);
        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED)
            limit = Math.min(limit, MeasureSpec.getSize(height));
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
    }
}
