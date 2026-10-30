package com.click.service.overlay;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.LoadingStatusView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class LoadingStatusLifecycleTest {
 private void field(Object obj, String name, Object value) throws Exception {
  Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);f.set(obj,value);
 }
 private LoadingStatusView find(ViewGroup parent) {
  for(int i=0;i<parent.getChildCount();i++) {
   View child=parent.getChildAt(i);
   if(child instanceof LoadingStatusView)return (LoadingStatusView)child;
   if(child instanceof ViewGroup){LoadingStatusView result=find((ViewGroup)child);if(result!=null)return result;}
  }
  return null;
 }
 @Test public void speechVisionAndServerWaitStatesEndWithoutStaleSpinner() throws Exception {
  ClickOverlayService service=new ClickOverlayService();
  ClickOverlayLayout overlay=new ClickOverlayLayout(RuntimeEnvironment.getApplication());overlay.openVoice();
  field(service,"overlayView",overlay);
  Method refresh=ClickOverlayService.class.getDeclaredMethod("refreshLoadingStatus");refresh.setAccessible(true);
  LoadingStatusView status=find(overlay);
  field(service,"listening",true);refresh.invoke(service);
  assertEquals(View.VISIBLE,status.getVisibility());
  assertTrue(((TextView)status.getChildAt(1)).getText().toString().contains("듣고"));
  field(service,"listening",false);field(service,"speechProcessing",true);refresh.invoke(service);
  assertTrue(((TextView)status.getChildAt(1)).getText().toString().contains("음성을 인식"));
  field(service,"speechProcessing",false);field(service,"visionCaptureInFlight",true);refresh.invoke(service);
  assertTrue(((TextView)status.getChildAt(1)).getText().toString().contains("화면을 분석"));
  field(service,"visionCaptureInFlight",false);field(service,"turnInFlight",true);refresh.invoke(service);
  assertEquals(View.VISIBLE,status.getVisibility());
  field(service,"turnInFlight",false);refresh.invoke(service);
  assertEquals(View.GONE,status.getVisibility());
 }
}
