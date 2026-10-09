package com.click.ui.overlay;

/** Fits a floating panel into the currently unobscured area without losing its resting position. */
public final class FloatingPanelPosition {
    public final int y;
    public final int height;

    private FloatingPanelPosition(int y, int height) {
        this.y = y;
        this.height = height;
    }

    public static FloatingPanelPosition fit(int restingY, int desiredHeight, int top, int bottom) {
        int height = Math.min(desiredHeight, Math.max(0, bottom - top));
        int y = Math.max(top, Math.min(restingY, bottom - height));
        return new FloatingPanelPosition(y, height);
    }
}
