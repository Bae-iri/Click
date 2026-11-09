package com.click.service.accessibility;

import android.app.UiAutomation;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import com.click.service.overlay.GuidedActionTracker;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in physical input probe. Restores FONT_SCALE and exports no screen text. */
@RunWith(AndroidJUnit4.class)
public class ManualFontRangeProbeTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final JSONObject report=new JSONObject();
    private static String locate(AccessibilityNodeInfo root,AccessibilityNodeInfo source,String id) {
        if(root==null)return null;if(root.equals(source))return id;
        for(int i=0;i<root.getChildCount();i++) {
            AccessibilityNodeInfo child=root.getChild(i);
            try {String found=locate(child,source,id+"_"+i);if(found!=null)return found;}
            finally {if(child!=null)child.recycle();}
        }
        return null;
    }
    private static void touch(UiAutomation ui,long down,int action,float x,float y) {
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {assertTrue(ui.injectInputEvent(e,true));} finally {e.recycle();}
    }
    private void openFontPage(UiAutomation ui) {
        Intent display=new Intent(Settings.ACTION_DISPLAY_SETTINGS);
        var component=display.resolveActivity(app.getPackageManager());assertNotNull(component);
        app.startActivity(display.setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
        for(int attempt=0;attempt<12;attempt++) {
            SystemClock.sleep(350);
            AccessibilityNodeInfo root=ui.getRootInActiveWindow();if(root==null)continue;
            try {
                if(!"com.android.settings".contentEquals(root.getPackageName()))continue;
                var sliders=root.findAccessibilityNodeInfosByViewId("com.android.settings:id/seekBarForFontSize");
                boolean ready=!sliders.isEmpty();for(var slider:sliders)slider.recycle();
                if(ready)return;
                var labels=root.findAccessibilityNodeInfosByText("글자 크기와 스타일");boolean clicked=false;
                for(var label:labels) {
                    if(!clicked && label.isVisibleToUser()) {
                        AccessibilityNodeInfo row=AccessibilityNodeInfo.obtain(label);
                        while(row!=null) {
                            if(row.isClickable()) {clicked=row.performAction(AccessibilityNodeInfo.ACTION_CLICK);row.recycle();break;}
                            AccessibilityNodeInfo parent=row.getParent();row.recycle();row=parent;
                        }
                    }
                    label.recycle();
                }
                if(!clicked)scroll(root);
            } finally {root.recycle();}
        }
        fail("Could not navigate from Display to the native font slider");
    }
    private static boolean scroll(AccessibilityNodeInfo node) {
        if(node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            try {if(child!=null && scroll(child))return true;}finally{if(child!=null)child.recycle();}
        }
        return false;
    }
    @Test public void physicalRangeEventSurvivesDelayedRootLookupAndRestoresOriginalScale() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("probeManualFontRange")));
        UiAutomation ui=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout=0;ui.setServiceInfo(info);
        for(var enabled:app.getSystemService(AccessibilityManager.class).getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            if(enabled.getResolveInfo()!=null && app.getPackageName().equals(enabled.getResolveInfo().serviceInfo.packageName))
                report.put("click_service_notification_timeout_ms",enabled.notificationTimeout);
        float original=Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE);
        JSONArray events=new JSONArray();AtomicInteger accepted=new AtomicInteger(),pending=new AtomicInteger();
        Handler handler=new Handler(Looper.getMainLooper());
        report.put("original_font_scale",original).put("source","physical_native_range_event_with_150ms_delayed_root_lookup")
            .put("events",events).put("server_posts",0);
        try {
            assertFalse(app.getSystemService(android.app.KeyguardManager.class).isDeviceLocked());
            openFontPage(ui);
            AccessibilityNodeInfo root=ui.getRootInActiveWindow();assertNotNull(root);
            ScreenSnapshot screen;
            try {
                android.graphics.Rect area=new android.graphics.Rect();root.getBoundsInScreen(area);
                screen=new AccessibilitySnapshotCollector().collect(root,String.valueOf(root.getPackageName()),area.right,area.bottom,original);
            } finally {root.recycle();}
            AccessibilityNodeSnapshot target=null;
            for(var node:screen.nodes)if(node.progress!=null && SettingsSliderTarget.matches(screen,node,"font_size")) {
                assertNull("One font slider",target);target=node;
            }
            assertNotNull("Native font slider",target);assertNotNull(target.rangeMin);assertNotNull(target.rangeMax);
            int[] track=target.bounds.clone();
            for(var parent:screen.nodes)if(parent.nodeId.equals(target.parentId) && "slider".equals(parent.role)
                    && parent.bounds[0]<=track[0] && parent.bounds[2]>=track[2])track=parent.bounds.clone();
            GuidedActionTracker tracker=new GuidedActionTracker();
            tracker.bind("probe","probe","physical-guide","drag",target.nodeId,target.bounds,screen,System.currentTimeMillis());
            ui.setOnAccessibilityEventListener(event -> {
                if(!"com.android.settings".contentEquals(event.getPackageName()==null?"":event.getPackageName()))return;
                if(event.getEventType()!=AccessibilityEvent.TYPE_VIEW_SELECTED && event.getEventType()!=AccessibilityEvent.TYPE_VIEW_SCROLLED
                        && event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)return;
                AccessibilityNodeInfo source=event.getSource();if(source==null)return;
                var range=source.getRangeInfo();
                if(range==null){source.recycle();return;}
                String resource=source.getViewIdResourceName();int window=source.getWindowId();
                android.graphics.Rect box=new android.graphics.Rect();source.getBoundsInScreen(box);
                int[] bounds={box.left,box.top,box.right,box.bottom};
                float value=range.getCurrent(),min=range.getMin(),max=range.getMax();
                long at=System.currentTimeMillis()-(SystemClock.uptimeMillis()-event.getEventTime());
                pending.incrementAndGet();
                handler.postDelayed(() -> {
                    AccessibilityNodeInfo current=ui.getRootInActiveWindow();String id;
                    try {id=locate(current,source,"node_0");}finally{if(current!=null)current.recycle();source.recycle();}
                    JSONObject action=tracker.recordRangeChange("com.android.settings",window,id,resource,bounds,at,value,min,max);
                    if(action!=null)accepted.incrementAndGet();
                    try {synchronized(events){if(events.length()<32)events.put(new JSONObject().put("root_contains_source",id!=null)
                        .put("value",value).put("matched_original_guide",action!=null));}}catch(Exception ignored){}
                    pending.decrementAndGet();
                },150);
            });
            float progress=target.progress;
            float next=progress<target.rangeMax?Math.min(target.rangeMax,progress+1):progress-1;
            float width=track[2]-track[0]-32, y=(track[1]+track[3])/2f;
            float from=track[0]+16+(progress-target.rangeMin)/(target.rangeMax-target.rangeMin)*width;
            float to=track[0]+16+(next-target.rangeMin)/(target.rangeMax-target.rangeMin)*width;
            long down=SystemClock.uptimeMillis();touch(ui,down,MotionEvent.ACTION_DOWN,from,y);
            for(int step=1;step<=8;step++){SystemClock.sleep(45);touch(ui,down,MotionEvent.ACTION_MOVE,from+(to-from)*step/8f,y);}
            touch(ui,down,MotionEvent.ACTION_UP,to,y);
            SystemClock.sleep(1800);
            ui.setOnAccessibilityEventListener(null);
            report.put("after_font_scale",Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE))
                .put("matched_events",accepted.get());
            assertTrue("A real native range event must match the original guide",accepted.get()>0);
        } finally {
            ui.setOnAccessibilityEventListener(null);
            for(int i=0;i<15 && pending.get()>0;i++)SystemClock.sleep(100);
            try(ParcelFileDescriptor fd=ui.executeShellCommand("settings put system font_scale "+original);
                InputStream in=new FileInputStream(fd.getFileDescriptor())){in.readAllBytes();}
            SystemClock.sleep(700);
            float restored=Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE);
            report.put("restored_font_scale",restored);
            Files.write(new File(app.getCacheDir(),"manual-font-range-probe.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            assertEquals(original,restored,.0001f);
        }
    }
}
