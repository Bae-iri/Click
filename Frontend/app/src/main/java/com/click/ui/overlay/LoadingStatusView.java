package com.click.ui.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Native indeterminate spinner stops animating automatically when hidden or detached. */
public final class LoadingStatusView extends LinearLayout {
    private final ProgressBar spinner;
    private final TextView label;
    private final boolean reserveSpace;

    public LoadingStatusView(Context context) { this(context, false); }

    /** With reserveSpace the row keeps a single-line height, so the window does not resize while idle. */
    public LoadingStatusView(Context context, boolean reserveSpace) {
        super(context);
        this.reserveSpace = reserveSpace;
        setGravity(Gravity.CENTER_VERTICAL);
        int size = Math.round(18 * getResources().getDisplayMetrics().density);
        spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(0xFF3478F6));
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(spinner, new LayoutParams(size, size));
        label = new TextView(context);
        label.setTextSize(13);
        label.setTextColor(0xFF6F7F9E);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (reserveSpace) {
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
        }
        LayoutParams copy = new LayoutParams(0, -2, 1);
        copy.leftMargin = Math.round(8 * getResources().getDisplayMetrics().density);
        addView(label, copy);
        setStatus(null);
    }

    public boolean isActive() { return label.length() > 0; }

    public void setStatus(String message) {
        boolean active = message != null && !message.trim().isEmpty();
        label.setText(active ? message : "");
        if (reserveSpace) {
            setVisibility(VISIBLE);
            spinner.setVisibility(active ? VISIBLE : INVISIBLE);
        } else {
            setVisibility(active ? VISIBLE : GONE);
        }
    }
}
