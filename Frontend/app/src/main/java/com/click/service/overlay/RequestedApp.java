package com.click.service.overlay;

import android.content.Context;
import android.content.Intent;
import java.util.*;
import java.util.regex.Pattern;

/** Resolve an explicitly named app against launchable apps actually installed on this device. */
public final class RequestedApp {
    public final String packageName,label;
    public RequestedApp(String packageName,String label){this.packageName=packageName;this.label=label;}
    public static RequestedApp find(Context context,String goal){
        var pm=context.getPackageManager();List<RequestedApp> apps=new ArrayList<>();
        for(var app:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0))
            if(app.activityInfo!=null&&!context.getPackageName().equals(app.activityInfo.packageName))
                apps.add(new RequestedApp(app.activityInfo.packageName,String.valueOf(app.loadLabel(pm))));
        return resolve(goal,apps);
    }
    static String shortName(String label){return label.split("\\s*[-–—:(（]",2)[0].trim();}
    static RequestedApp resolve(String goal,List<RequestedApp> apps){
        if(goal==null)return null;RequestedApp best=null;int length=0;boolean ambiguous=false;
        for(RequestedApp app:apps){
            String name=shortName(app.label);if(name.length()<2)continue;
            Pattern mention=Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(name)+"(?=$|\\s|에서|으로|을|를|에|켜|열)",Pattern.CASE_INSENSITIVE);
            if(!mention.matcher(goal).find())continue;
            if(name.length()>length){best=app;length=name.length();ambiguous=false;}
            else if(name.length()==length&&best!=null&&!best.packageName.equals(app.packageName))ambiguous=true;
        }
        return ambiguous?null:best;
    }
}
