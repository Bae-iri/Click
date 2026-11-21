package com.click.service.overlay;

import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

public class AppTaskMemoryCompletionDiagnosticsTest {
    private static final String PKG="org.example.app";
    private JSONObject candidate(String label)throws Exception{return AppTaskCompletionTest.completion(PKG,label);}
    private JSONObject observed(ScreenSnapshot screen,JSONObject candidate)throws Exception{return AppTaskObservation.capture(screen,AppTaskCompletion.labels(candidate));}

    @Test public void absentInvalidAndUnsafeCandidatesHaveTransientClosedReasonsWithoutStateChanges()throws Exception {
        AppTaskMemory memory=new AppTaskMemory("디스플레이 열어줘");String before=memory.stored().toString();
        ScreenSnapshot menu=screen(PKG,node(PKG,"menu","디스플레이","button"));JSONObject candidate=candidate("디스플레이");
        assertFalse(memory.offerCompletion(null,observed(menu,candidate),menu));assertEquals("missing_candidate",memory.lastCompletionOfferReason());
        assertFalse(memory.offerCompletion(new JSONObject(),observed(menu,candidate),menu));assertEquals("invalid_contract",memory.lastCompletionOfferReason());
        assertFalse(memory.offerCompletion(candidate,observed(menu,candidate),menu));assertEquals("unsafe_for_goal",memory.lastCompletionOfferReason());
        assertEquals(before,memory.stored().toString());
        assertEquals("missing_candidate",new AppTaskMemory(memory.stored()).lastCompletionOfferReason());
        assertFalse(memory.state().has("lastCompletionOfferReason"));
    }

    @Test public void futurePredictionAcceptanceIsDistinctFromReplacementAndImmutabilityRejections()throws Exception {
        AppTaskMemory memory=new AppTaskMemory("디스플레이 열어줘");JSONObject first=candidate("디스플레이"),next=candidate("다른 화면");
        // Public label aliases may refine freely; dropping an entire required fact must fail.
        first.getJSONArray("checks").put(new JSONObject().put("package",PKG).put("label","밝기 안내"));
        ScreenSnapshot start=screen(PKG,node(PKG,"home","홈","button"));
        assertTrue(memory.offerCompletion(first,observed(start,first),start));assertEquals("initial_prediction",memory.lastCompletionOfferReason());
        assertEquals(AppTaskCompletion.Verdict.NOT_OBSERVED,AppTaskCompletion.checkForGoal(memory.goal(),first,observed(start,first),start));
        assertTrue(memory.offerCompletion(first,observed(start,first),start));assertEquals("same_candidate",memory.lastCompletionOfferReason());
        assertFalse(memory.offerCompletion(next,observed(start,next),start));assertEquals("replacement_unmatched",memory.lastCompletionOfferReason());
        ScreenSnapshot destination=screen(PKG,AppTaskGoalCompletionTest.heading(PKG,"title","다른 화면"));
        assertFalse(memory.offerCompletion(next,observed(destination,next),destination));assertEquals("requirements_not_preserved",memory.lastCompletionOfferReason());
        assertTrue(memory.supportCompletion(memory.evidenceVersion()));
        assertFalse(memory.offerCompletion(next,observed(destination,next),destination));assertEquals("immutable_supported",memory.lastCompletionOfferReason());
        memory.confirmResult();assertFalse(memory.offerCompletion(next,observed(destination,next),destination));assertEquals("immutable_finished",memory.lastCompletionOfferReason());
    }

    @Test public void boundedCandidateVersionAndObservedRefinementRetainExistingDecisions()throws Exception {
        JSONObject first=candidate("디스플레이"),refined=candidate("디스플레이");refined.getJSONArray("checks").getJSONObject(0).put("resource",PKG+":id/title");
        ScreenSnapshot destination=screen(PKG,AppTaskGoalCompletionTest.heading(PKG,"title","디스플레이"));
        AppTaskMemory memory=new AppTaskMemory("디스플레이 열어줘");assertTrue(memory.offerCompletion(first,observed(destination,first),destination));
        assertTrue(memory.offerCompletion(refined,observed(destination,refined),destination));assertEquals("observed_prediction_refined",memory.lastCompletionOfferReason());
        JSONObject stored=memory.stored();stored.getJSONObject("completion_evidence").put("version",32);
        AppTaskMemory bounded=new AppTaskMemory(stored);JSONObject different=candidate("미래 화면");
        assertFalse(bounded.offerCompletion(different,observed(destination,different),destination));assertEquals("version_limit",bounded.lastCompletionOfferReason());
    }
}
