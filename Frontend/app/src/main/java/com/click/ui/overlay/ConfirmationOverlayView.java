package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

import com.click.ui.common.ClickUi;

public final class ConfirmationOverlayView extends LinearLayout {
    public interface Callback { void onDecision(boolean accepted); }

    public ConfirmationOverlayView(Context context, String message, Callback callback) {
        super(context);
        setOrientation(VERTICAL);
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
        text.setTypeface(getResources().getFont(
                com.click.designpreview.R.font.pretendard_semibold));
        text.setGravity(Gravity.CENTER);
        ScrollView disclosure = new ScrollView(context);
        disclosure.addView(text, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(disclosure, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(context);
        Button reject = ClickUi.secondaryButton(context, "아니요");
        Button accept = ClickUi.primaryButton(context, "네, 계속할게요");
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
