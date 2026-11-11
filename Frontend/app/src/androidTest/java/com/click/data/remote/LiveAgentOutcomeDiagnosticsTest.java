package com.click.data.remote;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assume.assumeTrue;

/** Explicitly opt-in, read-only outcome diagnostics. No text, account data, tokens or screens are exported. */
@RunWith(AndroidJUnit4.class)
public class LiveAgentOutcomeDiagnosticsTest {
 @Test public void readBoundedRecentOutcomes() throws Exception {
  assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("readAgentOutcomes")));
  Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
  AuthenticatedHttpClient http=new AuthenticatedHttpClient(app);
  JSONObject report=new JSONObject().put("scope","read_only_agent_outcomes");
  try {
   ArrayList<JSONObject> records=new ArrayList<>();String cursor=null;
   for(int page=0;page<5;page++) {
    JSONObject history=new JSONObject(http.get("/api/v1/agent/sessions?limit=100"+(cursor==null?"":"&after="+cursor)).body);
    JSONArray items=history.getJSONArray("items");
    for(int i=0;i<items.length();i++) {
     JSONObject raw=items.getJSONObject(i);JSONObject safe=new JSONObject();
     for(String key:new String[]{"status","task_id","state_id"}) {
      String value=raw.optString(key,"");if(value.matches("[A-Za-z0-9_:\u002d]{1,100}"))safe.put(key,value);
     }
     safe.put("updated_at",raw.optLong("updated_at"));
     JSONObject outcome=raw.optJSONObject("task_outcome"),proof=outcome==null?null:outcome.optJSONObject("technical");
     if(proof!=null) {
      JSONObject bounded=new JSONObject();
      for(String key:new String[]{"status","reason","setting"}) {
       String value=proof.optString(key,"");if(value.matches("[A-Za-z0-9_]{1,100}"))bounded.put(key,value);
      }
      for(String key:new String[]{"baseline_value","after_value","baseline_setting_value","setting_value","action_at_ms","verified_at_ms"}) {
       Object value=proof.opt(key);if(value instanceof Number)bounded.put(key,value);
      }
      safe.put("technical",bounded);
     }
     records.add(safe);
    }
    cursor=history.optString("next_cursor",null);if(cursor==null || cursor.equals("null") || cursor.isEmpty())break;
   }
   records.sort(Comparator.comparingLong((JSONObject r)->r.optLong("updated_at")).reversed());
   JSONArray latest=new JSONArray();for(int i=0;i<Math.min(16,records.size());i++)latest.put(records.get(i));
   report.put("recent",latest).put("records_scanned",records.size()).put("passed",true);
  }catch(ApiException failure){report.put("http_status",failure.status).put("passed",false);}
  finally {
   Files.write(new File(app.getCacheDir(),"agent-outcome-diagnostics.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
  }
 }
}
