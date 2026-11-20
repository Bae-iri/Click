package com.click.service.overlay;

import com.click.data.snapshot.AppTaskObservation;
import org.json.*;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;
import static com.click.service.overlay.AppTaskCompletion.Verdict.*;

public class AppTaskCompletionTest {
    public static JSONObject completion(String pkg,String label)throws JSONException {
        return new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",pkg).put("label",label)));
    }
    @Test public void alreadyReachedOrManuallyReachedResultWorksWithoutAnyClickOrPlanBits()throws Exception {
        for(String pkg:List.of("com.spotify.music","org.mozilla.firefox","org.example.unregistered")){
            JSONObject result=completion(pkg,"목록");
            assertEquals(NOT_MET,AppTaskCompletion.check(result,observation(pkg,"검색")));
            assertEquals(MATCHED,AppTaskCompletion.check(result,observation(pkg,"목록")));
            assertEquals(NOT_MET,AppTaskCompletion.check(result,observation("org.wrong.app","목록")));
        }
    }
    @Test public void allFinalStatesMustHoldTogetherAndInTheRequestedDirection()throws Exception {
        String pkg="org.example.app";JSONObject result=completion(pkg,"목록");
        result.getJSONArray("checks").put(new JSONObject().put("package",pkg).put("label","알림").put("role","toggle").put("checked",false));
        JSONObject wrong=AppTaskObservation.capture(screen(pkg,node(pkg,"list","목록","button"),node(pkg,"toggle","알림","toggle",true)));
        JSONObject right=AppTaskObservation.capture(screen(pkg,node(pkg,"new-list","목록","button"),node(pkg,"redrawn-toggle","알림","toggle",false)));
        assertEquals(NOT_MET,AppTaskCompletion.check(result,wrong));assertEquals(MATCHED,AppTaskCompletion.check(result,right));
    }
    @Test public void ambiguousOcrProtectedAndIncompleteEvidenceCannotClaimSuccess()throws Exception {
        String pkg="org.example.app";JSONObject result=completion(pkg,"목록");
        JSONObject ambiguous=AppTaskObservation.capture(screen(pkg,node(pkg,"one","목록","button"),node(pkg,"two","목록","button")));
        assertEquals(UNKNOWN,AppTaskCompletion.check(result,ambiguous));
        JSONObject ocr=observation(pkg,"목록");ocr.getJSONArray("nodes").getJSONObject(0).put("source","ocr");assertEquals(NOT_MET,AppTaskCompletion.check(result,ocr));
        assertEquals(PROTECTED,AppTaskCompletion.check(result,AppTaskObservation.capture(screen(pkg,node(pkg,"secret","PRIVATE","password")))));
        assertEquals(UNKNOWN,AppTaskCompletion.check(result,null));assertFalse(AppTaskCompletion.valid(new JSONObject().put("checks",new JSONArray())));
        result.getJSONArray("checks").getJSONObject(0).put("predicate","arbitrary code");assertFalse(AppTaskCompletion.valid(result));
    }
    @Test public void resultContractIsImmutableAndSurvivesRestoringTheTask()throws Exception {
        AppTaskMemory memory=new AppTaskMemory("목록 열어줘");JSONObject result=completion("org.example.app","목록");memory.completion(result);
        AppTaskMemory restored=new AppTaskMemory(memory.stored());assertEquals(AppTaskCompletion.key(result),AppTaskCompletion.key(restored.completion()));
        try{restored.completion(completion("org.example.app","홈"));fail("Result was replaced");}catch(JSONException expected){}
    }
}
