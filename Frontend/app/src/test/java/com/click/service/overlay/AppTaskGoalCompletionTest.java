package com.click.service.overlay;

import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;
import static com.click.service.overlay.AppTaskCompletion.Verdict.*;

/** The final goal contract is stricter than the effect of one action. */
public class AppTaskGoalCompletionTest {
    private static final String PKG="org.example.unseen";
    static AccessibilityNodeSnapshot heading(String pkg,String id,String label){
        return new AccessibilityNodeSnapshot(id,null,true,pkg,label,null,pkg+":id/"+id,"android.widget.TextView",null,
            new int[]{10,20,400,120},false,true,false,false,false,null,42,false,null,null,false,null,true,false);
    }
    private static JSONObject result(String label)throws Exception{return AppTaskCompletionTest.completion(PKG,label);}

    @Test public void visibleMenuIsNotTheDestinationEvenIfModelOmitsItsRole()throws Exception {
        ScreenSnapshot menu=screen(PKG,node(PKG,"menu","디스플레이","button"));JSONObject observed=AppTaskObservation.capture(menu);
        JSONObject condition=result("디스플레이");
        assertEquals(MATCHED,AppTaskCompletion.check(condition,observed));
        assertTrue(AppTaskCompletion.unsafeForGoal("디스플레이 열어줘",condition,observed,menu));
        assertEquals(UNKNOWN,AppTaskCompletion.checkForGoal("디스플레이 열어줘",condition,observed,menu));
        condition.getJSONArray("checks").getJSONObject(0).put("role","button");
        assertEquals(UNKNOWN,AppTaskCompletion.checkForGoal("디스플레이 화면으로 이동",condition,observed,menu));
    }

    @Test public void futureLandmarkAndAlreadyOpenOrManuallyReachedDestinationAreValid()throws Exception {
        JSONObject condition=result("디스플레이");ScreenSnapshot start=screen(PKG,node(PKG,"search","검색","button"));
        assertFalse(AppTaskCompletion.unsafeForGoal("디스플레이 열어줘",condition,AppTaskObservation.capture(start),start));
        ScreenSnapshot destination=screen(PKG,heading(PKG,"title","디스플레이"));
        JSONObject observed=AppTaskObservation.capture(destination,Set.of("디스플레이"));
        assertEquals(MATCHED,AppTaskCompletion.checkForGoal("디스플레이 열어줘",condition,observed,destination));
        // A heading flag is optional: requested public, non-actionable text is enough.
        ScreenSnapshot text=screen(PKG,new AccessibilityNodeSnapshot("content",null,true,PKG,"디스플레이",null,PKG+":id/content",
            "android.widget.TextView",null,new int[]{10,20,400,120},false,true,false,false,false,null,42));
        assertEquals(MATCHED,AppTaskCompletion.checkForGoal("디스플레이 열어줘",condition,AppTaskObservation.capture(text,Set.of("디스플레이")),text));
    }

    @Test public void textWithinAnActionableMenuRowCannotMasqueradeAsDestinationText()throws Exception {
        AccessibilityNodeSnapshot child=new AccessibilityNodeSnapshot("caption","row",true,PKG,"디스플레이",null,PKG+":id/caption",
            "android.widget.TextView",null,new int[]{20,30,300,100},false,true,false,false,false,null,42);
        ScreenSnapshot menu=screen(PKG,node(PKG,"row","화면 설정 항목","button"),child);
        JSONObject condition=result("디스플레이"),observed=AppTaskObservation.capture(menu,Set.of("디스플레이"));
        assertEquals(MATCHED,AppTaskCompletion.check(condition,observed));
        assertEquals(UNKNOWN,AppTaskCompletion.checkForGoal("디스플레이 열어줘",condition,observed,menu));
    }

    @Test public void nativePageHeadingRemainsValidInsideAClickableBackground()throws Exception {
        AccessibilityNodeSnapshot title=new AccessibilityNodeSnapshot("title","background",true,PKG,"디스플레이",null,PKG+":id/title",
            "android.widget.TextView",null,new int[]{20,30,300,100},false,true,false,false,false,null,42,false,null,null,false,null,true,false);
        ScreenSnapshot destination=screen(PKG,node(PKG,"background","배경","button"),title);JSONObject condition=result("디스플레이");
        assertEquals(MATCHED,AppTaskCompletion.checkForGoal("디스플레이 열어줘",condition,AppTaskObservation.capture(destination),destination));
    }

    @Test public void selectedDestinationAndDesiredToggleStatesRemainValidWithoutAnAction()throws Exception {
        ScreenSnapshot selected=screen(PKG,node(PKG,"history","기록","tab",true));JSONObject observed=AppTaskObservation.capture(selected);
        JSONObject condition=result("기록");condition.getJSONArray("checks").getJSONObject(0).put("role","tab").put("selected",true);
        assertEquals(MATCHED,AppTaskCompletion.checkForGoal("기록 탭 열어줘",condition,observed,selected));
        condition.getJSONArray("checks").getJSONObject(0).put("selected",false);
        ScreenSnapshot unselected=screen(PKG,node(PKG,"history","기록","tab",false));
        assertEquals(UNKNOWN,AppTaskCompletion.checkForGoal("기록 탭 열어줘",condition,AppTaskObservation.capture(unselected),unselected));
        assertEquals(UNKNOWN,AppTaskCompletion.checkForGoal("기록 탭 선택해줘",condition,AppTaskObservation.capture(unselected),unselected));
        assertEquals(MATCHED,AppTaskCompletion.checkForGoal("기록 탭 선택 해제",condition,AppTaskObservation.capture(unselected),unselected));
        condition=result("알림");condition.getJSONArray("checks").getJSONObject(0).put("role","toggle").put("checked",false);
        ScreenSnapshot disabled=screen(PKG,node(PKG,"notifications","알림","toggle",false));
        assertEquals(MATCHED,AppTaskCompletion.checkForGoal("알림 꺼줘",condition,AppTaskObservation.capture(disabled),disabled));
    }

    @Test public void searchFieldOnlyProvesOpeningSearchAndNeverQueryOrResults()throws Exception {
        ScreenSnapshot search=screen(PKG,node(PKG,"search","","search"));JSONObject observed=AppTaskObservation.capture(search),condition=result("검색어 입력");
        for(String goal:new String[]{"검색창 열어줘","검색창 보여줘","검색창 띄워줘","검색창 켜줘","show search"})
            assertEquals(goal,MATCHED,AppTaskCompletion.checkForGoal(goal,condition,observed,search));
        for(String goal:new String[]{"검색어 입력해줘","검색 결과 열어줘","검색 열기 그리고 목록 확인","검색 열고 목록 확인"})
            assertEquals(goal,UNKNOWN,AppTaskCompletion.checkForGoal(goal,condition,observed,search));
    }

    @Test public void rejectingLegacyCompletionPreservesTaskProgressAndUserConfirmedResults()throws Exception {
        AppTaskMemory memory=new AppTaskMemory("디스플레이 열어줘");memory.localPlan();memory.scope("test-scope");memory.completion(result("디스플레이"));
        String id=memory.id(),plan=memory.state().getJSONArray("subgoals").toString();
        memory.discardCompletion();assertNull(memory.completion());assertEquals(id,memory.id());assertEquals(plan,memory.state().getJSONArray("subgoals").toString());assertEquals("test-scope",memory.scope());
        memory.completion(result("디스플레이 화면"));memory.confirmResult();memory.discardCompletion();
        assertTrue(memory.finished());assertNotNull(memory.completion());assertEquals(id,memory.id());
    }
}
