package com.click.ui.overlay;

import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class LoadingStatusViewTest {
 @Test public void showsIndeterminateSpinnerAndStatusAndHidesWhenFinished() {
  LoadingStatusView view = new LoadingStatusView(RuntimeEnvironment.getApplication());
  assertEquals(View.GONE, view.getVisibility());
  view.setStatus("화면을 분석하고 있어요…");
  assertEquals(View.VISIBLE, view.getVisibility());
  assertTrue(((ProgressBar)view.getChildAt(0)).isIndeterminate());
  assertEquals("화면을 분석하고 있어요…", ((TextView)view.getChildAt(1)).getText().toString());
  view.setStatus("음성을 인식하고 있어요…");
  assertEquals("음성을 인식하고 있어요…", ((TextView)view.getChildAt(1)).getText().toString());
  view.setStatus(null);
  assertEquals(View.GONE, view.getVisibility());
  assertEquals("", ((TextView)view.getChildAt(1)).getText().toString());
 }
 @Test public void statusSurvivesInputModeChanges() {
  ClickOverlayLayout overlay = new ClickOverlayLayout(RuntimeEnvironment.getApplication());
  overlay.setLoadingStatus("음성을 인식하고 있어요…");
  overlay.openVoice();
  assertNotNull(findVisibleStatus(overlay));
  overlay.openText();
  assertNotNull(findVisibleStatus(overlay));
  overlay.setLoadingStatus(null);
  assertNull(findVisibleStatus(overlay));
 }
 private LoadingStatusView findVisibleStatus(android.view.ViewGroup parent) {
  for(int i=0;i<parent.getChildCount();i++) {
   View v=parent.getChildAt(i);
   if(v instanceof LoadingStatusView && v.getVisibility()==View.VISIBLE) return (LoadingStatusView)v;
   if(v instanceof android.view.ViewGroup) {
    LoadingStatusView found=findVisibleStatus((android.view.ViewGroup)v);if(found!=null)return found;
   }
  }
  return null;
 }
}
