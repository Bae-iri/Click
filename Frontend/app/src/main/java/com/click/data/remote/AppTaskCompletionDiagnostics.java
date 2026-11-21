package com.click.data.remote;

import android.content.Context;
import com.click.data.local.CandidateDiagnosticLog;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** Separate device-local completion decisions. Never stores selectors, bodies or errors. */
public final class AppTaskCompletionDiagnostics {
    public static final String VERSION="app-task-completion-event.v1";
    private static final int MAX_BYTES=256*1024;
    private static final Set<String> STAGES=Set.of("response","candidate_accepted","candidate_rejected","goal_check","evidence","verified","discarded","unavailable","unknown");
    private static final Set<String> REASONS=Set.of("received","closed","finishing","not_observable","no_frame","changed_frame","changed_context",
        "missing_candidate","invalid_contract","unsafe_for_goal","same_candidate","immutable_finished","immutable_supported",
        "version_limit","replacement_unmatched","requirements_not_preserved","initial_prediction","observed_prediction_refined",
        "candidate_missing","ignored_frame","checked","first_frame","waiting_second_frame","second_frame",
        "support_rejected","fresh_native_observations","fresh_frame_unavailable","unsafe_prediction_discarded","unknown");
    private static final Set<String> VERDICTS=Set.of("matched","not_met","unknown","protected","not_checked");
    private static final Set<String> FRESHNESS=Set.of("fresh","future","stale","not_checked");
    private final File directory;
    public AppTaskCompletionDiagnostics(Context context){directory=new File(context.getApplicationContext().getNoBackupFilesDir(),"app-task-completion-diagnostics");}

    /** requestRef is already hashed; candidateKey is the canonical SHA256, never selector JSON. */
    public static JSONObject event(String taskId,String requestRef,String candidateKey,JSONObject observed,ScreenSnapshot nativeScreen,
            int version,int checkCount,String stage,String reason,String verdict,String freshness,Long ageMs,long elapsedMs,int frames){
        JSONObject out=new JSONObject();
        try{
            String window=nativeScreen==null?null:String.valueOf(nativeScreen.activeWindowId)+"|"+nativeScreen.windowContext.toJson();
            out.put("task_ref",nullable(AppTaskDiagnostics.ref(taskId))).put("request_ref",nullable(reference(requestRef)))
                .put("candidate_ref",nullable(candidateKey!=null&&candidateKey.matches("[0-9a-f]{64}")?candidateKey.substring(0,24):null))
                .put("observation_ref",nullable(AppTaskDiagnostics.ref(observed==null?null:observed.optString("observation_id"))))
                .put("window_ref",nullable(AppTaskDiagnostics.ref(window))).put("evidence_version",version).put("check_count",checkCount)
                .put("node_count",observed==null||observed.optJSONArray("nodes")==null?0:observed.optJSONArray("nodes").length())
                .put("stage",stage).put("reason",reason).put("verdict",verdict).put("freshness",freshness)
                .put("observation_age_ms",ageMs==null?JSONObject.NULL:ageMs).put("elapsed_ms",elapsedMs).put("frame_count",frames);
        }catch(Exception ignored){ /* A diagnostic cannot change a completion decision. */ }
        return sanitize(out);
    }

    /** Rebuild even internal input from closed fields; arbitrary extra JSON can never reach disk. */
    public static JSONObject sanitize(JSONObject source){
        JSONObject out=new JSONObject();
        try{
            out.put("schema_version",VERSION).put("raw_content_saved",false);
            for(String key:Set.of("task_ref","request_ref","candidate_ref","observation_ref","window_ref"))
                out.put(key,nullable(reference(source.optString(key))));
            out.put("stage",closed(source,"stage",STAGES,"unknown")).put("reason",closed(source,"reason",REASONS,"unknown"))
                .put("verdict",closed(source,"verdict",VERDICTS,"not_checked")).put("freshness",closed(source,"freshness",FRESHNESS,"not_checked"));
            for(String key:Set.of("evidence_version","check_count","node_count","frame_count","elapsed_ms")){
                int limit=key.equals("check_count")?8:key.equals("node_count")?80:key.equals("frame_count")?2:key.equals("evidence_version")?128:120000;
                out.put(key,number(source.opt(key),0,limit));
            }
            out.put("observation_age_ms",number(source.opt("observation_age_ms"),-120000,120000));
        }catch(Exception ignored){ }
        return out;
    }
    private static Object number(Object value,long low,long high){
        if(!(value instanceof Number))return JSONObject.NULL;
        double n=((Number)value).doubleValue();
        return Double.isFinite(n)&&n>=low&&n<=high&&n==Math.rint(n)?((Number)value).longValue():JSONObject.NULL;
    }
    private static String closed(JSONObject source,String key,Set<String> values,String fallback){String value=source.optString(key);return values.contains(value)?value:fallback;}
    private static String reference(String value){return value!=null&&value.matches("[0-9a-f]{24}")?value:null;}
    private static Object nullable(String value){return value==null?JSONObject.NULL:value;}

    public void record(JSONObject event){
        if(!CandidateDiagnosticLog.enabled())return;
        try{
            JSONObject safe=sanitize(event).put("recorded_at_ms",System.currentTimeMillis());
            byte[] bytes=(safe+"\n").getBytes(StandardCharsets.UTF_8);if(bytes.length>2048)return;
            synchronized(AppTaskCompletionDiagnostics.class){
                Files.createDirectories(directory.toPath());File current=new File(directory,"current.jsonl");
                if(current.length()+bytes.length>MAX_BYTES)Files.move(current.toPath(),new File(directory,"previous.jsonl").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.write(current.toPath(),bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }
        }catch(Exception ignored){ /* Full storage or diagnostics cannot break a task. */ }
    }
}
