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
    @Test public void taskPlanAndExecutionStayExplicitAtLargeFont()throws Exception {
        JSONObject result=proposal("tap");try(ActivityScenario<HomeActivity> scenario=ActivityScenario.launch(HomeActivity.class)){
            for(float scale:new float[]{1f,1.5f})scenario.onActivity(activity->{
                var config=new android.content.res.Configuration(activity.getResources().getConfiguration());config.fontScale=scale;
                var panel=new TaskFlowOverlayView(activity.createConfigurationContext(config),new Answers());int[] count={0};panel.appTask(result,v->count[0]++,()->{},()->{},()->{},()->{});activity.setContentView(panel);measure(panel);
                assertEquals(0,count[0]);assertNotNull(find(panel,"✓ 앱 열기\n● 검색 열기"));TextView execute=find(panel,"이 단계 실행");assertNotNull(execute);assertTrue(execute.isEnabled());assertTrue(execute.getHeight()>=48*activity.getResources().getDisplayMetrics().density);execute.performClick();assertEquals(1,count[0]);
            });
        }
    }
    @Test public void searchInputStaysLocalAndWaitsForItsOwnButton()throws Exception {
        JSONObject result=proposal("set_text");String[] entered={null};TaskFlowOverlayView[] panel={null};
        try(ActivityScenario<HomeActivity> scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(activity->{panel[0]=new TaskFlowOverlayView(activity,new Answers());panel[0].appTask(result,v->entered[0]=v,()->{},()->{},()->{},()->{});activity.setContentView(panel[0]);assertTrue(panel[0].needsPublicGuideInput());EditText input=findInput(panel[0]);assertNotNull(input);input.setText("클릭 테스트");assertNull(entered[0]);find(panel[0],"이 검색어 입력하기").performClick();});
            android.os.SystemClock.sleep(450);InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertEquals("클릭 테스트",entered[0]);
        }
    }
    @Test public void verifyingAndOcrOnlyTargetsHaveNoExecutionButton()throws Exception {
        JSONObject result=proposal("tap");result.put("execute_allowed",false);
        try(ActivityScenario<HomeActivity> scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(activity->{var panel=new TaskFlowOverlayView(activity,new Answers());panel.appTask(result,v->fail("OCR executed"),()->{},()->{},()->{},()->{});activity.setContentView(panel);assertNull(find(panel,"이 단계 실행"));assertNotNull(find(panel,"이 항목은 화면을 보고 직접 눌러주세요."));});
            result.put("verifying",true);scenario.onActivity(activity->{var panel=new TaskFlowOverlayView(activity,new Answers());panel.appTask(result,v->fail("Executed while verifying"),()->{},()->{},()->{},()->{});activity.setContentView(panel);assertNull(find(panel,"이 단계 실행"));assertNull(find(panel,"다른 동작"));assertNull(find(panel,"결과 확인 · 안내 끝내기"));});
        }
    }
    private static void measure(TaskFlowOverlayView view){view.measure(View.MeasureSpec.makeMeasureSpec(view.getWindowWidth(view.getResources().getDisplayMetrics().widthPixels),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.AT_MOST));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());}
    private static TextView find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static EditText findInput(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText found=findInput(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
}
