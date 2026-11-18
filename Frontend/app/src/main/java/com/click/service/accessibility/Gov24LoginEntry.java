package com.click.service.accessibility;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.Gov24LoginPrivacy;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local public-home navigation. Editable values are never read or exported. */
public final class Gov24LoginEntry {
    private static final JSONObject POLICY;
    static {try {POLICY=new JSONObject(com.click.generated.Gov24LoginPolicyData.json());}
        catch(Exception error){throw new ExceptionInInitializerError(error);}}
    public static final class Target {
        public final String nodeId;public final int windowId;public final int[] bounds;public final boolean visible;
        Target(String id,int window,Rect rect,boolean shown){nodeId=id;windowId=window;
            bounds=new int[]{rect.left,rect.top,rect.right,rect.bottom};visible=shown&&!rect.isEmpty();}
    }
    private static final class Scan {boolean brand,services,privateBoundary,choices;int inputs,logins;Target target;}
    public static Target inspect(AccessibilityNodeInfo root) {
        if(root==null || !Gov24LoginPrivacy.PACKAGE.contentEquals(String.valueOf(root.getPackageName())))return null;
        Scan scan=new Scan();visit(root,"node_0",scan);
        return scan.brand&&scan.services&&!scan.privateBoundary&&!scan.choices&&scan.inputs<=1&&scan.logins==1?scan.target:null;
    }
    private static void visit(AccessibilityNodeInfo node,String path,Scan scan) {
        CharSequence pkg=node.getPackageName();
        if(pkg!=null&&!Gov24LoginPrivacy.PACKAGE.contentEquals(pkg)){scan.privateBoundary=true;return;}
        String type=String.valueOf(node.getClassName());
        boolean input=node.isEditable()||type.contains("EditText")||node.isPassword();
        if(input){scan.inputs++;if(node.isPassword())scan.privateBoundary=true;}
        else {
            for(CharSequence value:new CharSequence[]{node.getText(),node.getContentDescription()}) {
                if(value==null)continue;String label=value.toString(),normalized=Gov24LoginPrivacy.normalize(label);
                if("정부24".equals(normalized))scan.brand=true;
                if("자주찾는서비스".equals(normalized))scan.services=true;
                if(node.isVisibleToUser()&&(exact(POLICY.optJSONArray("consent_labels"),label)
                        ||exact(POLICY.optJSONArray("handoff_labels"),label)))scan.privateBoundary=true;
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                String option=Gov24LoginPrivacy.option(label);
                if(node.isVisibleToUser()&&!bounds.isEmpty()&&node.isClickable()&&option!=null
                        &&(option.startsWith("method_")||option.startsWith("provider_")))scan.choices=true;
                if(node.isClickable()&&node.isEnabled()&&("로그인".equals(normalized)||"로그인하기".equals(normalized))) {
                    if(scan.target==null||!path.equals(scan.target.nodeId)) {
                        scan.logins++;scan.target=new Target(path,node.getWindowId(),bounds,node.isVisibleToUser());
                    }
                }
            }
        }
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{visit(child,path+"_"+i,scan);}finally{child.recycle();}
        }
    }
    private static boolean exact(JSONArray aliases,String label) {
        for(int i=0;aliases!=null&&i<aliases.length();i++)
            if(Gov24LoginPrivacy.normalize(aliases.optString(i)).equals(Gov24LoginPrivacy.normalize(label)))return true;
        return false;
    }
    static boolean reveal(AccessibilityNodeInfo root) {
        Target target=inspect(root);if(target==null)return false;
        AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain(root);
        try {
            String[] path=target.nodeId.split("_");
            for(int i=2;i<path.length;i++) {
                AccessibilityNodeInfo child=node.getChild(Integer.parseInt(path[i]));node.recycle();node=child;
                if(node==null)return false;
            }
            if(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()))return true;
            for(int i=0;i<12;i++) {
                AccessibilityNodeInfo parent=node.getParent();if(parent==null)break;
                node.recycle();node=parent;
                if((node.isScrollable()||String.valueOf(node.getClassName()).contains("WebView"))&&node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;
            }
            return false;
        }finally{if(node!=null)node.recycle();}
    }
}
