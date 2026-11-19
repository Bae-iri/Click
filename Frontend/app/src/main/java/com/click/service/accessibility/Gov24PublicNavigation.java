package com.click.service.accessibility;

import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.Gov24LoginPrivacy;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reveals a known public service; never clicks a service or reads editable values. */
public final class Gov24PublicNavigation {
    private static final JSONObject POLICY;
    static {try {POLICY=new JSONObject(com.click.generated.Gov24LoginPolicyData.json());}
        catch(Exception error){throw new ExceptionInInitializerError(error);}}
    private static final class Scan {
        int visited,matches,inputs;boolean privateBoundary;AccessibilityNodeInfo target,scroll;
        final AccessibilityTraversalBudget budget=new AccessibilityTraversalBudget(800,40,150,android.os.SystemClock::elapsedRealtime);
        void close(){if(target!=null)target.recycle();if(scroll!=null)scroll.recycle();}
    }
    public static boolean reveal(AccessibilityNodeInfo root,ScreenSnapshot screen,String goal) {
        if(root==null||screen==null||!Gov24LoginPrivacy.supportsCivilGoal(goal)
                ||!Gov24LoginPrivacy.PACKAGE.contentEquals(String.valueOf(root.getPackageName()))
                ||screen.activeWindowId!=null&&root.getWindowId()!=screen.activeWindowId)return false;
        try {
            long age=System.currentTimeMillis()-java.time.Instant.parse(screen.capturedAt).toEpochMilli();
            JSONObject safe=new Gov24LoginPrivacy(true).filter(screen);
            if(age<0||age>2000||!"public".equals(safe.optString("status"))||"signed_out".equals(safe.optString("auth_state")))return false;
            JSONArray aliases=POLICY.getJSONObject("civil_navigation").getJSONObject("services").getJSONObject(goal).getJSONArray("aliases");
            Scan scan=new Scan();
            try {
                visit(root,aliases,scan,0);
                scan.budget.check();
                if(scan.privateBoundary)return false;
                if(scan.matches==1&&scan.target!=null) {
                    android.graphics.Rect bounds=new android.graphics.Rect();scan.target.getBoundsInScreen(bounds);
                    boolean clipped=!scan.target.isVisibleToUser()||bounds.isEmpty()||bounds.left<0||bounds.top<0
                        ||bounds.right>screen.screenWidth||bounds.bottom>screen.screenHeight;
                    if(clipped&&scan.target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()))return true;
                }
                return scan.scroll!=null&&scan.scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }finally{scan.close();}
        }catch(Exception unavailable){return false;}
    }
    private static void visit(AccessibilityNodeInfo node,JSONArray aliases,Scan scan,int depth) {
        scan.budget.take(depth);scan.visited++;
        if(node.isEditable()||node.isPassword()||String.valueOf(node.getClassName()).contains("EditText")) {
            if(node.isVisibleToUser()) {
                CharSequence hint=node.getHintText();
                if(node.isPassword()||hint==null||!Gov24LoginPrivacy.isPublicSearchHint(hint.toString())||++scan.inputs>1)scan.privateBoundary=true;
            }
            return; // Never read an editable value, even during a second native check.
        }
        CharSequence pkg=node.getPackageName();
        if(pkg!=null&&!Gov24LoginPrivacy.PACKAGE.contentEquals(pkg))return;
        if(scan.scroll==null&&node.isVisibleToUser()&&node.isEnabled()
                &&(node.isScrollable()||String.valueOf(node.getClassName()).contains("WebView")))scan.scroll=AccessibilityNodeInfo.obtain(node);
        boolean match=false;
        for(CharSequence text:new CharSequence[]{node.getText(),node.getContentDescription()}) {
            if(text==null)continue;
            if(node.isVisibleToUser())for(String group:new String[]{"handoff_labels","consent_labels"}) {
                JSONArray labels=POLICY.optJSONArray(group);
                for(int i=0;labels!=null&&i<labels.length();i++)if(Gov24LoginPrivacy.normalize(text.toString()).equals(Gov24LoginPrivacy.normalize(labels.optString(i))))scan.privateBoundary=true;
            }
            for(int i=0;i<aliases.length();i++)match|=Gov24LoginPrivacy.normalize(text.toString()).equals(Gov24LoginPrivacy.normalize(aliases.optString(i)));
        }
        if(match&&node.isEnabled()) {
            scan.matches++;
            if(scan.target==null)scan.target=AccessibilityNodeInfo.obtain(node);
        }
        for(int i=0;i<node.getChildCount();i++) {
            scan.budget.check();
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{visit(child,aliases,scan,depth+1);}finally{child.recycle();}
        }
    }
}
