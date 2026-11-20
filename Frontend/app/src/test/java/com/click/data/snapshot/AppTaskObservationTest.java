package com.click.data.snapshot;

import org.json.*;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

public class AppTaskObservationTest {
    @Test public void sameProjectionWorksAcrossUnregisteredAppsAndRoles()throws Exception {
        for(String pkg:List.of("com.spotify.music","org.mozilla.firefox","com.google.android.youtube","org.example.unregistered")){
            JSONObject out=AppTaskObservation.capture(screen(pkg,node(pkg,"b","검색","button"),node(pkg,"s","","search"),node(pkg,"l","","list"),node(pkg,"t","홈","tab"),node(pkg,"v","정렬","toggle")));
            assertEquals("public",out.getString("status"));JSONArray nodes=out.getJSONArray("nodes");
            assertEquals(5,nodes.length());String[] roles={"button","text_field","list","tab","toggle"};
            for(int i=0;i<roles.length;i++)assertEquals(roles[i],nodes.getJSONObject(i).getString("role"));
        }
    }
    @Test public void inputContentIsBooleanAndNeverExportsText()throws Exception {
        String pkg="org.example.app",secret="PRIVATE_QUERY_검색어";
        JSONObject out=AppTaskObservation.capture(screen(pkg,node(pkg,"s",secret,"search")));
        assertEquals("public",out.getString("status"));assertFalse(out.toString().contains(secret));
        assertEquals("검색어 입력",out.getJSONArray("nodes").getJSONObject(0).getString("label"));
        assertTrue(out.getJSONArray("nodes").getJSONObject(0).getBoolean("has_value"));
    }
    @Test public void passwordBlocksWholeScreen()throws Exception {
        String pkg="org.example.app";JSONObject out=AppTaskObservation.capture(screen(pkg,node(pkg,"s","PRIVATE_PASSWORD","password"),node(pkg,"b","검색","button")));
        assertEquals("protected",out.getString("status"));assertEquals(0,out.getJSONArray("nodes").length());assertFalse(out.toString().contains("PRIVATE_PASSWORD"));
    }
    @Test public void timestampsAndKeyboardDoNotCreateFakeScreenTransitions()throws Exception {
        String pkg="org.example.app";ScreenSnapshot first=screen(pkg,node(pkg,"b","검색","button"));
        ScreenSnapshot second=screen(pkg,node(pkg,"b","검색","button")).withWindowContext(new NativeWindowContext("UNKNOWN","APPLICATION",true));
        JSONObject a=AppTaskObservation.capture(first),b=AppTaskObservation.capture(second);
        assertNotEquals(a.getString("observation_id"),b.getString("observation_id"));assertEquals(a.getString("fingerprint"),b.getString("fingerprint"));assertTrue(b.getBoolean("keyboard_visible"));
    }
    @Test public void selectionChangesFingerprintButSamePublicControlDoesNot()throws Exception {
        String pkg="org.example.app";
        assertNotEquals(AppTaskObservation.capture(screen(pkg,node(pkg,"t","정렬","toggle",false))).getString("fingerprint"),AppTaskObservation.capture(screen(pkg,node(pkg,"t","정렬","toggle",true))).getString("fingerprint"));
        assertEquals(observation(pkg,"검색").getString("fingerprint"),observation(pkg,"검색").getString("fingerprint"));
    }
    @Test public void piiLabelsAndUnrelatedAppsAreNotExported()throws Exception {
        String pkg="org.example.app";JSONObject out=AppTaskObservation.capture(screen(pkg,node(pkg,"sender","홍길동","button"),node(pkg,"number","010-1234-5678","button"),node("other.app","foreign","PRIVATE","button"),node(pkg,"b","검색","button")));
        assertFalse(out.toString().contains("홍길동"));assertFalse(out.toString().contains("010-1234"));assertFalse(out.toString().contains("PRIVATE"));assertNotNull(AppTaskObservation.find(out,"b"));
    }
    @Test public void nestedPublicListContentChangesViewportWithoutExportingBodyText()throws Exception {
        String pkg="org.example.app";String[] fingerprints=new String[2];int i=0;
        for(String title:List.of("첫 번째 공개 항목","다음 공개 항목")){
            AccessibilityNodeSnapshot wrapper=new AccessibilityNodeSnapshot("wrapper","list",true,pkg,null,null,null,"android.widget.LinearLayout",null,new int[]{10,20,400,120},false,true,false,false,false,null,42);
            AccessibilityNodeSnapshot row=new AccessibilityNodeSnapshot("row","wrapper",true,pkg,title,null,null,"android.widget.TextView",null,new int[]{10,20,400,120},false,true,false,false,false,null,42);
            JSONObject out=AppTaskObservation.capture(screen(pkg,node(pkg,"list","목록","list"),wrapper,row));
            assertFalse(out.toString().contains(title));fingerprints[i++]=out.getString("fingerprint");
        }
        assertNotEquals(fingerprints[0],fingerprints[1]);
    }
    @Test public void onlyRequestedPublicResultTextIsAddedToTheObservation()throws Exception {
        String pkg="org.example.app";
        AccessibilityNodeSnapshot result=new AccessibilityNodeSnapshot("result",null,true,pkg,"다운로드 완료",null,"result_label","android.widget.TextView",null,new int[]{10,20,400,120},false,true,false,false,false,null,42);
        AccessibilityNodeSnapshot body=new AccessibilityNodeSnapshot("body",null,true,pkg,"UNRELATED_PRIVATE_BODY",null,"body","android.widget.TextView",null,new int[]{10,200,400,320},false,true,false,false,false,null,42);
        ScreenSnapshot screen=screen(pkg,result,body);
        assertNull(AppTaskObservation.find(AppTaskObservation.capture(screen),"result"));
        JSONObject observed=AppTaskObservation.capture(screen,java.util.Set.of("다운로드 완료"));
        assertNotNull(AppTaskObservation.find(observed,"result"));assertFalse(observed.toString().contains("UNRELATED_PRIVATE_BODY"));
        assertEquals("text",AppTaskObservation.find(observed,"result").getString("role"));
    }
    @Test public void nestedLabelDoesNotMakeOneResultAmbiguousButSeparateRowsStillDo()throws Exception {
        String pkg="org.example.app";
        AccessibilityNodeSnapshot child=new AccessibilityNodeSnapshot("label","row",true,pkg,"목록",null,"label_resource","android.widget.TextView",null,new int[]{20,30,300,100},false,true,false,false,false,null,42);
        JSONObject result=com.click.service.overlay.AppTaskCompletionTest.completion(pkg,"목록");
        JSONObject one=AppTaskObservation.capture(screen(pkg,node(pkg,"row","목록","button"),child),java.util.Set.of("목록"));
        assertEquals(1,one.getJSONArray("nodes").length());assertEquals(com.click.service.overlay.AppTaskCompletion.Verdict.MATCHED,com.click.service.overlay.AppTaskCompletion.check(result,one));
        JSONObject two=AppTaskObservation.capture(screen(pkg,node(pkg,"row","목록","button"),child,node(pkg,"other","목록","button")),java.util.Set.of("목록"));
        assertEquals(com.click.service.overlay.AppTaskCompletion.Verdict.UNKNOWN,com.click.service.overlay.AppTaskCompletion.check(result,two));
    }
}
