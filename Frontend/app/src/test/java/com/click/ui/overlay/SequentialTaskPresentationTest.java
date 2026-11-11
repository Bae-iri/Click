package com.click.ui.overlay;
import android.app.Application;
import android.view.*;
import android.widget.TextView;
import com.click.domain.goal.GoalPreparationState;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class SequentialTaskPresentationTest {
 private View find(View view,String text){
  if(view.getVisibility()!=View.VISIBLE)return null;
  if(view instanceof TextView && text.equals(((TextView)view).getText().toString()))return view;
  if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=find(((ViewGroup)view).getChildAt(i),text);if(match!=null)return match;}
  return null;
 }
 private GoalPreparationState ready(String type,String source) throws Exception {
  return new GoalPreparationState(new JSONObject().put("version","goal-inputs.v1").put("id","ready")
    .put("source_text",source).put("status","ready").put("missing_fields",new JSONArray())
    .put("graph_reference",new JSONObject().put("task_id","fixture")),new JSONObject().put("goal_type",type),"이 내용으로 시작할까요?");
 }
 @Test public void initialConfirmationShowsOnlyTheNaturalTaskOverview() throws Exception {
  var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
  String introduction="글자 크기를 높이고 디스플레이 밝기를 높이겠습니다.";
  view.setPreparationMessage(introduction);view.setQueuedRequests(java.util.List.of("디스플레이 밝기를 높여줘"));
  view.preparation(ready("settings.font_size.increase","휴대폰 글자 크기 키워줘"));
  assertNotNull(find(view,introduction));assertNull(find(view,"대기 요청 1개"));assertEquals(introduction,view.guidanceMessage());
 }
 @Test public void nextTaskShowsItsOwnQuestionAndWaitsForTheStartButton() throws Exception {
  var listener=mock(TaskFlowOverlayView.Listener.class);var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),listener);
  view.setPreparationMessage("디스플레이 밝기를 높일까요?");view.preparation(ready("settings.brightness.increase","밝기를 높여줘"));
  assertNotNull(find(view,"디스플레이 밝기를 높일까요?"));verify(listener,never()).confirmRequirements();
  find(view,"안내 시작하기").performClick();verify(listener).confirmRequirements();
 }
 @Test public void endButtonFinishesOnlyOnce(){
  var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));int[] ended={0};
  view.completed("글자가 보기 편해졌어요.",true,()->ended[0]++);
  View end=find(view,"끝내기");assertNotNull(end);end.performClick();end.performClick();assertEquals(1,ended[0]);
 }

 @Test public void declinedChoiceHasNoApologyFailureClueOrRetryAndEndRunsOnce() {
  var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
  Runnable end=mock(Runnable.class);String message="밝기를 바꾸지 않고 안내를 마칠게요.";
  view.userDeclined(message,end);
  assertEquals(message,view.guidanceMessage());assertNotNull(find(view,message));
  assertNull(view.findViewWithTag("failure_clue"));assertNull(find(view,"다시 시도하기"));
  View finish=view.findViewWithTag("task_declined_end");assertNotNull(finish);
  finish.performClick();finish.performClick();verify(end,times(1)).run();
 }
 @Test public void unspecifiedStopAndConnectionFailureDoNotInventResponsibility() {
  var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
  view.failed("연결을 확인해 주세요.");assertFalse(view.guidanceMessage().contains("죄송"));
  assertNull(view.findViewWithTag("failure_clue"));
  view.resolutionUnavailable("밝기를 높여줘","서버 연결을 확인해 주세요.",()->{});
  assertFalse(view.guidanceMessage().contains("죄송"));assertNull(view.findViewWithTag("failure_clue"));
 }
}
