package com.click.service.overlay;
import com.click.domain.goal.GoalCatalogClassifier;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public class TaskSequenceTextTest {
 private GoalCatalogClassifier catalog() throws Exception {
  try(var in=getClass().getResourceAsStream("/mvp_goals.json")) {
   return GoalCatalogClassifier.fromJson(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
  }
 }
 @Test public void announcesTheTwoRequestedTasksInOrderWithoutQueueLanguage() throws Exception {
  assertEquals("글자 크기를 높이고 디스플레이 밝기를 높이겠습니다.",TaskSequenceText.introduction("휴대폰 글자 크기 키워줘",List.of("디스플레이 밝기를 높여줘"),catalog()));
 }
 @Test public void nextReviewNamesOnlyTheNextTask(){
  assertEquals("디스플레이 밝기를 높일까요?",TaskSequenceText.question("settings.brightness.increase"));
  assertEquals("글자 크기를 낮출까요?",TaskSequenceText.question("settings.font_size.decrease"));
 }
}
