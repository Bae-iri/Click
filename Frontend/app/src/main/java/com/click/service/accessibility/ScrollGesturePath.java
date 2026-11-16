package com.click.service.accessibility;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A short swipe must avoid controls along its actual segment, not an infinite line. */
final class ScrollGesturePath {
    static float[] vertical(Rect viewport, int distance, boolean forward, List<Rect> obstacles, int padding) {
        if (viewport == null || viewport.isEmpty() || distance <= 0 || padding < 0
                || viewport.height() - 2 * padding < distance || viewport.width() <= 2 * padding) return null;
        float bestScore = Float.MAX_VALUE;
        float[] best = null;
        List<Float> lanes = new ArrayList<>();
        for (float fraction : new float[]{.5f,.3f,.7f,.2f,.8f,.1f,.9f,.05f,.95f})
            lanes.add(viewport.left + viewport.width() * fraction);
        for (Rect obstacle : obstacles) {
            lanes.add((float) obstacle.left - padding - 1);
            lanes.add((float) obstacle.right + padding + 1);
        }
        for (float x : lanes) {
            if (x < viewport.left + padding || x > viewport.right - padding) continue;
            List<Rect> blocked = new ArrayList<>();
            for (Rect obstacle : obstacles)
                if (!obstacle.isEmpty() && x >= obstacle.left - padding && x <= obstacle.right + padding)
                    blocked.add(obstacle);
            blocked.sort(Comparator.comparingInt(r -> r.top));
            float gapTop = viewport.top + padding;
            for (int i = 0; i <= blocked.size(); i++) {
                float gapBottom = i == blocked.size() ? viewport.bottom - padding
                        : Math.min(viewport.bottom - padding, blocked.get(i).top - padding - 1);
                if (gapBottom - gapTop >= distance) {
                    float center = Math.max(gapTop + distance / 2f,
                            Math.min(viewport.exactCenterY(), gapBottom - distance / 2f));
                    float score = Math.abs(center - viewport.exactCenterY()) + Math.abs(x - viewport.exactCenterX());
                    if (score < bestScore) {
                        bestScore = score;
                        float top = center - distance / 2f, bottom = center + distance / 2f;
                        best = new float[]{x, forward ? bottom : top, x, forward ? top : bottom};
                    }
                }
                if (i < blocked.size()) gapTop = Math.max(gapTop, blocked.get(i).bottom + padding + 1);
            }
        }
        return best;
    }

    private ScrollGesturePath() {}
}
