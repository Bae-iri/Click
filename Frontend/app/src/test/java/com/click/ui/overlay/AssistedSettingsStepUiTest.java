package com.click.ui.overlay;
import android.app.Application;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class AssistedSettingsStepUiTest {
 private TextView text(View v,String label){
  if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return (TextView)v;
  if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=text(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;
 }
 @Test public void privateStepExplainsTheTaskWithoutAFailureOrSuccessClaim(){
  var listener=mock(TaskFlowOverlayView.Listener.class);var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),listener);
  String message="VPN 설정에서 연결할 프로필을 직접 선택해 주세요.";view.privateSettingsStep(message);
  assertNotNull(text(view,message));assertNotNull(text(view,"설정을 직접 이어가 주세요"));
  assertNull(view.findViewWithTag("failure_clue"));verifyNoInteractions(listener);
 }
 @Test public void continueButtonOnlyCollapsesAndFinishOnlyCancels(){
  var listener=mock(TaskFlowOverlayView.Listener.class);var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),listener);view.privateSettingsStep("VPN 설정 안내");
  text(view,"설정에서 직접 진행하기").performClick();verify(listener).collapse();verify(listener,never()).control("resume_task");
  text(view,"안내 끝내기").performClick();verify(listener).control("cancel_task");verify(listener,never()).result(anyString());
 }
}
