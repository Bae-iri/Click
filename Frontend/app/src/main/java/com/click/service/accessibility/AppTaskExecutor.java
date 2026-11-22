package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.*;
import com.click.service.overlay.AppTaskPolicy;
import com.click.service.overlay.AppEntryController;
import com.click.service.overlay.RequestedApp;
import org.json.*;

/** Shared native tools. Every call corresponds to one explicit execution-button press. */
public final class AppTaskExecutor {
    public static boolean execute(AccessibilityService service,ScreenSnapshot baseline,ScreenSnapshot current,
            JSONObject observation,JSONObject action,String input,String selectedPackage){
        if(service==null||action==null)return reject("service_unavailable");
        try{
            String tool=action.getString("tool");
            if("set_text".equals(tool))return false; // Users type in the highlighted app field.
            if("open_app".equals(tool)){
                return open(service,current,observation,action,new RequestedApp(selectedPackage,selectedPackage));
            }
            if(baseline==null||current==null||!java.util.Objects.equals(baseline.packageName,current.packageName)
                    ||!java.util.Objects.equals(baseline.activeWindowId,current.activeWindowId))return reject("window_changed");
            if(!AppTaskPolicy.allowed(observation,AppTaskObservation.recaptureLike(current,observation),action,selectedPackage))return reject("observation_or_policy_changed");
            if("back".equals(tool))return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            AccessibilityNodeSnapshot target=null;for(var n:current.nodes)if(n.nodeId.equals(action.optString("target_id")))target=n;
            if(target==null||!target.nodeId.matches("node_0(_[0-9]+)*"))return false;
            AccessibilityNodeInfo node=ForegroundWindowReader.readRoot(service);if(node==null)return false;
            try{
                String[] path=target.nodeId.split("_");for(int i=2;i<path.length;i++){
                    AccessibilityNodeInfo child=NodePath.child(node,Integer.parseInt(path[i]));node.recycle();node=child;if(node==null)return false;
                }
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);if(!bounds.intersect(0,0,current.screenWidth,current.screenHeight))return reject("target_outside_screen");
                if(!node.isVisibleToUser()||!node.isEnabled()||node.isPassword()||!current.packageName.contentEquals(String.valueOf(node.getPackageName()))
                        ||current.activeWindowId==null||node.getWindowId()!=current.activeWindowId
                        ||!bounds.equals(new Rect(target.bounds[0],target.bounds[1],target.bounds[2],target.bounds[3]))
                        ||!java.util.Objects.equals(target.className,String.valueOf(node.getClassName()))
                        ||!java.util.Objects.equals(target.resourceId,node.getViewIdResourceName()))return false;
                if("tap".equals(tool))return !node.isEditable()&&node.isClickable()&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if(!node.isScrollable())return false;
                if("scroll_forward".equals(tool))return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if("scroll_backward".equals(tool))return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                return false;
            }finally{if(node!=null)node.recycle();}
        }catch(Exception invalid){return reject("native_tool_exception_"+invalid.getClass().getSimpleName());}
    }
    public static boolean open(Context context, ScreenSnapshot current, JSONObject observation, JSONObject action, RequestedApp target) throws JSONException {
        if (target == null || action == null || !"open_app".equals(action.optString("tool")) || !AppTaskPolicy.allowed(observation, AppTaskObservation.recaptureLike(current,observation), action, target.packageName))
            return reject("app_not_selected");
        return AppEntryController.launch(context,target) == AppEntryController.Launch.STARTED;
    }
    private static boolean reject(String reason){android.util.Log.i("ClickAppTask","execution_rejected reason="+reason);return false;}
    public static boolean inputMatches(AccessibilityService service,ScreenSnapshot screen,String targetId,String expectedHash){
        if(service==null||screen==null||!screen.privacyMaskingReady||screen.activeWindowId==null||expectedHash==null
                ||targetId==null||!targetId.matches("node_0(_[0-9]+)*"))return false;
        AccessibilityNodeSnapshot target=null;
        for(var candidate:screen.nodes)if(targetId.equals(candidate.nodeId)){target=candidate;break;}
        if(target==null||!target.visibleToUser||!target.publicSearchEnabled()||!AppTaskObservation.searchField(target))return false;
        AccessibilityNodeInfo node=ForegroundWindowReader.readRoot(service);if(node==null)return false;
        try{
            String[] path=targetId.split("_");for(int i=2;i<path.length;i++){AccessibilityNodeInfo child=NodePath.child(node,Integer.parseInt(path[i]));node.recycle();node=child;if(node==null)return false;}
            if(node.getWindowId()!=screen.activeWindowId||!screen.packageName.contentEquals(String.valueOf(node.getPackageName()))
                    ||!java.util.Objects.equals(target.resourceId,node.getViewIdResourceName())||!node.isEnabled()||!node.isVisibleToUser()
                    ||node.isPassword()||!node.isEditable()||node.isShowingHintText())return false;
            CharSequence value=node.getText();return value!=null&&AppTaskObservation.hash(value.toString()).equals(expectedHash);
        }finally{if(node!=null)node.recycle();}
    }
}
