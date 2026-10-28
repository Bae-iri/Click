package com.click.service.accessibility;
/** Range checks shared by native slider execution and its verification. */
public final class SliderAdjustment {
    private SliderAdjustment() {}
    public static boolean canMove(float current, float min, float max, int direction) {
        return Float.isFinite(current) && Float.isFinite(min) && Float.isFinite(max)
                && min < max && current >= min && current <= max
                && (direction == 1 && current < max || direction == -1 && current > min);
    }
    public static boolean moved(float before, float after, int direction) {
        return Float.isFinite(before) && Float.isFinite(after)
                && (direction == 1 && after > before || direction == -1 && after < before);
    }
}
