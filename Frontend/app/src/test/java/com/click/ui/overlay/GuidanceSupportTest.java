package com.click.ui.overlay;

import android.app.Application;
import android.view.*;
import android.widget.TextView;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,application=Application.class) @LooperMode(LooperMode.Mode.PAUSED)
public class GuidanceSupportTest {
    private TextView find(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){var result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
    private int count(View view,String label){int matches=view instanceof TextView&&label.contentEquals(((TextView)view).getText())?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)matches+=count(((ViewGroup)view).getChildAt(i),label);return matches;}
    private boolean visible(View view){while(view!=null){if(view.getVisibility()!=View.VISIBLE)return false;view=view.getParent() instanceof View?(View)view.getParent():null;}return true;}
    @Test public void secondaryFeedbackStaysHiddenUntilAskedAndUnknownServerCannotRevise(){
        var callback=mock(GuidanceSupportView.Listener.class);
        var panel=new GuidanceSupportView(RuntimeEnvironment.getApplication(),"글씨 키워줘",GuidanceExplanation.DEFAULT,true,false,false,false,callback);
        assertFalse(visible(find(panel,"천천히 읽어주세요")));
        assertNull(find(panel,"요청 수정"));assertNull(find(panel,"수정한 요청 보내기"));
        assertEquals(1,count(panel,"다음 작업으로 추가"));
        find(panel,"다음 요청 작성").performClick();assertTrue(visible(find(panel,"다음 작업으로 추가")));find(panel,"다음 작업으로 추가").performClick();
        verify(callback).enqueue("글씨 키워줘");verify(callback,never()).revise(anyString());
        find(panel,"안내 평가·방식 바꾸기").performClick();assertTrue(visible(find(panel,"천천히 읽어주세요")));
        find(panel,"천천히 읽어주세요").performClick();verify(callback).feedback(com.click.data.local.GuidancePreferences.Feedback.SLOWER,false);
    }
    @Test public void normalProgressQuestionsPauseAndWaitingDoNotOfferFailureHelp() throws Exception {
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
        panel.progress(null,null,"디스플레이를 눌러주세요.",false);assertFalse(visible(find(panel,"이유·다시 확인")));
        panel.paused();assertFalse(visible(find(panel,"이유·다시 확인")));
        panel.waiting("요청 확인 중");shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertFalse(visible(find(panel,"이유·다시 확인")));
        panel.publicGuide("어떤 인증서를 쓸까요?",false,new org.json.JSONObject().put("field","login_choice").put("label","인증서"),false,(s,b)->{},()->{});
        assertFalse(visible(find(panel,"이유·다시 확인")));
        panel.appTask(new org.json.JSONObject().put("status","needs_input").put("message","원하는 앱을 열어주세요."),s->{},()->{},()->{},()->{},()->{});
        assertFalse(visible(find(panel,"이유·다시 확인")));
    }
    @Test public void failureControlsRemainClickableAndCollapseCannotLeaveOnlyHelpVisible() {
        var listener=mock(TaskFlowOverlayView.Listener.class);var retry=mock(Runnable.class);
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),listener);
        panel.observationRecovery("화면을 읽지 못했어요.",retry);
        assertTrue(visible(find(panel,"이유·다시 확인")));assertTrue(find(panel,"다시 시도하기").isEnabled());
        find(panel,"다시 시도하기").performClick();verify(retry).run();
        panel.setMinimizedProgress(true);
        assertFalse(visible(find(panel,"이유·다시 확인")));assertFalse(visible(find(panel,"다시 시도하기")));
        panel.setMinimizedProgress(false);
        assertTrue(visible(find(panel,"다시 시도하기")));assertTrue(visible(find(panel,"이유·다시 확인")));
        find(panel,"이유·다시 확인").performClick();verify(listener).assistance();
        panel.setMinimizedProgress(true);panel.progress(null,null,"화면을 확인했어요.",false);
        assertFalse(visible(find(panel,"이유·다시 확인")));
    }
    @Test public void finishedHelpHidesRetryAndStillAllowsAnExplicitNewRequest() {
        var listener=mock(GuidanceSupportView.Listener.class);
        var panel=new GuidanceSupportView(RuntimeEnvironment.getApplication(),"원래 요청",null,false,false,false,false,listener);
        assertFalse(visible(find(panel,"다시 확인")));
        assertNull(find(panel,"다음 작업으로 추가"));find(panel,"새 요청 작성").performClick();panel.editRequest("새 요청");find(panel,"새 요청 보내기").performClick();
        verify(listener).enqueue("새 요청");verify(listener,never()).retry();verify(listener,never()).revise(anyString());
    }
    @Test public void oldLoadingHelpCannotAppearOnANewerInstruction(){
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
        panel.waiting("요청 확인 중");panel.progress(null,null,"디스플레이를 눌러주세요.",false);
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertNull(find(panel,"응답을 기다리고 있어요. 일시정지하거나 요청을 다시 확인할 수 있어요."));
        assertNotNull(find(panel,"디스플레이를 눌러주세요."));
    }
    @Test public void pendingRevisionCannotBeDismissedOrSubmittedTwiceAndFailureKeepsDraft(){
        var callback=mock(GuidanceSupportView.Listener.class);
        var panel=new GuidanceSupportView(RuntimeEnvironment.getApplication(),"글씨 키워줘",GuidanceExplanation.DEFAULT,true,true,false,false,callback);
        panel.editRequest("밝기 올려줘");panel.saving();assertTrue(panel.isSaving());
        assertFalse(find(panel,"수정한 요청 확인").isEnabled());assertFalse(find(panel,"다음 작업으로 추가").isEnabled());
        find(panel,"수정한 요청 확인").performClick();find(panel,"다음 작업으로 추가").performClick();find(panel,"닫기").performClick();find(panel,"다시 확인").performClick();
        find(panel,"천천히 읽어주세요").performClick();panel.editRequest("변경되면 안 되는 내용");
        verifyNoInteractions(callback);
        panel.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK));verify(callback,never()).close();
        panel.error("연결을 다시 확인해 주세요.");assertFalse(panel.isSaving());assertTrue(find(panel,"수정한 요청 확인").isEnabled());
        find(panel,"수정한 요청 확인").performClick();verify(callback).revise("밝기 올려줘");
    }
    @Test public void blankRequestsCannotSubmitAndRecoveryRechecksCurrentDraft(){
        var callback=mock(GuidanceSupportView.Listener.class);
        var panel=new GuidanceSupportView(RuntimeEnvironment.getApplication(),"",null,true,true,false,false,callback);
        assertFalse(find(panel,"수정한 요청 확인").isEnabled());assertFalse(find(panel,"다음 작업으로 추가").isEnabled());
        panel.editRequest("   ");find(panel,"수정한 요청 확인").performClick();find(panel,"다음 작업으로 추가").performClick();verifyNoInteractions(callback);
        panel.editRequest(" 밝기 올려줘 ");assertTrue(find(panel,"수정한 요청 확인").isEnabled());
        panel.saving();panel.error("다시 연결해 주세요.");assertTrue(find(panel,"수정한 요청 확인").isEnabled());
        find(panel,"다음 작업으로 추가").performClick();verify(callback).enqueue("밝기 올려줘");
        panel.editRequest(" ");panel.saving();panel.error("다시 연결해 주세요.");
        assertFalse(find(panel,"수정한 요청 확인").isEnabled());assertFalse(find(panel,"다음 작업으로 추가").isEnabled());
    }
    @Test public void detailedReasonsAreOptionalAndDoNotReplaceTheAction(){
        var panel=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
        panel.progress(null,null,"디스플레이를 눌러주세요.",false);panel.setGuidanceEvidence("지금 읽은 화면의 디스플레이 항목이에요.");
        assertNull(find(panel,"지금 읽은 화면의 디스플레이 항목이에요."));
        panel.setDetailedExplanations(true);assertTrue(visible(find(panel,"지금 읽은 화면의 디스플레이 항목이에요.")));
        assertNotNull(find(panel,"디스플레이를 눌러주세요."));panel.setDetailedExplanations(false);
        assertFalse(visible(find(panel,"지금 읽은 화면의 디스플레이 항목이에요.")));
    }
}
