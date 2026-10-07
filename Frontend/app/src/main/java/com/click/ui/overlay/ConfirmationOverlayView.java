package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.click.ui.common.ClickUi;

public final class ConfirmationOverlayView extends LinearLayout {
    public interface Callback { void onDecision(boolean accepted); }

    public ConfirmationOverlayView(Context context, String message, Callback callback) {
        this(context, message, "아니요", "네, 계속할게요", callback);
    }

    public ConfirmationOverlayView(
            Context context,
            String message,
            String rejectLabel,
            String acceptLabel,
            Callback callback
    ) {
        super(context);
        setOrientation(VERTICAL);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(message);
        setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(22));
        background.setStroke(dp(2), ClickUi.SECONDARY_RED);
        setBackground(background);

        TextView text = new TextView(context);
        text.setText(message);
        text.setTextSize(18);
        text.setTextColor(ClickUi.TEXT);
        text.setGravity(Gravity.CENTER);
        LayoutParams textParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        textParams.bottomMargin = dp(12);
        addView(text, textParams);

        LinearLayout actions = new LinearLayout(context);
        Button reject = ClickUi.secondaryButton(context, rejectLabel);
        Button accept = ClickUi.primaryButton(context, acceptLabel);
        reject.setOnClickListener(view -> callback.onDecision(false));
        accept.setOnClickListener(view -> callback.onDecision(true));
        actions.addView(reject, new LayoutParams(0, dp(52), 1f));
        LayoutParams acceptParams = new LayoutParams(0, dp(52), 1.35f);
        acceptParams.leftMargin = dp(8);
        actions.addView(accept, acceptParams);
        addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, dp(52)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
