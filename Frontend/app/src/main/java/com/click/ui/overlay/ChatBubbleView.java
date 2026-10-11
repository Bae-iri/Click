package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.click.ui.common.ClickUi;

/** Readable, scrollable messages that also respect the user's system font size. */
final class ChatBubbleView extends ScrollView {
    private static final int MAX_MESSAGES = 6;
    private final LinearLayout messages;
    private final TextView placeholder;

    ChatBubbleView(Context context) {
        super(context);
        setFillViewport(true);
        setClipToPadding(false);
        setPadding(dp(16), dp(16), dp(16), dp(16));
        messages = new LinearLayout(context);
        messages.setOrientation(LinearLayout.VERTICAL);
        addView(messages, new LayoutParams(-1, -2));
        placeholder = text("예: 글자 크기를 키워줘");
        placeholder.setTextColor(ClickUi.SUBTEXT);
        placeholder.setGravity(Gravity.CENTER);
        messages.addView(placeholder, new LinearLayout.LayoutParams(-1, -1));
    }

    void add(String value, boolean user) {
        if (value == null || value.trim().isEmpty()) return;
        messages.removeView(placeholder);
        TextView bubble = text(value.trim());
        bubble.setTextColor(user ? Color.WHITE : ClickUi.TEXT);
        bubble.setPadding(dp(14), dp(12), dp(14), dp(12));
        bubble.setBackground(ClickUi.rounded(user ? ClickUi.BLUE : Color.WHITE, dp(18)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.gravity = user ? Gravity.END : Gravity.START;
        params.topMargin = dp(8);
        if (user) params.leftMargin = dp(24);
        else params.rightMargin = dp(24);
        messages.addView(bubble, params);
        while (messages.getChildCount() > MAX_MESSAGES) messages.removeViewAt(0);
        post(() -> fullScroll(FOCUS_DOWN));
    }

    void setPlaceholder(String value) {
        placeholder.setText(value);
    }

    private TextView text(String value) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(17);
        text.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        text.setLineSpacing(dp(4), 1.1f);
        return text;
    }

    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
