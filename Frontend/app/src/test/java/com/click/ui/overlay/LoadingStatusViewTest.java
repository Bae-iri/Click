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
@org.robolectric.annotation.Config(sdk = 34, application = android.app.Application.class)
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
 @Test public void reservedRowKeepsHeightAndOnlyTogglesSpinner() {
  LoadingStatusView view = new LoadingStatusView(RuntimeEnvironment.getApplication(), true);
  int spec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
  int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
  view.measure(spec, unspecified);
  int idle = view.getMeasuredHeight();
  assertEquals(View.VISIBLE, view.getVisibility());
  assertEquals(View.INVISIBLE, view.getChildAt(0).getVisibility());
  view.setStatus("요청을 확인하고 다음 단계를 찾고 있어요…");
  view.measure(spec, unspecified);
  assertEquals(View.VISIBLE, view.getChildAt(0).getVisibility());
  assertEquals(idle, view.getMeasuredHeight());
  view.setStatus(null);
  assertEquals(View.VISIBLE, view.getVisibility());
  assertEquals(View.INVISIBLE, view.getChildAt(0).getVisibility());
  assertEquals("", ((TextView)view.getChildAt(1)).getText().toString());
 }
 @Test public void statusUpdatesNeverInsertProgressIntoInput() {
  ClickOverlayLayout overlay = new ClickOverlayLayout(RuntimeEnvironment.getApplication());
  overlay.setLoadingStatus("음성을 인식하고 있어요…");
  overlay.setVoiceInputProcessing(true);
  overlay.openVoice();
  // Voice mode uses the microphone's processing animation instead of a second spinner.
  assertNull(findVisibleStatus(overlay));
  assertEquals(VoiceCaptureIndicator.State.PROCESSING, findVoiceIndicator(overlay).getState());
  overlay.openText();
  assertNull(findVisibleStatus(overlay));
  overlay.openVoice();
  assertEquals(VoiceCaptureIndicator.State.PROCESSING, findVoiceIndicator(overlay).getState());
  overlay.openText();
  assertNull(findVisibleStatus(overlay));
  overlay.setLoadingStatus(null);
  assertNull(findVisibleStatus(overlay));
 }
 private VoiceCaptureIndicator findVoiceIndicator(android.view.ViewGroup parent) {
  for(int i=0;i<parent.getChildCount();i++) {
   View view=parent.getChildAt(i);
   if(view instanceof VoiceCaptureIndicator)return (VoiceCaptureIndicator)view;
   if(view instanceof android.view.ViewGroup) {
    VoiceCaptureIndicator found=findVoiceIndicator((android.view.ViewGroup)view);if(found!=null)return found;
   }
  }
  return null;
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
