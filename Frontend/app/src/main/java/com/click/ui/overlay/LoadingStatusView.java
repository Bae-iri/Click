package com.click.ui.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Native indeterminate spinner stops animating automatically when hidden or detached. */
public final class LoadingStatusView extends LinearLayout {
    private final TextView label;
    public LoadingStatusView(Context context) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        int size = Math.round(22 * getResources().getDisplayMetrics().density);
        ProgressBar spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(0xFF3478F6));
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(spinner, new LayoutParams(size, size));
        label = new TextView(context);
        label.setTextSize(15);
        label.setTextColor(0xFF6F7F9E);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LayoutParams copy = new LayoutParams(0, -2, 1);
        copy.leftMargin = Math.round(10 * getResources().getDisplayMetrics().density);
        addView(label, copy);
        setVisibility(GONE);
    }
    public void setStatus(String message) {
        label.setText(message == null ? "" : message);
        setVisibility(message == null || message.trim().isEmpty() ? GONE : VISIBLE);
    }
}
