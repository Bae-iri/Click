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
    private boolean recoveryChecked;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    public CoupangBrowseGateway(Context context){this(context,PATH,"coupang-browse.v1");}
    public CoupangBrowseGateway(Context context,String path,String protocol){http=new AuthenticatedHttpClient(context);this.path=path;this.protocol=protocol;}
    public void send(JSONObject request,Callback callback) {
        executor.execute(()->{
            try {
                if("public-guide.v1".equals(protocol)&&!recoveryChecked) {
                    if(!supportsRecovery(new JSONObject(http.get("/openapi.json").body)))
                        throw new java.io.IOException("GOV24_SERVER_UPDATE_REQUIRED");
                    recoveryChecked=true;
                }
                // The opt-in Government24 planner may wait on a model call (25s budget).
                JSONObject response=new JSONObject(http.post(path,request.toString(),
                    "public-guide.v1".equals(protocol)?45000:15000).body);
                validate(request,response,protocol);
                main.post(()->callback.success(response));
            }catch(Exception error){main.post(()->callback.failure(error));}
        });
    }
    public static void validate(JSONObject request,JSONObject response) throws Exception {
        validate(request,response,"coupang-browse.v1");
    }
    public static void validate(JSONObject request,JSONObject response,String protocol) throws Exception {
        if("gov24-recovery.v1".equals(request.optString("recovery_version"))
                &&!"gov24-recovery.v1".equals(response.optString("recovery_version")))throw new java.io.IOException("GOV24_SERVER_UPDATE_REQUIRED");
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
    public static boolean supportsRecovery(JSONObject document) {
        JSONObject components=document.optJSONObject("components"),schemas=components==null?null:components.optJSONObject("schemas");
        JSONObject response=schemas==null?null:schemas.optJSONObject("PublicGuideResponse");
        JSONObject request=schemas==null?null:schemas.optJSONObject("PublicGuideRequest");
        JSONObject properties=response==null?null:response.optJSONObject("properties");
        JSONArray subgoals=properties==null||properties.optJSONObject("subgoal")==null?null:properties.optJSONObject("subgoal").optJSONArray("enum");
        java.util.Set<String> values=new java.util.HashSet<>();
        for(int i=0;subgoals!=null&&i<subgoals.length();i++)values.add(subgoals.optString(i));
        JSONObject screen=schemas==null?null:schemas.optJSONObject("PublicGuideScreen");
        return values.containsAll(java.util.Set.of("await_login","await_public"))&&properties!=null&&declaresRecovery(properties.optJSONObject("recovery_version"))
            &&request!=null&&request.optJSONObject("properties")!=null&&declaresRecovery(request.optJSONObject("properties").optJSONObject("recovery_version"))
            &&screen!=null&&screen.optJSONObject("properties")!=null&&screen.optJSONObject("properties").has("auth_state");
    }
    private static boolean declaresRecovery(JSONObject schema) {
        if(schema==null)return false;
        if("gov24-recovery.v1".equals(schema.optString("const")))return true;
        JSONArray values=schema.optJSONArray("enum");
        for(int i=0;values!=null&&i<values.length();i++)if("gov24-recovery.v1".equals(values.optString(i)))return true;
        JSONArray choices=schema.optJSONArray("anyOf");
        for(int i=0;choices!=null&&i<choices.length();i++)if(declaresRecovery(choices.optJSONObject(i)))return true;
        return false;
    }
    public void close(){executor.shutdownNow();}
}
