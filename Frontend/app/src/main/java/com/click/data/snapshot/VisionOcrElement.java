package com.click.data.snapshot;

import java.util.Arrays;

/** Immutable OCR output in physical screen coordinates. */
public final class VisionOcrElement {
    public final String elementId;
    public final String text;
    public final int[] bounds;
    public final String role;
    public final float confidence;
    public final boolean actionable;

    public VisionOcrElement(String elementId, String text, int[] bounds, String role,
                            float confidence, boolean actionable) {
        this.elementId = elementId;
        this.text = text == null ? "" : text.trim();
        this.bounds = bounds == null ? null : bounds.clone();
        this.role = role;
        this.confidence = Math.max(0f, Math.min(1f, confidence));
        this.actionable = actionable;
    }

    public boolean isValid(int width, int height) {
        return elementId != null && !elementId.isEmpty() && !text.isEmpty()
                && bounds != null && bounds.length == 4
                && 0 <= bounds[0] && bounds[0] < bounds[2] && bounds[2] <= width
                && 0 <= bounds[1] && bounds[1] < bounds[3] && bounds[3] <= height;
    }

    @Override public String toString() {
        return elementId + ':' + text + '@' + Arrays.toString(bounds);
    }
}
