package com.click.ui.overlay;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;

/** Explains observable evidence, never invents a model's hidden reasoning or accuracy. */
public final class GuidanceExplanation {
    private GuidanceExplanation(){}
    public static final String CAPABILITIES="글씨·밝기·소리 같은 설정을 돕고, 다른 앱에서는 보이는 메뉴를 찾아 안내해요.";
    public static final String LIMITS="앱과 화면 구성에 따라 안내 위치가 틀릴 수 있어요. 맞지 않으면 다시 확인하거나 요청을 고쳐 주세요.";
    public static final String BOUNDARIES="비밀번호·인증·결제처럼 직접 확인할 단계에서는 멈춰요. 이미 바꾼 설정은 안내를 끝내도 되돌아가지 않아요.";
    public static final String DEFAULT="요청한 작업과 현재 화면을 맞춰 다음 단계를 확인해요. 전체 작업이 끝났는지는 별도로 확인해요.";
    public static String target(ScreenSnapshot screen,String id){
        if(screen!=null&&id!=null)for(AccessibilityNodeSnapshot node:screen.nodes) {
            if(!id.equals(node.nodeId)||!node.visibleToUser||node.sensitiveInput)continue;
            String label=node.text==null||node.text.isBlank()?node.contentDescription:node.text;
            if(label!=null&&!label.isBlank())return "안내를 만들 때 읽은 화면에서 ‘"+label.substring(0,Math.min(60,label.length()))+"’ 항목을 대상으로 사용했어요. 화면이 바뀌면 다시 확인해요.";
        }
        return "현재 화면에서 안내 대상을 확실히 확인하지 못했어요. 화면을 다시 읽거나 보이는 항목 이름으로 요청을 고쳐 주세요.";
    }
}
