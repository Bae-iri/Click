package com.click.ui.overlay;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=34,application=android.app.Application.class,qualifiers="ko-rKR-w420dp-h900dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class NavigationRecoveryViewTest {
 private Button find(ViewGroup group,String label) {
  for(int i=0;i<group.getChildCount();i++) {
   View child=group.getChildAt(i);
   if(child instanceof Button && ((Button)child).getText().toString().equals(label))return (Button)child;
   if(child instanceof ViewGroup){Button found=find((ViewGroup)child,label);if(found!=null)return found;}
  }
  return null;
 }
 @Test public void recoveryOffersOnlyTheSettingsActionAndNeverBackOrHome() throws Exception {
  var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
  Runnable open=mock(Runnable.class);view.settingsEntryRecovery(open);
  assertNull(find(view,"뒤로가기"));assertNull(find(view,"돌아가기"));assertNull(find(view,"홈으로 이동"));
  Button settings=find(view,"설정 열기");assertNotNull(settings);verifyNoInteractions(open);
  render(view);
  settings.performClick();verify(open).run();
 }
 @Test public void commerceEntryNamesTheAppAndStillRequiresOneUserClick() {
  TaskFlowOverlayView view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
  int[] clicks={0};view.appEntryRecovery("쿠팡",()->clicks[0]++);
  Button open=find(view,"쿠팡 열기");assertNotNull(open);assertEquals(0,clicks[0]);
  open.performClick();open.performClick();assertEquals(1,clicks[0]);
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
 @Test public void anUnacceptedLaunchDoesNotLeaveADeadDisabledButton() {
  var view=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),mock(TaskFlowOverlayView.Listener.class));
  Runnable declined=mock(Runnable.class);view.settingsEntryRecovery("이동을 확인하지 못했어요.",declined);
  Button settings=find(view,"설정 열기");settings.performClick();assertTrue(settings.isEnabled());
  assertEquals("이동을 확인하지 못했어요.",view.guidanceMessage());
 }
 private void render(TaskFlowOverlayView view) throws Exception {
  int width=view.getWindowWidth(840);
  view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
  view.layout(0,0,width,view.getMeasuredHeight());
  android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
  var canvas=new android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);view.draw(canvas);
  var path=java.nio.file.Path.of(System.getProperty("click.contractOutput"),"settings-entry.png");
  java.nio.file.Files.createDirectories(path.getParent());
  try(var out=java.nio.file.Files.newOutputStream(path)){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
 }
}
