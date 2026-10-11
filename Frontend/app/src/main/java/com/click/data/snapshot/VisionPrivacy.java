package com.click.data.snapshot;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Fail-closed export policy for the current settings graph; no free-form OCR text. */
public final class VisionPrivacy {
    private static final Set<String> LABELS = new HashSet<>(Arrays.asList(
            "설정", "settings", "디스플레이", "display", "화면", "screen", "접근성", "accessibility",
            "시인성 향상", "시인성", "visibility", "visibility enhancements", "글자 크기", "글꼴 크기",
            "폰트 크기", "text size", "글자 크기와 스타일", "글꼴 크기 및 스타일", "font size", "font size and style"));

    private VisionPrivacy() {}

    public static String exportLabel(String text) {
        if (text == null) throw new IllegalArgumentException("Missing OCR text");
        String label = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return LABELS.contains(label) ? label : null;
    }

    public static void requireMaskBounds(int[] bounds, int width, int height) {
        if (bounds == null || bounds.length != 4 || width <= 0 || height <= 0
                || bounds[0] < 0 || bounds[1] < 0 || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]
                || bounds[2] > width || bounds[3] > height) {
            throw new IllegalArgumentException("Cannot safely mask input field");
        }
    }
}
