package com.click.ui.tutorial;
import org.junit.Test;
import static org.junit.Assert.*;
public class TutorialNarrationTextTest {
    @Test public void examplesRemainVisualButDoNotEnterSpeech() {
        assertEquals("마이크를 눌렀을 때만 들어요.", TutorialNarrationText.instructionsOnly("마이크를 눌렀을 때만 들어요.\n예: 휴대폰 글자 크기 키워줘"));
        assertEquals("입력한 뒤 화살표를 눌러 보내요.", TutorialNarrationText.instructionsOnly("예: 글자 크기 키워줘\n입력한 뒤 화살표를 눌러 보내요."));
    }
    @Test public void choicesAndInstructionsAreStillSpoken() {
        assertEquals("휴대폰 기본 글자, 특정 앱 안에서만 중에서 선택해 주세요.", TutorialNarrationText.instructionsOnly("휴대폰 기본 글자, 특정 앱 안에서만 중에서 선택해 주세요."));
    }
}
