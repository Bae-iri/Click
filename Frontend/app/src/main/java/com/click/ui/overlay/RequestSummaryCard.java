package com.click.ui.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.text.LineBreaker;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.click.R;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUi;

/** The request shown for confirmation in floating guidance and in the home walkthrough. */
public final class RequestSummaryCard extends LinearLayout {
    @SuppressLint("InlinedApi") // TextView accepts the inlined balanced strategy value since API 23.
    public RequestSummaryCard(Context context, String request, boolean compact) {
        super(context);
        setTag("review_summary_card");
        setOrientation(VERTICAL);
        int pad = ClickUi.dp(context, 16);
        setPadding(pad, pad, pad, pad);
        setBackground(FloatingPanelStyle.summary(getResources().getDisplayMetrics().density));
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(-1, -2));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_flow_document);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setTag("review_summary_icon");
        int iconSize = ClickUi.dp(context, 26);
        row.addView(icon, new LayoutParams(iconSize, iconSize));
        TextView summary = new TextView(context);
        summary.setTag("review_summary_text");
        summary.setText(request);
        summary.setTextSize(ClickTextScale.size(context, compact ? 15 : 17));
        summary.setTextColor(0xFF101A36);
        summary.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        summary.setIncludeFontPadding(false);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setLineSpacing(ClickUi.dp(context, 2), 1.1f);
        summary.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);
        LayoutParams label = new LayoutParams(0, -2, 1);
        label.setMarginStart(pad);
        row.addView(summary, label);
    }
}
