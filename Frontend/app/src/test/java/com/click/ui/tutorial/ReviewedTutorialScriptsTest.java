package com.click.ui.tutorial;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
public class ReviewedTutorialScriptsTest {
 @Test public void usesReviewedCopyOnlyForExactSource() {
  ReviewedTutorialScripts scripts=new ReviewedTutorialScripts(RuntimeEnvironment.getApplication());
  String source="클릭과 함께 시작해 볼까요?. 아래 클릭 버튼을 눌러보세요.";
  assertEquals("클릭과 함께 시작해 볼까요? 화면 아래의 클릭 버튼을 눌러보세요.",scripts.forSource(source));
  assertEquals("다른 버튼을 눌러주세요.",scripts.forSource("다른 버튼을 눌러주세요."));
 }
 @Test public void outdatedCopyDoesNotOverrideNewInstructions() {
  ReviewedTutorialScripts scripts=new ReviewedTutorialScripts(RuntimeEnvironment.getApplication());
  String updated="클릭과 함께 시작해 볼까요?. 위의 클릭 버튼을 눌러보세요.";
  assertEquals(updated,scripts.forSource(updated));
 }
}
