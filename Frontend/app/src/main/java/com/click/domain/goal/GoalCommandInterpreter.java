package com.click.domain.goal;

/** FE-owned interpretation for queue controls and data-driven typed goals. */
public final class GoalCommandInterpreter {
    public enum Kind { START_TASK, REPLACE_TASK, PAUSE_TASK, RESUME_TASK, CANCEL_TASK, EXIT_APPLICATION,
        CONFIRM_ACCEPTED, CONFIRM_REJECTED, INTERPRET_REQUEST, PUBLIC_GUIDE, CATALOGUED_UNAVAILABLE, NEEDS_CLARIFICATION, UNSUPPORTED }

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
        if (requiresContextualInterpretation(source))
            return result(hasActiveTask && text.contains("대신") ? Kind.REPLACE_TASK : Kind.INTERPRET_REQUEST, null);

        // Resolve concrete business goals before generic controls. For example,
        // "쿠팡 주문 취소" is a commerce goal, while "이 작업 취소" is a queue control.
        if (catalog != null) {
            GoalCatalogClassifier.Classification classification = catalog.classify(source, foregroundPackage);
            if (classification.ambiguous) {
                return result(Kind.NEEDS_CLARIFICATION, "몇 가지 도움 중에서 확인하고 있어요. 어떤 앱에서 무엇을 하고 싶으신지 말씀해 주시겠어요?");
            }
            if (classification.matched()) {
                GoalCatalogClassifier.Match match = classification.match;
                if("public_guide".equals(match.executionStatus)) {
                    return new Result(Kind.PUBLIC_GUIDE,"정부24 공개 화면 안내를 서버에서 확인할게요.",match);
                }
                if (!match.isExecutable()) {
                    return new Result(Kind.CATALOGUED_UNAVAILABLE,
                            match.displayName + " 요청으로 분류했어요. 이 작업의 실행 안내는 아직 승인되지 않았어요.", match);
                }
                Kind kind = hasActiveTask && text.contains("대신") ? Kind.REPLACE_TASK : Kind.START_TASK;
                return new Result(kind, null, match);
            }
        }

        if (isCancelControl(text))
            return result(hasActiveTask ? Kind.CANCEL_TASK : Kind.UNSUPPORTED, "진행 중인 작업이 없어요.");
        if (isResumeControl(text))
            return result(paused ? Kind.RESUME_TASK : Kind.UNSUPPORTED, "일시정지한 작업이 없어요.");
        if (isPauseControl(text))
            return result(hasActiveTask ? Kind.PAUSE_TASK : Kind.UNSUPPORTED, "진행 중인 작업이 없어요.");
        if (text.equals("그만") || text.contains("멈춰") || text.equals("중단해")
                || text.equals("스톱") || text.equals("stop"))
            return result(Kind.NEEDS_CLARIFICATION, "잠시 멈출까요, 작업을 취소할까요?");

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
        return result(Kind.UNSUPPORTED, "설정이나 쿠팡에서 어떤 도움이 필요하신지 말씀해 주시겠어요?");
    }

    /** A complete new request is not an answer to a previous task's result question. */
    public static boolean explicitTaskRequest(String source, GoalCatalogClassifier catalog, String foregroundPackage) {
        if (source == null) return false;
        String normalized = GoalCatalogClassifier.normalize(source);
        if (normalized.contains("대신")) return false;
        if (requiresContextualInterpretation(source)) return true;
        if (catalog == null || normalized.matches(".*(멈춰|중단해|작업취소|일단멈춰|잠깐기다려|기다려봐).*")) return false;
        boolean action = normalized.matches(".*(켜|꺼|키워|줄여|낮춰|높여|열어|보여|바꿔|설정해|연결해|삭제해|켜고|끄고|키우고|줄이고|낮추고|높이고|열고|바꾸고|해줘|해주세요|open|connect|change|turn).*");
        GoalCatalogClassifier.Classification result = catalog.classify(source, foregroundPackage);
        return action && (result.matched() || result.ambiguous);
    }

    /** Routing only: keep negated/replaced clauses together for server interpretation. */
    public static boolean requiresContextualInterpretation(String source) {
        return GoalCatalogClassifier.normalize(source).matches(
                ".*(하지마|하지말|지마|지말|지않|안해|말아|말았|말고|아니라|아니고|대신|않"
                        + "|안켜|안꺼|안키|안끄|안줄|안올|안내려|안바꿔"
                        + "|못켜|못꺼|못키|못끄|못줄|못올|못내려|못바꿔).*");
    }

    private static boolean isCancelControl(String text) {
        return text.equals("취소") || text.equals("취소해") || text.equals("취소해줘")
                || text.equals("취소할래") || text.equals("안할래") || text.equals("이거안할래")
                || text.equals("그만할래") || text.equals("그거취소")
                || text.contains("작업취소");
    }

    private static boolean isResumeControl(String text) {
        return text.contains("이어서") || text.contains("계속해") || text.equals("재개")
                || text.equals("재개해") || text.equals("다시시작") || text.equals("다시시작해");
    }

    private static boolean isPauseControl(String text) {
        return text.contains("잠깐기다려") || text.contains("기다려봐") || text.contains("일단멈춰")
                || text.equals("일시정지") || text.equals("일시정지해")
                || text.equals("잠깐만") || text.equals("잠시멈춰");
    }

    private static Result result(Kind kind, String message) { return new Result(kind, message, null); }
}
