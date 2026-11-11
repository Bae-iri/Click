package com.click.ui.home;

import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.click.ui.common.ClickUi;
import java.util.function.BiConsumer;
import java.util.ArrayList;
import java.util.List;

/** Long-press reordering on the vertical axis, with scrolling at the viewport edges. */
public final class LibraryDragController {
    private final LinearLayout notebook;
    private final ScrollView scroll;
    private final BiConsumer<String, Integer> move;
    private String dragging;
    private View draggingRow;
    private Drawable rowBackground;
    private final List<View> rows = new ArrayList<>();
    private final DecelerateInterpolator easing = new DecelerateInterpolator(1.5f);
    private int destination = -1;
    private boolean settling;
    private float rowElevation, grabOffset;
    private int scrollStep;
    private float lastScreenY;
    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (dragging == null || scrollStep == 0) return;
            scroll.scrollBy(0, scrollStep);
            updateRow();
            notebook.postOnAnimationDelayed(this, 16);
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
            if (settling) return true;
            stop();
            dragging = id; draggingRow = row;
            for (int i = 0; i < notebook.getChildCount(); i++) {
                View child = notebook.getChildAt(i);
                if (child.getTag() instanceof String && ((String) child.getTag()).startsWith("library_")) rows.add(child);
            }
            destination = rows.indexOf(row);
            rowBackground = row.getBackground(); rowElevation = row.getElevation();
            int[] location = new int[2]; row.getLocationOnScreen(location);
            grabOffset = Math.max(0, Math.min(downY[0] - location[1], row.getHeight()));
            lastScreenY = downY[0];
            row.setPressed(false);
            row.setElevation(ClickUi.dp(row.getContext(), 8));
            row.setBackground(ClickUi.rounded(0xFFF1F6FF, ClickUi.dp(row.getContext(), 12)));
            row.animate().scaleX(1.015f).scaleY(1.015f).setDuration(160).setInterpolator(easing).start();
            notebook.requestDisallowInterceptTouchEvent(true);
            return true;
        });
        row.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downY[0] = event.getRawY();
                return false;
            }
            if (draggingRow != row) return false;
            if (settling) return true;
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
                    lastScreenY = event.getRawY();
                    updateRow();
                    settle(true);
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    settle(false);
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
        int next = position(top + draggingRow.getHeight() / 2f);
        if (next != destination) {
            destination = next;
            animateSlots();
        }
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

    private float animateSlots() {
        List<View> ordered = new ArrayList<>(rows);
        ordered.remove(draggingRow);
        ordered.add(Math.max(0, Math.min(destination, ordered.size())), draggingRow);
        float top = rows.get(0).getTop(), landing = draggingRow.getTop();
        for (int i = 0; i < ordered.size(); i++) {
            View child = ordered.get(i);
            if (child == draggingRow) landing = top;
            else child.animate().translationY(top - child.getTop()).setDuration(200).setInterpolator(easing).start();
            float gap = i + 1 < rows.size() ? rows.get(i + 1).getTop() - rows.get(i).getBottom() : 0;
            top += child.getHeight() + gap;
        }
        return landing;
    }

    private void settle(boolean commit) {
        settling = true;
        scrollStep = 0;
        notebook.removeCallbacks(autoScroll);
        String moved = dragging;
        int position = destination;
        float landing;
        if (commit) landing = animateSlots();
        else {
            landing = draggingRow.getTop();
            for (View child : rows) if (child != draggingRow)
                child.animate().translationY(0).setDuration(200).setInterpolator(easing).start();
        }
        draggingRow.animate().translationY(landing - draggingRow.getTop()).scaleX(1).scaleY(1)
                .setDuration(200).setInterpolator(easing).withEndAction(() -> {
                    stop();
                    if (commit) move.accept(moved, position);
                }).start();
    }
    private void stop() {
        notebook.removeCallbacks(autoScroll);
        for (View row : rows) {
            row.animate().withEndAction(null).cancel();
            row.setTranslationY(0);
            row.setScaleX(1); row.setScaleY(1);
        }
        rows.clear();
        if (draggingRow != null) {
            draggingRow.setTranslationY(0);
            draggingRow.setElevation(rowElevation);
            draggingRow.setBackground(rowBackground);
            notebook.requestDisallowInterceptTouchEvent(false);
        }
        dragging = null; draggingRow = null; rowBackground = null; scrollStep = 0;
        destination = -1; settling = false;
    }
}
