package com.click.service.overlay;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

public class AppTaskMemoryTest {
    @Test public void planAdvancesOnlyAfterVerifiedActionAndSurvivesRestore()throws Exception {
        AppTaskMemory m=new AppTaskMemory("검색 열기 그리고 목록 확인");m.localPlan();
        JSONObject screen=observation("org.example.app","검색"),action=action(screen,"tap","screen_changed");
        m.record(screen,action,"failed",false);assertEquals("g0",m.active());
        m.record(screen,action,"verified",false);assertEquals("g0",m.active());
        m.record(screen,action,"verified",true);assertEquals("g1",m.active());
        AppTaskMemory restored=new AppTaskMemory(m.stored());assertEquals(m.id(),restored.id());assertEquals("g1",restored.active());assertEquals(3,restored.actions());assertFalse(restored.finished());
        restored.record(screen,action,"verified",true);assertNull(restored.active());assertFalse(restored.finished());restored.confirmResult();assertTrue(restored.finished());
    }
    @Test public void modelCannotMarkStepsCompleteOrRewritePlan()throws Exception {
        AppTaskMemory m=new AppTaskMemory("검색 열기");JSONArray plan=new JSONArray().put(new JSONObject().put("id","g0").put("title","검색 열기").put("status","verified"));m.plan(plan);assertEquals("g0",m.active());
        m.plan(plan);assertEquals("g0",m.active());
        plan.getJSONObject(0).put("title","다른 작업");try{m.plan(plan);fail("Plan was replaced");}catch(JSONException expected){}
    }
    @Test public void attemptsAreBoundedAndRepeatedFailureUsesStableIdentity()throws Exception {
        AppTaskMemory m=new AppTaskMemory("뒤로 가기");m.localPlan();JSONObject before=observation("org.example.app","검색"),a=action(before,"back","screen_changed").put("target_id",JSONObject.NULL);
        m.record(before,a,"failed",false);assertFalse(m.repeated(before,a));m.record(before,a,"failed",false);assertTrue(m.repeated(before,a));
        for(int i=0;i<20;i++)m.record(before,a,"failed",false);assertEquals(8,m.state().getJSONArray("attempts").length());
        assertFalse(m.repeated(observation("org.other.app","검색"),a));assertEquals("",m.state().getJSONArray("attempts").getJSONObject(0).getString("target"));
    }
    @Test public void learnedRoutesContainSemanticsAndNeverTextValuesOrCoordinates()throws Exception {
        AppTaskMemory m=new AppTaskMemory("검색어 입력");m.localPlan();JSONObject before=observation("org.example.app","검색"),a=action(before,"set_text","input_present").put("input","PRIVATE_QUERY");
        m.record(before,a,"verified",true);String route=m.route().toString();assertFalse(route.contains("PRIVATE_QUERY"));assertFalse(route.contains("bounds"));assertFalse(route.contains("target_id"));assertTrue(route.contains("resource"));
    }
}
