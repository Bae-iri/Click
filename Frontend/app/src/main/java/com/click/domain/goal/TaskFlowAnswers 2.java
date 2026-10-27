package com.click.domain.goal;

/** Exact contextual answers; "I tapped it" is never a technical verification signal. */
public final class TaskFlowAnswers {
    private TaskFlowAnswers() {}
    public static String result(String source) {
        String s = GoalCatalogClassifier.normalize(source);
        if (s.equals("네") || s.equals("예") || s.equals("잘보여요") || s.equals("네잘보여요")
                || s.equals("이제잘보여요") || s.equals("보기편해요")) return "satisfied";
        if (s.equals("아직불편해요") || s.equals("잘안보여요") || s.equals("아니요")
                || s.equals("아니오")) return "needs_help";
        if (stop(s)) return "stopped";
        return null;
    }
    public static boolean stop(String source) {
        String s = GoalCatalogClassifier.normalize(source);
        return s.equals("그만") || s.equals("그만할게요") || s.equals("취소") || s.equals("중단");
    }
    public static String adjustment(String source) {
        String s = GoalCatalogClassifier.normalize(source);
        if (s.contains("작아") || s.contains("작아요") || s.equals("글자가아직작아요")) return "too_small";
        if (s.contains("너무커") || s.equals("커요")) return "too_large";
        if (s.contains("다른") || s.contains("크기말고")) return "other";
        if (s.contains("모르겠")) return "unsure";
        return null;
    }
}
