package com.click.ui.home;

import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.click.ui.common.ClickUi;
import java.util.function.BiConsumer;

/** Long-press reordering on the vertical axis, with scrolling at the viewport edges. */
public final class LibraryDragController {
    private final LinearLayout notebook;
    private final ScrollView scroll;
    private final BiConsumer<String, Integer> move;
    private String dragging;
    private View draggingRow, highlighted;
    private Drawable rowBackground, highlightedBackground;
    private float rowElevation, grabOffset;
    private int scrollStep;
    private float lastScreenY;
    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (dragging == null || scrollStep == 0) return;
            scroll.scrollBy(0, scrollStep);
            updateRow();
            notebook.postDelayed(this, 24);
        }
    };

    public LibraryDragController(LinearLayout notebook, ScrollView scroll, BiConsumer<String, Integer> move) {
        this.notebook = notebook; this.scroll = scroll; this.move = move;
        notebook.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) { stop(); }
        });
    }

    public void prepare(View row, String id) {
        final float[] downY = {0};
        row.setOnLongClickListener(view -> {
            stop();
            dragging = id; draggingRow = row;
            rowBackground = row.getBackground(); rowElevation = row.getElevation();
            int[] location = new int[2]; row.getLocationOnScreen(location);
            grabOffset = Math.max(0, Math.min(downY[0] - location[1], row.getHeight()));
            lastScreenY = downY[0];
            row.setPressed(false);
            row.setElevation(ClickUi.dp(row.getContext(), 8));
            row.setBackground(ClickUi.rounded(0xFFF1F6FF, ClickUi.dp(row.getContext(), 12)));
            notebook.requestDisallowInterceptTouchEvent(true);
            return true;
        });
        row.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downY[0] = event.getRawY();
                return false;
            }
            if (draggingRow != row) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    lastScreenY = event.getRawY();
                    int[] location = new int[2]; scroll.getLocationOnScreen(location);
                    int edge = ClickUi.dp(view.getContext(), 64), step = ClickUi.dp(view.getContext(), 12);
                    scrollStep = lastScreenY < location[1] + scroll.getPaddingTop() + edge ? -step
                            : lastScreenY > location[1] + scroll.getHeight() - ClickUi.dp(view.getContext(), 100) ? step : 0;
                    notebook.removeCallbacks(autoScroll);
                    if (scrollStep != 0) notebook.post(autoScroll);
                    updateRow();
                    break;
                case MotionEvent.ACTION_UP:
                    String moved = dragging;
                    int position = position(localY(event.getRawY()));
                    stop();
                    notebook.post(() -> move.accept(moved, position));
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    stop();
                    break;
            }
            return true;
        });
    }

    private float localY(float screenY) {
        int[] location = new int[2]; notebook.getLocationOnScreen(location);
        return screenY - location[1];
    }

    private void updateRow() {
        float y = localY(lastScreenY);
        float top = Math.max(notebook.getPaddingTop(), Math.min(y - grabOffset,
                notebook.getHeight() - notebook.getPaddingBottom() - draggingRow.getHeight()));
        draggingRow.setTranslationY(top - draggingRow.getTop());
        highlight(y);
    }

    private int position(float y) {
        int index = 0;
        for (int i = 0; i < notebook.getChildCount(); i++) {
            View child = notebook.getChildAt(i);
            if (!(child.getTag() instanceof String) || !((String) child.getTag()).startsWith("library_")
                    || child == draggingRow) continue;
            if (y < child.getTop() + child.getHeight() / 2f) break;
            index++;
        }
        return index;
    }

    private void highlight(float y) {
        clearHighlight();
        for (int i = 0; i < notebook.getChildCount(); i++) {
            View child = notebook.getChildAt(i);
            if (!(child.getTag() instanceof String) || !((String) child.getTag()).startsWith("library_")
                    || child == draggingRow) continue;
            if (y >= child.getTop() && y <= child.getBottom()) {
                highlighted = child;
                highlightedBackground = child.getBackground();
                child.setBackground(ClickUi.rounded(0xFFEAF2FF, ClickUi.dp(child.getContext(), 12)));
                break;
            }
        }
    }
    private void clearHighlight() {
        if (highlighted != null) highlighted.setBackground(highlightedBackground);
        highlighted = null; highlightedBackground = null;
    }
    private void stop() {
        notebook.removeCallbacks(autoScroll);
        clearHighlight();
        if (draggingRow != null) {
            draggingRow.setTranslationY(0);
            draggingRow.setElevation(rowElevation);
            draggingRow.setBackground(rowBackground);
            notebook.requestDisallowInterceptTouchEvent(false);
        }
        dragging = null; draggingRow = null; rowBackground = null; scrollStep = 0;
    }
}
