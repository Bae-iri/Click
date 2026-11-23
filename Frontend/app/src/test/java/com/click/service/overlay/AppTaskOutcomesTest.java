package com.click.service.overlay;

import android.app.Application;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import java.time.Instant;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static com.click.service.overlay.AppTaskCompletion.Verdict.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class AppTaskOutcomesTest {
    static final String PKG="com.android.settings";
    static JSONObject clause(String kind,String label)throws Exception {
        return new JSONObject().put("kind",kind).put("labels",new JSONArray().put(label)).put("metric","none").put("steps",0);
    }
    static JSONObject goal(JSONObject... clauses)throws Exception{
        return new JSONObject().put("seal","a".repeat(64)).put("clauses",new JSONArray(Arrays.asList(clauses)));
    }
    @Test public void literalTabRequiresTheNamedSelectionEvenWithAnOldBinding()throws Exception {
        for(String label:List.of("스톱워치","타이머","알람","구독")){
            JSONObject g=goal(clause("selected",label).put("proof_role","tab"));
            var other=com.click.testing.AppTaskFixtures.node(PKG,"other","다른 탭","tab",true);
            var stale=new JSONObject().put("r0",new JSONObject().put("package",PKG).put("label","다른 탭").put("role","tab"));
            for(boolean selected:List.of(false,true)){
                var frame=com.click.testing.AppTaskFixtures.screen(PKG,other,
                    com.click.testing.AppTaskFixtures.node(PKG,"wanted",label,"tab",selected));
                var current=AppTaskObservation.capture(frame,AppTaskOutcomes.labels(g));
                assertEquals(selected?MATCHED:NOT_MET,AppTaskOutcomes.check(g,current,frame,new JSONObject(),stale).verdict());
                var first=AppTaskOutcomes.firstTap(g,current,frame,new JSONObject(),stale);
                if(selected)assertNull(first);else assertEquals(label,first.node().getString("label"));
            }
        }
    }
    @Test public void searchOpenRequiresOnePublicEditableInputAndNeverTheToolbar()throws Exception {
        var g=goal(clause("page","검색어 입력").put("proof_role","text_field"));
        var entry=com.click.testing.AppTaskFixtures.node(PKG,"search_button","검색","button");
        var toolbar=new AccessibilityNodeSnapshot("toolbar",null,true,PKG,"위로 이동",null,PKG+":id/toolbar",
                "android.widget.TextView","text",new int[]{10,10,200,80},false,true,false,false,false,null,42,
                false,null,null,false,null,true,false);
        var field=com.click.testing.AppTaskFixtures.node(PKG,"field","private query","search");
        var other=com.click.testing.AppTaskFixtures.node(PKG,"another","another private query","search");
        var password=com.click.testing.AppTaskFixtures.node(PKG,"password","private","password");
        var stale=new JSONObject().put("r0",new JSONObject().put("package",PKG).put("label","위로 이동").put("role","text"));
        for(var elements:List.of(List.of(entry,toolbar),List.of(field,entry,toolbar),List.of(field,other),List.of(password))){
            ScreenSnapshot screen=com.click.testing.AppTaskFixtures.screen(PKG,elements.toArray(new AccessibilityNodeSnapshot[0]));
            var current=AppTaskObservation.capture(screen,AppTaskOutcomes.labels(g));
            var verdict=AppTaskOutcomes.check(g,current,screen,new JSONObject(),stale).verdict();
            assertEquals(elements.contains(field)&&!elements.contains(other),verdict==MATCHED);
            assertFalse(current.toString().contains("private query"));
        }
    }
    @Test public void restoredSessionTransmitsItsOriginalSealAndDiscardsStaleProofOnce()throws Exception {
        var raw=goal(clause("page","Clock"),clause("selected","알람"));
        var verified=goal(clause("selected","알람").put("proof_role","tab")).put("seal","b".repeat(64));
        var task=new AppTaskMemory("시계에서 알람 탭으로 이동해줘");
        JSONObject old=task.stored().put("outcome_goal",raw).put("arrival_receipts",new JSONObject().put("0",PKG))
                .put("outcome_bindings",new JSONObject().put("r0",new JSONObject().put("label","Clock")));
        task=new AppTaskMemory(old);
        assertTrue(task.needsOutcomePreparation());
        assertFalse(task.installOutcome(goal(clause("manual","무관")).put("seal","c".repeat(64)),verified));
        assertTrue(task.installOutcome(raw,verified));
        assertNull(task.bindings());assertEquals(0,task.arrivals().length());
        var resumed=new AppTaskMemory(task.stored());
        assertFalse(resumed.needsOutcomePreparation());
        assertEquals(raw.toString(),resumed.state().getJSONObject("outcome_goal").toString());
        assertEquals(verified.toString(),resumed.outcomeGoal().toString());
        int version=resumed.evidenceVersion();
        assertTrue(resumed.installOutcome(raw,verified));assertEquals(version,resumed.evidenceVersion());
    }
    @Test public void firstTapUsesAUniqueAliasAndRetainsRequestedState() throws Exception {
        JSONObject page = clause("page","보관함").put("labels", new JSONArray(List.of("보관함","Library")));
        JSONObject sync = clause("off","자동 동기화");
        JSONObject task = goal(page,sync);
        JSONObject entry = new JSONObject().put("role","button").put("label","Library")
                .put("source","accessibility").put("clickable",true);
        JSONObject toggle = new JSONObject().put("role","toggle").put("label","자동 동기화")
                .put("source","accessibility").put("clickable",true).put("checked",true);
        JSONObject current = new JSONObject().put("package",PKG).put("nodes",new JSONArray(List.of(entry,toggle)));
        JSONObject visits = new JSONObject();
        assertEquals(entry, AppTaskOutcomes.firstTap(task,current,null,visits,null).node());
        current.getJSONArray("nodes").put(new JSONObject(entry.toString()));
        assertNull(AppTaskOutcomes.firstTap(task,current,null,visits,null));
        visits.put("0",PKG);
        var tap = AppTaskOutcomes.firstTap(task,current,null,visits,null);
        assertEquals(toggle,tap.node());
        assertFalse(tap.expectation().getBoolean("checked"));
        toggle.put("checked",false);
        assertNull(AppTaskOutcomes.firstTap(task,current,null,visits,null));
        toggle.remove("checked");
        assertNull(AppTaskOutcomes.firstTap(task,current,null,visits,null));
    }

    @Test public void firstTapRequiresANativeUnselectedTab() throws Exception {
        JSONObject task = goal(clause("selected","스톱워치"));
        JSONObject item = new JSONObject().put("role","tab").put("label","스톱워치")
                .put("source","accessibility").put("clickable",true).put("selected",false);
        JSONObject current = new JSONObject().put("package",PKG).put("nodes",new JSONArray(List.of(item)));
        assertTrue(AppTaskOutcomes.firstTap(task,current,null,new JSONObject(),null).expectation().getBoolean("selected"));
        item.put("selected",true);
        assertNull(AppTaskOutcomes.firstTap(task,current,null,new JSONObject(),null));
        item.put("selected",false).put("source","ocr");
        assertNull(AppTaskOutcomes.firstTap(task,current,null,new JSONObject(),null));
    }
    @Test public void pageArrivalUsesTheSelectedNativeTabAndSkipsAlreadyMetClauses() throws Exception {
        var page=goal(clause("page","보관함"));
        var tab=com.click.testing.AppTaskFixtures.node(PKG,"library","보관함","tab",true);
        var selected=com.click.testing.AppTaskFixtures.screen(PKG,tab);
        var current=AppTaskObservation.capture(selected,Set.of("보관함"));
        assertEquals(MATCHED,AppTaskOutcomes.check(page,current,selected,new JSONObject(),null).verdict());
        var compound=goal(clause("selected","보관함"),clause("off","자동 동기화"));
        var settings=com.click.testing.AppTaskFixtures.screen(PKG,tab,com.click.testing.AppTaskFixtures.node(PKG,"sync","자동 동기화","toggle",true));
        var tap=AppTaskOutcomes.firstTap(compound,AppTaskObservation.capture(settings,AppTaskOutcomes.labels(compound)),settings,new JSONObject(),null);
        assertEquals("자동 동기화",tap.node().getString("label"));assertFalse(tap.expectation().getBoolean("checked"));
        var translated=goal(clause("off","Auto sync"));
        var selector=new JSONObject().put("package",PKG).put("role","toggle").put("label","자동 동기화").put("resource",PKG+":id/sync");
        var bindings=new JSONObject().put("r0",selector);
        var controls=AppTaskObservation.capture(settings,Set.of("자동 동기화"));
        assertFalse(AppTaskOutcomes.firstTap(translated,controls,settings,new JSONObject(),bindings).expectation().getBoolean("checked"));
        selector.put("package","org.another.app");
        assertNull(AppTaskOutcomes.firstTap(translated,controls,settings,new JSONObject(),bindings));
        current.getJSONArray("nodes").getJSONObject(0).put("source","ocr");
        assertEquals(NOT_OBSERVED,AppTaskOutcomes.check(page,current,selected,new JSONObject(),null).verdict());
        var inactive=com.click.testing.AppTaskFixtures.screen(PKG,com.click.testing.AppTaskFixtures.node(PKG,"library","보관함","tab",false));
        assertEquals(NOT_OBSERVED,AppTaskOutcomes.check(page,AppTaskObservation.capture(inactive),inactive,new JSONObject(),null).verdict());
    }
    private static AccessibilityNodeSnapshot node(String id,String label,boolean heading,Boolean checked,Float position,boolean discrete){
        return new AccessibilityNodeSnapshot(id,null,true,PKG,label,null,PKG+":id/"+id,
            position!=null?"android.widget.SeekBar":checked!=null?"android.widget.Switch":"android.widget.TextView",position!=null?"slider":null,
            new int[]{10,10,200,80},checked!=null,true,false,false,checked!=null,position,5,false,
            position==null?null:0f,position==null?null:6f,false,checked,heading,false,discrete);
    }
    private static ScreenSnapshot frame(long millis,float scale,AccessibilityNodeSnapshot... nodes){
        return new ScreenSnapshot(PKG,Instant.ofEpochMilli(millis).toString(),500,700,List.of(nodes),5,true,scale);
    }
    private static AppTaskCompletion.Verdict check(JSONObject g,ScreenSnapshot s,JSONObject baselines,AppTaskOutcomes.Arrivals visits,JSONObject receipts)throws Exception{
        return AppTaskOutcomes.check(g,AppTaskObservation.capture(s,AppTaskOutcomes.labels(g)),s,baselines,null,visits,receipts).verdict();
    }
    @Test public void reversalReopensTheMatchingCompletedStageAndSurvivesRestore()throws Exception{
        var task=new AppTaskMemory("자동 동기화를 끄고 보관함을 열어줘");
        task.plan(new JSONArray().put(new JSONObject().put("title","자동 동기화 끄기")).put(new JSONObject().put("title","보관함 열기")));
        assertTrue(task.installOutcome(goal(clause("off","자동동기화"),clause("page","보관함"))));
        JSONObject saved=task.stored();JSONArray stages=saved.getJSONArray("subgoals");
        for(int i=0;i<stages.length();i++)stages.getJSONObject(i).put("status","verified");
        task=new AppTaskMemory(saved);
        var frame=AppTaskObservation.capture(frame(1000,1,node("sync","자동 동기화",false,true,null,false)));
        task.reopenResult("native_state_reversed",frame,0);
        var restored=new AppTaskMemory(task.stored());JSONArray resumed=restored.state().getJSONArray("subgoals");
        assertEquals("pending",resumed.getJSONObject(0).getString("status"));
        assertEquals("verified",resumed.getJSONObject(1).getString("status"));
        assertFalse(restored.completionSupported());assertTrue(restored.routeDisputed());
    }
    @Test public void exactOneStepNeedsBothAnIdentifiedDiscreteChangeAndAnAppliedValueChange()throws Exception{
        JSONObject g=goal(clause("increase","글자 크기").put("metric","font_size").put("steps",1)),b=new JSONObject();
        var title=node("heading","글자 크기",true,null,null,false);
        assertEquals(NOT_OBSERVED,check(g,frame(1000,1f,title),b,null,null));
        assertEquals(UNKNOWN,check(g,frame(1050,1f,title,node("font_size","글자 크기",false,null,1f,false)),b,null,null));
        assertEquals(0,b.length());
        assertEquals(NOT_MET,check(g,frame(1100,1f,title,node("font_size","글자 크기",false,null,1f,true)),b,null,null));
        assertEquals(NOT_MET,check(g,frame(1200,1f,title,node("font_size","글자 크기",false,null,2f,true)),b,null,null));
        assertEquals(NOT_MET,check(g,frame(1300,1.3f,title,node("font_size","글자 크기",false,null,3f,true)),b,null,null));
        assertEquals(MATCHED,check(g,frame(1400,1.1f,title,node("font_size","글자 크기",false,null,2f,true)),b,null,null));
        assertEquals(NOT_MET,check(g,frame(1500,1f,title,node("font_size","글자 크기",false,null,1f,true)),b,null,null));
    }
    @Test public void aVerifiedVisitCanPrecedeTheToggleOnADifferentSubpage()throws Exception{
        JSONObject g=goal(clause("page","보관함"),clause("off","자동 동기화")),b=new JSONObject(),receipts=new JSONObject();
        var visits=new AppTaskOutcomes.Arrivals();
        var title=node("title","보관함",true,null,null,false);
        assertEquals(NOT_OBSERVED,check(g,frame(1000,1,title),b,visits,receipts));
        assertEquals(NOT_OBSERVED,check(g,frame(1200,1,title),b,visits,receipts));
        assertEquals(0,receipts.length());
        var off=node("sync","자동 동기화",false,false,null,false);
        assertEquals(NOT_OBSERVED,check(g,frame(1300,1,off),b,visits,receipts));
        assertEquals(NOT_OBSERVED,check(g,frame(1400,1,title),b,visits,receipts));
        assertEquals(NOT_OBSERVED,check(g,frame(1800,1,title),b,visits,receipts));
        assertEquals(1,receipts.length());
        assertEquals(MATCHED,check(g,frame(1900,1,off),b,visits,receipts));
        assertEquals(NOT_MET,check(g,frame(2000,1,node("sync","자동 동기화",false,true,null,false)),b,visits,receipts));
        assertEquals(NOT_OBSERVED,check(g,frame(2100,1,node("other","설정",true,null,null,false)),b,visits,receipts));
    }
}
