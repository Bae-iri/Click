package com.click.service.overlay;

import com.click.data.snapshot.AppTaskObservation;
import org.json.*;
import java.util.*;

/** Goal postconditions are rechecked on the current screen, never inferred from a click or plan bits. */
public final class AppTaskCompletion {
    public enum Verdict { MATCHED, NOT_MET, UNKNOWN, PROTECTED }
    private static final Set<String> FIELDS=Set.of("package","label","resource","role","checked","selected");
    private AppTaskCompletion() {}

    public static boolean valid(JSONObject completion) {
        if(completion==null||completion.length()!=1)return false;
        JSONArray checks=completion.optJSONArray("checks");
        if(checks==null||checks.length()<1||checks.length()>8)return false;
        String packageName=null;
        for(int i=0;i<checks.length();i++){
            JSONObject c=checks.optJSONObject(i);if(c==null)return false;
            for(Iterator<String> k=c.keys();k.hasNext();)if(!FIELDS.contains(k.next()))return false;
            for(String f:List.of("package","label","resource","role"))if(c.has(f)&&!(c.opt(f) instanceof String))return false;
            if(!c.optString("package").matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")
                    ||c.optString("package").length()>200||c.optString("resource").length()>300)return false;
            if(packageName!=null&&!packageName.equals(c.optString("package")))return false;
            packageName=c.optString("package");
            String label=c.optString("label"),role=c.optString("role");
            if(label.isEmpty()&&c.optString("resource").isEmpty()||!label.equals(AppTaskObservation.label(label))
                    ||!Set.of("","button","text_field","list","tab","toggle","text","image","unknown").contains(role))return false;
            if(!c.isNull("checked")&&(!(c.opt("checked") instanceof Boolean)||!"toggle".equals(role)))return false;
            if(!c.isNull("selected")&&(!(c.opt("selected") instanceof Boolean)||!Set.of("tab","toggle","button").contains(role)))return false;
        }
        return true;
    }

    public static Set<String> labels(JSONObject completion) {
        Set<String> labels=new HashSet<>();
        if(valid(completion)){JSONArray checks=completion.optJSONArray("checks");for(int i=0;i<checks.length();i++){
            String label=checks.optJSONObject(i).optString("label");if(!label.isEmpty())labels.add(label);
        }}
        return labels;
    }

    public static String key(JSONObject completion) {
        List<String> checks=new ArrayList<>();JSONArray all=completion.optJSONArray("checks");
        for(int i=0;all!=null&&i<all.length();i++){
            JSONObject c=all.optJSONObject(i);StringBuilder key=new StringBuilder();
            for(String f:List.of("package","label","resource","role","checked","selected"))key.append(c.isNull(f)?"":c.optString(f)).append('\n');
            checks.add(key.toString());
        }
        Collections.sort(checks);return AppTaskObservation.hash(String.join("\n",checks));
    }

    public static Verdict check(JSONObject completion,JSONObject screen) {
        if(!valid(completion)||screen==null)return Verdict.UNKNOWN;
        if("protected".equals(screen.optString("status")))return Verdict.PROTECTED;
        if(!"public".equals(screen.optString("status")))return Verdict.UNKNOWN;
        JSONArray checks=completion.optJSONArray("checks"),nodes=screen.optJSONArray("nodes");
        if(nodes==null)return Verdict.UNKNOWN;
        for(int i=0;i<checks.length();i++){
            JSONObject c=checks.optJSONObject(i),found=null;
            if(!c.optString("package").equals(screen.optString("package")))return Verdict.NOT_MET;
            for(int j=0;j<nodes.length();j++){
                JSONObject n=nodes.optJSONObject(j);
                if(n==null||!"accessibility".equals(n.optString("source")))continue;
                boolean matches=true;for(String key:List.of("label","resource","role"))
                    if(!c.optString(key).isEmpty()&&!c.optString(key).equals(n.optString(key)))matches=false;
                if(!matches)continue;
                if(found!=null)return Verdict.UNKNOWN;
                found=n;
            }
            if(found==null)return Verdict.NOT_MET;
            for(String state:List.of("checked","selected"))if(!c.isNull(state)){
                if(!(found.opt(state) instanceof Boolean))return Verdict.UNKNOWN;
                if(!c.opt(state).equals(found.opt(state)))return Verdict.NOT_MET;
            }
        }
        return Verdict.MATCHED;
    }
}
