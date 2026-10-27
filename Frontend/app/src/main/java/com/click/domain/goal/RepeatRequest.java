package com.click.domain.goal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Spoken asks to hear the last guide again ("다시 말해줘", "뭐라고?", "응?"). */
public final class RepeatRequest {
    private static final Set<String> EXACT = new HashSet<>(Arrays.asList(
            "뭐", "뭐요", "뭐야", "다시", "다시요", "한번더", "한번더요"));
    // Bare interjections double as "yes" answers, so they only count when asked as a question.
    private static final Set<String> QUESTION_ONLY = new HashSet<>(Arrays.asList(
            "응", "네", "예", "어", "음", "에", "엥", "잉"));
    private static final String[] CONTAINS = {
            "다시말해", "다시말씀", "다시한번말", "한번더말", "한번만더말", "다시얘기", "다시알려",
            "다시들려", "못들었", "못들은", "안들려", "안들렸", "잘안들", "못알아들", "무슨말",
            "뭔말", "뭐라는", "뭐라고", "뭐라구", "뭐라했", "뭐라하셨"};

    private RepeatRequest() {}

    public static boolean matches(String source) {
        if (source == null) return false;
        String n = GoalCatalogClassifier.normalize(source);
        if (n.isEmpty()) return false;
        if (EXACT.contains(n)) return true;
        if (QUESTION_ONLY.contains(n)) return source.trim().endsWith("?");
        for (String phrase : CONTAINS) if (n.contains(phrase)) return true;
        return false;
    }
}
