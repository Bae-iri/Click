package com.click.data.remote;

import android.content.Context;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

/** Manual browsing has its own negotiated contract; settings filters and graph IDs are not bypassed. */
public class CoupangBrowseGateway {
    public static final String PATH="/api/v1/agent/browse/commands";
    public interface Callback {void success(JSONObject response);void failure(Throwable error);}
    private final AuthenticatedHttpClient http;
    private final String path,protocol;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    public CoupangBrowseGateway(Context context){this(context,PATH,"coupang-browse.v1");}
    public CoupangBrowseGateway(Context context,String path,String protocol){http=new AuthenticatedHttpClient(context);this.path=path;this.protocol=protocol;}
    public void send(JSONObject request,Callback callback) {
        executor.execute(()->{
            try {
                JSONObject response=new JSONObject(http.post(path,request.toString(),15000).body);
                validate(request,response,protocol);
                main.post(()->callback.success(response));
            }catch(Exception error){main.post(()->callback.failure(error));}
        });
    }
    public static void validate(JSONObject request,JSONObject response) throws Exception {
        validate(request,response,"coupang-browse.v1");
    }
    public static void validate(JSONObject request,JSONObject response,String protocol) throws Exception {
        boolean snapshot="snapshot".equals(request.getString("command"));
        if(!protocol.equals(response.getString("protocol_version")) || !response.getBoolean("manual_only")
                || !request.getString("session_id").equals(response.getString("session_id"))
                || !request.getString("request_id").equals(response.getString("request_id"))
                || (snapshot?response.getInt("session_version")<request.getInt("expected_version"):
                    response.getInt("session_version")!=request.getInt("expected_version")+1))throw new java.io.IOException("INVALID_BROWSE_RESPONSE");
        if(!response.isNull("target_node_id")) {
            JSONObject screen=request.optJSONObject("screen");boolean found=false;
            JSONArray nodes=screen==null?null:screen.optJSONArray("nodes");
            for(int i=0;nodes!=null && i<nodes.length();i++) {
                JSONObject node=nodes.getJSONObject(i);
                if(response.getString("target_node_id").equals(node.getString("node_id"))
                        && node.getJSONArray("bounds").toString().equals(response.getJSONArray("target_bounds").toString())
                        && screen.getString("observation_id").equals(response.getString("observation_id")))found=true;
            }
            if(!found)throw new java.io.IOException("UNGROUNDED_BROWSE_TARGET");
        }
    }
    public void close(){executor.shutdownNow();}
}
