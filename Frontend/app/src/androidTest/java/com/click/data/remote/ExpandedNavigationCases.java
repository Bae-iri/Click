package com.click.data.remote;

import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Independent destinations and read-only navigation allowed during the expanded Galaxy Note20 audit. */
final class ExpandedNavigationCases {
    private enum Proof { SELECTED, EDITABLE, FOCUSED_EDITABLE, ID, TEXT }
    private static final String GALLERY="com.sec.android.gallery3d", FILES="com.sec.android.app.myfiles",
            PHONE="com.samsung.android.dialer", CONTACTS="com.samsung.android.app.contacts",
            CALENDAR="com.samsung.android.calendar", PLAY="com.android.vending", CHROME="com.android.chrome",
            SETTINGS="com.android.settings", CLOCK="com.sec.android.app.clockpackage", YOUTUBE="com.google.android.youtube";

    record Case(String id, String pkg, String app, String goal, Proof proof, String value, String resource,
                String startTab, List<String> path) {
        String[] row() { return new String[]{id,pkg,goal,value,"expanded"}; }
        String destinationPackage() { return id.equals("system_search") ? "com.android.settings.intelligence" : pkg; }
        boolean destination(AccessibilityNodeInfo node) {
            if (!node.isVisibleToUser()) return false;
            String id=resource.contains(":id/")?resource:destinationPackage()+":id/"+resource;
            if (!resource.isEmpty() && !id.equals(node.getViewIdResourceName())) return false;
            return switch (proof) {
                case ID -> true;
                case TEXT -> label(node,value) && !node.isClickable();
                case SELECTED -> label(node,value) && selected(node);
                case EDITABLE, FOCUSED_EDITABLE -> node.isEditable() && (proof!=Proof.FOCUSED_EDITABLE || node.isFocused());
            };
        }
        boolean allowsTap(String label,String resourceId) {
            return path.stream().anyMatch(step -> step.startsWith("#")
                    ? (pkg+":id/"+step.substring(1)).equals(resourceId)
                    : step.equals(label) || label.startsWith(step+",") || label.startsWith(step+" 탭,"));
        }

        JSONObject screenEvidence(ScreenSnapshot screen) throws JSONException {
            if(screen==null)return new JSONObject().put("status","no_frame");
            Set<String> labels=new HashSet<>(path);
            if(!value.isEmpty())labels.add(value);
            labels.add("검색어 입력");
            JSONObject projected=AppTaskObservation.capture(screen,labels);
            JSONArray nativeFacts=new JSONArray(), projectedFacts=new JSONArray();
            String proofId=resource.contains(":id/")?resource:destinationPackage()+":id/"+resource;
            for(var n:screen.nodes){
                if(!n.visibleToUser||!screen.packageName.equals(n.packageName)||screen.activeWindowId==null||n.windowId!=screen.activeWindowId)continue;
                boolean named=!value.isEmpty()&&(value.equals(n.text)||value.equals(n.contentDescription));
                boolean expectedId=!resource.isEmpty()&&proofId.equals(n.resourceId);
                boolean entry=allowsTap(n.sensitiveInput?"":String.valueOf(n.contentDescription!=null?n.contentDescription:n.text),n.resourceId);
                if(named||entry||expectedId){
                    nativeFacts.put(new JSONObject().put("named",named).put("entry",entry).put("expected_id",expectedId)
                            .put("selected",n.selected).put("clickable",n.clickable).put("search_input",AppTaskObservation.searchField(n)));
                }
            }
            JSONArray nodes=projected.getJSONArray("nodes");
            for(int i=0;i<nodes.length();i++){
                JSONObject n=nodes.getJSONObject(i);
                boolean named=!value.isEmpty()&&value.equals(n.optString("label"));
                boolean expectedId=!resource.isEmpty()&&proofId.equals(n.optString("resource"));
                boolean entry=allowsTap(n.optString("label"),n.optString("resource"));
                if(named||entry||expectedId||n.optBoolean("editable")){
                    projectedFacts.put(new JSONObject().put("named",named).put("entry",entry).put("expected_id",expectedId)
                            .put("role",n.optString("role")).put("selected",n.optBoolean("selected")).put("editable",n.optBoolean("editable")));
                }
            }
            return new JSONObject().put("status",projected.optString("status")).put("native",nativeFacts).put("projected",projectedFacts);
        }
    }

    static final List<Case> ALL=List.of(
        new Case("phone_recents",PHONE,"전화","전화에서 최근기록 탭으로 이동해줘",Proof.SELECTED,"최근기록","","키패드",List.of("최근기록")),
        new Case("gallery_albums",GALLERY,"갤러리","갤러리에서 앨범 탭으로 이동해줘",Proof.SELECTED,"앨범","","사진",List.of("앨범")),
        new Case("gallery_search",GALLERY,"갤러리","갤러리 검색창을 열어줘",Proof.EDITABLE,"","search_src_text","사진",List.of("검색")),
        new Case("calendar_search",CALENDAR,"캘린더","캘린더 검색창을 열어줘",Proof.EDITABLE,"","search_src_text","",List.of("검색")),
        new Case("contacts_search",CONTACTS,"연락처","연락처 검색창을 열어줘",Proof.EDITABLE,"","search_src_text","",List.of("검색")),
        new Case("files_search",FILES,"내 파일","내 파일 검색창을 열어줘",Proof.EDITABLE,"","search_src_text","",List.of("검색")),
        new Case("files_downloads",FILES,"내 파일","다운로드 화면을 열어줘",Proof.TEXT,"다운로드","path","",List.of("다운로드")),
        new Case("files_settings",FILES,"내 파일","설정 화면을 열어줘",Proof.TEXT,"내 파일 설정","","",List.of("옵션 더보기","설정")),
        new Case("play_search_tab",PLAY,"Play 스토어","검색 탭으로 이동해줘",Proof.SELECTED,"검색","","게임",List.of("검색")),
        new Case("system_search",SETTINGS,"설정","설정 검색창을 열어줘",Proof.EDITABLE,"","search_src_text","",List.of("설정 검색")),
        new Case("chrome_search",CHROME,"Chrome","Chrome 검색창을 열어줘",Proof.FOCUSED_EDITABLE,"","url_bar","",List.of("#search_box_text")),
        new Case("chrome_menu",CHROME,"Chrome","Chrome 메뉴를 열어줘",Proof.ID,"","app_menu_list","",List.of("Chrome 맞춤설정 및 제어")),
        new Case("chrome_settings",CHROME,"Chrome","설정 화면을 열어줘",Proof.TEXT,"검색엔진","android:id/title","",List.of("Chrome 맞춤설정 및 제어","설정")),
        new Case("control_timer",CLOCK,"시계","시계에서 타이머 탭으로 이동해줘",Proof.SELECTED,"타이머","","알람",List.of("타이머")),
        new Case("control_youtube_search",YOUTUBE,"YouTube","YouTube 검색창을 열어줘",Proof.EDITABLE,"","search_edit_text","",List.of("YouTube 검색","검색"))
    );

    static Case find(String[] row) {
        if (!"expanded".equals(row[4])) return null;
        return ALL.stream().filter(c -> c.id.equals(row[0])).findFirst().orElseThrow();
    }

    static boolean onScreen(UiAutomation ui,String pkg,Predicate<AccessibilityNodeInfo> match) {
        for (AccessibilityWindowInfo window:ui.getWindows()) {
            if (window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root=window.getRoot();
            if (root==null) continue;
            try { return pkg.contentEquals(root.getPackageName()) && visit(root,match); }
            finally { root.recycle(); }
        }
        return false;
    }

    static boolean open(UiAutomation ui,Case c,String step) {
        return onScreen(ui,c.pkg,n -> {
            boolean matches=step.startsWith("#") ? (c.pkg+":id/"+step.substring(1)).equals(n.getViewIdResourceName()) : label(n,step);
            if (!n.isVisibleToUser() || !matches) return false;
            AccessibilityNodeInfo target=AccessibilityNodeInfo.obtain(n);
            try {
                while (target!=null && !target.isClickable()) {
                    AccessibilityNodeInfo parent=target.getParent(); target.recycle(); target=parent;
                }
                return target!=null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } finally { if (target!=null) target.recycle(); }
        });
    }

    static boolean reached(UiAutomation ui,Case c) { return onScreen(ui,c.destinationPackage(),c::destination); }
    static boolean atStart(UiAutomation ui,Case c) {
        return c.startTab.isEmpty() || onScreen(ui,c.pkg,n -> n.isVisibleToUser() && label(n,c.startTab) && selected(n));
    }

    static boolean calibrate(UiAutomation ui,Case c) {
        for (String step:c.path) {
            if (reached(ui,c)) break;
            open(ui,c,step); SystemClock.sleep(450);
        }
        long limit=SystemClock.elapsedRealtime()+2400;
        while (SystemClock.elapsedRealtime()<limit) {
            if (reached(ui,c)) { SystemClock.sleep(500); if (reached(ui,c)) return true; }
            SystemClock.sleep(120);
        }
        return false;
    }

    private static boolean label(AccessibilityNodeInfo node,String label) {
        String desc=String.valueOf(node.getContentDescription());
        return label.equals(String.valueOf(node.getText())) || label.equals(desc) || desc.startsWith(label+",") || desc.startsWith(label+" 탭,");
    }

    private static boolean selected(AccessibilityNodeInfo node) {
        if (node.isSelected()) return true;
        AccessibilityNodeInfo parent=node.getParent();
        try { return parent!=null && parent.isSelected(); }
        finally { if (parent!=null) parent.recycle(); }
    }

    private static boolean visit(AccessibilityNodeInfo node,Predicate<AccessibilityNodeInfo> match) {
        if (match.test(node)) return true;
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if (child==null) continue;
            try { if (visit(child,match)) return true; } finally { child.recycle(); }
        }
        return false;
    }
}
