package com.click.service.overlay;

import com.click.data.remote.CoupangBrowseGateway;
import com.click.data.snapshot.*;
import org.json.*;
import java.util.UUID;

/** Reads app entry once, then follows explicit checks. Never performs app actions. */
public final class CoupangBrowseController {
    public interface Listener {
        void present(JSONObject response,ScreenSnapshot submitted);
        void failed(Throwable error,Runnable retry);
        void busy(boolean value);
        default boolean localGuide(ScreenSnapshot screen) { return false; }
        default boolean canObserve() { return true; }
    }
    private final CoupangBrowseGateway gateway;
    private final CoupangBrowsePrivacy privacy;
    private final Listener listener;
    private String session;
    private int version,generation;
    private boolean busy,closed,terminal;
    private JSONObject guide,action;
    private ScreenSnapshot guidedScreen;
    private ScreenSnapshot entryScreen;
    private boolean entryObserved;
    public CoupangBrowseController(CoupangBrowseGateway gateway,CoupangBrowsePrivacy privacy,Listener listener) {
        this.gateway=gateway;this.privacy=privacy;this.listener=listener;
    }
    public void start(String session,String resolution) {this.session=session;send("start",null,resolution);}
    public void restore(String session,int version) {this.session=session;this.version=version;send("snapshot",null,null);}
    public void observe(ScreenSnapshot screen) {
        if(!closed&&!busy&&!terminal&&listener.canObserve()&&!listener.localGuide(screen)) {
            if(screen!=null && "com.coupang.mobile".equals(screen.packageName))entryObserved=true;
            send("observe",screen,null);
        }
    }
    public void finish() {if(!busy&&!terminal)send("finish",null,null);}
    public boolean terminal(){return terminal;}
    public void screenChanged(ScreenSnapshot current) {
        entryScreen=current;
        if(guidedScreen!=null && (current==null || !guidedScreen.contentFingerprint().equals(current.contentFingerprint())))guide=null;
        observeEntry();
    }
    private void observeEntry() {
        if(entryObserved || closed || busy || terminal || entryScreen==null
                || !"com.coupang.mobile".equals(entryScreen.packageName))return;
        try {
            // Loading trees must not consume the only automatic entry observation.
            if("public".equals(privacy.filter(entryScreen).optString("status")))observe(entryScreen);
        } catch(Exception ignored) { /* Explicit screen check remains available. */ }
    }
    public void tap(String pkg,int window,String node,long occurredAt) {
        if(guide==null || guidedScreen==null || !guidedScreen.packageName.equals(pkg)
                || guidedScreen.activeWindowId!=null && guidedScreen.activeWindowId!=window
                || !java.util.Objects.equals(node,guide.optString("target_node_id")))return;
        try {action=new JSONObject().put("guide_id",guide.getString("guide_id"))
            .put("observation_id",guide.getString("observation_id")).put("node_id",node).put("occurred_at_ms",occurredAt);}
        catch(JSONException ignored){action=null;}
    }
    private void send(String command,ScreenSnapshot screen,String resolution) {
        if(closed||busy)return;
        try {
            JSONObject request=new JSONObject().put("protocol_version","coupang-browse.v1")
                .put("request_id",UUID.randomUUID().toString()).put("session_id",session)
                .put("expected_version",version).put("command",command);
            if(resolution!=null)request.put("goal_resolution_id",resolution);
            if(screen!=null)request.put("screen",privacy.filter(screen));
            if("observe".equals(command)&&action!=null)request.put("action",action);
            deliver(request,screen);
        }catch(Exception error){listener.failed(error,()->send(command,screen,resolution));}
    }
    private void deliver(JSONObject request,ScreenSnapshot screen) {
        if(closed||busy)return;
        busy=true;listener.busy(true);final int owner=generation;
        gateway.send(request,new CoupangBrowseGateway.Callback(){
            public void success(JSONObject response) {
                if(closed||owner!=generation)return;
                busy=false;listener.busy(false);version=response.optInt("session_version");
                terminal=!"IN_PROGRESS".equals(response.optString("status"));action=null;
                guide=response;guidedScreen=screen;listener.present(response,screen);
                observeEntry();
            }
            public void failure(Throwable error) {
                if(closed||owner!=generation)return;
                busy=false;listener.busy(false);guide=null;
                listener.failed(error,()->deliver(request,screen));
            }
        });
    }
    public void close(){closed=true;generation++;guide=action=null;gateway.close();}
}
