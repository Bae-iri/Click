package com.click.service.overlay;

import com.click.data.local.GuidancePreferences;

/** Explicit feedback adapts presentation, never permissions, targets or inferred abilities. */
public final class GuidancePersonalization {
    private final GuidancePreferences preferences;
    private float rate;
    private boolean detailed;
    private int repeats;
    public GuidancePersonalization(GuidancePreferences preferences){this.preferences=preferences;beginTask();}
    public void beginTask(){rate=preferences.speechRate();detailed=preferences.detailed();repeats=0;}
    public float speechRate(){return rate;}
    public boolean detailed(){return detailed;}
    public boolean remembers(){return preferences.remembers();}
    public boolean repeated(){
        if(!preferences.remembers()){repeats=0;return false;}
        if(detailed||++repeats<2)return false;
        detailed=true;preferences.record(GuidancePreferences.Feedback.MORE_DETAIL);return true;
    }
    public String feedback(GuidancePreferences.Feedback feedback,boolean remember){
        preferences.remember(remember);
        if(feedback==GuidancePreferences.Feedback.WRONG_TARGET)return "이번 작업의 자동 진행을 멈췄어요. ‘다시 확인’을 누르면 현재 화면부터 확인해요.";
        preferences.record(feedback);
        String effect;
        if(feedback==GuidancePreferences.Feedback.SLOWER){rate=.75f;effect="안내를 천천히 읽어요.";}
        else {detailed=feedback!=GuidancePreferences.Feedback.LESS_DETAIL;effect=detailed?"안내 이유와 확인할 내용을 펼쳐 보여요.":"핵심 안내를 먼저 보여요. 이유는 필요할 때 펼쳐 볼 수 있어요.";}
        return effect+(remember?" 이 기기에 기억해 다음 안내에도 반영해요.":" 이번 작업에만 적용해요.");
    }
}
