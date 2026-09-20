package com.click.domain.goal;

/** FE-owned interpretation for queue controls and data-driven typed goals. */
public final class GoalCommandInterpreter {
    public enum Kind { START_TASK, REPLACE_TASK, PAUSE_TASK, RESUME_TASK, CANCEL_TASK, EXIT_APPLICATION,
        CONFIRM_ACCEPTED, CONFIRM_REJECTED, CATALOGUED_UNAVAILABLE, NEEDS_CLARIFICATION, UNSUPPORTED }

    public static final class Result {
        public final Kind kind;
        public final String message;
        public final GoalCatalogClassifier.Match goal;

        Result(Kind kind, String message, GoalCatalogClassifier.Match goal) {
            this.kind = kind;
            this.message = message;
            this.goal = goal;
        }

        public boolean canStart() {
            return (kind == Kind.START_TASK || kind == Kind.REPLACE_TASK)
                    && goal != null && goal.isExecutable();
        }
    }

    private GoalCommandInterpreter() {}

    /** Compatibility entry point used by lightweight unit tests and old callers. */
    public static Result interpret(String source, boolean hasActiveTask,
                                   boolean paused, boolean confirmationOpen) {
        return interpret(source, hasActiveTask, paused, confirmationOpen, null, null);
    }

    public static Result interpret(String source, boolean hasActiveTask, boolean paused,
                                   boolean confirmationOpen, GoalCatalogClassifier catalog,
                                   String foregroundPackage) {
        String text = GoalCatalogClassifier.normalize(source);
        if (text.equals("클릭종료") || text.equals("꺼져") || text.equals("종료해")
                || text.equals("클릭꺼져"))
            return result(Kind.EXIT_APPLICATION, null);
        if (confirmationOpen && (text.equals("응") || text.equals("네") || text.equals("예") || text.equals("그래")))
            return result(Kind.CONFIRM_ACCEPTED, null);
        if (confirmationOpen && (text.equals("아니") || text.equals("아니오") || text.equals("싫어")))
            return result(Kind.CONFIRM_REJECTED, null);
        if (text.contains("취소") || text.contains("안할래"))
            return result(hasActiveTask ? Kind.CANCEL_TASK : Kind.UNSUPPORTED, "진행 중인 작업이 없어요.");
        if (text.contains("이어서") || text.contains("계속해"))
            return result(paused ? Kind.RESUME_TASK : Kind.UNSUPPORTED, "일시정지한 작업이 없어요.");
        if (text.contains("잠깐기다려") || text.contains("일단멈춰"))
            return result(hasActiveTask ? Kind.PAUSE_TASK : Kind.UNSUPPORTED, "진행 중인 작업이 없어요.");
        if (text.equals("그만") || text.contains("멈춰") || text.equals("중단해"))
            return result(Kind.NEEDS_CLARIFICATION, "잠시 멈출까요, 작업을 취소할까요?");
        if (text.contains("하지마") || text.contains("말아") || text.contains("키우지마"))
            return result(Kind.UNSUPPORTED, "요청한 변경을 실행하지 않을게요.");

        if (catalog != null) {
            GoalCatalogClassifier.Classification classification = catalog.classify(source, foregroundPackage);
            if (classification.ambiguous) {
                return result(Kind.NEEDS_CLARIFICATION, "비슷한 요청이 여러 개예요. 대상과 원하는 동작을 더 구체적으로 말씀해주세요.");
            }
            if (classification.matched()) {
                GoalCatalogClassifier.Match match = classification.match;
                if (!match.isExecutable()) {
                    return new Result(Kind.CATALOGUED_UNAVAILABLE,
                            match.displayName + " 요청으로 분류했어요. 이 작업의 실행 안내는 아직 승인되지 않았어요.", match);
                }
                Kind kind = hasActiveTask && text.contains("대신") ? Kind.REPLACE_TASK : Kind.START_TASK;
                return new Result(kind, null, match);
            }
        }

        // Legacy fallback keeps behavior stable if the catalog asset cannot be loaded.
        boolean font = text.contains("글자") || text.contains("글씨") || text.contains("폰트")
                || text.contains("fontsize") || text.contains("textsize");
        boolean decrease = text.contains("줄여") || text.contains("작게") || text.contains("낮춰")
                || text.contains("축소") || text.contains("너무커") || text.contains("커서");
        boolean increase = text.contains("키워") || text.contains("크게") || text.contains("확대")
                || text.contains("작아");
        if (text.contains("화면크게") && !font)
            return result(Kind.NEEDS_CLARIFICATION, "글자 크기와 화면 전체 크기 중 무엇을 키울까요?");
        if (font && (increase || decrease)) {
            Kind kind = hasActiveTask && text.contains("대신") ? Kind.REPLACE_TASK : Kind.START_TASK;
            return new Result(kind, null, null);
        }
        return result(Kind.UNSUPPORTED, "지원하는 설정이나 쿠팡 기능을 구체적으로 말씀해주세요.");
    }

    private static Result result(Kind kind, String message) { return new Result(kind, message, null); }
}
