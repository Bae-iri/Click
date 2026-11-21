package com.click.ui.tutorial;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds a vertical gap that leaves both the real control and the floating panel visible. */
public final class ClueCoachPlacement {
    private ClueCoachPlacement() {}

    public static Rect available(int width, int top, int bottom, int gap, Rect... avoid) {
        List<Rect> occupied = new ArrayList<>();
        for (Rect bounds : avoid) if (bounds != null && !bounds.isEmpty()
                && bounds.bottom > top && bounds.top < bottom)
            occupied.add(new Rect(0, Math.max(top, bounds.top - gap), width, Math.min(bottom, bounds.bottom + gap)));
        occupied.sort(Comparator.comparingInt(r -> r.top));
        Rect best = new Rect(0, top, width, top);
        int cursor = top;
        for (Rect block : occupied) {
            if (block.top - cursor >= best.height()) best.set(0, cursor, width, block.top);
            cursor = Math.max(cursor, block.bottom);
        }
        if (bottom - cursor >= best.height()) best.set(0, cursor, width, bottom);
        return best;
    }
}
