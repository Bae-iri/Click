package com.click.ui.overlay;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
public class NavigationRecoveryViewTest {
 private Button find(ViewGroup group,String label) {
  for(int i=0;i<group.getChildCount();i++) {
   View child=group.getChildAt(i);
   if(child instanceof Button && ((Button)child).getText().toString().equals(label)) return (Button)child;
   if(child instanceof ViewGroup) {Button found=find((ViewGroup)child,label);if(found!=null)return found;}
  }
  return null;
 }
 @Test public void highlightedNavigationActionRequiresUserAndRunsOnlyOnce() {
  TaskFlowOverlayView view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
  int[] clicks={0};
  view.navigationRecovery(true,"뒤로가기를 한 번 눌러주세요.",()->clicks[0]++);
  Button back=find(view,"뒤로가기");assertNotNull(back);assertNotNull(back.getBackground());
  assertEquals(0,clicks[0]);back.performClick();back.performClick();assertEquals(1,clicks[0]);
  view.navigationRecovery(false,"홈 버튼을 눌러주세요.",()->clicks[0]++);
  Button home=find(view,"홈으로 이동");assertNotNull(home);assertTrue(home.isEnabled());
  home.performClick();assertEquals(2,clicks[0]);
 }
}
