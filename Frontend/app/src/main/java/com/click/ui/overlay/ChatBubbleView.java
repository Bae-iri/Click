package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** Transparent DM-style transcript: only individual message bubbles are painted. */
final class ChatBubbleView extends View {
    private static final int MAX_MESSAGES = 6;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Message> messages = new ArrayList<>();

    ChatBubbleView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
    }

    void add(String text, boolean user) {
        if (text == null || text.trim().isEmpty()) return;
        messages.add(new Message(text.trim(), user));
        while (messages.size() > MAX_MESSAGES) messages.remove(0);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float y = getHeight() - dp(8);
        for (int index = messages.size() - 1; index >= 0 && y > 0; index--) {
            Message message = messages.get(index);
            paint.setTextSize(dp(13));
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
            float maxTextWidth = getWidth() * .68f;
            List<String> lines = wrap(message.text, maxTextWidth);
            if (lines.size() > 3) lines = lines.subList(0, 3);
            float widest = 0f;
            for (String line : lines) widest = Math.max(widest, paint.measureText(line));
            float bubbleWidth = widest + dp(24);
            float bubbleHeight = lines.size() * dp(19) + dp(16);
            float left = message.user ? getWidth() - bubbleWidth - dp(8) : dp(8);
            RectF bubble = new RectF(left, y - bubbleHeight, left + bubbleWidth, y);
            paint.setColor(message.user ? Color.rgb(255, 59, 77) : Color.rgb(239, 241, 245));
            canvas.drawRoundRect(bubble, dp(17), dp(17), paint);
            paint.setColor(message.user ? Color.WHITE : Color.rgb(35, 40, 48));
            for (int line = 0; line < lines.size(); line++) {
                canvas.drawText(lines.get(line), bubble.left + dp(12),
                        bubble.top + dp(20) + line * dp(19), paint);
            }
            y = bubble.top - dp(7);
        }
    }

    private List<String> wrap(String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (!remaining.isEmpty()) {
            int count = Math.max(1, paint.breakText(remaining, true, maxWidth, null));
            lines.add(remaining.substring(0, count));
            remaining = remaining.substring(count);
        }
        return lines;
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class Message {
        final String text;
        final boolean user;

        Message(String text, boolean user) {
            this.text = text;
            this.user = user;
        }
    }
}
