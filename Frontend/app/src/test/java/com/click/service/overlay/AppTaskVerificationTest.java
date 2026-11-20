package com.click.service.overlay;

import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;
import static com.click.service.overlay.AppTaskVerifier.Verdict.*;

public class AppTaskVerificationTest {
    private static final String PKG="org.example.app";
    @Test public void proposalAndUnchangedScreenNeverCountAsSuccess()throws Exception {
        JSONObject before=observation(PKG,"검색"),after=observation(PKG,"목록"),a=action(before,"tap","screen_changed");
        assertEquals(WAITING,AppTaskVerifier.check(before,after,a,false,false));
        assertEquals(WAITING,AppTaskVerifier.check(before,before,a,true,false));
        assertEquals(VERIFIED,AppTaskVerifier.check(before,after,a,true,false));
    }
    @Test public void expectedControlMustActuallyAppear()throws Exception {
        JSONObject before=observation(PKG,"검색"),a=action(before,"tap","target_visible");a.getJSONObject("expectation").put("label","목록");
        assertEquals(WAITING,AppTaskVerifier.check(before,observation(PKG,"홈"),a,true,false));
        assertEquals(VERIFIED,AppTaskVerifier.check(before,observation(PKG,"목록"),a,true,false));
        a.getJSONObject("expectation").put("label","검색");JSONObject changed=new JSONObject(before.toString());changed.getJSONArray("nodes").getJSONObject(0).put("selected",true);AppTaskObservation.finish(changed);
        assertEquals(WAITING,AppTaskVerifier.check(before,changed,a,true,false));
    }
    @Test public void inputRequiresLocalExactValueCheck()throws Exception {
        JSONObject before=AppTaskObservation.capture(screen(PKG,node(PKG,"node_0_1","","search"))),after=AppTaskObservation.capture(screen(PKG,node(PKG,"node_0_1","PRIVATE_QUERY","search")));
        JSONObject a=action(before,"set_text","input_present");
        assertEquals(WAITING,AppTaskVerifier.check(before,after,a,true,false));assertEquals(VERIFIED,AppTaskVerifier.check(before,after,a,true,true));
        assertEquals(WAITING,AppTaskVerifier.check(before,before,a,true,true));
        after.getJSONArray("nodes").getJSONObject(0).put("bounds",new JSONArray(new int[]{20,30,410,130}));assertEquals(WAITING,AppTaskVerifier.check(before,after,a,true,true));
    }
    @Test public void selectedStateRequiresTargetChange()throws Exception {
        JSONObject before=AppTaskObservation.capture(screen(PKG,node(PKG,"node_0_1","정렬","toggle",false))),after=AppTaskObservation.capture(screen(PKG,node(PKG,"node_0_1","정렬","toggle",true)));
        JSONObject a=action(before,"tap","selection_changed");
        assertEquals(WAITING,AppTaskVerifier.check(before,before,a,true,false));assertEquals(VERIFIED,AppTaskVerifier.check(before,after,a,true,false));
    }
    @Test public void appSwitchIsStaleExceptExplicitOpeningOrBack()throws Exception {
        JSONObject before=observation(PKG,"검색"),after=observation("org.other.app","목록"),a=action(before,"tap","screen_changed");
        assertEquals(STALE,AppTaskVerifier.check(before,after,a,true,false));
        a.put("tool","open_app").put("package","org.other.app").put("expectation",new JSONObject().put("kind","package_opened").put("package","org.other.app"));
        assertEquals(VERIFIED,AppTaskVerifier.check(before,after,a,true,false));
        a.put("expectation",new JSONObject().put("kind","package_opened").put("package",PKG));assertEquals(WAITING,AppTaskVerifier.check(before,after,a,true,false));
    }
    @Test public void protectedScreenPausesAndBackCanVerifyKeyboardDismissal()throws Exception {
        JSONObject before=observation(PKG,"검색"),a=action(before,"tap","screen_changed"),after=AppTaskObservation.capture(screen(PKG,node(PKG,"p","PRIVATE","password")));
        assertEquals(PROTECTED,AppTaskVerifier.check(before,after,a,true,false));
        after=observation(PKG,"검색");before.put("keyboard_visible",true);a.put("tool","back");assertEquals(VERIFIED,AppTaskVerifier.check(before,after,a,true,false));
    }
    @Test public void staleBoundsAndRiskyTargetsCannotExecute()throws Exception {
        JSONObject before=observation(PKG,"검색"),a=action(before,"tap","screen_changed");assertTrue(AppTaskPolicy.allowed(before,before,a,null));
        assertFalse(AppTaskPolicy.allowed(before,observation(PKG,"다른 화면"),a,null));
        a.put("bounds",new JSONArray(new int[]{1,2,3,4}));assertFalse(AppTaskPolicy.allowed(before,before,a,null));
        for(String label:new String[]{"결제하기","보내기","동의","허용","삭제"}){JSONObject risky=observation(PKG,label);assertFalse(AppTaskPolicy.allowed(risky,risky,action(risky,"tap","screen_changed"),null));assertFalse(AppTaskPolicy.manualTapAllowed(risky,action(risky,"tap","screen_changed")));}
    }
    @Test public void ocrIsManualOnlyAndAppOpenRequiresSelection()throws Exception {
        JSONObject before=observation(PKG,"검색"),a=action(before,"tap","screen_changed");before.getJSONArray("nodes").getJSONObject(0).put("source","ocr");AppTaskObservation.finish(before);
        assertFalse(AppTaskPolicy.allowed(before,before,a,null));assertTrue(AppTaskPolicy.manualTapAllowed(before,a));
        a.put("tool","open_app").put("package","org.selected.app").put("expectation",new JSONObject().put("kind","package_opened").put("package","org.selected.app"));assertFalse(AppTaskPolicy.allowed(before,before,a,null));assertFalse(AppTaskPolicy.allowed(before,before,a,PKG));assertTrue(AppTaskPolicy.allowed(before,before,a,"org.selected.app"));
        assertTrue(AppTaskPolicy.allowed(before,observation("org.other.app","다른 화면"),a,"org.selected.app"));
    }
}
