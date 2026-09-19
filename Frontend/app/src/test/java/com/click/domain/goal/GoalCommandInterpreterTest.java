package com.click.domain.goal;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GoalCommandInterpreterTest {
    @Test public void typesSupportedGoal() {
        assertEquals(GoalCommandInterpreter.Kind.START_TASK,
                GoalCommandInterpreter.interpret("글자 크기 키워줘", false, false, false).kind);
    }
    @Test public void doesNotTurnDecreaseIntoIncrease() {
        assertEquals(GoalCommandInterpreter.Kind.UNSUPPORTED,
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
}
