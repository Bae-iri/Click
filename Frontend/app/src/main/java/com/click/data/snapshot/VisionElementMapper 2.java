package com.click.data.snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps OCR line boxes to screen coordinates and enriches them with Accessibility semantics. */
public final class VisionElementMapper {
    public static final float MIN_CONFIDENCE = 0.65f;

    private VisionElementMapper() {}

    public static final class OcrLine {
        public final String text;
        public final int[] imageBounds;
        public final Float confidence;

        public OcrLine(String text, int[] imageBounds, Float confidence) {
            this.text = text;
            this.imageBounds = imageBounds == null ? null : imageBounds.clone();
            this.confidence = confidence;
        }
    }

    public static List<VisionOcrElement> map(
            List<OcrLine> lines,
            int imageWidth,
            int imageHeight,
            ScreenSnapshot screen
    ) {
        List<VisionOcrElement> output = new ArrayList<>();
        if (imageWidth <= 0 || imageHeight <= 0 || screen.screenWidth <= 0 || screen.screenHeight <= 0) {
            return output;
        }
        Map<String, VisionOcrElement> unique = new LinkedHashMap<>();
        int index = 0;
        for (OcrLine line : lines) {
            String text = line.text == null ? "" : line.text.trim();
            // Unknown confidence is not equivalent to a mediocre-but-usable result.
            // Abstain so the user is never guided from uncalibrated OCR output.
            if (line.confidence == null) continue;
            float confidence = line.confidence;
            if (text.isEmpty() || line.imageBounds == null || line.imageBounds.length != 4
                    || confidence < MIN_CONFIDENCE) continue;
            int[] bounds = scaleAndClamp(line.imageBounds, imageWidth, imageHeight,
                    screen.screenWidth, screen.screenHeight);
            if (bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) continue;
            AccessibilityNodeSnapshot semantic = bestSemanticNode(bounds, screen.nodes);
            if (semantic != null && semantic.sensitive) continue;
            String role = semantic == null ? "text" : semantic.role;
            boolean semanticControl = semantic != null && (semantic.clickable
                    || semantic.checkable || semantic.scrollable);
            // OCR never performs a tap; "actionable" only makes the box eligible for a
            // user-facing guide. Prefer a real Accessibility control when one exists.
            boolean actionable = true;
            int[] targetBounds = semanticControl ? semantic.bounds : bounds;
            String key = normalize(text) + ':' + quantize(targetBounds);
            VisionOcrElement candidate = new VisionOcrElement(
                    "ocr_" + index++, text, targetBounds, role, confidence, actionable);
            VisionOcrElement previous = unique.get(key);
            if (previous == null || candidate.confidence > previous.confidence) unique.put(key, candidate);
        }
        output.addAll(unique.values());
        return output;
    }

    static int[] scaleAndClamp(int[] source, int sourceWidth, int sourceHeight,
                               int targetWidth, int targetHeight) {
        int left = clamp(Math.round(source[0] * (float) targetWidth / sourceWidth), 0, targetWidth);
        int top = clamp(Math.round(source[1] * (float) targetHeight / sourceHeight), 0, targetHeight);
        int right = clamp(Math.round(source[2] * (float) targetWidth / sourceWidth), left, targetWidth);
        int bottom = clamp(Math.round(source[3] * (float) targetHeight / sourceHeight), top, targetHeight);
        return new int[]{left, top, right, bottom};
    }

    private static AccessibilityNodeSnapshot bestSemanticNode(
            int[] bounds, List<AccessibilityNodeSnapshot> nodes) {
        int centerX = (bounds[0] + bounds[2]) / 2;
        int centerY = (bounds[1] + bounds[3]) / 2;
        AccessibilityNodeSnapshot best = null;
        long bestArea = Long.MAX_VALUE;
        for (AccessibilityNodeSnapshot node : nodes) {
            if (!node.visibleToUser || !node.enabled || node.bounds == null || node.bounds.length != 4) continue;
            if (centerX < node.bounds[0] || centerX > node.bounds[2]
                    || centerY < node.bounds[1] || centerY > node.bounds[3]) continue;
            long area = (long) (node.bounds[2] - node.bounds[0]) * (node.bounds[3] - node.bounds[1]);
            boolean useful = node.sensitive || node.clickable || node.checkable || node.scrollable
                    || node.role != null;
            if (useful && area < bestArea) {
                best = node;
                bestArea = area;
            }
        }
        return best;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String quantize(int[] bounds) {
        return (bounds[0] / 8) + "," + (bounds[1] / 8) + ","
                + (bounds[2] / 8) + "," + (bounds[3] / 8);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }
}
