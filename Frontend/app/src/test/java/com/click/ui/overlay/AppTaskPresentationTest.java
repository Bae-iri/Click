package com.click.ui.overlay;

import android.app.Application;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,application=Application.class)
public class AppTaskPresentationTest {
    private TextView find(View v,String label){if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){var out=find(((ViewGroup)v).getChildAt(i),label);if(out!=null)return out;}return null;}
    @Test public void entryShowsOnlyTheNamedAppActionAndDoesNotRepeatTheGoal()throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));int[] calls={0};
        panel.appTask(new JSONObject().put("status","proposed").put("message","쿠팡을 열어주세요.").put("target_label","쿠팡").put("execute_allowed",true)
            .put("action",new JSONObject().put("tool","open_app")).put("subgoals",new JSONArray().put(new JSONObject().put("title","쿠팡에서 물 주문하고 싶어"))),v->calls[0]++,()->{},()->{},()->{},()->{});
        assertNotNull(find(panel,"쿠팡 열기"));assertNull(find(panel,"현재 화면 확인"));assertNull(find(panel,"다른 동작"));assertNull(find(panel,"더 보기"));
        assertNull(find(panel,"○ 쿠팡에서 물 주문하고 싶어"));find(panel,"쿠팡 열기").performClick();assertEquals(1,calls[0]);
        panel.setDetailedExplanations(true);assertNotNull(find(panel,"요청하신 ‘쿠팡’ 앱이 설치되어 있어요."));
    }
    @Test public void searchTextIsReviewableAndRecoveryChoicesStayFolded()throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
        panel.appTask(new JSONObject().put("status","proposed").put("message","검색어를 확인해 주세요.").put("execute_allowed",true).put("suggested_query","물").put("action",new JSONObject().put("tool","set_text")),v->{},()->{},()->{},()->{},()->{});
        assertTrue(find(panel,"물") instanceof EditText);assertNotNull(find(panel,"검색어 입력"));assertNull(find(panel,"직접 동작 선택"));
        find(panel,"더 보기").performClick();assertNotNull(find(panel,"직접 동작 선택"));assertNotNull(find(panel,"안내 끝내기"));
    }
}
