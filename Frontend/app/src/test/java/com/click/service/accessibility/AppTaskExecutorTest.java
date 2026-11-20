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
import java.util.*;
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
    private AccessibilityNodeInfo button(){
        AccessibilityNodeInfo n=mock(AccessibilityNodeInfo.class);
        when(n.getPackageName()).thenReturn(pkg);when(n.getWindowId()).thenReturn(42);
        when(n.getClassName()).thenReturn("android.widget.Button");when(n.getViewIdResourceName()).thenReturn(pkg+":id/node_0");
        when(n.isEnabled()).thenReturn(true);when(n.isVisibleToUser()).thenReturn(true);when(n.isClickable()).thenReturn(true);
        doAnswer(call->{((Rect)call.getArgument(0)).set(10,20,400,120);return null;}).when(n).getBoundsInScreen(any(Rect.class));
        when(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true);return n;
    }
    private AccessibilityNodeSnapshot resultText(){
        return new AccessibilityNodeSnapshot("node_0_1",null,true,pkg,"안내 결과",null,pkg+":id/result_text","android.widget.TextView",null,
            new int[]{10,180,400,240},false,true,false,false,false,null,42);
    }
    @Test public void resultProofProjectionDoesNotBlockAnUnchangedNativeTap()throws Exception {
        AccessibilityNodeInfo button=button();AccessibilityService service=service(button);
        ScreenSnapshot same=screen(pkg,node(pkg,"node_0","검색","button"),resultText());
        JSONObject observed=AppTaskObservation.capture(same,Set.of("안내 결과"));
        JSONObject tap=action(AppTaskObservation.capture(same),"tap","screen_changed");
        assertTrue(AppTaskExecutor.execute(service,same,same,observed,tap,null,null));
        verify(button).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    @Test public void sealedProjectionStillRejectsRealWindowAndNativeBoundsChanges()throws Exception {
        AccessibilityNodeInfo button=button();AccessibilityService service=service(button);
        ScreenSnapshot before=screen(pkg,node(pkg,"node_0","검색","button"),resultText());
        JSONObject observed=AppTaskObservation.capture(before,Set.of("안내 결과"));
        JSONObject tap=action(AppTaskObservation.capture(before),"tap","screen_changed");
        ScreenSnapshot otherWindow=new ScreenSnapshot(pkg,java.time.Instant.now().toString(),1080,2400,before.nodes,43);
        assertFalse(AppTaskExecutor.execute(service,before,otherWindow,observed,tap,null,null));
        doAnswer(call->{((Rect)call.getArgument(0)).set(20,30,410,130);return null;}).when(button).getBoundsInScreen(any(Rect.class));
        assertFalse(AppTaskExecutor.execute(service,before,before,observed,tap,null,null));
        verify(button,never()).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    @Test public void changedProofAndLostLocalProjectionFailClosedBeforeNativeClick()throws Exception {
        AccessibilityNodeInfo button=button();AccessibilityService service=service(button);
        ScreenSnapshot before=screen(pkg,node(pkg,"node_0","검색","button"),resultText());
        JSONObject observed=AppTaskObservation.capture(before,Set.of("안내 결과"));
        JSONObject tap=action(AppTaskObservation.capture(before),"tap","screen_changed");
        assertFalse(AppTaskExecutor.execute(service,before,before,new JSONObject(observed.toString()),tap,null,null));
        ScreenSnapshot changed=screen(pkg,node(pkg,"node_0","검색","button"));
        assertFalse(AppTaskExecutor.execute(service,before,changed,observed,tap,null,null));
        verify(button,never()).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
}
