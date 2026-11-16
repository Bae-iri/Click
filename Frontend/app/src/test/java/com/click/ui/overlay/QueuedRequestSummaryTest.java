package com.click.ui.overlay;
import android.app.Application;
import android.view.*;
import android.widget.*;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class QueuedRequestSummaryTest {
 private boolean contains(View v,String s){
  if(v instanceof TextView&&v.getVisibility()==View.VISIBLE&&((TextView)v).getText().toString().contains(s))return true;
  if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(contains(((ViewGroup)v).getChildAt(i),s))return true;return false;
 }
 @Test public void pendingRequestsNeverAppearInTheFloatingUi(){
  var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
  view.setQueuedRequests(List.of("와이파이 켜줘","밝기 낮춰줘"));
  view.privateSettingsStep("현재 설정 안내");
  assertFalse(contains(view,"대기 요청"));assertFalse(contains(view,"와이파이 켜줘"));assertFalse(contains(view,"밝기 낮춰줘"));
  view.setQueuedRequests(List.of());assertFalse(contains(view,"대기 요청"));
 }
}
