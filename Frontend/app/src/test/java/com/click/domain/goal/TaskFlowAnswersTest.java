package com.click.domain.goal;

import org.junit.Test;
import static org.junit.Assert.*;

public class TaskFlowAnswersTest {
    @Test public void resultAnswersStayDistinct() {
        assertEquals("satisfied", TaskFlowAnswers.result("네, 잘 보여요"));
        assertEquals("satisfied", TaskFlowAnswers.result("네, 좋아요"));
        assertEquals("needs_help", TaskFlowAnswers.result("조금 더 도와주세요"));
        assertEquals("needs_help", TaskFlowAnswers.result("아직 불편해요"));
        assertEquals("stopped", TaskFlowAnswers.result("그만할게요"));
        assertNull(TaskFlowAnswers.result("눌렀어요"));
        assertNull(TaskFlowAnswers.result(""));
        assertNull(TaskFlowAnswers.result("잘 보이는지 모르겠어요"));
    }
    @Test public void causeDoesNotAutomaticallyMeanLarger() {
        assertEquals("too_small", TaskFlowAnswers.adjustment("글자가 아직 작아요"));
        assertEquals("too_large", TaskFlowAnswers.adjustment("글자가 너무 커요"));
        assertEquals("other", TaskFlowAnswers.adjustment("크기 말고 다른 점이 불편해요"));
        assertEquals("unsure", TaskFlowAnswers.adjustment("잘 모르겠어요"));
        assertNull(TaskFlowAnswers.adjustment("아직 불편해요"));
        assertEquals("too_small", TaskFlowAnswers.adjustment("화면이 아직 어두워요"));
        assertEquals("too_large", TaskFlowAnswers.adjustment("화면이 너무 밝아요"));
    }
}
