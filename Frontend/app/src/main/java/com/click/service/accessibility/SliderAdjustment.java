package com.click.service.accessibility;
/** Range checks shared by native slider execution and its verification. */
public final class SliderAdjustment {
    private SliderAdjustment() {}
    public static boolean canMove(float current, float min, float max, int direction) {
        return Float.isFinite(current) && Float.isFinite(min) && Float.isFinite(max)
                && min < max && current >= min && current <= max
                && (direction == 1 && current < max || direction == -1 && current > min);
    }
    /** Discrete Android range indices move by one. Continuous ranges move by a bounded tenth. */
    public static float nextValue(float current, float min, float max, int direction, boolean integerRange) {
        if (!canMove(current, min, max, direction)) return Float.NaN;
        float step = integerRange ? 1f : (max - min) / 10f;
        return Math.max(min, Math.min(max, current + direction * step));
    }
    /** A bounded horizontal drag within the freshly verified slider; never a guessed screen point. */
    public static float[] dragPoints(int[] bounds, float before, float after, float min, float max) {
        if (bounds == null || bounds.length != 4 || !Float.isFinite(after) || before == after
                || !Float.isFinite(before) || !Float.isFinite(min) || !Float.isFinite(max) || max <= min
                || before < min || before > max || after < min || after > max
                || bounds[2] - bounds[0] < 2 * (bounds[3] - bounds[1]) || bounds[3] <= bounds[1]) return null;
        float padding = Math.min((bounds[3] - bounds[1]) / 2f, (bounds[2] - bounds[0]) * .1f);
        float length = bounds[2] - bounds[0] - 2 * padding;
        float start = bounds[0] + padding + length * (before - min) / (max - min);
        float end = bounds[0] + padding + length * (after - min) / (max - min);
        if (start < bounds[0] || start > bounds[2] || end < bounds[0] || end > bounds[2]) return null;
        return new float[]{start, (bounds[1] + bounds[3]) / 2f, end, (bounds[1] + bounds[3]) / 2f};
    }
    public static boolean moved(float before, float after, int direction) {
        return Float.isFinite(before) && Float.isFinite(after)
                && (direction == 1 && after > before || direction == -1 && after < before);
    }
}
