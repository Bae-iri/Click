package com.click.data.snapshot;

import com.click.service.overlay.*;
import org.json.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

public class AppTaskLearningKeysTest {
    @Test public void javaAndBackendShareUnicodeAndViewportFingerprints()throws Exception {
        String fixture=new String(getClass().getResourceAsStream("/app-task-experience-v1.json").readAllBytes(),StandardCharsets.UTF_8);JSONArray cases=new JSONObject(fixture).getJSONArray("cases");
        for(int i=0;i<cases.length();i++){JSONObject row=cases.getJSONObject(i),screen=row.getJSONObject("screen");assertEquals(row.getString("screen_key"),AppTaskObservation.learningKey(screen));for(int j=0;j<screen.getJSONArray("nodes").length();j++)assertEquals(row.getJSONArray("target_keys").getString(j),AppTaskObservation.targetKey(screen.getJSONArray("nodes").getJSONObject(j)));}
        assertNotEquals(cases.getJSONObject(0).getString("screen_key"),cases.getJSONObject(1).getString("screen_key"));
    }
    @Test public void rememberedScreenRequiresTheExpectedDestinationRatherThanAnyChange()throws Exception {
        JSONObject before=observation("org.example.reader","검색"),after=observation("org.example.reader","목록"),a=action(before,"tap","screen_changed");a.getJSONObject("expectation").put("screen_key",AppTaskObservation.learningKey(after));
        assertEquals(AppTaskVerifier.Verdict.WAITING,AppTaskVerifier.check(before,observation("org.example.reader","다른 화면"),a,true,false));assertEquals(AppTaskVerifier.Verdict.VERIFIED,AppTaskVerifier.check(before,after,a,true,false));
    }
    @Test public void nodeReindexingAndGeometryAloneNeverSupplySuccessEvidence()throws Exception {
        JSONObject before=observation("org.example.reader","검색"),after=new JSONObject(before.toString());after.getJSONArray("nodes").getJSONObject(0).put("id","node_0_7").put("bounds",new JSONArray(new int[]{30,40,420,140}));AppTaskObservation.finish(after);
        assertNotEquals(before.getString("fingerprint"),after.getString("fingerprint"));assertEquals(AppTaskVerifier.Verdict.WAITING,AppTaskVerifier.check(before,after,action(before,"tap","screen_changed"),true,false));
    }
}
