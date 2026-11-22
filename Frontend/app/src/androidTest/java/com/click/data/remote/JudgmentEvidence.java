package com.click.data.remote;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.SettingsSliderTarget;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;

/** Independent public result regions. No product completion signal or full screenshot is saved. */
final class JudgmentEvidence {
    static org.json.JSONObject publicFrame(ScreenSnapshot screen, JSONArray whitelist)throws Exception {
        var out=new org.json.JSONObject();if(screen==null)return out.put("available",false);
        var rows=new JSONArray();int sliders=0;
        for(var node:screen.nodes){
            if(node.visibleToUser&&"slider".equals(node.role))sliders++;
            for(int i=0;i<whitelist.length();i++)if(whitelist.optString(i).equals(node.text)){
                rows.put(new org.json.JSONObject().put("public_label",whitelist.optString(i))
                    .put("bounds",node.bounds==null?org.json.JSONObject.NULL:new JSONArray(node.bounds))
                    .put("visible",node.visibleToUser).put("clickable",node.clickable));break;
            }
        }
        return out.put("available",true).put("nodes",screen.nodes.size()).put("visible_sliders",sliders)
            .put("public_rows",rows);
    }
    record Proof(String key, Rect bounds) {}
    static Proof settingsHeading(UiAutomation ui, JSONArray titles, int height) {
        for(var window:ui.getWindows()){
            var root=window.getRoot();if(root==null)continue;
            try{
                String pkg=String.valueOf(root.getPackageName());
                if(!settingsResultPackage(pkg,titles))continue;
                Rect found=heading(root,titles,height);
                if(found!=null)return new Proof(pkg+":"+window.getId()+":"+found,found);
            }finally{root.recycle();}
        }
        return null;
    }
    static boolean settingsResultPackage(String pkg,JSONArray titles){
        if(Set.of("com.android.settings","com.samsung.accessibility").contains(pkg))return true;
        if(!"com.samsung.android.secsoundpicker".equals(pkg))return false;
        // Samsung launches its system picker for these two navigation goals.
        // Capture only the exact public toolbar, never ringtone names/options.
        for(int i=0;i<titles.length();i++)if(Set.of("벨소리","알림 소리","Ringtone","Phone ringtone",
            "Notification sound","Default notification sound").contains(titles.optString(i)))return true;
        return false;
    }
    private static Rect heading(AccessibilityNodeInfo node,JSONArray titles,int height){
        if(node.isVisibleToUser()&&node.getText()!=null){
            boolean match=false;for(int i=0;i<titles.length();i++)match|=node.getText().toString().equals(titles.optString(i));
            if(match){
                boolean actionable=node.isClickable()||node.isCheckable();var parent=node.getParent();
                while(parent!=null){
                    if(parent.isScrollable()){parent.recycle();break;}
                    actionable|=parent.isClickable()||parent.isCheckable();var next=parent.getParent();parent.recycle();parent=next;
                }
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                if(!actionable&&!bounds.isEmpty()&&bounds.top>=0&&bounds.bottom<=height*.42)return bounds;
            }
        }
        for(int i=0;i<node.getChildCount();i++){
            var child=node.getChild(i);if(child==null)continue;
            try{Rect found=heading(child,titles,height);if(found!=null)return found;}finally{child.recycle();}
        }
        return null;
    }
    static Proof settingControl(ScreenSnapshot screen,String setting){
        if(screen==null||!screen.privacyMaskingReady||screen.activeWindowId==null)return null;
        Proof found=null;
        for(var n:screen.nodes)if(n.visibleToUser&&n.enabled&&!n.sensitiveInput
                &&n.windowId==screen.activeWindowId&&SettingsSliderTarget.matches(screen,n,setting)){
            if(found!=null||n.bounds==null)return null;
            Rect bounds=new Rect(n.bounds[0],n.bounds[1],n.bounds[2],n.bounds[3]);
            if(bounds.isEmpty()||bounds.left<0||bounds.top<0||bounds.right>screen.screenWidth||bounds.bottom>screen.screenHeight)return null;
            found=new Proof(screen.packageName+":"+screen.activeWindowId+":"+setting+":"+bounds,bounds);
        }
        return found;
    }
    static boolean covered(Proof proof,View panel){
        if(proof==null)return true;
        if(panel==null||!panel.isShown()||panel.getAlpha()<=0)return false;
        int[] xy=new int[2];panel.getLocationOnScreen(xy);
        return Rect.intersects(proof.bounds,new Rect(xy[0],xy[1],xy[0]+panel.getWidth(),xy[1]+panel.getHeight()));
    }
    static String capture(UiAutomation ui,File cache,Proof proof)throws Exception{
        Bitmap screenshot=ui.takeScreenshot();if(screenshot==null)throw new IllegalStateException("SCREENSHOT_UNAVAILABLE");
        Bitmap crop=null;
        try{
            Rect b=proof.bounds;
            if(b.left<0||b.top<0||b.right>screenshot.getWidth()||b.bottom>screenshot.getHeight())throw new IllegalStateException("EVIDENCE_OUTSIDE_FRAME");
            crop=Bitmap.createBitmap(screenshot,b.left,b.top,b.width(),b.height());
            File folder=new File(cache,"judgment-evidence");if(!folder.exists()&&!folder.mkdirs())throw new IllegalStateException("EVIDENCE_DIRECTORY");
            File file=new File(folder,UUID.randomUUID()+".png");
            try(var out=new FileOutputStream(file)){if(!crop.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("EVIDENCE_ENCODING");}
            return "judgment-evidence/"+file.getName();
        }finally{if(crop!=null&&crop!=screenshot)crop.recycle();screenshot.recycle();}
    }
}
