package com.click;

import android.app.Application;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
import com.click.data.local.TouchAutomationSettings;
import com.click.data.remote.AccountActionsGateway;
import com.click.data.remote.GraphImprovementConsentGateway;
import com.click.service.overlay.ClickOverlayService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MenuAutomationConsentTest {
 @Test public void bothRoutesWaitForFullReadingBeforeEnablingAndUseTheSameDialog() {
  for(boolean viaSwitch:new boolean[]{true,false}) {
   try(var graph=mockConstruction(GraphImprovementConsentGateway.class);
       var account=mockConstruction(AccountActionsGateway.class);
       var overlays=mockStatic(ClickOverlayService.class)) {
    TouchAutomationSettings settings=new TouchAutomationSettings(RuntimeEnvironment.getApplication());settings.setEnabled(false);
    try(var controller=Robolectric.buildActivity(MenuActivity.class).setup()) {
     MenuActivity activity=controller.get();View root=activity.getWindow().getDecorView();
     SwitchCompat toggle=(SwitchCompat)find(root,"자동 모드");
     open(root,viaSwitch);Dialog dialog=ShadowDialog.getLatestDialog();layout(dialog);
     View content=dialog.getWindow().getDecorView();
     assertNotNull(find(content,"자동으로 도와주기"));
     assertNotNull(find(content,"결제·전화 걸기·메시지 보내기는 자동으로 실행하지 않아요."));
     assertNotNull(find(content,"화면 속 비밀번호·인증번호 같은 민감한 입력 내용은 서버로 보내지 않아요."));
     assertNotNull(find(content,"개인정보 입력이 필요하거나 안전하게 진행할 수 없는 화면에서는 자동 조작을 멈추고 알려드려요."));
     Button confirm=dialog.findViewById(android.R.id.button1);
     assertEquals("자동 모드 켜기",confirm.getText().toString());
     assertFalse(confirm.isEnabled());assertFalse(settings.isEnabled());assertFalse(toggle.isChecked());
     confirm.performClick();assertTrue(dialog.isShowing());assertFalse(settings.isEnabled());
     ScrollView body=content.findViewWithTag("click_dialog_body_scroll");
     assertTrue(body.canScrollVertically(1));int end=body.getChildAt(0).getHeight()-body.getHeight();
     body.scrollTo(0,end/2);assertFalse(confirm.isEnabled());
     body.scrollTo(0,end);assertTrue(confirm.isEnabled());assertFalse(settings.isEnabled());
     confirm.performClick();assertTrue(settings.isEnabled());assertTrue(toggle.isChecked());assertFalse(dialog.isShowing());
     overlays.verify(ClickOverlayService::onAutomationSettingChanged,times(1));
    } finally {settings.setEnabled(false);}
   }
  }
 }
 @Test public void cancellationDoesNotEnableAndEveryReopenRequiresReadingAgain() {
  try(var graph=mockConstruction(GraphImprovementConsentGateway.class);var account=mockConstruction(AccountActionsGateway.class)) {
   TouchAutomationSettings settings=new TouchAutomationSettings(RuntimeEnvironment.getApplication());settings.setEnabled(false);
   try(var controller=Robolectric.buildActivity(MenuActivity.class).setup()) {
    View root=controller.get().getWindow().getDecorView();
    for(boolean viaSwitch:new boolean[]{true,false}) {
     open(root,viaSwitch);Dialog dialog=ShadowDialog.getLatestDialog();layout(dialog);
     Button confirm=dialog.findViewById(android.R.id.button1);assertFalse(confirm.isEnabled());
     ScrollView body=dialog.getWindow().getDecorView().findViewWithTag("click_dialog_body_scroll");
     body.scrollTo(0,body.getChildAt(0).getHeight());assertTrue(confirm.isEnabled());
     dialog.dismiss();assertFalse(settings.isEnabled());assertFalse(((SwitchCompat)find(root,"자동 모드")).isChecked());
    }
   }
  }
 }
 @Test public void switchingOffRemainsImmediate() {
  try(var graph=mockConstruction(GraphImprovementConsentGateway.class);var account=mockConstruction(AccountActionsGateway.class);
      var overlays=mockStatic(ClickOverlayService.class)) {
   TouchAutomationSettings settings=new TouchAutomationSettings(RuntimeEnvironment.getApplication());settings.setEnabled(true);
   try(var controller=Robolectric.buildActivity(MenuActivity.class).setup()) {
    ((SwitchCompat)find(controller.get().getWindow().getDecorView(),"자동 모드")).performClick();
    assertFalse(settings.isEnabled());Dialog latest=ShadowDialog.getLatestDialog();assertTrue(latest==null||!latest.isShowing());
    overlays.verify(ClickOverlayService::onAutomationSettingChanged,times(1));
   }
  }
 }
 private void open(View root,boolean viaSwitch) {
  View view=find(root,viaSwitch?"자동 모드":"자동으로 도와주기");
  while(!view.isClickable()) view=(View)view.getParent();view.performClick();
 }
 private void layout(Dialog dialog) {
  View root=dialog.getWindow().getDecorView();
  int width=Math.round(380*root.getResources().getDisplayMetrics().density);
  root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1900,View.MeasureSpec.AT_MOST));
  root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
 }
 private TextView find(View view,String text) {
  if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return(TextView)view;
  if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
  return null;
 }
}
