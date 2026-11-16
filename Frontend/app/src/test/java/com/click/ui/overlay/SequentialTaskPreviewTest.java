package com.click.ui.overlay;
import android.app.Application;
import android.graphics.*;
import android.view.View;
import com.click.domain.goal.GoalPreparationState;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,qualifiers="ko-rKR-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SequentialTaskPreviewTest {
 private GoalPreparationState ready(String type,String source) throws Exception {
  return new GoalPreparationState(new JSONObject().put("version","goal-inputs.v1").put("id","ready")
    .put("source_text",source).put("status","ready").put("missing_fields",new JSONArray())
    .put("graph_reference",new JSONObject().put("task_id","fixture")),new JSONObject().put("goal_type",type),"이 내용으로 시작할까요?");
 }
 private TaskFlowOverlayView panel(){return new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));}
 private void render(String name,TaskFlowOverlayView panel) throws Exception {
  int width=panel.getWindowWidth(840);
  panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
  panel.layout(0,0,width,panel.getMeasuredHeight());
  Bitmap bitmap=Bitmap.createBitmap(width,panel.getHeight(),Bitmap.Config.ARGB_8888);
  Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);panel.draw(canvas);
  File directory=new File(System.getProperty("click.contractOutput"),"sequential-ui");assertTrue(directory.isDirectory() || directory.mkdirs());
  try(var output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
 }
 @Test public void renderInitialEndAndNextConfirmationScreens() throws Exception {
  var first=panel();first.setPreparationMessage("글자 크기를 높이고 디스플레이 밝기를 높이겠습니다.");
  first.preparation(ready("settings.font_size.increase","휴대폰 글자 크기 키워줘"));render("01-initial",first);
  var completed=panel();completed.completed("글자 크기 안내를 마쳤어요.",true,()->{});render("02-end",completed);
  var declined=panel();declined.userDeclined("밝기를 바꾸지 않고 안내를 마칠게요.",()->{});render("04-declined",declined);
  var next=panel();next.setPreparationMessage("디스플레이 밝기를 높일까요?");
  next.preparation(ready("settings.brightness.increase","디스플레이 밝기를 높여줘"));render("03-brightness",next);
 }
}
