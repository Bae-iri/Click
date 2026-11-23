package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Account-scoped choices only. Never stores requests, screen contents or inferred abilities. */
public final class GuidancePreferences {
    public enum Feedback { WRONG_TARGET, UNCLEAR, SLOWER, MORE_DETAIL, LESS_DETAIL }
    private final SharedPreferences prefs;
    public GuidancePreferences(Context context) {
        String user=new AuthTokenStore(context).userId();
        String scope=user==null||user.isEmpty()?"guest":UUID.nameUUIDFromBytes(user.getBytes(StandardCharsets.UTF_8)).toString();
        prefs=context.getSharedPreferences("guidance_choices_"+scope,Context.MODE_PRIVATE);
    }
    public GuidancePreferences(SharedPreferences prefs){this.prefs=prefs;}
    public boolean remembers(){return prefs.getBoolean("remember",false);}
    public float speechRate(){return remembers()&&prefs.getBoolean("slower",false)?.75f:.92f;}
    public boolean detailed(){return remembers()&&prefs.getBoolean("detail",false);}
    public void remember(boolean enabled){
        if(!enabled)clearChoices();
        prefs.edit().putBoolean("remember",enabled).apply();
    }
    public void record(Feedback feedback){
        if(!remembers())return;
        var edit=prefs.edit();
        edit.putInt("feedback_"+feedback.name(),prefs.getInt("feedback_"+feedback.name(),0)+1);
        if(feedback==Feedback.SLOWER)edit.putBoolean("slower",true);
        if(feedback==Feedback.MORE_DETAIL||feedback==Feedback.UNCLEAR)edit.putBoolean("detail",true);
        if(feedback==Feedback.LESS_DETAIL)edit.putBoolean("detail",false);
        edit.apply();
    }
    public void clearChoices(){
        var edit=prefs.edit().remove("slower").remove("detail");
        for(Feedback feedback:Feedback.values())edit.remove("feedback_"+feedback.name());
        edit.apply();
    }
    public String summary(){
        if(!remembers())return "선택은 이번 작업에만 반영해요.";
        return (speechRate()<.9f?"천천히 읽기 · ":"")+(detailed()?"자세한 설명":"기본 설명")+"을 다음 안내에도 반영해요.";
    }
    public boolean hasSeenHomeTutorial() { return prefs.getBoolean("clue_home_tutorial_seen", false); }
    public void markHomeTutorialSeen() { prefs.edit().putBoolean("clue_home_tutorial_seen", true).apply(); }
}
