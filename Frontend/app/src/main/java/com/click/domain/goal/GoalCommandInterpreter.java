package com.click.domain.goal;

import java.util.Locale;

/** FE-owned interpretation for the single catalogued MVP goal and control commands. */
public final class GoalCommandInterpreter {
    public enum Kind { START_TASK, REPLACE_TASK, PAUSE_TASK, RESUME_TASK, CANCEL_TASK, CONFIRM_ACCEPTED,
        CONFIRM_REJECTED, NEEDS_CLARIFICATION, UNSUPPORTED }

    public static final class Result {
        public final Kind kind;
        public final String message;
        Result(Kind kind, String message) { this.kind = kind; this.message = message; }
        public boolean canStart() { return kind == Kind.START_TASK; }
    }

    private GoalCommandInterpreter() {}

    public static Result interpret(String source, boolean hasActiveTask,
                                   boolean paused, boolean confirmationOpen) {
        String text = normalize(source);
        if (confirmationOpen && (text.equals("응") || text.equals("네") || text.equals("예") || text.equals("그래")))
            return new Result(Kind.CONFIRM_ACCEPTED, null);
        if (confirmationOpen && (text.equals("아니") || text.equals("아니오") || text.equals("싫어")))
            return new Result(Kind.CONFIRM_REJECTED, null);
        if (text.contains("취소") || text.contains("안할래"))
            return new Result(hasActiveTask ? Kind.CANCEL_TASK : Kind.UNSUPPORTED, "진행 중인 작업이 없어요.");
        if (text.contains("이어서") || text.contains("계속해"))
            return new Result(paused ? Kind.RESUME_TASK : Kind.UNSUPPORTED, "일시정지한 작업이 없어요.");
        if (text.contains("잠깐기다려") || text.contains("일단멈춰"))
            return new Result(hasActiveTask ? Kind.PAUSE_TASK : Kind.UNSUPPORTED, "진행 중인 작업이 없어요.");
        if (text.equals("그만") || text.contains("멈춰") || text.equals("중단해"))
            return new Result(Kind.NEEDS_CLARIFICATION, "잠시 멈출까요, 작업을 취소할까요?");
        boolean font = text.contains("글자") || text.contains("글씨") || text.contains("폰트")
                || text.contains("fontsize") || text.contains("textsize");
        boolean decrease = text.contains("줄여") || text.contains("작게") || text.contains("낮춰");
        boolean negative = text.contains("하지마") || text.contains("키우지마");
        boolean increase = text.contains("키워") || text.contains("크게") || text.contains("확대")
                || text.contains("작아");
        if (text.contains("화면크게") && !font)
            return new Result(Kind.NEEDS_CLARIFICATION, "글자 크기와 화면 전체 크기 중 무엇을 키울까요?");
        if (font && (decrease || negative))
            return new Result(Kind.UNSUPPORTED, "현재는 글자 크기 확대만 지원해요.");
        if (font && increase)
            return new Result(hasActiveTask && text.contains("대신") ? Kind.REPLACE_TASK : Kind.START_TASK, null);
        return new Result(Kind.UNSUPPORTED, "현재는 글자 크기 확대 요청만 지원해요.");
    }

    private static String normalize(String source) {
        return source == null ? "" : source.toLowerCase(Locale.ROOT).replaceAll("[\\s,.!?]", "");
    }
}
