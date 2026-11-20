package com.click.service.overlay;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.click.service.overlay.AppTaskMetaController.*;

public class AppTaskMetaControllerTest {
    private JSONObject observation(String id,long at)throws Exception {
        return new JSONObject().put("observation_id",id).put("captured_at_ms",at).put("package","org.example.app")
            .put("status","public").put("fingerprint","same-state");
    }
    private JSONObject action(String target)throws Exception{return new JSONObject().put("tool","tap").put("target_id",target).put("package","org.example.app");}
    @Test public void defaultPolicyMatchesFiveSecondBaselineAndNeverBlocksAnAction()throws Exception {
        var mc=new AppTaskMetaController(Arm.CURRENT);JSONObject before=observation("before",100),after=observation("after",200);var action=action("target");
        assertEquals(Decision.WAIT,mc.after(before,after,action,AppTaskVerifier.Verdict.WAITING,4999,7000));
        assertEquals(Decision.HANDOFF,mc.after(before,after,action,AppTaskVerifier.Verdict.WAITING,5000,7000));
        assertFalse(mc.blocked(before,action));assertEquals(0,mc.diagnostic().getInt("recovery_count"));
    }
    @Test public void onlyNativeSuccessCanContinueRegardlessOfEvents()throws Exception {
        for(Arm arm:Arm.values()){
            var mc=new AppTaskMetaController(arm);JSONObject before=observation("before",100),after=observation("after",200);
            assertEquals(Decision.CONTINUE,mc.after(before,after,action("target"),AppTaskVerifier.Verdict.VERIFIED,250,1000));
            for(var verdict:new AppTaskVerifier.Verdict[]{AppTaskVerifier.Verdict.PROTECTED,AppTaskVerifier.Verdict.STALE})
                assertEquals(Decision.HANDOFF,mc.after(before,after,action("target"),verdict,250,1000));
        }
    }
    @Test public void recentNativeEventExtendsObservationOnlyWithinHardBudget()throws Exception {
        var mc=new AppTaskMetaController(Arm.META);JSONObject before=observation("before",100),after=observation("after",200);var action=action("target");
        mc.observeEvent(after,6900);
        assertEquals(Decision.WAIT,mc.after(before,after,action,AppTaskVerifier.Verdict.WAITING,5000,7000));
        mc.observeEvent(after,9900);
        assertEquals(Decision.REOBSERVE,mc.after(before,after,action,AppTaskVerifier.Verdict.WAITING,8000,10000));
        assertTrue(mc.blocked(before,action));assertEquals(1,mc.diagnostic().getInt("recovery_count"));
        assertEquals(0,mc.diagnostic().getInt("reflection_model_calls"));
    }
    @Test public void eventAblationKeepsRecoveryButDoesNotExtendWaiting()throws Exception {
        var mc=new AppTaskMetaController(Arm.NO_EVENTS);JSONObject before=observation("before",100),after=observation("after",200);
        mc.observeEvent(after,6900);
        assertEquals(Decision.REOBSERVE,mc.after(before,after,action("target"),AppTaskVerifier.Verdict.WAITING,5000,7000));
        assertEquals(0,mc.diagnostic().getInt("event_count"));
    }
    @Test public void silentOrUnrelatedEventsDoNotShortenOrExtendTheAbsoluteLease()throws Exception {
        for(String event:new String[]{"none","stale","future","other_app","before_action","protected"}){
            var mc=new AppTaskMetaController(Arm.META);JSONObject before=observation("before",100),after=observation("after",200);
            if(!event.equals("none")){
                var source=observation("event",200);long at=6900;
                if(event.equals("other_app"))source.put("package","org.other.app");
                if(event.equals("protected"))source.put("status","protected");
                if(event.equals("stale"))at=5000;if(event.equals("future"))at=7001;if(event.equals("before_action"))at=1500;
                mc.observeEvent(source,at);
            }
            assertEquals(event,Decision.WAIT,mc.after(before,after,action("target"),AppTaskVerifier.Verdict.WAITING,5000,7000));
            assertEquals(event,Decision.REOBSERVE,mc.after(before,after,action("target"),AppTaskVerifier.Verdict.WAITING,8000,10000));
        }
    }
    @Test public void unknownOrInvalidObservationsNeverTriggerAutomaticRecovery()throws Exception {
        for(String invalid:new String[]{"unknown","same_id","time_reversed","protected","other_app"}){
            var mc=new AppTaskMetaController(Arm.META);JSONObject before=observation("before",100),after=observation("after",200);
            var verdict=AppTaskVerifier.Verdict.WAITING;
            if(invalid.equals("unknown"))verdict=AppTaskVerifier.Verdict.UNKNOWN;
            if(invalid.equals("same_id"))after.put("observation_id","before");
            if(invalid.equals("time_reversed"))after.put("captured_at_ms",99);
            if(invalid.equals("protected"))after.put("status","protected");
            if(invalid.equals("other_app"))after.put("package","org.other.app");
            assertEquals(invalid,Decision.HANDOFF,mc.after(before,after,action("target"),verdict,8000,10000));
            assertEquals(0,mc.diagnostic().getInt("recovery_count"));
        }
    }
    @Test public void noRepeatedAcceptedActionAndRecoveryBudgetIsTaskBounded()throws Exception {
        var mc=new AppTaskMetaController(Arm.META);JSONObject before=observation("before",100),after=observation("after",200);
        for(int i=0;i<3;i++){
            var action=action("target"+i);
            assertEquals(Decision.REOBSERVE,mc.after(before,after,action,AppTaskVerifier.Verdict.WAITING,8000,10000));
            assertTrue(mc.blocked(before,action));
            assertEquals(Decision.HANDOFF,mc.after(before,after,action,AppTaskVerifier.Verdict.WAITING,8000,10000));
        }
        assertEquals(Decision.HANDOFF,mc.after(before,after,action("fourth"),AppTaskVerifier.Verdict.WAITING,8000,10000));
        assertFalse(mc.blocked(new JSONObject(before.toString()).put("fingerprint","changed"),action("target0")));
    }
    @Test public void boundedMemoryDoesNotRetainPublicLabelsInputOrScreenshots()throws Exception {
        var mc=new AppTaskMetaController(Arm.META);JSONObject before=observation("before",100),after=observation("after",200).put("label","PRIVATE_QUERY");
        for(int i=0;i<100;i++){mc.observeEvent(after,i);mc.after(before,after,action("target"),AppTaskVerifier.Verdict.VERIFIED,250,1000);}
        assertEquals(16,mc.diagnostic().getInt("event_count"));assertEquals(16,mc.diagnostic().getJSONArray("outcomes").length());
        assertFalse(mc.diagnostic().toString().contains("PRIVATE_QUERY"));
    }
}
