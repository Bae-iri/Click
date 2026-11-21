package com.click.data.snapshot;

import org.json.*;
import com.click.generated.ScreenEnvironmentData;
import java.util.*;

/** Shared environment/recovery definitions never grant target or consent permission. */
public final class ScreenEnvironmentPolicy {
    private static final JSONObject DATA=load();
    private ScreenEnvironmentPolicy() {}
    private static JSONObject load(){try{return new JSONObject(ScreenEnvironmentData.json());}catch(JSONException error){throw new IllegalStateException(error);}}
    public static boolean contains(String key,String value) {
        if(value==null)return false;JSONArray data=DATA.optJSONArray(key);if(data==null)return false;
        for(int i=0;i<data.length();i++)if(value.equals(data.optString(i)))return true;return false;
    }
    public static boolean token(String key,String value){
        if(value==null)return false;String text=value.toLowerCase(Locale.ROOT);JSONArray tokens=DATA.optJSONArray(key);
        for(int i=0;i<tokens.length();i++)if(text.contains(tokens.optString(i)))return true;return false;
    }
    public static String activity(String pkg,String name){
        if(!contains("settings_packages",pkg) || name==null)return "UNKNOWN";
        if(contains("settings_home_activities",name))return "HOME";
        if(contains("settings_subpage_activities",name) || (name.startsWith("com.android.settings.") || name.startsWith("com.samsung.android.settings.")) && (name.endsWith("Activity") || name.contains("Settings$")))return "SUBPAGE";
        return "UNKNOWN";
    }
    static JSONObject controlSurfaces(){return DATA.optJSONObject("native_control_surfaces");}
    public static boolean manualApp(String setting,String pkg){
        JSONArray values=DATA.optJSONObject("manual_packages_by_setting").optJSONArray(setting==null?"":setting);
        if(values==null || pkg==null)return false;
        for(int i=0;i<values.length();i++)if(pkg.equals(values.optString(i)))return true;return false;
    }
    /** Some Settings rows open a separate system app, e.g. Samsung's wallpaper picker. */
    public static boolean settingsCompanion(String pkg) {
        Iterator<String> settings = DATA.optJSONObject("manual_packages_by_setting").keys();
        while (settings.hasNext()) if (manualApp(settings.next(), pkg)) return true;
        return false;
    }
    public static JSONObject recovery(String reason){
        JSONObject rule=DATA.optJSONObject("recovery").optJSONObject(reason==null?"":reason);
        try{return rule==null?new JSONObject():new JSONObject(rule.toString());}catch(JSONException e){throw new IllegalStateException(e);}
    }
}
