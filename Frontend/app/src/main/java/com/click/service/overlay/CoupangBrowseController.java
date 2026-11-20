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
        default ScreenSnapshot freshScreen() { return null; }
        default void settlingPrivateScreen() {}
    }
    private final CoupangBrowseGateway gateway;
    public interface ScreenFilter {JSONObject filter(ScreenSnapshot screen) throws Exception;}
    private final ScreenFilter privacy;
    private final String packageName,protocol;
    private final Listener listener;
    private String session;
    private int version,generation;
    private boolean busy,closed,terminal;
    private JSONObject guide,action,question;
    private ScreenSnapshot guidedScreen;
    private ScreenSnapshot entryScreen;
    private boolean entryObserved;
    private String observedPublicScene;
    private static final long PRIVATE_SETTLE_MS=1200L,PRIVATE_RECHECK_MS=400L;
    private final android.os.Handler observationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private String pendingPrivateKey,pendingPrivateObservation;
    private long pendingPrivateSince;
    private Runnable privateRecheck;
    private ScreenSnapshot verifyingPrivateRead;
    private String responseStatus,responseOutcome,confirmedPrivateReason;
    private String waitingFor;
    private int recoveryReads;
    private Runnable recoveryRead;
    public CoupangBrowseController(CoupangBrowseGateway gateway,CoupangBrowsePrivacy privacy,Listener listener) {
        this(gateway,privacy::filter,"com.coupang.mobile","coupang-browse.v1",listener);
    }
    public CoupangBrowseController(CoupangBrowseGateway gateway,ScreenFilter privacy,String packageName,String protocol,Listener listener) {
        this.gateway=gateway;this.privacy=privacy;this.listener=listener;this.packageName=packageName;this.protocol=protocol;
    }
    public void start(String session,String resolution) {this.session=session;send("start",null,resolution);}
    public void restore(String session,int version) {this.session=session;this.version=version;send("snapshot",null,null);}
    public void observe(ScreenSnapshot screen) {
        if(!closed&&!busy&&!terminal&&listener.canObserve()&&!listener.localGuide(screen)) {
            try {if(!canResume(screen)||deferPrivateInput(screen))return;}
            catch(Exception error){listener.failed(error,()->observe(screen));return;}
            if(screen!=null && packageName.equals(screen.packageName))entryObserved=true;
            send("observe",screen,null);
        }
    }
    public boolean awaitingAnswer(){return question!=null;}
    public void answer(ScreenSnapshot screen,String value,boolean button) {
        if(closed||busy||terminal||!listener.canObserve()||!awaitingAnswer())return;
        try {
            if(!canResume(screen)||deferPrivateInput(screen))return;
            JSONObject request=request("observe",screen,null);
            request.put("question_id",question.getString("id"))
                .put(button?"choice":"answer",value);
            deliver(request,screen);
        }catch(Exception error){listener.failed(error,()->answer(screen,value,button));}
    }
    public void finish() {if(!busy&&!terminal)send("finish",null,null);}
    public boolean terminal(){return terminal;}
    public boolean waitingForUser(){return waitingFor!=null;}
    public boolean ready(){return version>0;}
    /** Recollect on the device; a manual retry never bypasses the privacy filter. */
    public void refreshScreen() {
        if (closed || busy || terminal || !listener.canObserve()) return;
        observedPublicScene = null;
        ScreenSnapshot current = listener.freshScreen();
        if (current != null) screenChanged(current);
    }
    private void scheduleRecoveryRead() {
        if (recoveryRead != null || recoveryReads >= 5 || !"await_public".equals(waitingFor)
                || closed || terminal || !listener.canObserve()) return;
        recoveryRead = () -> {
            recoveryRead = null;
            recoveryReads++;
            refreshScreen();
            scheduleRecoveryRead();
        };
        observationHandler.postDelayed(recoveryRead, recoveryReads == 0 ? 500L : 1500L);
    }
    private void clearRecoveryRead() {
        if (recoveryRead != null) observationHandler.removeCallbacks(recoveryRead);
        recoveryRead = null;
    }
    public boolean guideMatches(ScreenSnapshot current) {
        if(current==null||guidedScreen==null)return false;
        if(!"public-guide.v1".equals(protocol))return guidedScreen.contentFingerprint().equals(current.contentFingerprint());
        try{return nativeScene(guidedScreen).equals(nativeScene(current));}
        catch(Exception invalid){return false;}
    }
    public String status(){return responseStatus;}
    public String outcome(){return responseOutcome;}
    public String handoffReason(){return confirmedPrivateReason;}
    public boolean settlingPrivateScreen(){return pendingPrivateKey!=null;}
    public void screenChanged(ScreenSnapshot current) {
        entryScreen=current;
        try {if(!canResume(current))return;}catch(Exception invalid){return;}
        if(pendingPrivateKey!=null) {
            try {
                JSONObject safe=current==null?null:privacy.filter(current);
                if(safe==null||!"handoff".equals(safe.optString("status"))
                        ||!"credential_input".equals(safe.optString("handoff_reason")))clearPrivateRecheck();
            }catch(Exception invalid){clearPrivateRecheck();}
        }
        if(version>0&&"public-guide.v1".equals(protocol)&&!closed&&!terminal&&listener.canObserve()&&listener.localGuide(current)) {
            clearPrivateRecheck();
            guide=question=null;guidedScreen=null;entryObserved=false;observedPublicScene=null;return;
        }
        if(guidedScreen!=null && !guideMatches(current)){guide=null;observedPublicScene=null;}
        if("public-guide.v1".equals(protocol)&&current!=null&&!closed&&!busy&&!terminal&&listener.canObserve()) {
            try {
                JSONObject safe=privacy.filter(current);
                if("handoff".equals(safe.optString("status")) || action!=null&&guidedScreen!=null
                        &&!guideMatches(current)&&packageName.equals(current.packageName)) {
                    observe(current);return;
                }
            }catch(Exception ignored){}
        }
        observeEntry();
    }
    private void observeEntry() {
        if(closed || busy || terminal || entryScreen==null || !listener.canObserve())return;
        try {
            if(!canResume(entryScreen))return;
            if("public-guide.v1".equals(protocol)&&listener.localGuide(entryScreen)) {
                clearPrivateRecheck();
                guide=question=null;guidedScreen=null;entryObserved=false;observedPublicScene=null;return;
            }
            JSONObject safe=privacy.filter(entryScreen);
            if("public-guide.v1".equals(protocol)) {
                // Direct native taps can change the public choice screen without
                // ever producing a tap on a highlighted target.
                if("handoff".equals(safe.optString("status"))) {
                    observe(entryScreen);return;
                }
                if("public".equals(safe.optString("status")) && packageName.equals(entryScreen.packageName)
                        && !nativeScene(entryScreen).equals(observedPublicScene))observe(entryScreen);
                return;
            }
            if(!entryObserved && packageName.equals(entryScreen.packageName)
                    && "public".equals(safe.optString("status")))observe(entryScreen);
        } catch(Exception ignored) { /* Explicit screen check remains available. */ }
    }
    private boolean canResume(ScreenSnapshot screen) throws Exception {
        if(waitingFor==null)return true;
        JSONObject safe=privacy.filter(screen);
        if(!"public".equals(safe.optString("status")))return false;
        if(!"await_login".equals(waitingFor)||!"unknown".equals(safe.optString("auth_state","unknown")))return true;
        JSONArray nodes=safe.optJSONArray("nodes");
        for(int i=0;nodes!=null&&i<nodes.length();i++) {
            String key=nodes.getJSONObject(i).optString("option_id");
            if(key.startsWith("method_")||key.startsWith("provider_")||"entry_login".equals(key))return true;
        }
        return false;
    }
    /** A partial WebView can briefly expose the home search as an authentication field.
     * Keep it local until fresh native reads prove a stable private surface. */
    private boolean deferPrivateInput(ScreenSnapshot screen) throws Exception {
        if(!"public-guide.v1".equals(protocol))return false;
        JSONObject safe=screen==null?null:privacy.filter(screen);
        boolean provisional=safe!=null&&"handoff".equals(safe.optString("status"))
                &&"credential_input".equals(safe.optString("handoff_reason"));
        if(!provisional){clearPrivateRecheck();return false;}
        long now=android.os.SystemClock.uptimeMillis();
        String key=screen.contentFingerprint();
        if(!key.equals(pendingPrivateKey)) {
            pendingPrivateKey=key;pendingPrivateSince=now;pendingPrivateObservation=screen.observationId;
            guide=question=null;guidedScreen=null;observedPublicScene=null;
            listener.settlingPrivateScreen();
        } else if(screen==verifyingPrivateRead&&now-pendingPrivateSince>=PRIVATE_SETTLE_MS
                &&!java.util.Objects.equals(pendingPrivateObservation,screen.observationId)) {
            confirmedPrivateReason="credential_input";clearPrivateRecheck();return false;
        }
        if(privateRecheck==null) {
            privateRecheck=()->{
                privateRecheck=null;
                refreshPrivateScreen();
            };
            observationHandler.postDelayed(privateRecheck,PRIVATE_RECHECK_MS);
        }
        return true;
    }
    private void refreshPrivateScreen() {
        if(closed||terminal||!listener.canObserve()){clearPrivateRecheck();return;}
        try {
            verifyingPrivateRead=listener.freshScreen();
            if(verifyingPrivateRead!=null)screenChanged(verifyingPrivateRead);
        }catch(RuntimeException unavailable){
            clearPrivateRecheck();listener.failed(unavailable,this::refreshPrivateScreen);
        }finally{verifyingPrivateRead=null;}
    }
    private void clearPrivateRecheck() {
        if(privateRecheck!=null)observationHandler.removeCallbacks(privateRecheck);
        privateRecheck=null;pendingPrivateKey=pendingPrivateObservation=null;pendingPrivateSince=0;
    }
    private String publicScene(JSONObject safe) {
        java.util.List<String> options=new java.util.ArrayList<>();
        JSONArray nodes=safe.optJSONArray("nodes");
        for(int i=0;nodes!=null&&i<nodes.length();i++) {
            JSONObject node=nodes.optJSONObject(i);if(node!=null)options.add(node.optString("option_id")+":"+node.optString("node_id")+":"+node.optJSONArray("bounds"));
        }
        java.util.Collections.sort(options);
        return safe.optString("status")+":"+safe.optString("auth_state","unknown")+":"+safe.optString("package_name")+":"+safe.optInt("width")+":"+safe.optInt("height")+":"+options;
    }
    private String nativeScene(ScreenSnapshot screen) throws Exception {
        return publicScene(privacy.filter(screen))+":"+(screen==null?null:screen.activeWindowId);
    }
    public void tap(String pkg,int window,String node,long occurredAt) {
        if(guide==null || guidedScreen==null || !guidedScreen.packageName.equals(pkg)
                || guidedScreen.activeWindowId!=null && guidedScreen.activeWindowId!=window
                || !java.util.Objects.equals(node,guide.optString("target_node_id")))return;
        try {action=new JSONObject().put("guide_id",guide.getString("guide_id"))
            .put("observation_id",guide.getString("observation_id")).put("node_id",node).put("occurred_at_ms",occurredAt);}
        catch(JSONException ignored){action=null;}
    }
    private JSONObject request(String command,ScreenSnapshot screen,String resolution) throws Exception {
        JSONObject request=new JSONObject().put("protocol_version",protocol)
            .put("request_id",UUID.randomUUID().toString()).put("session_id",session)
            .put("expected_version",version).put("command",command);
        if("public-guide.v1".equals(protocol))request.put("recovery_version","gov24-recovery.v1");
        if(resolution!=null)request.put("goal_resolution_id",resolution);
        if(screen!=null) {
            JSONObject safe=privacy.filter(screen);request.put("screen",safe);
            if("public-guide.v1".equals(protocol)&&com.click.data.local.CandidateDiagnosticLog.enabled())
                android.util.Log.i("ClickAgent","gov24_observe status="+safe.optString("status")
                    +" handoff="+safe.optString("handoff_reason","none")+" privacy="+safe.optString("privacy_reason","unavailable")
                    +" exported_nodes="+(safe.optJSONArray("nodes")==null?0:safe.optJSONArray("nodes").length()));
        }
        if("observe".equals(command)&&action!=null)request.put("action",action);
        return request;
    }
    private void send(String command,ScreenSnapshot screen,String resolution) {
        if(closed||busy)return;
        try {deliver(request(command,screen,resolution),screen);}
        catch(Exception error){listener.failed(error,()->send(command,screen,resolution));}
    }
    private void deliver(JSONObject request,ScreenSnapshot screen) {
        if(closed||busy)return;
        JSONObject safe=request.optJSONObject("screen");
        if("public-guide.v1".equals(protocol) && safe!=null && "public".equals(safe.optString("status")))
            try{observedPublicScene=nativeScene(screen);}catch(Exception invalid){observedPublicScene=null;}
        busy=true;listener.busy(true);final int owner=generation;
        gateway.send(request,new CoupangBrowseGateway.Callback(){
            public void success(JSONObject response) {
                if(closed||owner!=generation)return;
                busy=false;listener.busy(false);version=response.optInt("session_version");
                responseStatus=response.optString("status",null);responseOutcome=response.optString("outcome",null);
                terminal=!"IN_PROGRESS".equals(responseStatus);action=null;
                String subgoal=response.optString("subgoal");
                waitingFor=!terminal&&("await_login".equals(subgoal)||"await_public".equals(subgoal))?subgoal:null;
                if (waitingFor == null || terminal) { clearRecoveryRead(); recoveryReads = 0; }
                else scheduleRecoveryRead();
                JSONObject submitted=request.optJSONObject("screen");
                if(submitted!=null&&"handoff".equals(submitted.optString("status")))
                    confirmedPrivateReason=submitted.optString("handoff_reason",null);
                if(terminal||waitingFor!=null)clearPrivateRecheck();
                guide=response;question=response.optJSONObject("question");guidedScreen=screen;listener.present(response,screen);
                observeEntry();
            }
            public void failure(Throwable error) {
                if(closed||owner!=generation)return;
                busy=false;listener.busy(false);guide=null;
                listener.failed(error,()->deliver(request,screen));
            }
        });
    }
    public void close(){closed=true;generation++;clearPrivateRecheck();clearRecoveryRead();guide=action=question=null;gateway.close();}
}
