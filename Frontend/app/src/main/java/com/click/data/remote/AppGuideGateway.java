package com.click.data.remote;

import android.content.Context;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

public final class AppGuideGateway {
    public static final String PATH="/api/v1/agent/app-guide/step";
    public static final String TASK_PATH="/api/v1/agent/app-guide/task";
    public interface Callback {void success(JSONObject result);void failure(Throwable error);}
    private final AuthenticatedHttpClient http;
    private final RequestLifecycle lifecycle=new RequestLifecycle();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    public AppGuideGateway(Context context){http=new AuthenticatedHttpClient(context);}
    public void send(JSONObject request,Callback callback) {
        executor.execute(()->{
            try(RequestBudget budget=new RequestBudget(SystemClock.elapsedRealtime()+15000,lifecycle)) {
                boolean task="app-task.v2".equals(request.optString("protocol_version"));
                JSONObject result=new JSONObject(http.post(task?TASK_PATH:PATH,request.toString(),12000).body);
                if(task)validateTask(request,result);else validate(request,result);
                lifecycle.post(main,()->callback.success(result));
            }catch(Exception error){lifecycle.post(main,()->callback.failure(error));}
        });
    }
    public static void validate(JSONObject request,JSONObject result)throws Exception {
        JSONObject screen=request.getJSONObject("screen");
        if(!"app-guide.v1".equals(result.getString("protocol_version"))||!result.getBoolean("manual_only")
                ||!request.getString("request_id").equals(result.getString("request_id"))
                ||!screen.getString("observation_id").equals(result.getString("observation_id")))throw new java.io.IOException("INVALID_APP_GUIDE_RESPONSE");
        if(!result.isNull("target_node_id")) {
            JSONArray nodes=screen.getJSONArray("nodes");boolean found=false;
            for(int i=0;i<nodes.length();i++) {
                JSONObject node=nodes.getJSONObject(i);
                if(result.getString("target_node_id").equals(node.getString("node_id"))
                        &&node.getJSONArray("bounds").toString().equals(result.getJSONArray("target_bounds").toString()))found=true;
            }
            if(!found||!"guide".equals(result.getString("status")))throw new java.io.IOException("UNGROUNDED_APP_GUIDE_TARGET");
        }
    }
    public static void validateTask(JSONObject request,JSONObject result)throws Exception {
        JSONObject screen=request.getJSONObject("screen");
        if(!"app-task.v2".equals(result.getString("protocol_version"))||!result.getBoolean("requires_confirmation")
                ||!request.getString("request_id").equals(result.getString("request_id"))
                ||!request.getJSONObject("state").getString("task_id").equals(result.getString("task_id"))
                ||!screen.getString("observation_id").equals(result.getString("observation_id"))
                ||!screen.getString("fingerprint").equals(result.getString("fingerprint")))throw new java.io.IOException("INVALID_APP_TASK_RESPONSE");
        JSONObject action=result.optJSONObject("action");if(action==null)return;
        if(!"proposed".equals(result.getString("status"))||action.optString("id").isEmpty()
                ||!java.util.Set.of("tap","set_text","scroll_forward","scroll_backward","back","open_app").contains(action.optString("tool"))
                ||!com.click.service.overlay.AppTaskPolicy.validExpectation(action))throw new java.io.IOException("INVALID_APP_TASK_ACTION");
        String tool=action.getString("tool");
        if("open_app".equals(tool)){
            JSONArray apps=request.optJSONArray("apps");boolean selected=false;
            for(int i=0;apps!=null&&i<apps.length();i++)if(action.optString("package").equals(apps.getJSONObject(i).getString("package")))selected=true;
            if(!selected)throw new java.io.IOException("UNSELECTED_APP");return;
        }
        if("back".equals(tool))return;
        JSONObject node=com.click.data.snapshot.AppTaskObservation.find(screen,action.optString("target_id"));
        if(node==null||!node.getJSONArray("bounds").toString().equals(String.valueOf(action.optJSONArray("bounds"))))throw new java.io.IOException("UNGROUNDED_APP_TASK_TARGET");
        if("tap".equals(tool)&&!node.optBoolean("clickable")||"set_text".equals(tool)&&!node.optBoolean("editable")
                ||tool.startsWith("scroll_")&&!node.optBoolean("scrollable"))throw new java.io.IOException("UNSUPPORTED_APP_TASK_TOOL");
    }
    public void close(){lifecycle.close();executor.shutdownNow();}
}
