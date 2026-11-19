package com.click.service.overlay;

import org.json.*;
import java.util.*;
import com.click.data.snapshot.AppTaskObservation;

/** Bounded task state; only the verifier or explicit user result can advance it. */
public final class AppTaskMemory {
    private final JSONObject data;
    public AppTaskMemory(String goal)throws JSONException {
        data=new JSONObject().put("task_id",UUID.randomUUID().toString()).put("goal",goal)
            .put("revision",0).put("subgoals",new JSONArray()).put("attempts",new JSONArray()).put("route",new JSONArray()).put("completed",false);
    }
    public AppTaskMemory(JSONObject stored)throws JSONException {data=new JSONObject(stored.toString());data.getString("task_id");data.getJSONArray("subgoals");data.getJSONArray("attempts");}
    public String goal(){return data.optString("goal");}
    public String id(){return data.optString("task_id");}
    public JSONObject stored()throws JSONException{return new JSONObject(data.toString());}
    public JSONObject state()throws JSONException{return new JSONObject().put("task_id",id()).put("revision",data.optInt("revision")).put("subgoals",data.getJSONArray("subgoals")).put("attempts",data.getJSONArray("attempts"));}
    public String active(){JSONArray goals=data.optJSONArray("subgoals");for(int i=0;goals!=null&&i<goals.length();i++)if("pending".equals(goals.optJSONObject(i).optString("status")))return goals.optJSONObject(i).optString("id");return null;}
    public void plan(JSONArray incoming)throws JSONException {
        if(incoming==null||incoming.length()==0||incoming.length()>8)return;
        JSONArray existing=data.getJSONArray("subgoals");
        if(existing.length()>0){
            if(existing.length()!=incoming.length())throw new JSONException("Changed task plan");
            for(int i=0;i<existing.length();i++)if(!existing.getJSONObject(i).getString("id").equals(incoming.getJSONObject(i).getString("id"))
                    ||!existing.getJSONObject(i).getString("title").equals(incoming.getJSONObject(i).getString("title")))throw new JSONException("Changed subgoal");
            return; // A model cannot mark any subgoal verified.
        }
        JSONArray clean=new JSONArray();for(int i=0;i<incoming.length();i++)clean.put(new JSONObject().put("id","g"+i).put("title",incoming.getJSONObject(i).getString("title")).put("status","pending"));
        data.put("subgoals",clean);
    }
    public void localPlan()throws JSONException {
        if(data.getJSONArray("subgoals").length()>0)return;
        String[] clauses=goal().split("\\s*(?:그리고|그다음|한 뒤|→|하고)\\s*");JSONArray plan=new JSONArray();
        for(String clause:clauses){String title=clause.trim();if(title.isEmpty())continue;plan.put(new JSONObject().put("title",title.substring(0,Math.min(80,title.length()))));if(plan.length()==8)break;}
        if(plan.length()==0)plan.put(new JSONObject().put("title",goal().substring(0,Math.min(80,goal().length()))));plan(plan);
    }
    public String activeTitle(){String id=active();JSONArray goals=data.optJSONArray("subgoals");for(int i=0;goals!=null&&i<goals.length();i++)if(goals.optJSONObject(i).optString("id").equals(id))return goals.optJSONObject(i).optString("title");return goal();}
    public void record(JSONObject before,JSONObject action,String outcome,boolean completesSubgoal)throws JSONException {
        data.put("action_count",actions()+1);
        JSONObject row=new JSONObject().put("fingerprint",before.getString("fingerprint")).put("tool",action.getString("tool"))
            .put("target",target(action)).put("outcome",outcome);
        JSONArray attempts=data.getJSONArray("attempts");attempts.put(row);while(attempts.length()>8)attempts.remove(0);
        if("failed".equals(outcome))data.put("revision",data.optInt("revision")+1);
        if("verified".equals(outcome)) {
            JSONObject node=AppTaskObservation.find(before,action.optString("target_id"));
            if(data.getJSONArray("route").length()<32)data.getJSONArray("route").put(new JSONObject().put("package",before.optString("package")).put("tool",action.getString("tool"))
                .put("resource",node==null?"":node.optString("resource")).put("label",node==null?"":node.optString("label"))
                .put("open_package",action.optString("package")).put("expectation",action.getJSONObject("expectation"))
                .put("advances_subgoal",action.optBoolean("advances_subgoal")));
            if(completesSubgoal){String id=active();JSONArray goals=data.getJSONArray("subgoals");for(int i=0;i<goals.length();i++)if(goals.getJSONObject(i).getString("id").equals(id))goals.getJSONObject(i).put("status","verified");}
        }
    }
    private static String target(JSONObject action){return "open_app".equals(action.optString("tool"))?action.optString("package",""):action.isNull("target_id")?"":action.optString("target_id","");}
    public boolean repeated(JSONObject before,JSONObject action){int count=0;JSONArray attempts=data.optJSONArray("attempts");for(int i=0;attempts!=null&&i<attempts.length();i++){JSONObject a=attempts.optJSONObject(i);if("failed".equals(a.optString("outcome"))&&before.optString("fingerprint").equals(a.optString("fingerprint"))&&action.optString("tool").equals(a.optString("tool"))&&target(action).equals(a.optString("target")))count++;}return count>=2;}
    public boolean finished(){return data.optBoolean("completed");}
    public int actions(){return data.optInt("action_count");}
    public String scope(){return data.optString("route_scope",null);}
    public void scope(String scope)throws JSONException{data.put("route_scope",scope);}
    public void confirmResult()throws JSONException{data.put("completed",true);}
    public JSONArray route(){return data.optJSONArray("route");}
}
