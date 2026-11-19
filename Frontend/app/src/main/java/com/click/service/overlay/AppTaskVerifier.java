package com.click.service.overlay;

import com.click.data.snapshot.AppTaskObservation;
import org.json.*;

/** Native observations, never model confidence, determine step success. */
public final class AppTaskVerifier {
    public enum Verdict { VERIFIED, WAITING, PROTECTED, STALE }
    public static Verdict check(JSONObject before,JSONObject after,JSONObject action,boolean actionObserved,boolean inputMatches){
        if(after==null||!actionObserved||!AppTaskPolicy.validExpectation(action))return Verdict.WAITING;
        JSONObject expectation=action.optJSONObject("expectation");if(expectation==null)return Verdict.WAITING;
        String kind=expectation.optString("kind");
        if("package_opened".equals(kind))return expectation.optString("package").equals(after.optString("package"))?Verdict.VERIFIED:Verdict.WAITING;
        if("protected".equals(after.optString("status")))return Verdict.PROTECTED;
        if(!"public".equals(after.optString("status")))return Verdict.WAITING;
        if(!before.optString("package").equals(after.optString("package"))&&!"back".equals(action.optString("tool")))return Verdict.STALE;
        JSONObject old=AppTaskObservation.find(before,action.optString("target_id")),current=AppTaskObservation.find(after,action.optString("target_id"));
        if("input_present".equals(kind))return inputMatches&&sameTarget(old,current)&&current.optBoolean("editable")&&current.optBoolean("has_value")?Verdict.VERIFIED:Verdict.WAITING;
        if("selection_changed".equals(kind))return sameTarget(old,current)
            &&(old.optBoolean("selected")!=current.optBoolean("selected")||!String.valueOf(old.opt("checked")).equals(String.valueOf(current.opt("checked"))))?Verdict.VERIFIED:Verdict.WAITING;
        if("back".equals(action.optString("tool"))&&before.optBoolean("keyboard_visible")&&!after.optBoolean("keyboard_visible"))return Verdict.VERIFIED;
        if(before.optString("fingerprint").equals(after.optString("fingerprint")))return Verdict.WAITING;
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
    private static boolean sameTarget(JSONObject a,JSONObject b){return a!=null&&b!=null&&a.optString("resource").equals(b.optString("resource"))&&a.optString("role").equals(b.optString("role"))&&String.valueOf(a.optJSONArray("bounds")).equals(String.valueOf(b.optJSONArray("bounds")));}
}
