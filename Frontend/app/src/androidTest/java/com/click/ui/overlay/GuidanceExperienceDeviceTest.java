package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Device layout and interaction checks only. No queue writes or model calls. */
@RunWith(AndroidJUnit4.class)
public class GuidanceExperienceDeviceTest {
    @Test public void appEntryStaysCompactAtNormalAndLargeText()throws Exception {
        try(var scenario=ActivityScenario.launch(HomeActivity.class)){
            for(float scale:new float[]{1f,1.5f}){
                TaskFlowOverlayView[] panel={null};
                scenario.onActivity(activity->{
                    var config=new android.content.res.Configuration(activity.getResources().getConfiguration());config.fontScale=scale;
                    Context context=activity.createConfigurationContext(config);
                    panel[0]=new TaskFlowOverlayView(context,new TaskFlowOverlayView.Listener(){
                        public void chooseGoal(org.json.JSONObject goal){}public void clarify(String choice){}public void rephrase(String text){}
                        public void result(String answer){}public void adjust(String reason){}public void control(String command){}
                    });
                    try{panel[0].appTask(new org.json.JSONObject().put("status","proposed").put("message","쿠팡을 열어주세요.").put("target_label","쿠팡").put("execute_allowed",true).put("action",new org.json.JSONObject().put("tool","open_app")),v->{},()->{},()->{},()->{},()->{});}catch(Exception e){throw new AssertionError(e);}
                    FrameLayout host=new FrameLayout(activity);host.addView(panel[0],new FrameLayout.LayoutParams(Math.min(dp(context,390),context.getResources().getDisplayMetrics().widthPixels-dp(context,24)),-2,Gravity.CENTER));activity.setContentView(host);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity->{assertTrue(find(panel[0],"쿠팡 열기").isShown());assertNull(find(panel[0],"현재 화면 확인"));assertNull(find(panel[0],"다른 동작"));save(panel[0],"coupang-entry-"+scale);});
            }
        }
    }
    @Test public void compactHelpAndExpandedChoicesWorkAtNormalAndLargeText() {
        try(var scenario=ActivityScenario.launch(HomeActivity.class)) {
            for(float scale:new float[]{1f,1.5f}) {
                GuidanceSupportView[] current={null};int[] calls={0,0,0};
                scenario.onActivity(activity->{
                    var config=new android.content.res.Configuration(activity.getResources().getConfiguration());config.fontScale=scale;
                    Context context=activity.createConfigurationContext(config);
                    current[0]=new GuidanceSupportView(context,"휴대폰 글씨를 더 크게 해줘","읽은 화면의 ‘디스플레이’ 항목을 안내 대상으로 사용했어요.",true,true,false,false,
                        new GuidanceSupportView.Listener(){public void close(){calls[0]++;}public void retry(){calls[1]++;}public void revise(String value){calls[2]++;}public void enqueue(String value){calls[2]++;}public String feedback(com.click.data.local.GuidancePreferences.Feedback value,boolean remember){return "이번 작업에만 반영했어요.";}});
                    FrameLayout host=new FrameLayout(activity);host.addView(current[0],new FrameLayout.LayoutParams(Math.min(dp(context,390),context.getResources().getDisplayMetrics().widthPixels-dp(context,24)),-2,Gravity.CENTER));activity.setContentView(host);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity->{
                    assertTrue(find(current[0],"다시 확인").isShown());assertTrue(find(current[0],"요청 수정").isShown());
                    assertFalse(find(current[0],"천천히 읽어주세요").isShown());
                    save(current[0],"help-"+scale);
                    find(current[0],"다시 확인").performClick();assertEquals(1,calls[1]);
                    find(current[0],"요청 수정").performClick();assertTrue(find(current[0],"수정한 요청 확인").isShown());
                    find(current[0],"수정한 요청 확인").performClick();assertEquals(1,calls[2]);
                    find(current[0],"안내 평가·방식 바꾸기").performClick();assertTrue(find(current[0],"천천히 읽어주세요").getVisibility()==View.VISIBLE);
                    find(current[0],"천천히 읽어주세요").performClick();assertNotNull(find(current[0],"이번 작업에만 반영했어요."));
                    find(current[0],"닫기").performClick();assertEquals(1,calls[0]);
                });
            }
        }
    }
    private static TextView find(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){var found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static int dp(Context context,int value){return Math.round(context.getResources().getDisplayMetrics().density*value);}
    private static void save(View view,String name){
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        try(var output=new java.io.FileOutputStream(new java.io.File(view.getContext().getCacheDir(),"hai-"+name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}catch(Exception error){throw new AssertionError(error);}finally{bitmap.recycle();}
    }
}
