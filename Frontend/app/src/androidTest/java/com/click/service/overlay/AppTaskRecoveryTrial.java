package com.click.service.overlay;

import android.content.Context;
import android.os.SystemClock;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Runs a previously prepared page request against real public windows and an approved native tap. */
public final class AppTaskRecoveryTrial implements AppTaskController.Listener {
    private final Supplier<ScreenSnapshot> read;
    private final Runnable tap;
    public final AppTaskController controller;
    public volatile JSONObject panel;
    public volatile long firstMatch, verified;
    public int taps, launches;

    public AppTaskRecoveryTrial(Context context, String pkg, String page, Supplier<ScreenSnapshot> read, Runnable tap) throws Exception {
        this.read=read;
        this.tap=tap;
        AppTaskMemory memory=new AppTaskMemory(page+" 화면을 열어줘");
        JSONObject clause=new JSONObject().put("kind","page").put("labels",new JSONArray().put(page)).put("metric","none").put("steps",0);
        memory.installOutcome(new JSONObject().put("seal","a".repeat(64)).put("clauses",new JSONArray().put(clause)));
        controller=new AppTaskController(new AppGuideGateway(context),memory,null,this,true,false,true,AppTaskMetaController.Arm.META);
        controller.enableOutcomes(true);
        controller.targetApp(pkg,"시계");
    }
    public ScreenSnapshot fresh(){return read.get();}
    public boolean canObserve(){return true;}
    public void busy(boolean busy){}
    public boolean inputMatches(String target,String hash){return false;}
    public void failure(Throwable error){throw new AssertionError(error);}
    public boolean execute(ScreenSnapshot screen,JSONObject observation,JSONObject action,String input,String selected){
        if("open_app".equals(action.optString("tool")))launches++;
        assertEquals("tap",action.optString("tool"));
        taps++;tap.run();return true;
    }
    public void present(JSONObject response,ScreenSnapshot screen){
        panel=response;
        if("checking_result".equals(response.optString("status"))&&firstMatch==0)firstMatch=SystemClock.elapsedRealtime();
        JSONObject evidence=response.optJSONObject("result_assessment");
        if(evidence!=null&&"verified".equals(evidence.optString("status"))&&verified==0)verified=SystemClock.elapsedRealtime();
        if(response.optJSONObject("action")!=null)assertNotEquals("open_app",response.optJSONObject("action").optString("tool"));
    }
}
