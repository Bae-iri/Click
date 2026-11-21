package com.click.ui.overlay;

import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppTaskPanelTest {
    private static final class Answers implements TaskFlowOverlayView.Listener {
        public void chooseGoal(JSONObject v){}public void clarify(String v){}public void rephrase(String v){}public void result(String v){}public void adjust(String v){}public void control(String v){}
    }
    private JSONObject proposal(String tool)throws Exception{return new JSONObject().put("message","다음 동작을 확인해 주세요.").put("status","proposed").put("execute_allowed",true).put("target_label","검색").put("active_subgoal","g1")
        .put("subgoals",new JSONArray().put(new JSONObject().put("id","g0").put("title","앱 열기").put("status","verified")).put(new JSONObject().put("id","g1").put("title","검색 열기").put("status","pending")))
        .put("action",new JSONObject().put("id","a1").put("tool",tool));}
    @Test public void executionAndResultConfirmationStayExplicitAtLargeFont()throws Exception {
        JSONObject result=proposal("tap");try(ActivityScenario<HomeActivity> scenario=ActivityScenario.launch(HomeActivity.class)){
            for(float scale:new float[]{1f,1.5f})scenario.onActivity(activity->{
                var config=new android.content.res.Configuration(activity.getResources().getConfiguration());config.fontScale=scale;
                var panel=new TaskFlowOverlayView(activity.createConfigurationContext(config),new Answers());int[] count={0,0,0};panel.appTask(result,v->count[0]++,()->{},()->{},()->{},()->count[1]++,()->count[2]++);activity.setContentView(panel);measure(panel);
                assertEquals(0,count[0]);TextView execute=find(panel,"클릭이 대신 누르기");assertNotNull(execute);assertTrue(execute.isEnabled());assertTrue(execute.getHeight()>=48*activity.getResources().getDisplayMetrics().density);execute.performClick();assertEquals(1,count[0]);
                assertNull(find(panel,"결과를 확인했어요"));find(panel,"다른 방법").performClick();assertEquals(0,count[1]+count[2]);find(panel,"결과를 확인했어요").performClick();assertEquals(1,count[2]);assertEquals(0,count[1]);find(panel,"안내 끝내기").performClick();assertEquals(1,count[1]);
            });
        }
    }
    @Test public void searchGuideKeepsInputInTheApplication()throws Exception {
        String message="표시한 검색창에 이렇게 입력해 주세요.\ndino";
        JSONObject result=proposal("set_text").put("execute_allowed",false).put("message",message);
        EditText[] field={null};
        try(ActivityScenario<HomeActivity> scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(activity->{
                var body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
                field[0]=new EditText(activity);field[0].setSingleLine(true);field[0].setHint("검색");body.addView(field[0]);
                var panel=new TaskFlowOverlayView(activity,new Answers());panel.appTask(result,v->fail(),()->{},()->{},()->{},()->{});
                body.addView(panel);
                activity.addContentView(body,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
                assertTrue(field[0].requestFocus());
                assertNotNull(find(panel,message));assertFalse(panel.needsPublicGuideInput());
                assertNull(findInput(panel));assertNull(find(panel,"검색창에 입력"));
            });
            InstrumentationRegistry.getInstrumentation().sendStringSync("dino");
            scenario.onActivity(activity->{assertTrue(field[0].hasFocus());assertEquals("dino",field[0].getText().toString());});
        }
    }
    @Test public void verifyingAndOcrOnlyTargetsHaveNoExecutionButton()throws Exception {
        JSONObject result=proposal("tap");result.put("execute_allowed",false);
        try(ActivityScenario<HomeActivity> scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(activity->{var panel=new TaskFlowOverlayView(activity,new Answers());panel.appTask(result,v->fail("OCR executed"),()->{},()->{},()->{},()->{});activity.setContentView(panel);assertNull(find(panel,"클릭이 대신 누르기"));assertNotNull(find(panel,"‘검색’ 항목을 직접 눌러주세요."));});
            result.put("verifying",true);scenario.onActivity(activity->{var panel=new TaskFlowOverlayView(activity,new Answers());panel.appTask(result,v->fail("Executed while verifying"),()->{},()->{},()->{},()->{},()->{});activity.setContentView(panel);assertNull(find(panel,"클릭이 대신 누르기"));assertNull(find(panel,"다른 방법"));assertNull(find(panel,"결과를 확인했어요"));});
        }
    }
    private static void measure(TaskFlowOverlayView view){view.measure(View.MeasureSpec.makeMeasureSpec(view.getWindowWidth(view.getResources().getDisplayMetrics().widthPixels),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.AT_MOST));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());}
    private static TextView find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static EditText findInput(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText found=findInput(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
}
