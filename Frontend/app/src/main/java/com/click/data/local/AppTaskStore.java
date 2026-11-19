package com.click.data.local;

import android.content.SharedPreferences;
import com.click.service.overlay.AppTaskMemory;
import com.click.data.snapshot.AppTaskObservation;
import org.json.*;

/** User-scoped encrypted task checkpoints and twice-confirmed semantic routes. */
public final class AppTaskStore {
    private final SharedPreferences prefs;private final String user;private final java.util.function.Supplier<String> currentUser;
    AppTaskStore(SharedPreferences prefs,String user,java.util.function.Supplier<String> currentUser){this.prefs=prefs;this.user=user;this.currentUser=currentUser;}
    private boolean owned(){return user!=null&&!user.isEmpty()&&user.equals(currentUser.get());}
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
}
