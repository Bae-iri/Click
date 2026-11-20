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
        return resolve(goal,withAliases(apps));
    }
    /** Korean names people say for apps whose launcher label is English. Keyed by package, never by label. */
    private static final Map<String,List<String>> ALIASES=Map.ofEntries(
        Map.entry("com.google.android.youtube",List.of("유튜브")),
        Map.entry("com.google.android.apps.youtube.music",List.of("유튜브 뮤직","유튜브뮤직")),
        Map.entry("com.android.chrome",List.of("크롬")),
        Map.entry("com.google.android.gm",List.of("지메일")),
        Map.entry("com.google.android.apps.maps",List.of("구글 지도","구글지도","구글 맵","구글맵")),
        Map.entry("com.google.android.apps.photos",List.of("구글 포토","구글포토")),
        Map.entry("com.android.vending",List.of("플레이 스토어","플레이스토어")),
        Map.entry("com.instagram.android",List.of("인스타그램","인스타")),
        Map.entry("com.netflix.mediaclient",List.of("넷플릭스")),
        Map.entry("com.spotify.music",List.of("스포티파이")),
        Map.entry("com.facebook.katana",List.of("페이스북")));
    static List<RequestedApp> withAliases(List<RequestedApp> apps){
        List<RequestedApp> all=new ArrayList<>(apps);
        for(RequestedApp app:apps)for(String alias:ALIASES.getOrDefault(app.packageName,List.of()))all.add(new RequestedApp(app.packageName,alias));
        return all;
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
