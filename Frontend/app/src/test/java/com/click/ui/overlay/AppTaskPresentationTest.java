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
        assertNotNull(find(panel,"쿠팡 열기"));assertNull(find(panel,"현재 화면 확인"));assertNull(find(panel,"다른 동작"));assertNull(find(panel,"다른 방법"));
        assertNull(find(panel,"○ 쿠팡에서 물 주문하고 싶어"));find(panel,"쿠팡 열기").performClick();assertEquals(1,calls[0]);
        panel.setDetailedExplanations(true);assertNotNull(find(panel,"요청하신 ‘쿠팡’ 앱이 설치되어 있어요."));
    }
    @Test public void receivedRequestStaysVisibleForGeneralAppTasks()throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
        panel.setRequestSummary("유튜브에서 구독 탭 열어줘");
        panel.appTask(new JSONObject().put("status","planning").put("message","현재 화면에서 다음 단계를 찾고 있어요."),v->{},()->{},()->{},()->{},()->{});
        assertNotNull(find(panel,"유튜브에서 구독 탭 열어줘"));
        panel.appTask(new JSONObject().put("status","proposed").put("message","구독을 눌러주세요.").put("target_label","구독").put("execute_allowed",true)
            .put("action",new JSONObject().put("tool","tap")),v->{},()->{},()->{},()->{},()->{});
        assertNotNull(find(panel,"유튜브에서 구독 탭 열어줘"));assertNotNull(find(panel,"클릭이 대신 누르기"));
    }
    @Test public void receivedRequestStaysVisibleWhileOpeningAppsAndGuidingSteps()throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
        panel.setRequestSummary("정부24 열어줘");
        panel.waitingProgress("정부24 앱을 열고 있어요.");
        assertNotNull(find(panel,"정부24 열어줘"));
        panel.progress("정부24 열어줘",null,"표시한 버튼을 눌러주세요.",false);
        assertNotNull(find(panel,"정부24 열어줘"));
    }
    @Test public void searchTextIsReviewableAndRecoveryChoicesStayFolded()throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
        panel.appTask(new JSONObject().put("status","proposed").put("message","검색어를 확인해 주세요.").put("execute_allowed",true).put("available_tools",new JSONArray().put("back")).put("suggested_query","물").put("action",new JSONObject().put("tool","set_text")),v->{},()->{},()->{},()->{},()->{});
        assertTrue(find(panel,"물") instanceof EditText);assertNotNull(find(panel,"검색창에 입력"));assertNull(find(panel,"직접 진행할 방법"));
        find(panel,"다른 방법").performClick();assertNotNull(find(panel,"직접 진행할 방법"));assertNotNull(find(panel,"안내 끝내기"));
    }
    @Test public void resultConfirmationIsVisibleWithoutOpeningMoreActions()throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));int[] confirmed={0},ended={0};
        panel.appTask(new JSONObject().put("status","confirm_result").put("message","원하신 결과가 맞나요?"),v->{},()->{},()->{},()->{},()->ended[0]++,()->confirmed[0]++);
        assertNotNull(find(panel,"결과가 맞아요 · 끝내기"));assertNull(find(panel,"다른 방법"));
        find(panel,"결과가 맞아요 · 끝내기").performClick();assertEquals(1,confirmed[0]);assertEquals(0,ended[0]);
    }
    @Test public void planningAndProtectedScreensKeepStopWithoutIrrelevantTools()throws Exception {
        var listener=mock(TaskFlowOverlayView.Listener.class);int[] ends={0},refreshes={0};
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),listener);
        panel.appTask(new JSONObject().put("status","planning").put("message","다음 단계를 찾고 있어요."),v->fail(),()->{},()->fail(),()->fail(),()->ends[0]++);
        assertNotNull(find(panel,"일시정지"));assertNotNull(find(panel,"안내 끝내기"));assertNull(find(panel,"다른 방법"));
        find(panel,"일시정지").performClick();verify(listener).control("pause_task");find(panel,"안내 끝내기").performClick();assertEquals(1,ends[0]);
        panel.appTask(new JSONObject().put("status","protected").put("message","인증은 직접 진행해 주세요."),v->fail(),()->refreshes[0]++,()->fail(),()->fail(),()->{});
        assertNotNull(find(panel,"입력 후 안내 이어가기"));assertNull(find(panel,"버튼 이름 찾기"));assertNull(find(panel,"다른 방법"));
        find(panel,"입력 후 안내 이어가기").performClick();assertEquals(1,refreshes[0]);
    }
    private EditText findInput(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText found=findInput(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
    @Test public void blankSearchIsDisabledAndDelayedInputCannotCrossPresentations()throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));int[] sent={0};
        JSONObject proposal=new JSONObject().put("status","proposed").put("execute_allowed",true).put("action",new JSONObject().put("tool","set_text"));
        panel.appTask(proposal,v->sent[0]++,()->{},()->{},()->{},()->{});
        EditText input=findInput(panel);assertNotNull(input);TextView enter=find(panel,"검색창에 입력");assertFalse(enter.isEnabled());
        input.setText("   ");assertFalse(enter.isEnabled());input.setText("서울");assertTrue(enter.isEnabled());enter.performClick();
        panel.appTask(new JSONObject().put("status","protected"),v->fail(),()->{},()->{},()->{},()->{});
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));assertEquals(0,sent[0]);
    }
    @Test public void alternativesOnlyExposeAvailableToolsAndOldActionsCannotBeReplayed()throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));int[] runs={0};
        panel.appTask(new JSONObject().put("status","proposed").put("message","‘검색’ 항목을 직접 눌러주세요.").put("target_label","검색").put("execute_allowed",true).put("action",new JSONObject().put("tool","tap")),v->runs[0]++,()->{},()->{},()->{},()->{});
        TextView execute=find(panel,"클릭이 대신 누르기");assertNotNull(execute);execute.performClick();assertEquals(1,runs[0]);
        find(panel,"다른 방법").performClick();assertNull(find(panel,"버튼 이름 찾기"));assertNull(find(panel,"직접 진행할 방법"));
        panel.appTask(new JSONObject().put("status","verifying").put("verifying",true),v->fail(),()->{},()->{},()->{},()->{});
        execute.performClick();assertEquals(1,runs[0]);
        panel.appTaskTools(new JSONArray().put("back"),v->{},()->{});assertNotNull(find(panel,"뒤로가기"));assertNull(find(panel,"검색어 입력"));assertNull(find(panel,"아래로 이동"));
    }

    @Test public void oldAppSelectionCannotLaunchAfterPanelChanges()throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));int[] launches={0};
        panel.appTaskApps(new JSONArray().put(new JSONObject().put("package","org.example.app").put("label","테스트 앱")),(pkg,label)->launches[0]++,()->{});
        TextView old=find(panel,"테스트 앱");panel.paused();old.performClick();assertEquals(0,launches[0]);
    }

}
