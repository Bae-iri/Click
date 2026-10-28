package com.click.data.snapshot;

/** Geometry of a local crop. OCR boxes are always restored to physical screen pixels. */
public final class VisionImageRegion {
    public final int left, top, right, bottom;
    public VisionImageRegion(int left, int top, int right, int bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    public int width() { return right - left; }
    public int height() { return bottom - top; }
    public int[] toScreen(int[] box, int inputWidth, int inputHeight) {
        if (inputWidth <= 0 || inputHeight <= 0 || box == null || box.length != 4)
            throw new IllegalArgumentException("Invalid OCR geometry");
        return new int[]{left + Math.round(box[0] * width() / (float) inputWidth),
                top + Math.round(box[1] * height() / (float) inputHeight),
                left + Math.round(box[2] * width() / (float) inputWidth),
                top + Math.round(box[3] * height() / (float) inputHeight)};
    }
    public static VisionImageRegion[] regions(int width, int height, int[] focus) {
        if (focus != null && focus.length == 4 && focus[0] >= 0 && focus[1] >= 0
                && focus[2] <= width && focus[3] <= height && focus[0] < focus[2] && focus[1] < focus[3])
            return new VisionImageRegion[]{new VisionImageRegion(focus[0], focus[1], focus[2], focus[3])};
        // Overlap preserves words/rows crossing the middle of the screen.
        return new VisionImageRegion[]{new VisionImageRegion(0, 0, width, Math.max(1, height * 3 / 5)),
                new VisionImageRegion(0, height * 2 / 5, width, height)};
    }
}
