package com.click.data.snapshot;

import java.util.Arrays;

/** Observed Coupang load errors. Guides the actual retry control without performing a tap. */
public final class CoupangScreenGuide {
    public enum Kind { NETWORK_ERROR, LOAD_ERROR }
    public final Kind kind;
    public final AccessibilityNodeSnapshot target;
    public final String message;

    private CoupangScreenGuide(Kind kind, AccessibilityNodeSnapshot target, String message) {
        this.kind=kind; this.target=target; this.message=message;
    }

    public static CoupangScreenGuide identify(ScreenSnapshot screen) {
        if(screen==null || screen.nodes.isEmpty())return null;
        if(!"com.coupang.mobile".equals(screen.packageName))return null;
        boolean error=false,network=false;
        AccessibilityNodeSnapshot retry=null;
        for(var node:screen.nodes) {
            if(!eligible(screen,node))continue;
            String resource=resource(node.resourceId);
            if("home_load_error_view".equals(resource))error=true;
            if("error_view_title".equals(resource) && "네트워크 연결이 원활하지 않습니다".equals(node.text))network=true;
            if("error_view_first_button".equals(resource) && node.clickable) {
                if(retry!=null && !sameTarget(retry,node))return null;
                retry=node;
            }
        }
        return error||network?new CoupangScreenGuide(network?Kind.NETWORK_ERROR:Kind.LOAD_ERROR,retry,
                network?"쿠팡에 ‘네트워크 연결이 원활하지 않습니다’가 떠 있어요. 연결 상태를 확인하고 쿠팡의 ‘다시 시도하기’를 눌러주세요."
                    :"쿠팡이 화면을 불러오지 못했어요. 쿠팡의 ‘다시 시도하기’를 눌러주세요."):null;
    }

    public boolean sameStep(CoupangScreenGuide other) {
        return other!=null && kind==other.kind && (target==null ? other.target==null
                : other.target!=null && sameTarget(target,other.target));
    }
    private static boolean sameTarget(AccessibilityNodeSnapshot a, AccessibilityNodeSnapshot b) {
        return a.nodeId.equals(b.nodeId) && a.windowId==b.windowId && Arrays.equals(a.bounds,b.bounds);
    }
    private static boolean eligible(ScreenSnapshot screen, AccessibilityNodeSnapshot node) {
        int[] b=node.bounds;
        return node.visibleToUser && node.enabled && !node.sensitiveInput
                && screen.packageName.equals(node.packageName)
                && (screen.activeWindowId==null || screen.activeWindowId==node.windowId)
                && b!=null && b.length==4 && b[0]>=0 && b[1]>=0 && b[2]<=screen.screenWidth && b[3]<=screen.screenHeight
                && b[0]<b[2] && b[1]<b[3];
    }
    private static String resource(String value) {return value==null?"":value.substring(value.lastIndexOf('/')+1);}
}
