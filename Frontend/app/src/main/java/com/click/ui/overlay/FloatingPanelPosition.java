package com.click.ui.overlay;

/** Fits a floating panel into the currently unobscured area without losing its resting position. */
public final class FloatingPanelPosition {
    public final int y;
    public final int height;

    private FloatingPanelPosition(int y, int height) {
        this.y = y;
        this.height = height;
    }

    /** IME accessibility bounds can include a transparent full-screen window above a toolbar. */
    public static boolean isDockedKeyboard(int screenHeight, int top, int bottom, int edgeTolerance) {
        return bottom >= screenHeight - edgeTolerance && top >= screenHeight / 3;
    }

    public static FloatingPanelPosition fit(int restingY, int desiredHeight, int top, int bottom) {
        int height = Math.min(desiredHeight, Math.max(0, bottom - top));
        int y = Math.max(top, Math.min(restingY, bottom - height));
        return new FloatingPanelPosition(y, height);
    }
}
