package com.click.data.remote;

import android.content.Context;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

public final class AppGuideGateway {
    public static final String PATH="/api/v1/agent/app-guide/step";
    public interface Callback {void success(JSONObject result);void failure(Throwable error);}
    private final AuthenticatedHttpClient http;
    private final RequestLifecycle lifecycle=new RequestLifecycle();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    public AppGuideGateway(Context context){http=new AuthenticatedHttpClient(context);}
    public void send(JSONObject request,Callback callback) {
        executor.execute(()->{
            try(RequestBudget budget=new RequestBudget(SystemClock.elapsedRealtime()+15000,lifecycle)) {
                JSONObject result=new JSONObject(http.post(PATH,request.toString(),12000).body);
                validate(request,result);
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
    public void close(){lifecycle.close();executor.shutdownNow();}
}
