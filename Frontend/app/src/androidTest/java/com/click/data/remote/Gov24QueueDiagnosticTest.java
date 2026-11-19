package com.click.data.remote;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.BuildConfig;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in read-only queue check; no request text, identifiers or credentials leave the phone. */
@RunWith(AndroidJUnit4.class)
public class Gov24QueueDiagnosticTest {
    @Test public void inspectWhetherTheLoginSmokeTestOwnsTheRunningRequest() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("gov24QueueInspect")));
        org.junit.Assume.assumeTrue("com.clueclick.app".equals(BuildConfig.APPLICATION_ID));
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var http=new AuthenticatedHttpClient(instrumentation.getTargetContext());
        var queue=new JSONObject(http.get("/api/v1/voice-requests").body);
        var running=queue.optJSONObject("running");var queued=queue.optJSONArray("queued");
        String expected="정부24 로그인 도와줘";int matchingQueued=0;
        var times=new org.json.JSONArray();
        for(int i=0;queued!=null&&i<queued.length();i++)if(expected.equals(queued.getJSONObject(i).optString("raw_goal"))) {
            matchingQueued++;times.put(queued.getJSONObject(i).optLong("created_at"));
        }
        var report=new JSONObject().put("running_present",running!=null)
            .put("running_matches_login_smoke_test",running!=null&&expected.equals(running.optString("raw_goal")))
            .put("queued_count",queued==null?0:queued.length()).put("matching_smoke_test_queued",matchingQueued)
            .put("matching_created_at",times);
        var result=new android.os.Bundle();result.putString("gov24_queue_probe",report.toString());instrumentation.sendStatus(0,result);
    }
}
