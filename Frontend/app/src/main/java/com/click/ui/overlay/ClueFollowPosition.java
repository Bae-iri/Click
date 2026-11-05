package com.click.ui.overlay;

import android.graphics.Rect;

/** Finds the nearest position beside a highlight, entirely inside the usable screen. */
public final class ClueFollowPosition {
    private ClueFollowPosition() {}

    public static Rect beside(Rect target, Rect safe, int width, int height, int gap, int x, int y) {
        if (target == null || target.isEmpty() || width <= 0 || height <= 0
                || safe.width() < width || safe.height() < height || !Rect.intersects(target, safe)) return null;
        Rect protectedArea = new Rect(target);
        protectedArea.inset(-gap, -gap);
        int centeredX = target.centerX() - width / 2;
        int centeredY = target.centerY() - height / 2;
        int nearbyX = target.width() >= width ? Math.max(target.left, Math.min(x, target.right - width)) : centeredX;
        int[][] candidates = {
                {protectedArea.right, centeredY}, {protectedArea.left - width, centeredY},
                {centeredX, protectedArea.top - height}, {centeredX, protectedArea.bottom},
                {nearbyX, protectedArea.top - height}, {nearbyX, protectedArea.bottom}
        };
        Rect best = null;
        long distance = Long.MAX_VALUE;
        for (int[] candidate : candidates) {
            int left = Math.max(safe.left, Math.min(candidate[0], safe.right - width));
            int top = Math.max(safe.top, Math.min(candidate[1], safe.bottom - height));
            Rect position = new Rect(left, top, left + width, top + height);
            if (Rect.intersects(position, protectedArea)) continue;
            long dx = left - x, dy = top - y;
            long score = dx * dx + dy * dy;
            if (score < distance) { best = position; distance = score; }
        }
        return best;
    }
}
