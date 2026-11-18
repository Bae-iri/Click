package com.click.data.remote;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.BuildConfig;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in connected smoke test: uses on-device login and stops before authentication. */
@RunWith(AndroidJUnit4.class)
public class Gov24InstalledAppFlowTest {
    private UiAutomation automation;
    private String targetPackage;

    @Test public void installedClickOffersGovernment24LoginMethods() throws Exception {
        org.junit.Assume.assumeTrue("Explicit live-device smoke test only",
            "true".equals(InstrumentationRegistry.getArguments().getString("gov24Live")));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        targetPackage=InstrumentationRegistry.getArguments().getString("targetPackage","com.clueclick.app");
        assertTrue(java.util.Set.of("com.clueclick.app","com.click.gov24pilot").contains(targetPackage));
        assertNotEquals("Run this test from the other Click package so the target accessibility service stays alive",
            BuildConfig.APPLICATION_ID,targetPackage);
        HttpResult version=AuthGateway.get("/version",null);
        assertEquals(200,version.status);
        assertTrue("Live server has not enabled Government24",new JSONObject(version.body)
            .getJSONObject("public_guidance").getBoolean("gov24_login_enabled"));
        assertTrue(AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json",null).body)).gov24Login);
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            |android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        context.startActivity(new Intent().setComponent(new android.content.ComponentName(targetPackage,"com.click.HomeActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitText(targetPackage,targetPackage+":id/home_request_input",15000);
        AccessibilityNodeInfo input=find(targetPackage,targetPackage+":id/home_request_input");
        try {
            Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"정부24 로그인 도와줘");
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text));
        }finally{input.recycle();}
        clickText(targetPackage,"요청 보내기");
        long deadline=SystemClock.elapsedRealtime()+60000;
        boolean confirmed=false;
        while(SystemClock.elapsedRealtime()<deadline && !hasText(targetPackage,"1. 모바일 신분증")
                && !hasText(targetPackage,"__provider_question__")) {
            if(!confirmed&&hasText(targetPackage,"안내 시작하기")) {
                clickText(targetPackage,"안내 시작하기");confirmed=true;
            }
            if(hasText(targetPackage,"로그인을 눌러주세요.")&&hasText("kr.go.minwon.m","로그인"))
                tapText("kr.go.minwon.m","로그인");
            assertFalse("Public home must not be handed off as authentication",hasText(targetPackage,"직접 인증해 주세요."));
            assertFalse(hasText(targetPackage,"현재 화면 확인"));
            assertFalse(hasText(targetPackage,"정부24 열기"));
            assertFalse(hasText(targetPackage,"직접 진행하고 안내 끝내기"));
            SystemClock.sleep(300);
        }
        boolean providers=hasText(targetPackage,"__provider_question__");
        if(!providers) {
            awaitText(targetPackage,"1. 모바일 신분증",1000);
            assertTrue(hasText(targetPackage,"2. 간편인증"));
            assertTrue(hasText(targetPackage,"3. 공동인증서"));
            assertTrue(hasText(targetPackage,"4. 금융인증서"));
        }
        assertFalse(hasText(targetPackage,"아직 지원하지 않아요"));
        assertFalse(hasText(targetPackage,"로그인 방법을 고르고, 실제 인증은 직접 진행해요."));
        Bundle evidence=new Bundle();
        evidence.putString("gov24_live_flow",providers?"real_app_to_live_server_to_provider_question; no authentication":
            "real_app_to_live_server_to_four_login_choices; no authentication");
        instrumentation.sendStatus(0,evidence);
    }

    @Test public void nativeCertificateSelectionReachesManualAuthenticationBoundary() throws Exception {
        org.junit.Assume.assumeTrue("Explicit continuation of the live login choice screen only",
            "true".equals(InstrumentationRegistry.getArguments().getString("gov24Continue")));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        targetPackage=InstrumentationRegistry.getArguments().getString("targetPackage","com.click.gov24pilot");
        assertTrue(java.util.Set.of("com.clueclick.app","com.click.gov24pilot").contains(targetPackage));
        assertNotEquals(BuildConfig.APPLICATION_ID,targetPackage);
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            |android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        boolean directNativeTransition=hasText(targetPackage,"2. 간편인증");
        if(directNativeTransition) {
            clickText(targetPackage,"줄이기");
            tapText("kr.go.minwon.m","간편인증");
        }
        awaitText(targetPackage,"원하는 민간 인증서를 선택해 주세요.",25000);
        assertFalse("Provider choices must stay in Government24",hasText(targetPackage,"__any_numbered_choice__"));
        assertFalse(hasText(targetPackage,"사용할 인증수단 이름 입력"));
        String kakao=hasText("kr.go.minwon.m","KAKAO(카카오)")?"KAKAO(카카오)":
            hasText("kr.go.minwon.m","카카오톡")?"카카오톡":"카카오";
        tapText("kr.go.minwon.m",kakao);
        awaitText(targetPackage,"직접 인증해 주세요.",20000);
        Bundle result=new Bundle();result.putString("gov24_live_native_flow",
            (directNativeTransition?"direct_native_simple_tap_to_provider_question":"existing_provider_question")
                +"_to_native_certificate_selection_to_handoff; no credential entry or authentication request");
        instrumentation.sendStatus(0,result);
    }

    @Test public void completedPublicGuideCanBeEnded() throws Exception {
        org.junit.Assume.assumeTrue("Explicitly finish the already completed public guide only",
            "true".equals(InstrumentationRegistry.getArguments().getString("gov24Finish")));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        targetPackage=InstrumentationRegistry.getArguments().getString("targetPackage","com.click.gov24pilot");
        assertTrue(java.util.Set.of("com.clueclick.app","com.click.gov24pilot").contains(targetPackage));
        assertNotEquals(BuildConfig.APPLICATION_ID,targetPackage);
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        awaitText(targetPackage,"직접 인증해 주세요.",5000);
        clickText(targetPackage,"끝내기");
        SystemClock.sleep(1000);
        assertFalse(hasText(targetPackage,"직접 인증해 주세요."));
    }

    private void revealText(String pkg,String text) {
        for(int i=0;i<5 && !hasText(pkg,text);i++) {
            AccessibilityNodeInfo scroll=find(pkg,"__scroll_forward__");
            if(scroll==null)break;
            try {scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);}finally{scroll.recycle();}
            SystemClock.sleep(250);
        }
        awaitText(pkg,text,1500);
    }

    private void tapText(String pkg,String text) {
        AccessibilityNodeInfo node=find(pkg,text);assertNotNull("Missing observed public choice: "+text,node);
        android.graphics.Rect bounds=new android.graphics.Rect();
        try {node.getBoundsInScreen(bounds);}finally{node.recycle();}
        assertFalse(bounds.isEmpty());
        long now=SystemClock.uptimeMillis();
        var down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0);
        var up=android.view.MotionEvent.obtain(now,now+80,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0);
        try {assertTrue(automation.injectInputEvent(down,true));assertTrue(automation.injectInputEvent(up,true));}
        finally{down.recycle();up.recycle();}
    }

    private void awaitText(String pkg,String text,long timeout) {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        do {
            if(hasText(pkg,text))return;
            SystemClock.sleep(250);
        }while(SystemClock.elapsedRealtime()<deadline);
        fail("Expected public UI did not appear: "+text);
    }

    private boolean hasText(String pkg,String text) {
        AccessibilityNodeInfo node=find(pkg,text);
        if(node==null)return false;
        node.recycle();return true;
    }

    private void clickText(String pkg,String text) {
        assertTrue("Missing clickable public button: "+text,clickIfClickable(pkg,text));
    }

    private boolean clickIfClickable(String pkg,String text) {
        AccessibilityNodeInfo node=find(pkg,text);
        if(node==null)return false;
        try {
            for(int n=0;n<4;n++) {
                if(node.isClickable())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                AccessibilityNodeInfo parent=node.getParent();if(parent==null)break;
                node.recycle();node=parent;
            }
            return false;
        }finally{node.recycle();}
    }

    private AccessibilityNodeInfo find(String pkg,String text) {
        for(AccessibilityWindowInfo window:automation.getWindows()) {
            AccessibilityNodeInfo root=window.getRoot();
            try {
                if(root!=null && pkg.equals(String.valueOf(root.getPackageName()))) {
                    AccessibilityNodeInfo found=findInTree(root,text);if(found!=null)return found;
                }
            }finally{if(root!=null)root.recycle();window.recycle();}
        }
        return null;
    }

    private AccessibilityNodeInfo findInTree(AccessibilityNodeInfo node,String text) {
        if(("__provider_question__".equals(text) && (String.valueOf(node.getText()).startsWith("어느 인증기관을 사용하실까요?")
                    || "원하는 민간 인증서를 선택해 주세요.".contentEquals(String.valueOf(node.getText())))
                || "__any_numbered_choice__".equals(text)&&String.valueOf(node.getText()).matches("[0-9]+[.] .+")
                || "__scroll_forward__".equals(text) && node.isScrollable()
                || text.equals(node.getText()==null?null:node.getText().toString())
                || text.equals(node.getContentDescription()==null?null:node.getContentDescription().toString())
                || text.equals(node.getViewIdResourceName())) && node.isVisibleToUser())return AccessibilityNodeInfo.obtain(node);
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try {AccessibilityNodeInfo found=findInTree(child,text);if(found!=null)return found;}
            finally{child.recycle();}
        }
        return null;
    }
}
