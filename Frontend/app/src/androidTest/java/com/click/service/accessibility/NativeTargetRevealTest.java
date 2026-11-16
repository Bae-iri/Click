package com.click.service.accessibility;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in: scrolls a Settings menu only; no taps, setting writes or server requests. */
@RunWith(AndroidJUnit4.class)
public class NativeTargetRevealTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private UiAutomation ui;
    private ScreenSnapshot read() {
        // Use the app's real observation boundary and display metrics, as rendering does.
        return ClickAccessibilityService.freshSnapshot();
    }
    private AccessibilityNodeSnapshot display(ScreenSnapshot screen) {
        if(screen!=null)for(var node:screen.nodes) {
            if(!node.visibleToUser || !"디스플레이".equals(node.text) || node.scrollable)continue;
            // Jev selects the actionable row, not just its inset text label.
            var target=node;Set<String> seen=new HashSet<>();
            while(target.parentId!=null && seen.add(target.nodeId)) {
                AccessibilityNodeSnapshot parent=null;
                for(var candidate:screen.nodes)if(target.parentId.equals(candidate.nodeId)){parent=candidate;break;}
                if(parent==null || parent.scrollable)break;
                if(parent.clickable && parent.enabled && !parent.checkable)return parent;
                target=parent;
            }
            return node;
        }
        return null;
    }
    private void swipe(int[] area,float distance) {
        float x=area[0]+(area[2]-area[0])*.06f, y=(area[1]+area[3])/2f;
        long down=SystemClock.uptimeMillis();
        for(int step=0;step<=9;step++) {
            int action=step==0?MotionEvent.ACTION_DOWN:step==9?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y-distance*Math.min(step,8)/8f,0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try {assertTrue(ui.injectInputEvent(event,true));}finally{event.recycle();}
            SystemClock.sleep(40);
        }
        SystemClock.sleep(550);
    }
    private void prepareWithExistingGrant() {
        assertFalse("Unlock the phone",app.getSystemService(KeyguardManager.class).isDeviceLocked());
        ui=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(info);
        String key=Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original=Settings.Secure.getString(app.getContentResolver(),key);
        ComponentName component=new ComponentName(app,ClickAccessibilityService.class);
        List<String> others=new ArrayList<>();boolean enabled=false;
        if(original!=null)for(String entry:original.split(":")) {
            if(component.equals(ComponentName.unflattenFromString(entry)))enabled=true;else others.add(entry);
        }
        assertTrue("Requires an existing user service grant",enabled);
        ui.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {Settings.Secure.putString(app.getContentResolver(),key,String.join(":",others));SystemClock.sleep(200);}
        finally {Settings.Secure.putString(app.getContentResolver(),key,original);ui.dropShellPermissionIdentity();}
    }
    @Test public void clippedDisplayRowIsRevealedWithoutClickingIt() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("targetReveal")));
        prepareWithExistingGrant();
        Intent settings=new Intent(Settings.ACTION_SETTINGS);
        app.startActivity(settings.setComponent(settings.resolveActivity(app.getPackageManager()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
        SystemClock.sleep(900);
        // Instrumentation and an APK update can restart the service; wait for its
        // actual binding instead of treating an unavailable snapshot as geometry.
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(read()==null && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
        ScreenSnapshot before=null;AccessibilityNodeSnapshot target=null;TargetVisibility.Plan plan=null;
        float density=app.getResources().getDisplayMetrics().density;
        boolean bottomEdge="bottom".equals(InstrumentationRegistry.getArguments().getString("targetRevealEdge"));
        for(int attempt=0;attempt<10;attempt++) {
            before=read();assertNotNull(before);target=display(before);
            if(target!=null) {
                plan=TargetVisibility.plan(before,target.nodeId,target.bounds,density);
                if(plan!=null && plan.forward==bottomEdge)break;
                AccessibilityNodeSnapshot list=null;
                for(var node:before.nodes)if(node.scrollable && node.visibleToUser) {list=node;break;}
                assertNotNull("Native Settings list",list);
                float desired=bottomEdge?list.bounds[3]-(target.bounds[3]-target.bounds[1])*.5f:list.bounds[1];
                float delta=(target.bounds[1]+target.bounds[3])*.5f-desired;
                float limit=(list.bounds[3]-list.bounds[1])*.4f;
                swipe(list.bounds,Math.max(-limit,Math.min(limit,delta)));
            } else {
                AccessibilityNodeInfo root=ui.getRootInActiveWindow();
                try {assertNotNull(root);assertTrue(new NativeScrollExecutor(newService(),new Handler(Looper.getMainLooper()),()->{}).performScroll(true));}
                finally{if(root!=null)root.recycle();}
                SystemClock.sleep(600);
            }
        }
        assertNotNull("Prepare an edge-clipped Display row",plan);
        assertEquals("Prepare the requested viewport edge",bottomEdge,plan.forward);
        int[] oldBounds=target.bounds.clone();boolean forward=plan.forward;
        boolean dispatched=ClickAccessibilityService.revealSettingsTarget(target.nodeId,target.bounds,before.packageName,before.activeWindowId);
        JSONObject diagnostic=new JSONObject().put("dispatched",dispatched).put("before_top",oldBounds[1])
                .put("before_bottom",oldBounds[3]).put("forward",forward);
        Files.write(new File(app.getCacheDir(),"native-target-reveal.json").toPath(),diagnostic.toString(2).getBytes(StandardCharsets.UTF_8));
        assertTrue("Native target reveal dispatched",dispatched);
        SystemClock.sleep(1100);
        ScreenSnapshot after=read();AccessibilityNodeSnapshot revealed=display(after);assertNotNull(revealed);
        assertFalse("The native list must move",Arrays.equals(oldBounds,revealed.bounds));
        assertNull("The row must be inside the comfortable viewport",TargetVisibility.plan(after,revealed.nodeId,revealed.bounds,density));
        JSONObject report=new JSONObject().put("passed",true).put("forward",forward)
                .put("edge",bottomEdge?"bottom":"top").put("target_kind","clickable_row")
                .put("before_top",oldBounds[1]).put("before_bottom",oldBounds[3])
                .put("after_top",revealed.bounds[1]).put("after_bottom",revealed.bounds[3])
                .put("server_posts",0).put("setting_writes",0).put("target_clicked",false);
        Files.write(new File(app.getCacheDir(),"native-target-reveal.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Test public void fontMenuIsRevealedPastDisplayControlsWithoutChangingSettings() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("fontReveal")));
        prepareWithExistingGrant();
        String brightness=Settings.System.getString(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS);
        String font=Settings.System.getString(app.getContentResolver(),Settings.System.FONT_SCALE);
        app.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(1200);
        float density=app.getResources().getDisplayMetrics().density;
        ScreenSnapshot before=null;AccessibilityNodeSnapshot target=null;TargetVisibility.Plan plan=null;
        for(int attempt=0;attempt<12;attempt++) {
            before=read();
            if(before==null){SystemClock.sleep(500);continue;}
            target=null;
            for(var node:before.nodes)if(node.visibleToUser && "글자 크기와 스타일".equals(node.text)) {
                target=node;
                for(var parent:before.nodes)if(node.parentId!=null && node.parentId.equals(parent.nodeId)
                        && parent.clickable && !parent.checkable)target=parent;
                break;
            }
            if(target!=null) {
                plan=TargetVisibility.plan(before,target.nodeId,target.bounds,density);
                if(plan!=null)break;
                android.util.Log.i("ClickScrollTest","font_prepare attempt="+attempt+" row="+Arrays.toString(target.bounds)
                        +" start="+(before.navigation!=null && before.navigation.atStart())+" end="+(before.navigation!=null && before.navigation.atEnd()));
            }
            if(target==null) {
                assertTrue(ClickAccessibilityService.performSettingsScroll(true,before.packageName,before.activeWindowId));
                SystemClock.sleep(650);
            } else {
                AccessibilityNodeSnapshot list=null;
                for(var node:before.nodes)if(node.scrollable && node.visibleToUser){list=node;break;}
                assertNotNull(list);
                float desired=list.bounds[1]+(target.bounds[3]-target.bounds[1])*.5f;
                float delta=(target.bounds[1]+target.bounds[3])*.5f-desired;
                float limit=Math.min(96*density,(list.bounds[3]-list.bounds[1])*.25f);
                swipeDisplaySafely(before,list.bounds,Math.max(-limit,Math.min(limit,delta)));
            }
        }
        assertNotNull("Font menu must be found and prepared at a viewport edge",plan);
        int[] oldBounds=target.bounds.clone();
        assertTrue("Display controls must not block a safe reveal segment",
                ClickAccessibilityService.revealSettingsTarget(target.nodeId,target.bounds,before.packageName,before.activeWindowId));
        SystemClock.sleep(1100);
        ScreenSnapshot after=read();assertNotNull(after);
        AccessibilityNodeSnapshot moved=null;
        for(var node:after.nodes)if(node.visibleToUser && "글자 크기와 스타일".equals(node.text)) {
            moved=node;
            for(var parent:after.nodes)if(node.parentId!=null && node.parentId.equals(parent.nodeId)
                    && parent.clickable && !parent.checkable)moved=parent;
            break;
        }
        assertNotNull("Font menu remains visible after reveal",moved);
        assertFalse("The font row must actually move",Arrays.equals(oldBounds,moved.bounds));
        assertNull("Fresh font row is comfortable to highlight",TargetVisibility.plan(after,moved.nodeId,moved.bounds,density));
        assertEquals("Brightness must stay unchanged",brightness,Settings.System.getString(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS));
        assertEquals("Font scale must stay unchanged",font,Settings.System.getString(app.getContentResolver(),Settings.System.FONT_SCALE));
        android.util.Log.i("ClickScrollTest","font_reveal_passed before_top="+oldBounds[1]+" after_top="+moved.bounds[1]+" setting_writes=0");
    }
    private void swipeDisplaySafely(ScreenSnapshot screen,int[] area,float distance) throws Exception {
        List<android.graphics.Rect> controls=new ArrayList<>();
        for(var node:screen.nodes)if(node.visibleToUser && node.bounds!=null
                && ("slider".equals(node.role) || "text_field".equals(node.role) || node.checkable))
            controls.add(new android.graphics.Rect(node.bounds[0],node.bounds[1],node.bounds[2],node.bounds[3]));
        float[] segment=ScrollGesturePath.vertical(new android.graphics.Rect(area[0],area[1],area[2],area[3]),
                Math.max(1,Math.round(Math.abs(distance))),distance>0,controls,24);
        assertNotNull("Safe list preparation path",segment);
        android.graphics.Path path=new android.graphics.Path();path.moveTo(segment[0],segment[1]);path.lineTo(segment[2],segment[3]);
        var gesture=new android.accessibilityservice.GestureDescription.Builder().addStroke(
                new android.accessibilityservice.GestureDescription.StrokeDescription(path,0,400)).build();
        ClickAccessibilityService service=newService();
        java.util.concurrent.atomic.AtomicBoolean dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        instrumentation.runOnMainSync(()->dispatched.set(service.dispatchGesture(gesture,
                new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(android.accessibilityservice.GestureDescription value){completed.set(true);done.countDown();}
                    @Override public void onCancelled(android.accessibilityservice.GestureDescription value){done.countDown();}
                },new Handler(Looper.getMainLooper()))));
        assertTrue("Native preparation gesture accepted",dispatched.get());
        assertTrue("Native preparation gesture callback",done.await(3,java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("Native preparation gesture completed without cancellation",completed.get());
        SystemClock.sleep(500);
    }
    private ClickAccessibilityService newService() throws Exception {
        var field=ClickAccessibilityService.class.getDeclaredField("connectedService");field.setAccessible(true);
        ClickAccessibilityService service=(ClickAccessibilityService)((java.lang.ref.WeakReference<?>)field.get(null)).get();
        assertNotNull("Existing service connected",service);return service;
    }
}
