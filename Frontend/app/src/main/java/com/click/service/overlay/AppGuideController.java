package com.click.service.overlay;

import android.os.*;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.*;
import org.json.*;
import java.util.*;

/** Any app, one grounded manual step. Unchanged screens never trigger repeated model calls. */
public final class AppGuideController {
    public interface Listener {
        ScreenSnapshot fresh();
        boolean canObserve();
        void busy(boolean busy);
        void present(JSONObject result,ScreenSnapshot screen);
        void failure(Throwable error);
    }
    private final AppGuideGateway gateway;
    private final Listener listener;
    private final String goal;
    private final AppGuidePrivacy privacy=new AppGuidePrivacy();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String submitted;
    private boolean busy,closed;
    private int steps;
    private final ArrayDeque<String> previous=new ArrayDeque<>();
    private final Runnable refresh=()->observe(false);
    public AppGuideController(AppGuideGateway gateway,String goal,Listener listener){this.gateway=gateway;this.goal=goal;this.listener=listener;}
    public void screenChanged(ScreenSnapshot screen){if(!closed){handler.removeCallbacks(refresh);handler.postDelayed(refresh,300);}}
    public boolean matches(ScreenSnapshot screen){return screen!=null&&submitted!=null&&submitted.equals(screen.contentFingerprint());}
    public void refresh(){observe(true);}
    private void observe(boolean force) {
        if(closed||busy||!listener.canObserve())return;
        ScreenSnapshot screen=listener.fresh();
        if(screen==null)return;
        String fingerprint=screen.contentFingerprint();
        if(!force&&fingerprint.equals(submitted))return;
        submitted=fingerprint;
        try {
            JSONObject safe=privacy.filter(screen);
            if(!"public".equals(safe.getString("status"))) {
                listener.present(new JSONObject().put("status","needs_input").put("message",
                    "sensitive".equals(safe.getString("status"))?"입력·인증은 직접 진행한 뒤 현재 화면을 다시 확인해 주세요.":
                    "도움이 필요한 앱을 열고 현재 화면 확인을 눌러주세요."),screen);return;
            }
            if(steps>=32){listener.present(new JSONObject().put("status","needs_input").put("message","안내 횟수에 도달했어요. 안내를 끝내고 새 요청으로 시작해 주세요."),screen);return;}
            JSONObject request=new JSONObject().put("protocol_version","app-guide.v1").put("request_id",UUID.randomUUID().toString())
                .put("goal",goal).put("screen",safe).put("previous_targets",new JSONArray(previous));
            busy=true;steps++;listener.busy(true);
            gateway.send(request,new AppGuideGateway.Callback(){
                public void success(JSONObject response){
                    if(closed)return;busy=false;listener.busy(false);
                    if(!listener.canObserve())return;
                    ScreenSnapshot current=listener.fresh();
                    if(current==null||!fingerprint.equals(current.contentFingerprint())){handler.post(refresh);return;}
                    String id=response.optString("target_node_id","");
                    JSONArray nodes=safe.optJSONArray("nodes");
                    for(int i=0;nodes!=null&&i<nodes.length();i++)if(id.equals(nodes.optJSONObject(i).optString("node_id"))) {
                        previous.addLast(nodes.optJSONObject(i).optString("label"));while(previous.size()>4)previous.removeFirst();break;
                    }
                    listener.present(response,screen);
                }
                public void failure(Throwable error){if(!closed){busy=false;listener.busy(false);listener.failure(error);}}
            });
        }catch(Exception error){listener.failure(error);}
    }
    public void close(){closed=true;handler.removeCallbacks(refresh);gateway.close();}
}
