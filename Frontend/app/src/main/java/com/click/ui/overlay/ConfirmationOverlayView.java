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
import com.click.ui.common.ClickTextScale;

public final class ConfirmationOverlayView extends LinearLayout {
    private int maximumHeight = Integer.MAX_VALUE;

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
        text.setTextSize(ClickTextScale.size(context, FloatingPanelStyle.INPUT_SIZE_SP));
        text.setIncludeFontPadding(false);
        text.setLineSpacing(dp(2), 1.1f);
        text.setTextColor(ClickUi.TEXT);
        text.setTypeface(getResources().getFont(
                com.click.designpreview.R.font.pretendard_semibold));
        text.setGravity(Gravity.CENTER);
        ScrollView disclosure = new ScrollView(context);
        disclosure.addView(text, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(disclosure, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(VERTICAL);
        Button reject = ClickUi.secondaryButton(context, "아니요");
        Button accept = ClickUi.primaryButton(context, "네, 계속할게요");
        reject.setOnClickListener(view -> callback.onDecision(false));
        accept.setOnClickListener(view -> callback.onDecision(true));
        for (Button button : new Button[]{accept, reject}) {
            button.setTextSize(ClickTextScale.size(context, FloatingPanelStyle.ACTION_SIZE_SP));
            button.setMinHeight(dp(52)); button.setMinimumHeight(dp(52));
            button.setPadding(dp(14), dp(12), dp(14), dp(12));
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(button == accept ? 12 : 8);
            actions.addView(button, params);
        }
        addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void setMaximumHeight(int heightPixels) {
        maximumHeight = Math.max(1, heightPixels);
        requestLayout();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int limit = mode == MeasureSpec.UNSPECIFIED ? maximumHeight : Math.min(size, maximumHeight);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit,
                mode == MeasureSpec.EXACTLY ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
