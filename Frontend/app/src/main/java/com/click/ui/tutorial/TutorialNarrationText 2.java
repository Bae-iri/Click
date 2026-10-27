package com.click.ui.tutorial;

/** Examples are visual prompts, never part of the spoken instruction or its replay. */
public final class TutorialNarrationText {
    private TutorialNarrationText() {}
    public static String instructionsOnly(String text) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\n")) {
            String value = line.trim();
            if (value.matches("^(예|예시)\\s*[:：].*")) continue;
            if (!value.isEmpty()) { if (result.length() > 0) result.append(' '); result.append(value); }
        }
        return result.toString();
    }
}
