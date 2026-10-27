package com.click.domain.goal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RepeatRequestTest {
    @Test public void recognizesAskBackPhrases() {
        for (String text : new String[]{"다시 말해줘", "뭐라고?", "뭐라고요", "뭐라고 했어?", "뭐라구?", "뭐?",
                "못 들었어", "잘 안 들려요", "한 번 더 말해줘", "다시 한번 말씀해 주세요", "무슨 말이야?",
                "못 알아들었어", "다시 알려줘", "다시요"})
            assertTrue(text, RepeatRequest.matches(text));
    }
    @Test public void bareInterjectionsOnlyCountAsQuestions() {
        assertTrue(RepeatRequest.matches("응?"));
        assertTrue(RepeatRequest.matches("네?"));
        assertTrue(RepeatRequest.matches(" 어? "));
        assertFalse(RepeatRequest.matches("응"));
        assertFalse(RepeatRequest.matches("네"));
        assertFalse(RepeatRequest.matches("예."));
    }
    @Test public void leavesGoalsControlsAndAnswersAlone() {
        for (String text : new String[]{"다시 시작해", "글자 크기 키워줘", "잘 보여요", "아직 불편해요",
                "그만", "잠깐만", "", "   "})
            assertFalse(text, RepeatRequest.matches(text));
        assertFalse(RepeatRequest.matches(null));
    }
}
