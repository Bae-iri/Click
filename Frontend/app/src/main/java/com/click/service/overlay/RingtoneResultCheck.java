package com.click.service.overlay;

import android.content.Context;
import android.provider.Settings;
import com.click.data.snapshot.AppTaskObservation;

/** Local change evidence only. Ringtone URIs and names never enter a request, log or checkpoint. */
final class RingtoneResultCheck {
    private String baseline,pending;private long pendingAt;private boolean consumed;
    static String read(Context context){
        try{return AppTaskObservation.hash(String.valueOf(Settings.System.getString(context.getContentResolver(),Settings.System.RINGTONE))+"\n"
            +String.valueOf(Settings.System.getString(context.getContentResolver(),"ringtone_2")));}
        catch(RuntimeException unavailable){return null;}
    }
    void bind(String value){baseline=value;pending=null;pendingAt=0;consumed=false;}
    boolean active(){return baseline!=null&&!consumed;}
    boolean changed(String value,long now){
        if(consumed||baseline==null||value==null||baseline.equals(value)){pending=null;return false;}
        if(!value.equals(pending)){pending=value;pendingAt=now;return false;}
        return now-pendingAt>=350;
    }
    void consume(){consumed=true;}
}
