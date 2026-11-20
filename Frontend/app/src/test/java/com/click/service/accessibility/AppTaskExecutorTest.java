package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.click.testing.AppTaskFixtures.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class AppTaskExecutorTest {
    private final String pkg="org.example.app";
    private AccessibilityService service(AccessibilityNodeInfo node){AccessibilityService service=mock(AccessibilityService.class);when(service.getRootInActiveWindow()).thenReturn(node);when(service.getWindows()).thenReturn(List.of());when(service.getPackageName()).thenReturn("com.clueclick.app");return service;}
    private AccessibilityNodeInfo field(){AccessibilityNodeInfo n=mock(AccessibilityNodeInfo.class);when(n.getPackageName()).thenReturn(pkg);when(n.getWindowId()).thenReturn(42);when(n.getClassName()).thenReturn("android.widget.EditText");when(n.getViewIdResourceName()).thenReturn(pkg+":id/search");when(n.isEnabled()).thenReturn(true);when(n.isVisibleToUser()).thenReturn(true);when(n.isEditable()).thenReturn(true);doAnswer(call->{((Rect)call.getArgument(0)).set(10,20,400,120);return null;}).when(n).getBoundsInScreen(any(Rect.class));when(n.performAction(eq(AccessibilityNodeInfo.ACTION_SET_TEXT),any(Bundle.class))).thenReturn(true);return n;}
    @Test public void publicSearchUsesExactUserTextAndNeverSubmitsOrClicks()throws Exception {
        AccessibilityNodeInfo field=field();AccessibilityService service=service(field);ScreenSnapshot screen=screen(pkg,node(pkg,"node_0","","search"));JSONObject observation=AppTaskObservation.capture(screen),a=action(observation,"set_text","input_present");
        assertTrue(AppTaskExecutor.execute(service,screen,screen,observation,a,"PRIVATE_QUERY",null));ArgumentCaptor<Bundle> args=ArgumentCaptor.forClass(Bundle.class);verify(field).performAction(eq(AccessibilityNodeInfo.ACTION_SET_TEXT),args.capture());assertEquals("PRIVATE_QUERY",args.getValue().getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE));verify(field,never()).performAction(AccessibilityNodeInfo.ACTION_CLICK);verify(service,never()).performGlobalAction(anyInt());assertFalse(observation.toString().contains("PRIVATE_QUERY"));
        when(field.getText()).thenReturn("PRIVATE_QUERY");assertTrue(AppTaskExecutor.inputMatches(service,"node_0",AppTaskObservation.hash("PRIVATE_QUERY")));assertFalse(AppTaskExecutor.inputMatches(service,"node_0",AppTaskObservation.hash("different")));
    }
    @Test public void changedNativeIdentityAndPasswordFieldNeverReceiveInput()throws Exception {
        AccessibilityNodeInfo field=field();AccessibilityService service=service(field);ScreenSnapshot screen=screen(pkg,node(pkg,"node_0","","search"));JSONObject observation=AppTaskObservation.capture(screen),a=action(observation,"set_text","input_present");
        when(field.getViewIdResourceName()).thenReturn(pkg+":id/other");assertFalse(AppTaskExecutor.execute(service,screen,screen,observation,a,"PRIVATE_QUERY",null));when(field.getViewIdResourceName()).thenReturn(pkg+":id/search");when(field.isPassword()).thenReturn(true);assertFalse(AppTaskExecutor.execute(service,screen,screen,observation,a,"PRIVATE_QUERY",null));verify(field,never()).performAction(eq(AccessibilityNodeInfo.ACTION_SET_TEXT),any(Bundle.class));
    }
}
