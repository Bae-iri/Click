package com.click.service.accessibility;

import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.*;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.*;
import com.click.service.overlay.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real launcher -> manually opened target -> native search. No account/queue writes or purchases. */
@RunWith(AndroidJUnit4.class)
public class RequestedAppEntryDeviceTest {
    @Test public void namedCoupangRequestContinuesAfterManualOpening()throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();var context=instrumentation.getTargetContext();
        var automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=automation.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(info);
        RequestedApp target=RequestedApp.find(context,"쿠팡에서 물 주문하고 싶어");assertNotNull(target);assertEquals("com.coupang.mobile",target.packageName);
        AtomicReference<ScreenSnapshot> current=new AtomicReference<>();AtomicReference<JSONObject> result=new AtomicReference<>();AppTaskController[] controller={null};
        context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(600);
        current.set(read(automation,context,null));
        instrumentation.runOnMainSync(()->{
            try{controller[0]=new AppTaskController(new AppGuideGateway(context),new AppTaskMemory("쿠팡에서 물 주문하고 싶어"),null,new AppTaskController.Listener(){
                public ScreenSnapshot fresh(){return current.get();}public boolean canObserve(){return true;}public void busy(boolean busy){}
                public void present(JSONObject value,ScreenSnapshot screen){result.set(value);}public void failure(Throwable error){throw new AssertionError(error);}
                public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String pkg){throw new AssertionError("Read-only probe must not execute an action");}
                public boolean inputMatches(String id,String hash){return false;}
            },false);controller[0].targetApp(target.packageName,target.label);controller[0].refresh();}catch(Exception e){throw new AssertionError(e);}
        });
        try{
            assertEquals("open_app",result.get().getJSONObject("action").getString("tool"));
            context.startActivity(context.getPackageManager().getLaunchIntentForPackage(target.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ScreenSnapshot screen=null;
            for(int i=0;i<40;i++){SystemClock.sleep(250);screen=read(automation,context,target.packageName);if(screen!=null&&AppTaskObservation.capture(screen).getJSONArray("nodes").length()>0){SystemClock.sleep(800);screen=read(automation,context,target.packageName);break;}}
            assertNotNull("Coupang native screen must load",screen);current.set(screen);
            instrumentation.runOnMainSync(()->controller[0].screenChanged(current.get()));SystemClock.sleep(500);instrumentation.waitForIdleSync();
            JSONObject proposed=result.get();
            assertFalse("Entry must not stay on the launcher proposal",proposed.optJSONObject("action")!=null&&"open_app".equals(proposed.getJSONObject("action").optString("tool")));
            boolean searchAvailable=proposed.optJSONObject("action")!=null;
            if(searchAvailable){
                String tool=proposed.getJSONObject("action").getString("tool");assertTrue(tool,tool.equals("tap")||tool.equals("set_text"));assertTrue(proposed.getBoolean("execute_allowed"));
                if(tool.equals("tap")){
                    nativeAction(automation,target.packageName,proposed.getJSONObject("action").getString("target_id"),AccessibilityNodeInfo.ACTION_CLICK);
                    for(int i=0;i<30;i++){SystemClock.sleep(200);screen=read(automation,context,target.packageName);if(screen!=null&&screen.nodes.stream().anyMatch(AppTaskObservation::searchField))break;}
                    current.set(screen);instrumentation.runOnMainSync(()->controller[0].screenChanged(current.get()));SystemClock.sleep(500);instrumentation.waitForIdleSync();
                }
                assertEquals("set_text",result.get().getJSONObject("action").getString("tool"));assertEquals("물",result.get().getString("suggested_query"));
            }else{
                assertEquals("needs_input",proposed.getString("status"));assertEquals("검색창을 눌러주세요. 화면이 바뀌면 이어서 안내할게요.",proposed.getString("message"));
                // Some installed Coupang surfaces omit their header controls from accessibility.
                // Never invent a target or loop native scrolling on those surfaces.
                assertTrue(proposed.isNull("action"));
            }
            assertFalse(controller[0].memory().finished());
            Bundle report=new Bundle();report.putString("stream","requested_app_resolved=true manual_entry_continued=true native_search_available="+searchAvailable+" queue_writes=0\n");instrumentation.sendStatus(0,report);
        }finally{instrumentation.runOnMainSync(()->controller[0].close());}
    }
    private void nativeAction(UiAutomation automation,String pkg,String id,int action){
        AccessibilityNodeInfo node=root(automation,pkg);assertNotNull(node);
        try{String[] path=id.split("_");for(int i=2;i<path.length;i++){var child=node.getChild(Integer.parseInt(path[i]));node.recycle();node=child;assertNotNull(node);}assertTrue(node.performAction(action));}finally{if(node!=null)node.recycle();}
    }
    private AccessibilityNodeInfo root(UiAutomation automation,String pkg){
        if(pkg==null)return automation.getRootInActiveWindow();
        for(var w:automation.getWindows()){var r=w.getRoot();if(r!=null){if(pkg.contentEquals(String.valueOf(r.getPackageName())))return r;r.recycle();}}
        return null;
    }
    private ScreenSnapshot read(UiAutomation automation,android.content.Context context,String pkg){
        var r=root(automation,pkg);if(r==null)return null;
        try{r.refresh();var display=context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();return new AccessibilitySnapshotCollector().collect(r,String.valueOf(r.getPackageName()),display.width(),display.height(),null);}finally{r.recycle();}
    }
}
