package com.click.ui.home;

import android.content.ClipData;
import android.view.DragEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.click.ui.common.ClickUi;
import java.util.function.BiConsumer;

/** Long-press native drag/drop, including scrolling at the viewport edges. */
public final class LibraryDragController implements View.OnDragListener {
    private final LinearLayout notebook;
    private final ScrollView scroll;
    private final BiConsumer<String, Integer> move;
    private String dragging;
    private View highlighted;
    private int scrollStep;
    private float lastScreenY;
    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (dragging == null || scrollStep == 0) return;
            scroll.scrollBy(0, scrollStep);
            int[] location = new int[2]; notebook.getLocationOnScreen(location);
            highlight(lastScreenY - location[1]);
            notebook.postDelayed(this, 24);
        }
    };

    public LibraryDragController(LinearLayout notebook, ScrollView scroll, BiConsumer<String, Integer> move) {
        this.notebook = notebook; this.scroll = scroll; this.move = move;
        notebook.setOnDragListener(this);
        notebook.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) { stop(); }
        });
    }

    public static void prepare(View row, String id) {
        row.setOnLongClickListener(view -> view.startDragAndDrop(
                ClipData.newPlainText("library", id), new View.DragShadowBuilder(view), id, 0));
    }

    @Override public boolean onDrag(View view, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                if (!(event.getLocalState() instanceof String)) return false;
                String id = (String) event.getLocalState();
                if (notebook.findViewWithTag("library_" + id) == null) return false;
                dragging = id;
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                int[] notebookLocation = new int[2], scrollLocation = new int[2];
                notebook.getLocationOnScreen(notebookLocation); scroll.getLocationOnScreen(scrollLocation);
                lastScreenY = notebookLocation[1] + event.getY();
                int edge = ClickUi.dp(view.getContext(), 64), step = ClickUi.dp(view.getContext(), 12);
                int newStep = lastScreenY < scrollLocation[1] + scroll.getPaddingTop() + edge ? -step
                        : lastScreenY > scrollLocation[1] + scroll.getHeight() - ClickUi.dp(view.getContext(), 100) ? step : 0;
                scrollStep = newStep;
                notebook.removeCallbacks(autoScroll);
                if (scrollStep != 0) notebook.post(autoScroll);
                highlight(event.getY());
                return true;
            case DragEvent.ACTION_DROP:
                if (dragging == null) return false;
                String moved = dragging; int position = position(event.getY());
                stop();
                notebook.post(() -> move.accept(moved, position));
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                scrollStep = 0; notebook.removeCallbacks(autoScroll); clearHighlight(); return true;
            case DragEvent.ACTION_DRAG_ENDED:
                stop(); return true;
            default: return true;
        }
    }

    private int position(float y) {
        int index = 0;
        for (int i = 0; i < notebook.getChildCount(); i++) {
            View child = notebook.getChildAt(i);
            if (!(child.getTag() instanceof String) || !((String) child.getTag()).startsWith("library_")
                    || ("library_" + dragging).equals(child.getTag())) continue;
            if (y < child.getTop() + child.getHeight() / 2f) break;
            index++;
        }
        return index;
    }

    private void highlight(float y) {
        clearHighlight();
        for (int i = 0; i < notebook.getChildCount(); i++) {
            View child = notebook.getChildAt(i);
            if (child.getTag() == null || ("library_" + dragging).equals(child.getTag())) continue;
            if (y >= child.getTop() && y <= child.getBottom()) {
                highlighted = child;
                child.setBackground(ClickUi.rounded(0xFFEAF2FF, ClickUi.dp(child.getContext(), 12)));
                break;
            }
        }
    }
    private void clearHighlight() {
        if (highlighted != null) highlighted.setBackgroundColor(0x00000000);
        highlighted = null;
    }
    private void stop() { dragging = null; scrollStep = 0; notebook.removeCallbacks(autoScroll); clearHighlight(); }
}
