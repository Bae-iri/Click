package com.click.data.local;

import android.content.SharedPreferences;
import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** Account-bound encrypted preferences in production. No credentials, images or raw goals. */
public final class DeviceTaskJournal {
    private final SharedPreferences preferences;
    private final String prefix;
    private final String owner;
    private final java.util.function.Supplier<String> currentOwner;
    public DeviceTaskJournal(SharedPreferences preferences,String owner) {this(preferences,owner,()->owner);}
    public DeviceTaskJournal(SharedPreferences preferences,String owner,java.util.function.Supplier<String> currentOwner) {
        this.preferences=preferences;this.owner=owner;this.currentOwner=currentOwner;
        this.prefix=owner==null || owner.isEmpty()?null:"device_task_"+hash(owner);
    }
    private boolean owns(){return prefix!=null && owner.equals(currentOwner.get());}
    private static String hash(String value) {
        try {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private JSONObject read(String suffix) {
        if(!owns())return null;
        try {String raw=preferences.getString(prefix+suffix,null);return raw==null?null:new JSONObject(raw);}
        catch(JSONException corrupt){throw new IllegalStateException("진행 기록을 읽지 못했어요.",corrupt);}
    }
    private void write(String suffix,JSONObject value) throws IOException {
        if(!owns())throw new IOException("AUTH_REQUIRED");
        if(!preferences.edit().putString(prefix+suffix,value.toString()).commit())throw new IOException("진행 기록을 저장하지 못했어요.");
    }
    public synchronized boolean begin(String session,String episode,String guide,String operation) throws IOException {
        return begin(session,episode,guide,operation,null);
    }
    public synchronized boolean begin(String session,String episode,String guide,String operation,String attempt) throws IOException {
        return begin(session,episode,guide,operation,attempt,null,null);
    }
    public synchronized boolean begin(String session,String episode,String guide,String operation,String attempt,String baselineObservation,String dispatchObservation) throws IOException {
        if(session==null || episode==null || guide==null || operation==null)throw new IOException("실행 근거가 없어요.");
        JSONObject previous=read("_action");
        if(previous!=null && session.equals(previous.optString("session")) && episode.equals(previous.optString("episode"))
                && "issued".equals(previous.optString("status")))return false;
        try {write("_action",new JSONObject().put("session",session).put("episode",episode).put("guide",guide)
                .put("operation",hash(operation)).put("attempt",attempt).put("baseline_observation_id",baselineObservation).put("dispatch_observation_id",dispatchObservation).put("status","issued").put("created_at_ms",System.currentTimeMillis()));return true;}
        catch(JSONException invalid){throw new IOException(invalid);}
    }
    public synchronized void verified(String session,String guide) throws IOException {
        JSONObject action=read("_action");
        if(action==null || !session.equals(action.optString("session")) || !guide.equals(action.optString("guide")))return;
        try {action.put("status","verified");write("_action",action);}catch(JSONException invalid){throw new IOException(invalid);}
    }
    public synchronized void verifiedAttempt(String session,String episode,String attempt) throws IOException {
        if(session==null || episode==null || attempt==null || attempt.isEmpty())return;
        JSONObject action=read("_action");
        if(action==null || !session.equals(action.optString("session")) || !episode.equals(action.optString("episode"))
                || !attempt.equals(action.optString("attempt")))return;
        verified(session,action.optString("guide"));
    }
    public static boolean disruptive(String setting) {return Set.of("wifi","mobile_data","airplane_mode","hotspot","screen_lock","talkback").contains(setting==null?"":setting);}
    public synchronized void prepare(String session,String episode,String goal,String setting,String state) throws IOException {
        if(!disruptive(setting))return;
        JSONObject previous=read("_plan_"+hash(session));
        if(previous!=null && session.equals(previous.optString("session")) && goal.equals(previous.optString("goal")))return;
        try {write("_plan_"+hash(session),new JSONObject().put("session",session).put("episode",episode).put("goal",goal)
                .put("setting",setting).put("requested_state",state).put("created_at_ms",System.currentTimeMillis()));}
        catch(JSONException invalid){throw new IOException(invalid);}
    }
    public synchronized JSONObject pending(String session) {
        if(session==null)return null;
        JSONObject value=read("_outbox_"+hash(session));
        return value!=null && session.equals(value.optString("session_id"))?value:null;
    }
    public synchronized JSONObject plan(String session) {
        if(session==null)return null;
        JSONObject value=read("_plan_"+hash(session));return value!=null && session.equals(value.optString("session"))?value:null;
    }
    /** Already privacy-filtered public observation only; it can never be an action approval. */
    public synchronized void stage(JSONObject wire) throws IOException {
        String command=wire.optString("command_type");
        if(!Set.of("observe","request_result","respond_result").contains(command) || !wire.has("episode_id")
                || wire.has("source_text") || wire.has("raw_goal") || wire.has("user_confirmation") || wire.has("vision_snapshot"))return;
        JSONObject plan=plan(wire.optString("session_id"));if(plan==null)return;
        JSONObject event=wire.optJSONObject("user_action_event");
        if("observe".equals(command) && (event==null || !Set.of("tap_completed","drag_completed").contains(event.optString("action_type"))))return;
        if("respond_result".equals(command) && (!Set.of("satisfied","needs_help","stopped","no_response").contains(wire.optString("user_result"))
                || wire.optString("result_prompt_id").isEmpty()))return;
        if(wire.toString().length()>262144)throw new IOException("결과 기록이 너무 커서 자동으로 전송하지 않을게요.");
        JSONObject pending=pending(wire.optString("session_id"));
        if(pending!=null && !pending.optString("request_id").equals(wire.optString("request_id")))
            throw new IOException("결과 기록을 먼저 확인해 주세요.");
        write("_outbox_"+hash(wire.optString("session_id")),wire);
    }
    public synchronized void acknowledge(String session,String requestId) throws IOException {
        JSONObject wire=pending(session);if(wire==null || !requestId.equals(wire.optString("request_id")))return;
        if(!preferences.edit().remove(prefix+"_outbox_"+hash(session)).commit())throw new IOException("기록 확인을 저장하지 못했어요.");
    }
    public synchronized void finish(String session) throws IOException {
        JSONObject plan=plan(session);if(plan==null)return;
        if(!preferences.edit().remove(prefix+"_plan_"+hash(session)).remove(prefix+"_outbox_"+hash(session)).commit())throw new IOException("기록 확인을 저장하지 못했어요.");
    }
}
