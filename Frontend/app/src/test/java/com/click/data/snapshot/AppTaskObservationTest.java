package com.click.data.snapshot;

import org.json.*;
import org.junit.Test;
import java.util.List;
import java.util.Set;
import com.click.service.overlay.AppTaskCompletion;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

public class AppTaskObservationTest {
    @Test public void selectedLayoutItemNeedsARealTabStripAndAnUnambiguousGroup()throws Exception {
        String pkg="org.example.tabs";
        for(String stripId:List.of("tabs","tab_contents")){
            for(boolean ambiguous:List.of(false,true)){
                var strip=layout(pkg,"strip",null,null,pkg+":id/"+stripId,false,false);
                var row=layout(pkg,"row","strip",null,null,false,false);
                var selected=layout(pkg,"a","row","최근 항목",null,false,true);
                var other=layout(pkg,"b","row","즐겨찾기",null,true,ambiguous);
                var nativeScreen=screen(pkg,strip,row,selected,other);
                JSONObject observed=AppTaskObservation.capture(nativeScreen,Set.of("최근 항목","즐겨찾기"));
                JSONObject proof=new JSONObject().put("checks",new JSONArray().put(new JSONObject()
                        .put("package",pkg).put("role","tab").put("label","최근 항목").put("selected",true)));
                assertEquals(stripId.equals("tabs")&&!ambiguous,
                        AppTaskCompletion.checkForGoal("최근 항목 열어줘",proof,observed,nativeScreen)==AppTaskCompletion.Verdict.MATCHED);
                proof.getJSONArray("checks").getJSONObject(0).put("label","즐겨찾기");
                assertNotEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal("즐겨찾기 열어줘",proof,observed,nativeScreen));
            }
        }
    }
    private static AccessibilityNodeSnapshot layout(String pkg,String id,String parent,String name,String resource,boolean clickable,boolean selected){
        return new AccessibilityNodeSnapshot(id,parent,true,pkg,null,name,resource,"android.widget.LinearLayout",
                "unknown",new int[]{10,100,200,160},clickable,true,selected,false,false,null,42);
    }
    @Test public void sameProjectionWorksAcrossUnregisteredAppsAndRoles()throws Exception {
        for(String pkg:List.of("com.spotify.music","org.mozilla.firefox","com.google.android.youtube","org.example.unregistered")){
            JSONObject out=AppTaskObservation.capture(screen(pkg,node(pkg,"b","검색","button"),node(pkg,"s","","search"),node(pkg,"l","","list"),node(pkg,"t","홈","tab"),node(pkg,"v","정렬","toggle")));
            assertEquals("public",out.getString("status"));JSONArray nodes=out.getJSONArray("nodes");
            assertEquals(5,nodes.length());String[] roles={"button","text_field","list","tab","toggle"};
            for(int i=0;i<roles.length;i++)assertEquals(roles[i],nodes.getJSONObject(i).getString("role"));
        }
    }
    private static AccessibilityNodeSnapshot raw(String pkg,String id,String parent,String text,String resource,String cls,int[] b,boolean clickable){
        return new AccessibilityNodeSnapshot(id,parent,true,pkg,text,null,resource,cls,null,b,clickable,true,false,false,false,null,42,false,null,null,false,null,false,false);
    }
    @Test public void appBarTitleIsExportedAsDestinationTextButBodyTextIsNot()throws Exception {
        String pkg="com.android.settings";
        JSONObject out=AppTaskObservation.capture(new ScreenSnapshot(pkg,java.time.Instant.now().toString(),1080,2400,List.of(
            raw(pkg,"node_0",null,null,pkg+":id/app_bar","android.widget.LinearLayout",new int[]{0,92,1080,317},false),
            raw(pkg,"node_0_0","node_0",null,pkg+":id/action_bar","android.view.ViewGroup",new int[]{0,92,1080,317},false),
            raw(pkg,"node_0_0_0","node_0_0","디스플레이",null,"android.widget.TextView",new int[]{158,179,468,274},false),
            raw(pkg,"node_1",null,"밝기 설명 본문",null,"android.widget.TextView",new int[]{40,400,1000,480},false),
            raw(pkg,"node_2",null,"화면 모드",pkg+":id/item","android.widget.Button",new int[]{0,500,1080,640},true)),42));
        JSONArray nodes=out.getJSONArray("nodes");JSONObject title=null;
        for(int i=0;i<nodes.length();i++)if("디스플레이".equals(nodes.getJSONObject(i).optString("label")))title=nodes.getJSONObject(i);
        assertNotNull(title);assertEquals("text",title.getString("role"));assertFalse(title.getBoolean("clickable"));
        assertFalse(out.toString().contains("밝기 설명 본문"));
        JSONObject completion=new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",pkg).put("label","디스플레이")
            .put("resource","").put("role","text").put("checked",JSONObject.NULL).put("selected",JSONObject.NULL)));
        assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal("디스플레이 설정 열어줘",completion,out,null));
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
    private static AccessibilityNodeSnapshot raw(String pkg,String id,String parent,String text,String resource,String cls,boolean clickable,boolean selected,boolean input,boolean password){
        return new AccessibilityNodeSnapshot(id,parent,true,pkg,text,null,resource,cls,null,new int[]{10,20,400,120},
            clickable,true,selected,false,false,null,42,input,null,null,password,null,false,false);
    }
    @Test public void timerNumberPickerDoesNotProtectTheTabStrip()throws Exception {
        // Samsung Clock opened on its Timer tab: three NumberPicker inputs plus the public tab strip.
        String pkg="com.sec.android.app.clockpackage";
        JSONObject out=AppTaskObservation.capture(screen(pkg,
            raw(pkg,"p",null,null,null,"android.widget.NumberPicker",false,false,false,false),
            raw(pkg,"h","p","00, 시간",pkg+":id/numberpicker_input","android.widget.EditText",true,false,true,false),
            raw(pkg,"t",null,"스톱워치",null,"android.widget.LinearLayout",true,false,false,false)));
        assertEquals("public",out.getString("status"));
        assertFalse("picker value is never exported",out.toString().contains("00, 시간"));
        JSONArray nodes=out.getJSONArray("nodes");
        assertEquals(1,nodes.length());assertEquals("스톱워치",nodes.getJSONObject(0).getString("label"));
        assertFalse(nodes.getJSONObject(0).getBoolean("editable"));
    }
    @Test public void pickerExceptionNeedsTheRealPickerParentAndNeverCoversPasswords()throws Exception {
        String pkg="org.example.app";
        // Same resource name but not inside a NumberPicker: still a protected input.
        JSONObject loose=AppTaskObservation.capture(screen(pkg,
            raw(pkg,"h",null,"PRIVATE_NAME",pkg+":id/numberpicker_input","android.widget.EditText",true,false,true,false),
            raw(pkg,"b",null,"다음",null,"android.widget.Button",true,false,false,false)));
        assertEquals("protected",loose.getString("status"));assertFalse(loose.toString().contains("PRIVATE_NAME"));
        JSONObject password=AppTaskObservation.capture(screen(pkg,
            raw(pkg,"p",null,null,null,"android.widget.NumberPicker",false,false,false,false),
            raw(pkg,"h","p","PRIVATE_PIN",pkg+":id/numberpicker_input","android.widget.EditText",true,false,true,true)));
        assertEquals("protected",password.getString("status"));
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

    @Test public void passiveScrollableTitleAncestorDoesNotEraseTheExactNativeHeadingProof()throws Exception {
        String pkg="org.example.unregistered",label="Display & touch";
        AccessibilityNodeSnapshot container=new AccessibilityNodeSnapshot("node_0_0",null,true,pkg,null,label,
            pkg+":id/content_parent","android.widget.FrameLayout",null,new int[]{0,0,1080,1200},
            false,true,false,true,false,null,42,false,null,null,false,null,false,false);
        AccessibilityNodeSnapshot heading=new AccessibilityNodeSnapshot("node_0_0_0","node_0_0",true,pkg,null,label,
            pkg+":id/page_title","android.widget.FrameLayout",null,new int[]{0,63,1080,210},
            false,true,false,false,false,null,42,false,null,null,false,null,true,false);
        ScreenSnapshot nativePage=screen(pkg,container,heading);
        JSONObject candidate=new JSONObject().put("checks",new JSONArray().put(new JSONObject()
            .put("package",pkg).put("label",label).put("resource",pkg+":id/page_title").put("role","text")));
        JSONObject plain=AppTaskObservation.capture(nativePage);
        assertEquals("list",AppTaskObservation.find(plain,container.nodeId).getString("role"));
        assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal("Display & touch 화면 열어줘",candidate,plain,nativePage));

        JSONObject projected=AppTaskObservation.capture(nativePage,AppTaskCompletion.labels(candidate));
        assertNotNull("A passive list borrowing its title label cannot own and erase the native heading",AppTaskObservation.find(projected,heading.nodeId));
        assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal("Display & touch 화면 열어줘",candidate,projected,nativePage));
        assertTrue(AppTaskCompletion.distinctProofTargets(candidate,projected));
        assertEquals(plain.getString("observation_id"),projected.getString("observation_id"));
        assertEquals(projected.getString("fingerprint"),AppTaskObservation.recaptureLike(nativePage,projected).getString("fingerprint"));
    }

    @Test public void nestedStaticLabelKeepsItsSingleStaticOwnerAndSeparateHeadingsStayAmbiguous()throws Exception {
        String pkg="org.example.unregistered",label="공개 목적 제목";
        AccessibilityNodeSnapshot owner=new AccessibilityNodeSnapshot("title",null,true,pkg,label,null,
            pkg+":id/title","android.widget.TextView",null,new int[]{10,20,400,120},
            false,true,false,false,false,null,42,false,null,null,false,null,true,false);
        AccessibilityNodeSnapshot child=new AccessibilityNodeSnapshot("title_child","title",true,pkg,label,null,
            pkg+":id/title_child","android.widget.TextView",null,new int[]{20,30,300,100},false,true,false,false,false,null,42);
        JSONObject one=AppTaskObservation.capture(screen(pkg,owner,child),Set.of(label));
        assertNotNull(AppTaskObservation.find(one,"title"));assertNull(AppTaskObservation.find(one,"title_child"));
        JSONObject candidate=new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",pkg).put("label",label)));
        assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.check(candidate,one));
        AccessibilityNodeSnapshot other=new AccessibilityNodeSnapshot("other",null,true,pkg,label,null,
            pkg+":id/other","android.widget.TextView",null,new int[]{10,180,400,280},
            false,true,false,false,false,null,42,false,null,null,false,null,true,false);
        JSONObject two=AppTaskObservation.capture(screen(pkg,owner,child,other),Set.of(label));
        assertEquals(AppTaskCompletion.Verdict.UNKNOWN,AppTaskCompletion.check(candidate,two));
    }

    @Test public void passiveScrollContainerDoesNotTurnItsNestedMenuButtonIntoGoalProof()throws Exception {
        String pkg="org.example.unregistered",label="목적 화면";
        AccessibilityNodeSnapshot container=new AccessibilityNodeSnapshot("container",null,true,pkg,null,label,
            pkg+":id/container","android.widget.FrameLayout",null,new int[]{0,0,1080,1200},false,true,false,true,false,null,42);
        AccessibilityNodeSnapshot button=new AccessibilityNodeSnapshot("button","container",true,pkg,label,null,
            pkg+":id/open_destination","android.widget.Button",null,new int[]{10,20,400,120},true,true,false,false,false,null,42);
        AccessibilityNodeSnapshot text=new AccessibilityNodeSnapshot("button_label","button",true,pkg,label,null,
            pkg+":id/button_label","android.widget.TextView",null,new int[]{20,30,300,100},false,true,false,false,false,null,42);
        ScreenSnapshot nativeMenu=screen(pkg,container,button,text);
        JSONObject projected=AppTaskObservation.capture(nativeMenu,Set.of(label));
        assertNotNull(AppTaskObservation.find(projected,"container"));assertNotNull(AppTaskObservation.find(projected,"button"));
        assertNull("The actual button still owns its ordinary label child",AppTaskObservation.find(projected,"button_label"));
        JSONObject buttonProof=new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",pkg)
            .put("label",label).put("resource",pkg+":id/open_destination").put("role","button")));
        assertEquals(AppTaskCompletion.Verdict.UNKNOWN,AppTaskCompletion.checkForGoal("목적 화면 열어줘",buttonProof,projected,nativeMenu));
        JSONObject childProof=new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",pkg)
            .put("label",label).put("resource",pkg+":id/button_label").put("role","text")));
        assertEquals(AppTaskCompletion.Verdict.NOT_OBSERVED,AppTaskCompletion.checkForGoal("목적 화면 열어줘",childProof,projected,nativeMenu));
    }
}
