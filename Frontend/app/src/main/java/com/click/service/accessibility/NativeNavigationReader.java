package com.click.service.accessibility;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.NavigationState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Select one native collection, not the first nested carousel that happens to move. */
final class NativeNavigationReader {
    private NativeNavigationReader() {}
    private static final Pattern CLOCK_LABEL = Pattern.compile("[0-9 :./%APMapm오전후]+");
    private static final Set<Integer> SCROLL_ACTIONS = Set.of(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(),
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId(), AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId(),
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId());
    private static boolean has(AccessibilityNodeInfo n,int action) {
        return n.getActionList().stream().anyMatch(a->a.getId()==action);
    }
    static boolean collection(AccessibilityNodeInfo n) {
        String type=String.valueOf(n.getClassName()).toLowerCase(Locale.ROOT);
        return n.isVisibleToUser() && n.isEnabled() && n.getRangeInfo()==null && !n.isCheckable()
                && !n.isEditable() && !type.contains("seekbar") && !type.contains("slider")
                && (n.isScrollable() || type.contains("recyclerview") || type.contains("listview")
                    || type.contains("viewpager") || type.contains("pagedview") || type.contains("scrollview"));
    }
    private static final class Candidate { String id;long score=-1; }
    static NavigationState read(AccessibilityNodeInfo root) {
        Candidate best=new Candidate();find(root,"node_0",best,com.click.service.overlay.NavigationRecovery.isHome(String.valueOf(root.getPackageName())));
        String id=best.id==null?"node_0":best.id;
        AccessibilityNodeInfo selected=nodeAt(root,id);boolean recycle=selected!=root;
        if(selected==null)return null;
        try {
            String type=String.valueOf(selected.getClassName()).toLowerCase(Locale.ROOT);
            AccessibilityNodeInfo.CollectionInfo info=selected.getCollectionInfo();
            boolean horizontal=has(selected,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId())
                    || has(selected,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId())
                    || type.contains("horizontal") || type.contains("viewpager") || type.contains("pagedview")
                    || info!=null && info.getRowCount()==1 && info.getColumnCount()>1;
            boolean vertical=has(selected,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId())
                    || has(selected,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId())
                    || type.contains("listview") || type.contains("scrollview") && !horizontal
                    || info!=null && info.getColumnCount()==1 && info.getRowCount()>1;
            NavigationState.Axis axis=horizontal?NavigationState.Axis.HORIZONTAL:vertical?NavigationState.Axis.VERTICAL:NavigationState.Axis.UNKNOWN;
            boolean back=has(selected,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    || has(selected,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId())
                    || has(selected,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId());
            boolean forward=has(selected,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    || has(selected,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId())
                    || has(selected,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId());
            int[] indices={Integer.MAX_VALUE,-1};
            int count=info==null?-1:axis==NavigationState.Axis.HORIZONTAL?info.getColumnCount():info.getRowCount();
            List<String> parts=new ArrayList<>();readContent(selected,axis,indices,parts,true,true,true);
            Collections.sort(parts);String digest;
            try {digest=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n",parts).getBytes(StandardCharsets.UTF_8)));}
            catch(Exception impossible){throw new IllegalStateException(impossible);}
            return new NavigationState(id,axis,back||forward?back:null,back||forward?forward:null,
                    indices[0]==Integer.MAX_VALUE?-1:indices[0],indices[1],count,digest);
        } finally {if(recycle)selected.recycle();}
    }
    private static void find(AccessibilityNodeInfo n,String id,Candidate best,boolean launcher) {
        if(!n.isVisibleToUser() || !n.isEnabled())return;
        // A launcher widget's list is not the app-page collection.
        if(launcher && String.valueOf(n.getClassName()).toLowerCase(Locale.ROOT).contains("appwidget"))return;
        if(collection(n)) {
            Rect b=new Rect();n.getBoundsInScreen(b);long score=(long)b.width()*b.height();
            if(n.isScrollable())score+=1_000_000_000_000L;
            if(n.getActionList().stream().anyMatch(action->SCROLL_ACTIONS.contains(action.getId())))score+=2_000_000_000_000L;
            // A range-backed control can never win over a navigation collection.
            if(score>best.score){best.id=id;best.score=score;}
        }
        for(int i=0;i<n.getChildCount();i++) {
            AccessibilityNodeInfo child=n.getChild(i);if(child==null)continue;
            try{find(child,id+"_"+i,best,launcher);}finally{child.recycle();}
        }
    }
    static AccessibilityNodeInfo nodeAt(AccessibilityNodeInfo root,String id) {
        AccessibilityNodeInfo n=root;String[] path=id.split("_");
        for(int i=2;i<path.length;i++) {
            AccessibilityNodeInfo child=n.getChild(Integer.parseInt(path[i]));
            if(n!=root)n.recycle();n=child;if(n==null)return null;
        }
        return n;
    }
    /** Read indices and page identity in one walk while retaining their separate privacy rules. */
    private static void readContent(AccessibilityNodeInfo n,NavigationState.Axis axis,int[] out,
                                    List<String> parts,boolean includeLabels,boolean includeIndices,boolean selectedRoot) {
        if(!n.isVisibleToUser())return;
        AccessibilityNodeInfo.CollectionItemInfo item=selectedRoot?null:n.getCollectionItemInfo();
        if(includeIndices && item!=null) {
            int index=axis==NavigationState.Axis.HORIZONTAL?item.getColumnIndex():item.getRowIndex();
            if(index>=0){out[0]=Math.min(out[0],index);out[1]=Math.max(out[1],index);}
        }
        includeLabels = includeLabels && !n.isPassword() && !n.isEditable() && n.getRangeInfo()==null;
        if(includeLabels) {
            String resource=String.valueOf(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            String type=String.valueOf(n.getClassName()).toLowerCase(Locale.ROOT);
            includeLabels = !resource.contains("widget") && !type.contains("appwidget") && !type.contains("clock") && !resource.endsWith("/summary");
            if(includeLabels) {
                CharSequence text=n.getText(),description=n.getContentDescription();
                String label=text!=null?text.toString():description!=null?description.toString():"";
                if(!label.isBlank() && !CLOCK_LABEL.matcher(label).matches()) {
                    AccessibilityNodeInfo.CollectionItemInfo labelItem=n.getCollectionItemInfo();
                    String index=labelItem==null?"":labelItem.getRowIndex()+":"+labelItem.getColumnIndex();
                    parts.add(resource+"|"+type+"|"+label+"|"+index);
                }
            }
        }
        int childCount=n.getChildCount();
        boolean childIndices=includeIndices && (selectedRoot || n.getCollectionInfo()==null);
        for(int i=0;i<childCount;i++) {
            AccessibilityNodeInfo child=n.getChild(i);if(child==null)continue;
            try{readContent(child,axis,out,parts,includeLabels,childIndices,false);}finally{child.recycle();}
        }
    }
}
