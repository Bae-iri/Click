package com.click.domain.goal;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;
public class QueueRequestIntentTest {
 private GoalCatalogClassifier catalog() throws Exception {
  try(var input=getClass().getResourceAsStream("/mvp_goals.json")){return GoalCatalogClassifier.fromJson(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
 }
 @Test public void completeRequestsAreNotContextualAnswers() throws Exception {
  for(String s:new String[]{"와이파이 켜줘","밝기 낮춰줘","와이파이 켜고 블루투스 꺼줘","다른 설정으로 블루투스 켜줘","와이파이는 켜지 마"})
   assertTrue(s,GoalCommandInterpreter.explicitTaskRequest(s,catalog(),null));
  for(String s:new String[]{"네","아니요","휴대폰 전체","크게","아직 불편해요","대신 블루투스 켜줘","멈춰, 와이파이 켜줘","이 작업 취소하고 밝기 낮춰줘"})
   assertFalse(s,GoalCommandInterpreter.explicitTaskRequest(s,catalog(),null));
 }
 @Test public void negatedCommandIsANewServerRequestAndNeverStartsAPositiveGoal() throws Exception {
  String source="와이파이는 켜지 마";
  assertTrue(GoalCommandInterpreter.explicitTaskRequest(source,catalog(),null));
  var result=GoalCommandInterpreter.interpret(source,false,false,false,catalog(),null);
  assertEquals(GoalCommandInterpreter.Kind.INTERPRET_REQUEST,result.kind);
  assertFalse(result.canStart());
  assertNull(result.goal);
 }
}
