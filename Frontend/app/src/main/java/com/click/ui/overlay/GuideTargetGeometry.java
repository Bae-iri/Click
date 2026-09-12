package com.click.ui.overlay;

/** Converts physical screen pixels to the guide View's local pixel coordinates. */
final class GuideTargetGeometry {
    private GuideTargetGeometry() {}

    static float[] toLocal(int[] screenBounds, int originX, int originY,
                           int viewWidth, int viewHeight) {
        if (screenBounds == null || screenBounds.length != 4
                || screenBounds[2] <= screenBounds[0] || screenBounds[3] <= screenBounds[1]
                || viewWidth <= 0 || viewHeight <= 0) return null;

        // Convert before clipping. Bounds are already pixels: never apply density or insets twice.
        float left = Math.max(0f, (float) screenBounds[0] - originX);
        float top = Math.max(0f, (float) screenBounds[1] - originY);
        float right = Math.min(viewWidth, (float) screenBounds[2] - originX);
        float bottom = Math.min(viewHeight, (float) screenBounds[3] - originY);
        if (right <= left || bottom <= top) return null;
        return new float[]{left, top, right, bottom};
    }
}
