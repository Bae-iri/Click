package com.click.data.local;

import android.content.SharedPreferences;
import com.click.service.overlay.AppTaskMemory;
import com.click.data.snapshot.AppTaskObservation;
import org.json.*;

/** User-scoped encrypted task checkpoints and twice-confirmed semantic routes. */
public final class AppTaskStore {
    private static final Object EXPERIENCE_LOCK=new Object();
    private final SharedPreferences prefs;private final String user;private final java.util.function.Supplier<String> currentUser;
    AppTaskStore(SharedPreferences prefs,String user,java.util.function.Supplier<String> currentUser){this.prefs=prefs;this.user=user;this.currentUser=currentUser;}
    private boolean owned(){return user!=null&&!user.isEmpty()&&user.equals(currentUser.get());}
    public static String consentKey(String user){return "app_task_consent_"+AppTaskObservation.hash(user==null?"":user);}
    public boolean learningDisabled(){return !owned()||prefs.contains(consentKey(user))&&!prefs.getBoolean(consentKey(user),false);}
    private String key(String goal){return "app_task_v2_"+AppTaskObservation.hash(user+"|"+goal);}
    public AppTaskMemory restore(String goal)throws JSONException {
        if(!owned())return new AppTaskMemory(goal);
        String raw=prefs.getString(key(goal),null);
        if(raw!=null)try{JSONObject stored=new JSONObject(raw);if(System.currentTimeMillis()-stored.optLong("saved_at")<86400000L){AppTaskMemory memory=new AppTaskMemory(stored.getJSONObject("memory"));if(!memory.finished()&&goal.equals(memory.goal()))return memory;}}catch(Exception ignored){}
        return new AppTaskMemory(goal);
    }
    public void save(AppTaskMemory memory){if(!owned())return;try{prefs.edit().putString(key(memory.goal()),new JSONObject().put("saved_at",System.currentTimeMillis()).put("memory",memory.stored()).toString()).apply();}catch(Exception ignored){}}
    public void recordRoute(AppTaskMemory memory,String scope){
        if(!owned()||!memory.finished()||memory.active()!=null||memory.route()==null||memory.route().length()==0||memory.route().length()>=32||memory.actions()!=memory.route().length())return;
        try{
            String k=key(memory.goal())+"_route_"+AppTaskObservation.hash(scope);String raw=prefs.getString(k,null);JSONObject previous=raw==null?new JSONObject():new JSONObject(raw);
            if(memory.id().equals(previous.optString("last_task")))return;
            String hash=AppTaskObservation.hash(memory.route().toString());int support=hash.equals(previous.optString("hash"))&&!memory.id().equals(previous.optString("last_task"))?previous.optInt("support")+1:1;
            prefs.edit().putString(k,new JSONObject().put("hash",hash).put("support",support).put("last_task",memory.id()).put("route",memory.route()).put("subgoals",memory.state().getJSONArray("subgoals")).put("saved_at",System.currentTimeMillis()).toString()).apply();
        }catch(Exception ignored){}
    }
    public JSONObject reusable(String goal,String scope){if(!owned())return null;try{String raw=prefs.getString(key(goal)+"_route_"+AppTaskObservation.hash(scope),null);if(raw==null)return null;JSONObject r=new JSONObject(raw);return r.optInt("support")>=2&&System.currentTimeMillis()-r.optLong("saved_at")<604800000L?r:null;}catch(Exception ignored){return null;}}
    public void invalidateRoute(String goal,String scope){if(owned()&&scope!=null)prefs.edit().remove(key(goal)+"_route_"+AppTaskObservation.hash(scope)).apply();}
    private String experienceKey(){return "app_task_experience_"+AppTaskObservation.hash(user==null?"":user);}
    private JSONArray experiences()throws JSONException {
        JSONArray saved=new JSONArray(prefs.getString(experienceKey(),"[]")),fresh=new JSONArray();long now=System.currentTimeMillis();
        for(int i=0;i<saved.length();i++){JSONObject row=saved.getJSONObject(i);long age=now-row.getJSONObject("event").optLong("observed_at_ms");if(age>=-60000&&age<86400000L)fresh.put(row);}return fresh;
    }
    /** One preference edit atomically checkpoints sequence and its durable outbox row. */
    public void saveExperience(AppTaskMemory memory,String session,String consent,JSONObject event){synchronized(EXPERIENCE_LOCK){
        if(learningDisabled()||session==null||consent==null)return;
        try{JSONArray queue=experiences();queue.put(new JSONObject().put("session_id",session).put("consent_id",consent).put("event",event));
            while(queue.length()>256){String task=queue.getJSONObject(0).getJSONObject("event").getString("task_id");JSONArray kept=new JSONArray();for(int i=0;i<queue.length();i++)if(!task.equals(queue.getJSONObject(i).getJSONObject("event").getString("task_id")))kept.put(queue.getJSONObject(i));queue=kept;}
            prefs.edit().putString(experienceKey(),queue.toString()).putString(key(memory.goal()),new JSONObject().put("saved_at",System.currentTimeMillis()).put("memory",memory.stored()).toString()).apply();
        }catch(Exception ignored){}
    }}
    public JSONObject experienceBatch()throws JSONException {synchronized(EXPERIENCE_LOCK){
        if(learningDisabled())return null;JSONArray queue=experiences();if(queue.length()==0)return null;JSONObject first=queue.getJSONObject(0);
        JSONObject batch=new JSONObject().put("protocol_version","app-task-experience-batch.v1").put("session_id",first.getString("session_id")).put("consent_id",first.getString("consent_id")).put("events",new JSONArray());
        for(int i=0;i<queue.length()&&batch.getJSONArray("events").length()<20;i++){JSONObject row=queue.getJSONObject(i);if(first.getString("session_id").equals(row.getString("session_id"))&&first.getString("consent_id").equals(row.getString("consent_id")))batch.getJSONArray("events").put(row.getJSONObject("event"));}
        return batch;
    }}
    public void acknowledgeExperiences(JSONArray ids){synchronized(EXPERIENCE_LOCK){
        if(!owned())return;try{java.util.Set<String> ack=new java.util.HashSet<>();for(int i=0;i<ids.length();i++)ack.add(ids.getString(i));JSONArray queue=experiences(),kept=new JSONArray();for(int i=0;i<queue.length();i++)if(!ack.contains(queue.getJSONObject(i).getJSONObject("event").getString("event_id")))kept.put(queue.getJSONObject(i));prefs.edit().putString(experienceKey(),kept.toString()).apply();}catch(Exception ignored){}
    }}
    public void retainExperienceConsent(String consent){synchronized(EXPERIENCE_LOCK){
        if(!owned())return;try{JSONArray queue=experiences(),kept=new JSONArray();for(int i=0;i<queue.length();i++)if(consent!=null&&consent.equals(queue.getJSONObject(i).optString("consent_id")))kept.put(queue.getJSONObject(i));prefs.edit().putString(experienceKey(),kept.toString()).apply();}catch(Exception ignored){}
    }}
}
