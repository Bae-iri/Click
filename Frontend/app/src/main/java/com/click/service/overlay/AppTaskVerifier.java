package com.click.service.overlay;

import com.click.data.snapshot.AppTaskObservation;
import org.json.*;

/** Native observations, never model confidence, determine step success. */
public final class AppTaskVerifier {
    public enum Verdict { VERIFIED, WAITING, UNKNOWN, PROTECTED, STALE }
    public static Verdict check(JSONObject before,JSONObject after,JSONObject action,boolean actionObserved,boolean inputMatches){
        return check(before,after,action,actionObserved,inputMatches,0);
    }
    public static Verdict check(JSONObject before,JSONObject after,JSONObject action,boolean actionObserved,boolean inputMatches,long actionAt){
        if(before==null||after==null||!AppTaskPolicy.validExpectation(action))return Verdict.UNKNOWN;
        if(!actionObserved)return Verdict.WAITING;
        if(before.optString("observation_id").isEmpty()||after.optString("observation_id").isEmpty()
                ||before.optString("observation_id").equals(after.optString("observation_id"))
                ||after.optLong("captured_at_ms")<before.optLong("captured_at_ms")
                ||actionAt>0&&(before.optLong("captured_at_ms")>actionAt||after.optLong("captured_at_ms")<actionAt))return Verdict.UNKNOWN;
        JSONObject expectation=action.optJSONObject("expectation");if(expectation==null)return Verdict.WAITING;
        String expectedScreen=expectation.optString("screen_key");
        if(!expectedScreen.isEmpty())try{if(!expectedScreen.equals(AppTaskObservation.routeKey(after)))return Verdict.WAITING;}catch(JSONException invalid){return Verdict.WAITING;}
        String kind=expectation.optString("kind");
        if("package_opened".equals(kind))return expectation.optString("package").equals(after.optString("package"))?Verdict.VERIFIED:Verdict.WAITING;
        if("protected".equals(after.optString("status")))return Verdict.PROTECTED;
        if(!"public".equals(after.optString("status")))return Verdict.UNKNOWN;
        if(!before.optString("package").equals(after.optString("package"))&&!"back".equals(action.optString("tool")))return Verdict.STALE;
        JSONObject old=AppTaskObservation.find(before,action.optString("target_id")),current=resolveTarget(before,after,action.optString("target_id"));
        if("input_present".equals(kind))return current==null?Verdict.UNKNOWN:inputMatches&&current.optBoolean("editable")&&current.optBoolean("has_value")?Verdict.VERIFIED:Verdict.WAITING;
        if("selection_changed".equals(kind)){
            if(old==null||current==null)return Verdict.UNKNOWN;
            boolean explicit=!expectation.isNull("checked")||!expectation.isNull("selected");
            for(String state:java.util.List.of("checked","selected"))if(!expectation.isNull(state)){
                if(!(current.opt(state) instanceof Boolean))return Verdict.UNKNOWN;
                if(!expectation.opt(state).equals(current.opt(state)))return Verdict.WAITING;
            }
            return (explicit||old.optBoolean("selected")!=current.optBoolean("selected")
                ||!String.valueOf(old.opt("checked")).equals(String.valueOf(current.opt("checked"))))?Verdict.VERIFIED:Verdict.WAITING;
        }
        if("back".equals(action.optString("tool"))&&before.optBoolean("keyboard_visible")&&!after.optBoolean("keyboard_visible"))return Verdict.VERIFIED;
        if(action.optString("tool").startsWith("scroll_")&&"screen_changed".equals(kind)){
            if(old==null||current==null)return Verdict.UNKNOWN;
            return !old.optString("viewport_hash").isEmpty()&&!current.optString("viewport_hash").isEmpty()
                &&!old.optString("viewport_hash").equals(current.optString("viewport_hash"))?Verdict.VERIFIED:Verdict.WAITING;
        }
        if(before.optString("fingerprint").equals(after.optString("fingerprint")))return Verdict.WAITING;
        if("screen_changed".equals(kind))try{if(AppTaskObservation.learningKey(before).equals(AppTaskObservation.learningKey(after)))return Verdict.WAITING;}catch(JSONException invalid){return Verdict.WAITING;}
        if("target_visible".equals(kind)){
            String label=expectation.optString("label");if(label.isEmpty())return Verdict.WAITING;
            JSONArray nodes=after.optJSONArray("nodes");for(int i=0;nodes!=null&&i<nodes.length();i++)if(label.equals(nodes.optJSONObject(i).optString("label"))){
                JSONObject next=nodes.optJSONObject(i);boolean existed=false;JSONArray previous=before.optJSONArray("nodes");
                for(int j=0;previous!=null&&j<previous.length();j++){JSONObject n=previous.optJSONObject(j);if(label.equals(n.optString("label"))&&next.optString("resource").equals(n.optString("resource"))&&next.optString("role").equals(n.optString("role")))existed=true;}
                if(!existed)return Verdict.VERIFIED;
            }
            return Verdict.WAITING;
        }
        return "screen_changed".equals(kind)?Verdict.VERIFIED:Verdict.WAITING;
    }
    /** Native resource + role + label survive redraw; duplicate selectors are never guessed. */
    public static JSONObject resolveTarget(JSONObject before,JSONObject after,String target){
        if(before==null||after==null)return null;
        JSONObject old=AppTaskObservation.find(before,target);if(old==null)return null;
        JSONObject match=null;JSONArray nodes=after.optJSONArray("nodes");
        for(int i=0;nodes!=null&&i<nodes.length();i++){
            JSONObject n=nodes.optJSONObject(i);if(n==null||!old.optString("role").equals(n.optString("role"))
                    ||!old.optString("resource").equals(n.optString("resource"))||!old.optString("label").equals(n.optString("label"))
                    ||!old.optString("source","accessibility").equals(n.optString("source","accessibility")))continue;
            if(old.optString("resource").isEmpty()&&(!target.equals(n.optString("id"))
                    ||!String.valueOf(old.optJSONArray("bounds")).equals(String.valueOf(n.optJSONArray("bounds")))))continue;
            if(match!=null)return null;match=n;
        }
        if(match!=null&&!old.optString("resource").isEmpty()){
            int count=0;JSONArray previous=before.optJSONArray("nodes");
            for(int i=0;previous!=null&&i<previous.length();i++){JSONObject n=previous.optJSONObject(i);
                if(n!=null&&old.optString("resource").equals(n.optString("resource"))&&old.optString("role").equals(n.optString("role"))&&old.optString("label").equals(n.optString("label")))count++;}
            if(count!=1)return null;
        }
        return match;
    }
}
