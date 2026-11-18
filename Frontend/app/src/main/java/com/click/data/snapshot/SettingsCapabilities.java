package com.click.data.snapshot;

import com.click.generated.SettingsCapabilitiesData;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;

/** Generated from approved low-risk graph navigation; never grants value-control permission. */
public final class SettingsCapabilities {
    private static final JSONObject DATA=load();
    private static final Set<String> LABELS=labels();
    private static final String HASH=computeHash();
    private static Set<String> labels(){Set<String> out=new HashSet<>();JSONArray labels=DATA.optJSONArray("navigation_labels");for(int i=0;i<labels.length();i++)out.add(canonical(labels.optString(i)));return Collections.unmodifiableSet(out);}
    private SettingsCapabilities() {}
    private static JSONObject load() {
        try { JSONObject data=new JSONObject(SettingsCapabilitiesData.json());
            if(!"settings-capabilities.v1".equals(data.getString("schema_version")))throw new IllegalStateException("Unsupported capability contract");
            return data;
        } catch(JSONException error){throw new IllegalStateException("Invalid capability contract",error);}
    }
    private static boolean contains(String key,String value) {
        JSONArray entries=DATA.optJSONArray(key);if(value==null || entries==null)return false;
        for(int i=0;i<entries.length();i++)if(value.equals(entries.optString(i)))return true;return false;
    }
    public static String normalize(String text){return text==null?"":text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
    public static String canonical(String text){String n=normalize(text);return DATA.optJSONObject("label_aliases").optString(n,n);}
    public static boolean navigationLabel(String text) {
        return LABELS.contains(canonical(text));
    }
    public static boolean navigationState(String state){return contains("navigation_states",state) || state!=null && contains("navigation_states",DATA.optJSONObject("state_aliases").optString(state,""));}
    public static boolean settingsPackage(String pkg){return contains("settings_packages",pkg);}
    public static boolean privateIdentity(String resource) {
        if(resource==null)return false;String name=normalize(resource.substring(resource.lastIndexOf('/')+1));
        JSONArray tokens=DATA.optJSONArray("private_identity_tokens");for(int i=0;i<tokens.length();i++)if(name.contains(tokens.optString(i)))return true;return false;
    }
    public static boolean risky(String text) {
        String value=normalize(text);JSONArray tokens=DATA.optJSONArray("blocked_label_tokens");
        for(int i=0;i<tokens.length();i++)if(value.contains(tokens.optString(i)))return true;return false;
    }
    public static int maxMoves(boolean launcher){return DATA.optJSONObject("search_budgets").optJSONObject(launcher?"launcher":"settings").optInt("max_moves");}
    public static long maxDuration(boolean launcher){return DATA.optJSONObject("search_budgets").optJSONObject(launcher?"launcher":"settings").optLong("max_duration_ms");}
    public static String targetLabel(String goal,String state) {
        JSONObject spec=DATA.optJSONObject("goals").optJSONObject(goal==null?"":goal);
        JSONObject routes=spec==null?null:spec.optJSONObject("navigation");JSONArray edges=routes==null?null:routes.optJSONArray(state==null?"":state);
        if(edges==null || edges.length()==0)return null;
        String authored=edges.optJSONObject(0).optString("label","");if(!authored.isEmpty())return authored;
        JSONArray names=edges.optJSONObject(0).optJSONArray("labels");if(names==null || names.length()==0)return null;
        // Prefer a Korean authored menu name for instructions.
        for(int i=0;i<names.length();i++)if(names.optString(i).matches(".*[가-힣].*"))return names.optString(i);
        return names.optString(0);
    }
    public static boolean pageTitle(String goal,String state,String text) {
        JSONObject spec=DATA.optJSONObject("goals").optJSONObject(goal==null?"":goal);
        JSONObject titles=spec==null?null:spec.optJSONObject("page_titles");JSONArray groups=titles==null?null:titles.optJSONArray(state==null?"":state);
        if(groups==null || groups.length()==0)return false;
        for(int i=0;i<groups.length();i++){boolean found=false;JSONArray group=groups.optJSONArray(i);for(int j=0;j<group.length();j++)found|=canonical(text).equals(canonical(group.optString(j)));if(!found)return false;}
        return true;
    }
    public static boolean splitTitle(String resource){return DATA.optJSONObject("split_switch_rows").optString("title_resource").equals(resource);}
    public static boolean splitSwitch(String resource){JSONArray a=DATA.optJSONObject("split_switch_rows").optJSONArray("switch_resources");for(int i=0;i<a.length();i++)if(a.optString(i).equals(resource))return true;return false;}
    public static boolean navigationForGoal(String goal,String state,String label) {
        JSONObject spec=DATA.optJSONObject("goals").optJSONObject(goal==null?"":goal);
        JSONObject routes=spec==null?null:spec.optJSONObject("navigation");
        if(routes==null)return false;
        String key=DATA.optJSONObject("state_aliases").optString(state==null?"":state,state==null?"":state);
        JSONArray edges=routes.optJSONArray(key);if(edges==null)return false;
        for(int i=0;i<edges.length();i++) {
            JSONArray names=edges.optJSONObject(i).optJSONArray("labels");
            for(int j=0;j<names.length();j++)if(canonical(label).equals(canonical(names.optString(j))))return true;
        }
        return false;
    }
    /** Titles on this goal's authored navigation, rather than its wider export vocabulary. */
    public static boolean routePageTitle(String goal,String title) {
        JSONObject spec=DATA.optJSONObject("goals").optJSONObject(goal==null?"":goal);
        JSONObject routes=spec==null?null:spec.optJSONObject("navigation");
        if(routes==null || title==null)return false;
        for(var states=routes.keys();states.hasNext();)
            if(navigationForGoal(goal,states.next(),title))return true;
        JSONObject pages=spec.optJSONObject("page_titles");
        if(pages!=null)for(var states=pages.keys();states.hasNext();)
            if(pageTitle(goal,states.next(),title))return true;
        return false;
    }
    public static boolean compatible(JSONObject remote) {
        return remote==null || "settings-capabilities.v1".equals(remote.optString("schema_version"))
                && sha256().equals(remote.optString("sha256"));
    }
    public static JSONObject successContract(String goal) {
        JSONObject spec=DATA.optJSONObject("goals").optJSONObject(goal==null?"":goal);
        JSONObject success=spec==null?null:spec.optJSONObject("success");
        try { return success==null?null:new JSONObject(success.toString()); }
        catch(JSONException error){throw new IllegalStateException(error);}
    }
    public static String sha256(){return HASH;}
    private static String computeHash(){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(SettingsCapabilitiesData.json().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
