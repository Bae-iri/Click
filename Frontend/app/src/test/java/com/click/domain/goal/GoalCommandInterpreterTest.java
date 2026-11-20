package com.click.domain.goal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class GoalCommandInterpreterTest {
    @Test public void typesSupportedGoal() {
        assertEquals(GoalCommandInterpreter.Kind.START_TASK,
                GoalCommandInterpreter.interpret("글자 크기 키워줘", false, false, false).kind);
    }
    @Test public void supportsDecreaseAsItsOwnGoal() {
        assertEquals(GoalCommandInterpreter.Kind.START_TASK,
                GoalCommandInterpreter.interpret("글자 크기 줄여줘", false, false, false).kind);
    }
    @Test public void asksWhichKindOfSize() {
        assertEquals(GoalCommandInterpreter.Kind.NEEDS_CLARIFICATION,
                GoalCommandInterpreter.interpret("화면 크게 해줘", false, false, false).kind);
    }
    @Test public void confirmationOnlyUsesOpenConfirmation() {
        assertEquals(GoalCommandInterpreter.Kind.CONFIRM_ACCEPTED,
                GoalCommandInterpreter.interpret("응", true, false, true).kind);
        assertEquals(GoalCommandInterpreter.Kind.UNSUPPORTED,
                GoalCommandInterpreter.interpret("응", true, false, false).kind);
    }
    @Test public void explicitInsteadBecomesReplacement() {
        assertEquals(GoalCommandInterpreter.Kind.REPLACE_TASK,
                GoalCommandInterpreter.interpret("하던 것 대신 글자 크기 키워줘", true, false, false).kind);
    }
    @Test public void recognizesClickExitPhrases() {
        assertEquals(GoalCommandInterpreter.Kind.EXIT_APPLICATION,
                GoalCommandInterpreter.interpret("클릭 종료", true, false, false).kind);
        assertEquals(GoalCommandInterpreter.Kind.EXIT_APPLICATION,
                GoalCommandInterpreter.interpret("꺼져", true, false, false).kind);
    }

    @Test public void businessCancellationIsNotMistakenForQueueCancellation() throws Exception {
        GoalCatalogClassifier catalog = loadCatalog();
        GoalCommandInterpreter.Result orderCancel = GoalCommandInterpreter.interpret(
                "쿠팡에서 한 주문 취소해줘", true, false, false, catalog, "com.coupang.mobile");
        assertEquals(GoalCommandInterpreter.Kind.UNSUPPORTED, orderCancel.kind);
        org.junit.Assert.assertNull(orderCancel.goal);

        GoalCommandInterpreter.Result queueCancel = GoalCommandInterpreter.interpret(
                "이 작업 취소해줘", true, false, false, catalog, "com.coupang.mobile");
        assertEquals(GoalCommandInterpreter.Kind.CANCEL_TASK, queueCancel.kind);
    }

    @Test public void pauseResumeAndNegativeCommandsRespectCurrentState() throws Exception {
        GoalCatalogClassifier catalog = loadCatalog();
        assertEquals(GoalCommandInterpreter.Kind.PAUSE_TASK,
                GoalCommandInterpreter.interpret("일시 정지해", true, false, false,
                        catalog, "com.android.settings").kind);
        assertEquals(GoalCommandInterpreter.Kind.PAUSE_TASK,
                GoalCommandInterpreter.interpret("잠깐 기다려 봐", true, false, false,
                        catalog, "com.android.settings").kind);
        assertEquals(GoalCommandInterpreter.Kind.RESUME_TASK,
                GoalCommandInterpreter.interpret("이어서 계속해", true, true, false,
                        catalog, "com.android.settings").kind);
        assertEquals(GoalCommandInterpreter.Kind.NEEDS_CLARIFICATION,
                GoalCommandInterpreter.interpret("스톱", true, false, false,
                        catalog, "com.android.settings").kind);
        assertEquals(GoalCommandInterpreter.Kind.INTERPRET_REQUEST,
                GoalCommandInterpreter.interpret("글자 크기 크게 하지 마", false, false, false,
                        catalog, "com.android.settings").kind);
        assertEquals(GoalCommandInterpreter.Kind.INTERPRET_REQUEST,
                GoalCommandInterpreter.interpret("글자 크기 크게 안 해 줘", false, false, false,
                        catalog, "com.android.settings").kind);
        assertEquals(GoalCommandInterpreter.Kind.INTERPRET_REQUEST,
                GoalCommandInterpreter.interpret("와이파이가 꺼지지 않게 해줘", false, false, false,
                        catalog, "com.android.settings").kind);
    }

    @Test public void contextualRequestsNeverChooseLocalKeywordGoalsOrNegatedControls() throws Exception {
        GoalCatalogClassifier catalog = loadCatalog();
        for (String source : new String[]{"글자를 키우지 말고 줄여줘", "글자 말고 밝기만 올려줘",
                "작업 취소하지 마", "취소하지 말고 계속해줘", "잠깐 기다려 봐 하지 말고 와이파이 켜줘",
                "카카오톡 말고 휴대폰 전체 글자를 줄여줘"}) {
            GoalCommandInterpreter.Result result = GoalCommandInterpreter.interpret(
                    source, true, true, true, catalog, "com.android.settings");
            assertEquals(source, GoalCommandInterpreter.Kind.INTERPRET_REQUEST, result.kind);
            org.junit.Assert.assertFalse(result.canStart());
            org.junit.Assert.assertNull(result.goal);
            assertTrue(GoalCommandInterpreter.explicitTaskRequest(source, catalog, null));
        }
        assertEquals(GoalCommandInterpreter.Kind.CANCEL_TASK,
                GoalCommandInterpreter.interpret("이 작업 취소해줘", true, false, false).kind);
        assertEquals(GoalCommandInterpreter.Kind.CONFIRM_REJECTED,
                GoalCommandInterpreter.interpret("아니오", true, false, true).kind);
    }

    @Test public void classifiesSettingsAndGovernmentGoalsFromSharedCatalog() throws Exception {
        GoalCatalogClassifier catalog = loadCatalog();
        assertEquals(139, catalog.size());

        GoalCommandInterpreter.Result font = GoalCommandInterpreter.interpret(
                "글씨가 너무 커서 좀 작게 해 줘", false, false, false, catalog, "com.android.settings");
        assertEquals(GoalCommandInterpreter.Kind.START_TASK, font.kind);
        assertTrue(font.canStart());
        assertEquals("settings.font_size.decrease", font.goal.goalType);

        GoalCommandInterpreter.Result wifi = GoalCommandInterpreter.interpret(
                "와이파이 좀 켜 줘", false, false, false, catalog, "com.android.settings");
        assertEquals(GoalCommandInterpreter.Kind.START_TASK, wifi.kind);
        assertTrue(wifi.canStart());
        assertNotNull(wifi.goal);
        assertEquals("settings.wifi.enable", wifi.goal.goalType);

        GoalCommandInterpreter.Result coupang = GoalCommandInterpreter.interpret(
                "장바구니에 이거 담아줘", false, false, false, catalog, "com.coupang.mobile");
        assertEquals(GoalCommandInterpreter.Kind.UNSUPPORTED, coupang.kind);
        GoalCommandInterpreter.Result government=GoalCommandInterpreter.interpret(
                "정부24 주민등록등본 발급 화면 찾아줘",false,false,false,catalog,"kr.go.minwon.m");
        assertEquals(GoalCommandInterpreter.Kind.PUBLIC_GUIDE,government.kind);
        assertEquals("gov24.resident_copy.open",government.goal.goalType);
        org.junit.Assert.assertFalse(government.canStart());
        GoalCommandInterpreter.Result planned=GoalCommandInterpreter.interpret(
                "정부24 전입신고 화면 찾아줘",false,false,false,catalog,"kr.go.minwon.m");
        assertEquals(GoalCommandInterpreter.Kind.PUBLIC_GUIDE,planned.kind);
        assertEquals("gov24.move_in.open",planned.goal.goalType);
        org.junit.Assert.assertFalse(planned.canStart());
    }

    private static GoalCatalogClassifier loadCatalog() throws Exception {
        Path[] candidates = {
                Path.of("..", "..", "AI-Agent", "goal_catalog", "mvp_goals.json"),
                Path.of("..", "AI-Agent", "goal_catalog", "mvp_goals.json"),
                Path.of("AI-Agent", "goal_catalog", "mvp_goals.json")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return GoalCatalogClassifier.fromJson(
                        new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8));
            }
        }
        throw new IllegalStateException("shared goal catalog not found from " + Path.of("").toAbsolutePath());
    }
}
