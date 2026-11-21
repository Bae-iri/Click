package com.click.service.overlay;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Set;

import static org.junit.Assert.*;

/** Device round 2 (2026-11-21): "앱 알림" was settled on the "알림" page. Generality guards. */
@RunWith(RobolectricTestRunner.class)
public class AppTaskCompletionGoalEntryTest {
    static final String PKG="com.android.settings";
    static JSONObject node(String id,String label,String role,boolean clickable,boolean selected)throws Exception{
        return new JSONObject().put("id",id).put("label",label).put("role",role).put("clickable",clickable).put("selected",selected)
            .put("editable",false).put("scrollable",false).put("source","accessibility").put("bounds",new JSONArray("[0,0,100,100]"));
    }
    static JSONObject screen(JSONObject... nodes){JSONArray a=new JSONArray();for(JSONObject n:nodes)a.put(n);
        try{return new JSONObject().put("status","public").put("package",PKG).put("nodes",a);}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject check(String label,String role,Boolean selected)throws Exception{
        JSONObject c=new JSONObject().put("package",PKG).put("label",label);if(role!=null)c.put("role",role);if(selected!=null)c.put("selected",selected);
        return new JSONObject().put("checks",new JSONArray().put(c));
    }

    @Test public void destinationPhraseIsParsedNotJustContained(){
        assertEquals(Set.of("앱알림화면","앱알림"),AppTaskCompletion.destinationForms("앱 알림 화면을 열어줘"));
        assertTrue(AppTaskCompletion.destinationForms("디스플레이 설정 화면을 열어줘").contains("디스플레이"));
        assertTrue(AppTaskCompletion.destinationForms("파일 앱에서 다운로드 화면을 열어줘").contains("다운로드"));
        assertTrue(AppTaskCompletion.destinationForms("Open the Display & touch screen in Settings").contains("display&touch"));
        assertTrue(AppTaskCompletion.destinationForms("디스플레이").isEmpty());
    }
    @Test public void parentPageTitleCannotProveADeeperDestination()throws Exception{
        String goal="앱 알림 화면을 열어줘";
        JSONObject parent=screen(node("t","알림","text",false,false),node("r","앱 알림","button",true,false));
        assertTrue(AppTaskCompletion.goalEntryVisible(goal,parent));
        assertTrue(AppTaskCompletion.unsafeForGoal(goal,check("알림",null,null),parent,null));
        assertNotEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal(goal,check("알림",null,null),parent,null));
    }
    @Test public void theRealDestinationTitleStillProves()throws Exception{
        String goal="앱 알림 화면을 열어줘";
        JSONObject destination=screen(node("t","앱 알림","text",false,false),node("f","전체","button",true,false));
        assertFalse(AppTaskCompletion.goalEntryVisible(goal,destination));
        assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal(goal,check("앱 알림",null,null),destination,null));
    }
    @Test public void inPlaceTogglesAndSelectedTabsAreNotEntriesElsewhere()throws Exception{
        JSONObject wifi=screen(node("t","Wi-Fi","text",false,false),node("s","Wi-Fi","toggle",true,false));
        assertFalse(AppTaskCompletion.goalEntryVisible("Wi-Fi 화면을 열어줘",wifi));
        JSONObject clock=screen(node("a","알람","tab",true,false),node("b","타이머","tab",true,true));
        assertFalse(AppTaskCompletion.goalEntryVisible("타이머 탭으로 이동해줘",clock));
        assertFalse(AppTaskCompletion.unsafeForGoal("타이머 탭으로 이동해줘",check("타이머","tab",true),clock,null));
    }

    @Test public void goalDerivedConditionNeedsTheDestinationTitleItself()throws Exception{
        JSONObject arrived=screen(node("t","앱 알림","text",false,false),node("f","전체","button",true,false));
        JSONObject c=AppTaskCompletion.goalDerivedCompletion("앱 알림 화면을 열어줘",arrived,"설정");
        assertNotNull(c);assertEquals("앱 알림",c.getJSONArray("checks").getJSONObject(0).getString("label"));
        assertEquals(AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal("앱 알림 화면을 열어줘",c,arrived,null));
        // Parent page: its title is a word inside the goal and the destination row is visible.
        JSONObject parent=screen(node("t","알림","text",false,false),node("r","앱 알림","button",true,false));
        assertNull(AppTaskCompletion.goalDerivedCompletion("앱 알림 화면을 열어줘",parent,"설정"));
        // A menu row named like the goal is never a title; generic and app-name titles never bind.
        assertNull(AppTaskCompletion.goalDerivedCompletion("디스플레이 화면을 열어줘",screen(node("r","디스플레이","button",true,false)),"설정"));
        assertNull(AppTaskCompletion.goalDerivedCompletion("설정 화면을 열어줘",screen(node("t","설정","text",false,false)),"설정"));
        // Two different matching destinations: leave it to the planner.
        assertNull(AppTaskCompletion.goalDerivedCompletion("디스플레이 설정 화면을 열어줘",
            screen(node("a","디스플레이","text",false,false),node("b","디스플레이 설정","text",false,false)),"설정"));
    }
    @Test public void goalDerivedConditionAcceptsOnlyTheSelectedRequestedTab()throws Exception{
        JSONObject on=screen(node("a","알람","tab",true,false),node("b","스톱워치","tab",true,true));
        JSONObject c=AppTaskCompletion.goalDerivedCompletion("시계에서 스톱워치 탭을 열어줘",on,"시계");
        assertNotNull(c);assertTrue(c.getJSONArray("checks").getJSONObject(0).getBoolean("selected"));
        JSONObject off=screen(node("a","알람","tab",true,true),node("b","스톱워치","tab",true,false));
        assertNull(AppTaskCompletion.goalDerivedCompletion("시계에서 스톱워치 탭을 열어줘",off,"시계"));
    }
}
