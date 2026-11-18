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
import com.click.HomeActivity;
import com.click.data.local.AuthTokenStore;
import com.click.service.overlay.ClickOverlayService;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in connected smoke test: uses on-device login and stops before authentication. */
@RunWith(AndroidJUnit4.class)
public class Gov24InstalledAppFlowTest {
    private UiAutomation automation;

    @Test public void installedClickOffersGovernment24LoginMethods() throws Exception {
        org.junit.Assume.assumeTrue("Explicit live-device smoke test only",
            "true".equals(InstrumentationRegistry.getArguments().getString("gov24Live")));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        assertEquals("com.clueclick.app",BuildConfig.APPLICATION_ID);
        assertTrue("Sign in to Click on the phone before this smoke test",new AuthTokenStore(context).hasRefreshToken());
        HttpResult version=AuthGateway.get("/version",null);
        assertEquals(200,version.status);
        assertTrue("Live server has not enabled Government24",new JSONObject(version.body)
            .getJSONObject("public_guidance").getBoolean("gov24_login_enabled"));
        assertTrue(AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json",null).body)).gov24Login);
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        instrumentation.startActivitySync(new Intent(context,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->context.startService(new Intent(context,ClickOverlayService.class)
            .putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL,"정부24 로그인 도와줘")));
        awaitText(BuildConfig.APPLICATION_ID,"안내 시작하기",60000);
        clickText(BuildConfig.APPLICATION_ID,"안내 시작하기");
        awaitText(BuildConfig.APPLICATION_ID,"정부24 열기",45000);
        clickText(BuildConfig.APPLICATION_ID,"정부24 열기");
        long deadline=SystemClock.elapsedRealtime()+45000;
        while(SystemClock.elapsedRealtime()<deadline && !hasText(BuildConfig.APPLICATION_ID,"1. 모바일 신분증")) {
            if(!hasText("kr.go.minwon.m","간편인증")) clickIfClickable("kr.go.minwon.m","로그인");
            SystemClock.sleep(500);
        }
        awaitText(BuildConfig.APPLICATION_ID,"1. 모바일 신분증",1000);
        assertTrue(hasText(BuildConfig.APPLICATION_ID,"2. 간편인증"));
        assertTrue(hasText(BuildConfig.APPLICATION_ID,"3. 공동인증서"));
        assertTrue(hasText(BuildConfig.APPLICATION_ID,"4. 금융인증서"));
        assertFalse(hasText(BuildConfig.APPLICATION_ID,"아직 지원하지 않아요"));
        Bundle evidence=new Bundle();
        evidence.putString("gov24_live_flow","real_app_to_live_server_to_four_login_choices; no authentication");
        instrumentation.sendStatus(0,evidence);
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
        if(text.equals(node.getText()==null?null:node.getText().toString()) && node.isVisibleToUser())return AccessibilityNodeInfo.obtain(node);
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try {AccessibilityNodeInfo found=findInTree(child,text);if(found!=null)return found;}
            finally{child.recycle();}
        }
        return null;
    }
}
