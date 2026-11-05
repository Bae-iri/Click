package com.click.ui.home;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.click.ui.common.ClickUi;

/** A horizontal swipe reveals a separate delete action; vertical gestures keep scrolling. */
public final class SwipeDeleteRow extends FrameLayout {
    private final View content;
    private final TextView delete;
    private final int reveal, slop;
    private float downX, downY, initial;
    private boolean horizontal, vertical, closeTap;

    public SwipeDeleteRow(Context context, View content, String label, Runnable remove) {
        super(context);
        this.content = content;
        reveal = ClickUi.dp(context, 72);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        delete = new TextView(context);
        delete.setText("삭제"); delete.setTextSize(16); delete.setTextColor(0xFFFFFFFF);
        delete.setGravity(Gravity.CENTER); delete.setContentDescription(label + " 기록 삭제");
        delete.setBackground(ClickUi.rounded(0xFFE94467, ClickUi.dp(context, 12)));
        delete.setFocusable(true); delete.setVisibility(INVISIBLE);
        delete.setOnClickListener(v -> remove.run());
        addView(delete, new LayoutParams(reveal, -1, Gravity.END));
        content.setBackground(ClickUi.rounded(0xFFFFFEFC, ClickUi.dp(context, 12)));
        content.setElevation(0);
        addView(content, new LayoutParams(-1, -2));
        ViewCompat.addAccessibilityAction(content, "기록 삭제 버튼 보기", (view, args) -> {
            settle(true); delete.requestFocus(); return true;
        });
        content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        content.setContentDescription(label);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); initial = content.getTranslationX();
            horizontal = false; vertical = false;
            closeTap = initial < 0 && downX < getWidth() - reveal;
            return closeTap;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !vertical) {
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) vertical = true;
            else if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                horizontal = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - downX;
            if (Math.abs(dx) > slop) horizontal = true;
            if (horizontal) {
                getParent().requestDisallowInterceptTouchEvent(true);
                content.setTranslationX(Math.max(-reveal, Math.min(0, initial + dx)));
                delete.setVisibility(content.getTranslationX() < 0 ? VISIBLE : INVISIBLE);
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            settle(horizontal && content.getTranslationX() < -reveal / 2f);
            getParent().requestDisallowInterceptTouchEvent(false);
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            settle(initial < 0);
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    private void settle(boolean open) {
        content.setTranslationX(open ? -reveal : 0);
        delete.setVisibility(open ? VISIBLE : INVISIBLE);
    }
}
